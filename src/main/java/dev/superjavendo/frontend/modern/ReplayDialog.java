package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.movie.Movie;
import dev.superjavendo.movie.Movies;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Modal list of the recordings of one game: replay, rename or delete (delete goes to our trash folder and can be undone). */
final class ReplayDialog {
    private static final int ROW = 58;

    private final Shell shell;
    private final Game game;
    private final int crc;
    private final JDialog dialog;
    private final RowList rows = new RowList();
    private final Ui.Btn playBtn, renameBtn, deleteBtn, editBtn;

    ReplayDialog(Shell shell, Game game) {
        this.shell = shell;
        this.game = game;
        this.crc = Integer.parseUnsignedInt(game.crc32(), 16);
        dialog = new JDialog(SwingUtilities.getWindowAncestor(shell), t("replay.title", game.title()), Dialog.ModalityType.APPLICATION_MODAL);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Pal.MAIN);
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        JLabel head = Widgets.label(game.title(), Pal.title(20), Pal.TXT);
        root.add(head, BorderLayout.NORTH);

        JScrollPane sp = Widgets.scroll(rows, Pal.MAIN, true);
        sp.setPreferredSize(new Dimension(560, ROW * 6 + 4));
        root.add(sp, BorderLayout.CENTER);

        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        foot.setOpaque(false);
        playBtn = new Ui.Btn(t("replay.play"), Pal.PLAY, Pal.PLAY_TXT, null).onClick(this::play);
        renameBtn = new Ui.Btn(t("replay.rename"), Pal.CARD, Pal.TXT, Pal.CARD_LINE).onClick(this::rename);
        deleteBtn = new Ui.Btn(t("replay.delete"), new Color(0x4a2630), Pal.ERR, null).onClick(this::delete);
        editBtn = new Ui.Btn(t("replay.edit"), Pal.CARD, Pal.TXT, Pal.CARD_LINE).onClick(this::edit);
        Ui.Btn close = new Ui.Btn(t("replay.close"), Pal.CARD, Pal.TXT, Pal.CARD_LINE).onClick(dialog::dispose);
        for (Ui.Btn b : List.of(deleteBtn, renameBtn, close, editBtn, playBtn)) foot.add(b);
        root.add(foot, BorderLayout.SOUTH);
        dialog.setContentPane(root);

        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        bind(im, am, KeyEvent.VK_ESCAPE, 0, "close", dialog::dispose);
        bind(im, am, KeyEvent.VK_ENTER, 0, "play", this::play);
        bind(im, am, KeyEvent.VK_F2, 0, "rename", this::rename);
        bind(im, am, KeyEvent.VK_DELETE, 0, "delete", this::delete);
        bind(im, am, KeyEvent.VK_UP, 0, "up", () -> rows.move(-1));
        bind(im, am, KeyEvent.VK_DOWN, 0, "down", () -> rows.move(1));
        bind(im, am, KeyEvent.VK_Z, Shell.shortcutMask(), "undo", () -> { shell.undoLast(); reload(); });
        reload();
        dialog.pack();
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(shell));
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowOpened(WindowEvent e) { rows.requestFocusInWindow(); } });
    }

    private static void bind(InputMap im, ActionMap am, int key, int mods, String name, Runnable r) {
        im.put(KeyStroke.getKeyStroke(key, mods), name);
        am.put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { r.run(); } });
    }

    void show() { dialog.setVisible(true); }

    private void reload() {
        rows.set(shell.movies().list(crc));
        boolean any = rows.selected() != null;
        playBtn.setEnabled(any); renameBtn.setEnabled(any); deleteBtn.setEnabled(any); editBtn.setEnabled(any);
    }

    private void play() {
        Movies.Entry e = rows.selected();
        if (e == null) return;
        dialog.dispose();
        shell.replay(game, e.file());
    }

    /** Opens the recording in the Rec editor: it is brought to its end at full speed, ready to be corrected. */
    private void edit() {
        Movies.Entry e = rows.selected();
        if (e == null) return;
        try {
            Movie m = Movie.load(e.file());
            dialog.dispose();
            shell.openRecEditor(game, m);
        } catch (IOException ex) { fail(ex); }
    }

    private void rename() {
        Movies.Entry e = rows.selected();
        if (e == null) return;
        Object in = JOptionPane.showInputDialog(dialog, t("replay.renameq"), t("replay.rename"), JOptionPane.PLAIN_MESSAGE, null, null, e.info().label());
        if (in == null) return;
        String now = in.toString().strip(), was = e.info().label();
        Path file = e.file();
        try {
            shell.movies().rename(file, now);
            shell.undo().push(t("undo.rename", e.displayName()), () -> setLabel(file, was), () -> setLabel(file, now));
        } catch (IOException ex) { fail(ex); }
        reload();
        rows.select(file);
    }

    private void setLabel(Path file, String label) {
        try { shell.movies().rename(file, label); } catch (IOException ex) { fail(ex); }
    }

    private void delete() {
        Movies.Entry e = rows.selected();
        if (e == null) return;
        Path file = e.file(), bin = shell.paths().trashDir().resolve("movies").resolve(file.getFileName());
        try {
            Files.createDirectories(bin.getParent());
            Files.move(file, bin, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            shell.undo().push(t("undo.moviedel", e.displayName()), () -> move(bin, file), () -> move(file, bin));
            shell.say(t("replay.deleted", e.displayName()));
        } catch (IOException ex) { fail(ex); }
        reload();
    }

    private void move(Path from, Path to) {
        try { Files.createDirectories(to.getParent()); Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (IOException ex) { fail(ex); }
    }

    private void fail(IOException ex) {
        JOptionPane.showMessageDialog(dialog, ex.getMessage(), t("error"), JOptionPane.ERROR_MESSAGE);
    }

    /** m:ss.cc */
    static String duration(double seconds) {
        int m = (int) (seconds / 60);
        return String.format("%d:%05.2f", m, seconds - m * 60);
    }

    /** The rows: name, date/verification on the left, length on the right. */
    private final class RowList extends JComponent {
        private List<Movies.Entry> items = List.of();
        private int sel = -1, hover = -1;

        RowList() {
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    int i = e.getY() / ROW;
                    if (i >= 0 && i < items.size()) { sel = i; repaint(); if (e.getClickCount() == 2) play(); }
                }
                @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) { int i = e.getY() / ROW; hover = i < items.size() ? i : -1; repaint(); }
            });
        }

        void set(List<Movies.Entry> l) {
            items = l;
            sel = l.isEmpty() ? -1 : Math.min(Math.max(sel, 0), l.size() - 1);
            revalidate(); repaint();
        }

        Movies.Entry selected() { return sel >= 0 && sel < items.size() ? items.get(sel) : null; }

        void select(Path file) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).file().equals(file)) sel = i;
            repaint();
        }

        void move(int d) {
            if (items.isEmpty()) return;
            sel = Math.max(0, Math.min(items.size() - 1, sel + d));
            scrollRectToVisible(new Rectangle(0, sel * ROW, 1, ROW));
            repaint();
        }

        @Override public Dimension getPreferredSize() { return new Dimension(540, Math.max(ROW * 2, items.size() * ROW)); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            g2.setColor(Pal.MAIN);
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (items.isEmpty()) {
                Ui.text(g2, t("replay.empty"), 8, 34, Pal.ui(14, 0), Pal.TXT3);
                g2.dispose();
                return;
            }
            for (int i = 0; i < items.size(); i++) {
                Movies.Entry e = items.get(i);
                int y = i * ROW, w = getWidth();
                Ui.fillRound(g2, 0, y + 3, w - 2, ROW - 6, 10, i == sel ? Pal.ROW_SEL : i == hover ? Pal.CARD : null, i == sel ? Pal.ACCENT_L : null);
                FontMetrics nm = g2.getFontMetrics(Pal.ui(15, 2));
                String d = duration(e.info().seconds());
                FontMetrics dm = g2.getFontMetrics(Pal.title(20));
                Ui.text(g2, d, w - 16 - dm.stringWidth(d), y + 36, Pal.title(20), Pal.TXT);
                Ui.text(g2, Ui.ellipsize(nm, e.displayName(), w - 48 - dm.stringWidth(d)), 16, y + 25, Pal.ui(15, 2), Pal.TXT);
                int v = e.info().verified();
                String st = v == Movie.VERIFIED ? "✓ " + t("replay.verified") : v == Movie.DESYNC ? "✗ " + t("replay.desync") : t("replay.unverified");
                Color c = v == Movie.VERIFIED ? Pal.OK : v == Movie.DESYNC ? Pal.ERR : Pal.TXT3;
                String when = e.info().label().isEmpty() ? "" : "  ·  " + java.time.Instant.ofEpochMilli(e.info().createdAt()).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
                int sw = Ui.text(g2, st, 16, y + 44, Pal.ui(12, 1), c);
                if (!when.isEmpty()) Ui.text(g2, when, 16 + sw, y + 44, Pal.ui(12, 0), Pal.TXT3);
            }
            g2.dispose();
        }
    }
}
