package dev.superjavendo.cart;

/** Coprocessor fitted to a cartridge, derived from the "ROM type" byte of the header. */
public enum Chip {
    NONE("none"), DSP("DSP-n"), SUPERFX("SuperFX (GSU)"), OBC1("OBC1"), SA1("SA-1"), SDD1("S-DD1"),
    SRTC("S-RTC"), SPC7110("SPC7110"), CX4("Cx4"), SETA("ST010/ST011/ST018"), OTHER("unknown coprocessor");

    public final String label;
    Chip(String label) { this.label = label; }

    public boolean supported() { return this == NONE || this == SUPERFX || this == DSP || this == OBC1 || this == CX4; }

    /** Decodes the header "ROM type" byte ($FFD6 / $7FD6). */
    public static Chip fromRomType(int type) {
        type &= 0xFF;
        int lo = type & 0x0F, hi = type >> 4;
        if (lo < 3) return NONE;                 // ROM / RAM / RAM+battery only
        switch (hi) {
            case 0: return DSP;
            case 1: return SUPERFX;
            case 2: return OBC1;
            case 3: return SA1;
            case 4: return SDD1;
            case 5: return SRTC;
            case 0xF: return switch (type) {
                case 0xF3 -> CX4;
                case 0xF5, 0xF9 -> SPC7110;
                case 0xF6 -> SETA;
                default -> OTHER;
            };
            default: return OTHER;
        }
    }
}
