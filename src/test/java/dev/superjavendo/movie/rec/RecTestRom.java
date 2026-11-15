package dev.superjavendo.movie.rec;

import dev.superjavendo.cart.Cartridge;

/** Tiny cartridges for the Rec editor tests. */
final class RecTestRom {
    private RecTestRom() { }

    /** Auto-joypad on; the main loop keeps adding the pad's high byte ($4219) to WRAM $10: every input leaves a trace. */
    static Cartridge readsPad() {
        return build(new int[]{0xA9, 0x81, 0x8D, 0x00, 0x42,
                0xAD, 0x19, 0x42, 0x18, 0x65, 0x10, 0x85, 0x10, 0x4C, 0x05, 0x80});
    }

    /** Never reads the controllers: every frame is a lag frame. */
    static Cartridge ignoresPad() {
        return build(new int[]{0xA9, 0x81, 0x8D, 0x00, 0x42, 0xE6, 0x10, 0x4C, 0x05, 0x80});
    }

    private static Cartridge build(int[] code) {
        byte[] rom = new byte[0x8000];
        for (int i = 0; i < code.length; i++) rom[i] = (byte) code[i];
        java.util.Arrays.fill(rom, 0x7FC0, 0x7FC0 + 21, (byte) ' ');
        rom[0x7FD5] = 0x20; rom[0x7FDC] = (byte) 0xFF; rom[0x7FDD] = (byte) 0xFF;
        rom[0x7FFD] = (byte) 0x80; rom[0x7FFB] = (byte) 0x81; rom[0x7FFF] = (byte) 0x82;
        rom[0x100] = 0x40; rom[0x200] = 0x40;
        return new Cartridge(rom);
    }
}
