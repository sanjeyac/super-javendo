package dev.superjavendo.input;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reports every press and release on /dev/input/js&lt;N&gt; as tokens ({@code BUTTON_n}, {@code AXIS_n+}, {@code AXIS_n-}) until closed.
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
        Thread t = new Thread(this::loop, "gamepad-monitor-js" + device);
        t.setDaemon(true);
        t.start();
    }

    public boolean connected() { return connected; }

    @Override public void close() { running = false; }

    private void loop() {
        while (running) {
            if (!GamepadDevices.connected(device)) { connected = false; sleep(500); continue; }
            try (DataInputStream in = new DataInputStream(new FileInputStream("/dev/input/js" + device))) {
                connected = true;
                byte[] raw = new byte[8];
                while (running) {
                    if (in.available() < 8) { sleep(8); continue; }
                    in.readFully(raw);
                    decode(raw);
                }
            } catch (IOException e) {
                // unplugged: try again
            }
            connected = false;
            sleep(500);
        }
    }

    private final int[] axisState = new int[256];            // -1, 0, +1 per axis

    /** One js_event (8 bytes). Package-private for tests. */
    void decode(byte[] raw) {
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.getInt();
        short value = b.getShort();
        int type = b.get() & 0xFF, number = b.get() & 0xFF;
        boolean initial = (type & 0x80) != 0;
        type &= 0x7F;
        if (type == 1 && !initial) listener.event("BUTTON_" + number, value != 0);
        else if (type == 2) {
            int now = value > threshold ? 1 : value < -threshold ? -1 : 0, before = axisState[number];
            axisState[number] = now;
            if (initial || now == before) return;
            if (before != 0) listener.event("AXIS_" + number + (before > 0 ? "+" : "-"), false);
            if (now != 0) listener.event("AXIS_" + number + (now > 0 ? "+" : "-"), true);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
