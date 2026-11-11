package dev.superjavendo.cart;

import dev.superjavendo.Snes;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the self-checking test master cartridge ({@code tools/testmaster}); see its result table layout in framework.py. */
class TestMasterTest {
    @Test void dsp1MasterPassesEveryGroup() throws Exception {
        byte[] rom;
        try (InputStream in = getClass().getResourceAsStream("/testmaster/test_master_dsp1.sfc")) { rom = in.readAllBytes(); }
        Snes snes = new Snes(new Cartridge(rom));
        for (int f = 0; f < 1500 && snes.bus.read(0x7E200A) != 0xA5; f++) snes.runFrame();
        assertEquals(0xA5, snes.bus.read(0x7E200A), "the run did not finish");
        int groups = snes.bus.read(0x7E2004);
        StringBuilder failed = new StringBuilder();
        for (int i = 0; i < groups; i++) if (snes.bus.read(0x7E2400 + i) != 0) failed.append(' ').append(i);
        assertEquals("", failed.toString(), "failed groups (index):");
        assertEquals(groups, snes.bus.read(0x7E2008) | snes.bus.read(0x7E2009) << 8);
    }
}
