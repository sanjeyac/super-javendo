package dev.superjavendo.cpu;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

import dev.superjavendo.bus.Memory;

/**
 * WDC 65C816 core. Cycle counts are approximated as one cycle per bus access
 * plus one for internal operations (good enough for a playable emulator).
 */
public final class Cpu65816 implements Stateful {
    // Addressing modes used by the regular ALU opcode group.
    private static final int DPXI = 0, SR = 1, DP = 2, DPL = 3, IMM = 4, ABS = 5, LONG = 6,
            DPIY = 7, DPI = 8, SRIY = 9, DPX = 10, DPLY = 11, ABSY = 12, ABSX = 13, LONGX = 14;

    private final Memory mem;

    // Registers
    public int a, x, y, s, d, db, pb, pc;
    public boolean fN, fV, fM, fX, fD, fI, fZ, fC, e;

    public boolean waiting, stopped;
    public boolean nmiPending, irqLine;
    public long cycles;
    /** Optional replacement for vector fetches ($FFEA.. etc.): returns the vector or -1 to read memory (SA-1 uses registers). */
    public java.util.function.IntUnaryOperator vectorOverride;

    public Cpu65816(Memory mem) {
        this.mem = mem;
    }

    // ---------------------------------------------------------------- state

    public void reset() {
        e = true; fM = fX = true; fI = true; fD = false;
        s = 0x01FF; d = 0; db = 0; pb = 0;
        x &= 0xFF; y &= 0xFF;
        waiting = stopped = false;
        pc = vector(0xFFFC);
    }

    public int getP() {
        return (fN ? 0x80 : 0) | (fV ? 0x40 : 0) | (fM ? 0x20 : 0) | (fX ? 0x10 : 0)
                | (fD ? 8 : 0) | (fI ? 4 : 0) | (fZ ? 2 : 0) | (fC ? 1 : 0);
    }

    public void setP(int p) {
        fN = (p & 0x80) != 0; fV = (p & 0x40) != 0; fD = (p & 8) != 0;
        fI = (p & 4) != 0; fZ = (p & 2) != 0; fC = (p & 1) != 0;
        if (e) { fM = fX = true; }
        else { fM = (p & 0x20) != 0; fX = (p & 0x10) != 0; }
        if (fX) { x &= 0xFF; y &= 0xFF; }
    }

    public int pcFull() { return (pb << 16) | pc; }

    // ---------------------------------------------------------------- bus helpers

    private int rd8(int addr) { cycles++; return mem.read(addr & 0xFFFFFF) & 0xFF; }
    private void wr8(int addr, int v) { cycles++; mem.write(addr & 0xFFFFFF, v & 0xFF); }
    private int rd(int addr, boolean wide) {
        int v = rd8(addr);
        return wide ? v | (rd8(addr + 1) << 8) : v;
    }
    private void wr(int addr, int v, boolean wide) {
        wr8(addr, v);
        if (wide) wr8(addr + 1, v >> 8);
    }
    private int read16(int addr) { return rd(addr, true); }

    private int vector(int addr) {
        int v = vectorOverride == null ? -1 : vectorOverride.applyAsInt(addr);
        if (v >= 0) { cycles += 2; return v & 0xFFFF; }
        return read16(addr);
    }

    private int fetch8() { int v = rd8((pb << 16) | pc); pc = (pc + 1) & 0xFFFF; return v; }
    private int fetch16() { int lo = fetch8(); return lo | (fetch8() << 8); }
    private int fetch24() { int lo = fetch16(); return lo | (fetch8() << 16); }

    private boolean m8() { return e || fM; }
    private boolean x8() { return e || fX; }

    private void push8(int v) {
        wr8(s, v);
        s = e ? 0x100 | ((s - 1) & 0xFF) : (s - 1) & 0xFFFF;
    }
    private void push16(int v) { push8(v >> 8); push8(v); }
    private int pull8() {
        s = e ? 0x100 | ((s + 1) & 0xFF) : (s + 1) & 0xFFFF;
        return rd8(s);
    }
    private int pull16() { int lo = pull8(); return lo | (pull8() << 8); }

    private void setNZ(int v, boolean wide) {
        if (wide) { fZ = (v & 0xFFFF) == 0; fN = (v & 0x8000) != 0; }
        else { fZ = (v & 0xFF) == 0; fN = (v & 0x80) != 0; }
    }

    // ---------------------------------------------------------------- addressing

    private int dpAddr(int off) { return (d + off) & 0xFFFF; }

    private int dpIndexed(int off, int idx) {
        if (e && (d & 0xFF) == 0) return d | ((off + idx) & 0xFF);
        return (d + off + idx) & 0xFFFF;
    }

    private int ea(int mode) {
        switch (mode) {
            case DPXI: { int p = dpIndexed(fetch8(), x); cycles++; return (db << 16) | read16(p); }
            case SR:   { cycles++; return (s + fetch8()) & 0xFFFF; }
            case DP:   { int o = fetch8(); if ((d & 0xFF) != 0) cycles++; return dpAddr(o); }
            case DPL:  { int p = dpAddr(fetch8()); return rd(p, true) | (rd8(p + 2) << 16); }
            case ABS:  return (db << 16) | fetch16();
            case LONG: return fetch24();
            case DPIY: { int p = dpAddr(fetch8()); return ((db << 16) + read16(p) + y) & 0xFFFFFF; }
            case DPI:  { int p = dpAddr(fetch8()); return (db << 16) | read16(p); }
            case SRIY: { cycles++; int p = (s + fetch8()) & 0xFFFF; cycles++; return ((db << 16) + read16(p) + y) & 0xFFFFFF; }
            case DPX:  { int o = fetch8(); cycles++; return dpIndexed(o, x); }
            case DPLY: { int p = dpAddr(fetch8()); int l = rd(p, true) | (rd8(p + 2) << 16); return (l + y) & 0xFFFFFF; }
            case ABSY: return ((db << 16) + fetch16() + y) & 0xFFFFFF;
            case ABSX: return ((db << 16) + fetch16() + x) & 0xFFFFFF;
            case LONGX: return (fetch24() + x) & 0xFFFFFF;
            default: throw new IllegalStateException("mode " + mode);
        }
    }

    private int eaDpY() { int o = fetch8(); cycles++; return dpIndexed(o, y); }

    // ---------------------------------------------------------------- ALU

    private void adc(int v, boolean w) {
        int mask = w ? 0xFFFF : 0xFF, sign = w ? 0x8000 : 0x80;
        int acc = a & mask, c = fC ? 1 : 0, r;
        if (!fD) {
            r = acc + v + c;
            fV = ((~(acc ^ v)) & (acc ^ r) & sign) != 0;
        } else {
            r = 0;
            int digits = w ? 4 : 2, carry = c, t = 0;
            for (int i = 0; i < digits; i++) {
                int sh = 4 * i;
                int dg = ((acc >> sh) & 0xF) + ((v >> sh) & 0xF) + carry;
                if (i == digits - 1) t = r | (dg << sh);
                if (dg > 9) dg += 6;
                carry = dg > 0xF ? 1 : 0;
                r |= (dg & 0xF) << sh;
            }
            fV = ((~(acc ^ v)) & (acc ^ t) & sign) != 0;
            r |= carry << (4 * digits);
        }
        fC = r > mask;
        setAcc(r & mask, w);
    }

    private void sbc(int v, boolean w) {
        int mask = w ? 0xFFFF : 0xFF, sign = w ? 0x8000 : 0x80;
        int acc = a & mask, c = fC ? 1 : 0;
        int bin = acc + (~v & mask) + c;
        fV = ((acc ^ v) & (acc ^ bin) & sign) != 0;
        if (!fD) {
            fC = bin > mask;
            setAcc(bin & mask, w);
        } else {
            int digits = w ? 4 : 2, borrow = 1 - c, r = 0;
            for (int i = 0; i < digits; i++) {
                int sh = 4 * i;
                int dg = ((acc >> sh) & 0xF) - ((v >> sh) & 0xF) - borrow;
                if (dg < 0) { dg -= 6; borrow = 1; } else borrow = 0;
                r |= (dg & 0xF) << sh;
            }
            fC = borrow == 0;
            setAcc(r & mask, w);
        }
    }

    private void setAcc(int v, boolean w) {
        if (w) a = v & 0xFFFF; else a = (a & 0xFF00) | (v & 0xFF);
        setNZ(v, w);
    }

    private void compare(int reg, int v, boolean w) {
        int mask = w ? 0xFFFF : 0xFF;
        int r = (reg & mask) - v;
        fC = (reg & mask) >= v;
        setNZ(r, w);
    }

    private int shift(int kind, int v, boolean w) {
        int mask = w ? 0xFFFF : 0xFF, sign = w ? 0x8000 : 0x80, r;
        boolean oc = fC;
        switch (kind) {
            case 0: fC = (v & sign) != 0; r = (v << 1) & mask; break;               // ASL
            case 1: fC = (v & sign) != 0; r = ((v << 1) | (oc ? 1 : 0)) & mask; break; // ROL
            case 2: fC = (v & 1) != 0; r = v >> 1; break;                            // LSR
            case 3: fC = (v & 1) != 0; r = (v >> 1) | (oc ? sign : 0); break;         // ROR
            case 4: r = (v - 1) & mask; break;                                       // DEC
            default: r = (v + 1) & mask;                                             // INC
        }
        setNZ(r, w);
        return r;
    }

    /** Read-modify-write on memory (shift/inc/dec). */
    private void rmw(int kind, int addr) {
        boolean w = !m8();
        int v = rd(addr, w);
        cycles++;
        wr(addr, shift(kind, v, w), w);
    }

    private void accShift(int kind) {
        boolean w = !m8();
        cycles++;
        setAccRaw(shift(kind, w ? a : a & 0xFF, w), w);
    }

    private void setAccRaw(int v, boolean w) { // no flag update
        if (w) a = v & 0xFFFF; else a = (a & 0xFF00) | (v & 0xFF);
    }

    private void bit(int v, boolean imm) {
        boolean w = !m8();
        int acc = w ? a : a & 0xFF;
        fZ = (acc & v) == 0;
        if (!imm) {
            fN = (v & (w ? 0x8000 : 0x80)) != 0;
            fV = (v & (w ? 0x4000 : 0x40)) != 0;
        }
    }

    private void branch(boolean cond) {
        int off = (byte) fetch8();
        if (cond) { cycles++; pc = (pc + off) & 0xFFFF; }
    }

    private void loadIndex(int which, int v) {
        boolean w = !x8();
        if (which == 0) x = v & (w ? 0xFFFF : 0xFF); else y = v & (w ? 0xFFFF : 0xFF);
        setNZ(v, w);
    }

    private void incIndex(int which, int delta) {
        boolean w = !x8();
        int mask = w ? 0xFFFF : 0xFF;
        cycles++;
        if (which == 0) { x = (x + delta) & mask; setNZ(x, w); }
        else { y = (y + delta) & mask; setNZ(y, w); }
    }

    // ---------------------------------------------------------------- interrupts

    private void interrupt(int vecNative, int vecEmu, boolean brk) {
        if (!e) push8(pb);
        push16(pc);
        int p = getP();
        if (e) p = (p & ~0x10) | (brk ? 0x10 : 0) | 0x20;
        push8(p);
        fD = false; fI = true;
        pb = 0;
        pc = vector(e ? vecEmu : vecNative);
    }

    // ---------------------------------------------------------------- execution

    /** Executes one instruction (or services an interrupt). Returns cycles consumed. */
    /** Debug: print one line per executed instruction (PC, opcode, registers) to this stream. */
    public java.io.PrintStream trace;

    public int step() {
        long start = cycles;
        if (stopped) { cycles += 1; return 1; }
        if (nmiPending) {
            nmiPending = false; waiting = false;
            interrupt(0xFFEA, 0xFFFA, false);
            cycles += 2;
            return (int) (cycles - start);
        }
        if (irqLine && !fI) {
            waiting = false;
            interrupt(0xFFEE, 0xFFFE, false);
            cycles += 2;
            return (int) (cycles - start);
        }
        if (waiting) {
            if (irqLine) waiting = false; // IRQ wakes WAI even when masked
            cycles += 1;
            return 1;
        }
        if (trace != null) trace.printf("%06X %02X A=%04X X=%04X Y=%04X S=%04X D=%04X DB=%02X P=%02X E=%d%n",
                pcFull(), mem.read(pcFull()) & 0xFF, a, x, y, this.s, d, db, getP(), e ? 1 : 0);
        execute(fetch8());
        return (int) (cycles - start);
    }

    private void execute(int op) {
        switch (op) {
            // ---- misc / control
            case 0xEA: cycles++; return;                             // NOP
            case 0x42: fetch8(); return;                             // WDM
            case 0x00: fetch8(); interrupt(0xFFE6, 0xFFFE, true); return; // BRK
            case 0x02: fetch8(); interrupt(0xFFE4, 0xFFF4, true); return; // COP
            case 0xCB: waiting = true; cycles += 2; return;          // WAI
            case 0xDB: stopped = true; cycles += 2; return;          // STP
            case 0x18: fC = false; cycles++; return;
            case 0x38: fC = true; cycles++; return;
            case 0x58: fI = false; cycles++; return;
            case 0x78: fI = true; cycles++; return;
            case 0xD8: fD = false; cycles++; return;
            case 0xF8: fD = true; cycles++; return;
            case 0xB8: fV = false; cycles++; return;
            case 0xC2: { int m = fetch8(); setP(getP() & ~m); if (e) { fM = fX = true; } cycles++; return; } // REP
            case 0xE2: { int m = fetch8(); setP(getP() | m); cycles++; return; } // SEP
            case 0xFB: { // XCE
                boolean oc = fC; fC = e; e = oc; cycles++;
                if (e) { fM = fX = true; x &= 0xFF; y &= 0xFF; s = 0x100 | (s & 0xFF); }
                return;
            }
            case 0xEB: { int lo = a & 0xFF; a = ((a & 0xFF) << 8) | (a >> 8); setNZ(a & 0xFF, false); cycles += 2; return; } // XBA

            // ---- branches
            case 0x10: branch(!fN); return;
            case 0x30: branch(fN); return;
            case 0x50: branch(!fV); return;
            case 0x70: branch(fV); return;
            case 0x80: branch(true); return;
            case 0x90: branch(!fC); return;
            case 0xB0: branch(fC); return;
            case 0xD0: branch(!fZ); return;
            case 0xF0: branch(fZ); return;
            case 0x82: { int off = (short) fetch16(); pc = (pc + off) & 0xFFFF; cycles++; return; } // BRL

            // ---- jumps / calls
            case 0x4C: pc = fetch16(); return;
            case 0x5C: { int t = fetch24(); pc = t & 0xFFFF; pb = t >> 16; return; }
            case 0x6C: { int p = fetch16(); pc = read16(p); return; }
            case 0x7C: { int p = fetch16(); cycles++; pc = read16((pb << 16) | ((p + x) & 0xFFFF)); return; }
            case 0xDC: { int p = fetch16(); int t = read16(p) | (rd8(p + 2) << 16); pc = t & 0xFFFF; pb = t >> 16; return; }
            case 0x20: { int t = fetch16(); cycles++; push16((pc - 1) & 0xFFFF); pc = t; return; }
            case 0xFC: { int p = fetch16(); push16((pc - 1) & 0xFFFF); cycles++; pc = read16((pb << 16) | ((p + x) & 0xFFFF)); return; }
            case 0x22: { int lo = fetch16(); push8(pb); cycles++; int bank = fetch8(); push16((pc - 1) & 0xFFFF); pc = lo; pb = bank; return; }
            case 0x60: { cycles += 2; pc = (pull16() + 1) & 0xFFFF; return; }
            case 0x6B: { cycles += 2; pc = (pull16() + 1) & 0xFFFF; pb = pull8(); return; }
            case 0x40: { // RTI
                cycles += 2;
                setP(pull8());
                pc = pull16();
                if (!e) pb = pull8();
                return;
            }

            // ---- stack
            case 0x48: cycles++; if (m8()) push8(a); else push16(a); return;
            case 0x68: { cycles += 2; boolean w = !m8(); int v = w ? pull16() : pull8(); setAcc(v, w); return; }
            case 0xDA: cycles++; if (x8()) push8(x); else push16(x); return;
            case 0xFA: { cycles += 2; boolean w = !x8(); loadIndex(0, w ? pull16() : pull8()); return; }
            case 0x5A: cycles++; if (x8()) push8(y); else push16(y); return;
            case 0x7A: { cycles += 2; boolean w = !x8(); loadIndex(1, w ? pull16() : pull8()); return; }
            case 0x08: { cycles++; int p = getP(); if (e) p |= 0x30; push8(p); return; }
            case 0x28: { cycles += 2; setP(pull8()); return; }
            case 0x0B: cycles++; push16(d); return;
            case 0x2B: { cycles += 2; d = pull16(); setNZ(d, true); return; }
            case 0x8B: cycles++; push8(db); return;
            case 0xAB: { cycles += 2; db = pull8(); setNZ(db, false); return; }
            case 0x4B: cycles++; push8(pb); return;
            case 0xF4: push16(fetch16()); return;                                     // PEA
            case 0xD4: { int p = dpAddr(fetch8()); push16(read16(p)); return; }       // PEI
            case 0x62: { int off = (short) fetch16(); cycles++; push16((pc + off) & 0xFFFF); return; } // PER

            // ---- transfers
            case 0xAA: cycles++; loadIndex(0, x8() ? a & 0xFF : a); return;           // TAX
            case 0xA8: cycles++; loadIndex(1, x8() ? a & 0xFF : a); return;           // TAY
            case 0x8A: { cycles++; boolean w = !m8(); setAcc(w ? x : x & 0xFF, w); return; } // TXA
            case 0x98: { cycles++; boolean w = !m8(); setAcc(w ? y : y & 0xFF, w); return; } // TYA
            case 0xBA: cycles++; loadIndex(0, x8() ? s & 0xFF : s); return;           // TSX
            case 0x9A: cycles++; s = e ? 0x100 | (x & 0xFF) : x; return;              // TXS
            case 0x9B: cycles++; loadIndex(1, x); return;                             // TXY
            case 0xBB: cycles++; loadIndex(0, y); return;                             // TYX
            case 0x5B: cycles++; d = a; setNZ(d, true); return;                       // TCD
            case 0x7B: cycles++; a = d; setNZ(a, true); return;                       // TDC
            case 0x1B: cycles++; s = e ? 0x100 | (a & 0xFF) : a; return;              // TCS
            case 0x3B: cycles++; a = s; setNZ(a, true); return;                       // TSC

            // ---- index inc/dec
            case 0xE8: incIndex(0, 1); return;
            case 0xC8: incIndex(1, 1); return;
            case 0xCA: incIndex(0, -1); return;
            case 0x88: incIndex(1, -1); return;

            // ---- block moves
            case 0x54: case 0x44: {
                int dst = fetch8(), src = fetch8();
                db = dst;
                int delta = op == 0x54 ? 1 : -1;
                wr8((dst << 16) | y, rd8((src << 16) | x));
                int mask = x8() ? 0xFF : 0xFFFF;
                x = (x + delta) & mask; y = (y + delta) & mask;
                a = (a - 1) & 0xFFFF;
                cycles += 2;
                if (a != 0xFFFF) pc = (pc - 3) & 0xFFFF;
                return;
            }

            // ---- accumulator shifts / inc / dec
            case 0x0A: accShift(0); return;
            case 0x2A: accShift(1); return;
            case 0x4A: accShift(2); return;
            case 0x6A: accShift(3); return;
            case 0x3A: accShift(4); return;
            case 0x1A: accShift(5); return;

            // ---- memory shifts / inc / dec
            case 0x06: rmw(0, ea(DP)); return;  case 0x0E: rmw(0, ea(ABS)); return;
            case 0x16: rmw(0, ea(DPX)); return; case 0x1E: rmw(0, ea(ABSX)); return;
            case 0x26: rmw(1, ea(DP)); return;  case 0x2E: rmw(1, ea(ABS)); return;
            case 0x36: rmw(1, ea(DPX)); return; case 0x3E: rmw(1, ea(ABSX)); return;
            case 0x46: rmw(2, ea(DP)); return;  case 0x4E: rmw(2, ea(ABS)); return;
            case 0x56: rmw(2, ea(DPX)); return; case 0x5E: rmw(2, ea(ABSX)); return;
            case 0x66: rmw(3, ea(DP)); return;  case 0x6E: rmw(3, ea(ABS)); return;
            case 0x76: rmw(3, ea(DPX)); return; case 0x7E: rmw(3, ea(ABSX)); return;
            case 0xC6: rmw(4, ea(DP)); return;  case 0xCE: rmw(4, ea(ABS)); return;
            case 0xD6: rmw(4, ea(DPX)); return; case 0xDE: rmw(4, ea(ABSX)); return;
            case 0xE6: rmw(5, ea(DP)); return;  case 0xEE: rmw(5, ea(ABS)); return;
            case 0xF6: rmw(5, ea(DPX)); return; case 0xFE: rmw(5, ea(ABSX)); return;

            // ---- TSB / TRB
            case 0x04: case 0x0C: case 0x14: case 0x1C: {
                int addr = ea((op & 0x08) != 0 ? ABS : DP);
                boolean w = !m8();
                int v = rd(addr, w), acc = w ? a : a & 0xFF;
                fZ = (acc & v) == 0;
                cycles++;
                wr(addr, (op & 0x10) != 0 ? v & ~acc : v | acc, w);
                return;
            }

            // ---- BIT
            case 0x89: { boolean w = !m8(); bit(w ? fetch16() : fetch8(), true); return; }
            case 0x24: bit(rd(ea(DP), !m8()), false); return;
            case 0x2C: bit(rd(ea(ABS), !m8()), false); return;
            case 0x34: bit(rd(ea(DPX), !m8()), false); return;
            case 0x3C: bit(rd(ea(ABSX), !m8()), false); return;

            // ---- index loads / stores / compares
            case 0xA2: { boolean w = !x8(); loadIndex(0, w ? fetch16() : fetch8()); return; }
            case 0xA6: loadIndex(0, rd(ea(DP), !x8())); return;
            case 0xAE: loadIndex(0, rd(ea(ABS), !x8())); return;
            case 0xB6: loadIndex(0, rd(eaDpY(), !x8())); return;
            case 0xBE: loadIndex(0, rd(ea(ABSY), !x8())); return;
            case 0xA0: { boolean w = !x8(); loadIndex(1, w ? fetch16() : fetch8()); return; }
            case 0xA4: loadIndex(1, rd(ea(DP), !x8())); return;
            case 0xAC: loadIndex(1, rd(ea(ABS), !x8())); return;
            case 0xB4: loadIndex(1, rd(ea(DPX), !x8())); return;
            case 0xBC: loadIndex(1, rd(ea(ABSX), !x8())); return;
            case 0x86: wr(ea(DP), x, !x8()); return;
            case 0x8E: wr(ea(ABS), x, !x8()); return;
            case 0x96: wr(eaDpY(), x, !x8()); return;
            case 0x84: wr(ea(DP), y, !x8()); return;
            case 0x8C: wr(ea(ABS), y, !x8()); return;
            case 0x94: wr(ea(DPX), y, !x8()); return;
            case 0x64: wr(ea(DP), 0, !m8()); return;
            case 0x74: wr(ea(DPX), 0, !m8()); return;
            case 0x9C: wr(ea(ABS), 0, !m8()); return;
            case 0x9E: wr(ea(ABSX), 0, !m8()); return;
            case 0xE0: { boolean w = !x8(); compare(x, w ? fetch16() : fetch8(), w); return; }
            case 0xE4: compare(x, rd(ea(DP), !x8()), !x8()); return;
            case 0xEC: compare(x, rd(ea(ABS), !x8()), !x8()); return;
            case 0xC0: { boolean w = !x8(); compare(y, w ? fetch16() : fetch8(), w); return; }
            case 0xC4: compare(y, rd(ea(DP), !x8()), !x8()); return;
            case 0xCC: compare(y, rd(ea(ABS), !x8()), !x8()); return;

            default: aluGroup(op);
        }
    }

    /** ORA/AND/EOR/ADC/STA/LDA/CMP/SBC in all their addressing modes. */
    private void aluGroup(int op) {
        int mode;
        switch (op & 0x1F) {
            case 0x01: mode = DPXI; break;  case 0x03: mode = SR; break;
            case 0x05: mode = DP; break;    case 0x07: mode = DPL; break;
            case 0x09: mode = IMM; break;   case 0x0D: mode = ABS; break;
            case 0x0F: mode = LONG; break;  case 0x11: mode = DPIY; break;
            case 0x12: mode = DPI; break;   case 0x13: mode = SRIY; break;
            case 0x15: mode = DPX; break;   case 0x17: mode = DPLY; break;
            case 0x19: mode = ABSY; break;  case 0x1D: mode = ABSX; break;
            case 0x1F: mode = LONGX; break;
            default: throw new IllegalStateException(String.format("unimplemented opcode %02X", op));
        }
        int grp = op >> 5;
        boolean w = !m8();
        if (grp == 4) { wr(ea(mode), a, w); return; } // STA
        int v = mode == IMM ? (w ? fetch16() : fetch8()) : rd(ea(mode), w);
        switch (grp) {
            case 0: setAcc((w ? a : a & 0xFF) | v, w); break;
            case 1: setAcc((w ? a : a & 0xFF) & v, w); break;
            case 2: setAcc((w ? a : a & 0xFF) ^ v, w); break;
            case 3: adc(v, w); break;
            case 5: setAcc(v, w); break;
            case 6: compare(a, v, w); break;
            default: sbc(v, w);
        }
    }

    @Override
    public void save(StateOut o) throws IOException {
        o.i(a); o.i(x); o.i(y); o.i(s); o.i(d); o.i(db); o.i(pb); o.i(pc);
        o.b(fN); o.b(fV); o.b(fM); o.b(fX); o.b(fD); o.b(fI); o.b(fZ); o.b(fC); o.b(e);
        o.b(waiting); o.b(stopped); o.b(nmiPending); o.b(irqLine); o.l(cycles);
    }

    @Override
    public void load(StateIn in) throws IOException {
        a = in.i(); x = in.i(); y = in.i(); s = in.i(); d = in.i(); db = in.i(); pb = in.i(); pc = in.i();
        fN = in.b(); fV = in.b(); fM = in.b(); fX = in.b(); fD = in.b(); fI = in.b(); fZ = in.b(); fC = in.b(); e = in.b();
        waiting = in.b(); stopped = in.b(); nmiPending = in.b(); irqLine = in.b(); cycles = in.l();
    }
}
