package dev.superjavendo.cart;

import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** GSU instruction-level tests beyond the basics of {@link SuperFxTest}. Programs are loaded at ROM $00:8000 (file offset 0). */
class SuperFxOpsTest {
    private static SuperFx start(Consumer<byte[]> data, int... code) {
        byte[] rom = new byte[0x8000];
        for (int i = 0; i < code.length; i++) rom[i] = (byte) code[i];
        data.accept(rom);
        return new SuperFx(rom, 0x10000);
    }

    private static void go(SuperFx fx) {
        fx.write(0, 0x301E, 0x00);
        fx.write(0, 0x301F, 0x80);                 // R15 = $8000 starts the chip
        for (int i = 0; i < 50000 && (fx.read(0, 0x3030) & 0x20) != 0; i++) fx.tick(16);
        assertEquals(0, fx.read(0, 0x3030) & 0x20, "program did not stop");
    }

    private static SuperFx run(int... code) { SuperFx fx = start(d -> { }, code); go(fx); return fx; }

    private static int reg(SuperFx fx, int n) { return fx.read(0, 0x3000 + n * 2) | (fx.read(0, 0x3001 + n * 2) << 8); }
    private static int sfr(SuperFx fx) { return fx.read(0, 0x3030) | fx.read(0, 0x3031) << 8; }
    private static final int Z = 2, CY = 4, S = 8, OV = 0x10;
    private static final int STOP = 0x00, NOP = 0x01;

    // ---- arithmetic and logic (default source and destination are R0)

    @Test void addSubtractAndTheCarry() {
        SuperFx fx = run(0xA0, 10, 0xA1, 5, 0x51, STOP, NOP);               // R0 = 10 + R1
        assertEquals(15, reg(fx, 0));
        fx = run(0xA0, 10, 0xA1, 5, 0x61, STOP, NOP);                        // R0 = 10 - R1
        assertEquals(5, reg(fx, 0));
        assertNotEquals(0, sfr(fx) & CY, "no borrow sets carry");
    }

    @Test void immediateFormsUseAlt2() {
        assertEquals(13, reg(run(0xA0, 10, 0x3E, 0x53, STOP, NOP), 0));      // ADD #3
        assertEquals(7, reg(run(0xA0, 10, 0x3E, 0x63, STOP, NOP), 0));       // SUB #3
        assertEquals(2, reg(run(0xA0, 0x0A, 0x3E, 0x72, STOP, NOP), 0));     // AND #2
        assertEquals(0x0B, reg(run(0xA0, 0x0A, 0x3E, 0xC1, STOP, NOP), 0));  // OR #1
    }

    @Test void addWithCarryAndSubtractWithBorrow() {
        // IWT R0,#$FFFF ; ADD #1 (carry out, R0=0) ; IBT R1,#0 ; ALT1 ADC R1 -> R0 = 0 + 0 + carry
        SuperFx fx = run(0xF0, 0xFF, 0xFF, 0x3E, 0x51, 0xA1, 0, 0x3D, 0x51, STOP, NOP);
        assertEquals(1, reg(fx, 0));
        fx = run(0xA0, 5, 0xA1, 2, 0x3D, 0x61, STOP, NOP);                   // SBC: 5 - 2 - !carry(0 at power-on) = 2 or 3
        assertEquals(2, reg(fx, 0));                                        // carry is clear at power-on, so SBC also subtracts 1: 5 - 2 - 1 = 2
    }

    @Test void logicOperations() {
        assertEquals(0x0F00, reg(run(0xF0, 0xFF, 0x0F, 0xF1, 0x00, 0xFF, 0x71, STOP, NOP), 0));    // AND R1
        assertEquals(0xFFFF, reg(run(0xF0, 0x0F, 0x0F, 0xF1, 0xF0, 0xF0, 0xC1, STOP, NOP), 0));              // OR R1
        assertEquals(0x0FF0, reg(run(0xF0, 0xFF, 0x0F, 0xF1, 0x0F, 0x00, 0x3D, 0xC1, STOP, NOP), 0));         // XOR R1
        assertEquals(0x00F0, reg(run(0xF0, 0xFF, 0x00, 0xF1, 0x0F, 0x00, 0x3D, 0x71, STOP, NOP), 0));  // BIC R1
        assertEquals(0xFF0F, reg(run(0xF0, 0xF0, 0x00, 0x4F, STOP, NOP), 0));                          // NOT
    }

    @Test void shiftsAndRotates() {
        SuperFx fx = run(0xA0, 0x05, 0x03, STOP, NOP);                       // LSR: 5 -> 2, carry = old bit 0
        assertEquals(2, reg(fx, 0));
        assertNotEquals(0, sfr(fx) & CY);
        assertEquals(0xFFFC, reg(run(0xA0, 0xF8, 0x96, STOP, NOP), 0));      // ASR of -8 (sign-extended IBT) -> -4
        assertEquals(0x0004, reg(run(0xA0, 0x02, 0x04, STOP, NOP), 0));      // ROL
        assertEquals(0x0001, reg(run(0xA0, 0x02, 0x97, STOP, NOP), 0));      // ROR
    }

    @Test void byteAndWordManipulation() {
        assertEquals(0x3412, reg(run(0xF0, 0x34, 0x12, 0x4D, STOP, NOP), 0));    // SWAP
        assertEquals(0xFF80, reg(run(0xF0, 0x80, 0x00, 0x95, STOP, NOP), 0));    // SEX: sign-extend the low byte
        assertEquals(0x0034, reg(run(0xF0, 0x34, 0x12, 0x9E, STOP, NOP), 0));    // LOB
        assertEquals(0x0012, reg(run(0xF0, 0x34, 0x12, 0xC0, STOP, NOP), 0));    // HIB
        // MERGE: R0 = (R7 & $FF00) | (R8 >> 8)
        assertEquals(0xAB12, reg(run(0xF7, 0x00, 0xAB, 0xF8, 0x00, 0x12, 0x70, STOP, NOP), 0));
    }

    @Test void incrementAndDecrementSetFlags() {
        SuperFx fx = run(0xA1, 0xFF, 0xD1, STOP, NOP);                       // R1 = -1 ; INC R1 -> 0
        assertEquals(0, reg(fx, 1));
        assertNotEquals(0, sfr(fx) & Z);
        fx = run(0xA2, 0x01, 0xE2, STOP, NOP);                               // DEC R2 -> 0
        assertEquals(0, reg(fx, 2));
        assertNotEquals(0, sfr(fx) & Z);
        assertEquals(0xFFFF, reg(run(0xA3, 0x00, 0xE3, STOP, NOP), 3));
    }

    @Test void multiplications() {
        assertEquals(0xFFF4, reg(run(0xA0, 0xFD, 0xA1, 4, 0x81, STOP, NOP), 0));       // MULT: -3 * 4
        assertEquals(0x03F4, reg(run(0xA0, 0xFD, 0xA1, 4, 0x3D, 0x81, STOP, NOP), 0)); // UMULT: 253 * 4
        assertEquals(0xFFFA, reg(run(0xA0, 0xFD, 0x3E, 0x82, STOP, NOP), 0));          // MULT #2: -3 * 2
    }

    @Test void fractionalMultiplyUsesTheHighWord() {
        // R6 = $4000, R0 = $4000: FMULT -> high 16 bits of the 32-bit product = $1000
        SuperFx fx = run(0xF6, 0x00, 0x40, 0xF0, 0x00, 0x40, 0x9F, STOP, NOP);
        assertEquals(0x1000, reg(fx, 0));
    }

    @Test void longMultiplyGivesAThirtyTwoBitResult() {
        // LMULT: R6 * R0 as a signed 32-bit value: high word to the destination (R0), low word to R4
        SuperFx fx = run(0xF6, 0x00, 0x40, 0xF0, 0x03, 0x40, 0x3D, 0x9F, STOP, NOP);
        assertEquals(0x1000, reg(fx, 0));
        assertEquals(0xC000, reg(fx, 4));
    }

    // ---- registers: prefixes and moves

    @Test void toFromWithMoveRegisters() {
        SuperFx fx = run(0xA1, 5, 0xB1, 0x12, 0x50, STOP, NOP);              // FROM R1 ; TO R2 ; ADD R0 -> R2 = R1 + R0
        assertEquals(5, reg(fx, 2));
        fx = run(0xA1, 7, 0x21, 0x12, STOP, NOP);                            // WITH R1 ; TO R2 form: MOVE R2,R1
        assertEquals(7, reg(fx, 2));
        fx = run(0xA4, 5, 0x23, 0xB4, STOP, NOP);                            // WITH R3 then FROM R4: MOVES R3,R4
        assertEquals(5, reg(fx, 3));
    }

    @Test void iwtLoadsSixteenBitImmediates() {
        SuperFx fx = run(0xF5, 0x34, 0x12, 0xFB, 0xCD, 0xAB, STOP, NOP);
        assertEquals(0x1234, reg(fx, 5));
        assertEquals(0xABCD, reg(fx, 11));
    }

    @Test void linkSetsR11AheadOfTheProgramCounter() {
        SuperFx fx = run(0x92, STOP, NOP);                                   // LINK #2
        assertEquals(0x8001 + 2, reg(fx, 11));
    }

    // ---- control flow

    private static int branchRegister(int preamble[], int branchOpcode) {
        int[] code = new int[preamble.length + 9];
        System.arraycopy(preamble, 0, code, 0, preamble.length);
        int i = preamble.length;
        code[i++] = branchOpcode; code[i++] = 0x03; code[i++] = NOP;        // taken: lands on the second IBT
        code[i++] = 0xA1; code[i++] = 9;                                    // R1 = 9 only when the branch was not taken
        code[i++] = 0xA2; code[i++] = 4;
        code[i++] = STOP; code[i] = NOP;
        return reg(run(code), 1);
    }

    private static final int[] ZERO = {0xA0, 0x01, 0x3E, 0x61};          // 1 - 1 : Z, carry, positive, no overflow
    private static final int[] POSITIVE = {0xA0, 0x02, 0x3E, 0x61};      // 2 - 1 : not Z, carry, positive
    private static final int[] BORROW = {0xA0, 0x00, 0x3E, 0x61};        // 0 - 1 : borrow, negative
    private static final int[] OVERFLOW = {0xF0, 0xFF, 0x7F, 0x3E, 0x51};  // $7FFF + 1 : overflow, negative

    @Test void conditionalBranches() {
        assertEquals(0, branchRegister(ZERO, 0x09), "BEQ taken");
        assertEquals(9, branchRegister(POSITIVE, 0x09), "BEQ not taken");
        assertEquals(0, branchRegister(POSITIVE, 0x08), "BNE taken");
        assertEquals(9, branchRegister(ZERO, 0x08), "BNE not taken");
        assertEquals(0, branchRegister(ZERO, 0x0D), "BCS taken");
        assertEquals(0, branchRegister(BORROW, 0x0C), "BCC taken");
        assertEquals(0, branchRegister(BORROW, 0x0B), "BMI taken");
        assertEquals(0, branchRegister(POSITIVE, 0x0A), "BPL taken");
        assertEquals(9, branchRegister(BORROW, 0x0A), "BPL not taken");
        assertEquals(0, branchRegister(POSITIVE, 0x0E), "BVC taken");
        assertEquals(0, branchRegister(OVERFLOW, 0x0F), "BVS taken");
        assertEquals(0, branchRegister(POSITIVE, 0x06), "BGE taken");
        assertEquals(0, branchRegister(BORROW, 0x07), "BLT taken");
    }

    @Test void jmpLoadsTheProgramCounterFromARegister() {
        SuperFx fx = start(d -> { d[0x10] = (byte) 0xA2; d[0x11] = 7; d[0x12] = STOP; d[0x13] = NOP; },
                0xF9, 0x10, 0x80, 0x99, NOP, 0xA2, 3, STOP, NOP);        // IWT R9,#$8010 ; JMP R9 (JMP takes R8-R13) ; delay slot
        go(fx);
        assertEquals(7, reg(fx, 2));
    }

    @Test void stopRaisesTheIrqFlagUnlessMasked() {
        SuperFx fx = run(STOP, NOP);
        assertTrue(fx.irq());
        assertNotEquals(0, fx.read(0, 0x3031) & 0x80);                        // reading SFR high acknowledges it
        assertFalse(fx.irq());
        fx = start(d -> { }, STOP, NOP);
        fx.write(0, 0x3037, 0x80);                                            // CFGR: mask the interrupt
        go(fx);
        assertFalse(fx.irq());
    }

    // ---- memory

    @Test void wordAndByteLoadsAndStores() {
        SuperFx fx = run(0xF2, 0x00, 0x01, 0xF0, 0x34, 0x12, 0x32, 0xA0, 0x00, 0x42, STOP, NOP);   // STW (R2) ; LDW (R2)
        assertEquals(0x34, fx.ram()[0x100] & 0xFF);
        assertEquals(0x12, fx.ram()[0x101] & 0xFF);
        assertEquals(0x1234, reg(fx, 0));
        fx = run(0xF2, 0x10, 0x00, 0xA0, 0x7E, 0x3D, 0x32, 0xA0, 0x00, 0x3D, 0x42, STOP, NOP);     // STB (R2) ; LDB (R2)
        assertEquals(0x7E, fx.ram()[0x10] & 0xFF);
        assertEquals(0x7E, reg(fx, 0));
    }

    @Test void absoluteLoadAndStoreForms() {
        // ALT2 SM (R1) to $0040 ; clear R1 ; ALT1 LM R1 from $0040
        SuperFx fx = run(0xF1, 0xEF, 0xBE, 0x3E, 0xF1, 0x40, 0x00, 0xF1, 0x00, 0x00, 0x3D, 0xF1, 0x40, 0x00, STOP, NOP);
        assertEquals(0xBEEF, reg(fx, 1));
        assertEquals(0xEF, fx.ram()[0x40] & 0xFF);
        // short forms: SMS (yy) / LMS (yy) address words at yy*2
        fx = run(0xF2, 0x34, 0x12, 0x3E, 0xA2, 0x10, 0xF2, 0x00, 0x00, 0x3D, 0xA2, 0x10, STOP, NOP);
        assertEquals(0x1234, reg(fx, 2));
        assertEquals(0x34, fx.ram()[0x20] & 0xFF);
    }

    @Test void romBufferReadsBytesThroughR14() {
        SuperFx fx = start(d -> d[0x20] = (byte) 0xAB, 0xFE, 0x20, 0x80, 0xEF, STOP, NOP);   // IWT R14,#$8020 ; GETB
        go(fx);
        assertEquals(0xAB, reg(fx, 0) & 0xFF);
    }

    @Test void getcLoadsTheColourRegisterFromTheRomBuffer() {
        SuperFx fx = start(d -> d[0x20] = 0x05, 0xFE, 0x20, 0x80, 0xDF, 0xA1, 0, 0xA2, 0, 0x4C, STOP, NOP);   // GETC ; PLOT
        go(fx);
        assertNotEquals(0, fx.ram()[0] & 0x80 | fx.ram()[16] & 0x80, "colour 5 has planes 0 and 2 set");
    }

    // ---- plot

    @Test void fourBitPlotSetsOneBitInEachOfTheFourPlanes() {
        SuperFx fx = start(d -> { }, 0xA0, 0x0F, 0x4E, 0xA1, 0, 0xA2, 0, 0x4C, STOP, NOP);
        fx.write(0, 0x303A, 0x01);                                           // SCMR: 4 bits per pixel
        go(fx);
        byte[] ram = fx.ram();
        for (int plane : new int[]{0, 1, 16, 17}) assertEquals(0x80, ram[plane] & 0x80, "plane byte " + plane);
    }

    @Test void eightBitPlotUsesEightPlanes() {
        SuperFx fx = start(d -> { }, 0xA0, (byte) 0xFF, 0x4E, 0xA1, 0, 0xA2, 0, 0x4C, STOP, NOP);
        fx.write(0, 0x303A, 0x03);
        go(fx);
        byte[] ram = fx.ram();
        for (int plane : new int[]{0, 1, 16, 17, 32, 33, 48, 49}) assertEquals(0x80, ram[plane] & 0x80, "plane byte " + plane);
    }

    @Test void plotMovesAcrossPixelsAndRpixReadsThemBack() {
        // COLOR #3, PLOT at (0,0) and (1,0), then RPIX at (0,0)
        SuperFx fx = run(0xA0, 3, 0x4E, 0xA1, 0, 0xA2, 0, 0x4C, 0x4C, 0xA1, 0, 0xA2, 0, 0x3D, 0x4C, STOP, NOP);
        assertEquals(3, reg(fx, 0) & 3);                                      // RPIX read colour 3 back
        assertEquals(0xC0, fx.ram()[0] & 0xC0);                               // plane 0: pixels 0 and 1
        assertEquals(0xC0, fx.ram()[1] & 0xC0);                               // plane 1
    }

    @Test void colorSelectedFromR0AndPlotModeRegister() {
        SuperFx fx = run(0xA0, 2, 0x4E, 0xA0, 1, 0x3D, 0x4E, 0xA1, 0, 0xA2, 0, 0x4C, STOP, NOP);   // COLOR=2 ; CMODE=1
        assertEquals(0, fx.ram()[0] & 0x80);                                  // colour 2 = plane 1 only
        assertEquals(0x80, fx.ram()[1] & 0x80);
    }

    // ---- registers and cache seen from the S-CPU

    @Test void registersAreFrozenWhileRunningAndVersionIsReported() {
        SuperFx fx = run(0xA1, 9, STOP, NOP);
        fx.write(0, 0x3002, 0x55);                                           // R1 low byte: allowed when stopped
        assertEquals(0x55, reg(fx, 1) & 0xFF);
        assertEquals(0x04, fx.read(0, 0x303B));
        assertEquals(0x00, fx.read(0, 0x3034));                              // PBR
    }

    @Test void cacheRamIsReadableAndWritableFromTheCpu() {
        SuperFx fx = new SuperFx(new byte[0x8000], 0x8000);
        fx.write(0, 0x3100, 0x42);
        assertEquals(0x42, fx.read(0, 0x3100));
        fx.write(0, 0x3034, 0x01);
        assertEquals(0x01, fx.read(0, 0x3034));
    }

    @Test void cpuSeesRomAndRamWindows() {
        byte[] rom = new byte[0x20000]; rom[0x12345] = 0x77;
        SuperFx fx = new SuperFx(rom, 0x8000);
        assertEquals(0x77, fx.read(0x41, 0x2345));
        fx.write(0x70, 0x0010, 0x66);
        assertEquals(0x66, fx.read(0x70, 0x0010));
        assertEquals(0x66, fx.read(0x00, 0x6010));                           // the same RAM through $6000-$7FFF
        assertEquals(-1, fx.read(0x00, 0x8000));
        assertTrue(fx.write(0x40, 0x0000, 1));                               // ROM writes are swallowed
    }

    @Test void stateRoundTrip() throws Exception {
        SuperFx fx = run(0xA1, 9, 0xF2, 0x34, 0x12, STOP, NOP);
        var bo = new java.io.ByteArrayOutputStream();
        fx.save(new dev.superjavendo.state.StateOut(bo));
        SuperFx copy = new SuperFx(new byte[0x8000], 0x10000);
        copy.load(new dev.superjavendo.state.StateIn(new java.io.ByteArrayInputStream(bo.toByteArray())));
        assertEquals(9, reg(copy, 1));
        assertEquals(0x1234, reg(copy, 2));
    }
}
