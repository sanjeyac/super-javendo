package dev.superjavendo.cart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SuperFxTest {
    /** A GSU with the given program at ROM $00:8000 (file offset 0), started through the CPU-side registers. */
    private static SuperFx run(int... code) {
        byte[] rom = new byte[0x8000];
        for (int i = 0; i < code.length; i++) rom[i] = (byte) code[i];
        SuperFx fx = new SuperFx(rom, 0x10000);
        fx.write(0, 0x301E, 0x00);
        fx.write(0, 0x301F, 0x80);                 // R15 = $8000: starts the chip
        for (int i = 0; i < 20000 && (fx.read(0, 0x3030) & 0x20) != 0; i++) fx.tick(16);
        return fx;
    }

    private static int reg(SuperFx fx, int n) { return fx.read(0, 0x3000 + n * 2) | (fx.read(0, 0x3001 + n * 2) << 8); }

    @Test void ibtAndAdd() {
        // IBT R1,#5 ; IBT R2,#7 ; WITH R1 ; ADD R2 -> R1 = 12 ; STOP ; NOP
        SuperFx fx = run(0xA1, 5, 0xA2, 7, 0x21, 0x52, 0x00, 0x01);
        assertEquals(12, reg(fx, 1));
    }

    @Test void mergeSetsZeroFlagWhenNonZero() {
        // MERGE with R7=R8=0 -> result 0 and Z clear (hardware quirk); then STOP
        SuperFx fx = run(0x70, 0x00, 0x01);
        assertEquals(0, fx.read(0, 0x3030) & 0x02);
    }

    @Test void branchTargetIsRelativeToTheInstructionAfterTheBranch() {
        // BRA +3 ; NOP(delay slot, $8002) ; IBT R1,#9 ($8003-4, skipped) ; IBT R2,#4 ($8005) ; STOP ; NOP
        // target = address after the BRA ($8002) + 3 = $8005
        SuperFx fx = run(0x05, 0x03, 0x01, 0xA1, 9, 0xA2, 4, 0x00, 0x01);
        assertEquals(0, reg(fx, 1));
        assertEquals(4, reg(fx, 2));
    }

    @Test void loopRunsR12Times() {
        // IBT R12,#3 ; MOVE R13,R15 (WITH R15 ; TO R13) ; INC R1 ; LOOP ; NOP ; STOP ; NOP
        SuperFx fx = run(0xAC, 3, 0x2F, 0x1D, 0xD1, 0x3C, 0x01, 0x00, 0x01);
        assertEquals(3, reg(fx, 1));
    }

    @Test void plotWritesPixelIntoRam() {
        // IBT R0,#1 ; COLOR ; IBT R1,#0 ; IBT R2,#0 ; PLOT ; STOP ; NOP
        SuperFx fx = run(0xA0, 1, 0x4E, 0xA1, 0, 0xA2, 0, 0x4C, 0x00, 0x01);
        // default SCMR (2bpp, 128 high): tile 0, row 0, pixel 0 -> plane 0 bit 7 of the first byte
        assertEquals(0x80, fx.ram()[0] & 0x80);
        assertEquals(1, reg(fx, 1));                  // PLOT advances X
    }

    @Test void branchKeepsPrefixForItsDelaySlot() {
        // ALT2 ; BRA +2 ; ADD #3 (delay slot, ALT2 => immediate form: R0 = R0 + 3) ; NOP (skipped) ; STOP ; NOP
        // Yoshi's Island relies on this: the prefix set before a branch applies to the delay-slot instruction.
        SuperFx fx = run(0x3E, 0x05, 0x02, 0x53, 0x01, 0x00, 0x01);
        assertEquals(3, reg(fx, 0));
    }

    @Test void alt3DecodesLikeAlt1ForImmediateWordLoads() {
        // ALT3 ; IWT/LM R1,($0000) -> LM: R1 = word from RAM (power-on RAM is filled with $60) ; STOP ; NOP
        SuperFx fx = run(0x3F, 0xF1, 0x00, 0x00, 0x00, 0x01);
        assertEquals(0x6060, reg(fx, 1));
    }


    @Test void chipDetectionFromHeader() {
        assertEquals(Chip.SUPERFX, Chip.fromRomType(0x14));
        assertEquals(Chip.SUPERFX, Chip.fromRomType(0x1A));
        assertEquals(Chip.OBC1, Chip.fromRomType(0x25));
        assertEquals(Chip.SDD1, Chip.fromRomType(0x43));
        assertEquals(Chip.NONE, Chip.fromRomType(0x02));
        assertEquals(Chip.CX4, Chip.fromRomType(0xF3));
    }
}
