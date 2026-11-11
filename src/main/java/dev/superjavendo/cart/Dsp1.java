package dev.superjavendo.cart;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;

import java.io.IOException;

/**
 * DSP-1 / DSP-1A / DSP-1B (Super Mario Kart, Pilotwings, ...) as a high-level emulation.
 *
 * <p>Protocol: the CPU writes a command byte to the data register (DR), then the input words (16 bit, little endian,
 * byte by byte); after the last input byte the chip computes the result, which the CPU reads back from DR, again
 * byte by byte. The status register (SR) always reads {@code $80} (request for master).
 *
 * <p>All commands of the chip are implemented and checked bit-for-bit with random vectors against two independent
 * oracles, snes9x and bsnes ({@code tools/chiptests}): Multiply, Multiply2, Inverse, Triangle, Radius, Range, Range2,
 * Distance, Rotate, Polar, Attitude/Objective/Subjective/Scalar (matrices A, B, C), Gyrate, and the 3D projection
 * (Parameter, Raster in its continuous mode, Target, Project). Where the two oracles disagree (Multiply2, Distance,
 * Gyrate) bsnes is followed. Memory Dump returns zeros: the chip's ROM table is not modelled.
 * Written from the documented behaviour; the numeric tables were measured on the oracles.
 */
public final class Dsp1 implements Coprocessor {
    /** Sine table, 128 entries: floor(32768 sin(2 pi i / 256)), clamped; the second half is the negation. */
    private static final int[] SIN = new int[512];
    /** Interpolation slope table measured on the real chip (index = low byte of the angle). */
    private static final int[] MUL = {
        0, 3, 6, 9, 12, 15, 18, 21, 25, 28, 31, 34, 37, 40, 43, 47, 50, 53, 56, 59, 62, 65, 69, 72, 75, 78, 81, 84, 87, 91, 94, 97,
        100, 103, 106, 109, 113, 116, 119, 122, 125, 128, 131, 135, 138, 141, 144, 147, 150, 153, 157, 160, 163, 166, 169, 172, 175, 179, 182, 185, 188, 191, 194, 197,
        201, 204, 207, 210, 213, 216, 219, 223, 226, 229, 232, 235, 238, 241, 245, 248, 251, 254, 257, 260, 263, 267, 270, 273, 276, 279, 282, 285, 289, 292, 295, 298,
        301, 304, 307, 311, 314, 317, 320, 323, 326, 329, 333, 336, 339, 342, 345, 348, 351, 355, 358, 361, 364, 367, 370, 373, 376, 380, 383, 386, 389, 392, 395, 398,
        402, 405, 408, 411, 414, 417, 420, 424, 427, 430, 433, 436, 439, 442, 446, 449, 452, 455, 458, 461, 464, 468, 471, 474, 477, 480, 483, 486, 490, 493, 496, 499,
        502, 505, 508, 512, 515, 518, 521, 524, 527, 530, 534, 537, 540, 543, 546, 549, 552, 556, 559, 562, 565, 568, 571, 574, 578, 581, 584, 587, 590, 593, 596, 600,
        603, 606, 609, 612, 615, 618, 622, 625, 628, 631, 634, 637, 640, 644, 647, 650, 653, 656, 659, 662, 666, 669, 672, 675, 678, 681, 684, 688, 691, 694, 697, 700,
        703, 706, 710, 713, 716, 719, 722, 725, 728, 731, 735, 738, 741, 744, 747, 750, 753, 757, 760, 763, 766, 769, 772, 775, 779, 782, 785, 788, 791, 794, 797, 801 };
    /** Square-root nodes for Distance, indexed by (mantissa >> 9) - 16: about 4096 sqrt(i), measured on the oracle. */
    private static final int[] SQRT = {
        16383, 16887, 17377, 17853, 18317, 18769, 19211, 19643, 20065, 20479, 20884, 21282, 21673, 22056, 22434, 22804, 23169,
        23529, 23882, 24231, 24575, 24914, 25248, 25578, 25904, 26226, 26544, 26858, 27168, 27475, 27779, 28079, 28377, 28671,
        28962, 29250, 29535, 29818, 30098, 30375, 30650, 30923, 31193, 31461, 31726, 31989, 32250, 32510, 32767 };
    /** Initial guesses for the reciprocal (Newton iteration), one per 128 coefficient values from $4000. */
    private static final int[] INV = new int[128];

    static {
        for (int i = 0; i < 128; i++) {
            int v = (int) Math.min(32767, Math.floor(32768 * Math.sin(2 * Math.PI * i / 256)));
            SIN[i] = SIN[i + 256] = v;
            SIN[i + 128] = SIN[i + 384] = -v;
        }
        for (int k = 0; k < 128; k++) INV[k] = (int) Math.min(32767, Math.round(Math.pow(2, 29) / (0x4000 + 128 * k)));
    }

    private final int[] matA = new int[9], matB = new int[9], matC = new int[9];
    // Bus protocol: DR is a 16 bit register seen one byte at a time (drs selects the byte; it flips on every access
    // while a command is transferring data), and the chip is a three-state machine. Every access counts, whether it is a
    // read or a write: a game that writes $8000 words to end the raster stream relies on this, and so do many
    // "dummy" writes made while results are pending.
    private static final int WAIT_COMMAND = 0, READ_DATA = 1, WRITE_DATA = 2;
    private int state = WAIT_COMMAND, counter;
    private int dr = 0x0080;              // the 16 bit data register
    private boolean drs, frozen;
    private int command;                  // opcode being processed
    private final int[] in = new int[8];  // input words
    private final int[] out = new int[1024]; // output words
    private int outWords;

    public Dsp1() { this(false); }

    public Dsp1(boolean hiRom) { this.hiRom = hiRom; }

    /** LoROM boards (Pilotwings, ...): DR / SR in banks $30-$3F and $B0-$BF at $8000-$BFFF / $C000-$FFFF (ROM up to 1 MB).
     *  HiROM boards (Super Mario Kart): DR / SR in banks $00-$1F and $80-$9F at $6000-$6FFF / $7000-$7FFF. */
    private final boolean hiRom;

    private boolean isDr(int bank, int off) {
        int b = bank & 0x7F;
        return hiRom ? b < 0x20 && off >= 0x6000 && off < 0x7000 : b >= 0x30 && b <= 0x3F && off >= 0x8000 && off < 0xC000;
    }

    private boolean isSr(int bank, int off) {
        int b = bank & 0x7F;
        return hiRom ? b < 0x20 && off >= 0x7000 && off < 0x8000 : b >= 0x30 && b <= 0x3F && off >= 0xC000;
    }

    // diagnostic: -Ddsp1log=FILE records every DR access as (type, value) byte pairs: 0 = write, 1 = read
    private static final java.io.OutputStream LOG = openLog();
    private static java.io.OutputStream openLog() {
        String f = System.getProperty("dsp1log");
        if (f == null) return null;
        try {
            java.io.OutputStream o = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { o.close(); } catch (IOException ignored) { } }));
            return o;
        } catch (IOException e) { return null; }
    }
    private static void log(int type, int v) { if (LOG != null) try { LOG.write(type); LOG.write(v); } catch (IOException ignored) { } }

    @Override public int read(int bank, int off) {
        bank &= 0xFF;
        if (isSr(bank, off)) return frozen ? 0 : 0x80;
        if (!isDr(bank, off)) return -1;
        int v = access(true, 0);
        log(1, v);
        return v;
    }

    @Override public boolean write(int bank, int off, int value) {
        bank &= 0xFF;
        if (isSr(bank, off)) return true;
        if (!isDr(bank, off)) return false;
        value &= 0xFF;
        log(0, value);
        access(false, value);
        return true;
    }

    /** One byte access to DR; returns the byte seen by a read. */
    private int access(boolean read, int data) {
        if (frozen) return 0;
        int result = 0;
        if (read) result = drs ? (dr >> 8) & 0xFF : dr & 0xFF;
        else dr = drs ? (dr & 0x00FF) | data << 8 : (dr & 0xFF00) | data;
        switch (state) {
            case WAIT_COMMAND:
                command = dr & 0xFF;
                if ((command & 0xC0) == 0) {                         // valid opcode
                    if (command == 0x1A || command == 0x2A || command == 0x3A) frozen = true;   // these hang the chip
                    else { counter = 0; state = READ_DATA; }
                }
                break;
            case READ_DATA:
                drs = !drs;
                if (!drs) {                                           // a whole word has been transferred
                    in[counter++] = (short) dr;
                    if (counter >= READS[command]) {
                        outWords = execute();
                        if (outWords != 0) { counter = 0; dr = out[0] & 0xFFFF; state = WRITE_DATA; }
                        else { dr = 0x0080; state = WAIT_COMMAND; }
                    }
                }
                break;
            default:                                                  // WRITE_DATA
                drs = !drs;
                if (!drs && ++counter >= outWords) {
                    if (KIND[command] == RASTER && dr != 0x8000) {   // continuous mode: next line unless the CPU wrote $8000
                        in[0] = (short) (in[0] + 1);
                        outWords = execute(); counter = 0; dr = out[0] & 0xFFFF;
                    } else { dr = 0x0080; state = WAIT_COMMAND; }
                } else if (!drs) dr = out[counter] & 0xFFFF;
        }
        return result;
    }

    // Command ids; the opcode space ($00-$3F) holds several aliases and variants per command.
    private static final int MULT = 1, MULT2 = 2, INVERT = 3, TRI = 4, RADIUS = 5, RANGE = 6, RANGE2 = 7, DIST = 8, ROT = 9,
        POLAR = 10, MEMTEST = 11, MEMSIZE = 12, MEMDUMP = 13, ATTITUDE = 14, OBJECTIVE = 15, SUBJECTIVE = 16, SCALAR = 17,
        PARAMETER = 18, RASTER = 19, TARGET = 20, PROJECT = 21, GYRATE = 22;
    /** For each opcode: command id (0 = not implemented) and number of input words. */
    private static final int[] KIND = new int[64], READS = new int[64], WRITES = new int[64];

    private static void def(int id, int reads, int writes, int... opcodes) {
        for (int op : opcodes) { KIND[op] = id; READS[op] = reads; WRITES[op] = writes; }
    }

    static {
        def(MULT, 2, 1, 0x00);              def(MULT2, 2, 1, 0x20);
        def(INVERT, 2, 2, 0x10, 0x30);         def(TRI, 2, 2, 0x04, 0x24);
        def(RADIUS, 3, 2, 0x08);            def(DIST, 3, 1, 0x28);
        def(RANGE, 4, 1, 0x18);             def(RANGE2, 4, 1, 0x38);
        def(ROT, 3, 2, 0x0C, 0x2C);         def(POLAR, 6, 3, 0x1C, 0x3C);
        def(MEMTEST, 1, 1, 0x07, 0x0F);     def(MEMSIZE, 1, 1, 0x27, 0x2F);
        def(MEMDUMP, 1, 1024, 0x17, 0x1F, 0x37, 0x3F);
        // The three attitude matrices (A, B, C) are selected by the opcode; each opcode of a family has aliases.
        def(ATTITUDE, 4, 0, 0x01, 0x05, 0x31, 0x35, 0x11, 0x15, 0x21, 0x25);
        def(OBJECTIVE, 3, 3, 0x09, 0x0D, 0x39, 0x3D, 0x19, 0x1D, 0x29, 0x2D);
        def(SUBJECTIVE, 3, 3, 0x03, 0x33, 0x13, 0x23);
        def(SCALAR, 3, 1, 0x0B, 0x3B, 0x1B, 0x2B);
        def(PARAMETER, 7, 4, 0x02, 0x12, 0x22, 0x32);  def(RASTER, 1, 4, 0x0A);
        def(TARGET, 2, 2, 0x0E, 0x1E, 0x2E, 0x3E);      def(PROJECT, 3, 3, 0x06, 0x16, 0x26, 0x36);
        def(GYRATE, 6, 3, 0x14, 0x34);
    }

    /** Runs the pending command; returns the number of result words (stored in {@link #out}). */
    private int execute() {
        switch (KIND[command]) {
            case MULT: out[0] = (in[0] * in[1]) >> 15; return 1;
            case MULT2: out[0] = ((in[0] * in[1]) >> 15) + 1; return 1;
            case INVERT: inverse(in[0], in[1]); return 2;
            case TRI: out[0] = (sin(in[0]) * in[1]) >> 15; out[1] = (cos(in[0]) * in[1]) >> 15; return 2;
            case RADIUS: {
                int r = (in[0] * in[0] + in[1] * in[1] + in[2] * in[2]) << 1;
                out[0] = r & 0xFFFF; out[1] = (r >> 16) & 0xFFFF;
                return 2;
            }
            case RANGE: out[0] = (in[0] * in[0] + in[1] * in[1] + in[2] * in[2] - in[3] * in[3]) >> 15; return 1;
            case RANGE2: out[0] = ((in[0] * in[0] + in[1] * in[1] + in[2] * in[2] - in[3] * in[3]) >> 15) + 1; return 1;
            case DIST: out[0] = distance(in[0], in[1], in[2]); return 1;
            case ROT: {
                int a = in[0], x = in[1], y = in[2];
                out[0] = ((y * sin(a)) >> 15) + ((x * cos(a)) >> 15);
                out[1] = ((y * cos(a)) >> 15) - ((x * sin(a)) >> 15);
                return 2;
            }
            case POLAR: polar(); return 3;
            case ATTITUDE: attitude(matrix()); return 0;
            case OBJECTIVE: {          // global -> object coordinates: multiply by the transposed matrix
                int[] m = matrix();
                for (int c = 0; c < 3; c++)
                    out[c] = ((m[c] * in[0]) >> 15) + ((m[3 + c] * in[1]) >> 15) + ((m[6 + c] * in[2]) >> 15);
                return 3;
            }
            case SUBJECTIVE: {         // object -> global coordinates
                int[] m = matrix();
                for (int r = 0; r < 3; r++)
                    out[r] = ((m[3 * r] * in[0]) >> 15) + ((m[3 * r + 1] * in[1]) >> 15) + ((m[3 * r + 2] * in[2]) >> 15);
                return 3;
            }
            case PARAMETER: parameter(); return 4;
            case RASTER: raster(in[0]); return 4;
            case TARGET: target(in[0], in[1]); return 2;
            case PROJECT: project(in[0], in[1], in[2]); return 3;
            case GYRATE: gyrate(); return 3;
            case SCALAR: { int[] m = matrix(); out[0] = (in[0] * m[0] + in[1] * m[3] + in[2] * m[6]) >> 15; return 1; }
            case MEMTEST: out[0] = 0; return 1;
            case MEMSIZE: out[0] = 0x0100; return 1;
            case MEMDUMP: java.util.Arrays.fill(out, 0); return out.length;   // the ROM table is not modelled
            default: return 0;
        }
    }

    /** Matrix selected by the opcode family: bits 4-5 of the opcode pick A (0 or 3), B (1) or C (2). */
    private int[] matrix() { int f = (command >> 4) & 3; return f == 1 ? matB : f == 2 ? matC : matA; }

    private static int q(int a, int b) { return (a * b) >> 15; }

    /** Builds the scaled attitude matrix from (scale, Rz, Ry, Rx) = in[0..3]; the three rotations are applied in the
     *  order the chip uses (which is not the order its documentation gives). Entries are 16 bit. */
    private void attitude(int[] m) {
        int s = in[0] >> 1, sz = sin(in[1]), cz = cos(in[1]), sy = sin(in[2]), cy = cos(in[2]), sx = sin(in[3]), cx = cos(in[3]);
        int a = q(s, cz), b = q(s, sz);          // scale folded into the Z rotation
        m[0] = (short) q(a, cy);
        m[1] = (short) (q(b, cx) + q(q(a, sx), sy));
        m[2] = (short) (q(b, sx) - q(q(a, cx), sy));
        m[3] = (short) -q(b, cy);
        m[4] = (short) (q(a, cx) - q(q(b, sx), sy));
        m[5] = (short) (q(a, sx) + q(q(b, cx), sy));
        m[6] = (short) q(s, sy);
        m[7] = (short) -q(q(s, sx), cy);
        m[8] = (short) q(q(s, cx), cy);
    }

    /** Polar: rotates the point (x, y, z) around Z, then Y, then X; inputs are Az, Ay, Ax, x, y, z. */
    private void polar() {
        int az = in[0], ay = in[1], ax = in[2], x = in[3], y = in[4], z = in[5];
        int x1 = (short) (((y * sin(az)) >> 15) + ((x * cos(az)) >> 15));       // around Z (16 bit registers)
        int y1 = (short) (((y * cos(az)) >> 15) - ((x * sin(az)) >> 15));
        int z1 = (short) (((x1 * sin(ay)) >> 15) + ((z * cos(ay)) >> 15));      // around Y
        out[0] = (short) (((x1 * cos(ay)) >> 15) - ((z * sin(ay)) >> 15));
        out[1] = (short) (((z1 * sin(ax)) >> 15) + ((y1 * cos(ax)) >> 15));     // around X
        out[2] = (short) (((z1 * cos(ax)) >> 15) - ((y1 * sin(ax)) >> 15));
    }

    // ------------------------------------------------------------------ math

    static int sin(int angle) {
        if (angle < 0) return angle == -32768 ? 0 : -sin(-angle);
        int s = SIN[angle >> 8] + ((MUL[angle & 0xFF] * SIN[0x40 + (angle >> 8)]) >> 15);
        return Math.min(s, 32767);
    }

    static int cos(int angle) {
        if (angle < 0) { if (angle == -32768) return -32768; angle = -angle; }
        int s = SIN[0x40 + (angle >> 8)] - ((MUL[angle & 0xFF] * SIN[angle >> 8]) >> 15);
        return s < -32768 ? -32767 : s;
    }

    /** Distance: square root of x*x+y*y+z*z by normalising to a 15 bit mantissa and interpolating between table nodes. */
    static int distance(int x, int y, int z) {
        int r = x * x + y * y + z * z;
        if (r == 0) return 0;
        // Radius >= 2^30 (a distance above 32767) is outside the chip's valid range: the real result is a wrapped
        // value that we do not model, a plain square root keeps it sane.
        if (r < 0 || r >= 0x40000000) return (int) Math.sqrt((double) (r & 0xFFFFFFFFL)) & 0x7FFF;
        int e = 0;
        while ((r & (0x20000000 >> e)) == 0 && e < 30) e++;
        int c = (r << e) >> 15;
        if ((e & 1) != 0) c >>= 1;
        int pos = (c >> 9) - 16;
        int n1 = SQRT[pos], n2 = SQRT[pos + 1];
        int d = (((n2 - n1) * (c & 0x1FF)) >> 9) + n1;
        return d >> (e >> 1);
    }

    private int invC, invE;           // results of invert()
    private int nC, nE;               // results of the normalisation helpers

    private void inverse(int coefficient, int exponent) {
        invert(coefficient, exponent);
        out[0] = (short) invC;
        out[1] = (short) invE;
    }

    /** 1/(coefficient * 2^exponent) as invC * 2^invE, invC normalised to [0.5, 1). */
    private void invert(int coefficient, int exponent) {
        if (coefficient == 0) { invC = 0x7FFF; invE = 0x2F; return; }
        int sign = 1;
        if (coefficient < 0) { if (coefficient < -32767) coefficient = -32767; coefficient = -coefficient; sign = -1; }
        while (coefficient < 0x4000) { coefficient <<= 1; exponent--; }
        int ic;
        if (coefficient == 0x4000) {
            if (sign == 1) ic = 0x7FFF; else { ic = -0x4000; exponent--; }
        } else {
            int i = INV[(coefficient - 0x4000) >> 7];
            for (int n = 0; n < 2; n++) i = (short) ((i + ((-i * ((coefficient * i) >> 15)) >> 15)) << 1);
            ic = i * sign;
        }
        invC = (short) ic;
        invE = (short) (1 - exponent);
    }

    private static int s16(int v) { return (short) v; }

    /** Left-normalises a 16 bit value so that bit 14 differs from the sign; nE = exp - shifts. */
    private void normalize(int m, int exp) {
        m = (short) m;
        int e = 0;
        if (m < 0) { for (int i = 0x4000; i != 0 && (m & i) != 0; i >>= 1) e++; }
        else { for (int i = 0x4000; i != 0 && (m & i) == 0; i >>= 1) e++; }
        nC = e > 0 ? s16(m << e) : m;
        nE = exp - e;
    }

    /** Same for a 32 bit value: the mantissa keeps 16 significant bits; nE = number of left shifts. */
    private void normalizeDouble(int p) {
        int n = p & 0x7FFF, m = (short) (p >> 15), e = 0;
        if (m < 0) { for (int i = 0x4000; i != 0 && (m & i) != 0; i >>= 1) e++; }
        else { for (int i = 0x4000; i != 0 && (m & i) == 0; i >>= 1) e++; }
        int c = m;
        if (e > 0) {
            c = s16(m << e);
            if (e < 15) c = s16(c + (n >> (15 - e)));
            else {                                              // the high half is empty: use the low half
                for (int i = 0x4000; i != 0 && (m < 0 ? (n & i) != 0 : (n & i) == 0); i >>= 1) e++;
                c = e > 15 ? s16(n << (e - 15)) : s16(c + n);
            }
        }
        nC = c;
        nE = e;
    }

    /** Scales c by 2^e (e positive: saturates, as the chip does); the chip does it with a power-of-two table. */
    private static int denormalize(int c, int e) {
        if (e > 0) return c > 0 ? 32767 : c < 0 ? -32767 : c;
        if (e < 0) return e < -15 ? 0 : c >> -e;
        return c;
    }

    /** c / 2^e with the chip's table lookup: e == 0 multiplies by $7FFF instead of 1. */
    private static int shiftRight(int c, int e) { return e == 0 ? (c * 0x7FFF) >> 15 : e <= 15 ? c >> e : 0; }

    // ------------------------------------------------------------------ 3D projection (Parameter / Raster / Target / Project)

    private static final int LES = 0, LES_C = 1, LES_E = 2, SIN_AAS = 3, COS_AAS = 4, SIN_AZS = 5, COS_AZS = 6, SIN_Z = 7, COS_Z = 8,
        SEC_C1 = 9, SEC_E1 = 10, SEC_C2 = 11, SEC_E2 = 12, NX = 13, NY = 14, NZ = 15, GX = 16, GY = 17, GZ = 18, HX = 19, HY = 20,
        VX = 21, VY = 22, VZ = 23, V_OFFSET = 24, CEN_X = 25, CEN_Y = 26, CEN_Z_C = 27, CEN_Z_E = 28;
    private final int[] sh = new int[32];

    /** Largest usable zenith angle for each exponent of the (normalised) height of the centre of projection. */
    /* measured on the oracle: the smallest zenith angle for which Parameter reports a correction */
    private static final int[] MAX_AZS = {
        0x38B4, 0x38B7, 0x38BA, 0x38BE, 0x38C0, 0x38C4, 0x38C7, 0x38CA, 0x38CE, 0x38D0, 0x38D4, 0x38D7, 0x38DA, 0x38DD, 0x38E0, 0x38E4 };
    private static final int TAYLOR_TAN1 = 25736, TAYLOR_TAN3 = 5292, TAYLOR_COS2 = 10106, TAYLOR_COS4 = 2598;   // pi/4 based series

    /** Sets up the projection: base point (Fx, Fy, Fz), distances Lfe (to the viewpoint) and Les (to the screen),
     *  azimuth and zenith angles. Outputs: raster line of the centre, horizon line and ground point of the screen centre. */
    private void parameter() {
        int fx = in[0], fy = in[1], fz = in[2], lfe = in[3], les = in[4], aas = in[5], azs = in[6];
        int clip = azs;
        sh[LES] = les;
        normalize(les, 0);
        sh[LES_C] = nC; sh[LES_E] = nE;
        int sa = sin(aas), ca = cos(aas), sz = sin(azs), cz = cos(azs);
        sh[SIN_AAS] = sa; sh[COS_AAS] = ca; sh[SIN_AZS] = sz; sh[COS_AZS] = cz;
        int nx = s16((sz * -sa) >> 15), ny = s16((sz * ca) >> 15), nz = s16((cz * 0x7FFF) >> 15);
        sh[NX] = nx; sh[NY] = ny; sh[NZ] = nz;
        sh[HX] = s16((ca * 0x7FFF) >> 15); sh[HY] = s16((sa * 0x7FFF) >> 15);
        sh[VX] = s16((cz * -sa) >> 15); sh[VY] = s16((cz * ca) >> 15); sh[VZ] = s16((-sz * 0x7FFF) >> 15);
        int cenX = s16(fx + s16((lfe * nx) >> 15)), cenY = s16(fy + s16((lfe * ny) >> 15)), cenZ = s16(fz + s16((lfe * nz) >> 15));
        sh[GX] = s16(cenX - s16((les * nx) >> 15)); sh[GY] = s16(cenY - s16((les * ny) >> 15)); sh[GZ] = s16(cenZ - s16((les * nz) >> 15));
        normalize(cenZ, 0);
        int c = nC, e = nE;
        sh[CEN_Z_C] = c; sh[CEN_Z_E] = e;
        int maxAzs = MAX_AZS[-e];
        if (clip < 0) { maxAzs = -maxAzs; if (clip < maxAzs + 1) clip = maxAzs + 1; }
        else if (clip > maxAzs) clip = maxAzs;
        int sinZ = sin(clip), cosZ = cos(clip);
        sh[SIN_Z] = sinZ;
        invert(cosZ, 0);
        sh[SEC_C1] = invC; sh[SEC_E1] = invE;
        normalize((c * invC) >> 15, e);
        c = nC; e = nE + sh[SEC_E1];
        c = s16((denormalize(c, e) * sinZ) >> 15);                  // separation of the screen centre from the viewpoint's ground point
        cenX = s16(cenX + ((c * sa) >> 15));
        cenY = s16(cenY - ((c * ca) >> 15));
        sh[CEN_X] = cenX; sh[CEN_Y] = cenY;
        int vof = 0;
        if (azs != clip || azs == maxAzs) {                          // zenith outside the non-clipping interval: correct vof and cos
            if (azs == -32768) azs = -32767;
            c = azs - maxAzs;
            if (c >= 0) c--;
            int aux = s16(~(c << 2));
            c = s16((aux * TAYLOR_TAN3) >> 15);
            c = s16(((c * aux) >> 15) + TAYLOR_TAN1);
            vof = s16(vof - s16((((c * aux) >> 15) * les) >> 15));
            c = s16((aux * aux) >> 15);
            aux = s16(((c * TAYLOR_COS4) >> 15) + TAYLOR_COS2);
            cosZ = s16(cosZ + s16((((c * aux) >> 15) * cosZ) >> 15));
        }
        sh[COS_Z] = cosZ;
        int vOffset = s16((les * cosZ) >> 15);
        sh[V_OFFSET] = vOffset;
        invert(sinZ, 0);
        int cSec = invC;
        normalize(vOffset, invE);
        c = nC; e = nE;
        normalize((c * cSec) >> 15, e);
        c = nC; e = nE;
        if (c == -32768) { c >>= 1; e++; }
        int vva = s16(denormalize(-c, e));
        invert(cosZ, 0);
        sh[SEC_C2] = invC; sh[SEC_E2] = invE;
        out[0] = vof; out[1] = vva; out[2] = cenX; out[3] = cenY;
    }

    /** Projection matrix of a raster line vs: how a unit of the screen maps onto the ground (a b c d). */
    private void raster(int vs) {
        invert(s16(((vs * sh[SIN_AZS]) >> 15) + sh[V_OFFSET]), 7);
        int c = invC, e = s16(invE + sh[CEN_Z_E]);
        int c1 = s16((c * sh[CEN_Z_C]) >> 15), e1 = s16(e + sh[SEC_E2]);
        normalize(c1, e);
        c = s16(denormalize(nC, nE));
        out[0] = s16((c * sh[COS_AAS]) >> 15);
        out[2] = s16((c * sh[SIN_AAS]) >> 15);
        normalize(s16((c1 * sh[SEC_C2]) >> 15), e1);
        c = s16(denormalize(nC, nE));
        out[1] = s16((c * -sh[SIN_AAS]) >> 15);
        out[3] = s16((c * sh[COS_AAS]) >> 15);
    }

    /** Ground point under the screen position (h, v). */
    private void target(int h, int v) {
        invert(s16(((v * sh[SIN_AZS]) >> 15) + sh[V_OFFSET]), 8);
        int c = invC, e = s16(invE + sh[CEN_Z_E]);
        int c1 = s16((c * sh[CEN_Z_C]) >> 15), e1 = s16(e + sh[SEC_E1]);
        h = s16(h << 8);
        normalize(c1, e);
        c = s16((denormalize(nC, nE) * h) >> 15);
        int x = s16(sh[CEN_X] + ((c * sh[COS_AAS]) >> 15));
        int y = s16(sh[CEN_Y] - ((c * sh[SIN_AAS]) >> 15));
        v = s16(v << 8);
        normalize(s16((c1 * sh[SEC_C1]) >> 15), e1);
        c = s16((denormalize(nC, nE) * v) >> 15);
        out[0] = s16(x + ((c * -sh[SIN_AAS]) >> 15));
        out[1] = s16(y + ((c * sh[COS_AAS]) >> 15));
    }

    /** Screen position (h, v) and size ratio m of the point (x, y, z). */
    private void project(int x, int y, int z) {
        normalizeDouble(x - sh[GX]); int px = nC, ex = nE;
        normalizeDouble(y - sh[GY]); int py = nC, ey = nE;
        normalizeDouble(z - sh[GZ]); int pz = nC, ez = nE;
        px >>= 1; ex--; py >>= 1; ey--; pz >>= 1; ez--;          // headroom for the dot products
        int ref = Math.min(Math.min(ey, ez), ex);
        px = s16(shiftRight(px, ex - ref)); py = s16(shiftRight(py, ey - ref)); pz = s16(shiftRight(pz, ez - ref));
        int dot = s16(-((px * sh[NX]) >> 15) - ((py * sh[NY]) >> 15) - ((pz * sh[NZ]) >> 15));
        int d32 = dot;
        ref = 16 - ref;
        if (ref >= 0) d32 <<= ref; else d32 >>= -ref;
        if (d32 == -1) d32 = 0;
        d32 >>= 1;
        normalizeDouble((sh[LES] & 0xFFFF) + d32);
        int c10 = nC, e2 = 15 - nE;
        invert(c10, 0);
        int e4 = invE;
        int scale = s16((invC * sh[LES_C]) >> 15);
        int hDot = s16(s16((px * sh[HX]) >> 15) + s16((py * sh[HY]) >> 15));
        normalize(s16((hDot * scale) >> 15), 0);
        out[0] = s16(denormalize(nC, s16(sh[LES_E] - e2 + ref + nE)));
        int vDot = s16(s16((px * sh[VX]) >> 15) + s16((py * sh[VY]) >> 15) + s16((pz * sh[VZ]) >> 15));
        normalize(s16((vDot * scale) >> 15), 0);
        out[1] = s16(denormalize(nC, s16(sh[LES_E] - e2 + ref + nE)));
        normalize(scale, e4);
        out[2] = s16(denormalize(nC, s16(nE + sh[LES_E] - e2 - 7)));    // size ratio, scaled down by 2^7
    }

    /** Gyrate: new attitude angles after small rotations (u, f, l) of a body with attitude (az, ax, ay). */
    private void gyrate() {
        int az = in[0], ax = in[1], ay = in[2], u = in[3], f = in[4], l = in[5];
        int sinAy = sin(ay), cosAy = cos(ay);
        invert(cos(ax), 0);
        int cSec = invC, eSec = invE;
        normalizeDouble(u * cosAy - f * sinAy);
        int c = nC, e = s16(eSec - nE);
        normalize(s16((c * cSec) >> 15), e);
        out[0] = s16(az + denormalize(nC, nE));
        out[1] = s16(ax + ((u * sinAy) >> 15) + ((f * cosAy) >> 15));
        normalizeDouble(u * cosAy + f * sinAy);
        c = nC; e = s16(eSec - nE);
        normalize(sin(ax), e);
        int cSin = nC; e = nE;
        normalize(s16(-((c * ((cSec * cSin) >> 15)) >> 15)), e);
        out[2] = s16(ay + denormalize(nC, nE) + l);
    }

    // ------------------------------------------------------------------ save state

    @Override public void save(StateOut o) throws IOException {
        o.ints(new int[]{command, state, counter, dr, drs ? 1 : 0, frozen ? 1 : 0, outWords});
        o.ints(in);
        o.ints(out);
        o.ints(matA); o.ints(matB); o.ints(matC); o.ints(sh); 
    }

    @Override public void load(StateIn s) throws IOException {
        int[] v = new int[7];
        s.ints(v);
        command = v[0]; state = v[1]; counter = v[2]; dr = v[3]; drs = v[4] != 0; frozen = v[5] != 0; outWords = v[6];
        s.ints(in);
        s.ints(out);
        s.ints(matA); s.ints(matB); s.ints(matC); s.ints(sh);
    }
}
