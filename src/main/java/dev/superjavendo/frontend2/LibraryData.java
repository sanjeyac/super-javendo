package dev.superjavendo.frontend2;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.frontend.GameEntry;
import dev.superjavendo.frontend.GameList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Everything the library is made of on disk: the game list, ROM folders, CRC cache, metadata, and the user's decisions.
 * {@link #rebuild()} recomputes the games and the ROM Manager catalogue; the slow methods ({@link #rescan}, {@link #computeCrcs})
 * are meant for a background thread, everything else for the event thread.
 */
final class LibraryData {
    final AppPaths paths;
    final GameList gameList;
    final Sources sources;
    final Overrides overrides;
    final Meta crcs;
    private final Covers covers;
    private MetaDb db = new MetaDb();
    private List<Game> all = List.of();
    private RomCatalog catalog;

    LibraryData(AppPaths paths, Covers covers) {
        this.paths = paths;
        this.covers = covers;
        gameList = new GameList(paths.gameListFile());
        sources = new Sources(paths.sourcesFile());
        overrides = new Overrides(paths.overridesFile());
        crcs = new Meta(paths.configDir().resolve("crc.cache"));
        gameList.load();
        sources.load();
        overrides.load();
        crcs.load();
        if (sources.isEmpty() && !gameList.games().isEmpty()) {
            sources.seedFrom(gameList.games().stream().map(GameEntry::path).toList());
            sources.save();
        }
        rebuild();
    }

    List<Game> all() { return all; }
    RomCatalog catalog() { return catalog; }
    MetaDb metadata() { return db; }

    /** Reads games.tsv again and recomputes games and catalogue from the current game list (CRCs only from the cache). */
    void rebuild() {
        db = MetaDb.load(paths.metadataFile());
        List<Game> out = new ArrayList<>();
        for (GameEntry e : gameList.games()) {
            String crc = crcs.cached(e.path());
            long size = 0;
            try { size = Files.size(e.path()); } catch (Exception ignored) { }
            out.add(Game.of(e, db.get(crc), crc, size));
        }
        all = out;
        catalog = new RomCatalog(all, overrides, g -> covers.exists(g.path()), db.size() > 0);
    }

    /** Computes the CRC32 of every listed ROM not in the cache. @return true if any was computed */
    boolean computeCrcs() {
        boolean any = false;
        for (GameEntry e : gameList.games()) if (crcs.cached(e.path()) == null) { crcs.compute(e.path()); any = true; }
        if (any) crcs.save();
        return any;
    }

    /** Scans every source folder, saves the list and computes the new CRCs. @return number of games added */
    int rescan() {
        int added = 0;
        for (Sources.Folder f : sources.folders()) {
            try {
                added += gameList.scan(f.path());
                sources.markScanned(f.path(), System.currentTimeMillis());
            } catch (IOException e) {
                System.err.println("Cannot scan " + f.path() + ": " + e.getMessage());
            }
        }
        try { gameList.save(); } catch (IOException e) { System.err.println("Cannot save game list: " + e.getMessage()); }
        sources.save();
        computeCrcs();
        return added;
    }

    void addFolder(Path dir) { sources.add(dir); sources.save(); }

    /** Removes the folder and the games listed under it (the files stay on disk). */
    void removeFolder(Path dir) {
        sources.remove(dir);
        gameList.removeUnder(dir);
        sources.save();
        try { gameList.save(); } catch (IOException e) { System.err.println("Cannot save game list: " + e.getMessage()); }
    }

    void setHidden(Collection<Path> roms, boolean hidden) {
        for (Path p : roms) overrides.setHidden(p, hidden);
        overrides.save();
    }

    void setPrimary(Game g) { overrides.setPrimary(g.groupKey(), g.path()); overrides.save(); }

    /** Moves the ROM to the trash and takes it off the list. */
    void trash(Game g) throws IOException {
        Trash.move(g.path(), paths.trashDir());
        gameList.remove(g.path());
        gameList.save();
    }
}
