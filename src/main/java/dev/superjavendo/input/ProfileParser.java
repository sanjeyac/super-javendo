package dev.superjavendo.input;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a controller profile: one {@code key=value} per line, {@code #} starts a comment.
 * <pre>
 * type=keyboard | gamepad      (required)
 * name=Display name            (optional, defaults to the file name)
 * threshold=12000              (gamepad axis dead zone, optional)
 * up=KEY_UP                    (keyboard: Linux key names)
 * up=AXIS_7-,BUTTON_12         (gamepad: BUTTON_n, AXIS_n+, AXIS_n-)
 * </pre>
 * SNES buttons: up down left right a b x y l r start select. Emulator actions: save_state load_state (slot 0).
 * Several inputs may be given for the same button, comma separated and/or on repeated lines
 * (e.g. {@code l=BUTTON_6,BUTTON_8} makes both L1 and L2 press L).
 */
public final class ProfileParser {
    private static final Map<String, Integer> SNES = Map.ofEntries(
            Map.entry("up", Joypad.UP), Map.entry("down", Joypad.DOWN), Map.entry("left", Joypad.LEFT),
            Map.entry("right", Joypad.RIGHT), Map.entry("a", Joypad.A), Map.entry("b", Joypad.B),
            Map.entry("x", Joypad.X), Map.entry("y", Joypad.Y), Map.entry("l", Joypad.L), Map.entry("r", Joypad.R),
            Map.entry("start", Joypad.START), Map.entry("select", Joypad.SELECT),
            Map.entry("save_state", ControllerInput.SAVE_STATE), Map.entry("load_state", ControllerInput.LOAD_STATE));

    private ProfileParser() { }

    public static ControllerProfile parse(String fileName, List<String> lines) {
        String type = null, name = fileName;
        int threshold = 12000;
        Map<String, String> bindings = new HashMap<>();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw error(fileName, n, "expected key=value, got '" + line + "'");
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT), value = line.substring(eq + 1).trim();
            switch (key) {
                case "type" -> type = value.toLowerCase(Locale.ROOT);
                case "name" -> name = value;
                case "threshold" -> {
                    try { threshold = Integer.parseInt(value); }
                    catch (NumberFormatException e) { throw error(fileName, n, "threshold must be a number"); }
                }
                default -> {
                    if (!SNES.containsKey(key)) throw error(fileName, n, "unknown button '" + key + "'");
                    bindings.merge(key, value, (o, v) -> o + "," + v);
                }
            }
        }
        if (type == null) throw new IllegalArgumentException(fileName + ": missing type=keyboard|gamepad");
        Map<Integer, Integer> buttons = new HashMap<>();
        List<ControllerProfile.AxisBinding> axes = new ArrayList<>();
        boolean keyboard = type.equals("keyboard");
        if (!keyboard && !type.equals("gamepad")) throw new IllegalArgumentException(fileName + ": unknown type '" + type + "'");
        for (var e : bindings.entrySet()) {
            int mask = SNES.get(e.getKey());
            for (String tok : e.getValue().split(",")) {
                tok = tok.trim();
                if (tok.isEmpty()) continue;
                String up = tok.toUpperCase(Locale.ROOT);
                try {
                    if (keyboard) {
                        Integer code = KeyNames.code(up);
                        if (code == null) throw new IllegalArgumentException("unknown key");
                        buttons.merge(code, mask, (o, v) -> o | v);
                    } else if (up.startsWith("BUTTON_")) {
                        buttons.merge(Integer.parseInt(up.substring(7)), mask, (o, v) -> o | v);
                    } else if (up.startsWith("AXIS_") && (up.endsWith("+") || up.endsWith("-"))) {
                        axes.add(new ControllerProfile.AxisBinding(
                                Integer.parseInt(up.substring(5, up.length() - 1)), up.endsWith("+") ? 1 : -1, mask));
                    } else throw new IllegalArgumentException("expected BUTTON_n, AXIS_n+ or AXIS_n-");
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(fileName + ": " + e.getKey() + "=" + tok + ": " + ex.getMessage());
                }
            }
        }
        return keyboard ? ControllerProfile.keyboard(name, buttons)
                : ControllerProfile.gamepad(name, 0, buttons, axes, threshold);
    }

    private static IllegalArgumentException error(String file, int line, String msg) {
        return new IllegalArgumentException(file + ":" + (line + 1) + ": " + msg);
    }
}
