package dev.superjavendo.frontend.modern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NoIntroDbTest {
    @TempDir Path tmp;

    private static String game(String name, String crc) {
        return "game (\n\tname \"" + name + "\"\n\tdescription \"" + name + "\"\n\trom ( name \"" + name + ".sfc\" size 1 crc " + crc + " md5 AA sha1 BB )\n)\n\n";
    }

    @Test void missingFileGivesAnEmptyDb() {
        NoIntroDb db = NoIntroDb.load(tmp.resolve("nope.dat"));
        assertEquals(0, db.size());
        assertNull(db.nameOf("0000ABCD"));
    }

    @Test void parsesSeveralGamesAndIgnoresTheHeader() throws Exception {
        Path f = tmp.resolve("a.dat");
        Files.writeString(f, "clrmamepro (\n\tname \"Nintendo - SNES\"\n\tversion 1\n)\n\n" + game("F-Zero (USA)", "AAAAAAAA") + game("Zelda (Europe)", "bbbbbbbb"));
        NoIntroDb db = NoIntroDb.load(f);
        assertEquals(2, db.size());
        assertEquals("F-Zero (USA)", db.nameOf("AAAAAAAA"));
        assertEquals("Zelda (Europe)", db.nameOf("BBBBBBBB"));    // CRC case does not matter, on either side
        assertEquals("Zelda (Europe)", db.nameOf("bbbbbbbb"));
        assertNull(db.nameOf("CCCCCCCC"));
        assertNull(db.nameOf(null));
    }

    @Test void firstEntryWinsForARepeatedCrc() throws Exception {
        Path f = tmp.resolve("a.dat");
        Files.writeString(f, game("First", "12345678") + game("Second", "12345678"));
        NoIntroDb db = NoIntroDb.load(f);
        assertEquals(1, db.size());
        assertEquals("First", db.nameOf("12345678"));
    }
}
