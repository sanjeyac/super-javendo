package dev.superjavendo.cpu;

import dev.superjavendo.bus.Memory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuTest {
    static final class Ram implements Memory {
        final byte[] m = new byte[0x1000000];
        public int read(int a) { return m[a] & 0xFF; }
        public void write(int a, int v) { m[a] = (byte) v; }
    }

    private Ram ram;
    private Cpu65816 cpu;

    /** Loads code at $008000 and resets the CPU. */
    private void run(int steps, int... code) {
        ram = new Ram();
        ram.m[0xFFFC] = 0x00; ram.m[0xFFFD] = (byte) 0x80;
        ram.m[0xFFFE] = 0x00; ram.m[0xFFFF] = (byte) 0x90; // IRQ/BRK emu
        ram.m[0xFFE6] = 0x00; ram.m[0xFFE7] = (byte) 0xA0; // BRK native
        for (int i = 0; i < code.length; i++) ram.m[0x8000 + i] = (byte) code[i];
        cpu = new Cpu65816(ram);
        cpu.reset();
        for (int i = 0; i < steps; i++) cpu.step();
    }

    @Test void lda8AndFlags() {
        run(1, 0xA9, 0x80);
        assertEquals(0x80, cpu.a & 0xFF);
        assertTrue(cpu.fN); assertFalse(cpu.fZ);
    }

    @Test void native16BitMode() {
        // CLC; XCE; REP #$30; LDA #$1234; CLC; ADC #$0001
        run(6, 0x18, 0xFB, 0xC2, 0x30, 0xA9, 0x34, 0x12, 0x18, 0x69, 0x01, 0x00);
        assertFalse(cpu.e);
        assertEquals(0x1235, cpu.a);
    }

    @Test void adcCarryAndOverflow8() {
        // LDA #$7F; ADC #$01 -> $80, V set, C clear
        run(2, 0xA9, 0x7F, 0x69, 0x01);
        assertEquals(0x80, cpu.a & 0xFF);
        assertTrue(cpu.fV); assertFalse(cpu.fC);
    }

    @Test void decimalAdc() {
        // SED; CLC; LDA #$19; ADC #$28 -> $47
        run(4, 0xF8, 0x18, 0xA9, 0x19, 0x69, 0x28);
        assertEquals(0x47, cpu.a & 0xFF);
    }

    @Test void decimalSbc() {
        // SED; SEC; LDA #$42; SBC #$13 -> $29
        run(4, 0xF8, 0x38, 0xA9, 0x42, 0xE9, 0x13);
        assertEquals(0x29, cpu.a & 0xFF);
        assertTrue(cpu.fC);
    }

    @Test void branchTaken() {
        // LDA #$00; BEQ +2; LDA #$FF; (target) LDX #$55
        run(3, 0xA9, 0x00, 0xF0, 0x02, 0xA9, 0xFF, 0xA2, 0x55);
        assertEquals(0x55, cpu.x);
        assertEquals(0x00, cpu.a & 0xFF);
    }

    @Test void jsrRts() {
        // JSR $8010; LDX #$01 ... $8010: LDY #$02; RTS
        run(4, 0x20, 0x10, 0x80, 0xA2, 0x01);
        ram.m[0x8010] = (byte) 0xA0; ram.m[0x8011] = 0x02; ram.m[0x8012] = 0x60;
        // re-run with subroutine in place
        cpu = new Cpu65816(ram); cpu.reset();
        for (int i = 0; i < 4; i++) cpu.step();
        assertEquals(2, cpu.y);
        assertEquals(1, cpu.x);
    }

    @Test void storeAndLoadAbsolute() {
        // LDA #$AB; STA $2000 (DB=0); LDX $2000
        run(3, 0xA9, 0xAB, 0x8D, 0x00, 0x20, 0xAE, 0x00, 0x20);
        assertEquals(0xAB, ram.m[0x2000] & 0xFF);
        assertEquals(0xAB, cpu.x);
    }

    @Test void blockMoveMvn() {
        // native, 16-bit: LDA #$0002 (3 bytes); LDX #$9000; LDY #$A000; MVN 00,00
        run(0, 0x18, 0xFB, 0xC2, 0x30, 0xA9, 0x02, 0x00, 0xA2, 0x00, 0x90,
            0xA0, 0x00, 0xA0, 0x54, 0x00, 0x00);
        ram.m[0x9000] = 1; ram.m[0x9001] = 2; ram.m[0x9002] = 3;
        for (int i = 0; i < 6 + 3; i++) cpu.step();
        assertEquals(1, ram.m[0xA000]); assertEquals(2, ram.m[0xA001]); assertEquals(3, ram.m[0xA002]);
        assertEquals(0xFFFF, cpu.a);
    }

    @Test void brkEmulationVector() {
        run(1, 0x00, 0x00);
        assertEquals(0x9000, cpu.pc);
        assertTrue(cpu.fI);
    }

    @Test void cmpSetsCarry() {
        run(2, 0xA9, 0x10, 0xC9, 0x10);
        assertTrue(cpu.fC); assertTrue(cpu.fZ);
    }

    @Test void xbaSwaps() {
        // CLC; XCE; REP #$20; LDA #$12 34 ; XBA
        run(5, 0x18, 0xFB, 0xC2, 0x20, 0xA9, 0x34, 0x12, 0xEB);
        assertEquals(0x3412, cpu.a);
    }
}
