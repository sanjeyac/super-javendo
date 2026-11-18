package dev.superjavendo.games;

import java.util.List;

/**
 * A game that keeps its high scores in work RAM and forgets them when the console is switched off. {@link ScoreMonitor} copies
 * {@link #length()} bytes from {@code $7E:0000 + wramOffset()} and {@link ScoreStore} keeps what they decode to on disk.
 */
public interface RamScores {
    /** Where the table starts, as an offset in the 128 KB of WRAM. */
    int wramOffset();

    /** Size of the whole table in bytes. */
    int length();

    /** How many entries the game's table holds (the stored list is cut to this many). */
    int slots();

    /** The entries in the copied bytes, in any order; slots that are empty or not valid text are left out. */
    List<ScoreEntry> decode(byte[] region);

    /** Whether this is one of the entries the game puts in its table by itself (never a record of the player). */
    boolean isDefault(ScoreEntry entry);
}
