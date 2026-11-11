package dev.superjavendo.cart;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;

import java.io.IOException;

/**
 * S-DD1 (Street Fighter Alpha 2, Star Ocean): a streaming decompressor sitting between the ROM and the DMA unit.
 *
 * <p>The chip watches the DMA registers {@code $43x2-$43x6}. When a DMA channel that is enabled in {@code $4800} and
 * {@code $4801} reads from the address it was programmed with in banks {@code $C0-$FF}, the ROM data at that address
 * is treated as the start of a compressed stream and every read returns the next decompressed byte instead. The
 * registers {@code $4804-$4807} select which 1 MB ROM block shows up in each 1 MB window of {@code $C0-$FF}; the
 * LoROM-like view at {@code $00-$3F/$80-$BF:$8000-$FFFF} is derived from the same ROM.
 *
 * <p>The decoding algorithm (adaptive Golomb run-length coding driven by a 32-context probability estimator over
 * bitplane-aware contexts) was documented by Andreas Naive, who released his reference code to the public domain.
 * This is an independent implementation of it in the structure used by this emulator.
 */
public final class Sdd1 implements Coprocessor {
    private final byte[] rom;
    private int r4800, r4801, r4804 = 0, r4805 = 1, r4806 = 2, r4807 = 3;
    private final int[] dmaAddr = new int[8], dmaSize = new int[8];
    private static final boolean LOG = Boolean.getBoolean("sdd1log");
    private boolean streaming;
    private final Decoder decoder = new Decoder();

    public Sdd1(byte[] rom) { this.rom = rom; }

    // ------------------------------------------------------------------------------------------ memory

    /** Address folded into a ROM/RAM whose size is not a power of two (the upper part mirrors within itself). */
    static int mirror(int addr, int size) {
        if (size == 0) return 0;
        int base = 0, mask = 1 << 23;
        while (addr >= size) {
            while ((addr & mask) == 0) mask >>= 1;
            addr -= mask;
            if (size > mask) { size -= mask; base += mask; }
            mask >>= 1;
        }
        return base + addr;
    }

    private int romAt(int addr) { return rom[mirror(addr, rom.length)] & 0xFF; }

    /** ROM as seen through the four 1 MB bank windows at $C0-$FF. */
    private int mmc(int addr) {
        int block = switch ((addr >> 20) & 3) { case 0 -> r4804; case 1 -> r4805; case 2 -> r4806; default -> r4807; };
        return romAt((block & 0xF) << 20 | (addr & 0xFFFFF));
    }

    @Override public int read(int bank, int off) {
        bank &= 0xFF;
        if ((bank & 0x7F) < 0x40 && off >= 0x4800 && off < 0x4810) {
            switch (off & 0xF) {
                case 0: return r4800;
                case 1: return r4801;
                case 4: return r4804;
                case 5: return r4805;
                case 6: return r4806;
                case 7: return r4807;
                default: return -1;
            }
        }
        int addr = bank << 16 | off;
        if ((bank & 0x40) == 0) {                                   // $00-$3F, $80-$BF: $8000-$FFFF
            if (off < 0x8000) return -1;
            if ((bank & 0x80) == 0 && (bank & 0x20) != 0 && (r4805 & 0x80) != 0) addr &= ~(1 << 21);
            if ((bank & 0x80) != 0 && (bank & 0x20) != 0 && (r4807 & 0x80) != 0) addr &= ~(1 << 21);
            return romAt((addr >> 1) & 0x1F8000 | (addr & 0x7FFF));
        }
        if (bank < 0xC0) return -1;                                 // $40-$7F: SRAM etc., normal mapping
        if ((r4800 & r4801) != 0) {
            for (int n = 0; n < 8; n++) {
                if ((r4800 & r4801 & (1 << n)) != 0 && addr == dmaAddr[n]) {
                    if (!streaming) { decoder.start(addr); streaming = true; if (LOG) System.out.printf("SDD1 start ch%d addr=%06X size=%d r4804-7=%02X %02X %02X %02X%n", n, addr, dmaSize[n], r4804, r4805, r4806, r4807); }
                    int v = decoder.next();
                    dmaSize[n] = (dmaSize[n] - 1) & 0xFFFF;
                    if (dmaSize[n] == 0) { streaming = false; r4801 &= ~(1 << n); }
                    return v;
                }
            }
        }
        return mmc(addr);
    }

    @Override public boolean write(int bank, int off, int v) {
        if ((bank & 0x7F) >= 0x40) return false;
        if (LOG && off >= 0x4800 && off < 0x4810) System.out.printf("SDD1 W %04X=%02X%n", off, v);
        if (off >= 0x4300 && off < 0x4380) {                        // snoop DMA setup, the DMA unit still gets the write
            int ch = (off >> 4) & 7;
            switch (off & 0xF) {
                case 2 -> dmaAddr[ch] = dmaAddr[ch] & 0xFFFF00 | v;
                case 3 -> dmaAddr[ch] = dmaAddr[ch] & 0xFF00FF | v << 8;
                case 4 -> dmaAddr[ch] = dmaAddr[ch] & 0x00FFFF | v << 16;
                case 5 -> dmaSize[ch] = dmaSize[ch] & 0xFF00 | v;
                case 6 -> dmaSize[ch] = dmaSize[ch] & 0x00FF | v << 8;
                default -> { }
            }
            return false;
        }
        if (off < 0x4800 || off >= 0x4810) return false;
        switch (off & 0xF) {
            case 0 -> r4800 = v;
            case 1 -> r4801 = v;
            case 4 -> r4804 = v & 0x8F;
            case 5 -> r4805 = v & 0x8F;
            case 6 -> r4806 = v & 0x8F;
            case 7 -> r4807 = v & 0x8F;
            default -> { }
        }
        return true;
    }

    @Override public void save(StateOut o) throws IOException {
        o.i(r4800); o.i(r4801); o.i(r4804); o.i(r4805); o.i(r4806); o.i(r4807);
        o.ints(dmaAddr); o.ints(dmaSize);
    }

    @Override public void load(StateIn in) throws IOException {
        r4800 = in.i(); r4801 = in.i(); r4804 = in.i(); r4805 = in.i(); r4806 = in.i(); r4807 = in.i();
        in.ints(dmaAddr); in.ints(dmaSize);
        streaming = false;      // a decompression never spans a save state: the DMA runs to completion in one go
    }

    // ------------------------------------------------------------------------------------------ decoder

    /** Probability estimator states: {code number, next state after a most-probable symbol, after a least-probable one}. */
    private static final int[][] EVOLUTION = {
        {0, 25, 25}, {0, 2, 1}, {0, 3, 1}, {0, 4, 2}, {0, 5, 3}, {1, 6, 4}, {1, 7, 5}, {1, 8, 6}, {1, 9, 7},
        {2, 10, 8}, {2, 11, 9}, {2, 12, 10}, {2, 13, 11}, {3, 14, 12}, {3, 15, 13}, {3, 16, 14}, {3, 17, 15},
        {4, 18, 16}, {4, 19, 17}, {5, 20, 18}, {5, 21, 19}, {6, 22, 20}, {6, 23, 21}, {7, 24, 22}, {7, 24, 23},
        {0, 26, 1}, {1, 27, 2}, {2, 28, 4}, {3, 29, 8}, {4, 30, 12}, {5, 31, 16}, {6, 32, 18}, {7, 24, 22},
    };

    /**
     * Golomb run lengths: for a code word whose leading bit is 1, the bits after it (as an index) give the number of
     * most-probable symbols before the least-probable one. Within each block [2^m, 2^(m+1)) the value is the
     * bit-reversal of the complemented low m bits.
     */
    private static final int[] RUN = new int[256];
    static {
        for (int i = 1; i < 256; i++) {
            int m = 31 - Integer.numberOfLeadingZeros(i), v = ~i & ((1 << m) - 1), r = 0;
            for (int b = 0; b < m; b++) if ((v & (1 << b)) != 0) r |= 1 << (m - 1 - b);
            RUN[i] = r;
        }
    }

    private final class Decoder {
        // input manager
        private int offset, bitCount;
        // bit generators, one per code number
        private final int[] mpsCount = new int[8];
        private final boolean[] lpsPending = new boolean[8];
        // probability estimation, 32 contexts
        private final int[] status = new int[32], mps = new int[32];
        // context model + output logic
        private int planesInfo, contextInfo, bitNumber, plane, r0, r1, r2;
        private final int[] history = new int[8];

        void start(int addr) {
            offset = addr; bitCount = 4;
            java.util.Arrays.fill(mpsCount, 0); java.util.Arrays.fill(lpsPending, false);
            java.util.Arrays.fill(status, 0); java.util.Arrays.fill(mps, 0);
            int head = mmc(addr);
            planesInfo = head & 0xC0; contextInfo = head & 0x30;
            bitNumber = 0; java.util.Arrays.fill(history, 0);
            plane = switch (planesInfo) { case 0x00 -> 1; case 0x40 -> 7; case 0x80 -> 3; default -> 0; };
            r0 = 1;
        }

        private int codeWord(int codeLength) {
            int word = (mmc(offset) << bitCount) & 0xFF;
            bitCount++;
            if ((word & 0x80) != 0) {
                word |= mmc(offset + 1) >> (9 - bitCount);
                bitCount += codeLength;
            }
            if ((bitCount & 8) != 0) { offset++; bitCount &= 7; }
            return word & 0xFF;
        }

        /** Next bit of the run-length stream for this code number; the flag tells if the run just ended. */
        private int runBit(int code, boolean[] endOfRun) {
            if (mpsCount[code] == 0 && !lpsPending[code]) {
                int word = codeWord(code);
                if ((word & 0x80) != 0) { lpsPending[code] = true; mpsCount[code] = RUN[word >> (code ^ 7)]; }
                else mpsCount[code] = 1 << code;
            }
            int bit;
            if (mpsCount[code] != 0) { bit = 0; mpsCount[code]--; }
            else { bit = 1; lpsPending[code] = false; }
            endOfRun[0] = mpsCount[code] == 0 && !lpsPending[code];
            return bit;
        }

        private final boolean[] end = new boolean[1];

        private int estimate(int ctx) {
            int st = status[ctx], m = mps[ctx];
            int[] s = EVOLUTION[st];
            int bit = runBit(s[0], end);
            if (end[0]) {
                if (bit != 0) {
                    if ((st & 0xFE) == 0) mps[ctx] ^= 1;
                    status[ctx] = s[2];
                } else status[ctx] = s[1];
            }
            return bit ^ m;
        }

        private int modelBit() {
            switch (planesInfo) {
                case 0x00 -> plane ^= 1;
                case 0x40 -> { plane ^= 1; if ((bitNumber & 0x7F) == 0) plane = (plane + 2) & 7; }
                case 0x80 -> { plane ^= 1; if ((bitNumber & 0x7F) == 0) plane ^= 2; }
                default -> plane = bitNumber & 7;
            }
            int bits = history[plane];
            int ctx = (plane & 1) << 4;
            switch (contextInfo) {
                case 0x00 -> ctx |= ((bits & 0x1C0) >> 5) | (bits & 1);
                case 0x10 -> ctx |= ((bits & 0x180) >> 5) | (bits & 1);
                case 0x20 -> ctx |= ((bits & 0x0C0) >> 5) | (bits & 1);
                default -> ctx |= ((bits & 0x180) >> 5) | (bits & 3);
            }
            int bit = estimate(ctx);
            history[plane] = ((bits << 1) | bit) & 0xFFFF;
            bitNumber++;
            return bit;
        }

        int next() {
            if (planesInfo == 0xC0) {                                // 8 bpp: one plane per bit
                r1 = 0;
                for (r0 = 1; r0 < 0x100; r0 <<= 1) if (modelBit() != 0) r1 |= r0;
                return r1;
            }
            if (r0 == 0) { r0 = 0xFF; return r2; }                   // second byte of the pair produced last time
            r1 = 0; r2 = 0;
            for (r0 = 0x80; r0 != 0; r0 >>= 1) {
                if (modelBit() != 0) r1 |= r0;
                if (modelBit() != 0) r2 |= r0;
            }
            return r1;
        }
    }
}
