package dev.superjavendo.ppu;

import java.util.Arrays;

/**
 * "Enhanced Mode 7": the Mode 7 layer drawn again at {@code scale} times the resolution, as an extra output next to the exact
 * 256x224 frame (which is always produced and is the only thing emulation, hashes, movies and save states ever see).
 *
 * <p>The PPU tells it, line by line, the Mode 7 registers in force and which pixels of the finished line came from the layer
 * untouched (no window, no colour maths). At the end of the frame every output pixel of such a pixel is sampled from the
 * texture at a sub-pixel position with bilinear filtering; the matrix of the sub-rows between two lines is interpolated between the
 * line above and the one below, so a perspective floor made by changing the matrix every line (F-Zero, Mario Kart) gets
 * the extra rows its geometry would have had. Every other pixel (sprites, other layers, effects) is the native pixel repeated.
 * The sample at the top-left of each native pixel sits exactly where the hardware sampled.
 */
final class Mode7Enhancer {
    private static final int W = Ppu.WIDTH, H = Ppu.HEIGHT;
    final int scale, outW, outH;
    private final int[] out;
    private boolean any;

    // what the registers said for every line
    private final boolean[] m7 = new boolean[H];
    private final int[] ox = new int[H], oy = new int[H], ma = new int[H], mb = new int[H], mc = new int[H], md = new int[H];
    private final int[] sel = new int[H], bright = new int[H];
    private final boolean[] direct = new boolean[H];
    private final boolean[] plain = new boolean[H * W];
    private final int[] pal8 = new int[256];

    Mode7Enhancer(int scale) {
        this.scale = scale;
        outW = W * scale;
        outH = H * scale;
        out = new int[outW * outH];
    }

    void beginFrame() { Arrays.fill(m7, false); any = false; }

    /** The Mode 7 registers for screen row {@code s} (same arithmetic as the PPU's own Mode 7 row). */
    void line(int s, int a, int b, int c, int d, int x, int y0, int hofs, int vofs, int m7sel, boolean directColour, int bright) {
        boolean flipY = (m7sel & 2) != 0;
        int y = flipY ? 255 - (s + 1) : s + 1;
        int hoff = clip10(hofs - x), voff = clip10(vofs - y0);
        ox[s] = a * hoff + b * voff + (x << 8) + b * y;
        oy[s] = c * hoff + d * voff + (y0 << 8) + d * y;
        ma[s] = a; mb[s] = b; mc[s] = c; md[s] = d;
        sel[s] = m7sel; direct[s] = directColour; this.bright[s] = bright;
        m7[s] = true;
        any = true;
    }

    /** Whether the pixel of this line is the Mode 7 layer showing through unchanged. */
    void plain(int s, int x, boolean v) { plain[s * W + x] = v; }

    private static int clip10(int n) { return (n & 0x2000) != 0 ? (n | ~0x3FF) : (n & 0x3FF); }

    /** Builds the enhanced frame, or returns null when no line of it was Mode 7. */
    int[] finish(byte[] vram, int[] cgram, int[] frame) {
        if (!any) return null;
        for (int i = 0; i < 256; i++) pal8[i] = Ppu.toRgb(cgram[i], 15);
        java.util.stream.IntStream.range(0, outH).parallel().forEach(rr -> row(rr, vram, frame));      // rows are independent
        return out;
    }

    private void row(int rr, byte[] vram, int[] frame) {
        int s = rr / scale, r = rr % scale, o = rr * outW;
        if (!usable(s)) { repeat(frame, s, o); return; }
        double fr = (double) r / scale;
        boolean blend = s + 1 < H && usable(s + 1) && sel[s + 1] == sel[s];
        double x0, y0, a, c;
        if (blend) {
            x0 = (1 - fr) * ox[s] + fr * ox[s + 1]; y0 = (1 - fr) * oy[s] + fr * oy[s + 1];
            a = (1 - fr) * ma[s] + fr * ma[s + 1]; c = (1 - fr) * mc[s] + fr * mc[s + 1];
        } else {                                                       // last row of the layer: keep going with its own matrix
            x0 = ox[s] + fr * mb[s]; y0 = oy[s] + fr * md[s]; a = ma[s]; c = mc[s];
        }
        boolean flipX = (sel[s] & 1) != 0;
        int wrapMode = sel[s] >> 6, br = bright[s], rowBase = s * W;
        double step = 1.0 / scale;
        for (int cx = 0; cx < outW; cx++) {
            int px = cx / scale;
            if (!plain[rowBase + px]) { out[o + cx] = frame[rowBase + px]; continue; }
            double xf = px + (cx - px * scale) * step;
            if (flipX) xf = 255 - xf;
            out[o + cx] = sample(vram, (x0 + a * xf) * (1 / 256.0), (y0 + c * xf) * (1 / 256.0), wrapMode, br, frame[rowBase + px]);
        }
    }

    private boolean usable(int s) { return m7[s] && !direct[s] && (ma[s] != 0 || mc[s] != 0); }

    private void repeat(int[] frame, int s, int o) {
        for (int cx = 0; cx < outW; cx++) out[o + cx] = frame[s * W + cx / scale];
    }

    /** Bilinear sample of the plane at texel position (tx, ty); {@code native} is shown where the layer is transparent. */
    private int sample(byte[] vram, double tx, double ty, int wrapMode, int bright, int nativePixel) {
        int nearest = idx(vram, (int) Math.floor(tx), (int) Math.floor(ty), wrapMode);
        if (nearest == 0) return nativePixel;
        double u = tx - 0.5, v = ty - 0.5;
        int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        int fx = (int) ((u - x0) * 256), fy = (int) ((v - y0) * 256);
        int c00 = col(idx(vram, x0, y0, wrapMode), nearest), c10 = col(idx(vram, x0 + 1, y0, wrapMode), nearest);
        int c01 = col(idx(vram, x0, y0 + 1, wrapMode), nearest), c11 = col(idx(vram, x0 + 1, y0 + 1, wrapMode), nearest);
        int rgb = 0;
        for (int sh = 0; sh <= 16; sh += 8) {
            int top = ((c00 >> sh) & 255) * (256 - fx) + ((c10 >> sh) & 255) * fx;
            int bot = ((c01 >> sh) & 255) * (256 - fx) + ((c11 >> sh) & 255) * fx;
            int ch = (top * (256 - fy) + bot * fy) >> 16;
            if (bright != 15) ch = ch * bright / 15;
            rgb |= ch << sh;
        }
        return 0xFF000000 | rgb;
    }

    private int col(int idx, int nearest) { return pal8[idx == 0 ? nearest : idx]; }

    /** Palette index of texel (x, y) of the 1024x1024 plane; 0 = transparent. */
    private static int idx(byte[] vram, int x, int y, int wrapMode) {
        boolean outside = x < 0 || x > 1023 || y < 0 || y > 1023;
        if (outside && wrapMode == 2) return 0;
        int tile = outside && wrapMode == 3 ? 0 : vram[(((y >> 3) & 127) * 128 + ((x >> 3) & 127)) * 2 & 0xFFFF] & 0xFF;
        return vram[((tile * 64 + (y & 7) * 8 + (x & 7)) * 2 + 1) & 0xFFFF] & 0xFF;
    }
}
