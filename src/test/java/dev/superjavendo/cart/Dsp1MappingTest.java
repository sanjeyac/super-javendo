package dev.superjavendo.cart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Dsp1MappingTest {
    private static void send(Dsp1 d, int bank, int off, int... bytes) { for (int b : bytes) assertTrue(d.write(bank, off, b)); }

    @Test void loRomBoardUsesBank30AndC000ForStatus() {
        Dsp1 d = new Dsp1(false);
        assertEquals(0x80, d.read(0x30, 0xC000));
        send(d, 0x30, 0x8000, 0x00, 0x00, 0x40, 0x00, 0x40);       // Multiply 0.5 * 0.5
        assertEquals(0x00, d.read(0x30, 0x8000));
        assertEquals(0x20, d.read(0x30, 0x8000));
        assertEquals(-1, d.read(0x00, 0x6000));                     // not claimed on a LoROM board
    }

    @Test void hiRomBoardUsesBanks00To1FAt6000And7000() {
        Dsp1 d = new Dsp1(true);                                    // Super Mario Kart
        assertEquals(0x80, d.read(0x00, 0x7000));
        send(d, 0x00, 0x6000, 0x00, 0x00, 0x40, 0x00, 0x40);
        assertEquals(0x00, d.read(0x80, 0x6000));                   // $80-$9F mirror
        assertEquals(0x20, d.read(0x1F, 0x6000));
        assertEquals(-1, d.read(0x30, 0x8000));
        assertEquals(-1, d.read(0x20, 0x6000));                     // SRAM area of HiROM, not the chip
    }
}
