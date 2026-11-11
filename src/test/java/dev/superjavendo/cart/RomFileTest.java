package dev.superjavendo.cart;

import dev.superjavendo.frontend.legacy.GameList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RomFileTest {
    private static Path zip(Path dir, String name, String entry, byte[] data) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream o = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry(entry));
            z.write(data);
            z.closeEntry();
        }
        return p;
    }

    @Test void readsFirstRomInsideZip(@TempDir Path dir) throws IOException {
        byte[] rom = {1, 2, 3, 4};
        Path z = zip(dir, "game.zip", "inner/Game (USA).sfc", rom);
        assertEquals("inner/Game (USA).sfc", RomFile.firstRomEntry(z));
        assertArrayEquals(rom, RomFile.read(z));
    }

    @Test void zipWithoutRomIsRejected(@TempDir Path dir) throws IOException {
        Path z = zip(dir, "docs.zip", "readme.txt", new byte[]{1});
        assertNull(RomFile.firstRomEntry(z));
        IOException e = assertThrows(IOException.class, () -> RomFile.read(z));
        assertTrue(e.getMessage().contains("does not contain a SNES ROM"));
    }

    @Test void scanAddsZipWithRomAndReportsTheOthers(@TempDir Path dir) throws IOException {
        zip(dir, "good.zip", "a.smc", new byte[]{1});
        zip(dir, "bad.zip", "notes.txt", new byte[]{1});
        Files.write(dir.resolve("plain.sfc"), new byte[]{1});
        GameList list = new GameList(dir.resolve("game.list"));
        assertEquals(2, list.scan(dir));
        assertEquals(1, list.rejectedZips().size());
        assertEquals("bad.zip", list.rejectedZips().get(0).getFileName().toString());
        assertTrue(list.games().stream().anyMatch(g -> g.title().equals("good")));
    }

    @Test void plainFilesAreReadAsIs(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x.sfc");
        Files.write(p, new byte[]{9, 8});
        assertArrayEquals(new byte[]{9, 8}, RomFile.read(p));
    }
}
