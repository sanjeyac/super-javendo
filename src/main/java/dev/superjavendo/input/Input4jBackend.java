package dev.superjavendo.input;

import de.gurkenlabs.input4j.InputComponent;
import de.gurkenlabs.input4j.InputDevice;
import de.gurkenlabs.input4j.InputDevicePlugin;
import de.gurkenlabs.input4j.InputDevices;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Gamepads through the input4j library (pure Java over the Foreign Function API: XInput/DirectInput on Windows,
 * IOKit on macOS, evdev on Linux). Used on macOS and Windows; the numbers in {@code BUTTON_n}/{@code AXIS_n} are the
 * position of the component among the device's buttons / axes, so profiles written for the Linux joystick API do not
 * apply: remap in the controller screen. If the library cannot start, there are simply no pads.
 */
public final class Input4jBackend implements GamepadBackend {
    private static final long POLL_MILLIS = 2;

    private InputDevicePlugin plugin;
    private boolean failed;

    private synchronized List<InputDevice> devices() {
        if (plugin == null && !failed) {
            try { plugin = InputDevices.init(); }
            catch (Throwable t) { failed = true; System.err.println("input4j: gamepads unavailable: " + t); }
        }
        return plugin == null ? List.of() : new ArrayList<>(plugin.getAll());
    }

    private InputDevice device(int index) {
        List<InputDevice> all = devices();
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    @Override public boolean connected(int index) { return device(index) != null; }

    @Override public String name(int index) {
        InputDevice d = device(index);
        return d == null ? "" : d.getDisplayName();
    }

    @Override public List<Integer> present() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < devices().size(); i++) out.add(i);
        return out;
    }

    @Override public String label(int index) { return "pad " + index; }

    @Override public void read(int index, Sink sink, BooleanSupplier running) throws IOException {
        InputDevice d = device(index);
        if (d == null) throw new IOException("no gamepad " + index);
        List<InputComponent> buttons = new ArrayList<>(), axes = new ArrayList<>();
        for (InputComponent c : d.getComponents()) {
            if (c.isButton()) buttons.add(c);
            else if (c.isAxis() && !c.isRelative()) axes.add(c);
        }
        boolean[] down = new boolean[buttons.size()];
        int[] value = new int[axes.size()];
        boolean first = true;
        while (running.getAsBoolean()) {
            if (device(index) != d) throw new IOException("gamepad " + index + " disconnected");
            d.poll();
            for (int i = 0; i < buttons.size(); i++) {
                boolean now = buttons.get(i).getData() > 0.5f;
                if (now != down[i] || first && now) { down[i] = now; sink.button(i, now, first); }
            }
            for (int i = 0; i < axes.size(); i++) {
                int now = Math.round(Math.max(-1f, Math.min(1f, axes.get(i).getData())) * 32767);
                if (now != value[i] || first && now != 0) { value[i] = now; sink.axis(i, now, first); }
            }
            first = false;
            try { Thread.sleep(POLL_MILLIS); } catch (InterruptedException e) { return; }
        }
    }
}
