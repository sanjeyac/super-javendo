package dev.superjavendo.games;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F-ZERO. Battery save ({@code FZERO} header, 2 KB) layout deduced from real saves (not from documentation):
 * three blocks of 55 three-byte times followed by a 2-byte checksum (block b starts at 5 + 167·b), one block per league;
 * each block is 5 tracks × 11 times: 10 race times, then the best lap. A time is {@code flags|minutes, seconds, centiseconds}
 * in BCD; bit 7 of the first byte marks a recorded time (an empty slot is the default 9'59"99 with the bit clear).
 * League and track names follow the game's order; the block→league mapping is the part not yet checked against more saves.
 */
public final class FZero implements GameSupport {
    /** The game's verified dumps: CRC32 → No-Intro name (DAT 2026.10.07). */
    static final Map<String, String> DUMPS = Map.of(
            "F1D8F5DA", "F-Zero (Europe)",
            "7681EFC1", "F-Zero (Japan) (En)",
            "F3B356A7", "F-Zero (Japan) (En) (Virtual Console, Switch Online)",
            "AA0E31DE", "F-Zero (USA)",
            "61A8F2E3", "F-Zero (USA, Europe) (Virtual Console, Classic Mini, Switch Online)");
    static final Set<String> CRCS = DUMPS.keySet();

    public static boolean matches(String crc) { return crc != null && CRCS.contains(crc.trim().toUpperCase(Locale.ROOT)); }

    @Override public String dumpName(String crc) { return crc == null ? null : DUMPS.get(crc.trim().toUpperCase(Locale.ROOT)); }

    @Override public String title() { return "F-Zero"; }

    @Override public boolean hasRecords() { return true; }

    static final String[] LEAGUES = {"Knight League", "Queen League", "King League"};
    static final String[][] TRACKS = {
            {"Mute City I", "Big Blue", "Sand Ocean", "Death Wind I", "Silence"},
            {"Mute City II", "Port Town I", "Red Canyon I", "White Land I", "White Land II"},
            {"Fire Field", "Red Canyon II", "Death Wind II", "Mute City III", "Port Town II"},
    };
    private static final List<String> COLUMNS = List.of("records.race", "records.lap");
    private static final int BLOCK = 167, TIMES_PER_TRACK = 11, TRACKS_PER_LEAGUE = 5;

    /** Leagues and tracks without a time are left out. */
    @Override public Records readRecords(byte[] sram) {
        if (sram == null || sram.length < 5 + 3 * BLOCK || !"FZERO".equals(new String(sram, 0, 5, StandardCharsets.US_ASCII))) return null;
        List<Records.Group> groups = new ArrayList<>();
        for (int league = 0; league < 3; league++) {
            int base = 5 + BLOCK * league;
            List<Records.Row> rows = new ArrayList<>();
            for (int track = 0; track < TRACKS_PER_LEAGUE; track++) {
                int first = base + 3 * TIMES_PER_TRACK * track;
                int best = Integer.MAX_VALUE;
                for (int i = 0; i < TIMES_PER_TRACK - 1; i++) best = Math.min(best, time(sram, first + 3 * i));
                int lap = time(sram, first + 3 * (TIMES_PER_TRACK - 1));
                if (best == Integer.MAX_VALUE && lap == Integer.MAX_VALUE) continue;
                rows.add(Records.Row.of(TRACKS[league][track], format(best), format(lap)));
            }
            if (!rows.isEmpty()) groups.add(new Records.Group(LEAGUES[league], rows));
        }
        return new Records(COLUMNS, groups);
    }

    /** Centiseconds of the time at {@code off}, or MAX_VALUE if the slot is empty or not valid BCD. */
    static int time(byte[] s, int off) {
        int b0 = s[off] & 0xFF;
        if ((b0 & 0x80) == 0) return Integer.MAX_VALUE;
        int min = bcd(b0 & 0x0F), sec = bcd(s[off + 1] & 0xFF), cs = bcd(s[off + 2] & 0xFF);
        if (min < 0 || sec < 0 || cs < 0 || sec > 59) return Integer.MAX_VALUE;
        return (min * 60 + sec) * 100 + cs;
    }

    private static int bcd(int v) {
        int hi = v >> 4, lo = v & 15;
        return hi > 9 || lo > 9 ? -1 : hi * 10 + lo;
    }

    static String format(int cs) {
        return cs == Integer.MAX_VALUE ? null : String.format("%d'%02d\"%02d", cs / 6000, cs / 100 % 60, cs % 100);
    }
}
