package dev.superjavendo.input;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Gamepad strategy using the Linux joystick API (/dev/input/js&lt;N&gt;), no native libraries.
 * Hot-plug aware: keeps looking for its device and reopens it after a disconnect.
 */
public final class GamepadInput implements ControllerInput {
    private final ControllerProfile profile;
    private volatile int mask;
    private volatile boolean connected;
    private volatile boolean running = true;

    public GamepadInput(ControllerProfile profile) {
        this.profile = profile;
        Thread t = new Thread(this::loop, "gamepad-js" + profile.deviceIndex);
        t.setDaemon(true);
        t.start();
    }

    @Override public int poll() { return mask; }

    @Override public String name() { return profile.name + (connected ? "" : " (not connected)"); }

    @Override public void close() { running = false; }

    private void loop() {
        File dev = new File("/dev/input/js" + profile.deviceIndex);
        while (running) {
            if (!dev.exists() || !dev.canRead()) { sleep(2000); continue; }
            System.out.println("Gamepad connected: " + dev + " as " + profile.name);
            connected = true;
            try (DataInputStream in = new DataInputStream(new FileInputStream(dev))) {
                read(in);
            } catch (IOException e) {
                // disconnected
            }
            connected = false;
            mask = 0;
            sleep(1000);
        }
    }

    /** Parses js_event records (8 bytes) until the stream ends. Package-private for tests. */
    void read(DataInputStream in) throws IOException {
        byte[] raw = new byte[8];
        int buttons = 0;
        int[] axis = new int[256];
        while (running) {
            in.readFully(raw);
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            b.getInt(); // timestamp
            short value = b.getShort();
            int type = b.get() & 0x7F; // strip the "initial state" flag
            int number = b.get() & 0xFF;
            if (type == 1) {
                Integer bit = profile.buttons.get(number);
                if (bit != null) { if (value != 0) buttons |= bit; else buttons &= ~bit; }
            } else if (type == 2) {
                axis[number] = value;
            }
            int m = buttons;
            for (ControllerProfile.AxisBinding ab : profile.axes) {
                if (ab.direction() * axis[ab.axis()] > profile.threshold) m |= ab.mask();
            }
            mask = m;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
