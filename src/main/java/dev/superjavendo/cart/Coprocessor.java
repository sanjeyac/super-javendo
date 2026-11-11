package dev.superjavendo.cart;

import dev.superjavendo.state.Stateful;

/** A chip on the cartridge (SuperFX, OBC1, ...): claims part of the address space and runs alongside the CPU. */
public interface Coprocessor extends Stateful {
    /** @return the byte at the address, or -1 if the chip does not handle it (the normal cartridge mapping applies) */
    int read(int bank, int off);

    /** @return true if the write was consumed by the chip */
    boolean write(int bank, int off, int value);

    /** Advances the chip by this many master clocks. */
    default void tick(int masterClocks) { }

    /** Level of the chip's interrupt line towards the CPU. */
    default boolean irq() { return false; }
}
