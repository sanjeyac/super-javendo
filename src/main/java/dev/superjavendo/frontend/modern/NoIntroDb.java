package dev.superjavendo.frontend.modern;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A No-Intro DAT (clrmamepro format, as in libretro-database {@code metadat/no-intro}): the CRC32 of every verified dump
 * and its canonical name. The CRCs are of the ROM data without a copier header, like {@link Meta} computes them.
 */
public final class NoIntroDb {
    private static final Pattern GAME = Pattern.compile("name \"([^\"]+)\"[^\\n]*\\n(?:[^\\n]*\\n)*?\\s*rom \\(.*? crc ([0-9A-Fa-f]{8})");

    private final Map<String, String> byCrc = new HashMap<>();

    public static NoIntroDb load(Path file) {
        NoIntroDb db = new NoIntroDb();
        if (!Files.exists(file)) return db;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // One "game ( ... )" block per entry; the first "name" is the game's, the crc is on its rom line.
            for (String block : text.split("(?m)^game \\(")) {
                Matcher m = GAME.matcher(block);
                if (m.find()) db.byCrc.putIfAbsent(m.group(2).toUpperCase(Locale.ROOT), m.group(1));
            }
        } catch (IOException e) {
            System.err.println("Cannot read " + file + ": " + e.getMessage());
        }
        return db;
    }

    /** The No-Intro name of the dump with this CRC32, or null if it is not a verified dump. */
    public String nameOf(String crc) { return crc == null ? null : byCrc.get(crc.toUpperCase(Locale.ROOT)); }

    public int size() { return byCrc.size(); }
}
