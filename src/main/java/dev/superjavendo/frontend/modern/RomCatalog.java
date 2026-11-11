package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** What the ROM Manager shows: one row per ROM file with its status, plus the counters and filters. No UI here. */
public final class RomCatalog {
    public enum Status {
        OK("st.ok"), DUPLICATE("st.dup"), NO_COVER("st.nocover"), UNKNOWN("st.unknown"), ERROR("st.error");
        private final String key;
        Status(String key) { this.key = key; }
        public String label() { return t(key); }
    }

    public enum Filter {
        TO_FIX("flt.tofix"), ALL("flt.all"), NO_COVER("flt.nocover"), DUPLICATES("flt.dup"), UNKNOWN("flt.unknown"), HIDDEN("flt.hidden");
        private final String key;
        Filter(String key) { this.key = key; }
        public String label() { return t(key); }
    }

    /** @param main true for the file of its group the library shows; @param groupSize files of the same game (including this one) */
    public record Row(Game game, Status status, boolean hasCover, boolean hasMeta, boolean hidden, boolean main, int groupSize) {
        public boolean toFix() { return !hidden && status != Status.OK; }
    }

    public record Counters(int total, int recognized, int noCover, int duplicates, int errors) { }

    private final List<Row> rows;
    private final boolean metadataLoaded;

    /**
     * @param all            every ROM file, hidden ones included
     * @param metadataLoaded whether a metadata file with entries exists (without one nothing can be "not recognised")
     */
    public RomCatalog(List<Game> all, Overrides overrides, Predicate<Game> hasCover, boolean metadataLoaded) {
        this.metadataLoaded = metadataLoaded;
        Map<String, List<Game>> groups = new LinkedHashMap<>();
        for (Game g : all) groups.computeIfAbsent(g.groupKey(), k -> new ArrayList<>()).add(g);
        List<Row> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<Game> visible = e.getValue().stream().filter(g -> !overrides.isHidden(g.path())).toList();
            Game main = visible.isEmpty() ? null : LibraryModel.mainOf(visible, overrides.primaryOf(e.getKey()));
            for (Game g : e.getValue()) {
                boolean hidden = overrides.isHidden(g.path());
                boolean isMain = main != null && main.path().equals(g.path());
                boolean cover = hasCover.test(g);
                boolean meta = g.year() > 0 || !g.genre().isEmpty() || !g.publisher().isEmpty() || g.players() > 0;
                Status st;
                if (g.size() <= 0) st = Status.ERROR;
                else if (!hidden && e.getValue().size() > 1 && !isMain) st = Status.DUPLICATE;
                else if (metadataLoaded && !meta) st = Status.UNKNOWN;
                else if (!cover) st = Status.NO_COVER;
                else st = Status.OK;
                out.add(new Row(g, st, cover, meta, hidden, isMain, e.getValue().size()));
            }
        }
        rows = out;
    }

    public List<Row> rows() { return rows; }

    public boolean metadataLoaded() { return metadataLoaded; }

    public Counters counters() {
        int rec = 0, nc = 0, dup = 0, err = 0;
        for (Row r : rows) {
            if (r.hasMeta()) rec++;
            if (!r.hidden() && !r.hasCover() && r.status() != Status.ERROR) nc++;
            if (r.groupSize() > 1) dup++;
            if (r.status() == Status.ERROR) err++;
        }
        return new Counters(rows.size(), rec, nc, dup, err);
    }

    public int count(Filter f) { return (int) rows.stream().filter(r -> matches(r, f)).count(); }

    static boolean matches(Row r, Filter f) {
        return switch (f) {
            case ALL -> true;
            case TO_FIX -> r.toFix();
            case NO_COVER -> !r.hidden() && !r.hasCover() && r.status() != Status.ERROR;
            case DUPLICATES -> r.groupSize() > 1;
            case UNKNOWN -> !r.hidden() && r.status() == Status.UNKNOWN;
            case HIDDEN -> r.hidden();
        };
    }

    /** Rows of the filter whose file name, title or CRC contains the query (case-insensitive), sorted by file name. */
    public List<Row> view(Filter f, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (!matches(r, f)) continue;
            if (!q.isEmpty() && !r.game().fileName().toLowerCase(Locale.ROOT).contains(q)
                    && !r.game().title().toLowerCase(Locale.ROOT).contains(q)
                    && !(r.game().crc32() != null && r.game().crc32().toLowerCase(Locale.ROOT).contains(q))) continue;
            out.add(r);
        }
        out.sort((a, b) -> a.game().fileName().compareToIgnoreCase(b.game().fileName()));
        return out;
    }

    /** All files of the same game as {@code g}, main file first. */
    public List<Row> group(Game g) {
        List<Row> out = new ArrayList<>(rows.stream().filter(r -> r.game().groupKey().equals(g.groupKey())).toList());
        out.sort((a, b) -> Boolean.compare(b.main(), a.main()));
        return out;
    }
}
