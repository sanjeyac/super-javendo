package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the library shows: the filters (collection, genre, players, search text) and the sort order applied to the games.
 * Games that are different versions of the same title are folded into one entry (the first file found).
 */
public final class LibraryModel {
    public enum Collection { ALL("coll.all"), RECENT("coll.recent"), FAVORITES("coll.fav"), NO_COVER("coll.nocover");
        private final String key;
        Collection(String k) { key = k; }
        public String label() { return t(key); }
    }

    public enum Sort { TITLE("sort.title"), RECENT("sort.recent"), YEAR("sort.year");
        private final String key;
        Sort(String k) { key = k; }
        public String label() { return t(key); }
        @Override public String toString() { return label(); }
    }

    /** Players filter: 0 = all, 1 = single player, 2 = two or more. */
    public Collection collection = Collection.ALL;
    public String genre;               // null = all
    public int players;
    public String query = "";
    public Sort sort = Sort.TITLE;

    private List<Game> games = List.of();
    private final UserData user;
    private final java.util.function.Predicate<Game> hasCover;

    public LibraryModel(UserData user, java.util.function.Predicate<Game> hasCover) {
        this.user = user;
        this.hasCover = hasCover;
    }

    /** Replaces the game list (no hiding, first file of each group is the main one). */
    public void setGames(List<Game> all) { setGames(all, null); }

    /**
     * Replaces the game list. Files with the same normalized title become one game with {@code versions > 1}; the main
     * file is the one the user chose, else a verified dump, else one that is not a bad dump, else the first. Hidden files are left out.
     */
    public void setGames(List<Game> all, Overrides overrides) {
        Map<String, List<Game>> groups = new LinkedHashMap<>();
        for (Game g : all) {
            if (overrides != null && overrides.isHidden(g.path())) continue;
            groups.computeIfAbsent(g.groupKey(), k -> new ArrayList<>()).add(g);
        }
        List<Game> out = new ArrayList<>();
        for (var e : groups.entrySet()) out.add(mainOf(e.getValue(), overrides == null ? null : overrides.primaryOf(e.getKey())).withVersions(e.getValue().size()));
        games = out;
    }

    /** The main file of a group of files of the same game. */
    static Game mainOf(List<Game> grp, Path chosen) {
        if (chosen != null) for (Game g : grp) if (g.path().toAbsolutePath().normalize().equals(chosen.toAbsolutePath().normalize())) return g;
        Game best = grp.get(0);
        for (Game g : grp) if (g.quality().compareTo(best.quality()) < 0) best = g;
        return best;
    }

    public List<Game> allGames() { return games; }

    public boolean favorite(Game g) { var e = user.peek(g.path()); return e != null && e.favorite; }

    public long lastPlayed(Game g) { var e = user.peek(g.path()); return e == null ? 0 : e.lastPlayed; }

    public long playSeconds(Game g) { var e = user.peek(g.path()); return e == null ? 0 : e.playSeconds; }

    public boolean toggleFavorite(Game g) {
        var e = user.of(g.path());
        e.favorite = !e.favorite;
        user.save();
        return e.favorite;
    }

    public int count(Collection c) {
        int n = 0;
        for (Game g : games) if (inCollection(g, c)) n++;
        return n;
    }

    private boolean inCollection(Game g, Collection c) {
        return switch (c) {
            case ALL -> true;
            case RECENT -> lastPlayed(g) > 0;
            case FAVORITES -> favorite(g);
            case NO_COVER -> !hasCover.test(g);
        };
    }

    /** Genres present in the library with their game counts, alphabetical. */
    public Map<String, Integer> genres() {
        Map<String, Integer> m = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Game g : games) if (!g.genre().isEmpty()) m.merge(g.genre(), 1, Integer::sum);
        return m;
    }

    private boolean matches(Game g) {
        if (!inCollection(g, collection)) return false;
        if (genre != null && !genre.equalsIgnoreCase(g.genre())) return false;
        if (players == 1 && g.players() != 1) return false;
        if (players == 2 && g.players() < 2) return false;
        if (!query.isBlank()) {
            String q = query.toLowerCase(Locale.ROOT).trim();
            if (!g.title().toLowerCase(Locale.ROOT).contains(q) && !g.publisher().toLowerCase(Locale.ROOT).contains(q)
                    && !g.fileName().toLowerCase(Locale.ROOT).contains(q)) return false;
        }
        return true;
    }

    public boolean filtersActive() {
        return collection != Collection.ALL || genre != null || players != 0 || !query.isBlank();
    }

    public void resetFilters() { collection = Collection.ALL; genre = null; players = 0; query = ""; }

    public List<Game> view() {
        List<Game> out = new ArrayList<>();
        for (Game g : games) if (matches(g)) out.add(g);
        Comparator<Game> byTitle = Comparator.comparing(g -> g.title().toLowerCase(Locale.ROOT));
        switch (sort) {
            case TITLE -> out.sort(byTitle);
            case RECENT -> out.sort(Comparator.<Game>comparingLong(g -> -lastPlayed(g)).thenComparing(byTitle));
            case YEAR -> out.sort(Comparator.<Game>comparingInt(g -> g.year() == 0 ? Integer.MAX_VALUE : g.year()).thenComparing(byTitle));
        }
        return out;
    }

    public Path pathOf(Game g) { return g.path(); }
}
