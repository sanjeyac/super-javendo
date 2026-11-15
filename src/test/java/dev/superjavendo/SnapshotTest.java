package dev.superjavendo;

import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.input.Joypad;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotTest {
    /** Reads the pad every frame (NMI handler: LDA $4219, adds to $10) vs a program that never reads it. */
    private static Cartridge rom(boolean readsPad) {
        byte[] rom = new byte[0x8000];
        int[] code = readsPad
                ? new int[]{0xA9, 0x81, 0x8D, 0x00, 0x42, 0xAD, 0x19, 0x42, 0x18, 0x65, 0x10, 0x85, 0x10, 0x4C, 0x05, 0x80}
                : new int[]{0xA9, 0x81, 0x8D, 0x00, 0x42, 0xE6, 0x10, 0x4C, 0x05, 0x80};
        for (int i = 0; i < code.length; i++) rom[i] = (byte) code[i];
        java.util.Arrays.fill(rom, 0x7FC0, 0x7FC0 + 21, (byte) ' ');
        rom[0x7FD5] = 0x20; rom[0x7FDC] = (byte) 0xFF; rom[0x7FDD] = (byte) 0xFF;
        rom[0x7FFD] = (byte) 0x80; rom[0x7FFB] = (byte) 0x81; rom[0x7FFF] = (byte) 0x82;
        rom[0x100] = 0x40; rom[0x200] = 0x40;
        return new Cartridge(rom);
    }

    private static void run(Snes s, int frames, int pad) {
        for (int i = 0; i < frames; i++) { s.joypad.setButtons(0, (pad + i * 16) & 0xFFF0); s.runFrame(); }
    }

    @Test void restoreGivesTheSameConsoleAndTheSameFuture() {
        Snes s = new Snes(rom(true));
        run(s, 20, Joypad.RIGHT);
        byte[] snap = s.snapshot();
        int hash = s.stateHash();
        run(s, 30, Joypad.A);
        int future = s.stateHash();
        assertNotEquals(hash, future);
        s.restore(snap);
        assertEquals(hash, s.stateHash());
        run(s, 30, Joypad.A);
        assertEquals(future, s.stateHash());
    }

    @Test void snapshotLoadsIntoAFreshConsole() {
        Snes a = new Snes(rom(true));
        run(a, 15, Joypad.B);
        Snes b = new Snes(rom(true));
        b.restore(a.snapshot());
        assertEquals(a.stateHash(), b.stateHash());
        run(a, 10, Joypad.UP); run(b, 10, Joypad.UP);
        assertEquals(a.stateHash(), b.stateHash());
    }

    @Test void snapshotIsDeterministic() {
        Snes a = new Snes(rom(true)), b = new Snes(rom(true));
        run(a, 12, Joypad.LEFT); run(b, 12, Joypad.LEFT);
        assertArrayEquals(a.snapshot(), b.snapshot());
    }

    @Test void lagFrameIsDetected() {
        Snes reads = new Snes(rom(true)), ignores = new Snes(rom(false));
        run(reads, 5, 0); run(ignores, 5, 0);
        assertFalse(reads.lastFrameLagged);
        assertTrue(ignores.lastFrameLagged);
    }
}
