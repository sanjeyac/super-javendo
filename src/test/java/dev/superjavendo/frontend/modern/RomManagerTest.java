package dev.superjavendo.frontend.modern;

import dev.superjavendo.frontend.legacy.GameEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RomManagerTest {
    @TempDir Path tmp;

    private Game game(String file, long size, MetaDb.Meta meta) {
        Path p = tmp.resolve(file);
        return Game.of(new GameEntry(p, file.replaceAll("\\.\\w+$", "")), meta, "0000ABCD", size);
    }

    private static final MetaDb.Meta META = new MetaDb.Meta("F-Zero", 1990, "Nintendo", "Corse", 1, "USA");

    @Test void qualityFromTags() {
        assertEquals(Game.Quality.VERIFIED, game("F-Zero (U) [!].sfc", 1, null).quality());
        assertEquals(Game.Quality.BAD, game("F-Zero (U) [b1].sfc", 1, null).quality());
        assertEquals("b1", game("F-Zero (U) [b1].sfc", 1, null).badTag());
        assertEquals(Game.Quality.UNKNOWN, game("F-Zero (U).sfc", 1, null).quality());
    }

    @Test void mainFilePrefersChoiceThenVerifiedDump() {
        Game bad = game("F-Zero (U) [b1].sfc", 512, META), good = game("F-Zero (U) [!].sfc", 512, META);
        assertEquals(good, LibraryModel.mainOf(List.of(bad, good), null));
        assertEquals(bad, LibraryModel.mainOf(List.of(bad, good), bad.path()));
    }

    @Test void libraryShowsOneGamePerGroupAndSkipsHidden() {
        Overrides ov = new Overrides(tmp.resolve("o.tsv"));
        Game bad = game("F-Zero (U) [b1].sfc", 512, META), good = game("F-Zero (U) [!].sfc", 512, META), other = game("Other.sfc", 512, null);
        LibraryModel m = new LibraryModel(new UserData(tmp.resolve("u.tsv")), g -> true);
        m.setGames(List.of(bad, good, other), ov);
        assertEquals(2, m.allGames().size());
        assertEquals(2, m.allGames().stream().filter(g -> g.title().startsWith("F-Zero")).findFirst().get().versions());
        ov.setHidden(good.path(), true);
        m.setGames(List.of(bad, good, other), ov);
        assertEquals(bad.path(), m.allGames().stream().filter(g -> g.title().startsWith("F-Zero")).findFirst().get().path());
        ov.setHidden(other.path(), true);
        m.setGames(List.of(bad, good, other), ov);
        assertEquals(1, m.allGames().size());
    }

    @Test void catalogStatusesAndCounters() {
        Overrides ov = new Overrides(tmp.resolve("o.tsv"));
        Game good = game("F-Zero (U) [!].sfc", 512, META), bad = game("F-Zero (U) [b1].sfc", 512, META);
        Game nocover = game("ESPN (U).sfc", 2048, new MetaDb.Meta("ESPN", 1994, "", "Sport", 2, "USA"));
        Game unknown = game("Mystery.sfc", 1024, null), empty = game("unnamed.smc", 0, null);
        RomCatalog cat = new RomCatalog(List.of(good, bad, nocover, unknown, empty), ov, g -> !g.title().equals("ESPN"), true);
        var by = new java.util.HashMap<String, RomCatalog.Status>();
        for (var r : cat.rows()) by.put(r.game().fileName(), r.status());
        assertEquals(RomCatalog.Status.OK, by.get("F-Zero (U) [!].sfc"));
        assertEquals(RomCatalog.Status.DUPLICATE, by.get("F-Zero (U) [b1].sfc"));
        assertEquals(RomCatalog.Status.NO_COVER, by.get("ESPN (U).sfc"));
        assertEquals(RomCatalog.Status.UNKNOWN, by.get("Mystery.sfc"));
        assertEquals(RomCatalog.Status.ERROR, by.get("unnamed.smc"));
        var c = cat.counters();
        assertEquals(5, c.total());
        assertEquals(3, c.recognized());
        assertEquals(1, c.errors());
        assertEquals(2, c.duplicates());
        assertEquals(4, cat.count(RomCatalog.Filter.TO_FIX));
        assertEquals(List.of("F-Zero (U) [b1].sfc"), cat.view(RomCatalog.Filter.DUPLICATES, "b1").stream().map(r -> r.game().fileName()).toList());
        assertEquals(2, cat.group(good).size());
        assertTrue(cat.group(good).get(0).main());
        ov.setHidden(bad.path(), true);
        assertEquals(1, new RomCatalog(List.of(good, bad), ov, g -> true, true).count(RomCatalog.Filter.HIDDEN));
    }

    @Test void withoutMetadataNothingIsUnrecognised() {
        RomCatalog cat = new RomCatalog(List.of(game("Mystery.sfc", 1024, null)), new Overrides(tmp.resolve("o.tsv")), g -> true, false);
        assertEquals(RomCatalog.Status.OK, cat.rows().get(0).status());
    }

    @Test void overridesAndSourcesRoundTrip() throws Exception {
        Overrides ov = new Overrides(tmp.resolve("o.tsv"));
        ov.setHidden(tmp.resolve("a.sfc"), true);
        ov.setPrimary("fzero", tmp.resolve("b.sfc"));
        ov.save();
        Overrides ov2 = new Overrides(tmp.resolve("o.tsv"));
        ov2.load();
        assertTrue(ov2.isHidden(tmp.resolve("a.sfc")));
        assertEquals(tmp.resolve("b.sfc"), ov2.primaryOf("fzero"));

        Sources s = new Sources(tmp.resolve("s.tsv"));
        s.seedFrom(List.of(tmp.resolve("roms/a.sfc"), tmp.resolve("roms/sub/b.sfc"), tmp.resolve("hack/c.sfc")));
        assertEquals(2, s.folders().size());                       // roms/sub is inside roms
        s.markScanned(tmp.resolve("roms"), 5);
        s.save();
        Sources s2 = new Sources(tmp.resolve("s.tsv"));
        s2.load();
        assertEquals(2, s2.folders().size());
        assertEquals(2, Sources.countUnder(tmp.resolve("roms"), List.of(tmp.resolve("roms/a.sfc"), tmp.resolve("roms/sub/b.sfc"), tmp.resolve("hack/c.sfc"))));
        s2.remove(tmp.resolve("roms"));
        assertEquals(1, s2.folders().size());
    }
}
