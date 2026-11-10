package dev.superjavendo.input;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Keyboard strategy: the UI forwards key events through {@link #onKey}. */
public final class KeyboardInput implements ControllerInput {
    private final ControllerProfile profile;
    private final Map<Integer, Integer> keys;
    private final AtomicInteger mask = new AtomicInteger();

    public KeyboardInput(ControllerProfile profile) {
        this.profile = profile;
        this.keys = profile.buttons;
    }

    @Override public int poll() { return mask.get(); }

    @Override public String name() { return profile.name; }

    @Override public boolean onKey(int keyCode, boolean pressed) {
        Integer b = keys.get(keyCode);
        if (b == null) return false;
        if (pressed) mask.accumulateAndGet(b, (o, n) -> o | n);
        else mask.accumulateAndGet(~b, (o, n) -> o & n);
        return true;
    }
}
