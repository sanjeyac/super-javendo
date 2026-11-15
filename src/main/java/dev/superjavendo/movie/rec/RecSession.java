package dev.superjavendo.movie.rec;

import dev.superjavendo.Snes;
import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.movie.Movie;
import dev.superjavendo.movie.Movies;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The model of the Rec editor: a run being built or corrected, frame by frame.
 *
 * <p><b>Frames.</b> {@code state(k)} is the console after {@code k} frames. The <b>frontier</b> is the number of frames
 * emulated so far; frames at or beyond it only have planned inputs. Changing the input of frame {@code F} below the
 * frontier makes the results of frames {@code F..frontier-1} stale: the first stale frame is {@link #firstInvalid()} and
 * the greenzone loses the states after {@code F}. With {@link Policy#KEEP} the later inputs stay and
 * {@link #reemulate} replays them; with {@link Policy#CUT} they are deleted and the frontier goes back to {@code F}.
 * Frames {@code [0, firstInvalid)} are valid and the playhead can only be moved inside {@code [0, firstInvalid]}.
 *
 * <p>Not thread-safe: all methods run on one emulation thread; other threads read {@link #view()} snapshots. Battery RAM
 * is cleared for the session (a run starts from power-on, as a movie does) and put back by {@link #close()}.
 */
public final class RecSession implements AutoCloseable {
    public enum Policy { KEEP, CUT }

    /** Everything the UI needs, immutable and safe to read from any thread. */
    public record View(int frontier, int playhead, int firstInvalid, int[] video, int videoWidth, int videoHeight,
                       int pad1, int pad2, int[] ram, BitSet lag, BitSet states, InputTimeline.Frozen inputs,
                       boolean canUndo, boolean canRedo, Policy policy) {
        /** Frames waiting to be re-emulated. */
        public int pending() { return frontier - firstInvalid; }
    }

    private final Cartridge cart;
    private final byte[] sramBackup;
    private Snes snes;
    public final InputTimeline timeline;
    private final Greenzone gz;
    private int frontier, firstInvalid, playhead;
    private int consoleFrame;
    private boolean consoleValid;
    private Policy policy = Policy.KEEP;
    private final BitSet lag = new BitSet();
    private int[] video = new int[0];
    private int videoW, videoH;
    private int[] watch = new int[0], watchValues = new int[0];

    public RecSession(Cartridge cart, InputTimeline timeline, long greenzoneBytes) {
        this.cart = cart;
        this.timeline = timeline;
        this.gz = new Greenzone(greenzoneBytes);
        this.sramBackup = cart.sram.clone();
        Arrays.fill(cart.sram, (byte) 0);
        this.snes = new Snes(cart);
        gz.put(0, snes.snapshot());
        consoleFrame = 0;
        consoleValid = true;
    }

    @Override public void close() { System.arraycopy(sramBackup, 0, cart.sram, 0, sramBackup.length); }

    // ------------------------------------------------------------------ state

    public int frontier() { return frontier; }
    public int playhead() { return playhead; }
    public int firstInvalid() { return firstInvalid; }
    public boolean hasPending() { return firstInvalid < frontier; }
    public Policy policy() { return policy; }
    public void policy(Policy p) { policy = p; }
    public Greenzone greenzone() { return gz; }
    public boolean isLag(int frame) { return lag.get(frame); }

    /** Hash of the whole console at the playhead (for tests and for comparing runs). */
    public int consoleHash() { ensureConsoleAt(playhead); return snes.stateHash(); }

    /** Addresses of work RAM ({@code $0000..$1FFFF}) whose values are shown after every move. */
    public void watch(int... addresses) { watch = addresses.clone(); watchValues = new int[watch.length]; readWatch(); }

    public View view() {
        return new View(frontier, playhead, firstInvalid, video, videoW, videoH, timeline.mask(0, playhead), timeline.mask(1, playhead),
                watchValues.clone(), (BitSet) lag.clone(), gz.frames(), timeline.freeze(), timeline.canUndo(), timeline.canRedo(), policy);
    }

    // ------------------------------------------------------------------ moving

    /** Emulates the frame at the frontier with its planned inputs. False while frames wait for re-emulation. */
    public boolean advance() {
        if (hasPending()) return false;
        ensureConsoleAt(frontier);
        runFrame(frontier);
        frontier++;
        firstInvalid = frontier;
        gz.put(frontier, snes.snapshot());
        gz.enforce(frontier);
        playhead = frontier;
        captureVideo();
        return true;
    }

    /** Moves the playhead to {@code frame} (inside the valid range) and shows its picture. */
    public boolean seek(int frame) {
        if (frame < 0 || frame > firstInvalid) return false;
        renderAt(frame);
        return true;
    }

    /**
     * Replays the kept inputs from the first stale frame up to the old frontier, headless. {@code progress} receives the
     * number of frames done; return true from {@code cancelled} to stop early (what was done stays valid).
     */
    public void reemulate(IntConsumer progress, BooleanSupplier cancelled) {
        if (!hasPending()) return;
        int from = Math.max(0, gz.nearestAtOrBefore(firstInvalid));   // the snapshot at the stale frame itself may have been thinned out
        restoreTo(from);
        for (int f = from; f < frontier; f++) {
            if (cancelled != null && cancelled.getAsBoolean()) { firstInvalid = f; gz.put(f, snes.snapshot()); renderAt(Math.min(playhead, firstInvalid)); return; }
            runFrame(f);
            if (f + 1 >= frontier - Greenzone.DENSE || (f + 1) % Greenzone.SPARSE == 0) gz.put(f + 1, snes.snapshot());
            if (((f + 1) & 255) == 0) gz.enforce(frontier);
            if (progress != null) progress.accept(f + 1 - from);
        }
        gz.put(frontier, snes.snapshot());
        gz.enforce(frontier);
        firstInvalid = frontier;
        playhead = frontier;
        captureVideo();
    }

    public void reemulate() { reemulate(null, null); }

    /** Deletes every input after the first stale frame and moves the frontier back to it ("Cut here"). */
    public void cut() {
        if (!hasPending()) return;
        int f = firstInvalid;
        timeline.truncateAfter(f);
        moveFrontierTo(f);
    }

    // ------------------------------------------------------------------ editing (each call is one undo step)

    public void toggle(int pad, int frame, int button) { edit(() -> timeline.toggle(pad, frame, button)); }
    public void set(int pad, int frame, int button, boolean on) { edit(() -> timeline.set(pad, frame, button, on)); }
    public void setRange(int pad, int from, int to, int button, boolean on) { edit(() -> timeline.setRange(pad, from, to, button, on)); }
    public void clearRange(int pad, int from, int to) { edit(() -> timeline.clearRange(pad, from, to)); }
    public void paste(int pad, int at, int[] masks) { edit(() -> timeline.paste(pad, at, masks)); }
    public void insert(int frame) { edit(() -> timeline.insert(frame)); }
    public void delete(int frame) { edit(() -> timeline.delete(frame)); }

    public boolean undo() { int lo = timeline.undo(); stale(lo); return lo != InputTimeline.NONE; }
    public boolean redo() { int lo = timeline.redo(); stale(lo); return lo != InputTimeline.NONE; }

    private void edit(IntSupplier op) {
        int lo = timeline.group(() -> {
            int f = op.getAsInt();
            if (policy == Policy.CUT && f != InputTimeline.NONE && f < frontier) timeline.truncateAfter(f);
            return f;
        });
        if (lo == InputTimeline.NONE || lo >= frontier) return;        // only the plan changed
        if (policy == Policy.CUT) moveFrontierTo(lo); else stale(lo);
    }

    /** The results from frame {@code first} on are stale (inputs there changed). */
    private void stale(int first) {
        if (first == InputTimeline.NONE || first >= frontier) return;
        firstInvalid = Math.min(firstInvalid, first);
        gz.invalidateFrom(first + 1);
        if (consoleFrame > first) consoleValid = false;
        settlePlayhead();
    }

    /** Makes sure there is a snapshot exactly at {@code frame} (thinning may have removed it). */
    private void materialize(int frame) {
        if (gz.has(frame)) return;
        restoreTo(gz.nearestAtOrBefore(frame));
        for (int f = consoleFrame; f < frame; f++) runFrame(f);
        gz.put(frame, snes.snapshot());
    }

    private void moveFrontierTo(int f) {
        materialize(f);
        frontier = f;
        firstInvalid = Math.min(firstInvalid, f);
        gz.invalidateFrom(f + 1);
        lag.clear(f, Math.max(f, lag.length()));
        if (consoleFrame > f) consoleValid = false;
        settlePlayhead();
    }

    private void settlePlayhead() {
        int p = Math.min(playhead, firstInvalid);
        if (p != playhead || !consoleValid) renderAt(p);
    }

    // ------------------------------------------------------------------ saving

    /** The emulated part of the run as a movie (planned frames past the frontier are dropped). */
    public Movie toMovie(String label) {
        if (frontier == 0) throw new IllegalStateException("Nothing recorded yet");
        if (hasPending()) throw new IllegalStateException("Re-emulate the edited frames first");
        int ph = playhead;
        renderAt(frontier);
        Movie m = timeline.toMovie(Movie.crcOf(snes), cart.title, frontier);
        m.endHash = Movie.pictureHash(snes);
        m.label = label == null ? "" : label;
        renderAt(ph);
        return m;
    }

    public Path save(Movies store, String label) throws IOException {
        Movie m = toMovie(label);
        Path f = store.newFile(m.romCrc);
        m.save(f);
        return f;
    }

    // ------------------------------------------------------------------ the console

    private void runFrame(int f) {
        int a = timeline.mask(0, f), b = timeline.mask(1, f);
        if ((a & Movie.RESET) != 0) snes = new Snes(cart);          // a recorded reset
        snes.joypad.setButtons(0, a);
        snes.joypad.setButtons(1, b);
        snes.runFrame();
        lag.set(f, snes.lastFrameLagged);
        consoleFrame = f + 1;
    }

    private void restoreTo(int k) {
        byte[] raw = gz.get(k);
        if (raw == null) throw new IllegalStateException("No snapshot for frame " + k);
        snes.restore(raw);
        consoleFrame = k;
        consoleValid = true;
    }

    private void ensureConsoleAt(int k) {
        if (consoleValid && consoleFrame == k) return;
        restoreTo(k);
    }

    /** Puts the console at {@code frame} having just run the frame before it, so the picture is real; moves the playhead there. */
    private void renderAt(int frame) {
        if (frame == 0) {
            restoreTo(0);
            video = new int[0]; videoW = videoH = 0;
        } else {
            int k = gz.nearestAtOrBefore(frame - 1);
            restoreTo(k);
            for (int f = k; f < frame; f++) runFrame(f);
            captureVideo();
        }
        playhead = frame;
        readWatch();
    }

    private void captureVideo() {
        var ppu = snes.bus.ppu;
        videoW = ppu.outputWidth(); videoH = ppu.outputHeight();
        video = Arrays.copyOf(ppu.outputPixels(), videoW * videoH);
        readWatch();
    }

    private void readWatch() {
        for (int i = 0; i < watch.length; i++) watchValues[i] = snes.bus.read(0x7E0000 + (watch[i] & 0x1FFFF)) & 0xFF;
    }
}
