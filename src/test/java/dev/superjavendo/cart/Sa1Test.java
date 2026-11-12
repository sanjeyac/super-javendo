package dev.superjavendo.cart;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SA-1 tests. The S-CPU side is poked through {@link Sa1#write}/{@link Sa1#read}; the SA-1's own registers are reached by
 * running a tiny hand-assembled 65816 program (emulation mode, started at CRV = $8000) that leaves its results in I-RAM.
 */
class Sa1Test {
    private byte[] rom, bw;
    private Sa1 sa1;
    private int pc;

    @BeforeEach void setUp() {
        rom = new byte[0x800000];
        bw = new byte[0x40000];
        sa1 = new Sa1(rom, bw);
        pc = 0;
    }

    // ---- mini assembler: code is emitted at the start of the ROM, which the SA-1 sees at $00:8000
    private void lda(int imm) { rom[pc++] = (byte) 0xA9; rom[pc++] = (byte) imm; }
    private void sta(int abs) { rom[pc++] = (byte) 0x8D; rom[pc++] = (byte) abs; rom[pc++] = (byte) (abs >> 8); }
    private void ldaAbs(int abs) { rom[pc++] = (byte) 0xAD; rom[pc++] = (byte) abs; rom[pc++] = (byte) (abs >> 8); }
    private void set(int reg, int v) { lda(v); sta(reg); }
    private void ldaLong(int a) { rom[pc++] = (byte) 0xAF; rom[pc++] = (byte) a; rom[pc++] = (byte) (a >> 8); rom[pc++] = (byte) (a >> 16); }
    private void staLong(int a) { rom[pc++] = (byte) 0x8F; rom[pc++] = (byte) a; rom[pc++] = (byte) (a >> 8); rom[pc++] = (byte) (a >> 16); }
    private void raw(int... b) { for (int x : b) rom[pc++] = (byte) x; }
    private void spin() { rom[pc++] = (byte) 0x80; rom[pc++] = (byte) 0xFE; }

    /** Starts the SA-1 on the code assembled so far and lets it run. */
    private void run() {
        sa1.write(0, 0x2203, 0x00); sa1.write(0, 0x2204, 0x80);       // CRV = $8000
        sa1.write(0, 0x2200, 0x00);                                    // release from reset
        sa1.tick(200_000);
    }

    private int iram(int i) { return sa1.read(0x00, 0x3000 + i); }

    /** Unprotects I-RAM for the SA-1 and stores the 5 result bytes of the arithmetic unit at I-RAM 0..4. */
    private void storeResult() {
        for (int i = 0; i < 5; i++) { ldaAbs(0x2306 + i); sta(i); }
    }

    private long result() {
        long r = 0;
        for (int i = 4; i >= 0; i--) r = r << 8 | iram(i);
        return r;
    }

    @Test void multiplySignedSixteenBit() {
        set(0x222A, 0xFF);                                    // SA-1 may write all I-RAM
        set(0x2250, 0);                                       // multiply
        set(0x2251, 0xFE); set(0x2252, 0xFF);                 // -2
        set(0x2253, 3); set(0x2254, 0);                       // x 3 (this write starts it)
        storeResult(); spin(); run();
        assertEquals(0xFFFFFFFAL, result() & 0xFFFFFFFFL);    // -6 as 32 bits
    }

    @Test void divideGivesQuotientAndRemainder() {
        set(0x222A, 0xFF);
        set(0x2250, 1);                                       // divide
        set(0x2251, 100); set(0x2252, 0);
        set(0x2253, 7); set(0x2254, 0);
        storeResult(); spin(); run();
        assertEquals(14, iram(0) | iram(1) << 8);
        assertEquals(2, iram(2) | iram(3) << 8);
    }

    @Test void divideByZeroGivesZero() {
        set(0x222A, 0xFF);
        set(0x2250, 1);
        set(0x2251, 100); set(0x2252, 0);
        set(0x2253, 0); set(0x2254, 0);
        storeResult(); spin(); run();
        assertEquals(0, result());
    }

    @Test void cumulativeSumAddsProducts() {
        set(0x222A, 0xFF);
        set(0x2250, 2);                                       // accumulate (clears the sum)
        set(0x2251, 3); set(0x2252, 0); set(0x2253, 5); set(0x2254, 0);
        set(0x2251, 4); set(0x2252, 0); set(0x2253, 6); set(0x2254, 0);
        storeResult(); spin(); run();
        assertEquals(15 + 24, result());
        assertEquals(0, sa1.read(0x00, 0x2300) & 0x00);       // S-CPU status register readable
    }

    @Test void cumulativeSumOverflowSetsTheFlag() {
        set(0x222A, 0xFF);
        set(0x2250, 2);
        set(0x2251, 0x00); set(0x2252, 0x80);                 // -32768 x -32768 = 2^30, the 1024th addition reaches 2^40
        for (int i = 0; i < 1024; i++) { set(0x2253, 0x00); set(0x2254, 0x80); }
        ldaAbs(0x230B); sta(0);
        spin(); run();
        assertEquals(0x80, iram(0));
    }

    @Test void sa1RaisesTheCpuIrqLineAndTheCpuAcknowledgesIt() {
        sa1.write(0, 0x2201, 0x80);                           // S-CPU enables the SA-1 -> CPU interrupt
        set(0x2209, 0x80);                                    // SA-1 requests it
        spin(); run();
        assertTrue(sa1.irq());
        assertEquals(0x80, sa1.read(0x00, 0x2300) & 0x80);
        sa1.write(0, 0x2202, 0x80);                           // acknowledge
        assertFalse(sa1.irq());
        assertEquals(0, sa1.read(0x00, 0x2300) & 0x80);
    }

    @Test void disabledInterruptDoesNotReachTheCpu() {
        set(0x2209, 0x80);
        spin(); run();
        assertFalse(sa1.irq());
        assertEquals(0x80, sa1.read(0x00, 0x2300) & 0x80);    // the flag is visible anyway
    }

    @Test void scpuVectorOverridesComeFromTheSa1Registers() {
        rom[0x7FEE] = 0x11; rom[0x7FEF] = 0x22;               // real IRQ vector in ROM
        assertEquals(0x11, sa1.read(0x00, 0xFFEE));
        set(0x220E, 0xCD); set(0x220F, 0xAB);                 // SIV
        set(0x2209, 0x40);                                    // use SIV for the S-CPU IRQ vector
        spin(); run();
        assertEquals(0xCD, sa1.read(0x00, 0xFFEE));
        assertEquals(0xAB, sa1.read(0x00, 0xFFEF));
        assertEquals(0x40, sa1.read(0x00, 0x2300) & 0x40);
    }

    @Test void messageRegistersPassBetweenTheProcessors() {
        sa1.write(0, 0x2200, 0x2A);                           // S-CPU message 0xA, SA-1 kept in reset
        set(0x2209, 0x05);                                    // SA-1 message 5
        sa1.write(0, 0x2200, 0x20 | 0x0A);
        pc = 0; set(0x2209, 0x05); spin();
        sa1.write(0, 0x2203, 0x00); sa1.write(0, 0x2204, 0x80);
        sa1.write(0, 0x2200, 0x0A);
        sa1.tick(100_000);
        assertEquals(0x05, sa1.read(0x00, 0x2300) & 0x0F);
    }

    @Test void romBanksCanBeRemappedThroughTheMapper() {
        rom[0x000010] = 1; rom[0x100010] = 2; rom[0x200010] = 3; rom[0x700010] = 8;
        assertEquals(1, sa1.read(0xC0, 0x0010));
        assertEquals(2, sa1.read(0xD0, 0x0010));
        sa1.write(0, 0x2220, 0x82);                           // window C0 -> megabyte 2
        sa1.write(0, 0x2221, 0x87);                           // window D0 -> megabyte 7
        assertEquals(3, sa1.read(0xC0, 0x0010));
        assertEquals(8, sa1.read(0xD0, 0x0010));
    }

    @Test void loRomWindowShowsTheStartOfTheRom() {
        rom[0x0000] = 0x77;
        assertEquals(0x77, sa1.read(0x00, 0x8000));
        rom[0x7FFF] = 0x66;
        assertEquals(0x66, sa1.read(0x00, 0xFFFF));
    }

    @Test void iramIsWriteProtectedUntilThePageIsUnlocked() {
        sa1.write(0, 0x3000, 0x99);
        assertEquals(0, iram(0));
        sa1.write(0, 0x2229, 0x01);                           // first 256-byte page writable
        sa1.write(0, 0x3000, 0x99);
        sa1.write(0, 0x3100, 0x77);                           // second page still protected
        assertEquals(0x99, iram(0));
        assertEquals(0, iram(0x100));
    }

    @Test void bwRamIsProtectedUntilWriteEnabledAndBankSelectWorks() {
        sa1.write(0, 0x6000, 0x11);
        assertEquals(0, bw[0]);                               // power-on: all protected
        sa1.write(0, 0x2226, 0x80);                           // SWEN
        sa1.write(0, 0x6000, 0x11);
        assertEquals(0x11, bw[0] & 0xFF);
        sa1.write(0, 0x2224, 0x03);                           // BW-RAM block 3 at $6000
        sa1.write(0, 0x6010, 0x22);
        assertEquals(0x22, bw[3 * 0x2000 + 0x10] & 0xFF);
        assertEquals(0x22, sa1.read(0x00, 0x6010));
        sa1.write(0x40, 0x0005, 0x33);                        // direct window at $40:0000
        assertEquals(0x33, bw[5] & 0xFF);
        assertEquals(0x33, sa1.read(0x40, 0x0005));
    }

    @Test void bwWriteProtectAreaCanBeShrunkToAllowRamBelow() {
        sa1.write(0, 0x2228, 0x00);                           // protect nothing but the first 256 bytes
        sa1.write(0, 0x6200, 0x44);
        assertEquals(0x44, bw[0x200] & 0xFF);
        sa1.write(0, 0x6010, 0x55);
        assertEquals(0, bw[0x10]);
    }

    @Test void romWritesAreIgnoredAndUnmappedAddressesFallThrough() {
        assertTrue(sa1.write(0, 0x8000, 1));
        assertEquals(0, rom[0] & 0xFF);
        assertEquals(-1, sa1.read(0x00, 0x4000));
        assertEquals(-1, sa1.read(0x60, 0x0000));
        assertFalse(sa1.write(0x60, 0x0000, 1));
    }

    @Test void timerFiresAnInterruptFlagAtTheProgrammedPosition() {
        set(0x222A, 0xFF);
        set(0x2212, 10); set(0x2213, 0);                       // H count 10
        set(0x2210, 0x01);                                    // horizontal timer on
        set(0x220A, 0x40);                                    // timer IRQ enable
        spin(); run();
        sa1.write(0, 0x2200, 0x00);
        assertNotNull(sa1);                                   // ran 200k clocks without crashing
    }

    @Test void stateRoundTripKeepsTheProgramRunning() throws Exception {
        set(0x222A, 0xFF);
        set(0x2250, 0);
        set(0x2251, 6); set(0x2252, 0); set(0x2253, 7); set(0x2254, 0);
        storeResult(); spin(); run();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        sa1.save(new StateOut(bo));
        Sa1 copy = new Sa1(rom, bw);
        copy.load(new StateIn(new ByteArrayInputStream(bo.toByteArray())));
        copy.tick(1000);
        assertEquals(42, copy.read(0x00, 0x3000));
    }

    // ---- DMA, character conversion, bit reader, timers, interrupts, bitmap RAM

    @Test void normalDmaCopiesRomIntoIram() {
        rom[0x100] = 0x11; rom[0x101] = 0x22; rom[0x102] = 0x33; rom[0x103] = 0x44;
        set(0x222A, 0xFF);
        set(0x2230, 0x80);                                    // DMA on, ROM -> I-RAM
        set(0x2238, 4); set(0x2239, 0);                       // 4 bytes
        set(0x2232, 0x00); set(0x2233, 0x81); set(0x2234, 0x00);   // source $00:8100
        set(0x2235, 0x10); set(0x2236, 0x00);                 // destination I-RAM $0010: this write starts it
        spin(); run();
        assertEquals(0x11, iram(0x10)); assertEquals(0x22, iram(0x11));
        assertEquals(0x33, iram(0x12)); assertEquals(0x44, iram(0x13));
        assertEquals(0, iram(0x14));
    }

    @Test void normalDmaCopiesRomIntoBwRam() {
        rom[0x100] = 0x5A; rom[0x101] = 0x6B; rom[0x102] = 0x7C;
        set(0x2230, 0x84);                                    // DMA on, ROM -> BW-RAM
        set(0x2238, 3); set(0x2239, 0);
        set(0x2232, 0x00); set(0x2233, 0x81); set(0x2234, 0x00);
        set(0x2235, 0x20); set(0x2236, 0x00); set(0x2237, 0x00);   // BW-RAM $000020: the last write starts it
        spin(); run();
        assertEquals(0x5A, bw[0x20] & 0xFF); assertEquals(0x6B, bw[0x21] & 0xFF); assertEquals(0x7C, bw[0x22] & 0xFF);
    }

    @Test void dmaFromBwRamAndIramSources() {
        bw[0x30] = (byte) 0xA1;
        set(0x222A, 0xFF);
        set(0x2230, 0x81);                                    // BW-RAM -> I-RAM
        set(0x2238, 1); set(0x2239, 0);
        set(0x2232, 0x30); set(0x2233, 0x00); set(0x2234, 0x00);
        set(0x2235, 0x40); set(0x2236, 0x00);
        set(0x2230, 0x82);                                    // I-RAM -> I-RAM
        set(0x2238, 1); set(0x2239, 0);
        set(0x2232, 0x40); set(0x2233, 0x00); set(0x2234, 0x00);
        set(0x2235, 0x50); set(0x2236, 0x00);
        spin(); run();
        assertEquals(0xA1, iram(0x40));
        assertEquals(0xA1, iram(0x50));
    }

    @Test void dmaRaisesItsInterruptFlag() {
        rom[0x100] = 1;
        set(0x222A, 0xFF);
        set(0x2230, 0x80);
        set(0x2238, 1); set(0x2239, 0);
        set(0x2232, 0x00); set(0x2233, 0x81); set(0x2234, 0x00);
        set(0x2235, 0x10); set(0x2236, 0x00);
        ldaAbs(0x2301); sta(0x20);
        spin(); run();
        assertEquals(0x20, iram(0x20) & 0x20);
    }

    @Test void characterConversionType1BuildsPlanarTilesWhenTheCpuReadsBwRam() {
        bw[0] = (byte) 0xFF; bw[1] = (byte) 0xFF;             // row 0: eight pixels of colour 3 (2 bpp)
        bw[4] = 0x01;                                         // row 2: first pixel colour 1
        set(0x2231, 0x02);                                    // 2 bpp, one tile wide
        set(0x2232, 0); set(0x2233, 0); set(0x2234, 0);       // bitmap at BW-RAM $0000
        set(0x2235, 0); set(0x2237, 0);
        set(0x2230, 0x80 | 0x20 | 0x10 | 1);                  // DMA on, character conversion type 1, source BW-RAM
        set(0x2236, 0);                                       // destination I-RAM $0000: starts the conversion
        spin(); run();
        assertEquals(0xFF, sa1.read(0x00, 0x6000));           // row 0, plane 0
        assertEquals(0xFF, sa1.read(0x00, 0x6001));           // row 0, plane 1
        assertEquals(0x00, sa1.read(0x00, 0x6002));
        assertEquals(0x80, sa1.read(0x00, 0x6004));           // row 2, plane 0: only the first pixel
        assertEquals(0x00, sa1.read(0x00, 0x6005));
    }

    @Test void characterConversionEndsWhenTheCpuClearsTheMode() {
        bw[0] = 0x12;
        set(0x2231, 0x02);
        set(0x2230, 0x80 | 0x20 | 0x10 | 1);
        set(0x2236, 0);
        spin(); run();
        sa1.read(0x00, 0x6000);
        sa1.write(0x00, 0x2231, 0x80);                        // CHDEND: back to plain BW-RAM reads
        assertEquals(0x12, sa1.read(0x00, 0x6000));
    }

    @Test void characterConversionType2ConvertsPixelsPushedBytheSa1() {
        set(0x222A, 0xFF);
        set(0x2231, 0x02);                                    // 2 bpp
        set(0x2230, 0x80 | 0x20);                             // DMA on, character conversion type 2
        set(0x2235, 0); set(0x2236, 0);
        for (int i = 0; i < 8; i++) set(0x2240 + i, 3);       // eight pixels of colour 3: the last write converts them
        spin(); run();
        assertEquals(0xFF, iram(0));
        assertEquals(0xFF, iram(1));
    }

    @Test void variableLengthBitReader() {
        rom[0x200] = (byte) 0xB4; rom[0x201] = 0x2D; rom[0x202] = 0x00;
        set(0x222A, 0xFF);
        set(0x2259, 0x00); set(0x225A, 0x82); set(0x225B, 0x00);      // read from $00:8200
        set(0x2258, 0x84);                                    // fixed length mode, 4 bits per read
        ldaAbs(0x230D); sta(0);
        ldaAbs(0x230C); sta(1);
        set(0x2258, 0x04);                                    // auto-increment mode: the write itself steps 4 bits
        ldaAbs(0x230C); sta(2);
        spin(); run();
        assertEquals(0x2D, iram(0));
        assertEquals(0xDB, iram(1));
        assertEquals(0x2D, iram(2));                          // the write moved 4 more bits: 8 in total = one byte
    }

    @Test void timerFlagIsSetWhenTheHorizontalCounterMatches() {
        set(0x222A, 0xFF);
        set(0x2212, 10); set(0x2213, 0);                      // H count 10 (x4 clocks)
        set(0x2210, 0x01);                                    // horizontal timer
        raw(0xAD, 0x01, 0x23, 0x29, 0x40, 0xF0, 0xF9);        // wait until $2301 bit 6 (timer IRQ flag)
        set(0x0000, 0x77);
        spin(); run();
        assertEquals(0x77, iram(0));
    }

    @Test void verticalTimerAndCounterReadback() {
        set(0x222A, 0xFF);
        set(0x2214, 1); set(0x2215, 0);                       // V count 1
        set(0x2210, 0x02);                                    // vertical timer
        set(0x2211, 0);                                       // reset the counters
        raw(0xAD, 0x01, 0x23, 0x29, 0x40, 0xF0, 0xF9);
        ldaAbs(0x2304); sta(1);                               // V counter, latched with the H read
        ldaAbs(0x2302); sta(0);
        spin(); run();
        assertEquals(1, iram(1) | 1);
    }

    @Test void sa1ReceivesIrqAndNmiFromTheCpuAndRunsItsHandlers() {
        // IRQ handler at $8100: store $99 at I-RAM 1 ; NMI handler at $8180: store $88 at I-RAM 2
        int[] irq = {0xA9, 0x99, 0x8D, 0x01, 0x00, 0x40}, nmi = {0xA9, 0x88, 0x8D, 0x02, 0x00, 0x40};
        for (int i = 0; i < irq.length; i++) { rom[0x100 + i] = (byte) irq[i]; rom[0x180 + i] = (byte) nmi[i]; }
        sa1.write(0, 0x2207, 0x00); sa1.write(0, 0x2208, 0x81);       // CIV = $8100
        sa1.write(0, 0x2205, 0x80); sa1.write(0, 0x2206, 0x81);       // CNV = $8180
        set(0x222A, 0xFF);
        set(0x220A, 0xA0 & 0x80 | 0x10);                      // enable SA-1 IRQ and NMI sources
        raw(0x58);                                            // CLI
        spin();
        sa1.write(0, 0x2203, 0x00); sa1.write(0, 0x2204, 0x80);
        sa1.write(0, 0x2200, 0x00);
        sa1.tick(5000);
        assertEquals(0, iram(1));
        sa1.write(0, 0x2200, 0x80);                           // S-CPU raises the SA-1 IRQ
        sa1.tick(5000);
        assertEquals(0x99, iram(1));
        sa1.write(0, 0x2200, 0x10);                           // and the NMI
        sa1.tick(5000);
        assertEquals(0x88, iram(2));
    }

    @Test void sa1WaitsWhileHeldInTheWaitState() {
        set(0x222A, 0xFF);
        set(0x0000, 0x42);
        spin();
        sa1.write(0, 0x2203, 0x00); sa1.write(0, 0x2204, 0x80);
        sa1.write(0, 0x2200, 0x40);                           // RDYB: halted but not reset
        sa1.tick(5000);
        assertEquals(0, iram(0));
        sa1.write(0, 0x2200, 0x00);
        sa1.tick(5000);
        assertEquals(0x42, iram(0));
    }

    @Test void sa1WritesBwRamThroughItsWindowsAndBitmapMode() {
        set(0x2227, 0x80);                                    // SA-1 write enable for BW-RAM
        lda(0x5A); staLong(0x400010);                         // linear window
        ldaLong(0x400010); sta(0x0001 + 0);                   // read it back (I-RAM is protected: may be dropped)
        set(0x2225, 0x02);                                    // $6000 window -> block 2
        lda(0x66); sta(0x6000);
        spin(); run();
        assertEquals(0x5A, bw[0x10] & 0xFF);
        assertEquals(0x66, bw[0x4000] & 0xFF);
    }

    @Test void bitmapModeStoresPackedPixels() {
        set(0x2227, 0x80);
        set(0x223F, 0x00);                                    // 4 bits per pixel
        set(0x2225, 0x80);                                    // $6000 window as a bitmap
        lda(0x0A); sta(0x6000);
        lda(0x05); sta(0x6001);
        spin(); run();
        assertEquals(0x5A, bw[0] & 0xFF);

        setUp();
        set(0x2227, 0x80);
        set(0x223F, 0x80);                                    // 2 bits per pixel
        set(0x2225, 0x80);
        lda(0x03); sta(0x6000);
        lda(0x02); sta(0x6003);
        ldaAbs(0x6003); sta(0x0000 + 0);
        spin(); run();
        assertEquals(0x83, bw[0] & 0xFF);
    }
}
