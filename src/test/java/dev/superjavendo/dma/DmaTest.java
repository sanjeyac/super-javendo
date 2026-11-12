package dev.superjavendo.dma;

import dev.superjavendo.bus.Memory;
import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DmaTest {
    /** 128 KB of RAM at $7E:0000 plus a recorder for the B-bus ($21xx). */
    static final class Fake implements Memory {
        final byte[] ram = new byte[0x20000];
        final int[] io = new int[256];
        final List<int[]> ioWrites = new ArrayList<>();   // {register, value}
        public int read(int a) { return (a & 0xFFFF00) == 0x2100 ? io[a & 0xFF] : ram[a & 0x1FFFF] & 0xFF; }
        public void write(int a, int v) {
            if ((a & 0xFFFF00) == 0x2100) { io[a & 0xFF] = v; ioWrites.add(new int[]{a & 0xFF, v}); }
            else ram[a & 0x1FFFF] = (byte) v;
        }
        int[] writtenValues() { return ioWrites.stream().mapToInt(w -> w[1]).toArray(); }
        int[] writtenRegs() { return ioWrites.stream().mapToInt(w -> w[0]).toArray(); }
    }

    private Fake mem;
    private Dma dma;

    @BeforeEach void setUp() { mem = new Fake(); dma = new Dma(mem); }

    /** Programs channel ch: DMAP, B-bus register, A-bus bank:address, length. */
    private void setup(int ch, int dmap, int bbad, int bank, int addr, int len) {
        int base = ch << 4;
        dma.writeReg(base, dmap); dma.writeReg(base + 1, bbad);
        dma.writeReg(base + 2, addr & 0xFF); dma.writeReg(base + 3, addr >> 8); dma.writeReg(base + 4, bank);
        dma.writeReg(base + 5, len & 0xFF); dma.writeReg(base + 6, len >> 8);
    }

    private void fill(int addr, int... bytes) { for (int i = 0; i < bytes.length; i++) mem.ram[(addr + i) & 0x1FFFF] = (byte) bytes[i]; }

    @Test void registersPowerOnAsFF() {
        for (int i = 0; i < 0x80; i++) assertEquals(0xFF, dma.readReg(i));
    }

    @Test void registerReadBackIsMaskedToAByte() {
        dma.writeReg(0x31, 0x1AB);
        assertEquals(0xAB, dma.readReg(0x31));
    }

    @Test void mode0WritesOneRegister() {
        fill(0x100, 1, 2, 3);
        setup(0, 0x00, 0x18, 0x7E, 0x0100, 3);
        dma.runGeneral(1);
        assertArrayEquals(new int[]{1, 2, 3}, mem.writtenValues());
        assertArrayEquals(new int[]{0x18, 0x18, 0x18}, mem.writtenRegs());
    }

    @Test void mode1AlternatesTwoRegisters() {
        fill(0x100, 1, 2, 3, 4);
        setup(0, 0x01, 0x18, 0x7E, 0x0100, 4);
        dma.runGeneral(1);
        assertArrayEquals(new int[]{0x18, 0x19, 0x18, 0x19}, mem.writtenRegs());
    }

    @Test void mode3WritesPairsToTwoRegisters() {
        fill(0x100, 1, 2, 3, 4, 5, 6, 7, 8);
        setup(0, 0x03, 0x26, 0x7E, 0x0100, 8);
        dma.runGeneral(1);
        assertArrayEquals(new int[]{0x26, 0x26, 0x27, 0x27, 0x26, 0x26, 0x27, 0x27}, mem.writtenRegs());
    }

    @Test void mode4WritesFourConsecutiveRegisters() {
        fill(0x100, 1, 2, 3, 4);
        setup(0, 0x04, 0x10, 0x7E, 0x0100, 4);
        dma.runGeneral(1);
        assertArrayEquals(new int[]{0x10, 0x11, 0x12, 0x13}, mem.writtenRegs());
    }

    @Test void fixedAddressRepeatsTheSameSourceByte() {
        fill(0x100, 9, 8, 7);
        setup(0, 0x08, 0x18, 0x7E, 0x0100, 3);
        dma.runGeneral(1);
        assertArrayEquals(new int[]{9, 9, 9}, mem.writtenValues());
        assertEquals(0x00, dma.readReg(0x02));   // address did not move
        assertEquals(0x01, dma.readReg(0x03));
    }

    @Test void decrementingAddressWalksBackwards() {
        fill(0x100, 1, 2, 3);
        setup(0, 0x10, 0x18, 0x7E, 0x0102, 3);
        dma.runGeneral(1);
        assertArrayEquals(new int[]{3, 2, 1}, mem.writtenValues());
    }

    @Test void directionBToACopiesIntoMemory() {
        mem.io[0x39] = 0x5C;
        setup(0, 0x80, 0x39, 0x7E, 0x0200, 2);
        dma.runGeneral(1);
        assertEquals(0x5C, mem.ram[0x200] & 0xFF);
        assertEquals(0x5C, mem.ram[0x201] & 0xFF);
        assertTrue(mem.ioWrites.isEmpty());
    }

    @Test void transferUpdatesAddressAndClearsLength() {
        fill(0x100, 1, 2, 3, 4);
        setup(0, 0x00, 0x18, 0x7E, 0x0100, 4);
        dma.runGeneral(1);
        assertEquals(0x04, dma.readReg(0x02));
        assertEquals(0x01, dma.readReg(0x03));
        assertEquals(0, dma.readReg(0x05));
        assertEquals(0, dma.readReg(0x06));
    }

    @Test void lengthZeroMeans64K() {
        setup(0, 0x08, 0x18, 0x7E, 0x0000, 0);
        dma.runGeneral(1);
        assertEquals(0x10000, mem.ioWrites.size());
    }

    @Test void cpuStallIsEightClocksPerBytePlusOverhead() {
        setup(0, 0x00, 0x18, 0x7E, 0x0100, 4);
        assertEquals(8 + 8 * 4 + 8, dma.runGeneral(1));
        assertEquals(0, dma.runGeneral(0));          // no channel selected
    }

    @Test void channelsRunInOrderAndOnlyThoseInTheMask() {
        fill(0x100, 0xA1); fill(0x110, 0xB2); fill(0x120, 0xC3);
        setup(0, 0x00, 0x18, 0x7E, 0x0100, 1);
        setup(1, 0x00, 0x19, 0x7E, 0x0110, 1);
        setup(2, 0x00, 0x1A, 0x7E, 0x0120, 1);
        dma.runGeneral(0b101);
        assertArrayEquals(new int[]{0x18, 0x1A}, mem.writtenRegs());
        assertArrayEquals(new int[]{0xA1, 0xC3}, mem.writtenValues());
    }

    @Test void sourceAddressWrapsInsideTheBank() {
        fill(0xFFFF, 7); fill(0x0000, 8);
        setup(0, 0x00, 0x18, 0x7E, 0xFFFF, 2);
        dma.runGeneral(1);
        assertArrayEquals(new int[]{7, 8}, mem.writtenValues());
    }

    // ------------------------------------------------------------------ HDMA

    private void hdma(int ch, int dmap, int bbad, int tableAddr) {
        setup(ch, dmap, bbad, 0x7E, tableAddr, 0);
        dma.hdmaEnable |= 1 << ch;
    }

    @Test void hdmaDirectNonRepeatTransfersOnlyOnTheFirstLineOfAnEntry() {
        fill(0x100, 0x02, 0x11, 0x81, 0x22, 0x00);
        hdma(0, 0x00, 0x26, 0x0100);
        dma.hdmaInit();
        for (int line = 0; line < 5; line++) dma.hdmaRun();
        assertArrayEquals(new int[]{0x11, 0x22}, mem.writtenValues());
    }

    @Test void hdmaRepeatEntryTransfersEveryLine() {
        fill(0x100, 0x83, 0xAA, 0xBB, 0xCC, 0x00);
        hdma(0, 0x00, 0x26, 0x0100);
        dma.hdmaInit();
        for (int line = 0; line < 6; line++) dma.hdmaRun();
        assertArrayEquals(new int[]{0xAA, 0xBB, 0xCC}, mem.writtenValues());
    }

    @Test void hdmaTerminatesOnZeroLineCount() {
        fill(0x100, 0x00, 0x55);
        hdma(0, 0x00, 0x26, 0x0100);
        dma.hdmaInit();
        for (int line = 0; line < 3; line++) dma.hdmaRun();
        assertTrue(mem.ioWrites.isEmpty());
    }

    @Test void hdmaTwoRegisterModeWritesBothBytesPerTransfer() {
        fill(0x100, 0x81, 0x12, 0x34, 0x00);
        hdma(0, 0x02, 0x0D, 0x0100);   // mode 2: same register twice (scroll registers)
        dma.hdmaInit();
        dma.hdmaRun();
        assertArrayEquals(new int[]{0x0D, 0x0D}, mem.writtenRegs());
        assertArrayEquals(new int[]{0x12, 0x34}, mem.writtenValues());
    }

    @Test void hdmaIndirectReadsDataThroughThePointer() {
        fill(0x100, 0x01, 0x00, 0x02, 0x00);          // 1 line, data at $7E:0200
        fill(0x200, 0x5A);
        hdma(0, 0x40, 0x26, 0x0100);
        dma.writeReg(0x07, 0x7E);                      // indirect bank
        dma.hdmaInit();
        dma.hdmaRun();
        assertArrayEquals(new int[]{0x5A}, mem.writtenValues());
    }

    @Test void hdmaChannelsNotEnabledDoNothing() {
        fill(0x100, 0x81, 0x77, 0x00);
        setup(0, 0x00, 0x26, 0x7E, 0x0100, 0);
        dma.hdmaInit();
        dma.hdmaRun();
        assertTrue(mem.ioWrites.isEmpty());
    }

    @Test void hdmaRunReportsStallClocks() {
        fill(0x100, 0x81, 0x77, 0x00);
        hdma(0, 0x00, 0x26, 0x0100);
        dma.hdmaInit();
        assertEquals(8 + 8, dma.hdmaRun());    // one byte transferred + channel overhead
        assertEquals(0, new Dma(mem).hdmaRun());
    }

    @Test void stateRoundTripKeepsRegistersAndHdmaProgress() throws Exception {
        fill(0x100, 0x83, 0xAA, 0xBB, 0xCC, 0x00);
        hdma(0, 0x00, 0x26, 0x0100);
        dma.hdmaInit();
        dma.hdmaRun();                                 // AA written, BB pending
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        dma.save(new StateOut(bo));

        Dma copy = new Dma(mem);
        copy.load(new StateIn(new ByteArrayInputStream(bo.toByteArray())));
        mem.ioWrites.clear();
        copy.hdmaRun(); copy.hdmaRun(); copy.hdmaRun();
        assertArrayEquals(new int[]{0xBB, 0xCC}, mem.writtenValues());
        assertEquals(1, copy.hdmaEnable);
    }
}
