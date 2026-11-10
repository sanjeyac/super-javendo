package dev.superjavendo.input;

/** The two SNES controller ports. Each port holds one {@link ControllerInput} strategy, swappable at runtime. */
public final class ControllerPorts {
    private volatile ControllerInput port1 = new NullInput();
    private volatile ControllerInput port2 = new NullInput();

    /** Plugs a strategy into port 0 or 1, closing the previous one. */
    public void plug(int port, ControllerInput input) {
        ControllerInput old;
        if (port == 0) { old = port1; port1 = input; }
        else if (port == 1) { old = port2; port2 = input; }
        else throw new IllegalArgumentException("port " + port);
        if (old != input) old.close();
    }

    public ControllerInput get(int port) { return port == 0 ? port1 : port2; }

    /** Samples both ports into the console's joypads. Call once per frame. */
    public void apply(Joypad joypad) {
        joypad.setButtons(0, port1.poll());
        joypad.setButtons(1, port2.poll());
    }

    /** Emulator actions ({@link ControllerInput#SAVE_STATE}, ...) currently held on any port. */
    public int actions() { return (port1.poll() | port2.poll()) & ControllerInput.ACTIONS; }

    /** Forwards a keyboard event to both ports; returns true if any consumed it. */
    public boolean onKey(int keyCode, boolean pressed) {
        boolean a = port1.onKey(keyCode, pressed);
        boolean b = port2.onKey(keyCode, pressed);
        return a || b;
    }

    public String describe() {
        return "P1: " + port1.name() + " | P2: " + port2.name();
    }

    public void close() { port1.close(); port2.close(); }
}
