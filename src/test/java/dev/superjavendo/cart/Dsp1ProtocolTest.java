package dev.superjavendo.cart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The chip is a byte-level state machine: every DR access counts, which Super Mario Kart's raster loop relies on. */
class Dsp1ProtocolTest {
    private static void w(Dsp1 d, int... b) { for (int x : b) d.write(0x30, 0x8000, x); }
    private static int r(Dsp1 d) { return d.read(0x30, 0x8000); }

    private static int[] parameter(Dsp1 d) {
        w(d, 0x02, 0x80, 0x08, 0xA0, 0x27, 0, 0, 0x40, 0, 0x00, 0x01, 0x00, 0x01, 0x00, 0x34);
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = r(d);
        return out;
    }

    private static void rasterLines(Dsp1 d, int lines, boolean leaveWord) {
        w(d, 0x0A, 0xB5, 0xFF);
        for (int i = 0; i < lines * 8 - (leaveWord ? 2 : 0); i++) r(d);
    }

    @Test void rasterStreamEndsWithTheZeroEightyWordAndAFreshCommandWorks() {
        Dsp1 d = new Dsp1(false);
        int[] first = parameter(d);
        rasterLines(d, 3, true);
        w(d, 0x00, 0x80);                                    // $8000 in place of the unread word ends the stream
        assertArrayEquals(first, parameter(d));
    }

    @Test void idleReadsSeeTheCompletionValue() {
        Dsp1 d = new Dsp1(false);
        assertEquals(0x80, r(d));                            // DR resets to $0080
    }
}
