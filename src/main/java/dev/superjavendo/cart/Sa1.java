package dev.superjavendo.cart;

import dev.superjavendo.bus.Memory;
import dev.superjavendo.cpu.Cpu65816;
import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;

import java.io.IOException;

/**
 * SA-1 (Super Mario RPG, Kirby Super Star, Kirby's Dream Land 3): a second 65816 running at 10.74 MHz next to the
 * S-CPU, with 2 KB of internal RAM (I-RAM), access to the cartridge BW-RAM, a bank-switching ROM mapper, DMA with
 * bitmap-to-tile conversion, a multiplier/divider and a variable-length bit reader.
 *
 * <p>Both processors see the same ROM and BW-RAM but through different address maps; the SA-1 also owns the vector
 * registers ({@code $2203-$2208}) and can override the S-CPU NMI/IRQ vectors. The two CPUs are interleaved at
 * instruction granularity; bus-arbitration wait states are approximated (see {@link #BW_PENALTY}).
 *
 * <p>Written from the documented register behaviour and checked against snes9x on test cartridges.
 */
public final class Sa1 implements Coprocessor {
    private static final int BW_PENALTY = 1;     // extra SA-1 cycles for a BW-RAM access (it is slower than ROM/I-RAM)

    private final byte[] rom;
    private final byte[] bw;                     // BW-RAM: the cartridge battery RAM, shared with the Cartridge
    private final byte[] iram = new byte[0x800];
    private final Cpu65816 cpu;
    private final Bus sa1bus = new Bus();

    // $2200 CCNT
    private boolean sa1Irq, sa1Rdyb, sa1Resb = true, sa1Nmi;
    private int smeg;
    // $2201/$2202
    private boolean cpuIrqEn, chdmaIrqEn, cpuIrqCl, chdmaIrqCl;
    // vectors
    private int crv, cnv, civ, snv, siv;
    // $2209 SCNT
    private boolean cpuIrq, cpuIvsw, cpuNvsw;
    private int cmeg;
    // $220A/$220B
    private boolean sa1IrqEn, timerIrqEn, dmaIrqEn, sa1NmiEn, sa1IrqCl, timerIrqCl, dmaIrqCl, sa1NmiCl;
    // $2210..$2215 timers
    private boolean hvselb, ven, hen;
    private int hcnt, vcnt, hcounter, vcounter, hcr, vcr;
    // ROM mapper $2220-$2223
    private final boolean[] bankMode = new boolean[4];
    private final int[] bank = {0, 1, 2, 3};
    // BW-RAM mapping and protection
    private int sbm, cbm, bwp = 0x0F, siwp, ciwp;
    private boolean sw46, swen, cwen;
    // DMA
    private boolean dmaen, dprio, cden, cdsel;
    private int dd, sd, dmasize, dmacb, dsa, dda, dtc, dmaLine;
    private boolean chdend, bbf, bwDma;
    private final int[] brf = new int[16];
    // arithmetic
    private boolean acm, md, overflow;
    private int ma, mb;
    private long mr;
    // variable-length bit reader
    private boolean hl;
    private int vb = 16, va, vbit;
    // flags
    private boolean cpuIrqFl, chdmaIrqFl, sa1IrqFl, timerIrqFl, dmaIrqFl, sa1NmiFl;

    private long credit;                          // master clocks the SA-1 may still spend
    private int mdr;                              // SA-1 data bus latch
    private int extraCycles;                      // wait states accumulated by the current instruction

    public Sa1(byte[] rom, byte[] bwram) {
        this.rom = rom;
        this.bw = bwram.length > 0 ? bwram : new byte[0x8000];
        this.cpu = new Cpu65816(sa1bus);
        cpu.vectorOverride = a -> switch (a) {                           // the SA-1 takes its vectors from registers
            case 0xFFEA, 0xFFFA -> cnv;
            case 0xFFEE, 0xFFFE -> civ;
            case 0xFFFC -> crv;
            default -> -1;
        };
    }

    // ------------------------------------------------------------------------------------------ helpers

    private int romAt(int a) { return rom[Sdd1.mirror(a, rom.length)] & 0xFF; }
    private int bwAt(int a) { return bw[Sdd1.mirror(a, bw.length)] & 0xFF; }
    private void bwPut(int a, int v) { bw[Sdd1.mirror(a, bw.length)] = (byte) v; }
    private int iramAt(int a) { return iram[a & 0x7FF] & 0xFF; }

    /** ROM through the four 1 MB mapper windows; {@code a} is already in linear (translated) form. */
    private int romCpu(int a) {
        boolean lo = a < 0x400000;
        a &= 0x3FFFFF;
        int slot = a >> 20;
        if (lo && !bankMode[slot]) return romAt(a);
        return romAt(bank[slot] << 20 | (a & 0xFFFFF));
    }

    private static int translate(int addr) {
        if ((addr & 0x408000) == 0x008000) return (addr & 0x800000) >> 2 | (addr & 0x3F0000) >> 1 | addr & 0x7FFF;
        return addr;
    }

    private boolean bwWriteProtected(int a) { return !swen && !cwen && (a & 0x3FFFF) < (0x100 << bwp); }

    private int bitmapRead(int a) {
        if (!bbf) { int v = bwAt(a >> 1); return (a & 1) == 0 ? v & 15 : v >> 4 & 15; }
        int v = bwAt(a >> 2), sh = (a & 3) << 1;
        return v >> sh & 3;
    }

    private void bitmapWrite(int a, int d) {
        if (!bbf) {
            int i = a >> 1, v = bwAt(i);
            bwPut(i, (a & 1) == 0 ? v & 0xF0 | d & 0x0F : v & 0x0F | (d & 0x0F) << 4);
        } else {
            int i = a >> 2, sh = (a & 3) << 1, v = bwAt(i);
            bwPut(i, v & ~(3 << sh) | (d & 3) << sh);
        }
    }

    // ------------------------------------------------------------------------------------------ S-CPU side

    @Override public int read(int b, int off) {
        b &= 0xFF;
        int addr = b << 16 | off;
        if ((b & 0x7F) < 0x40) {
            if (off >= 0x2200 && off < 0x2400) return off == 0x2300 ? readSfr() : -1;
            if (off >= 0x3000 && off < 0x3800) return iramAt(off - 0x3000);
            if (off >= 0x6000 && off < 0x8000) return bwCpuRead(sbm * 0x2000 + (off & 0x1FFF));
            if (off < 0x8000) return -1;
            return romRead(translate(addr), true);
        }
        if (b >= 0x40 && b < 0x50) return bwCpuRead(addr & 0xFFFFF);
        if (b >= 0xC0) return romRead(addr, true);
        return -1;
    }

    private int romRead(int a, boolean cpuSide) {
        if (cpuSide && (a & 0xFFFFE0) == 0x007FE0) {                      // S-CPU vector overrides
            if (a == 0x7FEA && cpuNvsw) return snv & 0xFF;
            if (a == 0x7FEB && cpuNvsw) return snv >> 8;
            if (a == 0x7FEE && cpuIvsw) return siv & 0xFF;
            if (a == 0x7FEF && cpuIvsw) return siv >> 8;
        }
        return romCpu(a);
    }

    private int bwCpuRead(int a) {
        if (bwDma) return dmaCc1Read(a);
        return bwAt(a);
    }

    private int readSfr() {
        return (cpuIrqFl ? 0x80 : 0) | (cpuIvsw ? 0x40 : 0) | (chdmaIrqFl ? 0x20 : 0) | (cpuNvsw ? 0x10 : 0) | cmeg;
    }

    @Override public boolean write(int b, int off, int v) {
        b &= 0xFF;
        v &= 0xFF;
        if ((b & 0x7F) < 0x40) {
            if (off >= 0x2200 && off < 0x2400) { writeCpuIo(off, v); return true; }
            if (off >= 0x3000 && off < 0x3800) {
                if ((siwp & 1 << (off - 0x3000 >> 8 & 7)) != 0) iram[off - 0x3000] = (byte) v;
                return true;
            }
            if (off >= 0x6000 && off < 0x8000) { bwCpuWrite(sbm * 0x2000 + (off & 0x1FFF), v); return true; }
            return off >= 0x8000;                                          // ROM: writes are ignored
        }
        if (b >= 0x40 && b < 0x50) { bwCpuWrite((b << 16 | off) & 0xFFFFF, v); return true; }
        return b >= 0xC0;
    }

    private void bwCpuWrite(int a, int v) {
        if (bwWriteProtected(a)) return;
        bwPut(a, v);
    }

    private void writeCpuIo(int off, int v) {
        switch (off) {
            case 0x2200 -> {
                if (sa1Resb && (v & 0x20) == 0) { cpu.reset(); ciwp = 0; }   // release from reset: start at CRV
                sa1Irq = (v & 0x80) != 0; sa1Rdyb = (v & 0x40) != 0; sa1Resb = (v & 0x20) != 0; sa1Nmi = (v & 0x10) != 0;
                smeg = v & 15;
                if (sa1Irq) { sa1IrqFl = true; if (sa1IrqEn) sa1IrqCl = false; }
                if (sa1Nmi) { sa1NmiFl = true; if (sa1NmiEn) sa1NmiCl = false; }
            }
            case 0x2201 -> { cpuIrqEn = (v & 0x80) != 0; chdmaIrqEn = (v & 0x20) != 0; }
            case 0x2202 -> {
                cpuIrqCl = (v & 0x80) != 0; chdmaIrqCl = (v & 0x20) != 0;
                if (cpuIrqCl) cpuIrqFl = false;
                if (chdmaIrqCl) chdmaIrqFl = false;
            }
            case 0x2203 -> crv = crv & 0xFF00 | v;
            case 0x2204 -> crv = v << 8 | crv & 0xFF;
            case 0x2205 -> cnv = cnv & 0xFF00 | v;
            case 0x2206 -> cnv = v << 8 | cnv & 0xFF;
            case 0x2207 -> civ = civ & 0xFF00 | v;
            case 0x2208 -> civ = v << 8 | civ & 0xFF;
            case 0x2220, 0x2221, 0x2222, 0x2223 -> { bankMode[off - 0x2220] = (v & 0x80) != 0; bank[off - 0x2220] = v & 7; }
            case 0x2224 -> sbm = v & 0x1F;
            case 0x2226 -> swen = (v & 0x80) != 0;
            case 0x2228 -> bwp = v & 0x0F;
            case 0x2229 -> siwp = v;
            case 0x2231, 0x2232, 0x2233, 0x2234, 0x2235, 0x2236, 0x2237 -> writeShared(off, v);
            default -> { }
        }
    }

    @Override public boolean irq() { return cpuIrqFl && cpuIrqEn || chdmaIrqFl && chdmaIrqEn; }

    // ------------------------------------------------------------------------------------------ SA-1 side registers

    private int readSa1Io(int off) {
        switch (off) {
            case 0x2301: return (sa1IrqFl ? 0x80 : 0) | (timerIrqFl ? 0x40 : 0) | (dmaIrqFl ? 0x20 : 0) | (sa1NmiFl ? 0x10 : 0) | smeg;
            case 0x2302: hcr = hcounter >> 2; vcr = vcounter; return hcr & 0xFF;
            case 0x2303: return hcr >> 8 & 0xFF;
            case 0x2304: return vcr & 0xFF;
            case 0x2305: return vcr >> 8 & 0xFF;
            case 0x2306: return (int) (mr & 0xFF);
            case 0x2307: return (int) (mr >> 8 & 0xFF);
            case 0x2308: return (int) (mr >> 16 & 0xFF);
            case 0x2309: return (int) (mr >> 24 & 0xFF);
            case 0x230A: return (int) (mr >> 32 & 0xFF);
            case 0x230B: return overflow ? 0x80 : 0;
            case 0x230C: case 0x230D: {
                int d = readVbr(va) | readVbr(va + 1) << 8 | readVbr(va + 2) << 16;
                d >>= vbit;
                if (off == 0x230D) {
                    if (hl) { vbit += vb; va += vbit >> 3; vbit &= 7; }
                    return d >> 8 & 0xFF;
                }
                return d & 0xFF;
            }
            default: return 0xFF;
        }
    }

    private int readVbr(int a) {
        a &= 0xFFFFFF;
        if ((a & 0x408000) == 0x8000 || (a & 0xC00000) == 0xC00000) return romCpu(translate(a));
        if ((a & 0x40E000) == 0x6000 || (a & 0xF00000) == 0x400000) return bwAt(a);
        if ((a & 0x40F800) == 0 || (a & 0x40F800) == 0x3000) return iramAt(a);
        return 0xFF;
    }

    private void writeSa1Io(int off, int v) {
        switch (off) {
            case 0x2209 -> {
                cpuIrq = (v & 0x80) != 0; cpuIvsw = (v & 0x40) != 0; cpuNvsw = (v & 0x10) != 0; cmeg = v & 15;
                if (cpuIrq) { cpuIrqFl = true; if (cpuIrqEn) cpuIrqCl = false; }
            }
            case 0x220A -> {
                if (!sa1IrqEn && (v & 0x80) != 0 && sa1IrqFl) sa1IrqCl = false;
                if (!timerIrqEn && (v & 0x40) != 0 && timerIrqFl) timerIrqCl = false;
                if (!dmaIrqEn && (v & 0x20) != 0 && dmaIrqFl) dmaIrqCl = false;
                if (!sa1NmiEn && (v & 0x10) != 0 && sa1NmiFl) sa1NmiCl = false;
                sa1IrqEn = (v & 0x80) != 0; timerIrqEn = (v & 0x40) != 0; dmaIrqEn = (v & 0x20) != 0; sa1NmiEn = (v & 0x10) != 0;
            }
            case 0x220B -> {
                sa1IrqCl = (v & 0x80) != 0; timerIrqCl = (v & 0x40) != 0; dmaIrqCl = (v & 0x20) != 0; sa1NmiCl = (v & 0x10) != 0;
                if (sa1IrqCl) sa1IrqFl = false;
                if (timerIrqCl) timerIrqFl = false;
                if (dmaIrqCl) dmaIrqFl = false;
                if (sa1NmiCl) sa1NmiFl = false;
            }
            case 0x220C -> snv = snv & 0xFF00 | v;
            case 0x220D -> snv = v << 8 | snv & 0xFF;
            case 0x220E -> siv = siv & 0xFF00 | v;
            case 0x220F -> siv = v << 8 | siv & 0xFF;
            case 0x2210 -> { hvselb = (v & 0x80) != 0; ven = (v & 2) != 0; hen = (v & 1) != 0; }
            case 0x2211 -> { vcounter = 0; hcounter = 0; }
            case 0x2212 -> hcnt = hcnt & 0xFF00 | v;
            case 0x2213 -> hcnt = v << 8 | hcnt & 0xFF;
            case 0x2214 -> vcnt = vcnt & 0xFF00 | v;
            case 0x2215 -> vcnt = v << 8 | vcnt & 0xFF;
            case 0x2225 -> { sw46 = (v & 0x80) != 0; cbm = v & 0x7F; }
            case 0x2227 -> cwen = (v & 0x80) != 0;
            case 0x222A -> ciwp = v;
            case 0x2230 -> {
                dmaen = (v & 0x80) != 0; dprio = (v & 0x40) != 0; cden = (v & 0x20) != 0; cdsel = (v & 0x10) != 0;
                dd = v >> 2 & 1; sd = v & 3;
                if (!dmaen) dmaLine = 0;
            }
            case 0x2231, 0x2232, 0x2233, 0x2234, 0x2235, 0x2236, 0x2237 -> writeShared(off, v);
            case 0x2238 -> dtc = dtc & 0xFF00 | v;
            case 0x2239 -> dtc = v << 8 | dtc & 0xFF;
            case 0x223F -> bbf = (v & 0x80) != 0;
            case 0x2250 -> { acm = (v & 2) != 0; md = (v & 1) != 0; if (acm) mr = 0; }
            case 0x2251 -> ma = ma & 0xFF00 | v;
            case 0x2252 -> ma = v << 8 | ma & 0xFF;
            case 0x2253 -> mb = mb & 0xFF00 | v;
            case 0x2254 -> { mb = v << 8 | mb & 0xFF; arithmetic(); }
            case 0x2258 -> {
                hl = (v & 0x80) != 0; vb = v & 15;
                if (vb == 0) vb = 16;
                if (!hl) { vbit += vb; va += vbit >> 3; vbit &= 7; }
            }
            case 0x2259 -> va = va & 0xFFFF00 | v;
            case 0x225A -> va = va & 0xFF00FF | v << 8;
            case 0x225B -> { va = va & 0x00FFFF | v << 16; vbit = 0; }
            default -> {
                if (off >= 0x2240 && off <= 0x224F) {
                    brf[off - 0x2240] = v;
                    if ((off == 0x2247 || off == 0x224F) && dmaen && cden && !cdsel) dmaCc2();
                }
            }
        }
    }

    private void arithmetic() {
        if (!acm) {
            if (!md) {
                mr = (int) (short) ma * (int) (short) mb & 0xFFFFFFFFL;
                mb = 0;
            } else {
                if (mb == 0) mr = 0;
                else {
                    int dividend = (short) ma, divisor = mb;
                    long ext = dividend + (long) divisor * 65536;           // rounds towards negative infinity
                    long rem = ext % divisor, quo = ext / divisor - 65536;
                    mr = (rem & 0xFFFF) << 16 | quo & 0xFFFF;
                }
                ma = 0; mb = 0;
            }
        } else {
            mr += (long) (short) ma * (short) mb;
            overflow = (mr >> 40) != 0;
            mr &= 0xFFFFFFFFFFL;
            mb = 0;
        }
    }

    private void writeShared(int off, int v) {
        switch (off) {
            case 0x2231 -> {
                chdend = (v & 0x80) != 0; dmasize = v >> 2 & 7; dmacb = v & 3;
                if (chdend) bwDma = false;
                if (dmasize > 5) dmasize = 5;
                if (dmacb > 2) dmacb = 2;
            }
            case 0x2232 -> dsa = dsa & 0xFFFF00 | v;
            case 0x2233 -> dsa = dsa & 0xFF00FF | v << 8;
            case 0x2234 -> dsa = dsa & 0x00FFFF | v << 16;
            case 0x2235 -> dda = dda & 0xFFFF00 | v;
            case 0x2236 -> {
                dda = dda & 0xFF00FF | v << 8;
                if (dmaen) {
                    if (!cden && dd == 0) dmaNormal();
                    else if (cden && cdsel) dmaCc1();
                }
            }
            case 0x2237 -> {
                dda = dda & 0x00FFFF | v << 16;
                if (dmaen && !cden && dd == 1) dmaNormal();
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------------------------------ DMA

    private void dmaNormal() {
        int count = dtc & 0xFFFF;
        dtc = 0xFFFF;                                                    // the terminal counter ends up wrapped
        for (int n = 0; n < count; n++) {
            int src = dsa++ & 0xFFFFFF, dst = dda++ & 0xFFFFFF, data = 0;
            switch (sd) {
                case 0 -> data = romCpu(translate(src));                    // ROM
                case 1 -> data = bwAt(src);                                 // BW-RAM
                case 2 -> data = iramAt(src);                               // I-RAM
                default -> { }
            }
            if (dd == 0) iram[dst & 0x7FF] = (byte) data;
            else bwPut(dst, data);
        }
        dmaIrqFl = true;
        if (dmaIrqEn) dmaIrqCl = false;
    }

    private void dmaCc1() {
        bwDma = true;
        chdmaIrqFl = true;
        if (chdmaIrqEn) chdmaIrqCl = false;
    }

    /** Type-1 character conversion: the S-CPU reads BW-RAM bitmap data and gets planar tiles built in I-RAM. */
    private int dmaCc1Read(int addr) {
        int charmask = (1 << (6 - dmacb)) - 1;
        if ((addr & charmask) == 0) {
            int bpp = 2 << (2 - dmacb);
            int bpl = (8 << dmasize) >> dmacb;
            int bwmask = bw.length - 1;
            int tile = ((addr - dsa) & bwmask) >> (6 - dmacb);
            int ty = tile >> dmasize, tx = tile & ((1 << dmasize) - 1);
            int bwaddr = dsa + ty * 8 * bpl + tx * bpp;
            for (int y = 0; y < 8; y++) {
                long data = 0;
                for (int b = 0; b < bpp; b++) data |= (long) bwAt((bwaddr + b) & bwmask) << (b << 3);
                bwaddr += bpl;
                int[] out = new int[8];
                for (int x = 0; x < 8; x++) {
                    out[0] |= (int) (data & 1) << 7 - x; data >>= 1;
                    out[1] |= (int) (data & 1) << 7 - x; data >>= 1;
                    if (dmacb == 2) continue;
                    out[2] |= (int) (data & 1) << 7 - x; data >>= 1;
                    out[3] |= (int) (data & 1) << 7 - x; data >>= 1;
                    if (dmacb == 1) continue;
                    out[4] |= (int) (data & 1) << 7 - x; data >>= 1;
                    out[5] |= (int) (data & 1) << 7 - x; data >>= 1;
                    out[6] |= (int) (data & 1) << 7 - x; data >>= 1;
                    out[7] |= (int) (data & 1) << 7 - x; data >>= 1;
                }
                for (int b = 0; b < bpp; b++) iram[(dda + (y << 1) + ((b & 6) << 3) + (b & 1)) & 0x7FF] = (byte) out[b];
            }
        }
        return iram[(dda + (addr & charmask)) & 0x7FF] & 0xFF;
    }

    /** Type-2 character conversion: the SA-1 pushes 8 pixels (one register file) at a time. */
    private void dmaCc2() {
        int base = (dmaLine & 1) << 3;
        int bpp = 2 << (2 - dmacb);
        int addr = dda & 0x7FF;
        addr &= ~((1 << (7 - dmacb)) - 1);
        addr += (dmaLine & 8) * bpp;
        addr += (dmaLine & 7) * 2;
        for (int b = 0; b < bpp; b++) {
            int out = 0;
            for (int bit = 0; bit < 8; bit++) out |= (brf[base + bit] >> b & 1) << 7 - bit;
            iram[addr + ((b & 6) << 3) + (b & 1) & 0x7FF] = (byte) out;
        }
        dmaLine = dmaLine + 1 & 15;
    }

    // ------------------------------------------------------------------------------------------ SA-1 bus

    private final class Bus implements Memory {
        @Override public int read(int addr) {
            addr &= 0xFFFFFF;
            int data = mdr;
            if ((addr & 0x40FE00) == 0x002200) return mdr = readSa1Io(0x2200 | addr & 0x1FF);
            if ((addr & 0x408000) == 0x008000 || (addr & 0xC00000) == 0xC00000) return mdr = romCpu(translate(addr));
            if ((addr & 0x40E000) == 0x006000 || (addr & 0xE00000) == 0x400000 || (addr & 0xF00000) == 0x600000) {
                extraCycles += BW_PENALTY;
                if ((addr & 1 << 22) != 0 && (addr & 1 << 21) != 0) return mdr = bitmapRead(addr & 0xFFFFF);
                if ((addr & 1 << 22) != 0) return mdr = bwAt(addr & 0x1FFFFF);
                return mdr = sw46 ? bitmapRead(cbm * 0x2000 + (addr & 0x1FFF)) : bwAt((cbm & 0x1F) * 0x2000 + (addr & 0x1FFF));
            }
            if ((addr & 0x40F800) == 0x000000 || (addr & 0x40F800) == 0x003000) return mdr = iramAt(addr);
            return data;
        }

        @Override public void write(int addr, int v) {
            addr &= 0xFFFFFF;
            v &= 0xFF;
            mdr = v;
            if ((addr & 0x40FE00) == 0x002200) { writeSa1Io(0x2200 | addr & 0x1FF, v); return; }
            if ((addr & 0x408000) == 0x008000 || (addr & 0xC00000) == 0xC00000) return;
            if ((addr & 0x40E000) == 0x006000 || (addr & 0xE00000) == 0x400000 || (addr & 0xF00000) == 0x600000) {
                extraCycles += BW_PENALTY;
                if ((addr & 1 << 22) != 0 && (addr & 1 << 21) != 0) { bitmapWrite(addr & 0xFFFFF, v); return; }
                if ((addr & 1 << 22) != 0) { if (!bwWriteProtected(addr & 0x1FFFFF)) bwPut(addr & 0x1FFFFF, v); return; }
                if (sw46) bitmapWrite(cbm * 0x2000 + (addr & 0x1FFF), v);
                else {
                    int a = (cbm & 0x1F) * 0x2000 + (addr & 0x1FFF);
                    if (!bwWriteProtected(a)) bwPut(a, v);
                }
                return;
            }
            if ((addr & 0x40F800) == 0x000000 || (addr & 0x40F800) == 0x003000) {
                if ((ciwp & 1 << (addr >> 8 & 7)) != 0) iram[addr & 0x7FF] = (byte) v;
            }
        }
    }

    // ------------------------------------------------------------------------------------------ execution

    @Override public void tick(int masterClocks) {
        if (sa1Resb || sa1Rdyb) {                       // held in reset / halted: only the timers keep counting
            credit = 0;
            advanceTimers(masterClocks >> 1);
            return;
        }
        credit += masterClocks;
        while (credit > 0) {
            pollInterrupts();
            extraCycles = 0;
            int cycles = cpu.step() + extraCycles;
            credit -= (long) cycles << 1;
            advanceTimers(cycles);
        }
    }

    private void pollInterrupts() {
        if (sa1Nmi && !sa1NmiCl) {
            cpu.nmiPending = true;
            sa1NmiFl = true;
            sa1NmiCl = true;
            cpu.waiting = false;
        }
        boolean line = false;
        if (!cpu.fI) {
            if (timerIrqEn && !timerIrqCl) { timerIrqFl = true; line = true; }
            else if (dmaIrqEn && !dmaIrqCl) { dmaIrqFl = true; line = true; }
            else if (sa1Irq && !sa1IrqCl) { sa1IrqFl = true; line = true; }
        }
        cpu.irqLine = line;
    }

    private void advanceTimers(int cycles) {
        for (int i = 0; i < cycles; i++) {
            hcounter += 2;
            if (!hvselb) {
                if (hcounter >= 1364) { hcounter = 0; if (++vcounter >= 262) vcounter = 0; }
            } else {
                vcounter += hcounter >> 11;
                hcounter &= 0x7FF;
                vcounter &= 0x1FF;
            }
            boolean hit = switch ((hen ? 1 : 0) | (ven ? 2 : 0)) {
                case 1 -> hcounter == hcnt << 2;
                case 2 -> vcounter == vcnt && hcounter == 0;
                case 3 -> vcounter == vcnt && hcounter == hcnt << 2;
                default -> false;
            };
            if (hit) { timerIrqFl = true; if (timerIrqEn) timerIrqCl = false; }
        }
    }

    // ------------------------------------------------------------------------------------------ state

    @Override public void save(StateOut o) throws IOException {
        cpu.save(o);
        o.bytes(iram);
        o.b(sa1Irq); o.b(sa1Rdyb); o.b(sa1Resb); o.b(sa1Nmi); o.i(smeg);
        o.b(cpuIrqEn); o.b(chdmaIrqEn); o.b(cpuIrqCl); o.b(chdmaIrqCl);
        o.i(crv); o.i(cnv); o.i(civ); o.i(snv); o.i(siv);
        o.b(cpuIrq); o.b(cpuIvsw); o.b(cpuNvsw); o.i(cmeg);
        o.b(sa1IrqEn); o.b(timerIrqEn); o.b(dmaIrqEn); o.b(sa1NmiEn); o.b(sa1IrqCl); o.b(timerIrqCl); o.b(dmaIrqCl); o.b(sa1NmiCl);
        o.b(hvselb); o.b(ven); o.b(hen); o.i(hcnt); o.i(vcnt); o.i(hcounter); o.i(vcounter); o.i(hcr); o.i(vcr);
        o.bools(bankMode); o.ints(bank);
        o.i(sbm); o.i(cbm); o.i(bwp); o.i(siwp); o.i(ciwp); o.b(sw46); o.b(swen); o.b(cwen);
        o.b(dmaen); o.b(dprio); o.b(cden); o.b(cdsel);
        o.i(dd); o.i(sd); o.i(dmasize); o.i(dmacb); o.i(dsa); o.i(dda); o.i(dtc); o.i(dmaLine);
        o.b(chdend); o.b(bbf); o.b(bwDma); o.ints(brf);
        o.b(acm); o.b(md); o.b(overflow); o.i(ma); o.i(mb); o.l(mr);
        o.b(hl); o.i(vb); o.i(va); o.i(vbit);
        o.b(cpuIrqFl); o.b(chdmaIrqFl); o.b(sa1IrqFl); o.b(timerIrqFl); o.b(dmaIrqFl); o.b(sa1NmiFl);
        o.l(credit); o.i(mdr);
    }

    @Override public void load(StateIn in) throws IOException {
        cpu.load(in);
        in.bytes(iram);
        sa1Irq = in.b(); sa1Rdyb = in.b(); sa1Resb = in.b(); sa1Nmi = in.b(); smeg = in.i();
        cpuIrqEn = in.b(); chdmaIrqEn = in.b(); cpuIrqCl = in.b(); chdmaIrqCl = in.b();
        crv = in.i(); cnv = in.i(); civ = in.i(); snv = in.i(); siv = in.i();
        cpuIrq = in.b(); cpuIvsw = in.b(); cpuNvsw = in.b(); cmeg = in.i();
        sa1IrqEn = in.b(); timerIrqEn = in.b(); dmaIrqEn = in.b(); sa1NmiEn = in.b(); sa1IrqCl = in.b(); timerIrqCl = in.b(); dmaIrqCl = in.b(); sa1NmiCl = in.b();
        hvselb = in.b(); ven = in.b(); hen = in.b(); hcnt = in.i(); vcnt = in.i(); hcounter = in.i(); vcounter = in.i(); hcr = in.i(); vcr = in.i();
        in.bools(bankMode); in.ints(bank);
        sbm = in.i(); cbm = in.i(); bwp = in.i(); siwp = in.i(); ciwp = in.i(); sw46 = in.b(); swen = in.b(); cwen = in.b();
        dmaen = in.b(); dprio = in.b(); cden = in.b(); cdsel = in.b();
        dd = in.i(); sd = in.i(); dmasize = in.i(); dmacb = in.i(); dsa = in.i(); dda = in.i(); dtc = in.i(); dmaLine = in.i();
        chdend = in.b(); bbf = in.b(); bwDma = in.b(); in.ints(brf);
        acm = in.b(); md = in.b(); overflow = in.b(); ma = in.i(); mb = in.i(); mr = in.l();
        hl = in.b(); vb = in.i(); va = in.i(); vbit = in.i();
        cpuIrqFl = in.b(); chdmaIrqFl = in.b(); sa1IrqFl = in.b(); timerIrqFl = in.b(); dmaIrqFl = in.b(); sa1NmiFl = in.b();
        credit = in.l(); mdr = in.i();
    }
}
