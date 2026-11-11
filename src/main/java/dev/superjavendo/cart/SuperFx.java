package dev.superjavendo.cart;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;

import java.io.IOException;

/**
 * SuperFX (GSU-1/GSU-2): a 16-bit RISC CPU with a bitmap "plot" engine, sharing the cartridge ROM and RAM with the console.
 * Instruction level model: one instruction at a time with an approximate cycle cost, a one-byte opcode pipeline
 * (so branches have a delay slot) and a 512-byte instruction cache.
 */
public final class SuperFx implements Coprocessor {
    private final byte[] rom;
    private final byte[] ram;
    private final int ramMask;

    private final int[] r = new int[16];
    private boolean z, cy, s, ov, g, alt1, alt2, b, irqFlag;
    private int pbr, rombr, rambr, cbr, scbr, scmr, cfgr, clsr, bramr, por, colr, sreg, dreg, pipeline, ramAddr;
    private final byte[] cache = new byte[512];
    private int cacheValid;                         // one bit per 16-byte line
    private long budget;                            // master clocks available to the chip

    public SuperFx(byte[] rom, int ramBytes) {
        this.rom = rom;
        int size = Integer.highestOneBit(Math.max(ramBytes, 1) * 2 - 1);   // round up to 2^n; the RAM mirrors across its size
        this.ram = new byte[Math.min(size, 0x20000)];
        this.ramMask = ram.length - 1;
        java.util.Arrays.fill(ram, (byte) 0x60);       // power-on RAM contents are undefined; snes9x uses $60, so do we (keeps comparisons meaningful)
    }

    public byte[] ram() { return ram; }

    /** One-line state summary for diagnostics. */
    public String status() {
        return String.format("GSU g=%b sfr=%04X pbr=%02X r15=%04X r0-3=%04X,%04X,%04X,%04X scmr=%02X por=%02X colr=%02X cfgr=%02X clsr=%d",
                g, sfr(), pbr, r[15], r[0], r[1], r[2], r[3], scmr, por, colr, cfgr, clsr);
    }

    // ------------------------------------------------------------------ CPU side

    private static boolean lowBanks(int bank) { int b = bank & 0x7F; return b < 0x40; }

    @Override public int read(int bank, int off) {
        bank &= 0xFF;
        if (lowBanks(bank)) {
            if (off >= 0x3000 && off < 0x3300) return readReg(off);
            if (off >= 0x6000 && off < 0x8000) return ram[(off - 0x6000) & ramMask] & 0xFF;
            return -1;
        }
        int b = bank & 0x7F;
        if (b < 0x60) return rom[((b - 0x40) * 0x10000 + off) % rom.length] & 0xFF;
        if (b == 0x70 || b == 0x71) return ram[(((b & 1) << 16) + off) & ramMask] & 0xFF;
        return -1;
    }

    @Override public boolean write(int bank, int off, int v) {
        bank &= 0xFF;
        v &= 0xFF;
        if (lowBanks(bank)) {
            if (off >= 0x3000 && off < 0x3300) { writeReg(off, v); return true; }
            if (off >= 0x6000 && off < 0x8000) { ram[(off - 0x6000) & ramMask] = (byte) v; return true; }
            return false;
        }
        int b = bank & 0x7F;
        if (b == 0x70 || b == 0x71) { ram[(((b & 1) << 16) + off) & ramMask] = (byte) v; return true; }
        return b >= 0x40 && b < 0x60;           // ROM: ignore writes
    }

    private int sfr() {
        return (z ? 2 : 0) | (cy ? 4 : 0) | (s ? 8 : 0) | (ov ? 0x10 : 0) | (g ? 0x20 : 0)
                | (alt1 ? 0x100 : 0) | (alt2 ? 0x200 : 0) | (b ? 0x1000 : 0) | (irqFlag ? 0x8000 : 0);
    }

    private int readReg(int off) {
        if (off < 0x3020) { int v = r[(off - 0x3000) >> 1]; return (off & 1) == 0 ? v & 0xFF : v >> 8; }
        if (off >= 0x3100) return cache[off - 0x3100] & 0xFF;
        switch (off) {
            case 0x3030: return sfr() & 0xFF;
            case 0x3031: { int v = sfr() >> 8; irqFlag = false; return v; }
            case 0x3034: return pbr;
            case 0x3036: return rombr;
            case 0x303B: return 0x04;           // version
            case 0x303C: return rambr;
            case 0x303E: return cbr & 0xFF;
            case 0x303F: return cbr >> 8;
            default: return 0;
        }
    }

    private void writeReg(int off, int v) {
        if (off < 0x3020) {
            if (g) return;                      // registers are frozen while the chip runs
            int n = (off - 0x3000) >> 1;
            r[n] = (off & 1) == 0 ? (r[n] & 0xFF00) | v : (r[n] & 0x00FF) | (v << 8);
            if (off == 0x301F) { g = true; pipeline = 0x01; }          // writing the high byte of R15 starts the chip
            return;
        }
        if (off >= 0x3100) {
            int i = off - 0x3100;
            cache[i] = (byte) v;
            if ((i & 15) == 15) cacheValid |= 1 << (i >> 4);
            return;
        }
        switch (off) {
            case 0x3030:
                z = (v & 2) != 0; cy = (v & 4) != 0; s = (v & 8) != 0; ov = (v & 0x10) != 0;
                g = (v & 0x20) != 0;
                break;
            case 0x3031: alt1 = (v & 1) != 0; alt2 = (v & 2) != 0; b = (v & 0x10) != 0; irqFlag = (v & 0x80) != 0; break;
            case 0x3033: bramr = v; break;
            case 0x3034: pbr = v & 0x7F; cacheValid = 0; break;
            case 0x3037: cfgr = v; break;
            case 0x3038: scbr = v; break;
            case 0x3039: clsr = v & 1; break;
            case 0x303A: scmr = v; break;
            default: break;
        }
    }

    @Override public boolean irq() { return irqFlag && (cfgr & 0x80) == 0; }

    // ------------------------------------------------------------------ running

    @Override public void tick(int masterClocks) {
        if (!g) { budget = 0; return; }
        budget += masterClocks;
        int unit = clsr != 0 ? 1 : 2;           // master clocks per GSU cycle
        while (g && budget > 0) budget -= (long) step() * unit;
    }

    /** Debug: print one line per executed GSU instruction. */
    public java.io.PrintStream trace;

    private int step() {
        int op = pipeline;
        if (trace != null) trace.printf("%02X:%04X %02X sfr=%04X s=%d d=%d r0-3=%04X,%04X,%04X,%04X r4-7=%04X,%04X,%04X,%04X r8-11=%04X,%04X,%04X,%04X r12-14=%04X,%04X,%04X%n",
                pbr, (r[15] - 1) & 0xFFFF, op, sfr(), sreg, dreg, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], r[10], r[11], r[12], r[13], r[14]);
        pipeline = fetchOp();
        r[15] = (r[15] + 1) & 0xFFFF;
        return exec(op);
    }

    /** Operand / next opcode byte from the pipeline; refills the pipeline. */
    private int pipe() {
        int v = pipeline;
        pipeline = fetchOp();
        r[15] = (r[15] + 1) & 0xFFFF;
        return v;
    }

    private int memRead(int bank, int off) {
        if (bank < 0x40) return rom[(bank * 0x8000 + (off & 0x7FFF)) % rom.length] & 0xFF;
        if (bank < 0x60) return rom[((bank - 0x40) * 0x10000 + off) % rom.length] & 0xFF;
        if (bank == 0x70 || bank == 0x71) return ram[(((bank & 1) << 16) + off) & ramMask] & 0xFF;
        return 0;
    }

    private int fetchOp() {
        int pc = r[15];
        int idx = (pc - cbr) & 0xFFFF;
        if (idx < 512) {
            int line = idx >> 4;
            if ((cacheValid & (1 << line)) == 0) {
                int base = (cbr + (line << 4)) & 0xFFFF;
                for (int k = 0; k < 16; k++) cache[(line << 4) + k] = (byte) memRead(pbr, (base + k) & 0xFFFF);
                cacheValid |= 1 << line;
            }
            return cache[idx] & 0xFF;
        }
        return memRead(pbr, pc);
    }

    private int ramRead(int addr) { return ram[(((rambr & 1) << 16) + (addr & 0xFFFF)) & ramMask] & 0xFF; }
    private void ramWrite(int addr, int v) { ram[(((rambr & 1) << 16) + (addr & 0xFFFF)) & ramMask] = (byte) v; }
    private int ramWord(int addr) { return ramRead(addr) | (ramRead(addr ^ 1) << 8); }
    private void ramWordWrite(int addr, int v) { ramWrite(addr, v & 0xFF); ramWrite(addr ^ 1, v >> 8); }

    private int sr() { return rn(sreg); }
    /** Register read as seen by an instruction: R15 is the address of the next byte to fetch (one past the pipeline). */
    private int rn(int n) { return n == 15 ? (r[15] - R15_SKEW) & 0xFFFF : r[n]; }
    private static final int R15_SKEW = 1;
    private void setD(int v) { r[dreg] = v & 0xFFFF; }
    private void sz(int v) { s = (v & 0x8000) != 0; z = (v & 0xFFFF) == 0; }
    private int alt() { return (alt1 ? 1 : 0) | (alt2 ? 2 : 0); }
    private void clearPrefix() { alt1 = alt2 = b = false; sreg = dreg = 0; }

    private int colorLogic(int source) {
        if ((por & 4) != 0) source = (colr & 0xF0) | ((source & 0xFF) >> 4);
        if ((por & 8) != 0) source = (colr & 0xF0) | (source & 0x0F);
        return source & 0xFF;
    }

    private int ht() {
        if ((por & 0x10) != 0) return 3;
        return ((scmr >> 2) & 1) | ((scmr >> 4) & 2);
    }

    private int bpp() { int md = scmr & 3; return md == 0 ? 2 : md == 3 ? 8 : 4; }

    private int tileAddr(int x, int y) {
        int cn;
        switch (ht()) {
            case 0: cn = ((x & 0xF8) << 1) + ((y & 0xF8) >> 3); break;
            case 1: cn = ((x & 0xF8) << 1) + ((x & 0xF8) >> 1) + ((y & 0xF8) >> 3); break;
            case 2: cn = ((x & 0xF8) << 1) + (x & 0xF8) + ((y & 0xF8) >> 3); break;
            default: cn = ((y & 0x80) << 2) + ((x & 0x80) << 1) + ((y & 0x78) << 1) + ((x & 0x78) >> 3); break;
        }
        return cn * (bpp() << 3) + (scbr << 10) + (y & 7) * 2;
    }

    private void plot(int x, int y) {
        x &= 0xFF; y &= 0xFF;
        int color = colr;
        if ((por & 1) == 0) {                                   // transparency: colour 0 is not drawn
            if ((scmr & 3) == 3) { if ((por & 8) != 0 ? (color & 0x0F) == 0 : color == 0) return; }
            else if ((color & 0x0F) == 0) return;
        }
        if ((por & 2) != 0 && (scmr & 3) != 3) { if (((x ^ y) & 1) != 0) color >>= 4; color &= 0x0F; }
        int addr = tileAddr(x, y), bit = 7 - (x & 7), planes = bpp();
        for (int n = 0; n < planes; n++) {
            int a = (addr + ((n >> 1) << 4) + (n & 1)) & ramMask;
            int m = 1 << bit;
            ram[a] = (byte) (((color >> n) & 1) != 0 ? (ram[a] | m) : (ram[a] & ~m));
        }
    }

    private int rpix(int x, int y) {
        x &= 0xFF; y &= 0xFF;
        int addr = tileAddr(x, y), bit = 7 - (x & 7), v = 0;
        for (int n = 0; n < bpp(); n++) {
            int a = (addr + ((n >> 1) << 4) + (n & 1)) & ramMask;
            v |= ((ram[a] >> bit) & 1) << n;
        }
        return v;
    }

    /** Executes one opcode; returns the approximate cost in GSU cycles. */
    private int exec(int op) {
        int n = op & 15, cost = 1;
        boolean prefix = false;
        switch (op) {
            case 0x00: g = false; irqFlag = true; break;                           // STOP
            case 0x01: break;                                                     // NOP
            case 0x02: {                                                          // CACHE
                int c = r[15] & 0xFFF0;
                if (cbr != c) { cbr = c; cacheValid = 0; }
                break;
            }
            case 0x03: { int v = sr(); cy = (v & 1) != 0; int o = v >> 1; setD(o); sz(o); break; }          // LSR
            case 0x04: { int v = sr(); int o = ((v << 1) | (cy ? 1 : 0)) & 0xFFFF; cy = (v & 0x8000) != 0; setD(o); sz(o); break; } // ROL
            case 0x05: branch(true); break;
            case 0x06: branch(s == ov); break;
            case 0x07: branch(s != ov); break;
            case 0x08: branch(!z); break;
            case 0x09: branch(z); break;
            case 0x0A: branch(!s); break;
            case 0x0B: branch(s); break;
            case 0x0C: branch(!cy); break;
            case 0x0D: branch(cy); break;
            case 0x0E: branch(!ov); break;
            case 0x0F: branch(ov); break;
            case 0x3C: { r[12] = (r[12] - 1) & 0xFFFF; sz(r[12]); if (r[12] != 0) r[15] = r[13]; break; }  // LOOP
            case 0x3D: alt1 = true; prefix = true; break;
            case 0x3E: alt2 = true; prefix = true; break;
            case 0x3F: alt1 = alt2 = true; prefix = true; break;
            case 0x4C: {                                                          // PLOT / RPIX
                if (alt1) { int v = rpix(r[1], r[2]); setD(v); sz(v); }
                else { plot(r[1], r[2]); r[1] = (r[1] + 1) & 0xFFFF; }
                cost = 2;
                break;
            }
            case 0x4D: { int v = sr(); int o = ((v << 8) | (v >> 8)) & 0xFFFF; setD(o); sz(o); break; }       // SWAP
            case 0x4E:                                                            // COLOR / CMODE
                if (alt1) por = sr() & 0x1F; else colr = colorLogic(sr() & 0xFF);
                break;
            case 0x4F: { int o = ~sr() & 0xFFFF; setD(o); sz(o); break; }          // NOT
            case 0x70: {                                                          // MERGE
                int o = (r[7] & 0xFF00) | (r[8] >> 8);
                setD(o);
                s = (o & 0x8080) != 0; ov = (o & 0xC0C0) != 0; cy = (o & 0xE0E0) != 0; z = (o & 0xF0F0) != 0;   // Z is set when NON-zero (hardware quirk)
                break;
            }
            case 0x90: ramWordWrite(ramAddr, sr()); cost = 4; break;              // SBK
            case 0x95: { int o = (byte) sr() & 0xFFFF; setD(o); sz(o); break; }    // SEX
            case 0x96: {                                                          // ASR / DIV2
                int v = (short) sr();
                cy = (v & 1) != 0;
                int o = alt1 ? ((v < 0 ? v + 1 : v) >> 1) : (v >> 1);
                setD(o); sz(o);
                break;
            }
            case 0x97: { int v = sr(); int o = ((cy ? 0x8000 : 0) | (v >> 1)) & 0xFFFF; cy = (v & 1) != 0; setD(o); sz(o); break; } // ROR
            case 0x9E: { int o = sr() & 0xFF; setD(o); s = (o & 0x80) != 0; z = o == 0; break; }   // LOB
            case 0x9F: {                                                          // FMULT / LMULT
                long p = (long) (short) sr() * (short) r[6];
                cy = (p & 0x8000) != 0;
                if (alt1) r[4] = (int) (p & 0xFFFF);
                int o = (int) ((p >> 16) & 0xFFFF);
                setD(o); sz(o);
                cost = 8;
                break;
            }
            case 0xC0: { int o = sr() >> 8; setD(o); s = (o & 0x80) != 0; z = o == 0; break; }       // HIB
            case 0xDF:                                                            // GETC / RAMB / ROMB
                if (alt() == 2) rambr = sr() & 1;
                else if (alt() == 3) rombr = sr() & 0x7F;
                else colr = colorLogic(memRead(rombr, r[14]));
                break;
            case 0xEF: {                                                          // GETB family
                int v = memRead(rombr, r[14]);
                switch (alt()) {
                    case 0: setD(v); break;
                    case 1: setD((sr() & 0x00FF) | (v << 8)); break;
                    case 2: setD((sr() & 0xFF00) | v); break;
                    default: setD((byte) v & 0xFFFF); break;
                }
                cost = 3;
                break;
            }
            default: prefix = execFamily(op, n); break;
        }
        if (op >= 0x05 && op <= 0x0F) prefix = true;           // branches keep ALT1/ALT2/B/sreg/dreg: the delay slot sees them
        if (!prefix) clearPrefix();
        return cost;
    }

    private void branch(boolean take) {
        int d = (byte) pipe();
        if (take) r[15] = (r[15] - 1 + d) & 0xFFFF;      // relative to the address after the branch (the delay slot is already in the pipeline)
    }

    /** Opcodes grouped by their high nibble. Returns true if the opcode is a prefix (keeps ALT/B state). */
    private boolean execFamily(int op, int n) {
        int hi = op >> 4;
        switch (hi) {
            case 0x1:                                                             // TO / MOVE
                if (!b) { dreg = n; return true; }
                r[n] = sr();
                return false;
            case 0x2: sreg = dreg = n; b = true; return true;                      // WITH
            case 0x3:                                                             // STW / STB
                ramAddr = rn(n);
                if (alt1) ramWrite(ramAddr, sr() & 0xFF); else ramWordWrite(ramAddr, sr());
                return false;
            case 0x4:                                                             // LDW / LDB
                ramAddr = r[n];
                { int v = alt1 ? ramRead(ramAddr) : ramWord(ramAddr); setD(v); }
                return false;
            case 0x5: {                                                           // ADD / ADC / ADD# / ADC#
                int a = alt(), x = sr(), y = (a & 2) != 0 ? n : rn(n);
                int sum = x + y + ((a & 1) != 0 && cy ? 1 : 0);
                ov = (~(x ^ y) & (x ^ sum) & 0x8000) != 0;
                cy = sum > 0xFFFF;
                setD(sum); sz(sum & 0xFFFF);
                return false;
            }
            case 0x6: {                                                           // SUB / SBC / SUB# / CMP
                int a = alt(), x = sr(), y = a == 2 ? n : rn(n);
                int diff = x - y - (a == 1 && !cy ? 1 : 0);
                ov = ((x ^ y) & (x ^ diff) & 0x8000) != 0;
                cy = diff >= 0;
                if (a != 3) setD(diff);
                sz(diff & 0xFFFF);
                return false;
            }
            case 0x7: {                                                           // AND / BIC / AND# / BIC#
                int a = alt(), y = (a & 2) != 0 ? n : rn(n);
                int o = (a & 1) != 0 ? sr() & ~y : sr() & y;
                setD(o); sz(o & 0xFFFF);
                return false;
            }
            case 0x8: {                                                           // MULT / UMULT / MULT# / UMULT#
                int a = alt();
                int y = (a & 2) != 0 ? n : rn(n);
                int o = (a & 1) != 0 ? (sr() & 0xFF) * (y & 0xFF) : (byte) sr() * (a == 2 ? y : (byte) y);
                setD(o); sz(o & 0xFFFF);
                return false;
            }
            case 0x9:
                if (n >= 1 && n <= 4) { r[11] = (rn(15) + n) & 0xFFFF; return false; }      // LINK
                if (n >= 8 && n <= 13) {                                                   // JMP / LJMP
                    if (alt1) { pbr = rn(n) & 0x7F; r[15] = sr(); cbr = r[15] & 0xFFF0; cacheValid = 0; }
                    else r[15] = rn(n);
                    return false;
                }
                return false;
            case 0xA: {                                                           // IBT / LMS / SMS
                int a = alt();
                int imm = pipe();
                if ((a & 1) != 0) { ramAddr = imm << 1; r[n] = ramWord(ramAddr); }
                else if (a == 2) { ramAddr = imm << 1; ramWordWrite(ramAddr, r[n]); }
                else r[n] = (byte) imm & 0xFFFF;
                return false;
            }
            case 0xB:                                                             // FROM / MOVES
                if (!b) { sreg = n; return true; }
                { int v = rn(n); setD(v); ov = (v & 0x80) != 0; sz(v); }
                return false;
            case 0xC: {                                                           // OR / XOR / OR# / XOR#
                int a = alt(), y = (a & 2) != 0 ? n : rn(n);
                int o = (a & 1) != 0 ? sr() ^ y : sr() | y;
                setD(o); sz(o & 0xFFFF);
                return false;
            }
            case 0xD: { r[n] = (r[n] + 1) & 0xFFFF; sz(r[n]); return false; }       // INC
            case 0xE: { r[n] = (r[n] - 1) & 0xFFFF; sz(r[n]); return false; }       // DEC
            case 0xF: {                                                           // IWT / LM / SM
                int a = alt();
                int lo = pipe(), hiB = pipe();
                int imm = lo | (hiB << 8);
                if ((a & 1) != 0) { ramAddr = imm; r[n] = ramWord(ramAddr); }
                else if (a == 2) { ramAddr = imm; ramWordWrite(ramAddr, r[n]); }
                else r[n] = imm;
                return false;
            }
            default: return false;
        }
    }

    // ------------------------------------------------------------------ save state

    @Override public void save(StateOut o) throws IOException {
        o.ints(r);
        o.i((z ? 1 : 0) | (cy ? 2 : 0) | (s ? 4 : 0) | (ov ? 8 : 0) | (g ? 16 : 0) | (alt1 ? 32 : 0) | (alt2 ? 64 : 0) | (b ? 128 : 0) | (irqFlag ? 256 : 0));
        o.ints(new int[]{pbr, rombr, rambr, cbr, scbr, scmr, cfgr, clsr, bramr, por, colr, sreg, dreg, pipeline, ramAddr, cacheValid});
        o.l(budget);
        o.bytes(ram);
        o.bytes(cache);
    }

    @Override public void load(StateIn in) throws IOException {
        in.ints(r);
        int f = in.i();
        z = (f & 1) != 0; cy = (f & 2) != 0; s = (f & 4) != 0; ov = (f & 8) != 0; g = (f & 16) != 0;
        alt1 = (f & 32) != 0; alt2 = (f & 64) != 0; b = (f & 128) != 0; irqFlag = (f & 256) != 0;
        int[] v = new int[16];
        in.ints(v);
        pbr = v[0]; rombr = v[1]; rambr = v[2]; cbr = v[3]; scbr = v[4]; scmr = v[5]; cfgr = v[6]; clsr = v[7]; bramr = v[8];
        por = v[9]; colr = v[10]; sreg = v[11]; dreg = v[12]; pipeline = v[13]; ramAddr = v[14]; cacheValid = v[15];
        budget = in.l();
        in.bytes(ram);
        in.bytes(cache);
    }
}
