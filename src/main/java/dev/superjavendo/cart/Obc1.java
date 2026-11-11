package dev.superjavendo.cart;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;

import java.io.IOException;

/**
 * OBC1 (Metal Combat: Falcon's Revenge): 8 KB of RAM at $6000-$7FFF whose top part is an OAM staging area that the
 * chip rewrites on the fly. Registers $7FF0-$7FF6 give indexed access to the 4-byte sprite entries ($7FF0-$7FF3)
 * and to the 2-bit "high table" fields ($7FF4); $7FF5 selects which half of the RAM holds the sprites and $7FF6 the
 * sprite number.
 *
 * <p>Implemented from the documented behaviour (the snes9x core used as reference elsewhere does not emulate it);
 * see {@code Obc1Test}.
 */
public final class Obc1 implements Coprocessor {
    private final byte[] ram = new byte[0x2000];
    private int base = 0x1C00;      // start of the 128 x 4 byte sprite table
    private int index;              // current sprite, 0..127

    private static boolean window(int bank, int off) { return (bank & 0x7F) < 0x40 && off >= 0x6000 && off < 0x8000; }

    @Override public int read(int bank, int off) {
        if (!window(bank & 0xFF, off)) return -1;
        switch (off) {
            case 0x7FF0: case 0x7FF1: case 0x7FF2: case 0x7FF3:
                return ram[base + (index << 2) + (off - 0x7FF0)] & 0xFF;
            case 0x7FF4:
                return ram[base + (index >> 2) + 0x200] & 0xFF;
            default:
                return ram[off & 0x1FFF] & 0xFF;
        }
    }

    @Override public boolean write(int bank, int off, int v) {
        if (!window(bank & 0xFF, off)) return false;
        v &= 0xFF;
        switch (off) {
            case 0x7FF0: case 0x7FF1: case 0x7FF2: case 0x7FF3:
                ram[base + (index << 2) + (off - 0x7FF0)] = (byte) v;
                break;
            case 0x7FF4: {
                int a = base + (index >> 2) + 0x200, shift = (index & 3) << 1;
                ram[a] = (byte) ((ram[a] & ~(3 << shift)) | ((v & 3) << shift));
                break;
            }
            case 0x7FF5: base = (v & 1) != 0 ? 0x1800 : 0x1C00; ram[0x1FF5] = (byte) v; break;
            case 0x7FF6: index = v & 0x7F; ram[0x1FF6] = (byte) v; break;
            default: ram[off & 0x1FFF] = (byte) v;
        }
        return true;
    }

    @Override public void save(StateOut o) throws IOException { o.bytes(ram); o.i(base); o.i(index); }
    @Override public void load(StateIn in) throws IOException { in.bytes(ram); base = in.i(); index = in.i(); }
}
