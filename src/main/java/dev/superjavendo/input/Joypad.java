package dev.superjavendo.input;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;
import java.io.IOException;

/** Two standard SNES pads: manual serial reads ($4016/$4017) and auto-read ($4218-$421F). */
public final class Joypad implements Stateful {
    public static final int B = 0x8000, Y = 0x4000, SELECT = 0x2000, START = 0x1000,
            UP = 0x0800, DOWN = 0x0400, LEFT = 0x0200, RIGHT = 0x0100,
            A = 0x0080, X = 0x0040, L = 0x0020, R = 0x0010;

    private final int[] buttons = new int[2];
    private final int[] shift = new int[2];
    private final int[] idx = new int[2];
    private final int[] auto = new int[2];
    private boolean strobe;

    public void setButtons(int port, int mask) { buttons[port] = mask & 0xFFF0; }

    /** Write to $4016. */
    public void writeStrobe(int v) {
        boolean s = (v & 1) != 0;
        if (s || strobe) { // latch on high level and on the falling edge
            for (int p = 0; p < 2; p++) { shift[p] = buttons[p]; idx[p] = 0; }
        }
        strobe = s;
    }

    /** Serial read of one bit for the given port. */
    public int readBit(int port) {
        if (strobe) return (buttons[port] >> 15) & 1;
        if (idx[port] >= 16) return 1;
        return (shift[port] >> (15 - idx[port]++)) & 1;
    }

    public void autoRead() { auto[0] = buttons[0]; auto[1] = buttons[1]; }

    /** $4218 + i. */
    public int autoReg(int i) {
        if (i >= 4) return 0;
        int v = auto[i >> 1];
        return (i & 1) == 0 ? v & 0xFF : v >> 8;
    }

    @Override
    public void save(StateOut o) throws IOException {
        o.ints(shift); o.ints(idx); o.ints(auto); o.b(strobe); // held buttons come from the live input, not the state
    }

    @Override
    public void load(StateIn in) throws IOException {
        in.ints(shift); in.ints(idx); in.ints(auto); strobe = in.b();
    }
}
