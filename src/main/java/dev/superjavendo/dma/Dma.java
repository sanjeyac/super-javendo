package dev.superjavendo.dma;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

import dev.superjavendo.bus.Memory;

/** The 8 DMA/HDMA channels ($4300-$437F). */
public final class Dma implements Stateful {
    private static final int[][] PATTERN = {
            {0}, {0, 1}, {0, 0}, {0, 0, 1, 1}, {0, 1, 2, 3}, {0, 1, 0, 1}, {0, 0}, {0, 0, 1, 1}};
    // register offsets within a channel
    private static final int DMAP = 0, BBAD = 1, A1TL = 2, A1TH = 3, A1B = 4,
            DASL = 5, DASH = 6, DASB = 7, A2AL = 8, A2AH = 9, NTRL = 0xA;

    private final Memory mem;
    private final int[][] r = new int[8][16];
    private final boolean[] active = new boolean[8];
    private final boolean[] doTransfer = new boolean[8];
    public int hdmaEnable;
    public boolean trace;

    public Dma(Memory mem) {
        this.mem = mem;
        for (int[] c : r) java.util.Arrays.fill(c, 0xFF);
    }

    public int readReg(int off) { return r[(off >> 4) & 7][off & 0xF]; }
    public void writeReg(int off, int v) { r[(off >> 4) & 7][off & 0xF] = v & 0xFF; }

    private int get16(int ch, int lo) { return r[ch][lo] | (r[ch][lo + 1] << 8); }
    private void set16(int ch, int lo, int v) { r[ch][lo] = v & 0xFF; r[ch][lo + 1] = (v >> 8) & 0xFF; }

    /** General-purpose DMA for every channel in mask. Returns master clocks the CPU is stalled. */
    public int runGeneral(int mask) {
        int clocks = 0;
        for (int ch = 0; ch < 8; ch++) {
            if ((mask & (1 << ch)) == 0) continue;
            int dmap = r[ch][DMAP];
            int[] pat = PATTERN[dmap & 7];
            boolean toA = (dmap & 0x80) != 0;
            int step = (dmap & 0x08) != 0 ? 0 : ((dmap & 0x10) != 0 ? -1 : 1);
            int bank = r[ch][A1B] << 16, addr = get16(ch, A1TL), bbad = r[ch][BBAD];
            int len = get16(ch, DASL);
            if (len == 0) len = 0x10000;
            clocks += 8 + 8 * len;
            if (trace) System.out.printf("DMA ch%d mode=%d dmap=%02X B=%02X A=%02X:%04X len=%d%n", ch, dmap & 7, dmap, bbad, bank >> 16, addr, len);
            for (int i = 0; i < len; i++) {
                int b = 0x2100 | ((bbad + pat[i % pat.length]) & 0xFF);
                if (toA) mem.write(bank | addr, mem.read(b));
                else mem.write(b, mem.read(bank | addr));
                addr = (addr + step) & 0xFFFF;
            }
            set16(ch, A1TL, addr);
            set16(ch, DASL, 0);
        }
        return clocks > 0 ? clocks + 8 : 0;
    }

    /** Called at the start of each frame: loads the first table entry of every enabled channel. */
    public void hdmaInit() {
        for (int ch = 0; ch < 8; ch++) {
            // Every channel leaves init "not terminated" with doTransfer cleared. Channels that are
            // enabled later in the frame (games do this from IRQ handlers, pre-loading $43x8/$43xA
            // themselves) start running from that state.
            active[ch] = true;
            doTransfer[ch] = false;
            if ((hdmaEnable & (1 << ch)) == 0) continue;
            set16(ch, A2AL, get16(ch, A1TL));
            loadEntry(ch);
            doTransfer[ch] = true;
            if (trace) System.out.printf("HDMA init ch%d dmap=%02X B=%02X table=%02X:%04X counter=%02X active=%b%n", ch, r[ch][DMAP], r[ch][BBAD], r[ch][A1B], get16(ch, A1TL), r[ch][NTRL], active[ch]);
        }
        if (trace) System.out.printf("HDMA enable=%02X%n", hdmaEnable);
    }

    /** Reads the line-counter byte (and indirect address) at the table pointer. */
    private void loadEntry(int ch) {
        int bank = r[ch][A1B] << 16;
        int a2a = get16(ch, A2AL);
        int lc = mem.read(bank | a2a) & 0xFF;
        a2a = (a2a + 1) & 0xFFFF;
        r[ch][NTRL] = lc;
        active[ch] = lc != 0;
        if ((r[ch][DMAP] & 0x40) != 0) { // indirect: 16-bit data pointer follows
            int lo = mem.read(bank | a2a) & 0xFF;
            int hi = mem.read(bank | ((a2a + 1) & 0xFFFF)) & 0xFF;
            a2a = (a2a + 2) & 0xFFFF;
            set16(ch, DASL, lo | (hi << 8));
        }
        set16(ch, A2AL, a2a);
    }

    /** Runs one scanline of HDMA. Returns master clocks of CPU stall. */
    public int hdmaRun() {
        int clocks = 0;
        for (int ch = 0; ch < 8; ch++) {
            if ((hdmaEnable & (1 << ch)) == 0 || !active[ch]) continue;
            int dmap = r[ch][DMAP];
            boolean indirect = (dmap & 0x40) != 0;
            if (doTransfer[ch]) {
                int[] pat = PATTERN[dmap & 7];
                boolean toA = (dmap & 0x80) != 0;
                for (int i = 0; i < pat.length; i++) {
                    int b = 0x2100 | ((r[ch][BBAD] + pat[i]) & 0xFF);
                    int src;
                    if (indirect) { src = (r[ch][DASB] << 16) | get16(ch, DASL); set16(ch, DASL, get16(ch, DASL) + 1); }
                    else { src = (r[ch][A1B] << 16) | get16(ch, A2AL); set16(ch, A2AL, get16(ch, A2AL) + 1); }
                    if (toA) mem.write(src, mem.read(b)); else mem.write(b, mem.read(src));
                    if (trace && ch == 2) System.out.printf("    ch2 src=%06X val=%02X%n", src, mem.read(src));
                }
                clocks += 8 * pat.length;
            }
            clocks += 8;
            if (trace) System.out.printf("  hdma ch%d dmap=%02X B=%02X ntrl=%02X xfer=%b a2a=%04X das=%04X%n", ch, dmap, r[ch][BBAD], r[ch][NTRL], doTransfer[ch], get16(ch, A2AL), get16(ch, DASL));
            int lc = (r[ch][NTRL] - 1) & 0xFF;
            r[ch][NTRL] = lc;
            doTransfer[ch] = (lc & 0x80) != 0;
            if ((lc & 0x7F) == 0) {
                loadEntry(ch);
                doTransfer[ch] = true;
            }
        }
        return clocks;
    }

    @Override
    public void save(StateOut o) throws IOException {
        o.ints2(r); o.bools(active); o.bools(doTransfer); o.i(hdmaEnable);
    }

    @Override
    public void load(StateIn in) throws IOException {
        in.ints2(r); in.bools(active); in.bools(doTransfer); hdmaEnable = in.i();
    }
}
