package dev.superjavendo.games;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.i18n.I18n;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BruceLeeTest {
    private static final String CRC = "407C5C24";
    private final RamScores spec = new BruceLee().liveScores().orElseThrow();

    /** The 133 bytes from $7E:5D7D as the game leaves them at power on: "BRUCE LEE" with 5.0M, 4.5M ... 1.5M. */
    private byte[] table() {
        byte[] r = new byte[spec.length()];
        int[] defaults = {0x50, 0x45, 0x40, 0x35, 0x30, 0x25, 0x20, 0x15};
        for (int i = 0; i < 8; i++) put(r, i, defaults[i], "BRUCE LEE");
        return r;
    }

    /** One record: BCD score (units of 100,000, low byte first), 12 characters padded with blanks. */
    private static void put(byte[] r, int slot, int bcdUnits, String name) {
        int o = slot * 17;
        r[o] = (byte) (bcdUnits & 0xFF);
        r[o + 1] = (byte) (bcdUnits >> 8);
        byte[] n = String.format("%-12s", name).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, r, o + 2, 12);
    }

    @Test void theRegistryKnowsTheGameAndItsDumpName() {
        assertInstanceOf(BruceLee.class, Games.byCrc("407c5c24").orElseThrow());
        assertEquals("Dragon - The Bruce Lee Story (USA)", Games.dumpName(CRC));
        assertTrue(BruceLee.matches(CRC));
        assertEquals(0x5D7D, spec.wramOffset());
        assertEquals(133, spec.length());
    }

    @Test void theTableTheGameStartsWithIsAllDefaults() {
        List<ScoreEntry> e = spec.decode(table());
        assertEquals(8, e.size());
        assertEquals(5_000_000, e.get(0).score());
        assertEquals("BRUCE LEE", e.get(0).name());
        for (ScoreEntry x : e) assertTrue(spec.isDefault(x), x.toString());
    }

    @Test void aNewEntryTakesTheLastSlotAndScoresAreInHundredThousands() {
        byte[] r = table();
        put(r, 7, 0x0101, "AAAA");                       // as seen in a real state: 10,120,000 stored as 101
        List<ScoreEntry> e = spec.decode(r);
        ScoreEntry mine = e.get(7);
        assertEquals(new ScoreEntry(10_100_000, "AAAA"), mine);
        assertFalse(spec.isDefault(mine));
    }

    @Test void memoryThatIsNotATableYetDecodesToNothing() {
        assertTrue(spec.decode(new byte[spec.length()]).isEmpty());                 // WRAM still zero
        byte[] junk = table();
        junk[2] = 0x05;                                                             // a control character inside a name
        junk[17] = (byte) 0xFA;                                                     // not BCD
        assertEquals(6, spec.decode(junk).size());
    }

    @Test void onlyTheRecordsOfThePlayerAreStoredAndTheyComeBackSorted(@TempDir Path tmp) {
        ScoreStore store = new ScoreStore(new AppPaths(tmp));
        assertFalse(store.merge(CRC, spec, spec.decode(table())));                  // defaults only: nothing to keep
        assertTrue(store.load(CRC).isEmpty());
        byte[] r = table();
        put(r, 7, 0x0101, "AAAA");
        put(r, 3, 0x0036, "ZED");                                                   // 3.6M
        assertTrue(store.merge(CRC, spec, spec.decode(r)));
        assertEquals(List.of(new ScoreEntry(10_100_000, "AAAA"), new ScoreEntry(3_600_000, "ZED")), store.load(CRC));
        assertFalse(store.merge(CRC, spec, spec.decode(r)), "same table again changes nothing");
        // the console was switched off: the game starts again from its own table, the stored list stays
        assertFalse(store.merge(CRC, spec, spec.decode(table())));
        assertEquals(2, new ScoreStore(new AppPaths(tmp)).load(CRC).size());
    }

    @Test void aNameBeingTypedIsOneEntryNotMany(@TempDir Path tmp) {
        ScoreStore store = new ScoreStore(new AppPaths(tmp));
        for (String typed : new String[]{"A", "AA", "AAAA"}) {
            byte[] r = table();
            put(r, 7, 0x0101, typed);
            store.merge(CRC, spec, spec.decode(r));
        }
        assertEquals(List.of(new ScoreEntry(10_100_000, "AAAA")), store.load(CRC));
        byte[] again = table();
        put(again, 7, 0x0101, "AA");                                                // an older partial copy arriving late
        assertFalse(store.merge(CRC, spec, spec.decode(again)));
    }

    @Test void onlyAsManyEntriesAsTheGameHasSlotsAreKept(@TempDir Path tmp) {
        ScoreStore store = new ScoreStore(new AppPaths(tmp));
        for (int i = 1; i <= 10; i++) {
            byte[] r = table();
            int units = 100 + i;                                                    // 10.1M, 10.2M ... 11.0M
            put(r, 7, ((units / 100) << 8) | (((units / 10) % 10) << 4) | units % 10, "P" + i);
            store.merge(CRC, spec, spec.decode(r));
        }
        List<ScoreEntry> l = store.load(CRC);
        assertEquals(8, l.size());
        assertEquals("P10", l.get(0).name());
        assertEquals("P3", l.get(7).name());
    }

    @Test void theLibraryTableShowsRankNameAndScore(@TempDir Path tmp) {
        I18n.set("en");
        ScoreStore store = new ScoreStore(new AppPaths(tmp));
        assertTrue(store.records(CRC).isEmpty());
        byte[] r = table();
        put(r, 7, 0x0101, "AAAA");
        store.merge(CRC, spec, spec.decode(r));
        Records rec = store.records(CRC).orElseThrow();
        assertEquals(List.of("records.score"), rec.columns());
        assertEquals("1  AAAA", rec.groups().get(0).rows().get(0).label());
        assertEquals("10,100,000", rec.groups().get(0).rows().get(0).values().get(0));
    }

    @Test void theMonitorStoresWhatItIsHandedOnItsOwnThread(@TempDir Path tmp) throws Exception {
        ScoreStore store = new ScoreStore(new AppPaths(tmp));
        byte[] r = table();
        put(r, 7, 0x0101, "AAAA");
        try (ScoreMonitor m = new ScoreMonitor(CRC, spec, store, 20)) {
            m.sample(r);
            long end = System.currentTimeMillis() + 5000;
            while (store.load(CRC).isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10);
            assertEquals(1, store.load(CRC).size());
        }
    }

    @Test void closingStoresTheLastCopyEvenIfItNeverSettled(@TempDir Path tmp) {
        ScoreStore store = new ScoreStore(new AppPaths(tmp));
        byte[] r = table();
        put(r, 7, 0x0099, "LAST");
        ScoreMonitor m = new ScoreMonitor(CRC, spec, store, 60_000);                // the tick never comes
        m.sample(r);
        m.close();
        assertEquals(List.of(new ScoreEntry(9_900_000, "LAST")), store.load(CRC));
    }
}
