package dev.superjavendo.input;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/** Linux key names (linux/input-event-codes.h, e.g. {@code KEY_UP}) to AWT key codes. */
final class KeyNames {
    private static final Map<String, Integer> MAP = new HashMap<>();

    static {
        for (char c = 'A'; c <= 'Z'; c++) MAP.put("KEY_" + c, KeyEvent.VK_A + (c - 'A'));
        for (char c = '0'; c <= '9'; c++) {
            MAP.put("KEY_" + c, KeyEvent.VK_0 + (c - '0'));
            MAP.put("KEY_KP" + c, KeyEvent.VK_NUMPAD0 + (c - '0'));
        }
        for (int i = 1; i <= 12; i++) MAP.put("KEY_F" + i, KeyEvent.VK_F1 + (i - 1));
        Object[] o = {
            "UP", KeyEvent.VK_UP, "DOWN", KeyEvent.VK_DOWN, "LEFT", KeyEvent.VK_LEFT, "RIGHT", KeyEvent.VK_RIGHT,
            "ENTER", KeyEvent.VK_ENTER, "KPENTER", KeyEvent.VK_ENTER, "SPACE", KeyEvent.VK_SPACE,
            "TAB", KeyEvent.VK_TAB, "BACKSPACE", KeyEvent.VK_BACK_SPACE, "ESC", KeyEvent.VK_ESCAPE,
            "LEFTSHIFT", KeyEvent.VK_SHIFT, "RIGHTSHIFT", KeyEvent.VK_SHIFT,
            "LEFTCTRL", KeyEvent.VK_CONTROL, "RIGHTCTRL", KeyEvent.VK_CONTROL,
            "LEFTALT", KeyEvent.VK_ALT, "RIGHTALT", KeyEvent.VK_ALT,
            "COMMA", KeyEvent.VK_COMMA, "DOT", KeyEvent.VK_PERIOD, "SLASH", KeyEvent.VK_SLASH,
            "SEMICOLON", KeyEvent.VK_SEMICOLON, "APOSTROPHE", KeyEvent.VK_QUOTE,
            "LEFTBRACE", KeyEvent.VK_OPEN_BRACKET, "RIGHTBRACE", KeyEvent.VK_CLOSE_BRACKET,
            "BACKSLASH", KeyEvent.VK_BACK_SLASH, "MINUS", KeyEvent.VK_MINUS, "EQUAL", KeyEvent.VK_EQUALS,
            "HOME", KeyEvent.VK_HOME, "END", KeyEvent.VK_END, "PAGEUP", KeyEvent.VK_PAGE_UP,
            "PAGEDOWN", KeyEvent.VK_PAGE_DOWN, "INSERT", KeyEvent.VK_INSERT, "DELETE", KeyEvent.VK_DELETE,
        };
        for (int i = 0; i < o.length; i += 2) MAP.put("KEY_" + o[i], (Integer) o[i + 1]);
    }

    private KeyNames() { }

    /** A Linux key name for an AWT key code (first match), or {@code null} if the key has no name here. */
    static String name(int awtCode) {
        String best = null;
        for (var e : MAP.entrySet())
            if (e.getValue() == awtCode && (best == null || e.getKey().length() < best.length()
                    || (e.getKey().length() == best.length() && e.getKey().compareTo(best) < 0))) best = e.getKey();
        return best;
    }

    /** AWT key code for a Linux key name, or {@code null} if unknown. */
    static Integer code(String name) { return MAP.get(name.toUpperCase()); }
}
