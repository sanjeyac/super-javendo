package dev.superjavendo.input;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Where gamepad events come from. Devices are addressed by a small index (0, 1, ...); buttons and axes are numbered
 * the way profiles name them ({@code BUTTON_n}, {@code AXIS_n+|-}). Axis values use the Linux joystick range, -32767..32767.
 */
public interface GamepadBackend {
    /** Receives the state changes of one device. {@code initial} marks the replay of the state at open time. */
    interface Sink {
        void button(int number, boolean down, boolean initial);
        void axis(int number, int value, boolean initial);
    }

    /** Whether the device can be read right now. */
    boolean connected(int index);

    /** Name of the device, or "" if unknown. */
    String name(int index);

    /** Indices of the devices present now, ascending. */
    List<Integer> present();

    /** Short label for the device in the UI ("js0", "pad 0"). */
    String label(int index);

    /**
     * Delivers the events of the device to {@code sink} until {@code running} turns false.
     * @throws IOException when the device is lost
     */
    void read(int index, Sink sink, BooleanSupplier running) throws IOException;
}
