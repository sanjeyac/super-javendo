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

    @Test void coverLookupFallsBackToNormalizedTitleButNeverGuessesOnAmbiguity(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("covers"));
        for (String n : new String[]{"Addams Family, The - Pugsley_s Scavenger Hunt (USA).png", "Aladdin (USA).png",
                "Super Bomberman (USA, Variant 1).png", "Super Bomberman (USA, Variant 2).png"}) {
            Files.write(dir.resolve(n), new byte[]{1});
        }
        assertEquals("Aladdin (USA).png", Previews.findCover("Aladdin (U) [!]", dir).getFileName().toString());
        assertEquals("Addams Family, The - Pugsley_s Scavenger Hunt (USA).png",
                Previews.findCover("Addams Family, The - Pugsley's Scavenger Hunt (U)", dir).getFileName().toString());
        assertNull(Previews.findCover("Super Bomberman (U)", dir));      // ambiguous
        assertNull(Previews.findCover("Unknown (U)", dir));
        assertEquals(Previews.normalizeTitle("Addams Family, The (U) [!]"), Previews.normalizeTitle("The Addams Family (USA)"));
    }

    @Test void coverIsFoundInFolderBesideTheRomsFolder(@TempDir Path tmp) throws Exception {
        AppPaths paths = new AppPaths(tmp.resolve("cfg"));                 // config has no covers at all
        Path rom = Files.write(Files.createDirectories(tmp.resolve("snes/roms")).resolve("Aladdin (U) [!].zip"), new byte[]{1});
        assertNull(Previews.of(rom, paths).image());
        var img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(img, "png", Files.createDirectories(tmp.resolve("snes/covers")).resolve("Aladdin (USA).png").toFile());
        var p = Previews.of(rom, paths);
        assertNotNull(p.image());
        assertEquals("Copertina", p.caption());
    }

    @Test void coverGridNavigationAndHitTesting() {
        CoverGrid grid = new CoverGrid(g -> null);
        java.util.List<GameEntry> games = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) games.add(new GameEntry(Path.of("g" + i + ".sfc"), "Game " + i));
        grid.setGames(games, null);
        grid.setSize(3 * CoverGrid.CELL_W + 5, 1000);                     // 3 columns
        assertEquals(3, grid.columns());
        assertEquals(4, grid.rows());                                      // 10 games in 3 columns
        assertEquals(0, grid.selectedIndex());
        grid.move(1);
        grid.move(3);                                                      // one row down
        assertEquals(4, grid.selectedIndex());
        assertEquals("Game 4", grid.selected().title());
        grid.move(-100);
        assertEquals(0, grid.selectedIndex());                             // clamped
        grid.move(100);
        assertEquals(9, grid.selectedIndex());
        assertEquals(4, grid.indexAt(CoverGrid.CELL_W + 1, CoverGrid.CELL_H + 1));
        assertEquals(-1, grid.indexAt(2 * CoverGrid.CELL_W + 1, 3 * CoverGrid.CELL_H + 1));   // empty cell after the last game
        assertEquals(1, CoverGrid.columnsFor(10));                         // never zero columns
        grid.setGames(games.subList(0, 5), games.get(3));                  // selection survives a refresh
        assertEquals(3, grid.selectedIndex());
    }

    @Test void coverGridScalesToBoxAndWrapsTitles() {
        var big = new java.awt.image.BufferedImage(1200, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var thumb = CoverGrid.scaleToBox(big);
        assertTrue(thumb.getWidth() <= CoverGrid.CELL_W - 2 * CoverGrid.PAD);
        assertTrue(thumb.getHeight() <= CoverGrid.CELL_H - 2 * CoverGrid.PAD);
        assertEquals(1.5, (double) thumb.getWidth() / thumb.getHeight(), 0.05);   // aspect kept
        var fm = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB).createGraphics().getFontMetrics();
        var lines = CoverGrid.wrap("Adventures of Rocky and Bullwinkle and Friends, The", fm, 100);
        assertTrue(lines.size() > 1);
        for (String l : lines) assertTrue(fm.stringWidth(l) <= 100 || !l.contains(" "));
        assertTrue(CoverGrid.wrap("", fm, 100).isEmpty());
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
