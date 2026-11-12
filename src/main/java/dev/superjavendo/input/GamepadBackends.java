package dev.superjavendo.input;

import java.util.Locale;

/**
 * The backend in use: Linux reads {@code /dev/input/js*} ({@link JsBackend}); macOS and Windows use input4j
 * ({@link Input4jBackend}). {@code -Dsuperjavendo.gamepad=js|input4j|none} forces one (input4j to try it on Linux, none for tests).
 */
public final class GamepadBackends {
    private GamepadBackends() { }

    private static volatile GamepadBackend current;

    /** No gamepads at all: what the tests run with ({@code -Dsuperjavendo.gamepad=none}), so they never touch real devices. */
    private static final GamepadBackend NONE = new GamepadBackend() {
        @Override public boolean connected(int index) { return false; }
        @Override public String name(int index) { return ""; }
        @Override public java.util.List<Integer> present() { return java.util.List.of(); }
        @Override public String label(int index) { return "none" + index; }
        @Override public void read(int index, Sink sink, java.util.function.BooleanSupplier running) { }
    };

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
        if (f.equals("none")) return NONE;
        return osName.toLowerCase(Locale.ROOT).contains("linux") ? new JsBackend() : new Input4jBackend();
    }
}
