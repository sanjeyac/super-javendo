package dev.superjavendo.input;

import java.io.IOException;
import java.util.function.Consumer;

/** Waits for the next button press or stick/hat movement on a gamepad (used by the configurator). */
public final class GamepadCapture {
    private GamepadCapture() { }

    /** Starts a daemon thread; {@code result} gets {@code BUTTON_n} / {@code AXIS_n+|-}, or null on timeout/error. */
    public static void next(int device, long timeoutMillis, Consumer<String> result, Consumer<String> error) {
        Thread t = new Thread(() -> {
            GamepadBackend backend = GamepadBackends.current();
            if (!backend.connected(device)) { error.accept(backend.label(device) + " non trovato"); return; }
            long end = System.currentTimeMillis() + timeoutMillis;
            String[] found = new String[1];
            GamepadBackend.Sink sink = new GamepadBackend.Sink() {
                @Override public void button(int number, boolean down, boolean initial) {
                    if (!initial && down && found[0] == null) found[0] = "BUTTON_" + number;
                }
                @Override public void axis(int number, int value, boolean initial) {
                    if (!initial && Math.abs(value) > 16000 && found[0] == null) found[0] = "AXIS_" + number + (value > 0 ? "+" : "-");
                }
            };
            try {
                backend.read(device, sink, () -> found[0] == null && System.currentTimeMillis() < end);
            } catch (IOException e) {
                error.accept("Errore: " + e.getMessage());
                return;
            }
            if (found[0] != null) result.accept(found[0]);
            else error.accept("Nessun input entro " + timeoutMillis / 1000 + " s");
        }, "gamepad-capture");
        t.setDaemon(true);
        t.start();
    }
}
