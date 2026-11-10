package dev.superjavendo.input;

/**
 * Strategy for one SNES controller port: whatever physical (or virtual) device is plugged in,
 * it only has to answer "which SNES buttons are held right now?".
 */
public interface ControllerInput {
    /**
     * Emulator actions travel in the low bits of {@link #poll()} (the SNES pad only uses the high 12):
     * quick save / quick load of state slot 0. They never reach the console.
     */
    int SAVE_STATE = 0x1, LOAD_STATE = 0x2, ACTIONS = 0xF;

    /** Currently pressed buttons as a mask of {@link Joypad} constants. Must be cheap and thread-safe. */
    int poll();

    /** Human-readable description (device name, profile...). */
    default String name() { return getClass().getSimpleName(); }

    /** Keyboard hook: return true if the event was consumed. Non-keyboard strategies ignore it. */
    default boolean onKey(int keyCode, boolean pressed) { return false; }

    /** Releases devices/threads. */
    default void close() { }
}
