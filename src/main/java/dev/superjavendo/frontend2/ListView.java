package dev.superjavendo.frontend2;

import static dev.superjavendo.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Table view of the library: favourite, title, year, genre, players, region, last played, play time. */
final class ListView extends JComponent implements Scrollable {
    private static final int ROW = 40, HEAD = 36;
    private static final String[] COLS = {"", "col.title", "col.year", "col.genre", "col.players", "col.region", "col.lastplayed", "col.time"};
    private static final int[] FIXED = {40, 0, 50, 80, 100, 80, 130, 70};

    private List<Game> games = List.of();
    private int selected = -1;
    private final Predicate<Game> favorite;
    private final Function<Game, Long> lastPlayed, playSeconds;
    private Consumer<Game> onSelect = g -> { }, onActivate = g -> { }, onStar = g -> { };

    ListView(Predicate<Game> favorite, Function<Game, Long> lastPlayed, Function<Game, Long> playSeconds) {
        this.favorite = favorite;
        this.lastPlayed = lastPlayed;
        this.playSeconds = playSeconds;
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int i = (e.getY() - HEAD) / ROW;
                if (e.getY() < HEAD || i < 0 || i >= games.size()) return;
                if (e.getX() < FIXED[0]) { onStar.accept(games.get(i)); repaint(); return; }
                select(i);
                if (e.getClickCount() == 2) onActivate.accept(games.get(i));
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (games.isEmpty()) return;
                int i = Math.max(selected, 0);
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> select(Math.min(games.size() - 1, i + 1));
                    case KeyEvent.VK_UP -> select(Math.max(0, i - 1));
                    case KeyEvent.VK_HOME -> select(0);
                    case KeyEvent.VK_END -> select(games.size() - 1);
                    case KeyEvent.VK_ENTER -> { if (selected >= 0) onActivate.accept(games.get(selected)); }
                    default -> { }
                }
            }
        });
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

    private void select(int i) {
        selected = i;
        scrollRectToVisible(new Rectangle(0, HEAD + i * ROW, 10, ROW));
        onSelect.accept(games.get(i));
        repaint();
    }

    @Override public Dimension getPreferredSize() { return new Dimension(600, HEAD + games.size() * ROW + 8); }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.g2(g);
        int w = getWidth();
        g2.setColor(Pal.MAIN);
        g2.fillRect(0, 0, w, getHeight());
        int[] x = new int[COLS.length + 1];
        int flex = w - 16;
        for (int f : FIXED) flex -= f;
        for (int c = 0; c < COLS.length; c++) x[c + 1] = x[c] + (FIXED[c] == 0 ? Math.max(160, flex) : FIXED[c]);
        for (int c = 1; c < COLS.length; c++) Ui.label(g2, t(COLS[c]), x[c] + (c == 1 ? 0 : 8), HEAD - 12);
        g2.setColor(Pal.SEP);
        g2.drawLine(0, HEAD - 1, w, HEAD - 1);
        for (int i = 0; i < games.size(); i++) {
            int y = HEAD + i * ROW;
            if (y > g.getClipBounds().y + g.getClipBounds().height || y + ROW < g.getClipBounds().y) continue;
            Game game = games.get(i);
            if (i == selected) { Ui.fillRound(g2, 0, y + 2, w - 8, ROW - 4, 12, Pal.ROW_SEL, isFocusOwner() ? Pal.ACCENT : null); }
            Ui.star(g2, 22, y + ROW / 2.0, 8, favorite.test(game), favorite.test(game) ? Pal.STAR : Pal.TXT3);
            int base = y + ROW / 2 + 5;
            FontMetrics fm = g2.getFontMetrics(Pal.ui(14, 2));
            Ui.text(g2, Ui.ellipsize(fm, game.title() + (game.versions() > 1 ? "  · " + t("grid.versions", game.versions()) : ""), x[2] - x[1] - 12), x[1], base, Pal.ui(14, 2), Pal.TXT);
            Font f = Pal.ui(13, 0);
            FontMetrics f2 = g2.getFontMetrics(f);
            Ui.text(g2, game.year() > 0 ? String.valueOf(game.year()) : "—", x[2] + 8, base, f, Pal.TXT2);
            Ui.text(g2, Ui.ellipsize(f2, game.genre().isEmpty() ? "—" : game.genre(), x[3] - x[2] - 16), x[3] + 8, base, f, Pal.TXT2);
            Ui.text(g2, Ui.players(game.players()), x[4] + 8, base, f, Pal.TXT2);
            Ui.text(g2, game.region().isEmpty() ? "—" : game.regionLabel(), x[5] + 8, base, f, Pal.TXT2);
            Ui.text(g2, Ui.lastPlayed(lastPlayed.apply(game)), x[6] + 8, base, f, Pal.TXT2);
            Ui.text(g2, Ui.playTime(playSeconds.apply(game)), x[7] + 8, base, f, Pal.TXT2);
        }
        g2.dispose();
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return ROW; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - ROW; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
