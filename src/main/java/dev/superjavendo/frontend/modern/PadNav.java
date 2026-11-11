package dev.superjavendo.frontend.modern;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.input.ControllerAssignments;
import dev.superjavendo.input.GamepadDevices;
import dev.superjavendo.input.GamepadMonitor;
import dev.superjavendo.input.ProfileFile;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Lets a gamepad drive the frontend: the buttons of the profile assigned to a player are translated into
 * the keys the screens already understand (arrows, Enter, Escape) or into library actions (favourite, collection, search).
 * Devices are rescanned every few seconds so a pad plugged in later works too. Does nothing while {@code enabled} is false
 * (a game is running, or the controller screen is listening for an input).
 */
final class PadNav {
    private final AppPaths paths;
    private final BooleanSupplier enabled;
    private final Consumer<String> action;
    private final Map<Integer, GamepadMonitor> monitors = new HashMap<>();

    PadNav(AppPaths paths, BooleanSupplier enabled, Consumer<String> action) {
        this.paths = paths;
        this.enabled = enabled;
        this.action = action;
        Thread t = new Thread(this::scan, "pad-nav");
        t.setDaemon(true);
        t.start();
    }

    private void scan() {
        while (true) {
            for (int d : GamepadDevices.present())
                if (!monitors.containsKey(d)) {
                    Map<String, String> tokens = tokensFor(d);
                    monitors.put(d, new GamepadMonitor(d, 12000, (token, down) -> {
                        String b = tokens.get(token);
                        if (b != null && down && enabled.getAsBoolean()) SwingUtilities.invokeLater(() -> press(b));
                    }));
                }
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    /** token (BUTTON_n / AXIS_n+) -> SNES button name, from the gamepad profiles assigned to the players on this device. */
    private Map<String, String> tokensFor(int device) {
        Map<String, String> out = new HashMap<>();
        ControllerAssignments as = new ControllerAssignments(paths.controllersFile());
        for (int p = 1; p <= ControllerAssignments.PLAYERS; p++)
            for (ControllerAssignments.Entry e : as.of(p)) {
                if (e.device() != device && e.device() >= 0) continue;
                try {
                    ProfileFile f = ProfileFile.read(paths.joystickDir().resolve(e.profile() + ".cfg"));
                    if (f.isKeyboard()) continue;
                    for (String b : List.of("up", "down", "left", "right", "a", "b", "x", "y", "l", "r", "start", "select"))
                        for (String tok : f.of(b)) out.putIfAbsent(tok, b);
                } catch (Exception ignored) { }
            }
        return out;
    }

    private void press(String b) {
        Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (w == null || !(w instanceof javax.swing.JFrame f) || !f.getTitle().equals("Super Javendo")) return;   // only the frontend window
        int key = switch (b) {
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            case "a" -> KeyEvent.VK_ENTER;
            case "b" -> KeyEvent.VK_ESCAPE;
            default -> 0;
        };
        if (key == 0) { action.accept(b); return; }
        Component c = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (c == null) { action.accept(b); return; }
        long now = System.currentTimeMillis();
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, now, 0, key, KeyEvent.CHAR_UNDEFINED));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, now, 0, key, KeyEvent.CHAR_UNDEFINED));
    }
}
