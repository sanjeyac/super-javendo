package dev.superjavendo.cart;

import dev.superjavendo.Snes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated chip test cartridges ({@code tools/chiptests}) and compares the results they leave in WRAM
 * ($7E:2000..) with the ones snes9x produced for the same ROM (frozen as {@code .golden} files).
 */
class ChipRomTest {
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = ChipRomTest.class.getResourceAsStream("/chiptests/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dsp1_multiply", "dsp1_inverse", "dsp1_triangle", "dsp1_radius", "dsp1_range", "dsp1_rotate", "dsp1_multiply2", "dsp1_distance", "dsp1_polar", "dsp1_attitudeA", "dsp1_attitudeB", "dsp1_attitudeC", "dsp1_projection", "dsp1_raster", "dsp1_gyrate", "dsp1_projwide", "dsp1_projclip"})
    void matchesSnes9x(String name) throws Exception {
        Snes snes = new Snes(new Cartridge(resource(name + ".sfc")));
        for (int f = 0; f < 30; f++) snes.runFrame();
        byte[] golden = resource(name + ".golden");
        for (int i = 0; i < golden.length; i++)
            assertEquals(golden[i] & 0xFF, snes.bus.read(0x7E2000 + i), name + " differs at +" + i);
    }
}
