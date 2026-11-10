package dev.superjavendo.bus;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

import dev.superjavendo.apu.Apu;
import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.cpu.Cpu65816;
import dev.superjavendo.dma.Dma;
import dev.superjavendo.input.Joypad;
import dev.superjavendo.ppu.Ppu;
import dev.superjavendo.timing.Timing;

/**
 * SNES system bus: WRAM, cartridge, CPU-side registers ($4200-$421F), DMA, joypads.
 * PPU registers go to {@link Ppu}, APU ports to {@link Apu}.
 */
public final class Bus implements Memory, Stateful {
    private final Cartridge cart;
    private final byte[] wram = new byte[0x20000];
    public final Timing timing = new Timing();
    public final Dma dma = new Dma(this);
    public final Joypad joypad = new Joypad();
    public final Ppu ppu = new Ppu();
    public final Apu apu = new Apu();
    private Cpu65816 cpu;

    private int wramAddr;
    private int wrio = 0xFF;
    private int memsel;

    // hardware multiply / divide
    private int wrmpya = 0xFF, wrdiv = 0xFFFF;
    private int rddiv, rdmpy;

    private int stallClocks;

    public Bus(Cartridge cart) { this.cart = cart; }

    public void attachCpu(Cpu65816 cpu) { this.cpu = cpu; }

    /** Master clocks the CPU has been stalled by DMA since the last call. */
    public int takeStall() { int s = stallClocks; stallClocks = 0; return s; }

    @Override
    public int read(int addr) {
        int bank = (addr >> 16) & 0xFF, off = addr & 0xFFFF;
        if (bank == 0x7E || bank == 0x7F) return wram[((bank & 1) << 16) | off] & 0xFF;
        if ((bank & 0x7F) < 0x40) {
            if (off < 0x2000) return wram[off] & 0xFF;
            if (off < 0x6000) return regRead(off);
        }
        return cart.read(bank, off);
    }

    @Override
    public void write(int addr, int v) {
        int bank = (addr >> 16) & 0xFF, off = addr & 0xFFFF;
        v &= 0xFF;
        if (watch2 && cpu != null && (addr == 0xB2 || addr == 0xB3 || ((addr & 0xFFFF) == ((cpu.d + 0xB2) & 0xFFFF)) && cpu.d != 0)) System.out.printf("WRAM f%d %06X=%02X from PC=%06X A=%04X X=%04X Y=%04X D=%04X%n", ppu.traceFrame, addr, v, cpu.pcFull(), cpu.a, cpu.x, cpu.y, cpu.d);
        if (bank == 0x7E || bank == 0x7F) { wram[((bank & 1) << 16) | off] = (byte) v; return; }
        if ((bank & 0x7F) < 0x40) {
            if (off < 0x2000) { wram[off] = (byte) v; return; }
            if (off < 0x6000) { regWrite(off, v); return; }
        }
        cart.write(bank, off, v);
    }

    // ------------------------------------------------------------------ reads

    public java.util.TreeMap<Integer,Integer> readHist;
    private int regRead(int off) {
        Timing t = timing;
        if (readHist != null) readHist.merge(off, 1, Integer::sum);
        if (off >= 0x4300 && off < 0x4380) return dma.readReg(off & 0xFF);
        if (off >= 0x2140 && off < 0x2180) return apu.readPort(off & 3);
        switch (off) {
            case 0x2137: t.latch(); return 0;
            case 0x213C: { int v = t.toggleH ? (t.latchedH >> 8) & 1 : t.latchedH & 0xFF; t.toggleH = !t.toggleH; return v; }
            case 0x213D: { int v = t.toggleV ? (t.latchedV >> 8) & 1 : t.latchedV & 0xFF; t.toggleV = !t.toggleV; return v; }
            case 0x213F: t.toggleH = t.toggleV = false; return 0x03;
            case 0x2134: case 0x2135: case 0x2136: case 0x2138: case 0x2139: case 0x213A: case 0x213B: case 0x213E:
                return ppu.read(off & 0xFF);
            case 0x2180: { int v = wram[wramAddr] & 0xFF; wramAddr = (wramAddr + 1) & 0x1FFFF; return v; }
            case 0x4016: return joypad.readBit(0);
            case 0x4017: return joypad.readBit(1) | 0x1C;
            case 0x4210: { int v = (t.nmiFlag ? 0x80 : 0) | 0x02; t.nmiFlag = false; return v; }
            case 0x4211: { int v = t.irqFlag ? 0x80 : 0; t.irqFlag = false; return v; }
            case 0x4212: return (t.inVBlank() ? 0x80 : 0) | (t.inHBlank() ? 0x40 : 0) | (t.autoJoyBusy ? 1 : 0);
            case 0x4213: return wrio;
            case 0x4214: return rddiv & 0xFF;
            case 0x4215: return rddiv >> 8;
            case 0x4216: return rdmpy & 0xFF;
            case 0x4217: return rdmpy >> 8;
            default:
                if (off >= 0x4218 && off < 0x4220) return joypad.autoReg(off - 0x4218);
                return 0;
        }
    }

    // ------------------------------------------------------------------ writes

    public boolean watch, watch2, ioTrace;
    private void regWrite(int off, int v) {
        Timing t = timing;
        if (ioTrace && off >= 0x4200 && off <= 0x420D) System.out.printf("f%d v=%d h=%d: W %04X=%02X PC=%06X%n", ppu.traceFrame, timing.v, timing.h, off, v, cpu != null ? cpu.pcFull() : 0);
        if (watch && (off == 0x210D || off == 0x210F) && cpu != null) System.out.printf("W %04X=%02X from PC=%06X A=%04X X=%04X Y=%04X%n", off, v, cpu.pcFull(), cpu.a, cpu.x, cpu.y);
        if (off >= 0x4300 && off < 0x4380) { dma.writeReg(off & 0xFF, v); return; }
        if (off >= 0x2100 && off < 0x2134) { ppu.write(off & 0xFF, v); return; }
        if (off >= 0x2140 && off < 0x2180) { apu.writePort(off & 3, v); return; }
        switch (off) {
            case 0x2180: wram[wramAddr] = (byte) v; wramAddr = (wramAddr + 1) & 0x1FFFF; break;
            case 0x2181: wramAddr = (wramAddr & 0x1FF00) | v; break;
            case 0x2182: wramAddr = (wramAddr & 0x100FF) | (v << 8); break;
            case 0x2183: wramAddr = (wramAddr & 0x0FFFF) | ((v & 1) << 16); break;
            case 0x4016: joypad.writeStrobe(v); break;
            case 0x4200: {
                boolean wasNmi = (t.nmitimen & 0x80) != 0;
                t.nmitimen = v;
                if ((v & 0x30) == 0) t.irqFlag = false;
                if (!wasNmi && (v & 0x80) != 0 && t.nmiFlag && cpu != null) cpu.nmiPending = true;
                break;
            }
            case 0x4201: wrio = v; break;
            case 0x4202: wrmpya = v; break;
            case 0x4203: rdmpy = wrmpya * v; break; // product shares the remainder register
            case 0x4204: wrdiv = (wrdiv & 0xFF00) | v; break;
            case 0x4205: wrdiv = (wrdiv & 0x00FF) | (v << 8); break;
            case 0x4206:
                if (v == 0) { rddiv = 0xFFFF; rdmpy = wrdiv; }
                else { rddiv = wrdiv / v; rdmpy = wrdiv % v; }
                break;
            case 0x4207: t.htime = (t.htime & 0x100) | v; break;
            case 0x4208: t.htime = (t.htime & 0x0FF) | ((v & 1) << 8); break;
            case 0x4209: t.vtime = (t.vtime & 0x100) | v; break;
            case 0x420A: t.vtime = (t.vtime & 0x0FF) | ((v & 1) << 8); break;
            case 0x420B: stallClocks += dma.runGeneral(v); break;
            case 0x420C: dma.hdmaEnable = v; break;
            case 0x420D: memsel = v; break;
            default: break;
        }
    }

    @Override
    public void save(StateOut o) throws IOException {
        o.bytes(wram); o.i(wramAddr); o.i(wrio); o.i(memsel);
        o.i(wrmpya); o.i(wrdiv); o.i(rddiv); o.i(rdmpy); o.i(stallClocks);
        o.bytes(cart.sram);
        timing.save(o); dma.save(o); joypad.save(o); ppu.save(o); apu.save(o);
    }

    @Override
    public void load(StateIn in) throws IOException {
        in.bytes(wram); wramAddr = in.i(); wrio = in.i(); memsel = in.i();
        wrmpya = in.i(); wrdiv = in.i(); rddiv = in.i(); rdmpy = in.i(); stallClocks = in.i();
        in.bytes(cart.sram);
        timing.load(in); dma.load(in); joypad.load(in); ppu.load(in); apu.load(in);
    }
}
