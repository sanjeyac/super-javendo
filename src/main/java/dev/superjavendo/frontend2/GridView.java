package dev.superjavendo.frontend2;

import static dev.superjavendo.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Cover grid: columns of at least 168 px, 4:3 covers, title and "year · genre · region" underneath. */
final class GridView extends JComponent implements Scrollable {
    private static final int MIN_W = 168, GAP = 24, PAD = 8, TEXT_H = 48, ROW_GAP = 18;

    private List<Game> games = List.of();
    private int selected = -1;
    private final Covers covers;
    private final Predicate<Game> running, favorite;
    private Consumer<Game> onSelect = g -> { }, onActivate = g -> { }, onStar = g -> { };

    GridView(Covers covers, Predicate<Game> running, Predicate<Game> favorite) {
        this.covers = covers;
        this.running = running;
        this.favorite = favorite;
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int i = indexAt(e.getX(), e.getY());
                if (i < 0) return;
                Rectangle c = coverRect(i);
                if (new Rectangle(c.x + c.width - 38, c.y + 8, 30, 30).contains(e.getPoint())) { onStar.accept(games.get(i)); repaint(); return; }
                select(i);
                if (e.getClickCount() == 2) onActivate.accept(games.get(i));
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (games.isEmpty()) return;
                int cols = cols(), i = Math.max(selected, 0);
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_RIGHT -> select(Math.min(games.size() - 1, i + 1));
                    case KeyEvent.VK_LEFT -> select(Math.max(0, i - 1));
                    case KeyEvent.VK_DOWN -> select(Math.min(games.size() - 1, i + cols));
                    case KeyEvent.VK_UP -> select(Math.max(0, i - cols));
                    case KeyEvent.VK_HOME -> select(0);
                    case KeyEvent.VK_END -> select(games.size() - 1);
                    case KeyEvent.VK_ENTER -> { if (selected >= 0) onActivate.accept(games.get(selected)); }
                    default -> { }
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        addComponentListener(new ComponentAdapter() { @Override public void componentResized(ComponentEvent e) { revalidate(); } });
    }

    void onSelect(Consumer<Game> c) { onSelect = c; }
    void onActivate(Consumer<Game> c) { onActivate = c; }
    void onStar(Consumer<Game> c) { onStar = c; }

    void setGames(List<Game> list, Game keep) {
        games = list;
        selected = -1;
        if (keep != null) for (int i = 0; i < list.size(); i++) if (list.get(i).path().equals(keep.path())) selected = i;
        if (selected < 0 && !list.isEmpty()) selected = 0;
        revalidate();
        repaint();
        onSelect.accept(selected >= 0 ? games.get(selected) : null);
    }

    Game selectedGame() { return selected >= 0 && selected < games.size() ? games.get(selected) : null; }

    private void select(int i) {
        selected = i;
        Rectangle r = rowRect(i);
        scrollRectToVisible(new Rectangle(r.x, r.y - 8, r.width, r.height + 16));
        onSelect.accept(games.get(i));
        repaint();
    }

    private int cols() { return Math.max(1, (getWidth() - 2 * PAD + GAP) / (MIN_W + GAP)); }

    private int cellW() { int c = cols(); return (getWidth() - 2 * PAD - (c - 1) * GAP) / c; }

    private int coverH() { return cellW() * 3 / 4; }

    private int rowH() { return coverH() + TEXT_H + ROW_GAP; }

    private Rectangle rowRect(int i) { int cols = cols(); return new Rectangle(PAD + (i % cols) * (cellW() + GAP), PAD + (i / cols) * rowH(), cellW(), rowH()); }

    private Rectangle coverRect(int i) { Rectangle r = rowRect(i); return new Rectangle(r.x, r.y, cellW(), coverH()); }

    private int indexAt(int x, int y) {
        for (int i = 0; i < games.size(); i++) if (rowRect(i).contains(x, y) && y - rowRect(i).y < coverH() + TEXT_H) return i;
        return -1;
    }

    @Override public Dimension getPreferredSize() {
        int w = Math.max(getWidth(), MIN_W + 2 * PAD);
        int cols = Math.max(1, (w - 2 * PAD + GAP) / (MIN_W + GAP));
        int cw = (w - 2 * PAD - (cols - 1) * GAP) / cols;
        int rows = (games.size() + cols - 1) / cols;
        return new Dimension(w, PAD * 2 + rows * (cw * 3 / 4 + TEXT_H + ROW_GAP));
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.g2(g);
        g2.setColor(Pal.MAIN);
        g2.fillRect(0, 0, getWidth(), getHeight());
        Rectangle clip = g.getClipBounds() == null ? new Rectangle(0, 0, getWidth(), getHeight()) : g.getClipBounds();
        for (int i = 0; i < games.size(); i++) {
            Rectangle r = rowRect(i);
            if (!r.intersects(clip)) continue;
            paintCell(g2, games.get(i), r, i == selected);
        }
        g2.dispose();
    }

    private void paintCell(Graphics2D g, Game game, Rectangle r, boolean sel) {
        int w = r.width, h = coverH();
        BufferedImage cover = covers.get(game.path());
        if (cover != null) {
            Ui.coverFit(g, cover, r.x, r.y, w, h, 12);
        } else if (covers.exists(game.path())) {                       // present but still loading
            Ui.fillRound(g, r.x, r.y, w, h, 12, Pal.CARD, null);
        } else {
            Ui.fillRound(g, r.x, r.y, w, h, 12, Pal.SIDE, null);
            Ui.dashedRound(g, r.x, r.y, w, h, 12, Pal.CARD_LINE);
            Ui.pictureIcon(g, r.x + w / 2, r.y + h / 2 - 26, Pal.TXT3);
            FontMetrics fm = g.getFontMetrics(Pal.ui(14, 2));
            String t = Ui.ellipsize(fm, game.title(), w - 24);
            Ui.text(g, t, r.x + (w - fm.stringWidth(t)) / 2, r.y + h / 2 + 10, Pal.ui(14, 2), Pal.TXT);
            FontMetrics f2 = g.getFontMetrics(Pal.ui(12, 0));
            String m = t("grid.nocover");
            Ui.text(g, m, r.x + (w - f2.stringWidth(m)) / 2, r.y + h / 2 + 30, Pal.ui(12, 0), Pal.TXT3);
        }
        // favourite star
        int sx = r.x + w - 38, sy = r.y + 8;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillOval(sx, sy, 30, 30);
        Ui.star(g, sx + 15, sy + 15.5, 8.5, favorite.test(game), favorite.test(game) ? Pal.STAR : Pal.TXT);
        // badges
        Font bf = Pal.ui(11, 2);
        FontMetrics bm = g.getFontMetrics(bf);
        if (running.test(game)) {
            String s = t("running.badge");
            int bw = bm.stringWidth(s) + 16;
            Ui.fillRound(g, r.x + 8, r.y + h - 30, bw, 22, 8, Pal.PLAY, null);
            Ui.text(g, s, r.x + 16, r.y + h - 15, bf, Pal.PLAY_TXT);
        }
        if (game.versions() > 1) {
            String s = t("grid.versions", game.versions());
            int bw = bm.stringWidth(s) + 16;
            Ui.fillRound(g, r.x + w - bw - 8, r.y + h - 30, bw, 22, 8, new Color(0x12151c), null);
            Ui.text(g, s, r.x + w - bw, r.y + h - 15, bf, Pal.TXT);
        }
        if (sel) {
            g.setColor(isFocusOwner() ? Pal.ACCENT_L : Pal.TXT3);
            g.setStroke(new BasicStroke(3f));
            g.draw(new java.awt.geom.RoundRectangle2D.Float(r.x - 4.5f, r.y - 4.5f, w + 9, h + 9, 16, 16));
            g.setStroke(new BasicStroke(1f));
        }
        FontMetrics fm = g.getFontMetrics(Pal.ui(15, 2));
        Ui.text(g, Ui.ellipsize(fm, game.title(), w), r.x, r.y + h + 22, Pal.ui(15, 2), Pal.TXT);
        FontMetrics f2 = g.getFontMetrics(Pal.ui(12, 0));
        Ui.text(g, Ui.ellipsize(f2, game.subtitle(), w), r.x, r.y + h + 41, Pal.ui(12, 0), Pal.TXT3);
    }

    // Scrollable
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 40; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
