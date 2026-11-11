package dev.superjavendo.frontend.legacy;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.ui.EmuWindow;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.Map;

/** Game library: grid of covers (black title card when a game has none), Start, Reset, folder scan, controller setup. */
public final class LauncherWindow extends JFrame {
    private final AppPaths paths = AppPaths.forCurrentUser();
    private final GameList library = new GameList(paths.gameListFile());
    private final CoverGrid grid = new CoverGrid(g -> Previews.coverOf(g.path(), paths));
    private final JLabel status = new JLabel(" ");
    private final Map<Integer, String> profiles;
    private EmuWindow current;

    public LauncherWindow(Map<Integer, String> profiles) {
        super("SuperJavendo");
        this.profiles = profiles;
        getContentPane().setBackground(Theme.BG);
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel("SUPER JAVENDO");
        title.setFont(Theme.TITLE);
        title.setForeground(Theme.INK);
        title.setOpaque(true);
        title.setBackground(Theme.PURPLE);
        title.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        add(title, BorderLayout.NORTH);

        grid.setOnActivate(this::start);
        grid.setOnSelect(g -> { if (g != null) status.setText(g.title()); });
        JScrollPane sp = new JScrollPane(grid);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BODY_DARK));
        sp.getViewport().setBackground(Theme.PANEL);
        sp.getVerticalScrollBar().setUnitIncrement(CoverGrid.CELL_H / 4);
        add(sp, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(4, 6));
        south.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(button("START", Theme.GREEN, this::start));
        buttons.add(button("RESET", Theme.RED, this::reset));
        buttons.add(button("Scansiona cartella…", Theme.PURPLE, this::scan));
        buttons.add(button("Controller…", Theme.BLUE, () -> new ControllerConfigWindow().setVisible(true)));
        south.add(buttons, BorderLayout.CENTER);
        status.setForeground(Theme.INK);
        south.add(status, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        library.load();
        refresh(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        setMinimumSize(new Dimension(CoverGrid.CELL_W * 2 + 60, 440));
        setLocationRelativeTo(null);
        grid.requestFocusInWindow();
        if (library.games().isEmpty()) status.setText("Nessun gioco: usa «Scansiona cartella…» per aggiungere una cartella di ROM.");
    }

    private JButton button(String text, Color color, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(Theme.TEXT.deriveFont(Font.BOLD));
        b.setBackground(color);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void refresh(GameEntry keep) {
        grid.setGames(library.games(), keep);
    }

    private void start() {
        GameEntry g = grid.selected();
        if (g == null) { status.setText("Seleziona un gioco."); return; }
        if (current != null && current.isDisplayable()) current.dispose();
        try {
            current = GameLauncher.open(g.path(), profiles, false);
            status.setText("In esecuzione: " + g.title());
        } catch (Exception ex) {
            status.setText("Impossibile avviare " + g.title() + ": " + ex.getMessage());
            if (dev.superjavendo.cart.RomFile.isZip(g.path()))
                JOptionPane.showMessageDialog(this, "Il file zip non contiene nessuna ROM SNES e non può essere usato:\n" + g.path().getFileName(),
                        "Zip senza ROM", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void reset() {
        if (current != null && current.isDisplayable()) { current.requestReset(); status.setText("Reset inviato."); }
        else status.setText("Nessun gioco in esecuzione.");
    }

    private void scan() {
        JFileChooser fc = new JFileChooser(Path.of(System.getProperty("user.home"), "Emu", "snes").toFile());
        fc.setDialogTitle("Cartella con le ROM");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path dir = fc.getSelectedFile().toPath();
        status.setText("Scansione di " + dir + "…");
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                int added = library.scan(dir);
                library.save();
                return added;
            }
            @Override protected void done() {
                try {
                    int n = get();
                    refresh(grid.selected());
                    status.setText("Trovati " + n + " nuovi giochi (" + library.games().size() + " in lista).");
                    warnRejectedZips();
                } catch (Exception e) {
                    status.setText("Scansione fallita: " + e.getMessage());
                }
            }
        }.execute();
    }

    /** Tells the user about zip files that hold no ROM and were therefore left out. */
    private void warnRejectedZips() {
        var bad = library.rejectedZips();
        if (bad.isEmpty()) return;
        StringBuilder sb = new StringBuilder(bad.size() == 1 ? "Questo file zip non contiene nessuna ROM SNES e non può essere usato:\n\n"
                : "Questi file zip non contengono nessuna ROM SNES e non possono essere usati:\n\n");
        int shown = 0;
        for (Path p : bad) {
            if (shown++ == 15) { sb.append("… e altri ").append(bad.size() - 15).append('\n'); break; }
            sb.append(" • ").append(p.getFileName()).append('\n');
        }
        JOptionPane.showMessageDialog(this, sb.toString(), "Zip senza ROM", JOptionPane.WARNING_MESSAGE);
    }
}
