package dev.superjavendo.games;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Game-specific knowledge for one game, found by the CRC32 of its ROM through {@link Games}. Implement it in one class per game
 * (with a {@code static boolean matches(String crc)}) and register that class in {@link Games}.
 */
public interface GameSupport {
    /** The game's name. */
    String title();

    /** The No-Intro name of the dump with this CRC32 (e.g. "F-Zero (USA)"), or null if it is not one of this game's dumps. */
    default String dumpName(String crc) { return null; }

    /** Whether {@link #readRecords} is implemented: the library only shows a records section for those. */
    default boolean hasRecords() { return false; }

    /** Records parsed from the contents of the battery save, or null if the data is not a save of this game. */
    default Records readRecords(byte[] sram) { return null; }

    /** The battery save the launcher uses for this ROM: next to it, same name, {@code .srm}. */
    static Path srmOf(Path rom) {
        return rom.resolveSibling(rom.getFileName().toString().replaceFirst("\\.[^.]*$", "") + ".srm");
    }

    /** Records from the ROM's save file; empty if the game has no reader, there is no save yet or it is not recognised. */
    default Optional<Records> records(Path rom) {
        if (!hasRecords()) return Optional.empty();
        try {
            Path srm = srmOf(rom);
            return Files.isRegularFile(srm) ? Optional.ofNullable(readRecords(Files.readAllBytes(srm))) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
