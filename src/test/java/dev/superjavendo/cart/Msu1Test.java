package dev.superjavendo.cart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class Msu1Test {
    @TempDir Path dir;

    private Msu1 chip(byte[] data, int track, int[][] frames, int loop) throws Exception {
        Path rom = dir.resolve("game.sfc");
        Files.write(rom, new byte[16]);
        if (data != null) Files.write(dir.resolve("game.msu"), data);
        if (frames != null) {
            ByteBuffer b = ByteBuffer.allocate(8 + frames.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            b.put(new byte[]{'M', 'S', 'U', '1'}).putInt(loop);
            for (int[] f : frames) { b.putShort((short) f[0]); b.putShort((short) f[1]); }
            Files.write(dir.resolve("game-" + track + ".pcm"), b.array());
        }
        return new Msu1(rom);
    }

    private static void seek(Msu1 m, int off) { for (int i = 0; i < 4; i++) m.write(i, off >> (8 * i)); }
    private static void selectTrack(Msu1 m, int t) { m.write(4, t); m.write(5, t >> 8); }

    @Test void presentOnlyWithADataFile() throws Exception {
        assertFalse(Msu1.present(dir.resolve("game.sfc")));
        chip(new byte[4], 1, null, 0);
        assertTrue(Msu1.present(dir.resolve("game.sfc")));
    }

    @Test void identifiesItself() throws Exception {
        Msu1 m = chip(new byte[4], 1, null, 0);
        StringBuilder id = new StringBuilder();
        for (int r = 2; r < 8; r++) id.append((char) m.read(r));
        assertEquals("S-MSU1", id.toString());
        assertEquals(1, m.read(0) & 7);       // revision
    }

    @Test void dataPortStreamsFromTheSeekedOffset() throws Exception {
        byte[] d = new byte[300];
        for (int i = 0; i < d.length; i++) d[i] = (byte) i;
        Msu1 m = chip(d, 1, null, 0);
        seek(m, 0x10);
        assertEquals(0x10, m.read(1));
        assertEquals(0x11, m.read(1));        // auto-increment
        seek(m, 299);
        assertEquals(299 & 0xFF, m.read(1));
        assertEquals(0, m.read(1));           // past the end
    }

    @Test void missingTrackSetsTheErrorFlagAndBlocksPlayback() throws Exception {
        Msu1 m = chip(new byte[4], 1, null, 0);
        selectTrack(m, 7);
        assertNotEquals(0, m.read(0) & 0x08);
        m.write(7, 1);
        assertEquals(0, m.read(0) & 0x10);    // not playing
    }

    @Test void playAndRepeatFlags() throws Exception {
        Msu1 m = chip(new byte[4], 1, new int[][]{{100, -100}, {200, -200}}, 0);
        selectTrack(m, 1);
        assertEquals(0, m.read(0) & 0x08);
        m.write(7, 0x03);
        assertEquals(0x30, m.read(0) & 0x30); // playing + repeat
        selectTrack(m, 1);                    // selecting a track stops playback
        assertEquals(0, m.read(0) & 0x30);
    }

    @Test void audioIsMixedAtTheTrackVolume() throws Exception {
        int n = 4000;
        int[][] frames = new int[n][];
        for (int i = 0; i < n; i++) frames[i] = new int[]{10000, -10000};
        Msu1 m = chip(null == null ? new byte[4] : null, 1, frames, 0);
        selectTrack(m, 1);
        m.write(6, 255);
        m.write(7, 1);
        for (int i = 0; i < 10; i++) m.nextSample();
        assertEquals(10000, m.mixL, 50);
        assertEquals(-10000, m.mixR, 50);
        m.write(6, 128);
        m.nextSample();
        assertEquals(5020, m.mixL, 80);
    }

    @Test void nonRepeatingTrackStopsAtTheEnd() throws Exception {
        Msu1 m = chip(new byte[4], 1, new int[][]{{1000, 1000}, {1000, 1000}, {1000, 1000}}, 0);
        selectTrack(m, 1);
        m.write(7, 1);
        for (int i = 0; i < 20; i++) m.nextSample();
        assertEquals(0, m.read(0) & 0x10);
        assertEquals(0, m.mixL);
    }

    @Test void repeatingTrackLoopsAtTheLoopPoint() throws Exception {
        int[][] frames = new int[200][];
        for (int i = 0; i < 200; i++) frames[i] = new int[]{i < 100 ? 0 : 5000, 0};
        Msu1 m = chip(new byte[4], 1, frames, 100);
        selectTrack(m, 1);
        m.write(7, 3);
        int maxAfterEnd = 0;
        for (int i = 0; i < 400; i++) { m.nextSample(); if (i > 250) maxAfterEnd = Math.max(maxAfterEnd, m.mixL); }
        assertNotEquals(0, m.read(0) & 0x10);   // still playing
        assertTrue(maxAfterEnd >= 4900);        // looped into the loud second half
    }
}
