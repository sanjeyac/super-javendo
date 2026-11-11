package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.ui.EmuWindow;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The v2 application: top bar with the screen tabs, the screens themselves (Library, ROM Manager, Controller, Settings)
 * and the bookkeeping of the game that is running in its own window.
 */
public final class Shell extends JPanel {
    private final AppPaths paths;
    private final Map<Integer, String> profiles;
    private final UserData user;
    private final LibraryData data;
    private final RomManagerScreen romManager;
    private final ControllerScreen controller;
    private final Covers covers;
    private final LibraryModel model;
    private final LibraryScreen library;
    private final CardLayout cards = new CardLayout();
    private final JPanel screens = new JPanel(cards);
    private final Map<String, Ui.Btn> tabs = new java.util.LinkedHashMap<>();
    private final JPanel runningPill = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JLabel runningLabel = Widgets.label("", Pal.ui(14, 1), Pal.TXT);
    private final JLabel padStatus = Widgets.label("", Pal.ui(13, 0), Pal.TXT2);

    private EmuWindow running;
    private Game runningGame;
    private long runningSince;

    public Shell(AppPaths paths, Map<Integer, String> profiles) {
        super(new BorderLayout());
        this.paths = paths;
        this.profiles = profiles;
        user = new UserData(paths.userDataFile());
        covers = new Covers(paths);
        model = new LibraryModel(user, g -> covers.exists(g.path()));
        setBackground(Pal.MAIN);

        user.load();
        data = new LibraryData(paths, covers);
        model.setNoIntro(data::noIntroStatus);
        model.setGames(data.all(), data.overrides);
        library = new LibraryScreen(this, model, covers, user);
        romManager = new RomManagerScreen(this, data, covers);
        controller = new ControllerScreen(this, paths);

        screens.setBackground(Pal.MAIN);
        screens.add(library, "library");
        screens.add(romManager, "rom");
        screens.add(controller, "controller");
        screens.add(new SettingsScreen(this, paths, this::rebuildForLanguage), "settings");
        add(topBar(), BorderLayout.NORTH);
        add(screens, BorderLayout.CENTER);
        showTab("library");
        updateRunning();
        updatePads();
        computeCrcsAsync();
        new PadNav(paths, () -> !controller.listening() && screens.isShowing()
                && (running == null || !running.isDisplayable() || !running.isActive()), this::padAction);
    }

    private void padAction(String b) {
        if (library.isShowing()) library.padAction(b);
    }

    // ------------------------------------------------------------------------------------------ data

    /** Recomputes games and catalogue from the current data and redraws both screens (event thread). */
    void dataChanged() {
        data.rebuild();
        model.setGames(data.all(), data.overrides);
        library.refresh();
        romManager.refresh();
    }

    /** Computes the missing CRC32s in the background, then rebuilds with the metadata they unlock. */
    private void computeCrcsAsync() {
        Thread t = new Thread(() -> {
            if (data.computeCrcs()) SwingUtilities.invokeLater(this::dataChanged);
        }, "crc32");
        t.setDaemon(true);
        t.start();
    }

    /** Scans the source folders in the background, then refreshes; {@code done} gets the number of new games. */
    void rescanAsync(java.util.function.IntConsumer done) {
        Thread t = new Thread(() -> {
            int added = data.rescan();
            SwingUtilities.invokeLater(() -> { dataChanged(); done.accept(added); });
        }, "scan");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------------------------------ top bar

    private JComponent topBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Pal.BAR);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Pal.SEP);
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            }
        };
        bar.setPreferredSize(new Dimension(0, 64));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 12));
        left.setOpaque(false);
        JLabel logo = Widgets.label("SUPER JAVENDO", Pal.title(21), Pal.TXT);
        logo.setBorder(new EmptyBorder(4, 12, 0, 22));
        left.add(logo);
        addTab(left, "library", t("tab.library"));
        addTab(left, "rom", t("tab.rom"));
        addTab(left, "controller", t("tab.controller"));
        addTab(left, "settings", t("tab.settings"));
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 12));
        right.setOpaque(false);
        runningPill.setOpaque(false);
        runningPill.setBorder(new EmptyBorder(0, 6, 0, 6));
        runningPill.add(runningLabel);
        Ui.Btn front = new Ui.Btn(t("shell.front"), new Color(0x1d3a2b), Pal.RUN, null).onClick(() -> { if (running != null) { running.toFront(); running.requestFocus(); } });
        front.setPreferredSize(new Dimension(170, 36));
        Ui.Btn close = new Ui.Btn(t("shell.close"), new Color(0x4a2630), Pal.ERR, null).onClick(this::closeRunning);
        close.setPreferredSize(new Dimension(80, 36));
        runningPill.add(front);
        runningPill.add(close);
        right.add(runningPill);
        right.add(padStatus);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void addTab(JPanel to, String id, String text) {
        Ui.Btn b = new Ui.Btn(text, null, Pal.TXT2, null).onClick(() -> showTab(id)).font(Pal.ui(15, 1));
        b.setPreferredSize(new Dimension(b.getPreferredSize().width + 8, 40));
        tabs.put(id, b);
        to.add(b);
    }

    /** Opens the ROM Manager on one game's file. */
    void showRomManager(Game g) {
        showTab("rom");
        romManager.select(g.path());
    }

    void showTab(String id) {
        cards.show(screens, id);
        controller.setActive(id.equals("controller"));
        for (var e : tabs.entrySet()) {
            boolean on = e.getKey().equals(id);
            e.getValue().setColors(on ? Pal.ACTIVE : null, on ? Pal.TXT : Pal.TXT2, null);
        }
        if (id.equals("library")) library.focusCatalogue();
    }

    /** "P1 keyboard + easysmx · P2 keyboard2 + xbox", read from controllers.cfg. */
    /** The language changed: replaces this screen by a new one in the same window (a running game keeps running, unlinked). */
    private void rebuildForLanguage() {
        Window w = SwingUtilities.getWindowAncestor(this);
        controller.setActive(false);
        if (!(w instanceof JFrame f)) return;
        Shell fresh = new Shell(paths, profiles);
        f.setContentPane(fresh);
        fresh.showTab("settings");
        f.revalidate();
        f.repaint();
    }

    void refreshPads() { updatePads(); }

    private void updatePads() {
        StringBuilder sb = new StringBuilder();
        try {
            for (String line : Files.readAllLines(paths.controllersFile())) {
                line = line.trim();
                if (line.startsWith("player") && line.contains("=")) {
                    String[] kv = line.split("=", 2);
                    if (sb.length() > 0) sb.append("  ·  ");
                    sb.append('P').append(kv[0].substring(6)).append(' ').append(kv[1].replaceAll("@\\d+", "").replace(",", " + "));
                }
            }
        } catch (Exception ignored) { }
        padStatus.setText(sb.toString());
    }

    // ------------------------------------------------------------------------------------------ running game

    boolean isRunning(Game g) { return runningGame != null && running != null && running.isDisplayable() && runningGame.path().equals(g.path()); }

    void play(Game g) { play(g, -1); }

    /** Starts the game, loading the given save-state slot right away (-1 = from power-on). A game already running just loads it. */
    void play(Game g, int resumeSlot) {
        if (g == null) return;
        if (isRunning(g)) { running.toFront(); if (resumeSlot >= 0) running.requestLoadSlot(resumeSlot); return; }
        closeRunning();
        try {
            EmuWindow w = GameLauncher2.open(g.path(), profiles, resumeSlot);
            running = w;
            runningGame = g;
            runningSince = System.currentTimeMillis();
            long since = runningSince;
            w.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) {
                    if (w.isSwitching()) return;                // re-created to enter or leave full screen
                    SwingUtilities.invokeLater(() -> {
                        if (running == w) { running = null; runningGame = null; }
                        user.recordSession(g.path(), since, System.currentTimeMillis());
                        updateRunning();
                        library.refresh();
                    });
                }
            });
            updateRunning();
            library.refresh();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, t("shell.launchfail", g.title()) + "\n" + ex.getMessage(), t("error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void closeRunning() {
        if (running != null && running.isDisplayable()) running.dispose();
    }

    private void updateRunning() {
        boolean on = running != null && running.isDisplayable() && runningGame != null;
        runningPill.setVisible(on);
        runningLabel.setText(on ? "●  " + t("shell.running", runningGame.title()) : "");
        runningLabel.setForeground(Pal.RUN);
        runningPill.revalidate();
        repaint();
    }

    /** Wraps the panel in a window. */
    public JFrame window() {
        JFrame f = new JFrame("Super Javendo");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setContentPane(this);
        f.setSize(1440, 900);
        f.setMinimumSize(new Dimension(1280, 700));
        f.setLocationRelativeTo(null);
        return f;
    }

    /** For screenshots: switches screen and lays everything out at the given size without a window. */
    public java.awt.image.BufferedImage render(int w, int h, String tab, boolean listView, String query) throws Exception {
        library.preset(listView, query);
        showTab(tab);
        if (tab.equals("rom") && !query.isBlank())            // screenshots: open the ROM Manager on the first file matching the query
            for (Game g : data.all()) if (g.fileName().toLowerCase().contains(query.toLowerCase())) { showRomManager(g); break; }
        setSize(w, h);
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> layoutAll(this));
            Thread.sleep(700);                                   // lets covers and slots load
            java.awt.image.BufferedImage warm = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(() -> { Graphics2D g = warm.createGraphics(); printAll(g); g.dispose(); });
        }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        SwingUtilities.invokeAndWait(() -> { layoutAll(this); Graphics2D g = img.createGraphics(); printAll(g); g.dispose(); });
        return img;
    }

    private static void layoutAll(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container cc) layoutAll(cc);
    }
}
