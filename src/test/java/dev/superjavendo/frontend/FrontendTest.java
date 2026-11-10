package dev.superjavendo.frontend;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.input.ProfileFile;
import dev.superjavendo.input.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FrontendTest {
    @Test void scanFindsRomsAddsOnlyNewOnesAndDropsMissing(@TempDir Path tmp) throws Exception {
        Path roms = Files.createDirectories(tmp.resolve("roms/sub"));
        Files.write(roms.resolve("Zelda.sfc"), new byte[]{1});
        Files.write(tmp.resolve("roms/Mario.SMC"), new byte[]{1});
        Files.write(tmp.resolve("roms/readme.txt"), new byte[]{1});
        GameList list = new GameList(tmp.resolve("cfg/game.list"));
        assertEquals(2, list.scan(tmp.resolve("roms")));
        assertEquals("Mario", list.games().get(0).title());          // sorted by title
        list.save();

        GameList again = new GameList(tmp.resolve("cfg/game.list"));
        again.load();
        assertEquals(2, again.games().size());
        assertEquals(0, again.scan(tmp.resolve("roms")));            // nothing new
        Files.delete(roms.resolve("Zelda.sfc"));
        again.scan(tmp.resolve("roms"));
        assertEquals(1, again.games().size());                        // vanished file dropped
    }

    @Test void previewFallsBackToCoverThenNothing(@TempDir Path tmp) throws Exception {
        AppPaths paths = new AppPaths(tmp.resolve("cfg"));
        Path rom = tmp.resolve("Game.sfc");
        assertNull(Previews.of(rom, paths).image());
        Files.createDirectories(paths.coversDir());
        var img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(img, "png", paths.coversDir().resolve("Game.png").toFile());
        var p = Previews.of(rom, paths);
        assertNotNull(p.image());
        assertEquals("Copertina", p.caption());
    }

    @Test void profileFileRoundTripKeepsBindingsAndHeader(@TempDir Path tmp) throws Exception {
        ProfileStore store = new ProfileStore(tmp);
        store.installDefaults();
        ProfileFile f = ProfileFile.read(store.file("easysmx"));
        assertEquals("gamepad", f.type);
        assertTrue(f.of("l").contains("BUTTON_8"));
        f.add("start", "BUTTON_12");
        f.remove("l", "BUTTON_8");
        f.write(store.file("easysmx"));
        ProfileFile g = ProfileFile.read(store.file("easysmx"));
        assertTrue(g.of("start").contains("BUTTON_12"));
        assertFalse(g.of("l").contains("BUTTON_8"));
        assertTrue(Files.readString(store.file("easysmx")).startsWith("#"));       // header comment kept
        assertEquals(2, store.load("easysmx").buttons.entrySet().stream().filter(e -> e.getValue() == dev.superjavendo.input.Joypad.START).count());
    }

    @Test void invalidBindingIsRejectedBeforeWriting(@TempDir Path tmp) throws Exception {
        ProfileStore store = new ProfileStore(tmp);
        store.installDefaults();
        ProfileFile f = ProfileFile.read(store.file("keyboard"));
        String before = Files.readString(store.file("keyboard"));
        f.add("a", "KEY_DOESNOTEXIST");
        assertThrows(IllegalArgumentException.class, () -> f.write(store.file("keyboard")));
        assertEquals(before, Files.readString(store.file("keyboard")));
    }
}
