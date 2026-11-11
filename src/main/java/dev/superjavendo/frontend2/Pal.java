package dev.superjavendo.frontend2;

import java.awt.Color;
import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Colours and fonts of the v2 frontend (see docs/ui-design: dark grey-blue with a single purple accent). */
final class Pal {
    static final Color BAR = new Color(0x0d1016), MAIN = new Color(0x12151c), SIDE = new Color(0x151921),
            CARD = new Color(0x1b2029), CARD_LINE = new Color(0x2b3240), SEP = new Color(0x232835),
            ACTIVE = new Color(0x2a2f45), ROW_SEL = new Color(0x232838),
            TXT = new Color(0xe6e9ef), TXT2 = new Color(0xaab2c2), TXT3 = new Color(0x8a93a5),
            ACCENT = new Color(0x5b4bd6), ACCENT_L = new Color(0xb4aaff),
            PLAY = new Color(0x3fbf6f), PLAY_TXT = new Color(0x06180d), RUN = new Color(0x4fd17f), STAR = new Color(0xf5c542),
            OK = new Color(0x8be8ad), WARN = new Color(0xf6d48a), DUP = new Color(0xa9c7ff), UNK = new Color(0xdcc4ff), ERR = new Color(0xffb3bc);

    private static final Map<String, Font> CACHE = new HashMap<>();

    private Pal() { }

    private static Font load(String resource, String fallback, int style) {
        return CACHE.computeIfAbsent(resource, r -> {
            try (InputStream in = Pal.class.getResourceAsStream("/fonts/" + r)) {
                if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception ignored) { }
            return new Font(fallback, style, 12);
        });
    }

    /** Inter (body text). weight: 0 regular, 1 medium, 2 semibold. */
    static Font ui(float size, int weight) {
        String f = weight == 0 ? "Inter-Regular.ttf" : weight == 1 ? "Inter-Medium.ttf" : "Inter-SemiBold.ttf";
        return load(f, Font.SANS_SERIF, weight == 2 ? Font.BOLD : Font.PLAIN).deriveFont(size);
    }

    /** Space Grotesk (logo and titles). */
    static Font title(float size) { return load("SpaceGrotesk-Bold.ttf", Font.SANS_SERIF, Font.BOLD).deriveFont(size); }

    static Font mono(float size) { return new Font(Font.MONOSPACED, Font.PLAIN, 12).deriveFont(size); }
}
