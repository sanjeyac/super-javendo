package dev.superjavendo.frontend.modern;

import dev.superjavendo.config.AppPaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders every screen of the v2 frontend off-screen (headless, throw-away config folder) and checks that something was drawn.
 * It is a smoke test: it exercises layout and painting of all the screens, not their exact look.
 */
class ShellSmokeTest {
    @TempDir static Path tmp;
    static Shell shell;

    @BeforeAll static void build() throws Exception {
        System.setProperty("java.awt.headless", "true");
        AppPaths paths = new AppPaths(tmp.resolve("cfg"));
        Files.createDirectories(paths.configDir());
        StringBuilder list = new StringBuilder();
        for (String n : new String[]{"F-Zero (U) [!]", "F-Zero (U) [b1]", "Super Mario World (U)", "Zelda (E)", "Unknown thing"}) {
            Path rom = tmp.resolve("roms").resolve(n + ".sfc");
            Files.createDirectories(rom.getParent());
            Files.write(rom, new byte[0x8000 + n.length()]);
            list.append(rom).append('\t').append(n).append('\n');
        }
        Files.writeString(paths.gameListFile(), list.toString());
        shell = new Shell(paths, new HashMap<>());
    }

    private static int distinctColours(BufferedImage img) {
        Set<Integer> seen = new HashSet<>();
        for (int y = 0; y < img.getHeight(); y += 3)
            for (int x = 0; x < img.getWidth(); x += 3) seen.add(img.getRGB(x, y));
        return seen.size();
    }

    private void check(String tab, boolean list, String query, int w, int h) throws Exception {
        BufferedImage img = shell.render(w, h, tab, list, query);
        assertEquals(w, img.getWidth());
        assertEquals(h, img.getHeight());
        assertTrue(distinctColours(img) > 8, tab + (list ? " list" : "") + " drew (almost) nothing");
    }

    @Test void libraryGrid() throws Exception { check("library", false, "", 1200, 800); }
    @Test void libraryList() throws Exception { check("library", true, "", 1200, 800); }
    @Test void librarySearch() throws Exception { check("library", false, "zelda", 1000, 700); }
    @Test void libraryNoMatch() throws Exception { check("library", false, "zzzz-no-such-game", 1000, 700); }
    @Test void romManager() throws Exception { check("rom", false, "", 1300, 800); }
    @Test void romManagerWithAFileOpen() throws Exception { check("rom", false, "F-Zero", 1300, 800); }
    @Test void controller() throws Exception { check("controller", false, "", 1300, 800); }
    @Test void settings() throws Exception { check("settings", false, "", 1100, 700); }
    @Test void smallWindow() throws Exception { check("library", false, "", 640, 480); }
}
