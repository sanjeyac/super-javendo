package dev.superjavendo.frontend.modern;

import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.config.AppPaths;
import dev.superjavendo.config.Settings;
import dev.superjavendo.movie.Movies;
import dev.superjavendo.movie.rec.InputTimeline;
import dev.superjavendo.movie.rec.RecSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RecPanelTest {
    @TempDir Path dir;

    private static Cartridge rom() {
        int[] code = {0xA9, 0x81, 0x8D, 0x00, 0x42, 0xAD, 0x19, 0x42, 0x18, 0x65, 0x10, 0x85, 0x10, 0x4C, 0x05, 0x80};
        byte[] rom = new byte[0x8000];
        for (int i = 0; i < code.length; i++) rom[i] = (byte) code[i];
        java.util.Arrays.fill(rom, 0x7FC0, 0x7FC0 + 21, (byte) ' ');
        rom[0x7FD5] = 0x20; rom[0x7FDC] = (byte) 0xFF; rom[0x7FDD] = (byte) 0xFF;
        rom[0x7FFD] = (byte) 0x80; rom[0x7FFB] = (byte) 0x81; rom[0x7FFF] = (byte) 0x82;
        rom[0x100] = 0x40; rom[0x200] = 0x40;
        return new Cartridge(rom);
    }

    private RecPanel panel(Runnable onClose) {
        AppPaths paths = new AppPaths(dir);
        RecSession s = new RecSession(rom(), new InputTimeline(), 1L << 28);
        return new RecPanel(s, Settings.load(paths), new Movies(dir.resolve("movies")), paths, "Test", "Run", "", 0, false, onClose);
    }

    private static void press(RecPanel p, int key, int mods) {
        Object name = p.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(key, mods));
        assertNotNull(name, "no binding for key " + key);
        p.getActionMap().get(name).actionPerformed(new ActionEvent(p, 0, ""));
    }

    private static void waitFor(java.util.function.BooleanSupplier c) throws Exception {
        for (int i = 0; i < 200 && !c.getAsBoolean(); i++) Thread.sleep(50);
        assertTrue(c.getAsBoolean());
    }

    @Test void keysDriveTheEngineAndThePanelPaintsEveryState() throws Exception {
        RecPanel p = panel(() -> { });
        try {
            press(p, KeyEvent.VK_PERIOD, 0);
            press(p, KeyEvent.VK_PERIOD, 0);
            press(p, KeyEvent.VK_PERIOD, 0);
            waitFor(() -> p.view().frontier() == 3);
            assertEquals(3, p.view().playhead());
            press(p, KeyEvent.VK_COMMA, 0);
            waitFor(() -> p.view().playhead() == 2);
            p.toggle(0, 1, 2);                                  // an edit in the emulated past
            waitFor(() -> p.view().pending() > 0);
            assertEquals(1, p.view().firstInvalid());
            BufferedImage img = p.render(1400, 900);
            assertEquals(1400, img.getWidth());
            assertTrue(distinctColours(img) > 20, "the panel paints something");
            press(p, KeyEvent.VK_R, 0);                         // re-emulate
            waitFor(() -> p.view().pending() == 0 && !p.status().busy());
            assertEquals(3, p.view().frontier());
            press(p, KeyEvent.VK_Z, Shell.shortcutMask());      // undo the edit
            waitFor(() -> p.view().pending() > 0);
            p.render(1100, 700);                                // the minimum size also paints
            p.render(1200, 800);                                // single-column cards
        } finally { p.shutdown(); }
    }

    @Test void padButtonsToggleTheSameSnesButtonAtTheCursorFrame() throws Exception {
        RecPanel p = panel(() -> { });
        try {
            p.padInput("b");
            p.padInput("right");
            waitFor(() -> p.view().inputs().mask(0, 0) != 0);
            int m = p.view().inputs().mask(0, 0);
            assertEquals(dev.superjavendo.input.Joypad.B | dev.superjavendo.input.Joypad.RIGHT, m);
            p.padInput("b");                                        // pressing again releases it
            waitFor(() -> p.view().inputs().mask(0, 0) == dev.superjavendo.input.Joypad.RIGHT);
            press(p, KeyEvent.VK_P, 0);                             // the other pad
            p.padInput("a");
            waitFor(() -> p.view().inputs().mask(1, 0) == dev.superjavendo.input.Joypad.A);
            press(p, KeyEvent.VK_PERIOD, 0);                        // advancing moves the cursor to the next frame to play
            waitFor(() -> p.view().frontier() == 1);
            waitFor(() -> p.roll.cursorFrame() == 1);
            p.padInput("x");
            waitFor(() -> p.view().inputs().mask(1, 1) == dev.superjavendo.input.Joypad.X);
        } finally { p.shutdown(); }
    }

    @Test void closingAnUnchangedRunDoesNotAsk() throws Exception {
        boolean[] closed = {false};
        RecPanel p = panel(() -> closed[0] = true);
        p.requestClose();
        assertTrue(closed[0]);
    }

    @Test void policyChoiceIsRememberedInTheSettings() throws Exception {
        RecPanel p = panel(() -> { });
        try {
            p.policy(RecSession.Policy.CUT);
            assertEquals("cut", Settings.load(new AppPaths(dir)).recPolicy);
        } finally { p.shutdown(); }
    }

    private static int distinctColours(BufferedImage img) {
        java.util.Set<Integer> s = new java.util.HashSet<>();
        for (int y = 0; y < img.getHeight(); y += 7) for (int x = 0; x < img.getWidth(); x += 7) s.add(img.getRGB(x, y));
        return s.size();
    }
}
