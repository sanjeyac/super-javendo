package dev.superjavendo.games;

import dev.superjavendo.Snes;

import java.util.Arrays;

/**
 * Watches a game's in-RAM score table without slowing the game down. The emulation thread only copies the table's bytes (a
 * hundred or so, a few times a second) into a field; a separate low-priority thread decodes them, merges them into the
 * {@link ScoreStore} and writes the file. A copy is acted on once it has stayed the same for a whole tick, so a name being typed
 * letter by letter is not stored at every letter; {@link #close()} stores whatever is left. It only ever reads memory.
 */
public final class ScoreMonitor implements AutoCloseable {
    private final String crc;
    private final RamScores spec;
    private final ScoreStore store;
    private final long tickMs;
    private final Thread thread;
    private volatile byte[] latest;            // written by the emulation thread
    private byte[] lastSent;                   // emulation thread only
    private volatile boolean stopped;

    public ScoreMonitor(String crc, RamScores spec, ScoreStore store, long tickMs) {
        this.crc = crc;
        this.spec = spec;
        this.store = store;
        this.tickMs = tickMs;
        thread = new Thread(this::run, "score-monitor");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Call between frames on the emulation thread. Cheap: copies the table and hands it over only if it changed. */
    public void sample(Snes snes) {
        byte[] b = new byte[spec.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) snes.bus.read(0x7E0000 + spec.wramOffset() + i);
        sample(b);
    }

    /** Hands over a copy of the table taken by the caller. */
    void sample(byte[] bytes) {
        if (Arrays.equals(bytes, lastSent)) return;
        lastSent = bytes;
        latest = bytes;
    }

    private void run() {
        byte[] previous = null, done = null;
        while (!stopped) {
            try { Thread.sleep(tickMs); } catch (InterruptedException e) { return; }
            byte[] cur = latest;
            if (cur != null && cur != done && Arrays.equals(cur, previous)) { store(cur); done = cur; }
            previous = cur;
        }
    }

    private synchronized void store(byte[] bytes) {
        try { store.merge(crc, spec, spec.decode(bytes)); }
        catch (RuntimeException e) { System.err.println("Score monitor: " + e); }
    }

    /** Stops the thread and stores the last copy handed over, stable or not. */
    @Override public void close() {
        stopped = true;
        thread.interrupt();
        try { thread.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        byte[] cur = latest;
        if (cur != null) store(cur);
    }
}
