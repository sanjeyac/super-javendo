package dev.superjavendo.frontend2;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.frontend.ControllerConfigWindow;
import dev.superjavendo.frontend.GameEntry;
import dev.superjavendo.frontend.GameList;
import dev.superjavendo.ui.EmuWindow;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The v2 application: top bar with the screen tabs, the screens themselves (Library, ROM Manager, Controller, Settings)
 * and the bookkeeping of the game that is running in its own window.
 */
public final class Shell extends JPanel {
    private final AppPaths paths;
    private final Map<Integer, String> profiles;
    private final GameList gameList;
    private final UserData user;
    private final Meta crcs;
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
        gameList = new GameList(paths.gameListFile());
        user = new UserData(paths.userDataFile());
        crcs = new Meta(paths.configDir().resolve("crc.cache"));
        covers = new Covers(paths);
        model = new LibraryModel(user, g -> covers.exists(g.path()));
        setBackground(Pal.MAIN);

        gameList.load();
        user.load();
        crcs.load();
        model.setGames(buildGames(MetaDb.load(paths.metadataFile()), false));
        library = new LibraryScreen(this, model, covers, user);

        screens.setBackground(Pal.MAIN);
        screens.add(library, "library");
        screens.add(placeholder("ROM Manager", "Cartelle, duplicati, copertine e metadati: in arrivo (milestone M2).", null), "rom");
        screens.add(placeholder("Controller", "Il nuovo editor dei profili è in arrivo (milestone M3). Intanto si può usare quello attuale.",
                new Ui.Btn("Apri il configuratore attuale", Pal.ACCENT, Color.WHITE, null).onClick(() -> new ControllerConfigWindow().setVisible(true))), "controller");
        screens.add(placeholder("Impostazioni", "Video, audio e percorsi: in arrivo (milestone M4).", null), "settings");
        add(topBar(), BorderLayout.NORTH);
        add(screens, BorderLayout.CENTER);
        showTab("library");
        updateRunning();
        updatePads();
        computeCrcsAsync();
    }

    // ------------------------------------------------------------------------------------------ data

    private List<Game> buildGames(MetaDb db, boolean withCrc) {
        List<Game> out = new ArrayList<>();
        for (GameEntry e : gameList.games()) {
            String crc = crcs.cached(e.path());
            long size = 0;
            try { size = Files.size(e.path()); } catch (Exception ignored) { }
            out.add(Game.of(e, db.get(crc), crc, size));
        }
        return out;
    }

    /** Computes the missing CRC32s in the background, then rebuilds the game list with the metadata they unlock. */
    private void computeCrcsAsync() {
        Thread t = new Thread(() -> {
            boolean any = false;
            for (GameEntry e : gameList.games()) if (crcs.cached(e.path()) == null) { crcs.compute(e.path()); any = true; }
            if (!any) return;
            crcs.save();
            MetaDb db = MetaDb.load(paths.metadataFile());
            List<Game> games = buildGames(db, true);
            SwingUtilities.invokeLater(() -> { model.setGames(games); library.refresh(); });
        }, "crc32");
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
        addTab(left, "library", "Libreria");
        addTab(left, "rom", "ROM Manager");
        addTab(left, "controller", "Controller");
        addTab(left, "settings", "Impostazioni");
        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 12));
        right.setOpaque(false);
        runningPill.setOpaque(false);
        runningPill.setBorder(new EmptyBorder(0, 6, 0, 6));
        runningPill.add(runningLabel);
        Ui.Btn front = new Ui.Btn("Porta in primo piano", new Color(0x1d3a2b), Pal.RUN, null).onClick(() -> { if (running != null) { running.toFront(); running.requestFocus(); } });
        front.setPreferredSize(new Dimension(170, 36));
        Ui.Btn close = new Ui.Btn("Chiudi", new Color(0x4a2630), Pal.ERR, null).onClick(this::closeRunning);
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

    void showTab(String id) {
        cards.show(screens, id);
        for (var e : tabs.entrySet()) {
            boolean on = e.getKey().equals(id);
            e.getValue().setColors(on ? Pal.ACTIVE : null, on ? Pal.TXT : Pal.TXT2, null);
        }
        if (id.equals("library")) library.focusCatalogue();
    }

    private JComponent placeholder(String title, String text, JComponent action) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Pal.MAIN);
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JLabel t = Widgets.label(title, Pal.title(28), Pal.TXT);
        JLabel d = Widgets.label(text, Pal.ui(14, 0), Pal.TXT2);
        t.setAlignmentX(CENTER_ALIGNMENT);
        d.setAlignmentX(CENTER_ALIGNMENT);
        box.add(t);
        box.add(Box.createVerticalStrut(10));
        box.add(d);
        if (action != null) { box.add(Box.createVerticalStrut(20)); action.setAlignmentX(CENTER_ALIGNMENT); box.add(action); }
        p.add(box);
        return p;
    }

    /** "P1 keyboard + easysmx · P2 keyboard2 + xbox", read from controllers.cfg. */
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

    void play(Game g) {
        if (g == null) return;
        if (isRunning(g)) { running.toFront(); return; }
        closeRunning();
        try {
            EmuWindow w = GameLauncher2.open(g.path(), profiles);
            running = w;
            runningGame = g;
            runningSince = System.currentTimeMillis();
            long since = runningSince;
            w.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) {
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
            JOptionPane.showMessageDialog(this, "Impossibile avviare " + g.title() + ":\n" + ex.getMessage(), "Errore", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void closeRunning() {
        if (running != null && running.isDisplayable()) running.dispose();
    }

    private void updateRunning() {
        boolean on = running != null && running.isDisplayable() && runningGame != null;
        runningPill.setVisible(on);
        runningLabel.setText(on ? "●  In esecuzione: " + runningGame.title() : "");
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
        f.setMinimumSize(new Dimension(1100, 640));
        f.setLocationRelativeTo(null);
        return f;
    }

    /** For screenshots: switches screen and lays everything out at the given size without a window. */
    public java.awt.image.BufferedImage render(int w, int h, String tab, boolean listView, String query) throws Exception {
        library.preset(listView, query);
        showTab(tab);
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
