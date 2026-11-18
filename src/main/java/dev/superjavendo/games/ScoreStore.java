package dev.superjavendo.games;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.i18n.I18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The best scores of the games that only keep them in RAM, one file per game ({@code <CRC32>.tsv} in {@link AppPaths#scoresDir()},
 * a line per entry: {@code score TAB name}). What the monitor sees is merged into what is already there, so the list survives
 * the console being switched off.
 */
public final class ScoreStore {
    private final Path dir;

    public ScoreStore(AppPaths paths) { this.dir = paths.scoresDir(); }

    private Path file(String crc) { return dir.resolve(crc.trim().toUpperCase(Locale.ROOT) + ".tsv"); }

    /** The stored entries, best first. Empty if there is no file or it cannot be read. */
    public List<ScoreEntry> load(String crc) {
        List<ScoreEntry> out = new ArrayList<>();
        try {
            Path f = file(crc);
            if (!Files.isRegularFile(f)) return out;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                try { out.add(new ScoreEntry(Long.parseLong(line.substring(0, tab)), line.substring(tab + 1))); }
                catch (NumberFormatException ignored) { }
            }
        } catch (IOException | RuntimeException e) {
            return new ArrayList<>();
        }
        out.sort(BEST_FIRST);
        return out;
    }

    private static final Comparator<ScoreEntry> BEST_FIRST = Comparator.comparingLong(ScoreEntry::score).reversed();

    /**
     * Adds what the game's table shows now. Entries the game fills in by itself are ignored. An entry with the same score as a
     * stored one whose name is a prefix of it (or the other way round) is the same entry being typed: the longer name wins.
     * The list is cut to the game's number of slots. Returns whether the stored list changed (and was saved).
     */
    public boolean merge(String crc, RamScores spec, List<ScoreEntry> seen) {
        List<ScoreEntry> before = load(crc), list = new ArrayList<>(before);
        for (ScoreEntry e : seen) {
            if (e.score() <= 0 || e.name().isBlank() || spec.isDefault(e)) continue;
            boolean merged = false;
            for (int i = 0; i < list.size() && !merged; i++) {
                ScoreEntry o = list.get(i);
                if (o.score() != e.score()) continue;
                if (o.name().equals(e.name()) || e.name().startsWith(o.name())) { list.set(i, e); merged = true; }
                else if (o.name().startsWith(e.name())) merged = true;
            }
            if (!merged) list.add(e);
        }
        list.sort(BEST_FIRST);                                   // stable: on a tie the older entry stays first
        if (list.size() > spec.slots()) list = new ArrayList<>(list.subList(0, spec.slots()));
        if (list.equals(before)) return false;
        try {
            save(crc, list);
            return true;
        } catch (IOException e) {
            System.err.println("Scores not saved: " + e.getMessage());
            return false;
        }
    }

    private void save(String crc, List<ScoreEntry> list) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        for (ScoreEntry e : list) sb.append(e.score()).append('\t').append(e.name()).append('\n');
        Path f = file(crc), tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, sb, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The stored scores as the table the library draws, or empty if there are none yet. */
    public Optional<Records> records(String crc) {
        List<ScoreEntry> list = load(crc);
        if (list.isEmpty()) return Optional.empty();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.forLanguageTag(I18n.language()));
        List<Records.Row> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
            rows.add(Records.Row.of((i + 1) + "  " + list.get(i).name(), nf.format(list.get(i).score())));
        return Optional.of(new Records(List.of("records.score"), List.of(new Records.Group(I18n.t("records.top"), rows))));
    }
}
