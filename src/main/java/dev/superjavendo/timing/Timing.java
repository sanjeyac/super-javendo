package dev.superjavendo.timing;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

/** Raster position and interrupt state shared between the scheduler and the bus registers. */
public final class Timing implements Stateful {
    public static final int LINE_CLOCKS = 1364;   // master clocks per scanline
    public static final int LINES = 262;          // NTSC, non-interlaced
    public static final int VBLANK_START = 225;   // first VBlank scanline (no overscan)

    public int v, h;                  // current scanline, master clock within the line
    public boolean nmiFlag, irqFlag;  // $4210 bit7 / $4211 bit7
    public boolean autoJoyBusy;
    public int nmitimen;              // $4200
    public int htime = 0x1FF, vtime = 0x1FF;

    // $2137 latch and $213C/$213D read toggles
    public int latchedH, latchedV;
    public boolean toggleH, toggleV;

    public boolean inVBlank() { return v >= VBLANK_START; }
    public boolean inHBlank() { return h >= 1096; }

    public void latch() { latchedH = h >> 2; latchedV = v; }

    /** Master clock within the line at which the programmable IRQ fires, or -1 for none this line. */
    public int irqClock() {
        switch ((nmitimen >> 4) & 3) {
            case 1: return htime < 340 ? htime * 4 + 14 : -1;               // H-IRQ every line
            case 2: return v == vtime ? 14 : -1;                            // V-IRQ
            case 3: return v == vtime && htime < 340 ? htime * 4 + 14 : -1; // HV-IRQ
            default: return -1;
        }
    }

    @Override
    public void save(StateOut o) throws IOException {
        o.i(v); o.i(h); o.b(nmiFlag); o.b(irqFlag); o.b(autoJoyBusy); o.i(nmitimen); o.i(htime); o.i(vtime);
        o.i(latchedH); o.i(latchedV); o.b(toggleH); o.b(toggleV);
    }

    @Override
    public void load(StateIn in) throws IOException {
        v = in.i(); h = in.i(); nmiFlag = in.b(); irqFlag = in.b(); autoJoyBusy = in.b(); nmitimen = in.i();
        htime = in.i(); vtime = in.i(); latchedH = in.i(); latchedV = in.i(); toggleH = in.b(); toggleV = in.b();
    }
}
