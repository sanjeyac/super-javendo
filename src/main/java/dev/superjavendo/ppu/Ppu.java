package dev.superjavendo.ppu;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

import java.util.Arrays;

/**
 * Scanline PPU. Supports BG modes 0-4 and 7, sprites, windows, mosaic and colour math
 * (main + sub screen). Hi-res modes (5/6), offset-per-tile and interlace are not emulated.
 */
public final class Ppu implements Stateful {
    public static final int WIDTH = 256, HEIGHT = 224;
    /** ARGB framebuffer, WIDTH x HEIGHT. */
    public final int[] frame = new int[WIDTH * HEIGHT];

    private final byte[] vram = new byte[0x10000];
    private final int[] cgram = new int[256];
    private final byte[] oam = new byte[544];

    // registers
    private int inidisp, obsel, oamAddrReg, oamAddr, oamLatch, oamPriority;
    private int bgmode, mosaic;
    private final int[] bgsc = new int[4], bgChar = new int[4];
    private final int[] hofs = new int[4], vofs = new int[4];
    private int bgLatch, m7Latch;
    private int vmain, vmadd, vramLatch;
    private int m7sel, m7a, m7b, m7c, m7d, m7x, m7y, m7hofs, m7vofs, m7bLast;
    private int cgadd, cgLatch;
    private boolean cgHigh;
    private int w12sel, w34sel, wobjsel, wh0, wh1, wh2, wh3, wbglog, wobjlog;
    private int tm, ts, tmw, tsw, cgwsel, cgadsub, fixR, fixG, fixB, setini;
    private int ophct, opvct; // unused latch helpers
    private boolean rangeOver, timeOver;
    /** Debug: bit mask of layers (0-3 BG1-4, 4 OBJ) allowed to display. */
    public int debugMask = 0x1F;

    public final int[][] dbgScroll = new int[4][HEIGHT]; // per line: h0,h1,v0,v1
    public String dumpScroll() {
        StringBuilder sb = new StringBuilder("line: hofs1 hofs2 vofs1 vofs2 tm mode\n");
        int last = -1;
        for (int y = 0; y < HEIGHT; y++) {
            int sig = dbgScroll[0][y] * 31 + dbgScroll[1][y] * 17 + dbgScroll[2][y] * 7 + dbgScroll[3][y];
            if (sig != last) { sb.append(y).append(": ").append(dbgScroll[0][y]).append(' ').append(dbgScroll[1][y]).append(' ').append(dbgScroll[2][y]).append(' ').append(dbgScroll[3][y]).append('\n'); last = sig; }
        }
        return sb.toString();
    }

    public String dumpMap(int bg) {
        int sc = bgsc[bg]; boolean wide = (sc & 1) != 0, tall = (sc & 2) != 0;
        int base = (sc & 0xFC) << 8;
        StringBuilder sb = new StringBuilder("map BG" + (bg + 1) + " base=" + Integer.toHexString(base) + (wide ? " wide" : "") + (tall ? " tall" : "") + "\n");
        for (int ty = 0; ty < (tall ? 64 : 32); ty++) {
            for (int tx = 0; tx < (wide ? 64 : 32); tx++) {
                int screen = ((tx >> 5) & 1) + (((ty >> 5) & 1) * (wide ? 2 : 1));
                int e = vramWord(base + screen * 0x400 + (ty & 31) * 32 + (tx & 31));
                sb.append((e & 0x3FF) == 0 ? '.' : '#');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public String dump() {
        return String.format("inidisp=%02X mode=%02X mosaic=%02X tm=%02X ts=%02X tmw=%02X cgwsel=%02X cgadsub=%02X fix=%d,%d,%d obsel=%02X%n"
                + "bgsc=%02X,%02X,%02X,%02X char=%04X,%04X,%04X,%04X hofs=%d,%d,%d,%d vofs=%d,%d,%d,%d%n"
                + "w12=%02X w34=%02X wobj=%02X wh=%d,%d,%d,%d wlog=%02X,%02X setini=%02X vmain=%02X",
                inidisp, bgmode, mosaic, tm, ts, tmw, cgwsel, cgadsub, fixR, fixG, fixB, obsel,
                bgsc[0], bgsc[1], bgsc[2], bgsc[3], bgChar[0], bgChar[1], bgChar[2], bgChar[3],
                hofs[0], hofs[1], hofs[2], hofs[3], vofs[0], vofs[1], vofs[2], vofs[3],
                w12sel, w34sel, wobjsel, wh0, wh1, wh2, wh3, wbglog, wobjlog, setini, vmain);
    }

    // per-line work buffers
    private final int[][] bgCol = new int[4][WIDTH];
    private final int[][] bgPrio = new int[4][WIDTH];
    private final int[] objCol = new int[WIDTH], objPrio = new int[WIDTH];
    private final boolean[] objMath = new boolean[WIDTH];
    private final boolean[][] win = new boolean[6][WIDTH];
    private final int[] mainCol = new int[WIDTH], mainLay = new int[WIDTH];
    private final boolean[] mainObjMath = new boolean[WIDTH];
    private final int[] subCol = new int[WIDTH], subLay = new int[WIDTH];
    private final boolean[] subObjMath = new boolean[WIDTH];

    // ------------------------------------------------------------------ helpers

    private int vramWord(int wordAddr) {
        int i = (wordAddr << 1) & 0xFFFF;
        return (vram[i] & 0xFF) | ((vram[i + 1] & 0xFF) << 8);
    }

    private int remap() {
        int a = vmadd & 0x7FFF;
        switch ((vmain >> 2) & 3) {
            case 1: return (a & 0xFF00) | ((a & 0xE0) >> 5) | ((a & 0x1F) << 3);
            case 2: return (a & 0xFE00) | ((a & 0x1C0) >> 6) | ((a & 0x3F) << 3);
            case 3: return (a & 0xFC00) | ((a & 0x380) >> 7) | ((a & 0x7F) << 3);
            default: return a;
        }
    }

    private int vramStep() {
        switch (vmain & 3) { case 0: return 1; case 1: return 32; default: return 128; }
    }

    private static int sext13(int v) { return (v << 19) >> 19; }

    /** Called by the scheduler when VBlank starts. */
    public void vblankStart() {
        if ((inidisp & 0x80) == 0) oamAddr = oamAddrReg << 1;
    }

    public void frameStart() { rangeOver = timeOver = false; }

    // ------------------------------------------------------------------ registers

    public boolean trace; public int traceLine, traceFrame;
    public void write(int reg, int v) {
        v &= 0xFF;
        if (trace && (reg <= 0x14 && reg != 0x04 && reg != 0x02 && reg != 0x03 || reg == 0x2C || reg == 0x2D || reg >= 0x30 && reg <= 0x32)) System.out.printf("f%d line %d: W $21%02X = %02X%n", traceFrame, traceLine, reg, v);
        switch (reg) {
            case 0x00: inidisp = v; break;
            case 0x01: obsel = v; break;
            case 0x02: oamAddrReg = (oamAddrReg & 0x100) | v; oamAddr = oamAddrReg << 1; break;
            case 0x03: oamAddrReg = (oamAddrReg & 0xFF) | ((v & 1) << 8); oamPriority = v & 0x80; oamAddr = oamAddrReg << 1; break;
            case 0x04:
                if (oamAddr >= 0x200) oam[0x200 + (oamAddr & 0x1F)] = (byte) v;
                else if ((oamAddr & 1) == 0) oamLatch = v;
                else { oam[oamAddr - 1] = (byte) oamLatch; oam[oamAddr] = (byte) v; }
                oamAddr = (oamAddr + 1) & 0x3FF;
                break;
            case 0x05: bgmode = v; break;
            case 0x06: mosaic = v; break;
            case 0x07: case 0x08: case 0x09: case 0x0A: bgsc[reg - 7] = v; break;
            case 0x0B: bgChar[0] = (v & 0xF) << 13; bgChar[1] = ((v >> 4) & 0xF) << 13; break;
            case 0x0C: bgChar[2] = (v & 0xF) << 13; bgChar[3] = ((v >> 4) & 0xF) << 13; break;
            case 0x0D: case 0x0F: case 0x11: case 0x13: {
                int bg = (reg - 0x0D) >> 1;
                if (bg == 0) m7hofs = sext13((v << 8) | m7Latch);
                hofs[bg] = (v << 8) | (bgLatch & ~7) | ((hofs[bg] >> 8) & 7); // kept 16-bit; wrapped when used
                bgLatch = v; m7Latch = v;
                break;
            }
            case 0x0E: case 0x10: case 0x12: case 0x14: {
                int bg = (reg - 0x0E) >> 1;
                if (bg == 0) m7vofs = sext13((v << 8) | m7Latch);
                vofs[bg] = (v << 8) | bgLatch;
                bgLatch = v; m7Latch = v;
                break;
            }
            case 0x15: vmain = v; break;
            case 0x16: vmadd = (vmadd & 0xFF00) | v; vramLatch = vramWord(remap()); break;
            case 0x17: vmadd = (vmadd & 0x00FF) | (v << 8); vramLatch = vramWord(remap()); break;
            case 0x18: {
                int i = (remap() << 1) & 0xFFFF;
                vram[i] = (byte) v;
                if ((vmain & 0x80) == 0) vmadd = (vmadd + vramStep()) & 0xFFFF;
                break;
            }
            case 0x19: {
                int i = ((remap() << 1) + 1) & 0xFFFF;
                vram[i] = (byte) v;
                if ((vmain & 0x80) != 0) vmadd = (vmadd + vramStep()) & 0xFFFF;
                break;
            }
            case 0x1A: m7sel = v; break;
            case 0x1B: m7a = (short) ((v << 8) | m7Latch); m7Latch = v; break;
            case 0x1C: m7b = (short) ((v << 8) | m7Latch); m7bLast = v; m7Latch = v; break;
            case 0x1D: m7c = (short) ((v << 8) | m7Latch); m7Latch = v; break;
            case 0x1E: m7d = (short) ((v << 8) | m7Latch); m7Latch = v; break;
            case 0x1F: m7x = sext13((v << 8) | m7Latch); m7Latch = v; break;
            case 0x20: m7y = sext13((v << 8) | m7Latch); m7Latch = v; break;
            case 0x21: cgadd = v; cgHigh = false; break;
            case 0x22:
                if (!cgHigh) cgLatch = v;
                else { cgram[cgadd] = ((v & 0x7F) << 8) | cgLatch; cgadd = (cgadd + 1) & 0xFF; }
                cgHigh = !cgHigh;
                break;
            case 0x23: w12sel = v; break;
            case 0x24: w34sel = v; break;
            case 0x25: wobjsel = v; break;
            case 0x26: wh0 = v; break;
            case 0x27: wh1 = v; break;
            case 0x28: wh2 = v; break;
            case 0x29: wh3 = v; break;
            case 0x2A: wbglog = v; break;
            case 0x2B: wobjlog = v; break;
            case 0x2C: tm = v; break;
            case 0x2D: ts = v; break;
            case 0x2E: tmw = v; break;
            case 0x2F: tsw = v; break;
            case 0x30: cgwsel = v; break;
            case 0x31: cgadsub = v; break;
            case 0x32:
                if ((v & 0x20) != 0) fixR = v & 31;
                if ((v & 0x40) != 0) fixG = v & 31;
                if ((v & 0x80) != 0) fixB = v & 31;
                break;
            case 0x33: setini = v; break;
            default: break;
        }
    }

    public int read(int reg) {
        switch (reg) {
            case 0x34: case 0x35: case 0x36: {
                int r = m7a * (byte) m7bLast;
                return (r >> ((reg - 0x34) * 8)) & 0xFF;
            }
            case 0x38: {
                int v;
                if (oamAddr >= 0x200) v = oam[0x200 + (oamAddr & 0x1F)] & 0xFF;
                else v = oam[oamAddr] & 0xFF;
                oamAddr = (oamAddr + 1) & 0x3FF;
                return v;
            }
            case 0x39: {
                int v = vramLatch & 0xFF;
                if ((vmain & 0x80) == 0) { vramLatch = vramWord(remap()); vmadd = (vmadd + vramStep()) & 0xFFFF; }
                return v;
            }
            case 0x3A: {
                int v = vramLatch >> 8;
                if ((vmain & 0x80) != 0) { vramLatch = vramWord(remap()); vmadd = (vmadd + vramStep()) & 0xFFFF; }
                return v;
            }
            case 0x3B: {
                int c = cgram[cgadd], v;
                if (!cgHigh) v = c & 0xFF; else { v = (c >> 8) & 0x7F; cgadd = (cgadd + 1) & 0xFF; }
                cgHigh = !cgHigh;
                return v;
            }
            case 0x3E: return 0x01 | (rangeOver ? 0x40 : 0) | (timeOver ? 0x80 : 0);
            default: return 0;
        }
    }

    // ------------------------------------------------------------------ rendering

    /** Renders screen row s (0..223). */
    public void renderLine(int s) {
        int base = s * WIDTH;
        if ((inidisp & 0x80) != 0) { Arrays.fill(frame, base, base + WIDTH, 0xFF000000); return; }
        int mode = bgmode & 7;
        dbgScroll[0][s] = hofs[0]; dbgScroll[1][s] = hofs[1]; dbgScroll[2][s] = vofs[0]; dbgScroll[3][s] = vofs[1];
        for (int b = 0; b < 4; b++) { Arrays.fill(bgCol[b], 0); }
        Arrays.fill(objCol, 0);

        switch (mode) {
            case 0: for (int b = 0; b < 4; b++) renderBg(b, s, 2, true); break;
            case 1: renderBg(0, s, 4, false); renderBg(1, s, 4, false); renderBg(2, s, 2, false); break;
            case 2: renderBg(0, s, 4, false); renderBg(1, s, 4, false); break;
            case 3: renderBg(0, s, 8, false); renderBg(1, s, 4, false); break;
            case 4: renderBg(0, s, 8, false); renderBg(1, s, 2, false); break;
            case 5: renderBg(0, s, 4, false); renderBg(1, s, 2, false); break;
            case 6: renderBg(0, s, 4, false); break;
            default: renderMode7(s); break;
        }
        if (((tm | ts) & 0x10) != 0) renderObjects(s);
        computeWindows();

        int[] order = order(mode);
        compose(tm & debugMask, tmw, mainCol, mainLay, mainObjMath, order, false);
        boolean useSub = (cgadsub & 0x3F) != 0 && (cgwsel & 2) != 0;
        if (useSub) compose(ts, tsw, subCol, subLay, subObjMath, order, true);

        int bright = inidisp & 0xF;
        int fixed = fixR | (fixG << 5) | (fixB << 10);
        boolean subtract = (cgadsub & 0x80) != 0, halfBit = (cgadsub & 0x40) != 0;
        for (int x = 0; x < WIDTH; x++) {
            int c = mainCol[x];
            int lay = mainLay[x];
            boolean inCw = win[5][x];
            if (region((cgwsel >> 6) & 3, inCw)) c = 0;
            boolean enabled;
            if (lay == 4) enabled = mainObjMath[x] && (cgadsub & 0x10) != 0;
            else enabled = (cgadsub & (1 << lay)) != 0;
            if (enabled && !region((cgwsel >> 4) & 3, inCw)) {
                int sc; boolean half = halfBit;
                if (useSub) {
                    sc = subCol[x];
                    if (subLay[x] == 5) { sc = fixed; half = false; }
                } else sc = fixed;
                c = blend(c, sc, subtract, half);
            }
            frame[base + x] = toRgb(c, bright);
        }
    }

    private static boolean region(int mode, boolean inWin) {
        switch (mode) { case 0: return false; case 1: return !inWin; case 2: return inWin; default: return true; }
    }

    private static int blend(int a, int b, boolean sub, boolean half) {
        int r = 0;
        for (int sh = 0; sh <= 10; sh += 5) {
            int x = (a >> sh) & 31, y = (b >> sh) & 31, v;
            if (sub) { v = x - y; if (v < 0) v = 0; if (half) v >>= 1; }
            else { v = x + y; if (half) v >>= 1; if (v > 31) v = 31; }
            r |= v << sh;
        }
        return r;
    }

    private static int toRgb(int c, int bright) {
        int r = c & 31, g = (c >> 5) & 31, b = (c >> 10) & 31;
        r = (r << 3) | (r >> 2); g = (g << 3) | (g >> 2); b = (b << 3) | (b >> 2);
        if (bright != 15) { r = r * bright / 15; g = g * bright / 15; b = b * bright / 15; }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // code = layer << 2 | priority ; layer 0-3 = BG1-4, 4 = OBJ
    private static final int O = 4 << 2;
    private static final int[] ORD0 = {O | 3, 0 | 1, 4 | 1, O | 2, 0, 4, O | 1, 8 | 1, 12 | 1, O, 8, 12};
    private static final int[] ORD1 = {O | 3, 0 | 1, 4 | 1, O | 2, 0, 4, O | 1, 8 | 1, O, 8};
    private static final int[] ORD1_BG3 = {8 | 1, O | 3, 0 | 1, 4 | 1, O | 2, 0, 4, O | 1, O, 8};
    private static final int[] ORD2 = {O | 3, 0 | 1, O | 2, 4 | 1, O | 1, 0, O, 4};
    private static final int[] ORD6 = {O | 3, 0 | 1, O | 2, O | 1, 0, O};
    private static final int[] ORD7 = {O | 3, O | 2, O | 1, 0, O};

    private int[] order(int mode) {
        switch (mode) {
            case 0: return ORD0;
            case 1: return (bgmode & 8) != 0 ? ORD1_BG3 : ORD1;
            case 6: return ORD6;
            case 7: return ORD7;
            default: return ORD2;
        }
    }

    private void compose(int enable, int winEnable, int[] outCol, int[] outLay, boolean[] outMath,
                         int[] order, boolean sub) {
        int backdrop = sub ? (fixR | (fixG << 5) | (fixB << 10)) : (debugMask != 0x1F ? 0x7C1F : cgram[0]);
        for (int x = 0; x < WIDTH; x++) {
            int col = backdrop, lay = 5;
            boolean math = false;
            for (int code : order) {
                int l = code >> 2, p = code & 3;
                if ((enable & (1 << l)) == 0) continue;
                int c;
                if (l == 4) { c = objCol[x]; if (c == 0 || objPrio[x] != p) continue; }
                else { c = bgCol[l][x]; if (c == 0 || bgPrio[l][x] != p) continue; }
                if ((winEnable & (1 << l)) != 0 && win[l][x]) continue;
                col = c & 0x7FFF; lay = l; math = l == 4 && objMath[x];
                break;
            }
            outCol[x] = col; outLay[x] = lay; outMath[x] = math;
        }
    }

    // ------------------------------------------------------------------ windows

    private void computeWindows() {
        for (int l = 0; l < 6; l++) {
            int sel, log;
            switch (l) {
                case 0: sel = w12sel & 0xF; log = wbglog & 3; break;
                case 1: sel = w12sel >> 4; log = (wbglog >> 2) & 3; break;
                case 2: sel = w34sel & 0xF; log = (wbglog >> 4) & 3; break;
                case 3: sel = w34sel >> 4; log = (wbglog >> 6) & 3; break;
                case 4: sel = wobjsel & 0xF; log = wobjlog & 3; break;
                default: sel = wobjsel >> 4; log = (wobjlog >> 2) & 3; break;
            }
            boolean w1 = (sel & 2) != 0, w2 = (sel & 8) != 0;
            boolean[] out = win[l];
            if (!w1 && !w2) { Arrays.fill(out, false); continue; }
            boolean i1 = (sel & 1) != 0, i2 = (sel & 4) != 0;
            for (int x = 0; x < WIDTH; x++) {
                boolean a = (x >= wh0 && x <= wh1) != i1;
                boolean b = (x >= wh2 && x <= wh3) != i2;
                boolean r;
                if (w1 && !w2) r = a;
                else if (w2 && !w1) r = b;
                else switch (log) {
                    case 0: r = a | b; break;
                    case 1: r = a & b; break;
                    case 2: r = a ^ b; break;
                    default: r = !(a ^ b); break;
                }
                out[x] = r;
            }
        }
    }

    // ------------------------------------------------------------------ backgrounds

    private void renderBg(int bg, int s, int bpp, boolean mode0) {
        int sc = bgsc[bg];
        boolean wide = (sc & 1) != 0, tall = (sc & 2) != 0;
        int mapBase = (sc & 0xFC) << 8;           // in words
        int tsz = ((bgmode >> (4 + bg)) & 1) != 0 ? 16 : 8;
        int msize = (mosaic >> 4) + 1;
        boolean mos = (mosaic & (1 << bg)) != 0 && msize > 1;
        int ys = mos ? s - s % msize : s;
        int y = ys + vofs[bg];
        int tileRows = tall ? 64 : 32, tileCols = wide ? 64 : 32;
        int ty = (y / tsz) & (tileRows - 1);
        int fyBase = y & (tsz - 1);
        int charBase = bgChar[bg];
        int tileBytes = bpp * 8;
        boolean direct = bpp == 8 && (cgwsel & 1) != 0;
        int palBase = mode0 ? bg * 32 : 0;
        int[] col = bgCol[bg], pri = bgPrio[bg];
        int lastTx = -1, entry = 0;
        for (int px = 0; px < WIDTH; px++) {
            int x = mos ? px - px % msize : px;
            int xx = x + hofs[bg];
            int tx = (xx / tsz) & (tileCols - 1);
            if (tx != lastTx) {
                int screen = ((tx >> 5) & 1) + (((ty >> 5) & 1) * (wide ? 2 : 1));
                entry = vramWord(mapBase + screen * 0x400 + (ty & 31) * 32 + (tx & 31));
                lastTx = tx;
            }
            int tnum = entry & 0x3FF, palette = (entry >> 10) & 7;
            boolean hf = (entry & 0x4000) != 0, vf = (entry & 0x8000) != 0;
            int fx = xx & (tsz - 1), fy = fyBase;
            int sub = 0;
            if (tsz == 16) {
                int tcx = fx >> 3, tcy = fy >> 3;
                if (hf) tcx = 1 - tcx;
                if (vf) tcy = 1 - tcy;
                sub = tcx + tcy * 16;
                fx &= 7; fy &= 7;
            }
            if (hf) fx = 7 - fx;
            if (vf) fy = 7 - fy;
            int a = (charBase + ((tnum + sub) & 0x3FF) * tileBytes) & 0xFFFF;
            int bit = 7 - fx;
            int r = fy * 2;
            int c = ((vram[(a + r) & 0xFFFF] >> bit) & 1) | (((vram[(a + r + 1) & 0xFFFF] >> bit) & 1) << 1);
            if (bpp >= 4) {
                c |= (((vram[(a + 16 + r) & 0xFFFF] >> bit) & 1) << 2) | (((vram[(a + 17 + r) & 0xFFFF] >> bit) & 1) << 3);
                if (bpp == 8) {
                    c |= (((vram[(a + 32 + r) & 0xFFFF] >> bit) & 1) << 4) | (((vram[(a + 33 + r) & 0xFFFF] >> bit) & 1) << 5)
                            | (((vram[(a + 48 + r) & 0xFFFF] >> bit) & 1) << 6) | (((vram[(a + 49 + r) & 0xFFFF] >> bit) & 1) << 7);
                }
            }
            if (c == 0) { col[px] = 0; continue; }
            int rgb;
            if (direct) {
                rgb = ((c & 7) << 2) | ((palette & 1) << 1) | ((((c >> 3) & 7) << 2 | (palette & 2)) << 5)
                        | ((((c >> 6) << 3) | ((palette & 4))) << 10);
            } else if (bpp == 8) rgb = cgram[c];
            else rgb = cgram[(palBase + palette * (1 << bpp) + c) & 0xFF];
            col[px] = 0x10000 | rgb;
            pri[px] = (entry >> 13) & 1;
        }
    }

    private void renderMode7(int s) {
        boolean flipX = (m7sel & 1) != 0, flipY = (m7sel & 2) != 0;
        int wrapMode = m7sel >> 6;
        int y = flipY ? 255 - s : s;
        int hoff = clip10(m7hofs - m7x), voff = clip10(m7vofs - m7y);
        int ox = m7a * hoff + m7b * voff + (m7x << 8) + m7b * y;
        int oy = m7c * hoff + m7d * voff + (m7y << 8) + m7d * y;
        boolean direct = (cgwsel & 1) != 0;
        for (int px = 0; px < WIDTH; px++) {
            int x = flipX ? 255 - px : px;
            int X = (ox + m7a * x) >> 8, Y = (oy + m7c * x) >> 8;
            int c;
            boolean outside = X < 0 || X > 1023 || Y < 0 || Y > 1023;
            if (outside && wrapMode == 2) { bgCol[0][px] = 0; continue; }
            int tile;
            if (outside && wrapMode == 3) tile = 0;
            else tile = vram[(((Y >> 3) & 127) * 128 + ((X >> 3) & 127)) * 2 & 0xFFFF] & 0xFF;
            c = vram[((tile * 64 + (Y & 7) * 8 + (X & 7)) * 2 + 1) & 0xFFFF] & 0xFF;
            if (c == 0) { bgCol[0][px] = 0; continue; }
            int rgb = direct ? ((c & 7) << 2) | ((((c >> 3) & 7) << 2) << 5) | (((c >> 6) << 3) << 10) : cgram[c];
            bgCol[0][px] = 0x10000 | rgb;
            bgPrio[0][px] = 0;
        }
    }

    private static int clip10(int n) { return (n & 0x2000) != 0 ? (n | ~0x3FF) : (n & 0x3FF); }

    // ------------------------------------------------------------------ sprites

    private static final int[][] OBJ_SIZES = {
            {8, 16}, {8, 32}, {8, 64}, {16, 32}, {16, 64}, {32, 64}, {16, 32}, {16, 32}};
    private static final int[][] OBJ_LARGE = {
            {16, 16}, {32, 32}, {64, 64}, {32, 32}, {64, 64}, {64, 64}, {32, 64}, {32, 32}};
    private static final int[][] OBJ_SMALL = {
            {8, 8}, {8, 8}, {8, 8}, {16, 16}, {16, 16}, {32, 32}, {16, 32}, {16, 32}};

    private final int[] lineSprites = new int[32];

    private void renderObjects(int s) {
        int sizeSel = obsel >> 5;
        int smallW = OBJ_SMALL[sizeSel][0], smallH = OBJ_SMALL[sizeSel][1];
        int largeW = OBJ_LARGE[sizeSel][0], largeH = OBJ_LARGE[sizeSel][1];
        int first = oamPriority != 0 ? (oamAddrReg >> 1) & 0x7F : 0;
        int count = 0;
        for (int n = 0; n < 128; n++) {
            int i = (first + n) & 0x7F;
            int hi = (oam[0x200 + (i >> 2)] >> ((i & 3) * 2)) & 3;
            int h = (hi & 2) != 0 ? largeH : smallH;
            int w = (hi & 2) != 0 ? largeW : smallW;
            int y = oam[i * 4 + 1] & 0xFF;
            int x = (oam[i * 4] & 0xFF) | ((hi & 1) << 8);
            if (x >= 256) x -= 512;
            if (((s - y) & 0xFF) >= h) continue;
            if (x + w <= 0 || x >= 256) continue;
            if (count == 32) { rangeOver = true; break; }
            lineSprites[count++] = i;
        }
        int nameBase = (obsel & 7) << 14;                       // bytes
        int nameGap = nameBase + ((((obsel >> 3) & 3) + 1) << 13);
        for (int k = count - 1; k >= 0; k--) {
            int i = lineSprites[k];
            int hi = (oam[0x200 + (i >> 2)] >> ((i & 3) * 2)) & 3;
            boolean large = (hi & 2) != 0;
            int w = large ? largeW : smallW, h = large ? largeH : smallH;
            int y = oam[i * 4 + 1] & 0xFF;
            int x = (oam[i * 4] & 0xFF) | ((hi & 1) << 8);
            if (x >= 256) x -= 512;
            int tile = oam[i * 4 + 2] & 0xFF, attr = oam[i * 4 + 3] & 0xFF;
            boolean hf = (attr & 0x40) != 0, vf = (attr & 0x80) != 0;
            int prio = (attr >> 4) & 3, pal = (attr >> 1) & 7;
            int dy = (s - y) & 0xFF;
            int fy = vf ? h - 1 - dy : dy;
            int ty = fy >> 3, py = fy & 7;
            int tableBase = (attr & 1) != 0 ? nameGap : nameBase;
            for (int ix = 0; ix < w; ix++) {
                int sx = x + ix;
                if (sx < 0 || sx >= 256) continue;
                int fx = hf ? w - 1 - ix : ix;
                int tx = fx >> 3, px = fx & 7;
                int n = (((tile & 0xF0) + (ty << 4)) & 0xF0) | ((tile + tx) & 0x0F);
                int a = (tableBase + n * 32 + py * 2) & 0xFFFF;
                int bit = 7 - px;
                int c = ((vram[a] >> bit) & 1) | (((vram[(a + 1) & 0xFFFF] >> bit) & 1) << 1)
                        | (((vram[(a + 16) & 0xFFFF] >> bit) & 1) << 2) | (((vram[(a + 17) & 0xFFFF] >> bit) & 1) << 3);
                if (c == 0) continue;
                objCol[sx] = 0x10000 | cgram[128 + pal * 16 + c];
                objPrio[sx] = prio;
                objMath[sx] = pal >= 4;
            }
        }
    }

    @Override
    public void save(StateOut o) throws IOException {
        o.bytes(vram); o.ints(cgram); o.bytes(oam);
        o.i(inidisp); o.i(obsel); o.i(oamAddrReg); o.i(oamAddr); o.i(oamLatch); o.i(oamPriority);
        o.i(bgmode); o.i(mosaic); o.ints(bgsc); o.ints(bgChar); o.ints(hofs); o.ints(vofs);
        o.i(bgLatch); o.i(m7Latch); o.i(vmain); o.i(vmadd); o.i(vramLatch);
        o.i(m7sel); o.i(m7a); o.i(m7b); o.i(m7c); o.i(m7d); o.i(m7x); o.i(m7y); o.i(m7hofs); o.i(m7vofs); o.i(m7bLast);
        o.i(cgadd); o.i(cgLatch); o.b(cgHigh);
        o.i(w12sel); o.i(w34sel); o.i(wobjsel); o.i(wh0); o.i(wh1); o.i(wh2); o.i(wh3); o.i(wbglog); o.i(wobjlog);
        o.i(tm); o.i(ts); o.i(tmw); o.i(tsw); o.i(cgwsel); o.i(cgadsub); o.i(fixR); o.i(fixG); o.i(fixB); o.i(setini);
        o.b(rangeOver); o.b(timeOver);
        o.ints(frame);
    }

    @Override
    public void load(StateIn in) throws IOException {
        in.bytes(vram); in.ints(cgram); in.bytes(oam);
        inidisp = in.i(); obsel = in.i(); oamAddrReg = in.i(); oamAddr = in.i(); oamLatch = in.i(); oamPriority = in.i();
        bgmode = in.i(); mosaic = in.i(); in.ints(bgsc); in.ints(bgChar); in.ints(hofs); in.ints(vofs);
        bgLatch = in.i(); m7Latch = in.i(); vmain = in.i(); vmadd = in.i(); vramLatch = in.i();
        m7sel = in.i(); m7a = in.i(); m7b = in.i(); m7c = in.i(); m7d = in.i(); m7x = in.i(); m7y = in.i();
        m7hofs = in.i(); m7vofs = in.i(); m7bLast = in.i();
        cgadd = in.i(); cgLatch = in.i(); cgHigh = in.b();
        w12sel = in.i(); w34sel = in.i(); wobjsel = in.i(); wh0 = in.i(); wh1 = in.i(); wh2 = in.i(); wh3 = in.i();
        wbglog = in.i(); wobjlog = in.i();
        tm = in.i(); ts = in.i(); tmw = in.i(); tsw = in.i(); cgwsel = in.i(); cgadsub = in.i();
        fixR = in.i(); fixG = in.i(); fixB = in.i(); setini = in.i();
        rangeOver = in.b(); timeOver = in.b();
        in.ints(frame);
    }
}
