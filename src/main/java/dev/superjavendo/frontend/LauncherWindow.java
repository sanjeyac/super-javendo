package dev.superjavendo.frontend;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.ui.EmuWindow;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;

/** Game library: list of scanned ROMs, preview of the last save / cover, Start, Reset, folder scan, controller setup. */
public final class LauncherWindow extends JFrame {
    private final AppPaths paths = AppPaths.forCurrentUser();
    private final GameList library = new GameList(paths.gameListFile());
    private final DefaultListModel<GameEntry> model = new DefaultListModel<>();
    private final JList<GameEntry> list = new JList<>(model);
    private final PreviewPanel preview = new PreviewPanel();
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

        list.setFont(Theme.TEXT);
        list.setBackground(Theme.PANEL);
        list.setForeground(Theme.INK);
        list.setSelectionBackground(Theme.RED);
        list.setSelectionForeground(Color.WHITE);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) updatePreview(); });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) start(); }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "start");
        list.getActionMap().put("start", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { start(); } });
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(300, 360));
        sp.setBorder(BorderFactory.createLineBorder(Theme.BODY_DARK));

        JSplitPane center = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sp, preview);
        center.setBorder(null);
        center.setOpaque(false);
        center.setDividerLocation(300);
        add(center, BorderLayout.CENTER);

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
        setMinimumSize(new Dimension(720, 440));
        setLocationRelativeTo(null);
        if (model.isEmpty()) status.setText("Nessun gioco: usa «Scansiona cartella…» per aggiungere una cartella di ROM.");
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
        model.clear();
        for (GameEntry g : library.games()) model.addElement(g);
        if (keep != null) list.setSelectedValue(keep, true);
        else if (!model.isEmpty()) list.setSelectedIndex(0);
        updatePreview();
    }

    private void updatePreview() {
        GameEntry g = list.getSelectedValue();
        preview.set(g == null ? null : g.title(), g == null ? null : Previews.of(g.path(), paths));
    }

    private void start() {
        GameEntry g = list.getSelectedValue();
        if (g == null) { status.setText("Seleziona un gioco."); return; }
        if (current != null && current.isDisplayable()) current.dispose();
        try {
            current = GameLauncher.open(g.path(), profiles, false);
            status.setText("In esecuzione: " + g.title());
            current.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) { updatePreview(); } // new save shows up
            });
        } catch (Exception ex) {
            status.setText("Impossibile avviare " + g.title() + ": " + ex.getMessage());
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
                    refresh(list.getSelectedValue());
                    status.setText("Trovati " + n + " nuovi giochi (" + model.size() + " in lista).");
                } catch (Exception e) {
                    status.setText("Scansione fallita: " + e.getMessage());
                }
            }
        }.execute();
    }

    /** Scaled preview image with caption. */
    private static final class PreviewPanel extends JPanel {
        private BufferedImage image;
        private String title = "", caption = "";

        PreviewPanel() { setBackground(Theme.PANEL); setPreferredSize(new Dimension(400, 360)); }

        void set(String title, Previews.Preview p) {
            this.title = title == null ? "" : title;
            image = p == null ? null : p.image();
            caption = p == null ? "" : p.caption();
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight() - 60;
            if (image != null) {
                double s = Math.min((w - 20.0) / image.getWidth(), (h - 20.0) / image.getHeight());
                int iw = (int) (image.getWidth() * s), ih = (int) (image.getHeight() * s);
                g.drawImage(image, (w - iw) / 2, 10 + (h - 20 - ih) / 2, iw, ih, null);
            } else {
                g.setColor(Theme.BODY_DARK);
                g.drawRoundRect(10, 10, w - 20, h - 20, 16, 16);
            }
            g.setColor(Theme.INK);
            g.setFont(Theme.TITLE);
            g.drawString(title, 14, getHeight() - 32);
            g.setFont(Theme.TEXT);
            g.setColor(Theme.BODY_DARK);
            g.drawString(caption, 14, getHeight() - 12);
        }
    }
}
