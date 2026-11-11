package dev.superjavendo.frontend2;

import dev.superjavendo.frontend.GameEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LibraryTest {
    @TempDir Path tmp;

    private Game game(String file, MetaDb.Meta meta) {
        Path p = tmp.resolve(file);
        return Game.of(new GameEntry(p, file.replaceAll("\\.\\w+$", "")), meta, null, 0);
    }

    @Test void regionAndTitleFromDumpTags() {
        assertEquals("USA", Game.regionOf("Super Metroid (U) [!].sfc"));
        assertEquals("EUR", Game.regionOf("Foo (Europe).sfc"));
        assertEquals("JPN", Game.regionOf("Foo (J).sfc"));
        assertEquals("", Game.regionOf("Foo.sfc"));
        assertEquals("Super Metroid", Game.cleanTitle("Super Metroid (U) [!]"));
    }

    @Test void metaDbReadsTsvByCrc() throws Exception {
        Path f = tmp.resolve("games.tsv");
        Files.writeString(f, "# c\nabcd1234\tSuper Metroid\t1994\tNintendo\tAction\t1\tUSA\n");
        MetaDb db = MetaDb.load(f);
        assertEquals(1, db.size());
        assertEquals("Action", db.get("ABCD1234").genre());
        assertNull(db.get("00000000"));
        assertEquals(0, MetaDb.load(tmp.resolve("missing.tsv")).size());
    }

    @Test void userDataRoundTrips() {
        Path rom = tmp.resolve("a.sfc");
        UserData u = new UserData(tmp.resolve("u.tsv"));
        u.of(rom).favorite = true;
        u.recordSession(rom, 1000, 61000);
        UserData v = new UserData(tmp.resolve("u.tsv"));
        v.load();
        assertTrue(v.peek(rom).favorite);
        assertEquals(60, v.peek(rom).playSeconds);
        assertEquals(61000, v.peek(rom).lastPlayed);
    }

    @Test void modelFiltersAndSorts() {
        UserData u = new UserData(tmp.resolve("u.tsv"));
        Game b = game("Beta.sfc", new MetaDb.Meta("Beta", 1995, "", "RPG", 2, ""));
        Game a = game("Alpha.sfc", new MetaDb.Meta("Alpha", 1993, "", "Action", 1, ""));
        LibraryModel m = new LibraryModel(u, g -> g.title().equals("Alpha"));
        m.setGames(List.of(b, a));
        assertEquals(List.of("Alpha", "Beta"), m.view().stream().map(Game::title).toList());
        m.query = "bet";
        assertEquals(1, m.view().size());
        m.resetFilters();
        m.genre = "RPG";
        assertEquals("Beta", m.view().get(0).title());
        m.resetFilters();
        assertEquals(1, m.count(LibraryModel.Collection.NO_COVER));
        m.toggleFavorite(a);
        m.collection = LibraryModel.Collection.FAVORITES;
        assertEquals(List.of(a), m.view());
        m.resetFilters();
        m.sort = LibraryModel.Sort.YEAR;
        assertEquals("Alpha", m.view().get(0).title());
    }
}
