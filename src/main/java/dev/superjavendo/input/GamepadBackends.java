package dev.superjavendo.input;

import java.util.Locale;

/**
 * The backend in use: Linux reads {@code /dev/input/js*} ({@link JsBackend}); macOS and Windows use input4j
 * ({@link Input4jBackend}). {@code -Dsuperjavendo.gamepad=js|input4j} forces one (to try input4j on Linux).
 */
public final class GamepadBackends {
    private GamepadBackends() { }

    private static volatile GamepadBackend current;

    public static GamepadBackend current() {
        GamepadBackend b = current;
        if (b == null) {
            synchronized (GamepadBackends.class) {
                if (current == null) current = choose(System.getProperty("superjavendo.gamepad"), System.getProperty("os.name", ""));
                b = current;
            }
        }
        return b;
    }

    /** Replaces the backend (tests); null goes back to the automatic choice. */
    public static void use(GamepadBackend backend) { current = backend; }

    static GamepadBackend choose(String forced, String osName) {
        String f = forced == null ? "" : forced.trim().toLowerCase(Locale.ROOT);
        if (f.equals("js")) return new JsBackend();
        if (f.equals("input4j")) return new Input4jBackend();
        return osName.toLowerCase(Locale.ROOT).contains("linux") ? new JsBackend() : new Input4jBackend();
    }
}
