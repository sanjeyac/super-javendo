package dev.superjavendo.apu;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

/** Sony SPC700 core. Memory-mapped I/O ($F0-$FF) is handled by {@link Apu} through the callbacks below. */
public final class Spc700 implements Stateful {
    public interface Bus {
        int read(int addr);
        void write(int addr, int v);
    }

    private static final int[] CYC = {
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 5, 4, 5, 4, 6, 8,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 6, 5, 2, 2, 4, 6,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 5, 4, 5, 4, 5, 4,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 6, 5, 2, 2, 3, 8,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 4, 4, 5, 4, 6, 6,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 4, 5, 2, 2, 4, 3,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 4, 4, 5, 4, 5, 5,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 5, 5, 2, 2, 3, 6,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 6, 5, 4, 5, 2, 4, 5,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 5, 5, 2, 2, 12, 5,
            3, 8, 4, 5, 3, 4, 3, 6, 2, 6, 4, 4, 5, 2, 4, 4,
            2, 8, 4, 5, 4, 5, 5, 6, 5, 5, 5, 5, 2, 2, 3, 4,
            3, 8, 4, 5, 4, 5, 4, 7, 2, 5, 6, 4, 5, 2, 4, 9,
            2, 8, 4, 5, 5, 6, 6, 7, 4, 5, 5, 5, 2, 2, 6, 3,
            2, 8, 4, 5, 3, 4, 3, 6, 2, 4, 5, 3, 4, 3, 4, 3,
            2, 8, 4, 5, 4, 5, 5, 6, 3, 4, 5, 4, 2, 2, 4, 3};

    private final Bus bus;
    public int a, x, y, sp, pc;
    private boolean fN, fV, fP, fB, fH, fI, fZ, fC;
    private int cycles;
    public boolean stopped;

    public Spc700(Bus bus) { this.bus = bus; }

    public void reset(int resetVector) {
        a = x = y = 0; sp = 0xEF; pc = resetVector;
        fN = fV = fP = fB = fH = fI = fZ = fC = false;
    }

    private int psw() {
        return (fN ? 0x80 : 0) | (fV ? 0x40 : 0) | (fP ? 0x20 : 0) | (fB ? 0x10 : 0)
                | (fH ? 8 : 0) | (fI ? 4 : 0) | (fZ ? 2 : 0) | (fC ? 1 : 0);
    }

    private void setPsw(int p) {
        fN = (p & 0x80) != 0; fV = (p & 0x40) != 0; fP = (p & 0x20) != 0; fB = (p & 0x10) != 0;
        fH = (p & 8) != 0; fI = (p & 4) != 0; fZ = (p & 2) != 0; fC = (p & 1) != 0;
    }

    // ---------------------------------------------------------------- access helpers
    private int rd(int addr) { return bus.read(addr & 0xFFFF) & 0xFF; }
    private void wr(int addr, int v) { bus.write(addr & 0xFFFF, v & 0xFF); }
    private int fetch() { int v = rd(pc); pc = (pc + 1) & 0xFFFF; return v; }
    private int fetch16() { int lo = fetch(); return lo | (fetch() << 8); }
    private int dp(int off) { return (fP ? 0x100 : 0) | (off & 0xFF); }
    private int rdDp(int off) { return rd(dp(off)); }
    private void wrDp(int off, int v) { wr(dp(off), v); }
    private int rdDp16(int off) { int lo = rdDp(off); return lo | (rdDp(off + 1) << 8); }
    private void wrDp16(int off, int v) { wrDp(off, v); wrDp(off + 1, v >> 8); }
    private void push(int v) { wr(0x100 | sp, v); sp = (sp - 1) & 0xFF; }
    private int pop() { sp = (sp + 1) & 0xFF; return rd(0x100 | sp); }
    private void push16(int v) { push(v >> 8); push(v); }
    private int pop16() { int lo = pop(); return lo | (pop() << 8); }

    private void nz(int v) { fN = (v & 0x80) != 0; fZ = (v & 0xFF) == 0; }
    private void nz16(int v) { fN = (v & 0x8000) != 0; fZ = (v & 0xFFFF) == 0; }

    // addressing: returns the effective address
    private int aDp() { return dp(fetch()); }
    private int aDpX() { return dp(fetch() + x); }
    private int aAbs() { return fetch16(); }
    private int aAbsX() { return (fetch16() + x) & 0xFFFF; }
    private int aAbsY() { return (fetch16() + y) & 0xFFFF; }
    private int aIndX() { int p = fetch() + x; return rdDp(p) | (rdDp(p + 1) << 8); }
    private int aIndY() { int p = fetch(); return ((rdDp(p) | (rdDp(p + 1) << 8)) + y) & 0xFFFF; }

    // ---------------------------------------------------------------- ALU
    private int alu(int op, int p, int q) {
        switch (op) {
            case 0: p |= q; nz(p); return p & 0xFF;
            case 1: p &= q; nz(p); return p & 0xFF;
            case 2: p ^= q; nz(p); return p & 0xFF;
            case 3: { int r = p - q; fC = p >= q; nz(r); return p; }      // CMP (result discarded)
            case 4: { int r = p + q + (fC ? 1 : 0);
                fH = ((p & 0xF) + (q & 0xF) + (fC ? 1 : 0)) > 0xF;
                fV = ((~(p ^ q)) & (p ^ r) & 0x80) != 0;
                fC = r > 0xFF; nz(r); return r & 0xFF; }
            default: { int r = p - q - (fC ? 0 : 1);
                fH = ((p & 0xF) - (q & 0xF) - (fC ? 0 : 1)) >= 0;
                fV = ((p ^ q) & (p ^ r) & 0x80) != 0;
                fC = r >= 0; nz(r); return r & 0xFF; }
        }
    }

    private int asl(int v) { fC = (v & 0x80) != 0; v = (v << 1) & 0xFF; nz(v); return v; }
    private int lsr(int v) { fC = (v & 1) != 0; v >>= 1; nz(v); return v; }
    private int rol(int v) { int c = fC ? 1 : 0; fC = (v & 0x80) != 0; v = ((v << 1) | c) & 0xFF; nz(v); return v; }
    private int ror(int v) { int c = fC ? 0x80 : 0; fC = (v & 1) != 0; v = (v >> 1) | c; nz(v); return v; }

    private void branch(boolean cond) {
        int off = (byte) fetch();
        if (cond) { pc = (pc + off) & 0xFFFF; cycles += 2; }
    }

    private int bitAddr(int[] bit) {
        int v = fetch16();
        bit[0] = v >> 13;
        return v & 0x1FFF;
    }

    private final int[] bitTmp = new int[1];

    /** Executes one instruction; returns SPC cycles used. */
    public int step() {
        if (stopped) return 2;
        int op = fetch();
        cycles = CYC[op];
        exec(op);
        return cycles;
    }

    private void exec(int op) {
        int lo = op & 0xF, hi = op >> 4;

        // ---- ALU group: OR/AND/EOR/CMP/ADC/SBC with 9 addressing forms
        if (op < 0xC0 && lo >= 4 && lo <= 9 && (op & 0xE0) != 0xE0) {
            int aluOp = op >> 5; // 0 OR,1 AND,2 EOR,3 CMP,4 ADC,5 SBC
            boolean odd = (op & 0x10) != 0;
            switch (lo) {
                case 4: { int addr = odd ? aDpX() : aDp(); a = alu3(aluOp, a, rd(addr)); return; }
                case 5: { int addr = odd ? aAbsX() : aAbs(); a = alu3(aluOp, a, rd(addr)); return; }
                case 6: { if (odd) { int addr = aAbsY(); a = alu3(aluOp, a, rd(addr)); }
                          else a = alu3(aluOp, a, rd(dp(x))); return; }
                case 7: { int addr = odd ? aIndY() : aIndX(); a = alu3(aluOp, a, rd(addr)); return; }
                case 8: {
                    if (!odd) { a = alu3(aluOp, a, fetch()); return; }
                    int imm = fetch(); int d = aDp();
                    int r = alu(aluOp, rd(d), imm); if (aluOp != 3) wr(d, r); return;
                }
                default: {
                    if (!odd) { int s = fetch(); int d = fetch();
                        int r = alu(aluOp, rd(dp(d)), rd(dp(s))); if (aluOp != 3) wr(dp(d), r); return; }
                    int r = alu(aluOp, rd(dp(x)), rd(dp(y))); if (aluOp != 3) wr(dp(x), r); return;
                }
            }
        }

        switch (op) {
            case 0x00: return;                                        // NOP
            case 0x0F: push16(pc); push(psw()); fB = true; fI = false; pc = rd(0xFFDE) | (rd(0xFFDF) << 8); return;
            case 0xEF: case 0xFF: stopped = true; return;             // SLEEP / STOP
            case 0x20: fP = false; return;
            case 0x40: fP = true; return;
            case 0x60: fC = false; return;
            case 0x80: fC = true; return;
            case 0xA0: fI = true; return;
            case 0xC0: fI = false; return;
            case 0xE0: fV = false; fH = false; return;
            case 0xED: fC = !fC; return;

            // ---- branches
            case 0x10: branch(!fN); return;
            case 0x30: branch(fN); return;
            case 0x50: branch(!fV); return;
            case 0x70: branch(fV); return;
            case 0x90: branch(!fC); return;
            case 0xB0: branch(fC); return;
            case 0xD0: branch(!fZ); return;
            case 0xF0: branch(fZ); return;
            case 0x2F: { int off = (byte) fetch(); pc = (pc + off) & 0xFFFF; return; }   // BRA: 4 cycles in the table, no taken-branch penalty

            // ---- bit-test branches / SET1 / CLR1
            default:
        }
        if (lo == 2 && (hi & 1) == 0) { int d = aDp(); wr(d, rd(d) | (1 << (hi >> 1))); return; }   // SET1
        if (lo == 2) { int d = aDp(); wr(d, rd(d) & ~(1 << (hi >> 1))); return; }                      // CLR1
        if (lo == 3) {                                                                                 // BBS / BBC
            int d = aDp(); int off = (byte) fetch();
            boolean set = (rd(d) & (1 << (hi >> 1))) != 0;
            if (set == ((hi & 1) == 0)) { pc = (pc + off) & 0xFFFF; cycles += 2; }
            return;
        }
        if (lo == 1) { // TCALL n
            push16(pc);
            int vec = 0xFFDE - 2 * hi;
            pc = rd(vec) | (rd(vec + 1) << 8);
            return;
        }

        switch (op) {
            // ---- MOV loads
            case 0xE8: a = fetch(); nz(a); return;
            case 0xE4: a = rd(aDp()); nz(a); return;
            case 0xF4: a = rd(aDpX()); nz(a); return;
            case 0xE5: a = rd(aAbs()); nz(a); return;
            case 0xF5: a = rd(aAbsX()); nz(a); return;
            case 0xF6: a = rd(aAbsY()); nz(a); return;
            case 0xE7: a = rd(aIndX()); nz(a); return;
            case 0xF7: a = rd(aIndY()); nz(a); return;
            case 0xE6: a = rd(dp(x)); nz(a); return;
            case 0xBF: a = rd(dp(x)); x = (x + 1) & 0xFF; nz(a); return;
            case 0xCD: x = fetch(); nz(x); return;
            case 0xF8: x = rd(aDp()); nz(x); return;
            case 0xF9: x = rd(dp(fetch() + y)); nz(x); return;
            case 0xE9: x = rd(aAbs()); nz(x); return;
            case 0x8D: y = fetch(); nz(y); return;
            case 0xEB: y = rd(aDp()); nz(y); return;
            case 0xFB: y = rd(aDpX()); nz(y); return;
            case 0xEC: y = rd(aAbs()); nz(y); return;
            // ---- MOV stores
            case 0xC4: wr(aDp(), a); return;
            case 0xD4: wr(aDpX(), a); return;
            case 0xC5: wr(aAbs(), a); return;
            case 0xD5: wr(aAbsX(), a); return;
            case 0xD6: wr(aAbsY(), a); return;
            case 0xC7: wr(aIndX(), a); return;
            case 0xD7: wr(aIndY(), a); return;
            case 0xC6: wr(dp(x), a); return;
            case 0xAF: wr(dp(x), a); x = (x + 1) & 0xFF; return;
            case 0xD8: wr(aDp(), x); return;
            case 0xD9: wr(dp(fetch() + y), x); return;
            case 0xC9: wr(aAbs(), x); return;
            case 0xCB: wr(aDp(), y); return;
            case 0xDB: wr(aDpX(), y); return;
            case 0xCC: wr(aAbs(), y); return;
            // ---- MOV register/memory forms
            case 0x7D: a = x; nz(a); return;
            case 0xDD: a = y; nz(a); return;
            case 0x5D: x = a; nz(x); return;
            case 0xFD: y = a; nz(y); return;
            case 0x9D: x = sp; nz(x); return;
            case 0xBD: sp = x; return;
            case 0x8F: { int imm = fetch(); wr(aDp(), imm); return; }
            case 0xFA: { int s = fetch(); int d = fetch(); wr(dp(d), rd(dp(s))); return; }
            case 0xBA: { int v = rdDp16(fetch()); a = v & 0xFF; y = v >> 8; nz16(v); return; }
            case 0xDA: { wrDp16(fetch(), a | (y << 8)); return; }

            // ---- CMP X / Y
            case 0xC8: cmp(x, fetch()); return;
            case 0x3E: cmp(x, rd(aDp())); return;
            case 0x1E: cmp(x, rd(aAbs())); return;
            case 0xAD: cmp(y, fetch()); return;
            case 0x7E: cmp(y, rd(aDp())); return;
            case 0x5E: cmp(y, rd(aAbs())); return;

            // ---- INC / DEC
            case 0xBC: a = (a + 1) & 0xFF; nz(a); return;
            case 0x3D: x = (x + 1) & 0xFF; nz(x); return;
            case 0xFC: y = (y + 1) & 0xFF; nz(y); return;
            case 0x9C: a = (a - 1) & 0xFF; nz(a); return;
            case 0x1D: x = (x - 1) & 0xFF; nz(x); return;
            case 0xDC: y = (y - 1) & 0xFF; nz(y); return;
            case 0xAB: { int d = aDp(); int v = (rd(d) + 1) & 0xFF; wr(d, v); nz(v); return; }
            case 0xBB: { int d = aDpX(); int v = (rd(d) + 1) & 0xFF; wr(d, v); nz(v); return; }
            case 0xAC: { int d = aAbs(); int v = (rd(d) + 1) & 0xFF; wr(d, v); nz(v); return; }
            case 0x8B: { int d = aDp(); int v = (rd(d) - 1) & 0xFF; wr(d, v); nz(v); return; }
            case 0x9B: { int d = aDpX(); int v = (rd(d) - 1) & 0xFF; wr(d, v); nz(v); return; }
            case 0x8C: { int d = aAbs(); int v = (rd(d) - 1) & 0xFF; wr(d, v); nz(v); return; }

            // ---- shifts
            case 0x1C: a = asl(a); return;
            case 0x3C: a = rol(a); return;
            case 0x5C: a = lsr(a); return;
            case 0x7C: a = ror(a); return;
            case 0x0B: { int d = aDp(); wr(d, asl(rd(d))); return; }
            case 0x1B: { int d = aDpX(); wr(d, asl(rd(d))); return; }
            case 0x0C: { int d = aAbs(); wr(d, asl(rd(d))); return; }
            case 0x2B: { int d = aDp(); wr(d, rol(rd(d))); return; }
            case 0x3B: { int d = aDpX(); wr(d, rol(rd(d))); return; }
            case 0x2C: { int d = aAbs(); wr(d, rol(rd(d))); return; }
            case 0x4B: { int d = aDp(); wr(d, lsr(rd(d))); return; }
            case 0x5B: { int d = aDpX(); wr(d, lsr(rd(d))); return; }
            case 0x4C: { int d = aAbs(); wr(d, lsr(rd(d))); return; }
            case 0x6B: { int d = aDp(); wr(d, ror(rd(d))); return; }
            case 0x7B: { int d = aDpX(); wr(d, ror(rd(d))); return; }
            case 0x6C: { int d = aAbs(); wr(d, ror(rd(d))); return; }
            case 0x9F: a = ((a << 4) | (a >> 4)) & 0xFF; nz(a); return; // XCN

            // ---- 16-bit
            case 0x3A: { int d = fetch(); int v = (rdDp16(d) + 1) & 0xFFFF; wrDp16(d, v); nz16(v); return; }  // INCW
            case 0x1A: { int d = fetch(); int v = (rdDp16(d) - 1) & 0xFFFF; wrDp16(d, v); nz16(v); return; }  // DECW
            case 0x5A: { int v = rdDp16(fetch()); int ya = a | (y << 8); fC = ya >= v; nz16(ya - v); return; } // CMPW
            case 0x7A: { // ADDW
                int v = rdDp16(fetch()); int ya = a | (y << 8);
                int lowCarry = ((ya & 0xFF) + (v & 0xFF)) > 0xFF ? 1 : 0;
                int r = ya + v;
                fH = (((ya >> 8) & 0xF) + ((v >> 8) & 0xF) + lowCarry) > 0xF;
                fV = ((~(ya ^ v)) & (ya ^ r) & 0x8000) != 0;
                fC = r > 0xFFFF; a = r & 0xFF; y = (r >> 8) & 0xFF; nz16(r); return;
            }
            case 0x9A: { // SUBW
                int v = rdDp16(fetch()); int ya = a | (y << 8);
                int lowBorrow = (ya & 0xFF) < (v & 0xFF) ? 1 : 0;
                int r = ya - v;
                fH = (((ya >> 8) & 0xF) - ((v >> 8) & 0xF) - lowBorrow) >= 0;
                fV = ((ya ^ v) & (ya ^ r) & 0x8000) != 0;
                fC = r >= 0; a = r & 0xFF; y = (r >> 8) & 0xFF; nz16(r); return;
            }
            case 0xCF: { int r = y * a; a = r & 0xFF; y = r >> 8; nz(y); return; }   // MUL
            case 0x9E: { // DIV YA,X
                int ya = a | (y << 8);
                fH = (y & 15) >= (x & 15);
                fV = y >= x;
                if (y < (x << 1)) {
                    a = x == 0 ? 0xFF : (ya / x) & 0xFF;
                    y = x == 0 ? ya & 0xFF : (ya % x) & 0xFF;
                } else {
                    int d = 256 - x;
                    a = (255 - (ya - (x << 9)) / d) & 0xFF;
                    y = (x + (ya - (x << 9)) % d) & 0xFF;
                }
                nz(a); return;
            }
            case 0xDF: { // DAA
                if (fC || a > 0x99) { a += 0x60; fC = true; }
                if (fH || (a & 15) > 9) a += 6;
                a &= 0xFF; nz(a); return;
            }
            case 0xBE: { // DAS
                if (!fC || a > 0x99) { a -= 0x60; fC = false; }
                if (!fH || (a & 15) > 9) a -= 6;
                a &= 0xFF; nz(a); return;
            }

            // ---- stack
            case 0x2D: push(a); return;
            case 0x4D: push(x); return;
            case 0x6D: push(y); return;
            case 0x0D: push(psw()); return;
            case 0xAE: a = pop(); return;
            case 0xCE: x = pop(); return;
            case 0xEE: y = pop(); return;
            case 0x8E: setPsw(pop()); return;

            // ---- jumps / calls
            case 0x5F: pc = fetch16(); return;
            case 0x1F: { int p = (fetch16() + x) & 0xFFFF; pc = rd(p) | (rd(p + 1) << 8); return; }
            case 0x3F: { int t = fetch16(); push16(pc); pc = t; return; }
            case 0x4F: { int u = fetch(); push16(pc); pc = 0xFF00 | u; return; }
            case 0x6F: pc = pop16(); return;
            case 0x7F: setPsw(pop()); pc = pop16(); return;

            case 0x2E: { int d = aDp(); int off = (byte) fetch(); if (a != rd(d)) { pc = (pc + off) & 0xFFFF; cycles += 2; } return; }
            case 0xDE: { int d = aDpX(); int off = (byte) fetch(); if (a != rd(d)) { pc = (pc + off) & 0xFFFF; cycles += 2; } return; }
            case 0x6E: { int d = aDp(); int off = (byte) fetch(); int v = (rd(d) - 1) & 0xFF; wr(d, v); if (v != 0) { pc = (pc + off) & 0xFFFF; cycles += 2; } return; }
            case 0xFE: { int off = (byte) fetch(); y = (y - 1) & 0xFF; if (y != 0) { pc = (pc + off) & 0xFFFF; cycles += 2; } return; }

            // ---- TSET1 / TCLR1
            case 0x0E: { int d = aAbs(); int v = rd(d); nz(a - v); wr(d, v | a); return; }
            case 0x4E: { int d = aAbs(); int v = rd(d); nz(a - v); wr(d, v & ~a); return; }

            // ---- bit operations on carry
            case 0x0A: { int ad = bitAddr(bitTmp); if ((rd(ad) >> bitTmp[0] & 1) != 0) fC = true; return; }
            case 0x2A: { int ad = bitAddr(bitTmp); if ((rd(ad) >> bitTmp[0] & 1) == 0) fC = true; return; }
            case 0x4A: { int ad = bitAddr(bitTmp); if ((rd(ad) >> bitTmp[0] & 1) == 0) fC = false; return; }
            case 0x6A: { int ad = bitAddr(bitTmp); if ((rd(ad) >> bitTmp[0] & 1) != 0) fC = false; return; }
            case 0x8A: { int ad = bitAddr(bitTmp); fC ^= ((rd(ad) >> bitTmp[0]) & 1) != 0; return; }
            case 0xAA: { int ad = bitAddr(bitTmp); fC = ((rd(ad) >> bitTmp[0]) & 1) != 0; return; }
            case 0xCA: { int ad = bitAddr(bitTmp); int v = rd(ad); wr(ad, fC ? v | (1 << bitTmp[0]) : v & ~(1 << bitTmp[0])); return; }
            case 0xEA: { int ad = bitAddr(bitTmp); wr(ad, rd(ad) ^ (1 << bitTmp[0])); return; }

            default:
                throw new IllegalStateException(String.format("SPC700: unimplemented opcode %02X at %04X", op, (pc - 1) & 0xFFFF));
        }
    }

    private int alu3(int aluOp, int p, int q) {
        int r = alu(aluOp, p, q);
        return aluOp == 3 ? p : r;
    }

    private void cmp(int reg, int v) { fC = reg >= v; nz(reg - v); }

    @Override
    public void save(StateOut o) throws IOException {
        o.i(a); o.i(x); o.i(y); o.i(sp); o.i(pc);
        o.b(fN); o.b(fV); o.b(fP); o.b(fB); o.b(fH); o.b(fI); o.b(fZ); o.b(fC); o.b(stopped);
    }

    @Override
    public void load(StateIn in) throws IOException {
        a = in.i(); x = in.i(); y = in.i(); sp = in.i(); pc = in.i();
        fN = in.b(); fV = in.b(); fP = in.b(); fB = in.b(); fH = in.b(); fI = in.b(); fZ = in.b(); fC = in.b(); stopped = in.b();
    }
}
