package dev.superjavendo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppPathsTest {
    @Test void stateFileLivesUnderStatesNamedAfterTheRom(@TempDir Path tmp) {
        AppPaths p = new AppPaths(tmp.resolve("superjavendo"));
        Path f = p.stateFile(Path.of("/games/Street Fighter II (USA).sfc"));
        assertEquals(tmp.resolve("superjavendo/states/Street Fighter II (USA).state"), f);
    }

    @Test void legacyStateNextToRomIsMoved(@TempDir Path tmp) throws Exception {
        Path rom = tmp.resolve("games/a.sfc");
        Files.createDirectories(rom.getParent());
        Files.write(rom, new byte[]{1});
        Path legacy = tmp.resolve("games/a.state");
        Files.write(legacy, new byte[]{7, 7});
        AppPaths p = new AppPaths(tmp.resolve("cfg"));
        p.migrateLegacyState(rom);
        assertFalse(Files.exists(legacy));
        assertArrayEquals(new byte[]{7, 7}, Files.readAllBytes(p.stateFile(rom)));
    }

    @Test void migrationNeverOverwritesANewerState(@TempDir Path tmp) throws Exception {
        Path rom = tmp.resolve("a.sfc");
        Files.write(rom, new byte[]{1});
        Files.write(tmp.resolve("a.state"), new byte[]{1});
        AppPaths p = new AppPaths(tmp.resolve("cfg"));
        Files.createDirectories(p.statesDir());
        Files.write(p.stateFile(rom), new byte[]{9});
        p.migrateLegacyState(rom);
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(p.stateFile(rom)));
        assertTrue(Files.exists(tmp.resolve("a.state")));
    }
}
