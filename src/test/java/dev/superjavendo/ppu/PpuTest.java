package dev.superjavendo.ppu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PpuTest {
    private static void vramWrite(Ppu p, int wordAddr, int lo, int hi) {
        p.write(0x15, 0x80);
        p.write(0x16, wordAddr & 0xFF); p.write(0x17, wordAddr >> 8);
        p.write(0x18, lo); p.write(0x19, hi);
    }

    @Test void bg1Mode1RendersTileAndBackdrop() {
        Ppu p = new Ppu();
        p.write(0x00, 0x0F);                         // display on, full brightness
        p.write(0x05, 0x01);                         // mode 1
        p.write(0x07, 0x00);                         // BG1 map at word 0
        p.write(0x0B, 0x01);                         // BG1 chars at word $1000 (nibble 1)
        p.write(0x2C, 0x01);                         // TM: BG1
        // tilemap entry 0 -> tile 1, palette 0
        vramWrite(p, 0, 0x01, 0x00);
        // tile 1, 4bpp, row 0: plane0 = $FF -> colour 1 across 8 pixels
        int tileWord = 0x1000 + 1 * 16;
        vramWrite(p, tileWord, 0xFF, 0x00);
        // CGRAM colour 1 = pure red (31)
        p.write(0x21, 1); p.write(0x22, 0x1F); p.write(0x22, 0x00);
        p.renderLine(0);
        assertEquals(0xFFFF0000, p.frame[0]);        // red, 31 -> 255
        assertEquals(0xFFFF0000, p.frame[7]);
        assertEquals(0xFF000000, p.frame[8]);        // transparent -> backdrop (black)
    }

    @Test void scrollRegisterKeepsLowBitsAcrossTwoByteWrite() {
        // Writing 63 then 0 (as HDMA does) must give a horizontal scroll of 63, not 59.
        Ppu p = new Ppu();
        p.write(0x00, 0x0F); p.write(0x05, 0x01); p.write(0x2C, 0x01);
        p.write(0x07, 0x00); p.write(0x0B, 0x01);
        vramWrite(p, 0, 0x01, 0x00);                  // map entry 0 -> tile 1
        vramWrite(p, 0x1000 + 16, 0xFF, 0x00);        // tile 1 row 0: colour 1 everywhere
        p.write(0x21, 1); p.write(0x22, 0x1F); p.write(0x22, 0x00);
        p.write(0x0D, 63); p.write(0x0D, 0);          // BG1HOFS = 63
        p.renderLine(0);
        // tile 0 covers x 0..7; with hofs=63 the first screen pixels show map column 7 (empty, transparent)
        assertEquals(0xFF000000, p.frame[0]);
        p.write(0x0D, 4); p.write(0x0D, 0);           // hofs=4: pixels 0..3 still inside tile 0
        p.renderLine(0);
        assertEquals(0xFFFF0000, p.frame[3]);
        assertEquals(0xFF000000, p.frame[4]);
    }

    @Test void forceBlankIsBlack() {
        Ppu p = new Ppu();
        p.write(0x00, 0x80);
        p.renderLine(0);
        assertEquals(0xFF000000, p.frame[0]);
    }

    @Test void fixedColourAddMath() {
        Ppu p = new Ppu();
        p.write(0x00, 0x0F);
        p.write(0x05, 0x01);
        // no layers enabled, backdrop = colour 0 (black); add fixed blue to backdrop
        p.write(0x31, 0x20);                          // add, backdrop enabled
        p.write(0x32, 0x80 | 31);                     // fixed colour: blue = 31
        p.renderLine(0);
        assertEquals(0xFF0000FF, p.frame[0]);
    }

    @Test void vramReadBackThroughPort() {
        Ppu p = new Ppu();
        vramWrite(p, 0x0100, 0xAB, 0xCD);
        p.write(0x15, 0x80);
        p.write(0x16, 0x00); p.write(0x17, 0x01);
        assertEquals(0xAB, p.read(0x39));
        assertEquals(0xCD, p.read(0x3A));
    }
}
