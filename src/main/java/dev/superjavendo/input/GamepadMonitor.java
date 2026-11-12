package dev.superjavendo.input;

import java.io.IOException;

/**
 * Reports every press and release on a gamepad as tokens ({@code BUTTON_n}, {@code AXIS_n+}, {@code AXIS_n-}) until closed.
 * Used by the controller screen for the live test and for "add input". Events come from a daemon thread.
 */
public final class GamepadMonitor implements AutoCloseable {
    public interface Listener { void event(String token, boolean down); }

    private final int device, threshold;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile boolean connected;

    public GamepadMonitor(int device, int threshold, Listener listener) {
        this.device = device;
        this.threshold = threshold;
        this.listener = listener;
        Thread t = new Thread(this::loop, "gamepad-monitor-" + device);
        t.setDaemon(true);
        t.start();
    }

    public boolean connected() { return connected; }

    @Override public void close() { running = false; }

    private void loop() {
        GamepadBackend backend = GamepadBackends.current();
        while (running) {
            if (!backend.connected(device)) { connected = false; sleep(500); continue; }
            try {
                connected = true;
                backend.read(device, sink(), () -> running);
            } catch (IOException e) {
                // unplugged: try again
            }
            connected = false;
            sleep(500);
        }
    }

    /** Turns device events into tokens. Package-private for tests. */
    GamepadBackend.Sink sink() {
        return new GamepadBackend.Sink() {
            private final int[] axisState = new int[256];            // -1, 0, +1 per axis

            @Override public void button(int number, boolean down, boolean initial) {
                if (!initial) listener.event("BUTTON_" + number, down);
            }

            @Override public void axis(int number, int value, boolean initial) {
                if (number < 0 || number >= axisState.length) return;
                int now = value > threshold ? 1 : value < -threshold ? -1 : 0, before = axisState[number];
                axisState[number] = now;
                if (initial || now == before) return;
                if (before != 0) listener.event("AXIS_" + number + (before > 0 ? "+" : "-"), false);
                if (now != 0) listener.event("AXIS_" + number + (now > 0 ? "+" : "-"), true);
            }
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
