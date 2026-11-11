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
        p.write(0x0E, 0xFF); p.write(0x0E, 0x03);    // BGVOFS=-1: line 0 shows tile row 0 (PPU renders line+1)
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
        p.write(0x0E, 0xFF); p.write(0x0E, 0x03);     // BGVOFS=-1 (see above)
        p.write(0x0D, 63); p.write(0x0D, 0);          // BG1HOFS = 63
        p.renderLine(0);
        // tile 0 covers x 0..7; with hofs=63 the first screen pixels show map column 7 (empty, transparent)
        assertEquals(0xFF000000, p.frame[0]);
        p.write(0x0D, 4); p.write(0x0D, 0);           // hofs=4: pixels 0..3 still inside tile 0
        p.renderLine(0);
        assertEquals(0xFFFF0000, p.frame[3]);
        assertEquals(0xFF000000, p.frame[4]);
    }

    @Test void bgLineZeroShowsSecondTileRow() {
        // Like hardware/snes9x: the first visible scanline renders tile row 1 (y+1) when scroll is 0.
        Ppu p = new Ppu();
        p.write(0x00, 0x0F); p.write(0x05, 0x01); p.write(0x2C, 0x01);
        p.write(0x07, 0x00); p.write(0x0B, 0x01);
        vramWrite(p, 0, 0x01, 0x00);
        vramWrite(p, 0x1000 + 16, 0x00, 0x00);        // tile 1 row 0 empty
        vramWrite(p, 0x1000 + 16 + 1, 0xFF, 0x00);    // tile 1 row 1: colour 1
        p.write(0x21, 1); p.write(0x22, 0x1F); p.write(0x22, 0x00);
        p.renderLine(0);
        assertEquals(0xFFFF0000, p.frame[0]);
        p.renderLine(7);                               // line 7 -> tile row 8 = next tile (empty)
        assertEquals(0xFF000000, p.frame[7 * 256]);
    }

    @Test void mode7LineZeroSamplesSecondRow() {
        Ppu p = new Ppu();
        p.write(0x00, 0x0F); p.write(0x05, 0x07); p.write(0x2C, 0x01);
        // identity matrix: A=D=$0100
        p.write(0x1B, 0x00); p.write(0x1B, 0x01);
        p.write(0x1E, 0x00); p.write(0x1E, 0x01);
        // map entry 0 -> tile 1 (even VRAM bytes), tile 1 pixel data at odd bytes
        p.write(0x15, 0x00); p.write(0x16, 0); p.write(0x17, 0); p.write(0x18, 1);
        // tile 1 row 1 (pixel y=1) = colour 5: byte offset (1*64 + 8)*2 + 1 -> word (1*64+8), high byte
        int word = 1 * 64 + 1 * 8;
        p.write(0x15, 0x80); p.write(0x16, word & 0xFF); p.write(0x17, word >> 8); p.write(0x18, 0); p.write(0x19, 5);
        p.write(0x21, 5); p.write(0x22, 0x1F); p.write(0x22, 0x00);
        p.renderLine(0);
        assertEquals(0xFFFF0000, p.frame[0]);
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

    // ---- hi-res and interlace

    /** Mode 1 BG1 with one solid red tile at the top left, display on, BG1 on the main screen. */
    private static Ppu redTile(int mode) {
        Ppu p = new Ppu();
        p.write(0x00, 0x0F); p.write(0x05, mode); p.write(0x2C, 0x01);
        if (mode >= 5) p.write(0x2D, 0x01);           // hi-res: BG1 on the sub screen too (even dots)
        p.write(0x07, 0x00); p.write(0x0B, 0x01);
        vramWrite(p, 0, 0x01, 0x00);                  // map entry 0 -> char 1
        vramWrite(p, 0x1000 + 16, 0xFF, 0x00);        // char 1 row 0: colour 1 everywhere
        p.write(0x21, 1); p.write(0x22, 0x1F); p.write(0x22, 0x00);
        p.write(0x0E, 0xFF); p.write(0x0E, 0x03);     // BGVOFS=-1: line 0 shows tile row 0
        return p;
    }

    @Test void lowResFrameStaysAt256x224() {
        Ppu p = redTile(1);
        p.frameStart(); p.renderLine(0); p.vblankStart();
        assertEquals(256, p.outputWidth());
        assertEquals(224, p.outputHeight());
        assertSame(p.frame, p.outputPixels());
    }

    @Test void mode5TilesAreSixteenDotsWideAndFrameIs512() {
        Ppu p = redTile(5);
        vramWrite(p, 0x1000 + 32, 0x00, 0x00);        // char 2 (the right half of the 16-dot tile): empty
        p.frameStart(); p.renderLine(0); p.vblankStart();
        int[] o = p.outputPixels();
        assertEquals(512, p.outputWidth());
        assertEquals(224, p.outputHeight());
        for (int dot = 0; dot < 8; dot++) assertEquals(0xFFFF0000, o[dot], "dot " + dot);
        for (int dot = 8; dot < 16; dot++) assertEquals(0xFF000000, o[dot], "dot " + dot);
    }

    @Test void mode5HorizontalScrollMovesTwoDotsPerPixel() {
        Ppu p = redTile(5);
        vramWrite(p, 0x1000 + 32, 0x00, 0x00);
        p.write(0x0D, 1); p.write(0x0D, 0);           // BG1HOFS = 1 lo-res pixel = 2 dots
        p.frameStart(); p.renderLine(0); p.vblankStart();
        int[] o = p.outputPixels();
        assertEquals(0xFFFF0000, o[5]);               // dots 0..5 are the tile's dots 2..7
        assertEquals(0xFF000000, o[6]);
    }

    @Test void pseudoHiResSplitsMainAndSubScreen() {
        Ppu p = redTile(1);
        p.write(0x2C, 0x00); p.write(0x2D, 0x01);     // BG1 only on the sub screen
        p.write(0x33, 0x08);                          // pseudo hi-res
        p.frameStart(); p.renderLine(0); p.vblankStart();
        int[] o = p.outputPixels();
        assertEquals(512, p.outputWidth());
        assertEquals(0xFFFF0000, o[2]);               // even dot = sub screen: red
        assertEquals(0xFF000000, o[3]);               // odd dot = main screen: backdrop
    }

    @Test void interlaceFieldsFillAlternateRowsAndKeepTheOtherField() {
        Ppu p = redTile(1);
        p.write(0x33, 0x01);                          // interlace
        p.frameStart(); p.renderLine(0); p.vblankStart();    // first frame: field 1
        assertEquals(448, p.outputHeight());
        assertEquals(512, p.outputWidth());
        int[] o = p.outputPixels();
        assertEquals(0, o[0]);                        // row 0 belongs to the other field: not drawn yet
        assertEquals(0xFFFF0000, o[512]);             // row 1
        p.frameStart(); p.renderLine(0); p.vblankStart();    // second frame: field 0
        o = p.outputPixels();
        assertEquals(0xFFFF0000, o[0]);
        assertEquals(0xFFFF0000, o[512]);             // row 1 kept from the previous frame
    }

    @Test void stat78ReportsTheField() {
        Ppu p = new Ppu();
        p.frameStart();
        int a = p.read(0x3F) >> 7;
        p.frameStart();
        assertEquals(1 - a, p.read(0x3F) >> 7);
    }
}
