package dev.superjavendo.cart;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Obc1Test {
    private final Obc1 chip = new Obc1();

    private void w(int off, int v) { assertTrue(chip.write(0x00, off, v)); }
    private int r(int off) { return chip.read(0x00, off); }

    @Test void plainRamWindow() {
        w(0x6000, 0x12); w(0x7000, 0x34);
        assertEquals(0x12, r(0x6000));
        assertEquals(0x34, r(0x7000));
    }

    @Test void ignoresAddressesOutsideTheWindow() {
        assertEquals(-1, chip.read(0x00, 0x8000));
        assertFalse(chip.write(0x00, 0x5FFF, 1));
        assertEquals(-1, chip.read(0x40, 0x6000));
    }

    @Test void spriteRegistersAreIndexedByTheSpriteNumber() {
        w(0x7FF5, 0);                         // sprite table at $7C00
        w(0x7FF6, 3);                         // sprite 3
        for (int i = 0; i < 4; i++) w(0x7FF0 + i, 0x10 + i);
        assertEquals(0x10, r(0x7C0C));        // 3 * 4 bytes into the table
        assertEquals(0x13, r(0x7C0F));
        w(0x7FF6, 0);
        assertEquals(0, r(0x7FF0));           // sprite 0 untouched
        w(0x7FF6, 3);
        assertEquals(0x12, r(0x7FF2));
    }

    @Test void highTableHoldsTwoBitsPerSprite() {
        w(0x7FF5, 0);
        w(0x7FF6, 5);                         // byte 5>>2 = 1, bits 2..3
        w(0x7FF4, 0x03);
        assertEquals(0x0C, r(0x7C00 + 0x200 + 1));
        w(0x7FF6, 4);                         // same byte, bits 0..1
        w(0x7FF4, 0x02);
        assertEquals(0x0E, r(0x7C00 + 0x200 + 1));
        assertEquals(0x0E, r(0x7FF4));         // reads back the whole byte
    }

    @Test void baseRegisterSelectsTheOtherHalf() {
        w(0x7FF5, 1);                         // table at $7800
        w(0x7FF6, 0);
        w(0x7FF0, 0xAB);
        assertEquals(0xAB, r(0x7800));
        w(0x7FF5, 0);
        assertEquals(0, r(0x7FF0));           // now looking at $7C00
    }

    @Test void indexIsSevenBits() {
        w(0x7FF6, 0x85);
        assertEquals(0x85, r(0x7FF6));        // register reads back as written
        w(0x7FF5, 0); w(0x7FF0, 7);
        assertEquals(7, r(0x7C00 + 5 * 4));
    }

    @Test void headerSelectsTheChip() {
        assertEquals(Chip.OBC1, Chip.fromRomType(0x25));
        assertTrue(Chip.OBC1.supported());
    }
}
