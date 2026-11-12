package dev.superjavendo.games;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GamesTest {
    /** An empty F-ZERO save: header, three blocks of 55 default times (9'59"99, flag clear) and checksums. */
    private static byte[] emptySave() {
        byte[] s = new byte[2048];
        System.arraycopy("FZERO".getBytes(), 0, s, 0, 5);
        for (int b = 0; b < 3; b++) for (int i = 0; i < 55; i++) { int o = 5 + 167 * b + 3 * i; s[o] = 0x09; s[o + 1] = 0x59; s[o + 2] = (byte) 0x99; }
        return s;
    }

    private static void set(byte[] s, int league, int track, int slot, int min, int sec, int cs) {
        int o = 5 + 167 * league + 33 * track + 3 * slot;
        s[o] = (byte) (0x80 | min); s[o + 1] = (byte) (((sec / 10) << 4) | sec % 10); s[o + 2] = (byte) (((cs / 10) << 4) | cs % 10);
    }

    @Test void registryFindsTheGameByCrcCaseInsensitively() {
        assertInstanceOf(FZero.class, Games.byCrc("aa0e31de").orElseThrow());
        assertInstanceOf(FZero.class, Games.byCrc(" F1D8F5DA ").orElseThrow());
        assertTrue(Games.byCrc("00000000").isEmpty());          // unknown: "no idea", not "invalid"
        assertTrue(Games.byCrc(null).isEmpty());
    }

    @Test void staticMatchesAgreesWithTheRegistry() {
        for (String crc : FZero.CRCS) {
            assertTrue(FZero.matches(crc), crc);
            assertInstanceOf(FZero.class, Games.byCrc(crc).orElseThrow());
        }
        assertFalse(FZero.matches("00000000"));
        assertFalse(FZero.matches(null));
    }

    @Test void dumpNamesComeFromTheGameClassWithoutAnyDatFile() {
        assertEquals("F-Zero (USA)", Games.dumpName("aa0e31de"));
        assertEquals("F-Zero (Europe)", new FZero().dumpName("F1D8F5DA"));
        assertNull(Games.dumpName("00000000"));
        assertNull(Games.dumpName(null));
        for (String crc : FZero.CRCS) assertTrue(FZero.DUMPS.get(crc).startsWith("F-Zero"), crc);
    }

    @Test void everyRegisteredCrcIsWellFormed() {
        assertFalse(Games.crcs().isEmpty());
        for (String c : Games.crcs()) assertTrue(c.matches("[0-9A-F]{8}"), c);
    }

    @Test void emptySaveHasNoRecords() {
        assertTrue(new FZero().readRecords(emptySave()).isEmpty());
    }

    @Test void readsBestRaceTimeAndLapPerTrack() {
        byte[] s = emptySave();
        set(s, 0, 0, 0, 2, 24, 46); set(s, 0, 0, 1, 2, 29, 0); set(s, 0, 0, 10, 0, 27, 18);   // Mute City I
        set(s, 0, 1, 0, 2, 38, 51); set(s, 0, 1, 10, 0, 29, 43);                              // Big Blue
        Records r = new FZero().readRecords(s);
        assertEquals(1, r.groups().size());
        assertEquals("Knight League", r.groups().get(0).title());
        assertEquals(List.of("records.race", "records.lap"), r.columns());
        assertEquals(Records.Row.of("Mute City I", "2'24\"46", "0'27\"18"), r.groups().get(0).rows().get(0));
        assertEquals(Records.Row.of("Big Blue", "2'38\"51", "0'29\"43"), r.groups().get(0).rows().get(1));
    }

    @Test void bestIsTheLowestEvenIfNotFirst() {
        byte[] s = emptySave();
        set(s, 1, 2, 3, 2, 50, 0); set(s, 1, 2, 4, 2, 10, 5);
        Records.Group g = new FZero().readRecords(s).groups().get(0);
        assertEquals("Queen League", g.title());
        assertEquals("Red Canyon I", g.rows().get(0).label());
        assertEquals("2'10\"05", g.rows().get(0).values().get(0));
        assertNull(g.rows().get(0).values().get(1));
    }

    @Test void garbageIsRejected() {
        FZero f = new FZero();
        assertNull(f.readRecords(new byte[2048]));
        assertNull(f.readRecords(null));
        assertNull(f.readRecords(new byte[10]));
        byte[] s = emptySave();
        s[5] = (byte) 0x8F;                       // not BCD: ignored, not a crash
        assertTrue(f.readRecords(s).isEmpty());
    }

    @Test void recordsAreReadFromTheSrmNextToTheRom(@TempDir Path tmp) throws Exception {
        Path rom = tmp.resolve("F-ZERO (U) [!].zip");
        assertEquals(tmp.resolve("F-ZERO (U) [!].srm"), GameSupport.srmOf(rom));
        FZero f = new FZero();
        assertTrue(f.records(rom).isEmpty());                         // no save yet
        byte[] s = emptySave();
        set(s, 0, 0, 0, 2, 24, 46);
        Files.write(GameSupport.srmOf(rom), s);
        assertEquals("2'24\"46", f.records(rom).orElseThrow().groups().get(0).rows().get(0).values().get(0));
        Files.write(GameSupport.srmOf(rom), new byte[100]);
        assertTrue(f.records(rom).isEmpty());                         // not a recognised save
    }

    @Test void everyRowMustHaveOneValuePerColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> new Records(List.of("a", "b"), List.of(new Records.Group("g", List.of(Records.Row.of("x", "1"))))));
        assertDoesNotThrow(() -> new Records(List.of("a"), List.of(new Records.Group("g", List.of(Records.Row.of("x", (String) null))))));
    }
}
