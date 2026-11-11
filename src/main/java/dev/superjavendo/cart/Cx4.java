package dev.superjavendo.cart;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;

import java.io.IOException;
import java.util.function.IntUnaryOperator;

/**
 * Capcom CX4 (Mega Man X2 / X3) as a high-level emulation.
 *
 * <p>The chip appears in banks $00-$3F / $80-$BF at $6000-$7FFF: $6000-$6BFF is its 3 KB of RAM (which games use as an
 * output buffer for sprites, bitmaps and wireframes) and $7F00-$7FFF are registers. Writing a command number to
 * $7F4F runs it; writing $7F47 starts a DMA-like copy from the cartridge into the chip RAM. The 16 24-bit "registers" of
 * the CPU-visible math commands live at $7F80.
 *
 * <p>Commands are implemented from their documented behaviour and checked against snes9x and bsnes
 * ({@code tools/testmaster}). Some of them are defined by floating point arithmetic on the original (a square root, an
 * arc tangent, a 0.99 fudge factor): we do the same in double precision.
 */
public final class Cx4 implements Coprocessor {
    private final byte[] ram = new byte[0x0C00];
    private final byte[] reg = new byte[0x100];
    private final IntUnaryOperator bus;          // 24 bit address -> byte, the cartridge's view of the address space

    // scratch registers of the math commands (the sine helper clobbers r0, and games can observe that)
    private int r0, r1, r2, r3, r4, r5;

    // wireframe working values
    private int wfX, wfY, wfZ, wfX2, wfY2, wfDist, wfScale;

    private static final double PI = Math.PI;
    /** 24 bit sine, 128 entries for the first half of a half-wave followed by the negated ones (see {@link #sin}). */
    private static final int[] SIN24 = new int[256];
    /** 16 bit sine / cosine over a 512 step circle, used by Trapezoid and Scale/Rotate. */
    private static final int[] SIN16 = new int[512], COS16 = new int[512];
    /** Values the chip holds in its constant ROM, loaded by the Immediate Register commands. */
    private static final int[] IMMEDIATE = {
        0, 0, 0, 255, 255, 255, 0, 255, 0, 0, 0, 255, 255, 255, 0, 0, 255, 255, 0, 0, 128, 255, 255, 127, 0, 128, 0, 255,
        127, 0, 255, 127, 255, 127, 255, 255, 0, 0, 1, 255, 255, 254, 0, 1, 0, 255, 254, 0 };

    static {
        for (int i = 0; i < 128; i++) {
            double s = Math.sin(2 * PI * i / 512);
            SIN24[i] = (int) (65536 * s);
            SIN24[i + 128] = (int) Math.floor(-65536 * s) & 0xFFFFFF;
        }
        SIN24[128 + 71] = 16727070;          // measured on the oracle: two entries differ by one from the formula
        SIN24[128 + 111] = 16713101;
        for (int i = 0; i < 512; i++) {
            SIN16[i] = Math.min(32767, (int) (32768 * Math.sin(2 * PI * i / 512)));
            COS16[i] = Math.min(32767, (int) (32768 * Math.cos(2 * PI * i / 512)));
        }
        SIN16[384] = -32767;
        COS16[256] = -32767;
    }

    public Cx4(IntUnaryOperator bus) { this.bus = bus; }

    // ------------------------------------------------------------------ bus interface

    private static boolean window(int bank, int off) { return (bank & 0x7F) < 0x40 && off >= 0x6000 && off < 0x8000; }

    @Override public int read(int bank, int off) {
        if (!window(bank & 0xFF, off)) return -1;
        int a = off & 0x1FFF;
        if (a < 0x0C00) return ram[a] & 0xFF;
        if (a >= 0x1F00) return reg[a & 0xFF] & 0xFF;
        return -1;                                           // unmapped: open bus
    }

    @Override public boolean write(int bank, int off, int value) {
        if (!window(bank & 0xFF, off)) return false;
        store(off & 0x1FFF, value & 0xFF);
        return true;
    }

    private void store(int a, int v) {
        a &= 0x1FFF;
        if (a < 0x0C00) { ram[a] = (byte) v; return; }
        if (a < 0x1F00) return;
        reg[a & 0xFF] = (byte) v;
        if (a == 0x1F47) { transfer(); return; }
        if (a == 0x1F4F) command(v);
    }

    private int rd(int a) { return read(0, 0x6000 | (a & 0x1FFF)) & 0xFF; }
    private int rdw(int a) { return rd(a) | rd(a + 1) << 8; }
    private int rdl(int a) { return rd(a) | rd(a + 1) << 8 | rd(a + 2) << 16; }
    private void wr(int a, int v) { store(a, v & 0xFF); }
    private void wrw(int a, int v) { wr(a, v); wr(a + 1, v >> 8); }
    private int bus(int addr) { return bus.applyAsInt(addr & 0xFFFFFF) & 0xFF; }
    private int busw(int addr) { return bus(addr) << 8 | bus(addr + 1); }      // big endian word, as the wireframe data is

    /** Copies bytes from the cartridge into the chip (source $7F40, count $7F43, destination $7F45). */
    private void transfer() {
        int src = (reg[0x40] & 0xFF) | (reg[0x41] & 0xFF) << 8 | (reg[0x42] & 0xFF) << 16;
        int count = (reg[0x43] & 0xFF) | (reg[0x44] & 0xFF) << 8;
        int dest = (reg[0x45] & 0xFF) | (reg[0x46] & 0xFF) << 8;
        for (int i = 0; i < count; i++) store(dest++, bus(src++));
    }

    // ------------------------------------------------------------------ commands

    private void command(int op) {
        if ((reg[0x4D] & 0xFF) == 0x0E && (op & 0xC3) == 0) { reg[0x80] = (byte) (op >> 2); return; }   // self test
        switch (op) {
            case 0x00: sprite(); break;
            case 0x01: java.util.Arrays.fill(ram, 0x300, 0x300 + 2304, (byte) 0); drawWireframe(); break;
            case 0x05: propulsion(); break;
            case 0x0D: vectorLength(); break;
            case 0x10: triangle(true); break;
            case 0x13: triangle(false); break;
            case 0x15: pythagoras(); break;
            case 0x1F: angle(); break;
            case 0x22: trapezoid(); break;
            case 0x25: { r0 = ldr(0); r1 = ldr(1); long p = mulFull(r0, r1); str(0, (int) (p & 0xFFFFFF)); str(1, (int) ((p >> 24) & 0xFFFFFF)); break; }
            case 0x2D: transformCoords(); break;
            case 0x40: { r0 = 0; for (int i = 0; i < 0x800; i++) r0 += ram[i] & 0xFF; str(0, r0); break; }
            case 0x54: { r0 = ldr(0); long p = mulFull(r0, r0); str(1, (int) (p & 0xFFFFFF)); str(2, (int) ((p >> 24) & 0xFFFFFF)); break; }
            case 0x5C: str(0, 0); immediate(0); break;
            case 0x89: str(0, 0x054336); str(1, 0xFFFFFF); break;
            default:
                if (op >= 0x5E && op <= 0x7C && (op & 1) == 0) immediate((op - 0x5E) / 2 * 3);
        }
    }

    private int ldr(int r) { int a = 0x80 + r * 3; return (reg[a] & 0xFF) | (reg[a + 1] & 0xFF) << 8 | (reg[a + 2] & 0xFF) << 16; }
    private void str(int r, int v) { int a = 0x80 + r * 3; reg[a] = (byte) v; reg[a + 1] = (byte) (v >> 8); reg[a + 2] = (byte) (v >> 16); }

    private static long mulFull(int x, int y) { return (long) (x << 8 >> 8) * (y << 8 >> 8); }     // 24 bit signed operands

    /** Sine over a full circle of 512 steps, folded into a table of 128 values; clobbers r0 like the chip's routine. */
    private int sin(int rx) {
        r0 = rx & 0x1FF;
        if ((r0 & 0x100) != 0) r0 ^= 0x1FF;
        if ((r0 & 0x080) != 0) r0 ^= 0x0FF;
        return (rx & 0x100) != 0 ? SIN24[r0 + 0x80] : SIN24[r0];
    }

    private int cos(int rx) { return sin(rx + 0x80); }

    /** Immediate register commands: store constants from the chip's ROM into RAM at the address held in register 0. */
    private void immediate(int start) {
        r0 = ldr(0);
        for (int i = start; i < 48; i++) {
            if ((r0 & 0xFFF) < 0xC00) ram[r0 & 0xFFF] = (byte) IMMEDIATE[i];
            r0++;
        }
        str(0, r0);
    }

    /** Triangle: r2, r3 = cos, sin of the angle in r0 times r1 (signed 16 bit for $10, full 24 bit for $13). */
    private void triangle(boolean narrow) {
        r0 = ldr(0);
        r1 = ldr(1);
        r4 = r0 & 0x1FF;
        if (narrow) r1 = (r1 & 0x8000) != 0 ? r1 | ~0x7FFF : r1 & 0x7FFF;
        long p = mulFull(cos(r4), r1);
        r5 = (int) (p & 0xFFFFFF); r2 = (int) ((p >> 24) & 0xFFFFFF);
        if (narrow) { r5 = (r5 >> 16) & 0xFF; r2 = (r2 << 8) + r5; } else { r5 = (r5 >> 8) & 0xFFFF; r2 = (r2 << 16) + r5; }
        p = mulFull(sin(r4), r1);
        r5 = (int) (p & 0xFFFFFF); r3 = (int) ((p >> 24) & 0xFFFFFF);
        if (narrow) { r5 = (r5 >> 16) & 0xFF; r3 = (r3 << 8) + r5; } else { r5 = (r5 >> 8) & 0xFFFF; r3 = (r3 << 16) + r5; }
        str(0, r0); str(1, r1); str(2, r2); str(3, r3); str(4, r4); str(5, r5);
    }

    private void propulsion() {
        int t = 0x10000;
        int d = rdw(0x1F83);
        if (d != 0) t = ((t / d) * rdw(0x1F81)) >> 8;
        wrw(0x1F80, t);
    }

    private void vectorLength() {
        int x = (short) rdw(0x1F80), y = (short) rdw(0x1F83), dist = (short) rdw(0x1F86);
        double k = (double) dist / Math.sqrt((double) y * y + (double) x * x);
        wrw(0x1F89, (short) (int) ((double) x * k * 0.98));
        wrw(0x1F8C, (short) (int) ((double) y * k * 0.99));
    }

    private void pythagoras() {
        int x = (short) rdw(0x1F80), y = (short) rdw(0x1F83);
        wrw(0x1F80, (short) (int) Math.sqrt((double) x * x + (double) y * y));
    }

    /** Angle of the vector (x, y) in 512ths of a turn. */
    private void angle() {
        int x = (short) rdw(0x1F80), y = (short) rdw(0x1F83), a;
        if (x == 0) a = y > 0 ? 0x080 : 0x180;
        else {
            a = (short) (int) (Math.atan((double) y / (double) x) / (PI * 2) * 512);
            if (x < 0) a += 0x100;
            a &= 0x1FF;
        }
        wrw(0x1F86, a);
    }

    private static int tan(int a) { return COS16[a] != 0 ? (SIN16[a] << 16) / COS16[a] : 0x80000000; }

    /** Trapezoid: left and right edge of each of 225 scanlines, for HDMA window tables at $6800 / $6900. */
    private void trapezoid() {
        int a1 = rdw(0x1F8C) & 0x1FF, a2 = rdw(0x1F8F) & 0x1FF;
        int t1 = tan(a1), t2 = tan(a2);
        int y = (short) (rdw(0x1F83) - rdw(0x1F89));
        for (int j = 0; j < 225; j++, y = (short) (y + 1)) {
            int left, right;
            if (y >= 0) {
                left = (short) (((t1 * y) >> 16) - rdw(0x1F80) + rdw(0x1F86));
                right = (short) (((t2 * y) >> 16) - rdw(0x1F80) + rdw(0x1F86) + rdw(0x1F93));
                if (left < 0 && right < 0) { left = 1; right = 0; }
                else if (left < 0) left = 0;
                else if (right < 0) right = 0;
                if (left > 255 && right > 255) { left = 255; right = 254; }
                else if (left > 255) left = 255;
                else if (right > 255) right = 255;
            } else { left = 1; right = 0; }
            ram[j + 0x800] = (byte) left;
            ram[j + 0x900] = (byte) right;
        }
    }

    // ------------------------------------------------------------------ wireframe

    /** Rotates (wfX, wfY, wfZ) around X, Y and Z by wfX2, wfY2, wfDist (1/128 turns) and scales it. */
    private void transformWireframe2() {
        double x = wfX, y = wfY, z = wfZ;
        double t = -(double) wfX2 * PI * 2 / 128;
        double y2 = y * Math.cos(t) - z * Math.sin(t);
        double z2 = y * Math.sin(t) + z * Math.cos(t);
        t = -(double) wfY2 * PI * 2 / 128;
        double x2 = x * Math.cos(t) + z2 * Math.sin(t);
        z = x * -Math.sin(t) + z2 * Math.cos(t);
        t = -(double) wfDist * PI * 2 / 128;
        x = x2 * Math.cos(t) - y2 * Math.sin(t);
        y = x2 * Math.sin(t) + y2 * Math.cos(t);
        wfX = (short) (int) (x * wfScale / 0x100);
        wfY = (short) (int) (y * wfScale / 0x100);
    }

    /** Perspective variant used by "Transform Lines": the point is moved by $95 along Z and divided by its depth. */
    private void transformWireframe() {
        double x = wfX, y = wfY, z = (double) wfZ - 0x95;
        double t = -(double) wfX2 * PI * 2 / 128;
        double y2 = y * Math.cos(t) - z * Math.sin(t);
        double z2 = y * Math.sin(t) + z * Math.cos(t);
        t = -(double) wfY2 * PI * 2 / 128;
        double x2 = x * Math.cos(t) + z2 * Math.sin(t);
        z = x * -Math.sin(t) + z2 * Math.cos(t);
        t = -(double) wfDist * PI * 2 / 128;
        x = x2 * Math.cos(t) - y2 * Math.sin(t);
        y = x2 * Math.sin(t) + y2 * Math.cos(t);
        wfX = (short) (int) (x * wfScale / (0x90 * (z + 0x95)) * 0x95);
        wfY = (short) (int) (y * wfScale / (0x90 * (z + 0x95)) * 0x95);
    }

    /** Line set-up: turns the end points in (wfX, wfY) / (wfX2, wfY2) into a step vector (256 = one pixel) and a length. */
    private void calcLine() {
        wfX = (short) (wfX2 - wfX);
        wfY = (short) (wfY2 - wfY);
        if (Math.abs(wfX) > Math.abs(wfY)) {
            wfDist = (short) (Math.abs(wfX) + 1);
            wfY = (short) ((256 * wfY) / Math.abs(wfX));
            wfX = wfX < 0 ? -256 : 256;
        } else if (wfY != 0) {
            wfDist = (short) (Math.abs(wfY) + 1);
            wfX = (short) ((256 * wfX) / Math.abs(wfY));
            wfY = wfY < 0 ? -256 : 256;
        } else wfDist = 0;
    }

    private void drawWireframe() {
        int line = rdl(0x1F80);
        for (int i = ram[0x295] & 0xFF; i > 0; i--, line += 5) {
            int p1, p2;
            if (bus(line) == 0xFF && bus(line + 1) == 0xFF) {                  // "same start as the previous line"
                int tmp = line - 5;
                while (bus(tmp + 2) == 0xFF && bus(tmp + 3) == 0xFF && tmp + 2 >= 0) tmp -= 5;
                p1 = rd(0x1F82) << 16 | bus(tmp + 2) << 8 | bus(tmp + 3);
            } else p1 = rd(0x1F82) << 16 | bus(line) << 8 | bus(line + 1);
            p2 = rd(0x1F82) << 16 | bus(line + 2) << 8 | bus(line + 3);
            drawLine((short) busw(p1), (short) busw(p1 + 2), (short) busw(p1 + 4),
                     (short) busw(p2), (short) busw(p2 + 2), (short) busw(p2 + 4), bus(line + 4));
        }
    }

    private void drawLine(int x1, int y1, int z1, int x2, int y2, int z2, int color) {
        wfX = (short) x1; wfY = (short) y1; wfZ = z1;
        wfScale = rd(0x1F90); wfX2 = rd(0x1F86); wfY2 = rd(0x1F87); wfDist = rd(0x1F88);
        transformWireframe2();
        x1 = (wfX + 48) << 8; y1 = (wfY + 48) << 8;
        wfX = (short) x2; wfY = (short) y2; wfZ = z2;
        transformWireframe2();
        x2 = (wfX + 48) << 8; y2 = (wfY + 48) << 8;
        wfX = (short) (x1 >> 8); wfY = (short) (y1 >> 8); wfX2 = (short) (x2 >> 8); wfY2 = (short) (y2 >> 8);
        calcLine();
        x2 = (short) wfX; y2 = (short) wfY;
        for (int i = wfDist != 0 ? wfDist : (short) 1; i > 0; i--) {
            if (x1 > 0xFF && y1 > 0xFF && x1 < 0x6000 && y1 < 0x6000) {
                int addr = (((y1 >> 8) >> 3) << 8) - (((y1 >> 8) >> 3) << 6) + (((x1 >> 8) >> 3) << 4) + ((y1 >> 8) & 7) * 2;
                int bit = 0x80 >> ((x1 >> 8) & 7);
                ram[addr + 0x300] &= ~bit;
                ram[addr + 0x301] &= ~bit;
                if ((color & 1) != 0) ram[addr + 0x300] |= bit;
                if ((color & 2) != 0) ram[addr + 0x301] |= bit;
            }
            x1 += x2;
            y1 += y2;
        }
    }

    private void transformCoords() {
        wfX = (short) rdw(0x1F81); wfY = (short) rdw(0x1F84); wfZ = (short) rdw(0x1F87);
        wfX2 = rd(0x1F89); wfY2 = rd(0x1F8A); wfDist = rd(0x1F8B); wfScale = (short) rdw(0x1F90);
        transformWireframe2();
        wrw(0x1F80, wfX);
        wrw(0x1F83, wfY);
    }

    // ------------------------------------------------------------------ sprite commands ($7F4D selects the sub-command)

    private void sprite() {
        switch (reg[0x4D] & 0xFF) {
            case 0x00: buildOam(); break;
            case 0x03: scaleRotate(0); break;
            case 0x05: transformLines(); break;
            case 0x07: scaleRotate(64); break;
            case 0x08: drawWireframe(); break;
            case 0x0B: disintegrate(); break;
            case 0x0C: bitplaneWave(); break;
            default:
        }
    }

    /** Builds the OAM table from a list of meta-sprites. */
    private void buildOam() {
        int oamPtr = (ram[0x626] & 0xFF) << 2;
        for (int i = 0x1FD; i > oamPtr && i >= 0; i -= 4) ram[i] = (byte) 0xE0;
        int globalX = rdw(0x621), globalY = rdw(0x623);
        int oamPtr2 = 0x200 + ((ram[0x626] & 0xFF) >> 2);
        if (ram[0x620] == 0) return;
        int sprCount = 128 - (ram[0x626] & 0xFF);
        int offset = ((ram[0x626] & 3)) * 2;
        int src = 0x220;
        for (int i = ram[0x620] & 0xFF; i > 0 && sprCount > 0; i--, src += 16) {
            int sprX = (short) (rdw(src) - globalX), sprY = (short) (rdw(src + 2) - globalY);
            int name = ram[src + 5] & 0xFF;
            int attr = (ram[src + 4] | ram[src + 6]) & 0xFF;
            int addr = rdl(src + 7);
            if (bus(addr) != 0) {
                int n = bus(addr++);
                for (; n > 0 && sprCount > 0; n--, addr += 4) {
                    int x = (byte) bus(addr + 1);
                    if ((attr & 0x40) != 0) x = -x - ((bus(addr) & 0x20) != 0 ? 16 : 8);
                    x += sprX;
                    if (x >= -16 && x <= 272) {
                        int y = (byte) bus(addr + 2);
                        if ((attr & 0x80) != 0) y = -y - ((bus(addr) & 0x20) != 0 ? 16 : 8);
                        y += sprY;
                        if (y >= -16 && y <= 224) {
                            ram[oamPtr] = (byte) x;
                            ram[oamPtr + 1] = (byte) y;
                            ram[oamPtr + 2] = (byte) (name + bus(addr + 3));
                            ram[oamPtr + 3] = (byte) (attr ^ (bus(addr) & 0xC0));
                            ram[oamPtr2] &= ~(3 << offset);
                            if ((x & 0x100) != 0) ram[oamPtr2] |= 1 << offset;
                            if ((bus(addr) & 0x20) != 0) ram[oamPtr2] |= 2 << offset;
                            oamPtr += 4;
                            sprCount--;
                            offset = (offset + 2) & 6;
                            if (offset == 0) oamPtr2++;
                        }
                    }
                }
            } else if (sprCount > 0) {
                ram[oamPtr] = (byte) sprX;
                ram[oamPtr + 1] = (byte) sprY;
                ram[oamPtr + 2] = (byte) name;
                ram[oamPtr + 3] = (byte) attr;
                ram[oamPtr2] &= ~(3 << offset);
                ram[oamPtr2] |= ((sprX & 0x100) != 0 ? 3 : 2) << offset;
                oamPtr += 4;
                sprCount--;
                offset = (offset + 2) & 6;
                if (offset == 0) oamPtr2++;
            }
        }
    }

    /** Scale and rotate a 4 bit bitmap (at $6600, width/height in $7F89/$7F8C) into planar SNES tiles at $6000. */
    private void scaleRotate(int rowPadding) {
        int xs = rdw(0x1F8F), ys = rdw(0x1F92);
        if ((xs & 0x8000) != 0) xs = 0x7FFF;
        if ((ys & 0x8000) != 0) ys = 0x7FFF;
        int rot = rdw(0x1F80), a, b, c, d;
        if (rot == 0) { a = xs; b = 0; c = 0; d = ys; }
        else if (rot == 128) { a = 0; b = -ys; c = xs; d = 0; }
        else if (rot == 256) { a = -xs; b = 0; c = 0; d = -ys; }
        else if (rot == 384) { a = 0; b = ys; c = -xs; d = 0; }
        else {
            a = (COS16[rot & 0x1FF] * xs) >> 15;
            b = -((SIN16[rot & 0x1FF] * ys) >> 15);
            c = (SIN16[rot & 0x1FF] * xs) >> 15;
            d = (COS16[rot & 0x1FF] * ys) >> 15;
        }
        a = (short) a; b = (short) b; c = (short) c; d = (short) d;
        int w = rd(0x1F89) & ~7, h = rd(0x1F8C) & ~7;
        java.util.Arrays.fill(ram, 0, Math.min(ram.length, (w + rowPadding / 4) * h / 2), (byte) 0);
        int cx = (short) rdw(0x1F83), cy = (short) rdw(0x1F86);
        int lineX = (cx << 12) - cx * a - cx * b;
        int lineY = (cy << 12) - cy * c - cy * d;
        int out = 0, bit = 0x80;
        for (int y = 0; y < h; y++) {
            int x0 = lineX, y0 = lineY;
            for (int x = 0; x < w; x++) {
                int pixel;
                if (Integer.compareUnsigned(x0 >>> 12, w) >= 0 || Integer.compareUnsigned(y0 >>> 12, h) >= 0) pixel = 0;
                else {
                    int addr = (y0 >>> 12) * w + (x0 >>> 12);
                    pixel = rd(0x600 + (addr >> 1));
                    if ((addr & 1) != 0) pixel >>= 4;
                }
                if ((pixel & 1) != 0) ram[out] |= bit;
                if ((pixel & 2) != 0) ram[out + 1] |= bit;
                if ((pixel & 4) != 0) ram[out + 16] |= bit;
                if ((pixel & 8) != 0) ram[out + 17] |= bit;
                bit >>= 1;
                if (bit == 0) { bit = 0x80; out += 32; }
                x0 += a;
                y0 += c;
            }
            out += 2 + rowPadding;
            if ((out & 0x10) != 0) out &= ~0x10; else out -= w * 4 + rowPadding;
            lineX += b;
            lineY += d;
        }
    }

    /** Rotates the vertices stored at $6000 (16 bytes each) and builds the line list at $6600 from the pairs at $6B02. */
    private void transformLines() {
        wfX2 = rd(0x1F83); wfY2 = rd(0x1F86); wfDist = rd(0x1F89); wfScale = rd(0x1F8C);
        int ptr = 0;
        for (int i = rdw(0x1F80); i > 0; i--, ptr += 0x10) {
            wfX = (short) rdw(ptr + 1); wfY = (short) rdw(ptr + 5); wfZ = (short) rdw(ptr + 9);
            transformWireframe();
            wrw(ptr + 1, wfX + 0x80);
            wrw(ptr + 5, wfY + 0x50);
        }
        wrw(0x600, 23); wrw(0x602, 0x60); wrw(0x605, 0x40);
        wrw(0x608, 23); wrw(0x60A, 0x60); wrw(0x60D, 0x40);
        ptr = 0xB02;
        int ptr2 = 0;
        for (int i = rdw(0xB00); i > 0; i--, ptr += 2, ptr2 += 8) {
            wfX = (short) rdw((rd(ptr) << 4) + 1); wfY = (short) rdw((rd(ptr) << 4) + 5);
            wfX2 = (short) rdw((rd(ptr + 1) << 4) + 1); wfY2 = (short) rdw((rd(ptr + 1) << 4) + 5);
            calcLine();
            wrw(ptr2 + 0x600, wfDist != 0 ? wfDist : 1);
            wrw(ptr2 + 0x602, wfX);
            wrw(ptr2 + 0x605, wfY);
        }
    }

    /** "Disintegrate": scales a bitmap away from a centre point, producing planar tiles. */
    private void disintegrate() {
        int width = rd(0x1F89), height = rd(0x1F8C);
        int cx = rdw(0x1F80), cy = rdw(0x1F83);
        int scaleX = (short) rdw(0x1F86), scaleY = (short) rdw(0x1F8F);
        int startX = -cx * scaleX + (cx << 8), startY = -cy * scaleY + (cy << 8);
        int src = 0x600;
        for (int i = 0; i < (width * height) >> 1; i++) store(i, 0);
        int y = startY;
        for (int i = 0; i < height; i++, y += scaleY) {
            int x = startX;
            for (int j = 0; j < width; j++, x += scaleX) {
                if (Integer.compareUnsigned(x >>> 8, width) < 0 && Integer.compareUnsigned(y >>> 8, height) < 0
                        && (y >>> 8) * width + (x >>> 8) < 0x2000) {
                    int pixel = (j & 1) != 0 ? (ram[src] & 0xFF) >> 4 : ram[src] & 0xFF;
                    int index = (y >>> 11) * width * 4 + (x >>> 11) * 32 + ((y >>> 8) & 7) * 2;
                    int mask = 0x80 >> ((x >>> 8) & 7);
                    if ((pixel & 1) != 0) ram[index] |= mask;
                    if ((pixel & 2) != 0) ram[index + 1] |= mask;
                    if ((pixel & 4) != 0) ram[index + 16] |= mask;
                    if ((pixel & 8) != 0) ram[index + 17] |= mask;
                }
                if ((j & 1) != 0) src++;
            }
        }
    }

    /** "Bitplane wave": the ripple effect of the stage intros, applied to the two upper bitplanes of 8x8 tiles. */
    private void bitplaneWave() {
        int dest = 0, wave = rd(0x1F83);
        int mask1 = 0xC0C0, mask2 = 0x3F3F;
        for (int j = 0; j < 0x10; j++) {
            for (int half = 0; half < 2; half++) {
                do {
                    int height = -(byte) rd(wave + 0xB00) - 16;
                    for (int i = 0; i < 40; i++) {
                        int t = rdw(dest + (i / 8) * 512 + (i % 8) * 2) & mask2;
                        if (height >= 0) {
                            if (height < 8) t |= mask1 & rdw(0xA00 + half * 0x10 + height * 2);
                            else t |= mask1 & 0xFF00;
                        }
                        wrw(dest + (i / 8) * 512 + (i % 8) * 2, t);
                        height++;
                    }
                    wave = (wave + 1) & 0x7F;
                    mask1 = ((mask1 >> 2) | (mask1 << 6)) & 0xFFFF;
                    mask2 = ((mask2 >> 2) | (mask2 << 6)) & 0xFFFF;
                } while (mask1 != 0xC0C0);
                dest += 16;
            }
        }
    }

    // ------------------------------------------------------------------ save state

    @Override public void save(StateOut o) throws IOException {
        o.bytes(ram); o.bytes(reg);
        o.ints(new int[]{r0, r1, r2, r3, r4, r5});
    }

    @Override public void load(StateIn s) throws IOException {
        s.bytes(ram); s.bytes(reg);
        int[] v = new int[6];
        s.ints(v);
        r0 = v[0]; r1 = v[1]; r2 = v[2]; r3 = v[3]; r4 = v[4]; r5 = v[5];
    }
}
