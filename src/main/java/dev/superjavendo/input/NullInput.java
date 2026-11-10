package dev.superjavendo.input;

/** Nothing plugged in. */
public final class NullInput implements ControllerInput {
    @Override public int poll() { return 0; }
    @Override public String name() { return "none"; }
}
