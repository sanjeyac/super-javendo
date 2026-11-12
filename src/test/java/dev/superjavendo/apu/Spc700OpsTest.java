package dev.superjavendo.apu;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Instruction-level tests for the SPC700 beyond the basics in {@link ApuTest}. Code is loaded at $0200. */
class Spc700OpsTest {
    private final ApuTest.Ram ram = new ApuTest.Ram();
    private Spc700 spc;

    private void boot(int... code) {
        for (int i = 0; i < code.length; i++) ram.m[0x200 + i] = (byte) code[i];
        spc = new Spc700(ram);
        spc.reset(0x200);
    }

    private void go(int steps) { for (int i = 0; i < steps; i++) spc.step(); }

    /** Runs the code then PUSH PSW / POP A, so A holds the flags (N V P B H I Z C). */
    private int flagsAfter(int... code) {
        int[] full = new int[code.length + 2];
        System.arraycopy(code, 0, full, 0, code.length);
        full[code.length] = 0x0D; full[code.length + 1] = 0xAE;
        boot(full);
        go(code.length == 0 ? 2 : countInstructions(code) + 2);
        return spc.a;
    }

    private static int countInstructions(int[] code) { return code.length; }   // callers pass one byte per instruction

    @Test void movImmediateAndRegisterTransfers() {
        boot(0xE8, 0x12, 0xCD, 0x34, 0x8D, 0x56, 0x7D, 0xFD);    // A=12 X=34 Y=56; A=X; Y=A
        go(5);
        assertEquals(0x34, spc.a);
        assertEquals(0x34, spc.x);
        assertEquals(0x34, spc.y);
    }

    @Test void incAndDecWrapAndSetFlags() {
        boot(0xE8, 0xFF, 0xBC);                                   // A=FF; INC A -> 0 (Z)
        go(2);
        assertEquals(0, spc.a);
        boot(0xE8, 0x00, 0x9C);                                   // DEC A -> FF
        go(2);
        assertEquals(0xFF, spc.a);
        boot(0xCD, 0x7F, 0x3D);                                   // INC X
        go(2);
        assertEquals(0x80, spc.x);
        boot(0x8D, 0x01, 0xDC);                                   // DEC Y
        go(2);
        assertEquals(0, spc.y);
    }

    @Test void zeroAndNegativeFlags() {
        assertEquals(0x02, flagsAfter(0xE8, 0x00) & 0x82);        // Z set, N clear
        assertEquals(0x80, flagsAfter(0xE8, 0x80) & 0x82);        // N set, Z clear
    }

    @Test void shiftsAndRotatesUseTheCarry() {
        boot(0xE8, 0x81, 0x1C);                                   // ASL A: 81 -> 02, C=1
        go(2);
        assertEquals(0x02, spc.a);
        boot(0xE8, 0x81, 0x1C, 0x0D, 0xAE);
        go(4);
        assertEquals(1, spc.a & 1);

        boot(0xE8, 0x03, 0x5C);                                   // LSR A: 03 -> 01
        go(2);
        assertEquals(0x01, spc.a);

        boot(0x80, 0xE8, 0x00, 0x3C);                             // SETC; ROL A pulls the carry in
        go(3);
        assertEquals(0x01, spc.a);

        boot(0x80, 0xE8, 0x00, 0x7C);                             // SETC; ROR A puts it in bit 7
        go(3);
        assertEquals(0x80, spc.a);
    }

    @Test void pushPopRoundTripsAndRestoresStackPointer() {
        boot(0xE8, 0x5A, 0x2D, 0xE8, 0x00, 0xAE);                 // PUSH A; A=0; POP A
        int sp0 = spc.sp;
        go(2);
        assertEquals(sp0 - 1, spc.sp);
        go(2);
        assertEquals(0x5A, spc.a);
        assertEquals(sp0, spc.sp);
    }

    @Test void xcnSwapsNibbles() {
        boot(0xE8, 0xAB, 0x9F);
        go(2);
        assertEquals(0xBA, spc.a);
    }

    @Test void sixteenBitOpsOnYa() {
        ram.m[0x10] = (byte) 0xFF; ram.m[0x11] = 0x00;
        boot(0xE8, 0x01, 0x8D, 0x00, 0x7A, 0x10);                 // YA=0001; ADDW YA,$10 (00FF) -> 0100
        go(3);
        assertEquals(0x00, spc.a);
        assertEquals(0x01, spc.y);

        ram.m[0x10] = 0x01; ram.m[0x11] = 0x00;
        boot(0xE8, 0x00, 0x8D, 0x01, 0x9A, 0x10);                 // YA=0100; SUBW YA,$10 (0001) -> 00FF
        go(3);
        assertEquals(0xFF, spc.a);
        assertEquals(0x00, spc.y);

        ram.m[0x10] = (byte) 0xFF; ram.m[0x11] = 0x00;
        boot(0x3A, 0x10);                                         // INCW $10: 00FF -> 0100
        go(1);
        assertEquals(0x00, ram.m[0x10]);
        assertEquals(0x01, ram.m[0x11]);

        ram.m[0x20] = 0x34; ram.m[0x21] = 0x12;
        boot(0xBA, 0x20);                                         // MOVW YA,$20
        go(1);
        assertEquals(0x34, spc.a);
        assertEquals(0x12, spc.y);
    }

    @Test void bitSetAndClearOnDirectPage() {
        boot(0x62, 0x10, 0x02, 0x10, 0x12, 0x10);                 // SET1 $10.3; SET1 $10.0; CLR1 $10.0
        go(1);
        assertEquals(0x08, ram.m[0x10]);
        go(1);
        assertEquals(0x09, ram.m[0x10]);
        go(1);
        assertEquals(0x08, ram.m[0x10]);
    }

    @Test void branchesTakenAndNotTaken() {
        boot(0x2F, 0x02, 0xE8, 0x11, 0xE8, 0x22);                 // BRA +2 skips the first MOV
        go(2);
        assertEquals(0x22, spc.a);

        boot(0xE8, 0x00, 0xF0, 0x02, 0xE8, 0x11, 0xE8, 0x22);     // BEQ taken (Z)
        go(3);
        assertEquals(0x22, spc.a);

        boot(0xE8, 0x00, 0xD0, 0x02, 0xE8, 0x11, 0xE8, 0x22);     // BNE not taken
        go(3);
        assertEquals(0x11, spc.a);

        boot(0x80, 0xB0, 0x02, 0xE8, 0x11, 0xE8, 0x22);           // SETC; BCS taken
        go(3);
        assertEquals(0x22, spc.a);

        boot(0x60, 0x90, 0x02, 0xE8, 0x11, 0xE8, 0x22);           // CLRC; BCC taken
        go(3);
        assertEquals(0x22, spc.a);
    }

    @Test void takenBranchCostsTwoMoreCycles() {
        boot(0x2F, 0x00);
        assertEquals(4, spc.step());                              // BRA: 2 + 2
        boot(0xE8, 0x01, 0xF0, 0x00);
        spc.step();
        assertEquals(2, spc.step());                              // BEQ not taken
    }

    @Test void callAndReturn() {
        boot(0x3F, 0x06, 0x02, 0xE8, 0xAA, 0x00, 0xE8, 0x77, 0x6F);   // CALL $0206 ... RET back to $0203
        int sp0 = spc.sp;
        go(1);
        assertEquals(0x206, spc.pc);
        assertEquals(sp0 - 2, spc.sp);
        go(2);                                                    // MOV A,#77; RET
        assertEquals(0x203, spc.pc);
        assertEquals(sp0, spc.sp);
        go(1);
        assertEquals(0xAA, spc.a);
    }

    @Test void jumpAbsolute() {
        boot(0x5F, 0x10, 0x02);
        go(1);
        assertEquals(0x210, spc.pc);
    }

    @Test void carryFlagInstructionsShowInPsw() {
        assertEquals(1, flagsAfter(0x80) & 1);                    // SETC
        assertEquals(0, flagsAfter(0x80, 0x60) & 1);              // SETC; CLRC
    }

    @Test void mulStoresProductInYa() {
        boot(0x8D, 0x10, 0xE8, 0x10, 0xCF);                       // Y=16, A=16; MUL YA -> 0100
        go(3);
        assertEquals(0x00, spc.a);
        assertEquals(0x01, spc.y);
    }

    @Test void stateRoundTripResumesExecution() throws Exception {
        boot(0xE8, 0x11, 0xCD, 0x22, 0x7D);
        go(2);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        spc.save(new StateOut(bo));
        Spc700 copy = new Spc700(ram);
        copy.load(new StateIn(new ByteArrayInputStream(bo.toByteArray())));
        assertEquals(spc.pc, copy.pc);
        assertEquals(0x22, copy.x);
        copy.step();
        assertEquals(0x22, copy.a);
    }
}
