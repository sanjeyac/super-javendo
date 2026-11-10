package dev.superjavendo.input;

import java.util.List;
import java.util.Map;

/**
 * Immutable description of how a physical device maps onto SNES buttons. A profile is plain data,
 * normally read from a <code>.cfg</code> file by {@link ProfileParser}; {@link ControllerFactory}
 * turns it into the matching {@link ControllerInput} strategy.
 */
public final class ControllerProfile {
    public enum Type { KEYBOARD, GAMEPAD }

    /** A joystick axis that, past the threshold in {@code direction} (-1/+1), presses {@code mask}. */
    public record AxisBinding(int axis, int direction, int mask) { }

    public final String name;
    public final Type type;
    /** KEYBOARD: AWT key code -> SNES mask. GAMEPAD: js button number -> SNES mask. */
    public final Map<Integer, Integer> buttons;
    /** GAMEPAD only: axis bindings (sticks, hats) and the dead-zone threshold. */
    public final List<AxisBinding> axes;
    public final int threshold;
    /** GAMEPAD only: /dev/input/js&lt;N&gt; to use. */
    public final int deviceIndex;

    private ControllerProfile(String name, Type type, Map<Integer, Integer> buttons, List<AxisBinding> axes,
                              int threshold, int deviceIndex) {
        this.name = name; this.type = type; this.buttons = Map.copyOf(buttons); this.axes = List.copyOf(axes);
        this.threshold = threshold; this.deviceIndex = deviceIndex;
    }

    public static ControllerProfile keyboard(String name, Map<Integer, Integer> keys) {
        return new ControllerProfile(name, Type.KEYBOARD, keys, List.of(), 0, -1);
    }

    public static ControllerProfile gamepad(String name, int deviceIndex, Map<Integer, Integer> buttons,
                                            List<AxisBinding> axes, int threshold) {
        return new ControllerProfile(name, Type.GAMEPAD, buttons, axes, threshold, deviceIndex);
    }

    /** Same profile bound to another /dev/input/js&lt;N&gt;. */
    public ControllerProfile withDevice(int index) {
        return new ControllerProfile(name, type, buttons, axes, threshold, index);
    }

    // ------------------------------------------------------------------ bundled profiles

    public static ControllerProfile keyboardPlayer1() { return ProfileStore.bundled("keyboard"); }

    public static ControllerProfile keyboardPlayer2() { return ProfileStore.bundled("keyboard2"); }

    /** Bundled Xbox profile bound to js&lt;deviceIndex&gt;. */
    public static ControllerProfile xboxGamepad(int deviceIndex) { return ProfileStore.bundled("xbox").withDevice(deviceIndex); }
}
