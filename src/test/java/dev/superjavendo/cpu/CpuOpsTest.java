package dev.superjavendo.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Wider instruction coverage for the 65816: addressing modes, memory/register operations, stack, control flow, interrupts. */
class CpuOpsTest {
    private final CpuTest.Ram ram = new CpuTest.Ram();
    private Cpu65816 cpu;

    private static final int[] NATIVE16 = {0x18, 0xFB, 0xC2, 0x30};       // CLC ; XCE ; REP #$30

    private void vec(int addr, int target) { ram.m[addr] = (byte) target; ram.m[addr + 1] = (byte) (target >> 8); }

    private void boot(int... code) {
        vec(0xFFFC, 0x8000);
        vec(0xFFFE, 0x9000); vec(0xFFFA, 0x9100); vec(0xFFF4, 0x9200);        // emulation: IRQ/BRK, NMI, COP
        vec(0xFFE6, 0xA000); vec(0xFFEA, 0xA100); vec(0xFFE4, 0xA200); vec(0xFFEE, 0xA300);   // native: BRK, NMI, COP, IRQ
        for (int i = 0; i < code.length; i++) ram.m[0x8000 + i] = (byte) code[i];
        cpu = new Cpu65816(ram);
        cpu.reset();
    }

    /** Runs until the program counter leaves the code (or 500 steps). */
    private void exec(int... code) {
        boot(code);
        for (int i = 0; i < 500 && cpu.pb == 0 && cpu.pc >= 0x8000 && cpu.pc < 0x8000 + code.length; i++) cpu.step();
    }

    private static int[] cat(int[]... parts) {
        int n = 0; for (int[] p : parts) n += p.length;
        int[] r = new int[n]; int i = 0;
        for (int[] p : parts) { System.arraycopy(p, 0, r, i, p.length); i += p.length; }
        return r;
    }

    private int m(int a) { return ram.m[a] & 0xFF; }
    private void poke(int a, int... v) { for (int i = 0; i < v.length; i++) ram.m[a + i] = (byte) v[i]; }

    // ------------------------------------------------------------------ addressing modes

    @Test void everyLoadAddressingMode() {
        poke(0x10, 0x11);
        poke(0x72, 0x00, 0x03); poke(0x300, 0x22);                 // (d,x)
        poke(0x200, 0x33);                                         // sr
        poke(0x40, 0x00, 0x04, 0x00); poke(0x400, 0x44);           // [dp]
        poke(0x500, 0x55);
        poke(0x600, 0x66);
        poke(0x20, 0x00, 0x07); poke(0x703, 0x77);                 // (dp),y
        poke(0x30, 0x00, 0x08); poke(0x800, 0x88);                 // (dp)
        poke(0x202, 0x00, 0x09); poke(0x901, 0x99);                // (sr),y
        poke(0x64, 0xAA);                                          // dp,x
        poke(0x50, 0x00, 0x0A, 0x00); poke(0xA02, 0xBB);           // [dp],y
        poke(0xB05, 0xCC);                                         // abs,y
        poke(0xC06, 0xDD);                                         // abs,x
        poke(0xD07, 0xEE);                                         // long,x
        Object[][] cases = {
                {0x11, new int[]{0xA5, 0x10}},
                {0x22, new int[]{0xA2, 0x02, 0xA1, 0x70}},
                {0x33, new int[]{0xA3, 0x01}},
                {0x44, new int[]{0xA7, 0x40}},
                {0x55, new int[]{0xAD, 0x00, 0x05}},
                {0x66, new int[]{0xAF, 0x00, 0x06, 0x00}},
                {0x77, new int[]{0xA0, 0x03, 0xB1, 0x20}},
                {0x88, new int[]{0xB2, 0x30}},
                {0x99, new int[]{0xA0, 0x01, 0xB3, 0x03}},
                {0xAA, new int[]{0xA2, 0x04, 0xB5, 0x60}},
                {0xBB, new int[]{0xA0, 0x02, 0xB7, 0x50}},
                {0xCC, new int[]{0xA0, 0x05, 0xB9, 0x00, 0x0B}},
                {0xDD, new int[]{0xA2, 0x06, 0xBD, 0x00, 0x0C}},
                {0xEE, new int[]{0xA2, 0x07, 0xBF, 0x00, 0x0D, 0x00}},
        };
        int n = 0;
        for (Object[] c : cases) {
            exec((int[]) c[1]);
            assertEquals((int) c[0], cpu.a & 0xFF, "LDA addressing mode case " + n++);
        }
    }

    @Test void everyStoreAddressingMode() {
        poke(0x72, 0x00, 0x03);                                    // (d,x) -> $0300
        poke(0x40, 0x00, 0x04, 0x00);                              // [dp]  -> $0400
        poke(0x20, 0x00, 0x07);                                    // (dp),y-> $0700 + y
        poke(0x30, 0x00, 0x08);                                    // (dp)  -> $0800
        poke(0x50, 0x00, 0x0A, 0x00);                              // [dp],y-> $0A00 + y
        poke(0x202, 0x00, 0x09);                                   // (sr),y-> $0900 + y
        int[][] code = {
                {0xA2, 0x02, 0x81, 0x70}, {0xA2, 0x04, 0x95, 0x60}, {0x83, 0x05}, {0x87, 0x40},
                {0x8D, 0x00, 0x05}, {0x8F, 0x00, 0x06, 0x00}, {0xA0, 0x03, 0x91, 0x20}, {0x92, 0x30},
                {0xA0, 0x01, 0x93, 0x03}, {0xA0, 0x02, 0x97, 0x50}, {0xA0, 0x05, 0x99, 0x00, 0x0B},
                {0xA2, 0x06, 0x9D, 0x00, 0x0C}, {0xA2, 0x07, 0x9F, 0x00, 0x0D, 0x00},
        };
        int[] where = {0x300, 0x64, 0x204, 0x400, 0x500, 0x600, 0x703, 0x800, 0x901, 0xA02, 0xB05, 0xC06, 0xD07};
        for (int i = 0; i < code.length; i++) {
            exec(cat(new int[]{0xA9, 0x5A}, code[i]));
            assertEquals(0x5A, m(where[i]), "store case " + i);
        }
    }

    @Test void storeXYAndZero() {
        exec(0xA2, 0x12, 0x86, 0x10, 0x8E, 0x00, 0x03, 0xA0, 0x34, 0x84, 0x11, 0x8C, 0x01, 0x03, 0x74, 0x10, 0x9C, 0x01, 0x03);
        assertEquals(0x12, m(0x10));
        assertEquals(0x12, m(0x300));
        assertEquals(0x34, m(0x11));
        assertEquals(0, m(0x301));
        exec(0xA2, 0x02, 0xA0, 0x07, 0x96, 0x10, 0x94, 0x20);       // STX dp,y ; STY dp,x
        assertEquals(0x02, m(0x17));
        assertEquals(0x07, m(0x22));
    }

    // ------------------------------------------------------------------ memory and accumulator operations

    @Test void shiftsAndRotatesOnMemory() {
        poke(0x300, 0x81); exec(0x0E, 0x00, 0x03);                  // ASL abs
        assertEquals(0x02, m(0x300)); assertTrue(cpu.fC);
        poke(0x10, 0x03); exec(0x46, 0x10);                         // LSR dp
        assertEquals(0x01, m(0x10)); assertTrue(cpu.fC);
        poke(0x10, 0x80); exec(0x38, 0x26, 0x10);                   // SEC ; ROL dp
        assertEquals(0x01, m(0x10)); assertTrue(cpu.fC);
        poke(0x10, 0x01); exec(0x38, 0x66, 0x10);                   // SEC ; ROR dp
        assertEquals(0x80, m(0x10)); assertTrue(cpu.fC); assertTrue(cpu.fN);
        poke(0x14, 0x40); exec(0xA2, 0x04, 0x16, 0x10);             // ASL dp,x
        assertEquals(0x80, m(0x14));
        poke(0x305, 0x02); exec(0xA2, 0x05, 0x5E, 0x00, 0x03);      // LSR abs,x
        assertEquals(0x01, m(0x305));
        poke(0x306, 0x40); exec(0xA2, 0x06, 0x3E, 0x00, 0x03);      // ROL abs,x
        assertEquals(0x80, m(0x306));
        poke(0x307, 0x02); exec(0xA2, 0x07, 0x7E, 0x00, 0x03);      // ROR abs,x
        assertEquals(0x01, m(0x307));
    }

    @Test void accumulatorShiftsAndIncDec() {
        exec(0xA9, 0x81, 0x0A); assertEquals(0x02, cpu.a & 0xFF); assertTrue(cpu.fC);
        exec(0xA9, 0x03, 0x4A); assertEquals(0x01, cpu.a & 0xFF); assertTrue(cpu.fC);
        exec(0xA9, 0x80, 0x38, 0x2A); assertEquals(0x01, cpu.a & 0xFF); assertTrue(cpu.fC);
        exec(0xA9, 0x01, 0x38, 0x6A); assertEquals(0x80, cpu.a & 0xFF); assertTrue(cpu.fC);
        exec(0xA9, 0xFF, 0x1A); assertEquals(0, cpu.a & 0xFF); assertTrue(cpu.fZ);
        exec(0xA9, 0x00, 0x3A); assertEquals(0xFF, cpu.a & 0xFF); assertTrue(cpu.fN);
        exec(0xA2, 0xFF, 0xE8); assertEquals(0, cpu.x); assertTrue(cpu.fZ);
        exec(0xA0, 0x00, 0x88); assertEquals(0xFF, cpu.y);
        exec(0xA2, 0x00, 0xCA); assertEquals(0xFF, cpu.x);
        exec(0xA0, 0xFF, 0xC8); assertEquals(0, cpu.y);
    }

    @Test void incrementAndDecrementMemory() {
        poke(0x10, 0x7F); exec(0xE6, 0x10); assertEquals(0x80, m(0x10)); assertTrue(cpu.fN);
        poke(0x10, 0x00); exec(0xC6, 0x10); assertEquals(0xFF, m(0x10));
        poke(0x300, 0xFF); exec(0xEE, 0x00, 0x03); assertEquals(0, m(0x300)); assertTrue(cpu.fZ);
        poke(0x300, 0x01); exec(0xCE, 0x00, 0x03); assertEquals(0, m(0x300));
        poke(0x14, 0x05); exec(0xA2, 0x04, 0xF6, 0x10); assertEquals(0x06, m(0x14));
        poke(0x14, 0x05); exec(0xA2, 0x04, 0xD6, 0x10); assertEquals(0x04, m(0x14));
        poke(0x303, 0x05); exec(0xA2, 0x03, 0xFE, 0x00, 0x03); assertEquals(0x06, m(0x303));
        poke(0x303, 0x05); exec(0xA2, 0x03, 0xDE, 0x00, 0x03); assertEquals(0x04, m(0x303));
    }

    @Test void testAndSetOrResetBits() {
        poke(0x10, 0xF0); exec(0xA9, 0x0F, 0x04, 0x10);             // TSB dp
        assertEquals(0xFF, m(0x10)); assertTrue(cpu.fZ);
        poke(0x10, 0xFF); exec(0xA9, 0x0F, 0x14, 0x10);             // TRB dp
        assertEquals(0xF0, m(0x10)); assertFalse(cpu.fZ);
        poke(0x300, 0x01); exec(0xA9, 0x02, 0x0C, 0x00, 0x03);      // TSB abs
        assertEquals(0x03, m(0x300));
        poke(0x300, 0x03); exec(0xA9, 0x02, 0x1C, 0x00, 0x03);      // TRB abs
        assertEquals(0x01, m(0x300));
    }

    @Test void bitInstructions() {
        poke(0x10, 0xC0); exec(0xA9, 0x01, 0x24, 0x10);             // BIT dp: N and V come from the memory
        assertTrue(cpu.fN); assertTrue(cpu.fV); assertTrue(cpu.fZ);
        exec(0xA9, 0x01, 0xB8, 0x89, 0x80);                         // BIT #: only Z
        assertTrue(cpu.fZ); assertFalse(cpu.fN); assertFalse(cpu.fV);
        poke(0x300, 0x40); exec(0xA9, 0x40, 0x2C, 0x00, 0x03);      // BIT abs
        assertFalse(cpu.fZ); assertTrue(cpu.fV);
        poke(0x14, 0x80); exec(0xA9, 0x80, 0xA2, 0x04, 0x34, 0x10); // BIT dp,x
        assertTrue(cpu.fN);
        poke(0x303, 0x80); exec(0xA9, 0x80, 0xA2, 0x03, 0x3C, 0x00, 0x03);   // BIT abs,x
        assertTrue(cpu.fN); assertFalse(cpu.fZ);
    }

    @Test void compareInstructions() {
        exec(0xA9, 0x05, 0xC9, 0x05); assertTrue(cpu.fZ); assertTrue(cpu.fC);
        exec(0xA9, 0x05, 0xC9, 0x06); assertFalse(cpu.fZ); assertFalse(cpu.fC); assertTrue(cpu.fN);
        exec(0xA2, 0x10, 0xE0, 0x0F); assertTrue(cpu.fC); assertFalse(cpu.fZ);
        exec(0xA0, 0x10, 0xC0, 0x10); assertTrue(cpu.fZ);
        poke(0x10, 0x20); exec(0xA2, 0x20, 0xE4, 0x10); assertTrue(cpu.fZ);
        exec(0xA0, 0x20, 0xC4, 0x10); assertTrue(cpu.fZ);
        poke(0x300, 0x20); exec(0xA2, 0x20, 0xEC, 0x00, 0x03); assertTrue(cpu.fZ);
        exec(0xA0, 0x20, 0xCC, 0x00, 0x03); assertTrue(cpu.fZ);
        poke(0x10, 0x30); exec(0xA9, 0x30, 0xC5, 0x10); assertTrue(cpu.fZ);
        poke(0x300, 0x30); exec(0xA9, 0x30, 0xCD, 0x00, 0x03); assertTrue(cpu.fZ);
    }

    @Test void logicalOperations() {
        exec(0xA9, 0xF0, 0x29, 0x3C); assertEquals(0x30, cpu.a & 0xFF);
        exec(0xA9, 0xF0, 0x09, 0x0F); assertEquals(0xFF, cpu.a & 0xFF);
        exec(0xA9, 0xFF, 0x49, 0x0F); assertEquals(0xF0, cpu.a & 0xFF);
        poke(0x10, 0x0F); exec(0xA9, 0xFF, 0x25, 0x10); assertEquals(0x0F, cpu.a & 0xFF);
        poke(0x10, 0xF0); exec(0xA9, 0x0F, 0x05, 0x10); assertEquals(0xFF, cpu.a & 0xFF);
        poke(0x10, 0xFF); exec(0xA9, 0x0F, 0x45, 0x10); assertEquals(0xF0, cpu.a & 0xFF);
        poke(0x300, 0xF0); exec(0xA9, 0x3C, 0x2D, 0x00, 0x03); assertEquals(0x30, cpu.a & 0xFF);
        poke(0x300, 0x01); exec(0xA9, 0x02, 0x0D, 0x00, 0x03); assertEquals(0x03, cpu.a & 0xFF);
        poke(0x300, 0xFF); exec(0xA9, 0x0F, 0x4D, 0x00, 0x03); assertEquals(0xF0, cpu.a & 0xFF);
    }

    @Test void arithmeticModesAndBorrow() {
        poke(0x10, 0x05); exec(0x18, 0xA9, 0x10, 0x65, 0x10); assertEquals(0x15, cpu.a & 0xFF);
        poke(0x300, 0x05); exec(0x38, 0xA9, 0x10, 0xED, 0x00, 0x03); assertEquals(0x0B, cpu.a & 0xFF); assertTrue(cpu.fC);
        exec(0x38, 0xA9, 0x05, 0xE9, 0x06); assertEquals(0xFF, cpu.a & 0xFF); assertFalse(cpu.fC);
        exec(0x38, 0xA9, 0x50, 0xE9, 0xB0); assertTrue(cpu.fV);
        exec(0x38, 0xA9, 0x00, 0xE9, 0x00); assertTrue(cpu.fZ);
        exec(0x18, 0xA9, 0xFF, 0x69, 0x01); assertTrue(cpu.fC); assertTrue(cpu.fZ);
    }

    @Test void sixteenBitArithmeticAndDecimal() {
        exec(cat(NATIVE16, new int[]{0xF8, 0x18, 0xA9, 0x99, 0x19, 0x69, 0x01, 0x00}));   // SED CLC LDA #$1999 ADC #$0001 -> $2000
        assertEquals(0x2000, cpu.a);
        exec(cat(NATIVE16, new int[]{0xF8, 0x38, 0xA9, 0x00, 0x20, 0xE9, 0x01, 0x00}));   // SBC -> $1999
        assertEquals(0x1999, cpu.a);
        exec(cat(NATIVE16, new int[]{0x18, 0xA9, 0xFF, 0xFF, 0x69, 0x01, 0x00}));
        assertEquals(0, cpu.a); assertTrue(cpu.fC);
    }

    // ------------------------------------------------------------------ registers, flags and transfers

    @Test void transfers() {
        exec(0xA9, 0x12, 0xAA); assertEquals(0x12, cpu.x);
        exec(0xA9, 0x13, 0xA8); assertEquals(0x13, cpu.y);
        exec(0xA2, 0x14, 0x8A); assertEquals(0x14, cpu.a & 0xFF);
        exec(0xA0, 0x15, 0x98); assertEquals(0x15, cpu.a & 0xFF);
        exec(0xBA); assertEquals(cpu.s & 0xFF, cpu.x);
        exec(0xA2, 0x80, 0x9A); assertEquals(0x180, cpu.s);                      // TXS in emulation mode keeps page 1
        exec(0xA2, 0x21, 0x9B); assertEquals(0x21, cpu.y);
        exec(0xA0, 0x22, 0xBB); assertEquals(0x22, cpu.x);
    }

    @Test void sixteenBitTransfersAndExchange() {
        exec(cat(NATIVE16, new int[]{0xA9, 0x34, 0x12, 0x5B}));                      // TCD
        assertEquals(0x1234, cpu.d);
        exec(cat(NATIVE16, new int[]{0xA9, 0x00, 0x00, 0x7B}));                      // TDC (D = 0)
        assertEquals(0, cpu.a);
        exec(cat(NATIVE16, new int[]{0xA9, 0x00, 0x20, 0x1B}));                      // TCS
        assertEquals(0x2000, cpu.s);
        exec(cat(NATIVE16, new int[]{0x3B}));                                        // TSC
        assertEquals(cpu.s, cpu.a);
        exec(cat(NATIVE16, new int[]{0xA9, 0x34, 0x12, 0xEB}));                      // XBA
        assertEquals(0x3412, cpu.a);
        exec(cat(NATIVE16, new int[]{0xA2, 0x01, 0x02, 0x8A}));                      // TXA 16 bit
        assertEquals(0x0201, cpu.a);
    }

    @Test void flagInstructions() {
        exec(0x38); assertTrue(cpu.fC);
        exec(0x38, 0x18); assertFalse(cpu.fC);
        exec(0x78); assertTrue(cpu.fI);
        exec(0x78, 0x58); assertFalse(cpu.fI);
        exec(0xF8); assertTrue(cpu.fD);
        exec(0xF8, 0xD8); assertFalse(cpu.fD);
        exec(0xA9, 0x7F, 0x69, 0x01, 0xB8); assertFalse(cpu.fV);
        exec(0x18, 0xFB); assertFalse(cpu.e);
        exec(0x18, 0xFB, 0x38, 0xFB); assertTrue(cpu.e);
        exec(0x18, 0xFB, 0xC2, 0x30); assertFalse(cpu.fM); assertFalse(cpu.fX);
        exec(0x18, 0xFB, 0xC2, 0x30, 0xE2, 0x30); assertTrue(cpu.fM); assertTrue(cpu.fX);
    }

    @Test void switchingToEightBitIndexClearsTheHighBytes() {
        exec(cat(NATIVE16, new int[]{0xA2, 0x34, 0x12, 0xA0, 0x78, 0x56, 0xE2, 0x10}));   // SEP #$10
        assertEquals(0x34, cpu.x);
        assertEquals(0x78, cpu.y);
    }

    // ------------------------------------------------------------------ stack

    @Test void pushAndPullRegisters() {
        exec(0xA9, 0x11, 0x48, 0xA9, 0x00, 0x68); assertEquals(0x11, cpu.a & 0xFF);
        exec(0xA2, 0x22, 0xDA, 0xA2, 0x00, 0xFA); assertEquals(0x22, cpu.x);
        exec(0xA0, 0x33, 0x5A, 0xA0, 0x00, 0x7A); assertEquals(0x33, cpu.y);
        exec(0x38, 0x08, 0x18, 0x28); assertTrue(cpu.fC);
        exec(cat(NATIVE16, new int[]{0xE2, 0x20, 0xA9, 0x05, 0x48, 0xAB}));            // PHA ; PLB
        assertEquals(0x05, cpu.db);
        exec(cat(NATIVE16, new int[]{0x8B, 0xA9, 0x00, 0x00, 0x0B, 0x2B}));
        assertEquals(0, cpu.d);
        exec(0x4B); assertEquals(0x1FE, cpu.s);                                  // PHK pushed one byte
    }

    @Test void pushEffectiveAddresses() {
        exec(cat(NATIVE16, new int[]{0xF4, 0x34, 0x12, 0x68}));                       // PEA $1234 ; PLA
        assertEquals(0x1234, cpu.a);
        poke(0x10, 0x78, 0x56);
        exec(cat(NATIVE16, new int[]{0xD4, 0x10, 0x68}));                             // PEI ($10) ; PLA
        assertEquals(0x5678, cpu.a);
        exec(cat(NATIVE16, new int[]{0x62, 0x10, 0x00, 0x68}));                       // PER +$10 ; PLA
        assertEquals(0x8007 + 0x10, cpu.a);                                           // PC after the instruction + offset
    }

    // ------------------------------------------------------------------ control flow

    @Test void jumps() {
        boot(0x4C, 0x10, 0x80); cpu.step(); assertEquals(0x8010, cpu.pc);
        poke(0x300, 0x20, 0x80); boot(0x6C, 0x00, 0x03); cpu.step(); assertEquals(0x8020, cpu.pc);
        poke(0x310, 0x30, 0x80); boot(0xA2, 0x10, 0x7C, 0x00, 0x03); cpu.step(); cpu.step(); assertEquals(0x8030, cpu.pc);
        boot(0x5C, 0x40, 0x80, 0x00); cpu.step(); assertEquals(0x8040, cpu.pc); assertEquals(0, cpu.pb);
        poke(0x300, 0x50, 0x80, 0x00); boot(0xDC, 0x00, 0x03); cpu.step(); assertEquals(0x8050, cpu.pc);
    }

    @Test void subroutineCallsAndReturns() {
        poke(0x8010, 0xA0, 0x07, 0x6B);                                               // LDY #7 ; RTL
        boot(0x22, 0x10, 0x80, 0x00, 0xA2, 0x01);                                     // JSL ; LDX #1
        for (int i = 0; i < 4; i++) cpu.step();
        assertEquals(7, cpu.y); assertEquals(1, cpu.x);
        poke(0x8010, 0xA0, 0x08, 0x60);                                               // LDY #8 ; RTS
        poke(0x310, 0x10, 0x80);
        boot(0xA2, 0x10, 0xFC, 0x00, 0x03, 0xA2, 0x02);                               // JSR (abs,x)
        for (int i = 0; i < 5; i++) cpu.step();
        assertEquals(8, cpu.y); assertEquals(2, cpu.x);
    }

    @Test void allConditionalBranches() {
        // each case: flag set-up, branch opcode, whether it is taken. The branch skips a LDX #$FF.
        int[][] taken = {
                {0xA9, 0x00, 0xF0}, {0xA9, 0x01, 0xD0}, {0x38, 0xB0}, {0x18, 0x90}, {0xA9, 0x80, 0x30}, {0xA9, 0x01, 0x10},
                {0xA9, 0x7F, 0x69, 0x01, 0x70}, {0xB8, 0x50}, {0x18, 0x80},
        };
        for (int[] t : taken) {
            int[] code = cat(t, new int[]{0x02, 0xA2, 0xFF, 0xEA});
            exec(code);
            assertNotEquals(0xFF, cpu.x, "branch " + Integer.toHexString(t[t.length - 1]) + " should be taken");
        }
        int[][] notTaken = {{0xA9, 0x01, 0xF0}, {0xA9, 0x00, 0xD0}, {0x18, 0xB0}, {0x38, 0x90}, {0xA9, 0x01, 0x30}, {0xA9, 0x80, 0x10}};
        for (int[] t : notTaken) {
            int[] code = cat(t, new int[]{0x02, 0xA2, 0xFF, 0xEA});
            exec(code);
            assertEquals(0xFF, cpu.x, "branch " + Integer.toHexString(t[t.length - 1]) + " should not be taken");
        }
        exec(0x82, 0x02, 0x00, 0xA2, 0xFF, 0xEA);                                     // BRL skips LDX
        assertNotEquals(0xFF, cpu.x);
        exec(0x80, 0xFE);
        assertEquals(0x8000, cpu.pc);                                                 // BRA -2 loops on itself
    }

    @Test void blockMoves() {
        poke(0x1000, 1, 2, 3, 4);
        exec(cat(NATIVE16, new int[]{0xA9, 0x03, 0x00, 0xA2, 0x00, 0x10, 0xA0, 0x00, 0x20, 0x54, 0x00, 0x00}));   // MVN
        assertEquals(4, m(0x2003)); assertEquals(1, m(0x2000));
        assertEquals(0xFFFF, cpu.a);
        poke(0x1000, 5, 6, 7, 8);
        exec(cat(NATIVE16, new int[]{0xA9, 0x03, 0x00, 0xA2, 0x03, 0x10, 0xA0, 0x03, 0x20, 0x44, 0x00, 0x00}));   // MVP (backwards)
        assertEquals(5, m(0x2000)); assertEquals(8, m(0x2003));
    }

    // ------------------------------------------------------------------ interrupts and halting

    @Test void brkAndRtiInEmulationMode() {
        poke(0x9000, 0x40);                                                           // RTI
        boot(0xEA, 0x00, 0xFF, 0xA2, 0x05);                                           // NOP ; BRK #$FF ; LDX #5
        cpu.step(); cpu.step();
        assertEquals(0x9000, cpu.pc);
        assertTrue(cpu.fI);
        cpu.step();                                                                   // RTI returns after the signature byte
        assertEquals(0x8003, cpu.pc);
    }

    @Test void brkAndCopInNativeMode() {
        poke(0xA000, 0x40); poke(0xA200, 0x40);
        boot(cat(NATIVE16, new int[]{0x00, 0x00, 0x02, 0x00, 0xEA}));
        for (int i = 0; i < 4; i++) cpu.step();                                       // CLC XCE REP BRK
        assertEquals(0xA000, cpu.pc);
        cpu.step();
        assertEquals(0x8006, cpu.pc);
        cpu.step();
        assertEquals(0xA200, cpu.pc);
        cpu.step();
        assertEquals(0x8008, cpu.pc);
    }

    @Test void nmiAndIrqAreTakenAtTheNextInstruction() {
        boot(0xEA, 0xEA, 0xEA);
        cpu.step();
        cpu.nmiPending = true;
        cpu.step();                                                                   // the NMI is serviced instead of the next instruction
        assertEquals(0x9100, cpu.pc);
        boot(0x58, 0xEA, 0xEA);                                                       // CLI
        cpu.step();
        cpu.irqLine = true;
        cpu.step();
        assertEquals(0x9000, cpu.pc);
        boot(0x78, 0xEA, 0xEA);                                                       // SEI: IRQ masked
        cpu.step();
        cpu.irqLine = true;
        cpu.step();
        assertEquals(0x8002, cpu.pc);
    }

    @Test void nativeModeInterruptsUseTheNativeVectors() {
        boot(cat(NATIVE16, new int[]{0x58, 0xEA, 0xEA}));
        for (int i = 0; i < 4; i++) cpu.step();                                       // CLC XCE REP CLI
        cpu.irqLine = true;
        cpu.step();
        assertEquals(0xA300, cpu.pc);
        boot(cat(NATIVE16, new int[]{0xEA, 0xEA, 0xEA}));
        for (int i = 0; i < 3; i++) cpu.step();
        cpu.nmiPending = true;
        cpu.step();
        assertEquals(0xA100, cpu.pc);
    }

    @Test void waitForInterruptStopsUntilOneArrives() {
        boot(0xCB, 0xA2, 0x09);                                                       // WAI ; LDX #9
        cpu.step();
        assertTrue(cpu.waiting);
        int pc = cpu.pc;
        cpu.step();
        assertEquals(pc, cpu.pc);
        cpu.nmiPending = true;
        cpu.step();
        assertFalse(cpu.waiting);
        assertEquals(0x9100, cpu.pc);
    }

    @Test void stopHaltsTheProcessor() {
        boot(0xDB, 0xA2, 0x09);
        cpu.step();
        assertTrue(cpu.stopped);
        int pc = cpu.pc;
        cpu.step();
        assertEquals(pc, cpu.pc);
    }

    @Test void wdmAndNopSkipTheirBytes() {
        exec(0x42, 0xFF, 0xEA, 0xA2, 0x03);
        assertEquals(3, cpu.x);
    }

    @Test void statusRegisterRoundTripThroughSetP() {
        boot(0x18, 0xFB);
        cpu.step(); cpu.step();                                                       // native mode
        cpu.setP(0xCF);
        assertEquals(0xCF, cpu.getP());
        assertTrue(cpu.fN && cpu.fV && cpu.fD && cpu.fI && cpu.fZ && cpu.fC);
        assertFalse(cpu.fM || cpu.fX);
        cpu.setP(0x00);
        assertEquals(0x00, cpu.getP());
        boot();
        cpu.setP(0x00);
        assertEquals(0x30, cpu.getP());                                               // emulation mode forces M and X
    }

    @Test void stateRoundTrip() throws Exception {
        boot(cat(NATIVE16, new int[]{0xA9, 0x34, 0x12, 0xA2, 0x78, 0x56}));
        for (int i = 0; i < 5; i++) cpu.step();
        var bo = new java.io.ByteArrayOutputStream();
        cpu.save(new dev.superjavendo.state.StateOut(bo));
        Cpu65816 copy = new Cpu65816(ram);
        copy.load(new dev.superjavendo.state.StateIn(new java.io.ByteArrayInputStream(bo.toByteArray())));
        assertEquals(cpu.a, copy.a); assertEquals(cpu.x, copy.x); assertEquals(cpu.pc, copy.pc);
        assertEquals(cpu.e, copy.e); assertEquals(cpu.fM, copy.fM);
    }
}
