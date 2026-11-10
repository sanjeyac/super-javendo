package dev.superjavendo.input;

import java.util.List;
import java.util.stream.Collectors;

/** Several strategies feeding the same port (e.g. keyboard + gamepad); buttons are OR-ed together. */
public final class CompositeInput implements ControllerInput {
    private final List<ControllerInput> inputs;

    public CompositeInput(List<ControllerInput> inputs) { this.inputs = List.copyOf(inputs); }

    @Override public int poll() {
        int m = 0;
        for (ControllerInput i : inputs) m |= i.poll();
        return m;
    }

    @Override public String name() {
        return inputs.stream().map(ControllerInput::name).filter(n -> !n.isEmpty()).collect(Collectors.joining(" + "));
    }

    @Override public boolean onKey(int keyCode, boolean pressed) {
        boolean used = false;
        for (ControllerInput i : inputs) used |= i.onKey(keyCode, pressed);
        return used;
    }

    @Override public void close() { inputs.forEach(ControllerInput::close); }
}
