package dev.superjavendo.frontend2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Optional game metadata, one line per game: {@code CRC32 <TAB> title <TAB> year <TAB> publisher <TAB> genre <TAB> players <TAB> region}
 * ({@code #} lines are comments). Looked up by the CRC32 of the ROM file.
 */
public final class MetaDb {
    public record Meta(String title, int year, String publisher, String genre, int players, String region) { }

    private final Map<String, Meta> byCrc = new HashMap<>();

    public static MetaDb load(Path file) {
        MetaDb db = new MetaDb();
        if (!Files.exists(file)) return db;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 2) continue;
                db.byCrc.put(f[0].trim().toUpperCase(Locale.ROOT), new Meta(f[1].trim(), num(f, 2), str(f, 3), str(f, 4), num(f, 5), str(f, 6)));
            }
        } catch (IOException e) {
            System.err.println("Cannot read " + file + ": " + e.getMessage());
        }
        return db;
    }

    private static String str(String[] f, int i) { return i < f.length ? f[i].trim() : ""; }

    private static int num(String[] f, int i) {
        try { return i < f.length ? Integer.parseInt(f[i].trim()) : 0; } catch (NumberFormatException e) { return 0; }
    }

    public Meta get(String crc) { return crc == null ? null : byCrc.get(crc.toUpperCase(Locale.ROOT)); }

    public int size() { return byCrc.size(); }
}
