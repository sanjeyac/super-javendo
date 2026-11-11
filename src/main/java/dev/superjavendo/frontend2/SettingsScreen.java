package dev.superjavendo.frontend2;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.config.Settings;
import dev.superjavendo.i18n.I18n;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.superjavendo.i18n.I18n.t;

/** Language, video, audio and folders. Every change is applied and saved at once; the language rebuilds the window. */
final class SettingsScreen extends JPanel {
    private static final int WIDTH = 760;

    private final Shell shell;
    private final AppPaths paths;
    private final Settings settings;
    private final Runnable onLanguage;

    SettingsScreen(Shell shell, AppPaths paths, Runnable onLanguage) {
        super(new BorderLayout());
        this.shell = shell;
        this.paths = paths;
        this.settings = Settings.load(paths);
        this.onLanguage = onLanguage;
        setBackground(Pal.MAIN);

        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(new EmptyBorder(28, 0, 28, 0));
        col.add(heading(t("set.title")));
        col.add(Box.createVerticalStrut(18));

        col.add(card(t("set.language"), t("set.languagehint"), languageRow()));
        col.add(Box.createVerticalStrut(16));
        col.add(card(t("set.video"), null, videoRows()));
        col.add(Box.createVerticalStrut(16));
        col.add(card(t("set.audio"), t("set.audiohint"), audioRow()));
        col.add(Box.createVerticalStrut(16));
        col.add(card(t("set.folders"), null, folderRows()));

        JPanel wrap = new JPanel(new GridBagLayout());      // centres the column
        wrap.setBackground(Pal.MAIN);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTH;
        c.weighty = 1;
        col.setPreferredSize(new Dimension(WIDTH, col.getPreferredSize().height));
        wrap.add(col, c);
        add(Widgets.scroll(wrap, Pal.MAIN), BorderLayout.CENTER);
    }

    private static JComponent heading(String s) {
        JLabel l = Widgets.label(s, Pal.title(28), Pal.TXT);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    /** A rounded card with a title, an optional explanation and the content. */
    private static JComponent card(String title, String hint, JComponent content) {
        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(new EmptyBorder(18, 22, 20, 22));
        JLabel t = Widgets.label(title, Pal.ui(16, 2), Pal.TXT);
        t.setAlignmentX(LEFT_ALIGNMENT);
        inner.add(t);
        if (hint != null) {
            inner.add(Box.createVerticalStrut(4));
            JLabel h = Widgets.label("<html><body style='width:660px'>" + hint + "</body></html>", Pal.ui(13, 0), Pal.TXT3);
            h.setAlignmentX(LEFT_ALIGNMENT);
            inner.add(h);
        }
        inner.add(Box.createVerticalStrut(14));
        content.setAlignmentX(LEFT_ALIGNMENT);
        inner.add(content);
        JPanel card = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Ui.g2(g);
                Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 20, Pal.CARD, Pal.CARD_LINE);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.add(inner);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height + 400));
        return card;
    }

    /** A row of mutually exclusive chips. */
    private static JComponent chips(List<String> labels, int selected, int columns, java.util.function.IntConsumer pick) {
        int rows = (labels.size() + columns - 1) / columns;
        JPanel p = new JPanel(new GridLayout(0, columns, 8, 8));
        p.setPreferredSize(new Dimension(100, rows * 40 + (rows - 1) * 8));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * 40 + (rows - 1) * 8));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setOpaque(false);
        List<Ui.Btn> btns = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            boolean on = i == selected;
            Ui.Btn b = new Ui.Btn(labels.get(i), on ? Pal.ACCENT : Pal.SIDE, on ? Color.WHITE : Pal.TXT, on ? null : Pal.CARD_LINE).font(Pal.ui(14, 1));
            b.onClick(() -> {
                for (int k = 0; k < btns.size(); k++) btns.get(k).setColors(k == idx ? Pal.ACCENT : Pal.SIDE, k == idx ? Color.WHITE : Pal.TXT, k == idx ? null : Pal.CARD_LINE);
                pick.accept(idx);
            });
            btns.add(b);
            p.add(b);
        }
        for (int i = labels.size(); i % columns != 0; i++) p.add(new JLabel());
        return p;
    }

    private JComponent languageRow() {
        List<String> names = new ArrayList<>();
        names.add(t("set.auto"));
        for (I18n.Language l : I18n.LANGUAGES) names.add(l.name());
        int sel = 0;
        for (int i = 0; i < I18n.LANGUAGES.size(); i++) if (I18n.LANGUAGES.get(i).code().equals(settings.language)) sel = i + 1;
        return chips(names, sel, 5, i -> {
            settings.language = i == 0 ? "" : I18n.LANGUAGES.get(i - 1).code();
            settings.save();
            I18n.set(settings.language);
            SwingUtilities.invokeLater(onLanguage);
        });
    }

    private JComponent videoRows() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(rowLabel(t("set.filter")));
        List<String> filters = Settings.FILTERS;
        p.add(chips(filters.stream().map(f -> t("game.filter." + f)).toList(), filters.indexOf(settings.filter), 3, i -> { settings.filter = filters.get(i); settings.save(); }));
        p.add(Box.createVerticalStrut(12));
        p.add(rowLabel(t("set.scale")));
        List<String> scales = new ArrayList<>();
        for (int s = Settings.MIN_SCALE; s <= Settings.MAX_SCALE; s++) scales.add("×" + s + " · " + 256 * s + "×" + 224 * s);
        p.add(chips(scales, settings.scale - Settings.MIN_SCALE, 6, i -> { settings.scale = Settings.MIN_SCALE + i; settings.save(); }));
        JLabel hint = Widgets.label(t("set.gamehint"), Pal.ui(12, 0), Pal.TXT3);
        hint.setBorder(new EmptyBorder(8, 0, 0, 0));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        p.add(hint);
        return p;
    }

    private static JLabel rowLabel(String s) {
        JLabel l = Widgets.label(s, Pal.ui(13, 1), Pal.TXT2);
        l.setBorder(new EmptyBorder(0, 0, 6, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private JComponent audioRow() {
        JPanel p = new JPanel(new BorderLayout(16, 0));
        p.setOpaque(false);
        JLabel value = Widgets.label(settings.volume + "%", Pal.mono(15), Pal.TXT);
        value.setPreferredSize(new Dimension(56, 30));
        Slider slider = new Slider(settings.volume, v -> { settings.volume = v; value.setText(v + "%"); }, () -> settings.save());
        p.add(Widgets.label(t("set.volume"), Pal.ui(14, 1), Pal.TXT2), BorderLayout.WEST);
        p.add(slider, BorderLayout.CENTER);
        p.add(value, BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return p;
    }

    private JComponent folderRows() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(folderRow(t("set.config"), paths.configDir(), null));
        p.add(folderRow(t("set.states"), paths.statesDir(), null));
        p.add(folderRow(t("set.covers"), paths.coversDir(), null));
        p.add(folderRow(t("set.romfolders"), null, () -> shell.showTab("rom")));
        return p;
    }

    private JComponent folderRow(String name, Path dir, Runnable custom) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel n = Widgets.label(name, Pal.ui(14, 1), Pal.TXT);
        n.setAlignmentX(LEFT_ALIGNMENT);
        text.add(n);
        if (dir != null) {
            JLabel d = Widgets.label(dir.toString(), Pal.mono(12), Pal.TXT3);
            d.setAlignmentX(LEFT_ALIGNMENT);
            text.add(d);
        }
        row.add(text, BorderLayout.CENTER);
        Ui.Btn b = new Ui.Btn(dir == null ? t("set.manage") : t("set.open"), Pal.SIDE, Pal.TXT, Pal.CARD_LINE).font(Pal.ui(13, 1));
        b.setPreferredSize(new Dimension(Math.max(120, b.getPreferredSize().width), 38));
        b.onClick(custom != null ? custom : () -> open(dir));
        row.add(b, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        return row;
    }

    private void open(Path dir) {
        try {
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(dir.toFile());
            else JOptionPane.showMessageDialog(this, dir.toString(), t("set.folders"), JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(this, dir + "\n" + e.getMessage(), t("error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /** A dark slider, 0..100; {@code done} fires when the mouse is released. */
    private static final class Slider extends JComponent {
        private int value;
        private final java.util.function.IntConsumer onChange;
        private final Runnable done;

        Slider(int value, java.util.function.IntConsumer onChange, Runnable done) {
            this.value = value; this.onChange = onChange; this.done = done;
            setPreferredSize(new Dimension(300, 30));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { set(e.getX()); }
                @Override public void mouseDragged(MouseEvent e) { set(e.getX()); }
                @Override public void mouseReleased(MouseEvent e) { done.run(); }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private void set(int x) {
            value = Math.max(0, Math.min(100, Math.round((x - 10) * 100f / Math.max(1, getWidth() - 20))));
            onChange.accept(value);
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            int w = getWidth() - 20, cy = getHeight() / 2, kx = 10 + w * value / 100;
            Ui.fillRound(g2, 10, cy - 3, w, 6, 6, Pal.CARD_LINE, null);
            Ui.fillRound(g2, 10, cy - 3, Math.max(6, kx - 10), 6, 6, Pal.ACCENT, null);
            g2.setColor(Pal.ACCENT_L);
            g2.fillOval(kx - 9, cy - 9, 18, 18);
            g2.dispose();
        }
    }
}
