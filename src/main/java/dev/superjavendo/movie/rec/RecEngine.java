package dev.superjavendo.movie.rec;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a {@link RecSession} on its own thread. The UI never touches the console: it queues commands with
 * {@link #run} and receives an immutable {@link Status} after each one (and about ten times a second during long
 * operations). Playback paces itself at the console's frame rate times the chosen speed.
 */
public final class RecEngine implements AutoCloseable {
    public static final double FPS = 60.0988;

    /** What the UI shows: the session's view plus what the engine is doing. */
    public record Status(RecSession.View view, boolean busy, int done, int total, boolean playing, int speed, String error) { }

    private final RecSession session;
    private final Consumer<Status> sink;
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean open = true, playing, busy, cancel;
    private volatile int speed = 1, done, total;
    private volatile String error;

    public RecEngine(RecSession session, Consumer<Status> sink) {
        this.session = session;
        this.sink = sink;
        thread = new Thread(this::loop, "rec-engine");
        thread.setDaemon(true);
        thread.start();
        queue.add(() -> { });                            // publish the first status
    }

    /** Queues a command for the session. Exceptions are reported in {@link Status#error}, not thrown. */
    public void run(Consumer<RecSession> command) {
        queue.add(() -> {
            try { error = null; command.accept(session); } catch (RuntimeException e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
        });
    }

    /** A long command (fast-forward, re-emulate) that reports progress and stops when {@link #cancelLong()} is called. */
    public void runLong(int totalFrames, LongCommand command) {
        queue.add(() -> {
            busy = true; cancel = false; done = 0; total = totalFrames;
            long[] last = {0};
            try {
                error = null;
                command.run(session, n -> {
                    done = n;
                    long now = System.nanoTime();
                    if (now - last[0] > 100_000_000L) { last[0] = now; publish(); }
                }, () -> cancel);
            } catch (RuntimeException e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            finally { busy = false; }
        });
    }

    @FunctionalInterface public interface LongCommand {
        void run(RecSession s, java.util.function.IntConsumer progress, java.util.function.BooleanSupplier cancelled);
    }

    public void cancelLong() { cancel = true; }
    public boolean busy() { return busy; }
    public void playing(boolean on) { playing = on; queue.add(() -> { }); }
    public boolean playing() { return playing; }
    public void speed(int x) { speed = Math.max(1, x); }
    public int speed() { return speed; }

    private void publish() {
        sink.accept(new Status(session.view(), busy, done, total, playing, speed, error));
    }

    private void loop() {
        long next = System.nanoTime();
        while (open) {
            try {
                Runnable r = playing ? queue.poll() : queue.poll(200, TimeUnit.MILLISECONDS);
                if (r != null) { r.run(); publish(); next = System.nanoTime(); continue; }
                if (!playing) continue;
                if (session.hasPending() || !session.playStep()) playing = false;
                publish();
                next += (long) (1e9 / FPS / speed);
                long wait = next - System.nanoTime();
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                else if (wait < -100_000_000L) next = System.nanoTime();
            } catch (InterruptedException e) { return; }
            catch (RuntimeException e) { error = String.valueOf(e.getMessage()); playing = false; publish(); }
        }
    }

    @Override public void close() {
        open = false;
        playing = false;
        thread.interrupt();
        try { thread.join(2000); } catch (InterruptedException ignored) { }
    }
}
