package dev.superjavendo.input;

import java.util.List;

/** The gamepads the current {@link GamepadBackend} sees. */
public final class GamepadDevices {
    private GamepadDevices() { }

    public static boolean connected(int index) { return GamepadBackends.current().connected(index); }

    /** Name of the device, or "" if unknown. */
    public static String name(int index) { return GamepadBackends.current().name(index); }

    /** Indices of the devices present now, ascending. */
    public static List<Integer> present() { return GamepadBackends.current().present(); }

    /** Short label for the UI ("js0", "pad 0"). */
    public static String label(int index) { return GamepadBackends.current().label(index); }
}
