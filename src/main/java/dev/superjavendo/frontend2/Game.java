package dev.superjavendo.frontend2;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.frontend.GameEntry;
import dev.superjavendo.frontend.Previews;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A game as shown by the library: the ROM entry plus whatever metadata is known. Missing text fields are empty, year 0. */
public record Game(GameEntry entry, String title, String publisher, int year, String genre, int players, String region,
                   String crc32, long size, int versions, String groupKey) {

    private static final Pattern TAG = Pattern.compile("\\(([^)]*)\\)");

    public Path path() { return entry.path(); }

    public String fileName() { return entry.path().getFileName().toString(); }

    /** Region from the usual dump tags: (U)/(USA), (E)/(Europe), (J)/(Japan). */
    public static String regionOf(String fileName) {
        Matcher m = TAG.matcher(fileName);
        while (m.find()) {
            String t = m.group(1).trim().toLowerCase(Locale.ROOT);
            if (t.equals("u") || t.startsWith("usa") || t.equals("us")) return "USA";
            if (t.equals("e") || t.startsWith("europe") || t.equals("eur")) return "EUR";
            if (t.equals("j") || t.startsWith("japan")) return "JPN";
        }
        return "";
    }

    /** Title without dump tags: "Super Metroid (U) [!]" → "Super Metroid". */
    public static String cleanTitle(String name) {
        String s = name.replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]", "").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? name : s;
    }

    public static Game of(GameEntry e, MetaDb.Meta meta, String crc, long size) {
        String base = e.title();
        String title = meta != null && !meta.title().isBlank() ? meta.title() : cleanTitle(base);
        String region = meta != null && !meta.region().isBlank() ? meta.region() : regionOf(e.path().getFileName().toString());
        String key = Previews.normalizeTitle(title);
        if (key.isEmpty()) key = e.path().toString();
        return new Game(e, title, meta == null ? "" : meta.publisher(), meta == null ? 0 : meta.year(),
                meta == null ? "" : meta.genre(), meta == null ? 0 : meta.players(), region, crc, size, 1, key);
    }

    public enum Quality { VERIFIED, UNKNOWN, BAD }

    /** Dump quality from the usual tags: [!] verified good dump, [b1]/[b2]… bad dump. */
    public Quality quality() {
        String n = fileName();
        if (n.matches(".*\\[b\\d*\\].*")) return Quality.BAD;
        return n.contains("[!]") ? Quality.VERIFIED : Quality.UNKNOWN;
    }

    /** The first bracket tag of the file name ("b1"), or "" if there is none. */
    public String badTag() {
        Matcher m = Pattern.compile("\\[(b\\d*)\\]").matcher(fileName());
        return m.find() ? m.group(1) : "";
    }

    /** The region as shown to the user: "USA", or the translated Europe / Japan; other text (from games.tsv) is shown as it is. */
    public String regionLabel() {
        String r = region.toLowerCase(java.util.Locale.ROOT);
        if (r.equals("eur") || r.equals("europe") || r.equals("europa")) return t("region.eur");
        if (r.equals("jpn") || r.equals("japan") || r.equals("giappone")) return t("region.jpn");
        return region;
    }

    public Game withVersions(int n) {
        return new Game(entry, title, publisher, year, genre, players, region, crc32, size, n, groupKey);
    }

    /** "1992 · Corse · USA", skipping unknown parts. */
    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (year > 0) sb.append(year);
        if (!genre.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(genre);
        if (!region.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(regionLabel());
        return sb.length() == 0 ? "—" : sb.toString();
    }
}
