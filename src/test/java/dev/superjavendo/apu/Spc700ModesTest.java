package dev.superjavendo.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** SPC700 addressing modes, memory operations, stack, branches and bit instructions. Code is loaded at $0200. */
class Spc700ModesTest {
    private final ApuTest.Ram ram = new ApuTest.Ram();
    private Spc700 spc;

    private void boot(int... code) {
        for (int i = 0; i < code.length; i++) ram.m[0x200 + i] = (byte) code[i];
        spc = new Spc700(ram);
        spc.reset(0x200);
    }

    /** Runs until the program counter leaves the code. */
    private void exec(int... code) {
        boot(code);
        for (int i = 0; i < 300 && spc.pc >= 0x200 && spc.pc < 0x200 + code.length; i++) spc.step();
    }

    private void poke(int a, int... v) { for (int i = 0; i < v.length; i++) ram.m[a + i] = (byte) v[i]; }
    private int m(int a) { return ram.m[a] & 0xFF; }

    /** Flags (N V P B H I Z C) after running the code followed by PUSH PSW / POP A. */
    private int psw(int... code) {
        int[] full = new int[code.length + 2];
        System.arraycopy(code, 0, full, 0, code.length);
        full[code.length] = 0x0D; full[code.length + 1] = 0xAE;
        exec(full);
        return spc.a;
    }

    // ------------------------------------------------------------------ loads and stores

    @Test void everyLoadAddressingMode() {
        poke(0x10, 0x11); poke(0x14, 0x22); poke(0x300, 0x33); poke(0x304, 0x44); poke(0x305, 0x55);
        poke(0x20, 0x00, 0x04); poke(0x400, 0x66);                 // (dp+X) with X=2 -> pointer at $22
        poke(0x22, 0x00, 0x04);
        poke(0x30, 0x00, 0x05); poke(0x503, 0x77);                 // (dp)+Y with Y=3
        poke(0x06, 0x88);                                          // (X) with X=6
        int[][] cases = {
                {0xE4, 0x10}, {0x8F, 0x00, 0x00, 0xCD, 0x04, 0xF4, 0x10}, {0xE5, 0x00, 0x03},
                {0xCD, 0x04, 0xF5, 0x00, 0x03}, {0x8D, 0x05, 0xF6, 0x00, 0x03},
                {0xCD, 0x02, 0xE7, 0x20}, {0x8D, 0x03, 0xF7, 0x30}, {0xCD, 0x06, 0xE6},
        };
        int[] expect = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88};
        for (int i = 0; i < cases.length; i++) {
            exec(cases[i]);
            assertEquals(expect[i], spc.a, "MOV A case " + i);
        }
    }

    @Test void loadWithAutoIncrementAndIndexRegisters() {
        poke(0x06, 0x99);
        exec(0xCD, 0x06, 0xBF);                                    // MOV A,(X)+
        assertEquals(0x99, spc.a); assertEquals(7, spc.x);
        poke(0x10, 0x12); poke(0x15, 0x13); poke(0x300, 0x14); poke(0x305, 0x15);
        exec(0xF8, 0x10); assertEquals(0x12, spc.x);               // MOV X,dp
        exec(0x8D, 0x05, 0xF9, 0x10); assertEquals(0x13, spc.x);   // MOV X,dp+Y
        exec(0xE9, 0x00, 0x03); assertEquals(0x14, spc.x);         // MOV X,abs
        exec(0xCD, 0x05, 0xFB, 0x10); assertEquals(0x13, spc.y);   // MOV Y,dp+X
        exec(0xEC, 0x00, 0x03); assertEquals(0x14, spc.y);         // MOV Y,abs
        exec(0x9D); assertEquals(spc.sp, spc.x);                   // MOV X,SP
    }

    @Test void everyStoreAddressingMode() {
        poke(0x22, 0x00, 0x04); poke(0x30, 0x00, 0x05);
        int[][] cases = {
                {0xCD, 0x04, 0xD4, 0x10},                          // MOV dp+X,A  -> $14
                {0xC5, 0x00, 0x03},                                // MOV abs,A   -> $300
                {0xCD, 0x04, 0xD5, 0x00, 0x03},                    // MOV abs+X,A -> $304
                {0x8D, 0x05, 0xD6, 0x00, 0x03},                    // MOV abs+Y,A -> $305
                {0xCD, 0x02, 0xC7, 0x20},                          // MOV (dp+X),A-> $400
                {0x8D, 0x03, 0xD7, 0x30},                          // MOV (dp)+Y,A-> $503
                {0xCD, 0x06, 0xAF},                                // MOV (X)+,A  -> $06, X=7
        };
        int[] where = {0x14, 0x300, 0x304, 0x305, 0x400, 0x503, 0x06};
        for (int i = 0; i < cases.length; i++) {
            int[] code = new int[cases[i].length + 2];
            code[0] = 0xE8; code[1] = 0x5A;
            System.arraycopy(cases[i], 0, code, 2, cases[i].length);
            exec(code);
            assertEquals(0x5A, m(where[i]), "store case " + i);
        }
    }

    @Test void storeIndexRegistersAndMoveBetweenDirectPageBytes() {
        exec(0xCD, 0x21, 0xD8, 0x10, 0xC9, 0x00, 0x03);            // MOV dp,X ; MOV abs,X
        assertEquals(0x21, m(0x10)); assertEquals(0x21, m(0x300));
        exec(0xCD, 0x22, 0x8D, 0x05, 0xD9, 0x10);                  // MOV dp+Y,X
        assertEquals(0x22, m(0x15));
        exec(0x8D, 0x23, 0xCD, 0x02, 0xDB, 0x10, 0xCC, 0x01, 0x03);   // MOV dp+X,Y ; MOV abs,Y
        assertEquals(0x23, m(0x12)); assertEquals(0x23, m(0x301));
        poke(0x40, 0x7E); exec(0xFA, 0x40, 0x41);                  // MOV dp,dp (source first)
        assertEquals(0x7E, m(0x41));
    }

    // ------------------------------------------------------------------ ALU forms

    @Test void orAndEorCmpAddSubtractWithEveryOperandForm() {
        poke(0x10, 0x0F); poke(0x14, 0x0F); poke(0x300, 0x0F); poke(0x304, 0x0F); poke(0x305, 0x0F);
        poke(0x22, 0x00, 0x04); poke(0x400, 0x0F); poke(0x30, 0x00, 0x05); poke(0x503, 0x0F); poke(0x06, 0x0F);
        // the nine forms for OR (base $00): #imm 08, dp 04, dp+X 14, abs 05, abs+X 15, abs+Y 16, (X) 06, (dp+X) 07, (dp)+Y 17
        int[][] forms = {
                {0x08, 0x0F}, {0x04, 0x10}, {0xCD, 0x04, 0x14, 0x10}, {0x05, 0x00, 0x03}, {0xCD, 0x04, 0x15, 0x00, 0x03},
                {0x8D, 0x05, 0x16, 0x00, 0x03}, {0xCD, 0x06, 0x06}, {0xCD, 0x02, 0x07, 0x20}, {0x8D, 0x03, 0x17, 0x30},
        };
        for (int i = 0; i < forms.length; i++) {
            int[] code = new int[forms[i].length + 2];
            code[0] = 0xE8; code[1] = 0xF0;
            System.arraycopy(forms[i], 0, code, 2, forms[i].length);
            exec(code);
            assertEquals(0xFF, spc.a, "OR form " + i);
        }
        exec(0xE8, 0xFC, 0x28, 0x0F); assertEquals(0x0C, spc.a);   // AND #
        exec(0xE8, 0xFF, 0x48, 0x0F); assertEquals(0xF0, spc.a);   // EOR #
        exec(0xE8, 0x10, 0x68, 0x10); assertEquals(0x10, spc.a);   // CMP # (A unchanged)
        assertNotEquals(0, psw(0xE8, 0x10, 0x68, 0x10) & 2);       // ... and Z is set
        exec(0x60, 0xE8, 0x10, 0x88, 0x05); assertEquals(0x15, spc.a);   // CLRC ; ADC #
        exec(0x80, 0xE8, 0x10, 0xA8, 0x05); assertEquals(0x0B, spc.a);   // SETC ; SBC #
    }

    @Test void aluOnMemoryAndAbsolute() {
        poke(0x10, 0x0F); poke(0x14, 0x0F); poke(0x300, 0x0F); poke(0x304, 0x0F);
        exec(0xE8, 0x30, 0x24, 0x10); assertEquals(0x00, spc.a);   // AND dp
        exec(0xE8, 0xF0, 0x44, 0x10); assertEquals(0xFF, spc.a);   // EOR dp
        exec(0xE8, 0x0F, 0x64, 0x10); assertEquals(0x0F, spc.a);   // CMP dp
        exec(0x60, 0xE8, 0x01, 0x84, 0x10); assertEquals(0x10, spc.a);   // ADC dp
        exec(0x80, 0xE8, 0x20, 0xA4, 0x10); assertEquals(0x11, spc.a);   // SBC dp
        exec(0xE8, 0x1F, 0x25, 0x00, 0x03); assertEquals(0x0F, spc.a);   // AND abs
        exec(0xE8, 0x10, 0x05, 0x00, 0x03); assertEquals(0x1F, spc.a);   // OR abs
        exec(0xE8, 0xFF, 0x45, 0x00, 0x03); assertEquals(0xF0, spc.a);   // EOR abs
        exec(0x60, 0xE8, 0x01, 0x85, 0x00, 0x03); assertEquals(0x10, spc.a);   // ADC abs
        exec(0x80, 0xE8, 0x20, 0xA5, 0x00, 0x03); assertEquals(0x11, spc.a);   // SBC abs
        exec(0xCD, 0x04, 0xE8, 0x1F, 0x34, 0x10); assertEquals(0x0F, spc.a);   // AND dp+X
        exec(0xCD, 0x04, 0xE8, 0x1F, 0x35, 0x00, 0x03); assertEquals(0x0F, spc.a);   // AND abs+X
        exec(0x8D, 0x04, 0xE8, 0x1F, 0x36, 0x00, 0x03); assertEquals(0x0F, spc.a);   // AND abs+Y
        poke(0x06, 0x0F); exec(0xCD, 0x06, 0xE8, 0x1F, 0x26); assertEquals(0x0F, spc.a);   // AND (X)
    }

    @Test void directPageToDirectPageAluForms() {
        poke(0x10, 0x0F); poke(0x11, 0xF0);
        exec(0x09, 0x10, 0x11); assertEquals(0xFF, m(0x11));       // OR dp,dp  (dest |= source)
        poke(0x11, 0xFF); exec(0x29, 0x10, 0x11); assertEquals(0x0F, m(0x11));   // AND dp,dp
        poke(0x11, 0xFF); exec(0x49, 0x10, 0x11); assertEquals(0xF0, m(0x11));   // EOR dp,dp
        poke(0x11, 0x0F); exec(0x69, 0x10, 0x11); assertEquals(0x0F, m(0x11));   // CMP dp,dp: no write
        exec(0x60, 0x89, 0x10, 0x11); assertEquals(0x1E, m(0x11));              // ADC dp,dp
        poke(0x11, 0x20); exec(0x80, 0xA9, 0x10, 0x11); assertEquals(0x11, m(0x11));   // SBC dp,dp
        poke(0x06, 0x0F); poke(0x07, 0xF0);
        exec(0xCD, 0x06, 0x8D, 0x07, 0x19); assertEquals(0xFF, m(0x06));        // OR (X),(Y)
    }

    @Test void subtractWithBorrowSetsFlags() {
        assertEquals(0, psw(0x80, 0xE8, 0x10, 0xA8, 0x10) & 0x80);     // 0x10 - 0x10 : N clear
        assertNotEquals(0, psw(0x80, 0xE8, 0x10, 0xA8, 0x10) & 0x02);  // Z
        assertNotEquals(0, psw(0x80, 0xE8, 0x10, 0xA8, 0x10) & 0x01);  // no borrow -> C
        assertNotEquals(0, psw(0x80, 0xE8, 0x80, 0xA8, 0x01) & 0x40);  // -128 - 1 overflows
        assertEquals(0, psw(0x80, 0xE8, 0x00, 0xA8, 0x01) & 0x01);     // borrow clears C
    }

    @Test void compareIndexRegisters() {
        poke(0x10, 0x20); poke(0x300, 0x20);
        assertNotEquals(0, psw(0xCD, 0x20, 0xC8, 0x20) & 2);       // CMP X,#
        assertNotEquals(0, psw(0xCD, 0x20, 0x3E, 0x10) & 2);       // CMP X,dp
        assertNotEquals(0, psw(0xCD, 0x20, 0x1E, 0x00, 0x03) & 2); // CMP X,abs
        assertNotEquals(0, psw(0x8D, 0x20, 0xAD, 0x20) & 2);       // CMP Y,#
        assertNotEquals(0, psw(0x8D, 0x20, 0x7E, 0x10) & 2);       // CMP Y,dp
        assertNotEquals(0, psw(0x8D, 0x20, 0x5E, 0x00, 0x03) & 2); // CMP Y,abs
        assertEquals(0, psw(0xCD, 0x21, 0xC8, 0x20) & 2);
    }

    // ------------------------------------------------------------------ read-modify-write

    @Test void incrementDecrementAndShiftMemory() {
        poke(0x10, 0x7F); exec(0xAB, 0x10); assertEquals(0x80, m(0x10));          // INC dp
        poke(0x14, 0x01); exec(0xCD, 0x04, 0xBB, 0x10); assertEquals(0x02, m(0x14));   // INC dp+X
        poke(0x300, 0xFF); exec(0xAC, 0x00, 0x03); assertEquals(0x00, m(0x300));  // INC abs
        poke(0x10, 0x01); exec(0x8B, 0x10); assertEquals(0x00, m(0x10));          // DEC dp
        poke(0x14, 0x01); exec(0xCD, 0x04, 0x9B, 0x10); assertEquals(0x00, m(0x14));   // DEC dp+X
        poke(0x300, 0x00); exec(0x8C, 0x00, 0x03); assertEquals(0xFF, m(0x300));  // DEC abs
        int[][] shifts = {   // dp, dp+X, abs opcodes for ASL ROL LSR ROR and the expected result of $81 with carry set
                {0x0B, 0x1B, 0x0C, 0x02}, {0x2B, 0x3B, 0x2C, 0x03}, {0x4B, 0x5B, 0x4C, 0x40}, {0x6B, 0x7B, 0x6C, 0xC0}};
        for (int[] s : shifts) {
            poke(0x10, 0x81); exec(0x80, s[0], 0x10); assertEquals(s[3], m(0x10), "dp op " + s[0]);
            poke(0x14, 0x81); exec(0x80, 0xCD, 0x04, s[1], 0x10); assertEquals(s[3], m(0x14), "dp+X op " + s[1]);
            poke(0x300, 0x81); exec(0x80, s[2], 0x00, 0x03); assertEquals(s[3], m(0x300), "abs op " + s[2]);
        }
    }

    @Test void wordDecrementAndCompare() {
        poke(0x10, 0x00, 0x01);
        exec(0x1A, 0x10);                                           // DECW $0100 -> $00FF
        assertEquals(0xFF, m(0x10)); assertEquals(0x00, m(0x11));
        poke(0x10, 0x34, 0x12);
        assertNotEquals(0, psw(0xE8, 0x34, 0x8D, 0x12, 0x5A, 0x10) & 2);    // CMPW YA,dp : equal
        assertNotEquals(0, psw(0xE8, 0x35, 0x8D, 0x12, 0x5A, 0x10) & 1);    // greater: carry
    }

    @Test void testAndSetClearBitsAgainstA() {
        poke(0x300, 0xF0); exec(0xE8, 0x0F, 0x0E, 0x00, 0x03);       // TSET1 abs
        assertEquals(0xFF, m(0x300));
        poke(0x300, 0xFF); exec(0xE8, 0x0F, 0x4E, 0x00, 0x03);       // TCLR1 abs
        assertEquals(0xF0, m(0x300));
    }

    @Test void divisionOverflowAndDecimalAdjust() {
        exec(0xE8, 0x00, 0x8D, 0x21, 0xCD, 0x10, 0x9E);              // Y=$21 >= X=$10 *2: the overflow branch
        assertEquals(0xFE, spc.a);                                   // 255 - (ya - x*512) / (256 - x)
        assertEquals(0x20, spc.y);                                   // x + (ya - x*512) % (256 - x)
        exec(0x60, 0xE8, 0x09, 0x88, 0x01, 0xDF);                    // ADC #1 -> $0A ; DAA -> $10
        assertEquals(0x10, spc.a);
        exec(0x80, 0xE8, 0x10, 0xA8, 0x01, 0xBE);                    // SBC #1 -> $0F ; DAS -> $09
        assertEquals(0x09, spc.a);
        exec(0x60, 0xE8, 0x99, 0x88, 0x01, 0xDF);                    // $9A + DAA -> $00 with carry
        assertEquals(0x00, spc.a);
    }

    // ------------------------------------------------------------------ flags, stack, branches

    @Test void flagInstructions() {
        assertEquals(0x20, psw(0x40) & 0x20);                        // SETP
        assertEquals(0x00, psw(0x40, 0x20) & 0x20);                  // CLRP
        assertEquals(0x04, psw(0xA0) & 0x04);                        // SETI
        assertEquals(0x00, psw(0xA0, 0xC0) & 0x04);                  // CLRI
        assertEquals(0x00, psw(0xE8, 0x7F, 0x88, 0x01, 0xE0) & 0x48);   // CLRV also clears H
        assertEquals(0x01, psw(0x60, 0xED) & 0x01);                  // NOTC
        assertEquals(0x00, psw(0x80, 0xED) & 0x01);
    }

    @Test void pushPopIndexRegistersAndStatus() {
        exec(0xCD, 0x33, 0x4D, 0xCD, 0x00, 0xCE); assertEquals(0x33, spc.x);       // PUSH X ; POP X
        exec(0x8D, 0x44, 0x6D, 0x8D, 0x00, 0xEE); assertEquals(0x44, spc.y);       // PUSH Y ; POP Y
        assertEquals(0x85, psw(0xE8, 0x85, 0x2D, 0x8E) & 0x85);                    // PUSH A ; POP PSW takes the byte as flags (N, I, C)
        assertEquals(1, psw(0x80, 0x0D, 0x60, 0x8E) & 1);                          // SETC ; PUSH PSW ; CLRC ; POP PSW restores C
    }

    @Test void conditionalBranchesOnNegativeAndOverflow() {
        int[][] taken = {{0xE8, 0x80, 0x30}, {0xE8, 0x7F, 0x88, 0x01, 0x70}, {0xE8, 0x01, 0x50}};   // BMI, BVS, BVC
        for (int[] t : taken) {
            int[] code = new int[t.length + 4];
            System.arraycopy(t, 0, code, 0, t.length);
            code[t.length] = 0x02; code[t.length + 1] = 0x8D; code[t.length + 2] = 0xFF; code[t.length + 3] = 0x00;
            exec(code);
            assertNotEquals(0xFF, spc.y, "branch " + Integer.toHexString(t[t.length - 1]));
        }
    }

    @Test void bitBranchesCompareAndDecrementBranches() {
        poke(0x10, 0x04);
        exec(0x43, 0x10, 0x02, 0x8D, 0xFF, 0x00);                    // BBS2: bit 2 set -> taken (bit n uses opcode n*$20+3)
        assertNotEquals(0xFF, spc.y);
        exec(0x13, 0x10, 0x02, 0x8D, 0xFF, 0x00);                    // BBC0: bit 0 clear -> taken
        assertNotEquals(0xFF, spc.y);
        exec(0x03, 0x10, 0x02, 0x8D, 0xFF, 0x00);                    // BBS0: bit 0 clear -> not taken
        assertEquals(0xFF, spc.y);
        poke(0x10, 0x05); exec(0xE8, 0x07, 0x2E, 0x10, 0x02, 0x8D, 0xFF, 0x00);   // CBNE dp : A != [dp] -> taken
        assertNotEquals(0xFF, spc.y);
        poke(0x14, 0x05); exec(0xE8, 0x07, 0xCD, 0x04, 0xDE, 0x10, 0x02, 0x8D, 0xFF, 0x00);   // CBNE dp+X
        assertNotEquals(0xFF, spc.y);
        poke(0x10, 0x02); exec(0x6E, 0x10, 0x02, 0x8D, 0xFF, 0x00);  // DBNZ dp: 2 -> 1, taken
        assertNotEquals(0xFF, spc.y); assertEquals(1, m(0x10));
        exec(0x8D, 0x02, 0xFE, 0x02, 0x8D, 0xFF, 0x00);              // DBNZ Y: 2 -> 1, taken
        assertEquals(1, spc.y);
    }

    @Test void singleBitOperationsOnCarry() {
        poke(0x10, 0x01);                                            // bit 0 of $0010 set; operand = address | bit << 13
        assertEquals(1, psw(0x60, 0x0A, 0x10, 0x00) & 1);            // OR1 C,mem.0
        assertEquals(0, psw(0x60, 0x2A, 0x10, 0x00) & 1);            // OR1 C,/mem.0 (bit set: unchanged)
        assertEquals(1, psw(0x80, 0x4A, 0x10, 0x00) & 1);            // AND1 C,mem.0
        assertEquals(0, psw(0x80, 0x6A, 0x10, 0x00) & 1);            // AND1 C,/mem.0: the inverted bit is 0
        assertEquals(0, psw(0x80, 0x8A, 0x10, 0x00) & 1);            // EOR1 C,mem.0 : 1 ^ 1
        assertEquals(1, psw(0xAA, 0x10, 0x00) & 1);                  // MOV1 C,mem.0
        exec(0x80, 0xCA, 0x12, 0x40);                                // MOV1 mem.2,C -> address $0012 bit 2 (operand bit field = 2<<13 = $4000)
        assertEquals(0x04, m(0x12));
        exec(0x60, 0xCA, 0x10, 0x00);                                // MOV1 mem.0,C with C clear -> clears the bit
        assertEquals(0x00, m(0x10));
    }

    @Test void callsAndInterrupts() {
        ram.m[0xFFDE] = 0x00; ram.m[0xFFDF] = 0x03;                  // TCALL 0 uses the vector at $FFDE
        ram.m[0xFFC0] = 0x20; ram.m[0xFFC1] = 0x03;                  // TCALL 15 uses the one at $FFC0
        boot(0x01);
        spc.step();
        assertEquals(0x0300, spc.pc);
        boot(0xF1);
        spc.step();
        assertEquals(0x0320, spc.pc);
        boot(0x4F, 0x40);                                            // PCALL $FF40
        spc.step();
        assertEquals(0xFF40, spc.pc);
        boot(0x0F);                                                  // BRK
        int sp = spc.sp;
        spc.step();
        assertEquals(0x0300, spc.pc);
        assertEquals(sp - 3, spc.sp);
        ram.m[0x300] = (byte) 0x7F;                                  // RETI
        spc.step();
        assertEquals(0x201, spc.pc);
    }

    @Test void sleepAndStopHaltTheCore() {
        boot(0xEF, 0xE8, 0x05);
        spc.step();
        assertTrue(spc.stopped);
        int pc = spc.pc;
        spc.step();
        assertEquals(pc, spc.pc);
        boot(0xFF);
        spc.step();
        assertTrue(spc.stopped);
    }

    @Test void everyOpcodeIsImplemented() {
        for (int op = 0; op < 256; op++) {
            boot(op, 0x10, 0x00);
            final int o = op;
            assertDoesNotThrow(() -> spc.step(), "opcode " + o);
        }
    }
}
