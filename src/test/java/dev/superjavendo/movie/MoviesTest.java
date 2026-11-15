package dev.superjavendo.movie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MoviesTest {
    @TempDir Path dir;

    private Path add(Movies store, int crc, long at, int frames) throws Exception {
        Movie m = new Movie(crc, "Game");
        m.createdAt = at;
        for (int i = 0; i < frames; i++) m.append(0, 0);
        m.endHash = 77;
        Path f = dir.resolve(Movies.hex(crc) + "-" + at + ".sjm");
        m.save(f);
        return f;
    }

    @Test void listsOnlyThisGamesMoviesNewestFirst() throws Exception {
        Movies s = new Movies(dir);
        add(s, 0xAABBCCDD, 1000, 60);
        Path newer = add(s, 0xAABBCCDD, 2000, 120);
        add(s, 0x11223344, 3000, 10);
        var l = s.list(0xAABBCCDD);
        assertEquals(2, l.size());
        assertEquals(newer, l.get(0).file());
        assertEquals(120, l.get(0).info().frames());
        assertEquals(1, s.list(0x11223344).size());
        assertTrue(s.list(0x01020304).isEmpty());
    }

    @Test void missingFolderIsEmptyAndGarbageIsSkipped() throws Exception {
        assertTrue(new Movies(dir.resolve("nope")).list(1).isEmpty());
        Files.write(dir.resolve(Movies.hex(5) + "-x.sjm"), new byte[]{1, 2, 3});
        assertTrue(new Movies(dir).list(5).isEmpty());
    }

    @Test void renameAndVerifiedLiveInTheHeader() throws Exception {
        Movies s = new Movies(dir);
        Path f = add(s, 9, 1000, 300);
        assertEquals(Movie.UNVERIFIED, s.list(9).get(0).info().verified());
        s.rename(f, "  any% attempt  ");
        s.setVerified(f, true);
        var e = s.list(9).get(0);
        assertEquals("any% attempt", e.info().label());
        assertEquals("any% attempt", e.displayName());
        assertEquals(Movie.VERIFIED, e.info().verified());
        assertEquals(300, Movie.load(f).length());           // the inputs survived the rewrite
        assertEquals(77, Movie.load(f).endHash);
        s.setVerified(f, false);
        assertEquals(Movie.DESYNC, s.list(9).get(0).info().verified());
    }

    @Test void newFileNeverOverwrites() throws Exception {
        Movies s = new Movies(dir);
        Path a = s.newFile(1);
        Files.write(a, new byte[0]);
        assertNotEquals(a, s.newFile(1));
        assertTrue(a.getFileName().toString().startsWith("00000001-"));
    }

    @Test void version1FilesStillRead() throws Exception {
        // header of a v1 file: magic, version 1, crc, title, frames, endHash, then runs
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        var gz = new java.util.zip.GZIPOutputStream(raw);
        var o = new java.io.DataOutputStream(gz);
        o.writeInt(0x534A4D56); o.writeInt(1); o.writeInt(0xCAFE); o.writeUTF("Old"); o.writeInt(5); o.writeInt(9);
        o.writeShort(0x8000); o.writeShort(0); o.writeInt(5);
        o.flush(); gz.finish();
        Path f = dir.resolve("000000000cafe-old.sjm");
        Files.write(f, raw.toByteArray());
        Movie m = Movie.load(f);
        assertEquals(5, m.length());
        assertEquals("", m.label);
        assertEquals(Movie.UNVERIFIED, m.verified);
        assertEquals(5, Movie.readInfo(f).frames());
    }
}
