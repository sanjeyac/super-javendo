package dev.superjavendo.cart;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The decompressor itself is checked against snes9x/bsnes by the {@code sdd1} chip ROM; these tests cover the rest of the chip. */
class Sdd1Test {
    @Test void mirrorFoldsAddressesIntoRomsThatAreNotAPowerOfTwo() {
        assertEquals(5, Sdd1.mirror(5, 0x100000));
        assertEquals(0, Sdd1.mirror(0x100000, 0x100000));           // power of two: wraps
        assertEquals(7, Sdd1.mirror(0x100007, 0x100000));
        assertEquals(0, Sdd1.mirror(123, 0));
        // 1.5 MB ROM: the last half megabyte mirrors the one before it
        int size = 0x180000;
        assertEquals(0x17FFFF, Sdd1.mirror(0x17FFFF, size));
        assertEquals(0x100000, Sdd1.mirror(0x180000, size));
        assertEquals(0x100010, Sdd1.mirror(0x180010, size));
        assertEquals(0x000010, Sdd1.mirror(0x200010, size));
    }

    private static byte[] rom() {
        byte[] r = new byte[0x400000];
        for (int i = 0; i < r.length; i += 0x1000) r[i] = (byte) (i >> 12);
        return r;
    }

    @Test void bankRegistersReadBackAndAreMasked() {
        Sdd1 s = new Sdd1(rom());
        assertEquals(0, s.read(0x00, 0x4804));
        assertEquals(1, s.read(0x00, 0x4805));
        assertEquals(2, s.read(0x00, 0x4806));
        assertEquals(3, s.read(0x00, 0x4807));
        assertTrue(s.write(0x00, 0x4804, 0xFF));
        assertEquals(0x8F, s.read(0x00, 0x4804));
        s.write(0x00, 0x4800, 0x12); s.write(0x00, 0x4801, 0x34);
        assertEquals(0x12, s.read(0x80, 0x4800));
        assertEquals(0x34, s.read(0x80, 0x4801));
        assertEquals(-1, s.read(0x00, 0x4802));                       // unused register
        assertFalse(s.write(0x40, 0x4800, 1));                        // not a register window
    }

    @Test void bigBankWindowsShowTheSelectedMegabyte() {
        Sdd1 s = new Sdd1(rom());
        int v0 = s.read(0xC0, 0x1000);                                 // window 0 -> megabyte 0
        s.write(0x00, 0x4804, 2);                                      // window 0 -> megabyte 2
        int v2 = s.read(0xC0, 0x1000);
        assertEquals(1, v0);                                           // marker byte at offset $1000
        assertEquals((2 * 0x100000 + 0x1000) >> 12 & 0xFF, v2);
        assertEquals(-1, s.read(0x50, 0x0000));                        // $40-$BF high banks: normal mapping
    }

    @Test void lowBanksShowRomThroughTheLoRomLikeView() {
        byte[] r = rom();
        r[0x0010] = 0x5A;
        Sdd1 s = new Sdd1(r);
        assertEquals(0x5A, s.read(0x00, 0x8010));
        assertEquals(0x5A, s.read(0x80, 0x8010));
        assertEquals(-1, s.read(0x00, 0x2000));
    }

    @Test void dmaSnoopingRecordsAddressAndLengthWithoutConsumingTheWrite() {
        Sdd1 s = new Sdd1(rom());
        assertFalse(s.write(0x00, 0x4302, 0x34));
        assertFalse(s.write(0x00, 0x4303, 0x12));
        assertFalse(s.write(0x00, 0x4304, 0xC1));
        assertFalse(s.write(0x00, 0x4305, 0x02));
        assertFalse(s.write(0x00, 0x4306, 0x00));
        assertFalse(s.write(0x00, 0x4300, 0x00));                      // other DMA registers are ignored
    }

    @Test void streamEndsAfterTheProgrammedNumberOfBytesAndReleasesTheChannel() {
        Sdd1 s = new Sdd1(rom());
        s.write(0x00, 0x4800, 0x01); s.write(0x00, 0x4801, 0x01);
        s.write(0x00, 0x4302, 0x00); s.write(0x00, 0x4303, 0x00); s.write(0x00, 0x4304, 0xC1);
        s.write(0x00, 0x4305, 0x03); s.write(0x00, 0x4306, 0x00);
        for (int i = 0; i < 3; i++) s.read(0xC1, 0x0000);              // three decompressed bytes
        assertEquals(0, s.read(0x00, 0x4801) & 1, "channel released when the byte count reaches zero");
        assertEquals(0x01, s.read(0x00, 0x4800));
    }

    @Test void stateRoundTrip() throws Exception {
        Sdd1 s = new Sdd1(rom());
        s.write(0x00, 0x4800, 0x05); s.write(0x00, 0x4805, 0x07); s.write(0x00, 0x4302, 0x99);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        s.save(new StateOut(bo));
        Sdd1 copy = new Sdd1(rom());
        copy.load(new StateIn(new ByteArrayInputStream(bo.toByteArray())));
        assertEquals(0x05, copy.read(0x00, 0x4800));
        assertEquals(0x07, copy.read(0x00, 0x4805));
    }
}
