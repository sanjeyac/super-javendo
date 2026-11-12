package dev.superjavendo.input;

import java.io.IOException;

/**
 * Gamepad strategy on top of a {@link GamepadBackend} (the Linux joystick API, or input4j on macOS and Windows).
 * Hot-plug aware: keeps looking for its device and reopens it after a disconnect.
 */
public final class GamepadInput implements ControllerInput {
    private final ControllerProfile profile;
    private volatile int mask;
    private volatile boolean connected;
    private volatile boolean running = true;

    public GamepadInput(ControllerProfile profile) {
        this.profile = profile;
        Thread t = new Thread(this::loop, "gamepad-" + profile.deviceIndex);
        t.setDaemon(true);
        t.start();
    }

    @Override public int poll() { return mask; }

    @Override public String name() { return profile.name + (connected ? "" : " (not connected)"); }

    @Override public void close() { running = false; }

    private void loop() {
        GamepadBackend backend = GamepadBackends.current();
        int index = profile.deviceIndex;
        while (running) {
            if (!backend.connected(index)) { sleep(2000); continue; }
            System.out.println("Gamepad connected: " + backend.label(index) + " as " + profile.name);
            connected = true;
            try {
                backend.read(index, sink(), () -> running);
            } catch (IOException e) {
                // disconnected
            }
            connected = false;
            mask = 0;
            sleep(1000);
        }
    }

    /** Turns device events into the console button mask. Package-private for tests. */
    GamepadBackend.Sink sink() {
        return new GamepadBackend.Sink() {
            private int buttons;
            private final int[] axis = new int[256];

            @Override public void button(int number, boolean down, boolean initial) {
                Integer bit = profile.buttons.get(number);
                if (bit != null) { if (down) buttons |= bit; else buttons &= ~bit; }
                update();
            }

            @Override public void axis(int number, int value, boolean initial) {
                if (number >= 0 && number < axis.length) axis[number] = value;
                update();
            }

            private void update() {
                int m = buttons;
                for (ControllerProfile.AxisBinding ab : profile.axes) {
                    if (ab.direction() * axis[ab.axis()] > profile.threshold) m |= ab.mask();
                }
                mask = m;
            }
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
