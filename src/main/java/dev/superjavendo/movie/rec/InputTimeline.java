package dev.superjavendo.movie.rec;

import dev.superjavendo.input.Joypad;
import dev.superjavendo.movie.Movie;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.IntSupplier;

/**
 * The inputs of a run: one 16-bit pad mask per frame for each of the two pads, with undo/redo.
 * Every change is a "splice" (remove some frames, insert others), so one mechanism covers toggle, range, paste,
 * insert, delete and cut, and each can be undone exactly. Every editing method returns the first frame whose input
 * changed, or {@link #NONE} if nothing did, so the session knows from where its emulated results are stale.
 * Frames past {@link #length()} read as 0. Not thread-safe: use {@link #freeze()} to hand a copy to another thread.
 */
public final class InputTimeline {
    public static final int NONE = -1;
    /** The twelve buttons in piano roll order. */
    public static final int[] BUTTONS = {Joypad.B, Joypad.Y, Joypad.A, Joypad.X, Joypad.L, Joypad.R,
            Joypad.UP, Joypad.DOWN, Joypad.LEFT, Joypad.RIGHT, Joypad.START, Joypad.SELECT};
    public static final String[] BUTTON_NAMES = {"B", "Y", "A", "X", "L", "R", "Up", "Down", "Left", "Right", "Start", "Select"};
    private static final int PAD_BITS = 0xFFF0;
    private static final int UNDO_LIMIT = 1000;

    /** An immutable copy of the inputs, safe to read from another thread. */
    public record Frozen(int[][] masks, int length) {
        public int mask(int pad, int frame) { return frame >= 0 && frame < length ? masks[pad][frame] : 0; }
    }

    private record Edit(int group, int from, int[][] removed, int[][] added) { }

    private int[][] m = new int[2][1024];
    private int length;
    private final Deque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private int nextGroup = 1, openGroup;
    private int version;
    private Frozen frozen;

    public InputTimeline() { }

    public int length() { return length; }
    public int version() { return version; }

    public int mask(int pad, int frame) { return frame >= 0 && frame < length ? m[pad][frame] : 0; }

    public static InputTimeline of(Movie movie) {
        InputTimeline t = new InputTimeline();
        t.ensure(movie.length());
        for (int f = 0; f < movie.length(); f++) { t.m[0][f] = movie.pad1(f); t.m[1][f] = movie.pad2(f); }
        t.length = movie.length();
        return t;
    }

    /** The first {@code frames} frames as a movie (the RESET flag of port 1 is kept). */
    public Movie toMovie(int romCrc, String title, int frames) {
        Movie mv = new Movie(romCrc, title);
        for (int f = 0; f < frames; f++) mv.append(mask(0, f), mask(1, f));
        return mv;
    }

    /** A copy for other threads; cached until the next change. */
    public Frozen freeze() {
        if (frozen == null || frozenVersion != version) {
            frozen = new Frozen(new int[][]{Arrays.copyOf(m[0], length), Arrays.copyOf(m[1], length)}, length);
            frozenVersion = version;
        }
        return frozen;
    }
    private int frozenVersion = -1;

    // ------------------------------------------------------------------ editing

    /** Presses or releases one button of one frame. */
    public int toggle(int pad, int frame, int button) {
        return setMask(pad, frame, mask(pad, frame) ^ button);
    }

    public int set(int pad, int frame, int button, boolean on) {
        int cur = mask(pad, frame);
        return setMask(pad, frame, on ? cur | button : cur & ~button);
    }

    /** Presses or releases a button over frames {@code from..to} inclusive. */
    public int setRange(int pad, int from, int to, int button, boolean on) {
        if (to < from) return NONE;
        int[][] add = new int[2][to - from + 1], rem = new int[2][to - from + 1];
        for (int f = from; f <= to; f++) {
            for (int p = 0; p < 2; p++) { add[p][f - from] = rem[p][f - from] = mask(p, f); }
            add[pad][f - from] = on ? add[pad][f - from] | button : add[pad][f - from] & ~button;
        }
        return splice(from, to - from + 1, add);
    }

    /** Releases every button of one pad over {@code from..to}. */
    public int clearRange(int pad, int from, int to) {
        if (to < from) return NONE;
        int[][] add = new int[2][to - from + 1];
        for (int f = from; f <= to; f++) { add[1 - pad][f - from] = mask(1 - pad, f); add[pad][f - from] = f < length ? mask(pad, f) & ~PAD_BITS : 0; }
        return splice(from, to - from + 1, add);
    }

    /** The masks of one pad over {@code from..to}, for copy and paste. */
    public int[] copy(int pad, int from, int to) {
        int[] out = new int[Math.max(0, to - from + 1)];
        for (int i = 0; i < out.length; i++) out[i] = mask(pad, from + i) & PAD_BITS;
        return out;
    }

    /** Overwrites the inputs of one pad from {@code at} with the copied masks. */
    public int paste(int pad, int at, int[] masks) {
        if (masks.length == 0) return NONE;
        int[][] add = new int[2][masks.length];
        for (int i = 0; i < masks.length; i++) {
            add[1 - pad][i] = mask(1 - pad, at + i);
            add[pad][i] = (mask(pad, at + i) & ~PAD_BITS) | (masks[i] & PAD_BITS);
        }
        return splice(at, masks.length, add);
    }

    /** Inserts an empty frame before {@code frame}: everything after moves one frame later. */
    public int insert(int frame) {
        if (frame < 0 || frame >= length) return NONE;
        return splice(frame, 0, new int[2][1]);
    }

    /** Deletes {@code frame}: everything after moves one frame earlier. */
    public int delete(int frame) {
        if (frame < 0 || frame >= length) return NONE;
        return splice(frame, 1, new int[2][0]);
    }

    /** Removes every frame after {@code frame} (that frame stays). */
    public int truncateAfter(int frame) {
        if (frame + 1 >= length) return NONE;
        return splice(frame + 1, length - frame - 1, new int[2][0]);
    }

    /** Runs several edits as one undo step; returns the lowest frame they changed. */
    public int group(IntSupplier body) {
        boolean outer = openGroup == 0;
        if (outer) openGroup = nextGroup++;
        try {
            int lo = body.getAsInt();
            return lo;
        } finally { if (outer) openGroup = 0; }
    }

    /** Several edits in one undo step: collects the minimum of the changed frames. */
    public int groupAll(IntSupplier... steps) {
        return group(() -> {
            int lo = NONE;
            for (IntSupplier s : steps) lo = min(lo, s.getAsInt());
            return lo;
        });
    }

    public static int min(int a, int b) { return a == NONE ? b : b == NONE ? a : Math.min(a, b); }

    private int setMask(int pad, int frame, int mask) {
        if (frame < 0) return NONE;
        int[][] add = new int[2][1];
        add[1 - pad][0] = mask(1 - pad, frame);
        add[pad][0] = mask;
        return splice(frame, 1, add);
    }

    // ------------------------------------------------------------------ undo / redo

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    /** Undoes the last step; returns the first frame it changed, or {@link #NONE}. */
    public int undo() { return step(undo, redo, true); }
    public int redo() { return step(redo, undo, false); }

    private int step(Deque<Edit> from, Deque<Edit> to, boolean undoing) {
        if (from.isEmpty()) return NONE;
        int group = from.peek().group(), lo = NONE;
        while (!from.isEmpty() && from.peek().group() == group) {
            Edit e = from.pop();
            int[][] now = undoing ? e.removed() : e.added(), was = undoing ? e.added() : e.removed();
            lo = min(lo, apply(e.from(), was[0].length, now));
            to.push(e);
        }
        version++;
        return lo;
    }

    // ------------------------------------------------------------------ the one primitive

    private int splice(int from, int removeCount, int[][] added) {
        ensure(from);                                   // frames before the edit exist (zeros) even if it lies in the planned future
        if (from > length) { Arrays.fill(m[0], length, from, 0); Arrays.fill(m[1], length, from, 0); length = from; }
        int rc = Math.min(removeCount, length - from);
        int[][] removed = {Arrays.copyOfRange(m[0], from, from + rc), Arrays.copyOfRange(m[1], from, from + rc)};
        if (removeCount != rc) { for (int p = 0; p < 2; p++) removed[p] = Arrays.copyOf(removed[p], removeCount); }
        if (Arrays.equals(removed[0], added[0]) && Arrays.equals(removed[1], added[1])) return NONE;
        int lo = apply(from, rc, added);
        int g = openGroup != 0 ? openGroup : nextGroup++;
        undo.push(new Edit(g, from, removed, new int[][]{added[0].clone(), added[1].clone()}));
        while (undo.size() > UNDO_LIMIT) undo.removeLast();
        redo.clear();
        version++;
        return lo;
    }

    /** Replaces {@code removeCount} frames at {@code from} with {@code added}; returns the first frame that now differs. */
    private int apply(int from, int removeCount, int[][] added) {
        int n = added[0].length;
        ensure(length - removeCount + n);
        int first = from;
        if (n == removeCount) {
            first = NONE;
            for (int i = 0; i < n && first == NONE; i++) if (mask(0, from + i) != added[0][i] || mask(1, from + i) != added[1][i]) first = from + i;
        }
        int rc = Math.min(removeCount, Math.max(0, length - from));
        for (int p = 0; p < 2; p++) {
            System.arraycopy(m[p], from + rc, m[p], from + n, length - from - rc);
            System.arraycopy(added[p], 0, m[p], from, n);
        }
        int old = length;
        length = length - rc + n;
        for (int p = 0; p < 2; p++) if (length < old) Arrays.fill(m[p], length, old, 0);   // nothing stale past the end
        return first;
    }

    private void ensure(int n) {
        if (n > m[0].length) { int c = Math.max(n, m[0].length * 2); m[0] = Arrays.copyOf(m[0], c); m[1] = Arrays.copyOf(m[1], c); }
    }
}
