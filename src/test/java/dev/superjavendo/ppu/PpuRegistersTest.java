package dev.superjavendo.ppu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PPU registers, sprites, windows, colour math and the remaining background modes. */
class PpuRegistersTest {
    private static final int RED = 0xFFFF0000, BLACK = 0xFF000000, GREEN = 0xFF00FF00;

    private static void vramWrite(Ppu p, int wordAddr, int lo, int hi) {
        p.write(0x15, 0x80);
        p.write(0x16, wordAddr & 0xFF); p.write(0x17, wordAddr >> 8);
        p.write(0x18, lo); p.write(0x19, hi);
    }

    private static void colour(Ppu p, int index, int bgr555) {
        p.write(0x21, index); p.write(0x22, bgr555 & 0xFF); p.write(0x22, bgr555 >> 8);
    }

    private static int rgb(int r5, int g5, int b5) {
        return 0xFF000000 | ((r5 << 3 | r5 >> 2) << 16) | ((g5 << 3 | g5 >> 2) << 8) | (b5 << 3 | b5 >> 2);
    }

    /** Mode 1, BG1 enabled on the main screen, map at word 0, characters at word $1000, colour 1 = red, scrolled to show tile row 0. */
    private static Ppu bg1() {
        Ppu p = new Ppu();
        p.write(0x00, 0x0F); p.write(0x05, 0x01); p.write(0x07, 0x00); p.write(0x0B, 0x01); p.write(0x2C, 0x01);
        colour(p, 1, 0x001F);
        p.write(0x0E, 0xFF); p.write(0x0E, 0x03);
        return p;
    }

    private static void solidTile1(Ppu p, int columns) {
        for (int i = 0; i < columns; i++) vramWrite(p, i, 0x01, 0x00);
        vramWrite(p, 0x1000 + 16, 0xFF, 0x00);                 // tile 1, row 0: colour 1
    }

    // ------------------------------------------------------------------ registers

    @Test void oamRoundTripThroughTheLowAndHighTables() {
        Ppu p = new Ppu();
        p.write(0x02, 0x00); p.write(0x03, 0x00);
        for (int v : new int[]{0x11, 0x22, 0x33, 0x44}) p.write(0x04, v);
        p.write(0x02, 0x00); p.write(0x03, 0x00);
        assertEquals(0x11, p.read(0x38)); assertEquals(0x22, p.read(0x38));
        assertEquals(0x33, p.read(0x38)); assertEquals(0x44, p.read(0x38));
        p.write(0x02, 0x00); p.write(0x03, 0x01);                // high table
        p.write(0x04, 0xA5);
        p.write(0x02, 0x00); p.write(0x03, 0x01);
        assertEquals(0xA5, p.read(0x38));
    }

    @Test void cgramReadBackReturnsLowThenHighByteAndAdvances() {
        Ppu p = new Ppu();
        colour(p, 5, 0x7ABC); colour(p, 6, 0x1234);
        p.write(0x21, 5);
        assertEquals(0xBC, p.read(0x3B));
        assertEquals(0x7A, p.read(0x3B));
        assertEquals(0x34, p.read(0x3B));                        // the next colour
    }

    @Test void multiplicationResultRegistersUseMode7A() {
        Ppu p = new Ppu();
        p.write(0x1B, 0x00); p.write(0x1B, 0x01);                // M7A = $0100
        p.write(0x1C, 0x03);                                     // x 3 (signed byte)
        assertEquals(0x00, p.read(0x34)); assertEquals(0x03, p.read(0x35)); assertEquals(0x00, p.read(0x36));
        p.write(0x1C, 0xFF);                                     // x -1
        assertEquals(0x00, p.read(0x34)); assertEquals(0xFF, p.read(0x35)); assertEquals(0xFF, p.read(0x36));
    }

    @Test void vramReadBackUsesThePrefetchLatch() {
        Ppu p = new Ppu();
        vramWrite(p, 0x0100, 0x34, 0x12);
        p.write(0x15, 0x80); p.write(0x16, 0x00); p.write(0x17, 0x01);        // VMADD = $0100: loads the latch
        assertEquals(0x34, p.read(0x39));
        assertEquals(0x12, p.read(0x3A));
    }

    @Test void statusRegistersReportVersionAndSpriteLimits() {
        Ppu p = new Ppu();
        assertEquals(0x01, p.read(0x3E) & 0x3F);
        assertEquals(0x03, p.read(0x3F) & 0x7F);
        assertEquals(0, p.read(0x00));                           // write-only registers read as 0
    }

    @Test void debugDumpsDescribeTheState() {
        Ppu p = bg1();
        solidTile1(p, 2);
        p.renderLine(0);
        assertTrue(p.dump().contains("inidisp=0F"));
        assertTrue(p.dumpMap(0).startsWith("map BG1"));
        assertTrue(p.dumpScroll().startsWith("line:"));
    }

    @Test void mosaicAndObjectSizeRegistersAreAccepted() {
        Ppu p = bg1();
        solidTile1(p, 4);
        p.write(0x06, 0x31);                                     // 4x4 mosaic on BG1
        p.write(0x01, 0x60);                                     // OBSEL: sprite size 3
        p.write(0x0C, 0x21);                                     // BG3/BG4 character bases
        p.renderLine(0);
        assertEquals(RED, p.frame[0]);
    }

    // ------------------------------------------------------------------ sprites

    private static void sprite(Ppu p, int index, int x, int y, int tile, int attr) {
        p.write(0x02, (index * 2) & 0xFF); p.write(0x03, (index * 2) >> 8 & 1);
        p.write(0x04, x); p.write(0x04, y); p.write(0x04, tile); p.write(0x04, attr);
    }

    private static Ppu spritePpu() {
        Ppu p = new Ppu();
        p.write(0x00, 0x0F); p.write(0x2C, 0x10);               // objects on the main screen
        colour(p, 129, 0x03E0);                                   // sprite palette 0, colour 1 = green
        return p;
    }

    private static int at(Ppu p, int line, int x) { return p.frame[line * 256 + x]; }

    @Test void spriteIsDrawnWithItsPaletteColourAtItsPosition() {
        Ppu p = spritePpu();
        vramWrite(p, 0, 0xFF, 0x00);                             // tile 0 row 0: colour 1
        sprite(p, 0, 10, 100, 0, 0x00);
        p.renderLine(100);
        assertEquals(GREEN, at(p, 100, 10));
        assertEquals(GREEN, at(p, 100, 17));
        assertEquals(BLACK, at(p, 100, 9));
        assertEquals(BLACK, at(p, 100, 18));
        p.renderLine(101);
        assertEquals(BLACK, at(p, 101, 10));                     // row 1 of the tile is empty
    }

    @Test void horizontalAndVerticalFlip() {
        Ppu p = spritePpu();
        vramWrite(p, 0, 0x80, 0x00);                             // row 0: only the leftmost pixel
        vramWrite(p, 7, 0xFF, 0x00);                             // row 7: all pixels
        sprite(p, 0, 10, 100, 0, 0x40);                          // hflip
        p.renderLine(100);
        assertEquals(GREEN, at(p, 100, 17));
        assertEquals(BLACK, at(p, 100, 10));
        sprite(p, 0, 10, 100, 0, 0x80);                          // vflip: line 0 of the sprite shows row 7
        p.renderLine(100);
        assertEquals(GREEN, at(p, 100, 10));
        assertEquals(GREEN, at(p, 100, 17));
    }

    @Test void largeSpritesUseTheSecondCharacterToTheRight() {
        Ppu p = spritePpu();
        vramWrite(p, 16, 0xFF, 0x00);                            // tile 1 row 0
        sprite(p, 0, 10, 100, 0, 0x00);
        p.write(0x02, 0x00); p.write(0x03, 0x01); p.write(0x04, 0x02);   // high table: sprite 0 is large (16x16)
        p.renderLine(100);
        assertEquals(BLACK, at(p, 100, 10));
        assertEquals(GREEN, at(p, 100, 18));
        assertEquals(GREEN, at(p, 100, 25));
        assertEquals(BLACK, at(p, 100, 26));
    }

    @Test void spritesCanBePartlyOffTheLeftEdge() {
        Ppu p = spritePpu();
        vramWrite(p, 0, 0xFF, 0x00);
        sprite(p, 0, 0xFC, 100, 0, 0x00);                        // x = -4 (9-bit sign comes from the high table)
        p.write(0x02, 0x00); p.write(0x03, 0x01); p.write(0x04, 0x01);
        p.renderLine(100);
        assertEquals(GREEN, at(p, 100, 0));
        assertEquals(GREEN, at(p, 100, 3));
        assertEquals(BLACK, at(p, 100, 4));
    }

    @Test void thirtyThreeSpritesOnALineSetTheRangeOverFlag() {
        Ppu p = spritePpu();
        for (int i = 0; i < 33; i++) sprite(p, i, 8 * i, 100, 0, 0x00);
        p.renderLine(100);
        assertEquals(0x40, p.read(0x3E) & 0x40);
        Ppu q = spritePpu();
        for (int i = 0; i < 32; i++) sprite(q, i, 8 * i, 100, 0, 0x00);
        q.renderLine(100);
        assertEquals(0, q.read(0x3E) & 0x40);
    }

    @Test void spritePaletteRowsAndPriorityAgainstTheBackground() {
        Ppu p = spritePpu();
        p.write(0x00, 0x0F); p.write(0x05, 0x01); p.write(0x07, 0x00); p.write(0x0B, 0x01); p.write(0x2C, 0x11);
        colour(p, 1, 0x001F);
        p.write(0x0E, 0x9B); p.write(0x0E, 0x03);                 // BG1 vertical scroll -101: line 100 shows tile row 0
        solidTile1(p, 4);                                         // BG1 red across x 0..31
        vramWrite(p, 0x4000, 0xFF, 0x00);                         // sprite tile 0 in the object name table at word $4000
        p.write(0x01, 0x02);                                      // OBSEL: name base 2 (word $4000)
        colour(p, 128 + 16 + 1, 0x7C00);                          // palette 1, colour 1 = blue
        sprite(p, 0, 8, 100, 0, 0x02 | 0x20);                     // palette 1, priority 2 (above BG1 priority 0 tiles)
        p.renderLine(100);
        assertEquals(0xFF0000FF, at(p, 100, 8));
        assertEquals(RED, at(p, 100, 20));
    }

    // ------------------------------------------------------------------ windows

    private static Ppu windowed(int sel, int log, int w1l, int w1r, int w2l, int w2r) {
        Ppu p = bg1();
        solidTile1(p, 4);
        p.write(0x23, sel); p.write(0x2A, log);
        p.write(0x26, w1l); p.write(0x27, w1r); p.write(0x28, w2l); p.write(0x29, w2r);
        p.write(0x2E, 0x01);                                      // window applies to BG1 on the main screen
        p.renderLine(0);
        return p;
    }

    private static boolean shown(Ppu p, int x) { return p.frame[x] == RED; }

    @Test void windowOneHidesItsRangeAndInversionHidesTheRest() {
        Ppu p = windowed(0x02, 0, 8, 15, 0, 0);
        for (int x = 0; x < 32; x++) assertEquals(!(x >= 8 && x <= 15), shown(p, x), "x=" + x);
        Ppu q = windowed(0x03, 0, 8, 15, 0, 0);
        for (int x = 0; x < 32; x++) assertEquals(x >= 8 && x <= 15, shown(q, x), "inverted x=" + x);
    }

    @Test void windowTwoAlone() {
        Ppu p = windowed(0x08, 0, 0, 0, 12, 19);
        for (int x = 0; x < 32; x++) assertEquals(!(x >= 12 && x <= 19), shown(p, x), "x=" + x);
    }

    @Test void twoWindowsCombineWithOrAndXorXnor() {
        // window 1 = 8..15, window 2 = 12..19; "hidden" is the combined mask
        Ppu or = windowed(0x0A, 0, 8, 15, 12, 19), and = windowed(0x0A, 1, 8, 15, 12, 19);
        Ppu xor = windowed(0x0A, 2, 8, 15, 12, 19), xnor = windowed(0x0A, 3, 8, 15, 12, 19);
        for (int x = 0; x < 32; x++) {
            boolean a = x >= 8 && x <= 15, b = x >= 12 && x <= 19;
            assertEquals(!(a | b), shown(or, x), "OR x=" + x);
            assertEquals(!(a & b), shown(and, x), "AND x=" + x);
            assertEquals(!(a ^ b), shown(xor, x), "XOR x=" + x);
            assertEquals(a ^ b, shown(xnor, x), "XNOR x=" + x);
        }
    }

    // ------------------------------------------------------------------ colour math

    private static Ppu mathPpu(int cgadsub, int fixedByte) {
        Ppu p = bg1();
        colour(p, 1, 0x03E0);                                     // BG1 colour = green
        solidTile1(p, 4);
        p.write(0x30, 0x00);                                      // always apply, fixed colour
        p.write(0x31, cgadsub);
        p.write(0x32, fixedByte);
        return p;
    }

    @Test void fixedColourIsAddedSubtractedAndHalved() {
        Ppu add = mathPpu(0x01, 0x20 | 31);                       // BG1 + red 31
        add.renderLine(0);
        assertEquals(rgb(31, 31, 0), add.frame[0]);
        Ppu sub = mathPpu(0x81, 0x40 | 10);                       // BG1 - green 10
        sub.renderLine(0);
        assertEquals(rgb(0, 21, 0), sub.frame[0]);
        Ppu half = mathPpu(0x41, 0x20 | 31);                      // (BG1 + red) / 2
        half.renderLine(0);
        assertEquals(rgb(15, 15, 0), half.frame[0]);
    }

    @Test void mathOnlyAffectsTheLayersSelectedInCgadsub() {
        Ppu p = mathPpu(0x20, 0x20 | 31);                         // only the backdrop takes part
        p.renderLine(0);
        assertEquals(rgb(0, 31, 0), p.frame[0]);                  // BG1 untouched
        assertEquals(rgb(31, 0, 0), p.frame[100]);                // backdrop + red
    }

    @Test void subScreenLayersAreBlendedInsteadOfTheFixedColour() {
        Ppu p = bg1();
        colour(p, 1, 0x03E0); colour(p, 2, 0x7C00);               // green, blue
        vramWrite(p, 0, 0x01, 0x00);                              // BG1: tile 1 in columns 0 and 1
        vramWrite(p, 1, 0x01, 0x00);
        vramWrite(p, 0x1000 + 16, 0xFF, 0x00);
        p.write(0x08, 0x04);                                      // BG2 map at word $0400
        p.write(0x0B, 0x21);                                      // BG1 chars $1000, BG2 chars $2000
        vramWrite(p, 0x0400, 0x02, 0x00);                         // BG2: tile 2 in column 0 only
        vramWrite(p, 0x2000 + 32, 0x00, 0xFF);                    // tile 2 row 0: colour 2 (plane 1)
        p.write(0x10, 0xFF); p.write(0x10, 0x03);                 // BG2 vertical scroll -1 like BG1
        p.write(0x2D, 0x02);                                      // sub screen: BG2
        p.write(0x30, 0x02);                                      // blend with the sub screen
        p.write(0x31, 0x01);                                      // add on BG1
        p.write(0x32, 0x20 | 31);                                 // fixed colour red (used where the sub screen is empty)
        p.renderLine(0);
        assertEquals(rgb(0, 31, 31), p.frame[0]);                 // BG1 green + BG2 blue
        assertEquals(rgb(31, 31, 0), p.frame[8]);                 // sub screen empty: the fixed colour
    }

    // ------------------------------------------------------------------ other background modes

    @Test void modes2And4ShowBg1WithItsBitDepth() {
        for (int mode : new int[]{2, 3, 4}) {
            Ppu p = new Ppu();
            p.write(0x00, 0x0F); p.write(0x05, mode); p.write(0x07, 0x00); p.write(0x0B, 0x01); p.write(0x2C, 0x01);
            colour(p, 1, 0x001F);
            vramWrite(p, 0, 0x01, 0x00);
            int tileWords = mode == 2 ? 16 : 32;                  // 4 bpp tiles are 16 words, 8 bpp 32
            vramWrite(p, 0x1000 + tileWords, 0xFF, 0x00);
            p.write(0x0E, 0xFF); p.write(0x0E, 0x03);
            p.renderLine(0);
            assertEquals(RED, p.frame[0], "mode " + mode);
            assertEquals(BLACK, p.frame[8], "mode " + mode);
        }
    }

    @Test void modes2To4DrawTheSecondBackground() {
        for (int mode : new int[]{2, 3, 4}) {
            Ppu p = new Ppu();
            p.write(0x00, 0x0F); p.write(0x05, mode); p.write(0x08, 0x04); p.write(0x0B, 0x20); p.write(0x2C, 0x02);
            colour(p, 1, 0x7C00);
            vramWrite(p, 0x0400, 0x01, 0x00);
            vramWrite(p, 0x2000 + 16, 0xFF, 0x00);                // 4 bpp in modes 2 and 3, 2 bpp (8 words) in mode 4
            vramWrite(p, 0x2000 + 8, 0xFF, 0x00);
            p.write(0x10, 0xFF); p.write(0x10, 0x03);
            p.renderLine(0);
            assertEquals(0xFF0000FF, p.frame[0], "mode " + mode);
        }
    }

    @Test void mode6IsHighResolution() {
        Ppu p = new Ppu();
        p.write(0x00, 0x0F); p.write(0x05, 0x06); p.write(0x2C, 0x01);
        for (int line = 0; line < 224; line++) p.renderLine(line);
        p.vblankStart();
        assertEquals(512, p.outputWidth());
    }

    @Test void mode7WithAFullMatrixAndFlipsRenders() {
        Ppu p = new Ppu();
        p.write(0x00, 0x0F); p.write(0x05, 0x07); p.write(0x2C, 0x01);
        p.write(0x1A, 0xC0);                                      // M7SEL: flip both axes, wrap
        for (int reg : new int[]{0x1B, 0x1C, 0x1D, 0x1E}) { p.write(reg, 0x00); p.write(reg, 0x01); }
        p.write(0x1F, 0x80); p.write(0x1F, 0x00);                 // centre X = 128
        p.write(0x20, 0x80); p.write(0x20, 0x00);                 // centre Y = 128
        p.write(0x1B, 0x00); p.write(0x1B, 0x01);
        for (int line = 0; line < 224; line++) p.renderLine(line);
        assertNotNull(p.outputPixels());
    }
}
