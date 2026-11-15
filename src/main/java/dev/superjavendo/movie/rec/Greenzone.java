package dev.superjavendo.movie.rec;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Console snapshots of a run kept so that seeking and re-emulating never start from power-on. {@code state(k)} is
 * the console after {@code k} frames. Policy: one snapshot every {@link #SPARSE} frames plus one per frame in the
 * last {@link #DENSE} frames before the frontier; compressed, with a memory cap that thins the oldest ones first.
 * Frame 0 and the newest snapshot are never dropped.
 */
public final class Greenzone {
    public static final int SPARSE = 60, DENSE = 120;

    private record Entry(byte[] z, int rawLength) { }

    private final TreeMap<Integer, Entry> states = new TreeMap<>();
    private long bytes;
    private long limit;

    public Greenzone(long limitBytes) { this.limit = limitBytes; }

    public void limit(long limitBytes) { this.limit = limitBytes; }
    public long bytes() { return bytes; }
    public int count() { return states.size(); }
    public boolean has(int frame) { return states.containsKey(frame); }

    /** Stores the raw snapshot of the console after {@code frame} frames (replacing any previous one). */
    public void put(int frame, byte[] raw) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        Entry old = states.put(frame, new Entry(out.toByteArray(), raw.length));
        bytes += out.size() - (old == null ? 0 : old.z().length);
    }

    /** The raw snapshot after {@code frame} frames, or null. */
    public byte[] get(int frame) {
        Entry e = states.get(frame);
        if (e == null) return null;
        Inflater inf = new Inflater();
        inf.setInput(e.z());
        byte[] raw = new byte[e.rawLength()];
        try {
            int n = 0;
            while (n < raw.length && !inf.finished()) n += inf.inflate(raw, n, raw.length - n);
        } catch (DataFormatException ex) { throw new IllegalStateException(ex); }
        finally { inf.end(); }
        return raw;
    }

    /** The latest frame {@code <= frame} that has a snapshot, or -1. */
    public int nearestAtOrBefore(int frame) {
        var e = states.floorKey(frame);
        return e == null ? -1 : e;
    }

    /** Forgets every snapshot of frame {@code frame} and later (they were made with inputs that have changed). */
    public void invalidateFrom(int frame) {
        var tail = states.tailMap(frame, true);
        for (Entry e : tail.values()) bytes -= e.z().length;
        tail.clear();
    }

    /** Frames that have a snapshot (for drawing the green strip). */
    public BitSet frames() {
        BitSet b = new BitSet();
        for (int k : states.keySet()) b.set(k);
        return b;
    }

    /**
     * Applies the policy for a run whose frontier is {@code frontier}: drops the snapshots older than the dense window
     * that are not multiples of {@link #SPARSE}, then, while over the memory cap, thins the oldest ones.
     */
    public void enforce(int frontier) {
        int denseFrom = frontier - DENSE;
        List<Integer> drop = new ArrayList<>();
        for (int k : states.keySet()) if (k > 0 && k < denseFrom && k % SPARSE != 0) drop.add(k);
        for (int k : drop) remove(k);
        // over the cap: thin the old sparse snapshots first (every second one, oldest side first); only when none are
        // left, thin the dense recent ones the same way
        while (bytes > limit) {
            List<Integer> pool = new ArrayList<>();
            for (int k : removable()) if (k < denseFrom) pool.add(k);
            if (pool.size() < 2) pool = removable();
            if (pool.isEmpty()) break;
            if (pool.size() == 1) { remove(pool.get(0)); continue; }
            for (int i = 1; i < pool.size() && bytes > limit; i += 2) remove(pool.get(i));
        }
    }

    /** Snapshots that may go: not frame 0, not the newest, oldest first. */
    private List<Integer> removable() {
        List<Integer> out = new ArrayList<>();
        int newest = states.isEmpty() ? -1 : states.lastKey();
        for (int k : states.keySet()) if (k > 0 && k != newest) out.add(k);
        return out;
    }

    private void remove(int frame) {
        Entry e = states.remove(frame);
        if (e != null) bytes -= e.z().length;
    }
}
