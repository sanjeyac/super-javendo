package dev.superjavendo.ppu;

import dev.superjavendo.Snes;
import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.config.AppPaths;
import dev.superjavendo.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Enhanced Mode 7" on the test cartridge made by {@code tools/mode7demo/build.py}: a perspective road drawn with a Mode 7 matrix
 * per line, sprites for a car and an energy bar, and a backdrop sky (rows 0..71).
 */
class Mode7EnhancerTest {
    private static Snes demo(int scale) throws Exception {
        try (InputStream in = Mode7EnhancerTest.class.getResourceAsStream("/mode7demo/mode7demo.sfc")) {
            Snes s = new Snes(new Cartridge(in.readAllBytes()));
            s.bus.ppu.setEnhancedMode7(scale);
            return s;
        }
    }

    private static void run(Snes s, int frames) { for (int i = 0; i < frames; i++) s.runFrame(); }

    @Test void offByDefaultAndWhenSetBelowTwo() throws Exception {
        Snes s = demo(0);
        run(s, 20);
        assertNull(s.bus.ppu.enhancedPixels());
        s.bus.ppu.setEnhancedMode7(1);
        run(s, 2);
        assertNull(s.bus.ppu.enhancedPixels());
    }

    @Test void theConsoleIsNotAffectedInAnyWay() throws Exception {
        Snes off = demo(0), two = demo(2), four = demo(4);
        for (int f = 0; f < 50; f++) {
            run(off, 1); run(two, 1); run(four, 1);
            assertEquals(off.stateHash(), two.stateHash(), "frame " + f);
            assertEquals(off.stateHash(), four.stateHash(), "frame " + f);
        }
        assertArrayEquals(off.bus.ppu.outputPixels(), two.bus.ppu.outputPixels());
        assertArrayEquals(off.bus.ppu.outputPixels(), four.bus.ppu.outputPixels());
    }

    @Test void theBiggerPictureHasTheRequestedSize() throws Exception {
        for (int scale : new int[]{2, 3, 4}) {
            Snes s = demo(scale);
            run(s, 20);
            assertNotNull(s.bus.ppu.enhancedPixels());
            assertEquals(256 * scale, s.bus.ppu.enhancedWidth());
            assertEquals(224 * scale, s.bus.ppu.enhancedHeight());
            assertEquals(256 * scale * 224 * scale, s.bus.ppu.enhancedPixels().length);
        }
    }

    @Test void everythingThatIsNotTheLayerIsTheNativePixelRepeated() throws Exception {
        Snes s = demo(2);
        run(s, 30);
        int[] nat = s.bus.ppu.outputPixels(), e = s.bus.ppu.enhancedPixels();
        int w = s.bus.ppu.enhancedWidth();
        for (int y = 0; y < 72; y++)                                     // sky and energy bar: no Mode 7 there
            for (int x = 0; x < 256; x++)
                for (int k = 0; k < 4; k++)
                    assertEquals(nat[y * 256 + x], e[(2 * y + k / 2) * w + 2 * x + k % 2], "sky " + x + "," + y);
        int sprites = 0;                                                  // the car: sprite pixels over the road stay as drawn
        for (int y = 190; y < 206; y++)
            for (int x = 120; x < 136; x++) {
                int c = nat[y * 256 + x];
                if (c == 0xFFD61829) {                                    // the car's red body
                    sprites++;
                    assertEquals(c, e[2 * y * w + 2 * x]);
                    assertEquals(c, e[(2 * y + 1) * w + 2 * x + 1]);
                }
            }
        assertTrue(sprites > 20, "the car was found");
    }

    /** Taking the average of each 2x2 block of the enhanced road gives back something close to the native road: same geometry. */
    @Test void theRoadStaysWhereTheHardwareDrewIt() throws Exception {
        Snes s = demo(2);
        run(s, 30);
        int[] nat = s.bus.ppu.outputPixels(), e = s.bus.ppu.enhancedPixels();
        int w = s.bus.ppu.enhancedWidth();
        long diff = 0, n = 0, changed = 0;
        for (int y = 150; y < 185; y++)                                   // lower road: texels bigger than pixels, no sprites
            for (int x = 8; x < 248; x++) {
                int[] avg = new int[3];
                for (int k = 0; k < 4; k++) {
                    int c = e[(2 * y + k / 2) * w + 2 * x + k % 2];
                    avg[0] += c >> 16 & 255; avg[1] += c >> 8 & 255; avg[2] += c & 255;
                    if (c != nat[y * 256 + x]) changed++;
                }
                int c = nat[y * 256 + x];
                diff += Math.abs(avg[0] / 4 - (c >> 16 & 255)) + Math.abs(avg[1] / 4 - (c >> 8 & 255)) + Math.abs(avg[2] / 4 - (c & 255));
                n += 3;
            }
        assertTrue(diff / (double) n < 25, "mean difference per channel " + diff / (double) n);
        assertTrue(changed > 1000, "the enhanced road is not just the native one repeated");
    }

    @Test void theSettingIsSavedAndBadValuesMeanOff(@TempDir Path dir) throws Exception {
        AppPaths paths = new AppPaths(dir);
        assertEquals(0, Settings.load(paths).enhancedMode7);
        Settings st = Settings.load(paths);
        st.enhancedMode7 = 4;
        st.save();
        assertEquals(4, Settings.load(paths).enhancedMode7);
        Files.writeString(paths.settingsFile(), "video.enhancedMode7=3\n");
        assertEquals(0, Settings.load(paths).enhancedMode7);             // only 0, 2 and 4 are offered
        Files.writeString(paths.settingsFile(), "video.enhancedMode7=abc\n");
        assertEquals(0, Settings.load(paths).enhancedMode7);
    }
}
