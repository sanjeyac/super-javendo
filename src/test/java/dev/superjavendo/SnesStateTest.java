package dev.superjavendo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SnesStateTest {
    private static Snes counter() {
        SnesTest.Asm a = new SnesTest.Asm().st(0x80, 0x4200).loop();
        return SnesTest.snes(a, new int[]{0xE6, 0x10, 0x40}, null, null, null); // NMI: INC $10
    }

    @Test void loadRestoresEverythingAndReplayIsIdentical() throws Exception {
        Snes s = counter();
        for (int i = 0; i < 5; i++) s.runFrame();
        byte[] state = s.saveState();

        for (int i = 0; i < 3; i++) s.runFrame();
        assertEquals(8, s.bus.read(0x10));
        long cyclesAfter = s.cpu.cycles;
        int[] frameAfter = s.bus.ppu.frame.clone();

        s.loadState(state);
        assertEquals(5, s.bus.read(0x10));
        assertEquals(5, s.frameCount);

        for (int i = 0; i < 3; i++) s.runFrame();
        assertEquals(8, s.bus.read(0x10));
        assertEquals(cyclesAfter, s.cpu.cycles);
        assertArrayEquals(frameAfter, s.bus.ppu.frame);
    }

    @Test void stateCanBeLoadedIntoAFreshConsole() throws Exception {
        Snes a = counter();
        for (int i = 0; i < 4; i++) a.runFrame();
        byte[] state = a.saveState();
        Snes b = counter();
        b.loadState(state);
        assertEquals(4, b.bus.read(0x10));
        a.runFrame(); b.runFrame();
        assertEquals(a.cpu.cycles, b.cpu.cycles);
        assertEquals(a.bus.read(0x10), b.bus.read(0x10));
    }

    @Test void garbageIsRejectedAndConsoleKeepsRunning() throws Exception {
        Snes s = counter();
        for (int i = 0; i < 3; i++) s.runFrame();
        long cycles = s.cpu.cycles;
        assertThrows(IOException.class, () -> s.loadState(new byte[]{1, 2, 3, 4, 5}));
        assertEquals(cycles, s.cpu.cycles);
        assertEquals(3, s.bus.read(0x10));
        s.runFrame();
        assertEquals(4, s.bus.read(0x10));
    }

    @Test void stateFromAnotherGameIsRejected() throws Exception {
        Snes s = counter();
        s.runFrame();
        byte[] state = s.saveState();
        Snes other = SnesTest.snes(new SnesTest.Asm().st(0x01, 0x4200).loop(), null, null,
                new int[]{9, 9, 9}, null); // different ROM contents
        IOException e = assertThrows(IOException.class, () -> other.loadState(state));
        assertTrue(e.getMessage().contains("another game"), e.getMessage());
    }

    @Test void headerCarriesTitleTimeAndScreenshot() throws Exception {
        Snes s = counter();
        s.runFrame();
        long before = System.currentTimeMillis();
        var info = Snes.readInfo(s.saveState());
        assertTrue(info.savedAtMillis >= before && info.savedAtMillis <= System.currentTimeMillis());
        assertTrue(info.hasScreenshot());
        var img = info.screenshot();
        assertEquals(256, img.getWidth());
        assertEquals(224, img.getHeight());
        assertTrue(info.gameTitle != null);
    }

    @Test void screenshotMatchesTheFrameAtSaveTime() throws Exception {
        Snes s = counter();
        s.runFrame();
        s.bus.ppu.frame[5] = 0xFF123456;              // marker pixel in the framebuffer
        var img = Snes.readInfo(s.saveState()).screenshot();
        assertEquals(0x123456, img.getRGB(5, 0) & 0xFFFFFF);
    }

    @Test void saveStateIsReasonablySmall() throws Exception {
        Snes s = counter();
        s.runFrame();
        assertTrue(s.saveState().length < 600_000, "size=" + s.saveState().length);
    }
}
