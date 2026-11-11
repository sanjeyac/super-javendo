package dev.superjavendo.frontend.legacy;

import java.awt.Color;
import java.awt.Font;

/** SNES-flavoured palette shared by the frontend windows (light grey console body, purple/red accents). */
final class Theme {
    static final Color BODY = new Color(0xC9, 0xC6, 0xCF), BODY_DARK = new Color(0x8E, 0x8A, 0x99),
            PANEL = new Color(0x2B, 0x28, 0x3A), INK = new Color(0xEE, 0xEC, 0xF2),
            PURPLE = new Color(0x6B, 0x4C, 0x9A), RED = new Color(0xC0, 0x39, 0x4B), YELLOW = new Color(0xE0, 0xB4, 0x3C),
            GREEN = new Color(0x3F, 0x9B, 0x5E), BLUE = new Color(0x3E, 0x62, 0xB5), BG = new Color(0x1F, 0x1D, 0x26);
    static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 18), TEXT = new Font(Font.SANS_SERIF, Font.PLAIN, 14);

    private Theme() { }
}
