package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.config.AppPaths;
import dev.superjavendo.config.Settings;
import dev.superjavendo.movie.Movie;
import dev.superjavendo.movie.Movies;
import dev.superjavendo.movie.rec.InputTimeline;
import dev.superjavendo.movie.rec.RecSession;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** The Rec editor window: opens a game (new run, or an existing movie to correct) in a {@link RecPanel}. */
final class RecWindow {
    private RecWindow() { }

    /** Opens the editor on a ROM. {@code existing} is a movie to edit, or null for a new run. */
    static JFrame open(Path rom, Movie existing, AppPaths paths) throws Exception {
        Cartridge cart = Cartridge.load(rom);
        Settings settings = Settings.load(paths);
        String title = Game.cleanTitle(rom.getFileName().toString().replaceFirst("\\.[^.]*$", ""));
        RecPanel[] panel = new RecPanel[1];
        JFrame f = new JFrame(t("rec.window", title));
        f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        panel[0] = create(cart, settings, paths, title, existing, f::dispose, true);
        f.setContentPane(panel[0]);
        f.setMinimumSize(new Dimension(1100, 700));
        f.setSize(1400, 900);
        f.setLocationRelativeTo(null);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { panel[0].requestClose(); }
        });
        f.setVisible(true);
        return f;
    }

    private static RecPanel create(Cartridge cart, Settings settings, AppPaths paths, String title, Movie existing, Runnable onClose, boolean pad) {
        InputTimeline timeline = existing == null ? new InputTimeline() : InputTimeline.of(existing);
        RecSession session = new RecSession(cart, timeline, settings.recGreenzoneMb * 1024L * 1024L);
        String label = existing == null ? "" : existing.label;
        String when = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        String run = !label.isEmpty() ? label : t("rec.run", when);
        return new RecPanel(session, settings, new Movies(paths.moviesDir()), paths, title, run, label, existing == null ? 0 : existing.length(), pad, onClose);
    }

    /**
     * Headless screenshot for checking the layout: {@code frames} frames emulated, then (optionally) an edit in the past
     * so the stale part and the banner are visible.
     */
    static BufferedImage shot(Path rom, Movie existing, AppPaths paths, int w, int h, int frames, boolean edit) throws Exception {
        Cartridge cart = Cartridge.load(rom);
        Settings settings = Settings.load(paths);
        settings.recPolicy = "keep";
        RecPanel p = create(cart, settings, paths, Game.cleanTitle(rom.getFileName().toString().replaceFirst("\\.[^.]*$", "")), existing, () -> { }, false);
        p.demo(frames, edit);
        BufferedImage[] out = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> out[0] = p.render(w, h));
        p.shutdown();
        return out[0];
    }
}
