package dev.superjavendo.frontend2;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;

/** The library: filters on the left, catalogue in the middle, selected game on the right, pad legend at the bottom. */
final class LibraryScreen extends JPanel {
    private final LibraryModel model;
    private final Covers covers;
    private final Shell shell;

    private final JPanel left = new JPanel();
    private final Widgets.Search search = new Widgets.Search("Cerca titolo, editore…");
    private final Ui.Btn sortBtn = new Ui.Btn("", Pal.CARD, Pal.TXT, Pal.CARD_LINE).font(Pal.ui(14, 1)).chevron();
    private final Ui.Btn gridBtn = new Ui.Btn("", Pal.ACTIVE, Pal.TXT, null).icon(Ui::gridIcon);
    private final Ui.Btn listBtn = new Ui.Btn("", Pal.CARD, Pal.TXT2, Pal.CARD_LINE).icon(Ui::listIcon);
    private final JLabel heading = Widgets.label("", Pal.title(22), Pal.TXT);
    private final JLabel countLabel = Widgets.label("", Pal.ui(13, 0), Pal.TXT3);
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final GridView grid;
    private final ListView list;
    private final DetailPanel detail;
    private boolean listMode;
    private Game current;

    LibraryScreen(Shell shell, LibraryModel model, Covers covers, UserData user) {
        super(new BorderLayout());
        this.shell = shell;
        this.model = model;
        this.covers = covers;
        grid = new GridView(covers, shell::isRunning, model::favorite);
        list = new ListView(model::favorite, model::lastPlayed, model::playSeconds);
        detail = new DetailPanel(covers, model, shell::isRunning);
        setBackground(Pal.MAIN);

        // ---- left column
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBackground(Pal.SIDE);
        left.setBorder(new EmptyBorder(24, 20, 20, 20));
        JScrollPane leftScroll = Widgets.scroll(left, Pal.SIDE);
        leftScroll.setPreferredSize(new Dimension(252, 0));
        add(wrapEdge(leftScroll, true), BorderLayout.WEST);

        // ---- centre
        JPanel centre = new JPanel(new BorderLayout(0, 8));
        centre.setBackground(Pal.MAIN);
        centre.setBorder(new EmptyBorder(24, 28, 0, 20));
        JPanel tools = new JPanel(new BorderLayout(12, 0));
        tools.setOpaque(false);
        tools.add(search, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(Widgets.label("Ordina", Pal.ui(13, 0), Pal.TXT2));
        sortBtn.setPreferredSize(new Dimension(170, 44));
        right.add(sortBtn);
        gridBtn.setPreferredSize(new Dimension(44, 44));
        listBtn.setPreferredSize(new Dimension(44, 44));
        right.add(gridBtn);
        right.add(listBtn);
        tools.add(right, BorderLayout.EAST);
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        head.setOpaque(false);
        head.add(heading);
        head.add(countLabel);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(tools, BorderLayout.NORTH);
        top.add(head, BorderLayout.SOUTH);
        centre.add(top, BorderLayout.NORTH);
        content.setBackground(Pal.MAIN);
        content.add(Widgets.scroll(grid, Pal.MAIN), "grid");
        content.add(Widgets.scroll(list, Pal.MAIN), "list");
        content.add(emptyView(), "empty");
        centre.add(content, BorderLayout.CENTER);
        add(centre, BorderLayout.CENTER);

        // ---- right column
        JScrollPane rs = Widgets.scroll(detail, Pal.SIDE);
        rs.setPreferredSize(new Dimension(348, 0));
        add(wrapEdge(rs, false), BorderLayout.EAST);

        add(commandBar(), BorderLayout.SOUTH);

        // ---- wiring
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void ch() { model.query = search.getText(); refresh(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { ch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { ch(); }
        });
        sortBtn.onClick(() -> {
            JPopupMenu m = new JPopupMenu();
            m.setBackground(Pal.CARD);
            m.setBorder(BorderFactory.createLineBorder(Pal.CARD_LINE));
            for (LibraryModel.Sort s : LibraryModel.Sort.values()) {
                JMenuItem it = new JMenuItem(s.label);
                it.setFont(Pal.ui(14, 0));
                it.setBackground(Pal.CARD);
                it.setForeground(Pal.TXT);
                it.addActionListener(e -> { model.sort = s; refresh(); });
                m.add(it);
            }
            m.show(sortBtn, 0, sortBtn.getHeight() + 4);
        });
        gridBtn.onClick(() -> setListMode(false));
        listBtn.onClick(() -> setListMode(true));
        grid.onSelect(this::select);
        list.onSelect(this::select);
        grid.onActivate(shell::play);
        list.onActivate(shell::play);
        grid.onStar(this::toggleFavorite);
        list.onStar(this::toggleFavorite);
        detail.onPlay(() -> { if (current != null) shell.play(current); });
        detail.onStar(() -> { if (current != null) toggleFavorite(current); });
        detail.onAssignCover(() -> { if (current != null) shell.showRomManager(current); });
        covers.onLoaded(() -> SwingUtilities.invokeLater(() -> { grid.repaint(); detail.repaint(); }));
        search.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_DOWN) (listMode ? list : grid).requestFocusInWindow();
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { search.setText(""); (listMode ? list : grid).requestFocusInWindow(); }
            }
        });
        refresh();
    }

    private JComponent wrapEdge(JComponent c, boolean right) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Pal.SIDE);
        p.add(c, BorderLayout.CENTER);
        JPanel line = new JPanel();
        line.setBackground(Pal.SEP);
        line.setPreferredSize(new Dimension(1, 0));
        p.add(line, right ? BorderLayout.EAST : BorderLayout.WEST);
        return p;
    }

    private JComponent emptyView() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Pal.MAIN);
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        JLabel a = Widgets.label("Nessun gioco corrisponde ai filtri", Pal.ui(16, 2), Pal.TXT);
        a.setAlignmentX(CENTER_ALIGNMENT);
        Ui.Btn reset = new Ui.Btn("Azzera filtri", null, Pal.ACCENT_L, null).onClick(() -> { model.resetFilters(); search.setText(""); refresh(); });
        reset.setAlignmentX(CENTER_ALIGNMENT);
        box.add(a);
        box.add(Box.createVerticalStrut(8));
        box.add(reset);
        p.add(box);
        return p;
    }

    private JComponent commandBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = Ui.g2(g);
                g2.setColor(Pal.BAR);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Pal.SEP);
                g2.drawLine(0, 0, getWidth(), 0);
                int x = 24, y = getHeight() / 2 + 5;
                x = pad(g2, x, y, "A", new Color(0xc9444f), "Gioca");
                x = pad(g2, x, y, "B", new Color(0xe0b43c), "Indietro");
                x = pad(g2, x, y, "Y", new Color(0x3f9b5e), "Preferito");
                x = pad(g2, x, y, "X", new Color(0x3e62b5), "Save state");
                x = key(g2, x, y, "L / R", "Cambia raccolta");
                key(g2, x, y, "SELECT", "Cerca");
                String r = "Doppio clic o Invio per avviare";
                Ui.text(g2, r, getWidth() - 24 - g2.getFontMetrics(Pal.ui(13, 0)).stringWidth(r), y, Pal.ui(13, 0), Pal.TXT3);
                g2.dispose();
            }

            private int pad(Graphics2D g, int x, int y, String b, Color c, String text) {
                g.setColor(c);
                g.fillOval(x, y - 17, 24, 24);
                FontMetrics fm = g.getFontMetrics(Pal.ui(12, 2));
                Ui.text(g, b, x + 12 - fm.stringWidth(b) / 2, y - 1, Pal.ui(12, 2), Color.WHITE);
                int w = Ui.text(g, text, x + 34, y, Pal.ui(14, 0), Pal.TXT2);
                return x + 34 + w + 30;
            }

            private int key(Graphics2D g, int x, int y, String k, String text) {
                FontMetrics fm = g.getFontMetrics(Pal.ui(11, 2));
                int kw = fm.stringWidth(k) + 16;
                Ui.fillRound(g, x, y - 18, kw, 25, 8, Pal.CARD, null);
                Ui.text(g, k, x + 8, y - 1, Pal.ui(11, 2), Pal.TXT);
                int w = Ui.text(g, text, x + kw + 10, y, Pal.ui(14, 0), Pal.TXT2);
                return x + kw + 10 + w + 30;
            }
        };
        bar.setPreferredSize(new Dimension(0, 52));
        return bar;
    }

    private void setListMode(boolean on) {
        listMode = on;
        gridBtn.setColors(on ? Pal.CARD : Pal.ACTIVE, on ? Pal.TXT2 : Pal.TXT, on ? Pal.CARD_LINE : null);
        listBtn.setColors(on ? Pal.ACTIVE : Pal.CARD, on ? Pal.TXT : Pal.TXT2, on ? null : Pal.CARD_LINE);
        refresh();
    }

    private void select(Game g) {
        current = g;
        detail.setGame(g);
    }

    private void toggleFavorite(Game g) {
        model.toggleFavorite(g);
        rebuildLeft();
        grid.repaint();
        list.repaint();
        detail.repaint();
        if (model.collection == LibraryModel.Collection.FAVORITES) refresh();
    }

    /** Re-applies the filters and redraws everything. */
    void refresh() {
        sortBtn.setText(model.sort.label + "    ");
        sortBtn.setPreferredSize(new Dimension(Math.max(170, sortBtn.getFontMetrics(Pal.ui(14, 1)).stringWidth(model.sort.label + "    ") + 34), 44));
        List<Game> view = model.view();
        Game keep = current;
        grid.setGames(view, keep);
        list.setGames(view, keep);
        heading.setText(model.collection.label + (model.genre != null ? " · " + model.genre : ""));
        countLabel.setText(view.size() + (view.size() == 1 ? " gioco" : " giochi"));
        cards.show(content, view.isEmpty() ? "empty" : listMode ? "list" : "grid");
        if (view.isEmpty()) select(null);
        rebuildLeft();
        revalidate();
        repaint();
    }

    private void rebuildLeft() {
        left.removeAll();
        left.add(section("Raccolte"));
        for (LibraryModel.Collection c : LibraryModel.Collection.values())
            left.add(new Widgets.NavRow(c.label, model.count(c), model.collection == c, () -> { model.collection = c; refresh(); }));
        Map<String, Integer> genres = model.genres();
        if (!genres.isEmpty()) {
            left.add(Box.createVerticalStrut(22));
            left.add(section("Genere"));
            left.add(new Widgets.NavRow("Tutti", model.allGames().size(), model.genre == null, () -> { model.genre = null; refresh(); }));
            for (var e : genres.entrySet())
                left.add(new Widgets.NavRow(e.getKey(), e.getValue(), e.getKey().equalsIgnoreCase(model.genre),
                        () -> { model.genre = e.getKey().equalsIgnoreCase(model.genre) ? null : e.getKey(); refresh(); }));
        }
        left.add(Box.createVerticalStrut(22));
        left.add(section("Giocatori"));
        JPanel pl = new JPanel(new GridLayout(1, 3, 8, 0));
        pl.setOpaque(false);
        pl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        String[] names = {"Tutti", "1", "2+"};
        for (int i = 0; i < 3; i++) {
            final int v = i;
            boolean on = model.players == v;
            pl.add(new Ui.Btn(names[i], on ? Pal.ACCENT : Pal.CARD, on ? Color.WHITE : Pal.TXT, on ? null : Pal.CARD_LINE).onClick(() -> { model.players = v; refresh(); }));
        }
        left.add(pl);
        left.add(Box.createVerticalGlue());
        left.revalidate();
        left.repaint();
    }

    private JComponent section(String title) {
        JLabel l = new JLabel(title.toUpperCase());
        l.setFont(Pal.ui(11, 2).deriveFont(Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f)));
        l.setForeground(Pal.TXT3);
        l.setBorder(new EmptyBorder(0, 4, 10, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    Game selectedGame() { return current; }

    /** For screenshots: list or grid view and a search text. */
    void preset(boolean listView, String query) { search.setText(query); setListMode(listView); }

    void focusCatalogue() { (listMode ? list : grid).requestFocusInWindow(); }
}
