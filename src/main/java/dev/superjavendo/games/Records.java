package dev.superjavendo.games;

import java.util.List;

/**
 * High scores / best times read from a game's battery save, as a table the library draws the same way for every game.
 * {@code columns} are i18n keys for the column headers (e.g. {@code records.race}); each row has one value per column
 * (display text, null when the game has no such value yet). Groups (e.g. a league) hold rows (e.g. a track).
 */
public record Records(List<String> columns, List<Group> groups) {
    public record Group(String title, List<Row> rows) { }

    public record Row(String label, List<String> values) {
        public static Row of(String label, String... values) { return new Row(label, java.util.Arrays.asList(values)); }
    }

    public Records {
        columns = List.copyOf(columns);
        groups = List.copyOf(groups);
        for (Group g : groups)
            for (Row r : g.rows())
                if (r.values().size() != columns.size())
                    throw new IllegalArgumentException(r.label() + ": " + r.values().size() + " values for " + columns.size() + " columns");
    }

    public boolean isEmpty() { return groups.stream().allMatch(g -> g.rows().isEmpty()); }
}
