package dev.superjavendo.apu;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

/** S-DSP: 8 BRR voices, ADSR/GAIN, noise, pitch modulation, echo. Produces one stereo sample per call (32 kHz). */
public final class Dsp implements Stateful {
    private static final int[] RATE = {
            0, 2048, 1536, 1280, 1024, 768, 640, 512, 384, 320, 256, 192, 160, 128, 96, 80,
            64, 48, 40, 32, 24, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2, 1};
    private static final int ATTACK = 0, DECAY = 1, SUSTAIN = 2, RELEASE = 3;

    private final byte[] ram;
    private final int[] reg = new int[128];

    // per-voice state
    private final int[] addr = new int[8], idx = new int[8], counter = new int[8];
    private final int[] header = new int[8], p1 = new int[8], p2 = new int[8];
    private final int[][] block = new int[8][16];
    private final int[][] hist = new int[8][4];
    private final int[] env = new int[8], state = new int[8];
    private final boolean[] on = new boolean[8];
    private final int[] outx = new int[8];

    private int endx, kon, noise = 0x4000, sampleCounter;
    private int echoPos;
    private final int[] firL = new int[8], firR = new int[8];
    private int firPos;

    public int outL, outR;

    public Dsp(byte[] ram) { this.ram = ram; }

    public void reset() {
        java.util.Arrays.fill(reg, 0);
        reg[0x6C] = 0xE0;
        java.util.Arrays.fill(on, false);
        java.util.Arrays.fill(env, 0);
        endx = 0; kon = 0;
    }

    public int read(int r) {
        r &= 0x7F;
        int v = reg[r];
        switch (r & 0xF) {
            case 8: if (r < 0x80) return env[r >> 4] >> 4; break;
            case 9: return outx[r >> 4] & 0xFF;
            default: break;
        }
        if (r == 0x7C) return endx;
        return v;
    }

    public void write(int r, int v) {
        r &= 0x7F; v &= 0xFF;
        reg[r] = v;
        if (r == 0x4C) kon |= v;
        else if (r == 0x7C) endx = 0;
        else if (r == 0x5C) { /* KOFF is read each sample */ }
    }

    private int rd16(int a) { return (ram[a & 0xFFFF] & 0xFF) | ((ram[(a + 1) & 0xFFFF] & 0xFF) << 8); }

    private static int clamp16(int v) { return v > 32767 ? 32767 : (v < -32768 ? -32768 : v); }

    private void keyOn(int v) {
        int dir = reg[0x5D] << 8;
        int e = dir + (reg[v * 16 + 4] & 0xFF) * 4;
        addr[v] = rd16(e);
        p1[v] = p2[v] = 0;
        java.util.Arrays.fill(hist[v], 0);
        counter[v] = 0;
        env[v] = 0; state[v] = ATTACK; on[v] = true;
        decode(v);
        idx[v] = 0;
        endx &= ~(1 << v);
    }

    private void decode(int v) {
        int a = addr[v];
        int h = ram[a & 0xFFFF] & 0xFF;
        header[v] = h;
        int range = h >> 4, filter = (h >> 2) & 3;
        int q1 = p1[v], q2 = p2[v];
        for (int i = 0; i < 16; i++) {
            int b = ram[(a + 1 + (i >> 1)) & 0xFFFF] & 0xFF;
            int n = (i & 1) == 0 ? b >> 4 : b & 0xF;
            n = (n ^ 8) - 8;
            int s = range <= 12 ? (n << range) >> 1 : (n < 0 ? -2048 : 0);
            switch (filter) {
                case 1: s += q1 - (q1 >> 4); break;
                case 2: s += (q1 << 1) + ((-(q1 * 3)) >> 5) - q2 + (q2 >> 4); break;
                case 3: s += (q1 << 1) + ((-(q1 * 13)) >> 6) - q2 + ((q2 * 3) >> 4); break;
                default: break;
            }
            s = clamp16(s);
            s = (short) (s << 1) >> 1;
            q2 = q1; q1 = s;
            block[v][i] = s << 1;
        }
        p1[v] = q1; p2[v] = q2;
    }

    private void nextSample(int v) {
        if (idx[v] == 16) {
            int h = header[v];
            if ((h & 1) != 0) {
                endx |= 1 << v;
                if ((h & 2) != 0) {
                    int dir = reg[0x5D] << 8;
                    addr[v] = rd16(dir + (reg[v * 16 + 4] & 0xFF) * 4 + 2);
                } else { on[v] = false; env[v] = 0; return; }
            } else addr[v] = (addr[v] + 9) & 0xFFFF;
            decode(v);
            idx[v] = 0;
        }
        int[] hs = hist[v];
        hs[0] = hs[1]; hs[1] = hs[2]; hs[2] = hs[3];
        hs[3] = block[v][idx[v]++];
    }

    private boolean tick(int rate) {
        return rate != 0 && (sampleCounter % RATE[rate]) == 0;
    }

    private void updateEnvelope(int v) {
        int adsr1 = reg[v * 16 + 5], adsr2 = reg[v * 16 + 6], gain = reg[v * 16 + 7];
        int e = env[v];
        if (state[v] == RELEASE) {
            e -= 8; if (e < 0) { e = 0; on[v] = false; }
            env[v] = e; return;
        }
        if ((adsr1 & 0x80) != 0) {
            int rate;
            switch (state[v]) {
                case ATTACK:
                    rate = (adsr1 & 0xF) * 2 + 1;
                    if (tick(rate)) { e += rate == 31 ? 1024 : 32; if (e >= 0x7E0) state[v] = DECAY; }
                    break;
                case DECAY:
                    rate = ((adsr1 >> 4) & 7) * 2 + 16;
                    if (tick(rate)) e -= ((e - 1) >> 8) + 1;
                    if ((e >> 8) <= (adsr2 >> 5)) state[v] = SUSTAIN;
                    break;
                default:
                    rate = adsr2 & 0x1F;
                    if (tick(rate)) e -= ((e - 1) >> 8) + 1;
                    break;
            }
        } else if ((gain & 0x80) == 0) {
            e = (gain & 0x7F) << 4;
        } else {
            int rate = gain & 0x1F;
            if (tick(rate)) {
                switch ((gain >> 5) & 3) {
                    case 0: e -= 32; break;
                    case 1: e -= ((e - 1) >> 8) + 1; break;
                    case 2: e += 32; break;
                    default: e += e < 0x600 ? 32 : 8; break;
                }
            }
        }
        env[v] = e < 0 ? 0 : Math.min(e, 0x7FF);
    }

    /** Generates one 32 kHz stereo sample into outL/outR. */
    public void sample() {
        sampleCounter = (sampleCounter + 1) % 30720; // lcm-friendly wrap of all rates
        int flg = reg[0x6C];
        int koff = reg[0x5C];

        // noise
        if (tick(flg & 0x1F)) noise = (noise >> 1) | (((noise ^ (noise >> 1)) & 1) << 14);
        int noiseOut = (short) (noise << 1);

        int mixL = 0, mixR = 0, echoL = 0, echoR = 0;
        int prevOut = 0;
        for (int v = 0; v < 8; v++) {
            int base = v * 16;
            if ((flg & 0x80) != 0) { on[v] = false; env[v] = 0; }
            if ((kon & (1 << v)) != 0) { keyOn(v); kon &= ~(1 << v); }
            if ((koff & (1 << v)) != 0 && on[v] && state[v] != RELEASE) state[v] = RELEASE;
            if (!on[v]) { outx[v] = 0; prevOut = 0; continue; }

            int pitch = reg[base + 2] | ((reg[base + 3] & 0x3F) << 8);
            if ((reg[0x2D] & (1 << v)) != 0 && v > 0) pitch += ((prevOut >> 5) * pitch) >> 10;
            if (pitch > 0x3FFF) pitch = 0x3FFF;
            if (pitch < 0) pitch = 0;
            counter[v] += pitch;
            while (counter[v] >= 0x1000 && on[v]) { counter[v] -= 0x1000; nextSample(v); }

            int smp;
            if ((reg[0x3D] & (1 << v)) != 0) smp = noiseOut;
            else {
                int[] h = hist[v];
                double t = (counter[v] & 0xFFF) / 4096.0;
                double p0 = h[0], q1 = h[1], q2 = h[2], q3 = h[3];
                double r = q1 + 0.5 * t * (q2 - p0 + t * (2 * p0 - 5 * q1 + 4 * q2 - q3 + t * (3 * (q1 - q2) + q3 - p0)));
                smp = clamp16((int) r);
            }
            updateEnvelope(v);
            int out = (smp * env[v]) >> 11;
            prevOut = out;
            outx[v] = out >> 8;
            int l = (out * (byte) reg[base]) >> 7;
            int r = (out * (byte) reg[base + 1]) >> 7;
            mixL = clamp16(mixL + l); mixR = clamp16(mixR + r);
            if ((reg[0x4D] & (1 << v)) != 0) { echoL = clamp16(echoL + l); echoR = clamp16(echoR + r); }
        }

        // echo
        int esa = reg[0x6D] << 8;
        int edl = reg[0x7D] & 0xF;
        int bufLen = edl == 0 ? 4 : edl * 2048;
        int ptr = esa + echoPos;
        int eL = (short) rd16(ptr), eR = (short) rd16(ptr + 2);
        firL[firPos] = eL >> 1; firR[firPos] = eR >> 1;
        int sumL = 0, sumR = 0;
        for (int i = 0; i < 8; i++) {
            int k = (firPos + 1 + i) & 7; // oldest first
            int c = (byte) reg[(i << 4) | 0x0F];
            sumL += firL[k] * c; sumR += firR[k] * c;
        }
        firPos = (firPos + 1) & 7;
        sumL = clamp16(sumL >> 6); sumR = clamp16(sumR >> 6);
        if ((flg & 0x20) == 0) {
            int fb = (byte) reg[0x0D];
            int wl = clamp16(echoL + ((sumL * fb) >> 7)) & ~1;
            int wr = clamp16(echoR + ((sumR * fb) >> 7)) & ~1;
            ram[ptr & 0xFFFF] = (byte) wl; ram[(ptr + 1) & 0xFFFF] = (byte) (wl >> 8);
            ram[(ptr + 2) & 0xFFFF] = (byte) wr; ram[(ptr + 3) & 0xFFFF] = (byte) (wr >> 8);
        }
        echoPos += 4;
        if (echoPos >= bufLen) echoPos = 0;

        int l = clamp16(((mixL * (byte) reg[0x0C]) >> 7) + ((sumL * (byte) reg[0x2C]) >> 7));
        int r = clamp16(((mixR * (byte) reg[0x1C]) >> 7) + ((sumR * (byte) reg[0x3C]) >> 7));
        if ((flg & 0x40) != 0) { l = 0; r = 0; }
        outL = l; outR = r;
    }

    @Override
    public void save(StateOut o) throws IOException {
        o.ints(reg); o.ints(addr); o.ints(idx); o.ints(counter); o.ints(header); o.ints(p1); o.ints(p2);
        o.ints2(block); o.ints2(hist); o.ints(env); o.ints(state); o.bools(on); o.ints(outx);
        o.i(endx); o.i(kon); o.i(noise); o.i(sampleCounter); o.i(echoPos);
        o.ints(firL); o.ints(firR); o.i(firPos); o.i(outL); o.i(outR);
    }

    @Override
    public void load(StateIn in) throws IOException {
        in.ints(reg); in.ints(addr); in.ints(idx); in.ints(counter); in.ints(header); in.ints(p1); in.ints(p2);
        in.ints2(block); in.ints2(hist); in.ints(env); in.ints(state); in.bools(on); in.ints(outx);
        endx = in.i(); kon = in.i(); noise = in.i(); sampleCounter = in.i(); echoPos = in.i();
        in.ints(firL); in.ints(firR); firPos = in.i(); outL = in.i(); outR = in.i();
    }
}
