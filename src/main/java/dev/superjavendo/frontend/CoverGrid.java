package dev.superjavendo.frontend;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Catalogue of game covers laid out as a grid inside a scroll pane. Only the visible cells are painted; their
 * covers are loaded and scaled on a background thread (a placeholder is shown meanwhile). A game without cover
 * is drawn as a black rectangle with its title in the middle.
 */
final class CoverGrid extends JComponent implements Scrollable {
    static final int CELL_W = 210, CELL_H = 164, PAD = 10;   // art box 190x144 ≈ 1.32; covers are ~1.4:1 (landscape box art)
    private static final int ART_W = CELL_W - 2 * PAD, ART_H = CELL_H - 2 * PAD;
    private static final int CACHE_SIZE = 300;

    /** A scaled cover; {@code image == null} means "looked for, none exists". */
    private record Thumb(BufferedImage image) { }

    private final Function<GameEntry, BufferedImage> loader;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cover-loader");
        t.setDaemon(true);
        return t;
    });
    private final Map<GameEntry, Thumb> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<GameEntry, Thumb> e) { return size() > CACHE_SIZE; }
    };
    private final Set<GameEntry> pending = new HashSet<>();
    private List<GameEntry> games = List.of();
    private int selected = -1;
    private Runnable onActivate = () -> { };
    private Consumer<GameEntry> onSelect = g -> { };

    CoverGrid(Function<GameEntry, BufferedImage> loader) {
        this.loader = loader;
        setFocusable(true);
        setBackground(Theme.PANEL);
        setOpaque(true);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int i = indexAt(e.getX(), e.getY());
                if (i >= 0) select(i);
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && indexAt(e.getX(), e.getY()) >= 0) onActivate.run();
            }
        });
        key("LEFT", () -> move(-1));
        key("RIGHT", () -> move(1));
        key("UP", () -> move(-columns()));
        key("DOWN", () -> move(columns()));
        key("PAGE_UP", () -> move(-columns() * Math.max(1, visibleRows() - 1)));
        key("PAGE_DOWN", () -> move(columns() * Math.max(1, visibleRows() - 1)));
        key("HOME", () -> select(0));
        key("END", () -> select(games.size() - 1));
        key("ENTER", () -> { if (selected >= 0) onActivate.run(); });
    }

    private void key(String stroke, Runnable r) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke);
        getActionMap().put(stroke, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { r.run(); } });
    }

    void setOnActivate(Runnable r) { onActivate = r; }
    void setOnSelect(Consumer<GameEntry> c) { onSelect = c; }

    /** Replaces the games; the selection stays on {@code keep} when it is still present, else on the first game. */
    void setGames(List<GameEntry> list, GameEntry keep) {
        games = List.copyOf(list);
        int i = keep == null ? -1 : games.indexOf(keep);
        selected = games.isEmpty() ? -1 : Math.max(i, 0);
        revalidate();
        repaint();
        fireSelect();
    }

    GameEntry selected() { return selected >= 0 && selected < games.size() ? games.get(selected) : null; }

    int selectedIndex() { return selected; }

    // ---- geometry (pure, unit-testable) ----

    /** Number of columns that fit in {@code width} pixels (at least 1). */
    static int columnsFor(int width) { return Math.max(1, width / CELL_W); }

    int columns() { return columnsFor(getWidth()); }

    private int visibleRows() {
        Container p = getParent();
        return p instanceof JViewport vp ? Math.max(1, vp.getHeight() / CELL_H) : 1;
    }

    int rows() { return (games.size() + columns() - 1) / columns(); }

    /** Index of the game whose cell contains the point, or -1. */
    int indexAt(int x, int y) {
        int cols = columns();
        if (x < 0 || y < 0 || x >= cols * CELL_W) return -1;
        int i = (y / CELL_H) * cols + x / CELL_W;
        return i < games.size() ? i : -1;
    }

    private Rectangle cellRect(int i) {
        int cols = columns();
        return new Rectangle((i % cols) * CELL_W, (i / cols) * CELL_H, CELL_W, CELL_H);
    }

    /** Moves the selection by {@code delta} cells, clamped to the list. */
    void move(int delta) {
        if (games.isEmpty()) return;
        select(Math.max(0, Math.min(games.size() - 1, (selected < 0 ? 0 : selected) + delta)));
    }

    void select(int i) {
        if (i < 0 || i >= games.size()) return;
        selected = i;
        scrollRectToVisible(cellRect(i));
        repaint();
        fireSelect();
    }

    private void fireSelect() { onSelect.accept(selected()); }

    // ---- painting ----

    @Override public Dimension getPreferredSize() {
        int w = getParent() instanceof JViewport vp ? vp.getWidth() : 5 * CELL_W;
        int cols = columnsFor(w);
        return new Dimension(cols * CELL_W, Math.max(1, (games.size() + cols - 1) / cols) * CELL_H);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        Rectangle clip = g.getClipBounds() != null ? g.getClipBounds() : new Rectangle(getSize());
        int cols = columns();
        int firstRow = Math.max(0, clip.y / CELL_H), lastRow = (clip.y + clip.height) / CELL_H;
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                if (i >= games.size()) break;
                paintCell(g, i, col * CELL_W, row * CELL_H);
            }
        }
        g.dispose();
    }

    private void paintCell(Graphics2D g, int i, int x, int y) {
        GameEntry game = games.get(i);
        Thumb t = thumbFor(game);
        Rectangle art;
        if (t != null && t.image() != null) {
            BufferedImage img = t.image();
            art = new Rectangle(x + (CELL_W - img.getWidth()) / 2, y + (CELL_H - img.getHeight()) / 2, img.getWidth(), img.getHeight());
            g.drawImage(img, art.x, art.y, null);
        } else {
            art = new Rectangle(x + PAD, y + PAD, ART_W, ART_H);
            g.setColor(Color.BLACK);
            g.fillRect(art.x, art.y, art.width, art.height);
            g.setColor(Theme.BODY_DARK);
            g.drawRect(art.x, art.y, art.width - 1, art.height - 1);
            drawCentered(g, game.title(), art);
        }
        if (i == selected) {
            g.setColor(Theme.RED);
            g.setStroke(new BasicStroke(3f));
            g.drawRect(art.x - 3, art.y - 3, art.width + 5, art.height + 5);
        }
    }

    /** Wraps the title on word boundaries and centres the lines in {@code box}. */
    private static void drawCentered(Graphics2D g, String title, Rectangle box) {
        g.setFont(Theme.TEXT.deriveFont(Font.BOLD, 14f));
        g.setColor(Color.WHITE);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(title, fm, box.width - 16);
        int lineH = fm.getHeight();
        int top = box.y + (box.height - lines.size() * lineH) / 2 + fm.getAscent();
        for (int k = 0; k < lines.size(); k++) {
            String s = lines.get(k);
            g.drawString(s, box.x + (box.width - fm.stringWidth(s)) / 2, top + k * lineH);
        }
    }

    static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = cur.length() == 0 ? word : cur + " " + word;
            if (cur.length() > 0 && fm.stringWidth(candidate) > maxWidth) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(candidate);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    // ---- cover loading ----

    /** The cached thumbnail, or null while it is still being loaded (a load is queued the first time). */
    private Thumb thumbFor(GameEntry game) {
        Thumb t = cache.get(game);
        if (t != null || !pending.add(game)) return t;
        worker.execute(() -> {
            BufferedImage src = null;
            try { src = loader.apply(game); } catch (Exception ignored) { }
            Thumb result = new Thumb(src == null ? null : scaleToBox(src));
            SwingUtilities.invokeLater(() -> {
                pending.remove(game);
                cache.put(game, result);
                repaint();
            });
        });
        return null;
    }

    /** Scales {@code src} to fit the cover box keeping the aspect ratio, halving step by step to keep it sharp. */
    static BufferedImage scaleToBox(BufferedImage src) {
        double s = Math.min((double) ART_W / src.getWidth(), (double) ART_H / src.getHeight());
        int tw = Math.max(1, (int) Math.round(src.getWidth() * s)), th = Math.max(1, (int) Math.round(src.getHeight() * s));
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        return resize(cur, tw, th);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ---- Scrollable ----

    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(5 * CELL_W, 3 * CELL_H); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return CELL_H / 4; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(CELL_H, r.height - CELL_H); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
