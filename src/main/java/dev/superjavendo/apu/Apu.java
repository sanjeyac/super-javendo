package dev.superjavendo.apu;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

/** SPC700 + DSP + timers + CPU communication ports ($2140-$2143). */
public final class Apu implements Spc700.Bus, Stateful {
    public interface Sink { void sample(int left, int right); }

    private static final int[] IPL = toInts(
            0xCD, 0xEF, 0xBD, 0xE8, 0x00, 0xC6, 0x1D, 0xD0, 0xFC, 0x8F, 0xAA, 0xF4, 0x8F, 0xBB, 0xF5, 0x78,
            0xCC, 0xF4, 0xD0, 0xFB, 0x2F, 0x19, 0xEB, 0xF4, 0xD0, 0xFC, 0x7E, 0xF4, 0xD0, 0x0B, 0xE4, 0xF5,
            0xCB, 0xF4, 0xD7, 0x00, 0xFC, 0xD0, 0xF3, 0xAB, 0x01, 0x10, 0xEF, 0x7E, 0xF4, 0x10, 0xEB, 0xBA,
            0xF6, 0xDA, 0x00, 0xBA, 0xF4, 0xC4, 0xF4, 0xDD, 0x5D, 0xD0, 0xDB, 0x1F, 0x00, 0x00, 0xC0, 0xFF);

    private static int[] toInts(int... v) { return v; }

    private static final long CPU_HZ = 21477270L, SPC_HZ = 1024000L;

    private final byte[] ram = new byte[0x10000];
    public final Spc700 spc = new Spc700(this);
    public final Dsp dsp = new Dsp(ram);
    private final int[] cpuToSpc = new int[4], spcToCpu = new int[4];
    private boolean iplEnabled = true;
    private int dspAddr;
    private final int[] target = new int[3], stage = new int[3], counter = new int[3], div = new int[3];
    private final boolean[] timerOn = new boolean[3];
    private long credit;
    private int dspCycles;
    private Sink sink;

    public Apu() { reset(); }

    public void setSink(Sink s) { this.sink = s; }

    public void reset() {
        java.util.Arrays.fill(ram, (byte) 0);
        iplEnabled = true;
        dsp.reset();
        spc.reset(0xFFC0);
        credit = 0;
    }

    // CPU side
    public int readPort(int n) { return spcToCpu[n & 3]; }
    public void writePort(int n, int v) { cpuToSpc[n & 3] = v & 0xFF; }

    /** Advances the APU by the given number of S-CPU master clocks. */
    public void tick(int masterClocks) {
        credit += masterClocks * SPC_HZ;
        while (credit > 0) {
            int c = spc.step();
            credit -= c * CPU_HZ;
            clockTimers(c);
            dspCycles += c;
            while (dspCycles >= 32) {
                dspCycles -= 32;
                dsp.sample();
                if (sink != null) sink.sample(dsp.outL, dsp.outR);
            }
        }
    }

    private void clockTimers(int cycles) {
        for (int t = 0; t < 3; t++) {
            div[t] += cycles;
            int period = t < 2 ? 128 : 16;
            while (div[t] >= period) {
                div[t] -= period;
                if (!timerOn[t]) continue;
                int tg = target[t] == 0 ? 256 : target[t];
                if (++stage[t] >= tg) { stage[t] = 0; counter[t] = (counter[t] + 1) & 15; }
            }
        }
    }

    // SPC side
    @Override
    public int read(int a) {
        if (a >= 0xF0 && a < 0x100) {
            switch (a) {
                case 0xF2: return dspAddr;
                case 0xF3: return dsp.read(dspAddr);
                case 0xF4: case 0xF5: case 0xF6: case 0xF7: return cpuToSpc[a - 0xF4];
                case 0xFD: case 0xFE: case 0xFF: { int t = a - 0xFD; int v = counter[t]; counter[t] = 0; return v; }
                case 0xF8: case 0xF9: return ram[a] & 0xFF;
                default: return 0;
            }
        }
        if (a >= 0xFFC0 && iplEnabled) return IPL[a - 0xFFC0];
        return ram[a] & 0xFF;
    }

    @Override
    public void write(int a, int v) {
        v &= 0xFF;
        ram[a] = (byte) v;
        if (a < 0xF0 || a > 0xFF) return;
        switch (a) {
            case 0xF1:
                for (int t = 0; t < 3; t++) {
                    boolean en = (v & (1 << t)) != 0;
                    if (en && !timerOn[t]) { stage[t] = 0; counter[t] = 0; }
                    timerOn[t] = en;
                }
                if ((v & 0x10) != 0) { cpuToSpc[0] = cpuToSpc[1] = 0; }
                if ((v & 0x20) != 0) { cpuToSpc[2] = cpuToSpc[3] = 0; }
                iplEnabled = (v & 0x80) != 0;
                break;
            case 0xF2: dspAddr = v; break;
            case 0xF3: if (dspAddr < 0x80) dsp.write(dspAddr, v); break;
            case 0xF4: case 0xF5: case 0xF6: case 0xF7: spcToCpu[a - 0xF4] = v; break;
            case 0xFA: case 0xFB: case 0xFC: target[a - 0xFA] = v; break;
            default: break;
        }
    }

    @Override
    public void save(StateOut o) throws IOException {
        o.bytes(ram); o.ints(cpuToSpc); o.ints(spcToCpu); o.b(iplEnabled); o.i(dspAddr);
        o.ints(target); o.ints(stage); o.ints(counter); o.ints(div); o.bools(timerOn);
        o.l(credit); o.i(dspCycles);
        spc.save(o); dsp.save(o);
    }

    @Override
    public void load(StateIn in) throws IOException {
        in.bytes(ram); in.ints(cpuToSpc); in.ints(spcToCpu); iplEnabled = in.b(); dspAddr = in.i();
        in.ints(target); in.ints(stage); in.ints(counter); in.ints(div); in.bools(timerOn);
        credit = in.l(); dspCycles = in.i();
        spc.load(in); dsp.load(in);
    }
}
