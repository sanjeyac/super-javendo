package dev.superjavendo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsRecTest {
    @TempDir Path dir;

    @Test void recSettingsHaveDefaultsRoundTripAndKeepForeignLines() throws Exception {
        AppPaths paths = new AppPaths(dir);
        Settings d = Settings.load(paths);
        assertEquals(512, d.recGreenzoneMb);
        assertEquals("keep", d.recPolicy);
        Files.writeString(paths.settingsFile(), "# x\nplayer1=easysmx\n");
        d.recGreenzoneMb = 1024;
        d.recPolicy = "cut";
        d.save();
        Settings r = Settings.load(paths);
        assertEquals(1024, r.recGreenzoneMb);
        assertEquals("cut", r.recPolicy);
        assertTrue(Files.readAllLines(paths.settingsFile()).contains("player1=easysmx"));
    }

    @Test void invalidValuesFallBack() throws Exception {
        AppPaths paths = new AppPaths(dir);
        Files.writeString(paths.settingsFile(), "rec.policy=banana\nrec.greenzoneMb=abc\n");
        Settings s = Settings.load(paths);
        assertEquals("keep", s.recPolicy);
        assertEquals(512, s.recGreenzoneMb);
        assertTrue(Settings.REC_MEMORY_MB.contains(512));
        assertEquals(List.of(128, 256, 512, 1024, 2048), Settings.REC_MEMORY_MB);
    }
}
