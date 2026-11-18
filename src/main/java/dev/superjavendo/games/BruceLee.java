package dev.superjavendo.games;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Dragon: The Bruce Lee Story (USA). The game keeps its eight best scores in WRAM and never saves them. Layout found by
 * comparing saved states (not from documentation): 8 records of 17 bytes starting at {@code $7E:5D7D}; each is the score
 * as 2 bytes of BCD little endian in units of 100,000 points (the game truncates, so 10,120,000 is stored as 101), then the
 * name as 12 ASCII characters padded with blanks, then 3 bytes of padding. The table is not kept sorted: the new entry takes
 * the last slot while the name is being typed, and the game's own list is built from it when it is shown. At power on the
 * game fills the table with "BRUCE LEE" and 5.0, 4.5 ... 1.5 million. The Europe dump has not been checked and is left out.
 */
public final class BruceLee implements GameSupport {
    static final Map<String, String> DUMPS = Map.of("407C5C24", "Dragon - The Bruce Lee Story (USA)");
    static final Set<String> CRCS = DUMPS.keySet();

    public static boolean matches(String crc) { return crc != null && CRCS.contains(crc.trim().toUpperCase(Locale.ROOT)); }

    @Override public String dumpName(String crc) { return crc == null ? null : DUMPS.get(crc.trim().toUpperCase(Locale.ROOT)); }

    @Override public String title() { return "Dragon: The Bruce Lee Story"; }

    @Override public boolean hasRecords() { return true; }

    @Override public Optional<RamScores> liveScores() { return Optional.of(TABLE); }

    static final int FIRST_SCORE = 0x5D7D, SLOTS = 8, STRIDE = 17, NAME_LEN = 12, UNIT = 100_000;
    static final String DEFAULT_NAME = "BRUCE LEE";
    /** The table the game starts with: every entry is "BRUCE LEE" with one of these scores. */
    static final Set<Long> DEFAULT_SCORES = Set.of(5_000_000L, 4_500_000L, 4_000_000L, 3_500_000L, 3_000_000L, 2_500_000L, 2_000_000L, 1_500_000L);

    private static final RamScores TABLE = new RamScores() {
        @Override public int wramOffset() { return FIRST_SCORE; }
        @Override public int length() { return STRIDE * (SLOTS - 1) + 2 + NAME_LEN; }
        @Override public int slots() { return SLOTS; }
        @Override public List<ScoreEntry> decode(byte[] region) { return BruceLee.decode(region); }
        @Override public boolean isDefault(ScoreEntry e) { return DEFAULT_NAME.equals(e.name()) && DEFAULT_SCORES.contains(e.score()); }
    };

    /** The valid entries of a copy of the table (before the table is up the bytes are zero: no entries). */
    static List<ScoreEntry> decode(byte[] region) {
        List<ScoreEntry> out = new ArrayList<>();
        if (region == null) return out;
        for (int i = 0; i < SLOTS; i++) {
            int o = i * STRIDE;
            if (o + 2 + NAME_LEN > region.length) break;
            int units = bcd16(region[o] & 0xFF, region[o + 1] & 0xFF);
            if (units <= 0) continue;
            StringBuilder name = new StringBuilder();
            boolean text = true;
            for (int c = 0; c < NAME_LEN && text; c++) {
                int b = region[o + 2 + c] & 0xFF;
                if (b < 0x20 || b > 0x7E) text = false; else name.append((char) b);
            }
            String n = name.toString().stripTrailing();
            if (text && !n.isBlank()) out.add(new ScoreEntry((long) units * UNIT, n));
        }
        return out;
    }

    /** Four BCD digits (low byte first), or -1 if a nibble is not a digit. */
    private static int bcd16(int lo, int hi) {
        int v = 0;
        for (int shift = 12; shift >= 0; shift -= 4) {
            int word = (hi << 8) | lo, d = (word >> shift) & 15;
            if (d > 9) return -1;
            v = v * 10 + d;
        }
        return v;
    }
}
