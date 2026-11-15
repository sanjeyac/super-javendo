package dev.superjavendo.movie;

import dev.superjavendo.Snes;
import dev.superjavendo.cart.Cartridge;

import java.util.function.Consumer;

/** Runs a {@link Movie} on a fresh console, with no window: used for verification and the headless CLI. */
public final class Replay {
    private Replay() { }

    /** Result of a full replay. {@code matches} is false when the final picture differs from the author's. */
    public record Result(int frames, int hash, boolean matches) { }

    /**
     * Plays the whole movie. The cartridge's battery RAM is cleared for the run and restored afterwards, so a replay
     * never touches the player's saves.
     */
    public static Result run(Cartridge cart, Movie m, Consumer<Snes> perFrame) {
        byte[] saved = cart.sram.clone();
        try {
            java.util.Arrays.fill(cart.sram, (byte) 0);
            Snes snes = new Snes(cart);
            for (int f = 0; f < m.length(); f++) {
                if ((m.pad1(f) & Movie.RESET) != 0) snes = new Snes(cart);
                snes.joypad.setButtons(0, m.pad1(f));
                snes.joypad.setButtons(1, m.pad2(f));
                snes.runFrame();
                if (perFrame != null) perFrame.accept(snes);
            }
            int hash = Movie.pictureHash(snes);
            return new Result(m.length(), hash, m.endHash == 0 || m.endHash == hash);
        } finally {
            System.arraycopy(saved, 0, cart.sram, 0, saved.length);
        }
    }
}
