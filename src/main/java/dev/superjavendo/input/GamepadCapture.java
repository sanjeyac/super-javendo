package dev.superjavendo.input;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

/** Waits for the next button press or stick/hat movement on /dev/input/js&lt;N&gt; (used by the configurator). */
public final class GamepadCapture {
    private GamepadCapture() { }

    /** Starts a daemon thread; {@code result} gets {@code BUTTON_n} / {@code AXIS_n+|-}, or null on timeout/error. */
    public static void next(int device, long timeoutMillis, Consumer<String> result, Consumer<String> error) {
        Thread t = new Thread(() -> {
            File dev = new File("/dev/input/js" + device);
            if (!dev.canRead()) { error.accept("/dev/input/js" + device + " non trovato"); return; }
            DataInputStream in = null;
            try {
                in = new DataInputStream(new FileInputStream(dev));
                long end = System.currentTimeMillis() + timeoutMillis;
                byte[] raw = new byte[8];
                while (System.currentTimeMillis() < end) {
                    if (in.available() < 8) { Thread.sleep(10); continue; }
                    in.readFully(raw);
                    ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                    b.getInt();
                    short value = b.getShort();
                    int type = b.get(), number = b.get() & 0xFF;
                    if ((type & 0x80) != 0) continue;                    // initial state replay
                    if (type == 1 && value != 0) { result.accept("BUTTON_" + number); return; }
                    if (type == 2 && Math.abs(value) > 16000) { result.accept("AXIS_" + number + (value > 0 ? "+" : "-")); return; }
                }
                error.accept("Nessun input entro " + timeoutMillis / 1000 + " s");
            } catch (IOException | InterruptedException e) {
                error.accept("Errore: " + e.getMessage());
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) { }
            }
        }, "gamepad-capture");
        t.setDaemon(true);
        t.start();
    }
}
