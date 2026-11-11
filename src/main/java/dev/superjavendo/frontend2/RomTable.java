package dev.superjavendo.frontend2;

import static dev.superjavendo.i18n.I18n.t;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/** The ROM Manager table: status, file, matched title, region, size, cover and metadata flags. Multiple selection. */
final class RomTable extends JComponent implements Scrollable {
    private static final int ROW = 46, HEAD = 40;
    private static final int[] FIXED = {128, 0, 0, 76, 70, 84, 84};
    private static final String[] COLS = {"rt.status", "rt.file", "rt.match", "rt.region", "rt.size", "rt.cover", "rt.meta"};

    private List<RomCatalog.Row> rows = List.of();
    private boolean metaLoaded;
    private final TreeSet<Integer> sel = new TreeSet<>();
    private int anchor = -1, focus = -1;
    private Consumer<List<RomCatalog.Row>> onSelection = r -> { };

    RomTable() {
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int i = (e.getY() - HEAD) / ROW;
                if (e.getY() < HEAD || i < 0 || i >= rows.size()) return;
                if (e.isShiftDown() && anchor >= 0) range(anchor, i, e.isControlDown());
                else if (e.isControlDown()) { if (!sel.remove(i)) sel.add(i); anchor = focus = i; }
                else { sel.clear(); sel.add(i); anchor = focus = i; }
                changed();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (rows.isEmpty()) return;
                int cur = focus < 0 ? 0 : focus, to = cur;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> to = Math.min(rows.size() - 1, cur + 1);
                    case KeyEvent.VK_UP -> to = Math.max(0, cur - 1);
                    case KeyEvent.VK_HOME -> to = 0;
                    case KeyEvent.VK_END -> to = rows.size() - 1;
                    case KeyEvent.VK_A -> {
                        if (e.isControlDown()) { for (int i = 0; i < rows.size(); i++) sel.add(i); changed(); }
                        return;
                    }
                    default -> { return; }
                }
                if (e.isShiftDown()) { if (anchor < 0) anchor = cur; range(anchor, to, false); }
                else { sel.clear(); sel.add(to); anchor = to; }
                focus = to;
                scrollRectToVisible(new Rectangle(0, HEAD + to * ROW, 10, ROW));
                changed();
            }
        });
    }

    void onSelection(Consumer<List<RomCatalog.Row>> c) { onSelection = c; }

    private void range(int a, int b, boolean keep) {
        if (!keep) sel.clear();
        for (int i = Math.min(a, b); i <= Math.max(a, b); i++) sel.add(i);
        focus = b;
    }

    private void changed() { repaint(); onSelection.accept(selected()); }

    List<RomCatalog.Row> selected() {
        List<RomCatalog.Row> out = new ArrayList<>();
        for (int i : sel) if (i < rows.size()) out.add(rows.get(i));
        return out;
    }

    /** Replaces the rows, keeping the selection of the files that are still there (else the first row). */
    void setRows(List<RomCatalog.Row> list, boolean metaLoaded) {
        List<Path> keep = selected().stream().map(r -> r.game().path()).toList();
        rows = list;
        this.metaLoaded = metaLoaded;
        sel.clear();
        for (int i = 0; i < rows.size(); i++) if (keep.contains(rows.get(i).game().path())) sel.add(i);
        if (sel.isEmpty() && !rows.isEmpty()) sel.add(0);
        anchor = focus = sel.isEmpty() ? -1 : sel.first();
        revalidate();
        changed();
    }

    /** Selects one file if it is in the current rows. */
    void select(Path rom) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).game().path().equals(rom)) {
            sel.clear(); sel.add(i); anchor = focus = i;
            scrollRectToVisible(new Rectangle(0, HEAD + i * ROW, 10, ROW));
            changed();
            return;
        }
    }

    @Override public Dimension getPreferredSize() { return new Dimension(600, HEAD + rows.size() * ROW + 8); }

    private static Color[] pill(RomCatalog.Status s) {
        return switch (s) {
            case OK -> new Color[]{new Color(0x1d3a2b), Pal.OK};
            case DUPLICATE -> new Color[]{new Color(0x23304d), Pal.DUP};
            case NO_COVER -> new Color[]{new Color(0x3d3317), Pal.WARN};
            case UNKNOWN -> new Color[]{new Color(0x33274a), Pal.UNK};
            case ERROR -> new Color[]{new Color(0x4a2630), Pal.ERR};
        };
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.g2(g);
        int w = getWidth();
        g2.setColor(Pal.MAIN);
        g2.fillRect(0, 0, w, getHeight());
        int fixed = 0;
        for (int f : FIXED) fixed += f;
        int flex = Math.max(200, w - 28 - fixed);
        int[] width = FIXED.clone();
        width[1] = flex * 66 / 100;
        width[2] = flex - width[1];
        int[] x = new int[COLS.length + 1];
        x[0] = 16;
        for (int c = 0; c < COLS.length; c++) x[c + 1] = x[c] + width[c];
        for (int c = 0; c < COLS.length; c++) Ui.label(g2, t(COLS[c]), x[c], HEAD - 14);
        g2.setColor(Pal.SEP);
        g2.drawLine(0, HEAD - 1, w, HEAD - 1);
        Rectangle clip = g.getClipBounds();
        Font body = Pal.ui(13, 0), mono = Pal.mono(13), small = Pal.ui(12, 2);
        FontMetrics bm = g2.getFontMetrics(body), mm = g2.getFontMetrics(mono);
        for (int i = 0; i < rows.size(); i++) {
            int y = HEAD + i * ROW;
            if (clip != null && (y > clip.y + clip.height || y + ROW < clip.y)) continue;
            RomCatalog.Row r = rows.get(i);
            Game game = r.game();
            if (sel.contains(i)) Ui.fillRound(g2, 4, y + 2, w - 12, ROW - 4, 12, Pal.ROW_SEL, i == focus && isFocusOwner() ? Pal.ACCENT : null);
            if (i > 0) { g2.setColor(Pal.SEP); g2.drawLine(16, y, w - 16, y); }
            int base = y + ROW / 2 + 5;
            Color dim = r.hidden() ? Pal.TXT3 : Pal.TXT;
            Color[] pc = pill(r.status());
            String label = r.hidden() ? t("st.hidden") : r.status().label();
            FontMetrics sm = g2.getFontMetrics(small);
            int pw = sm.stringWidth(label) + 20;
            Ui.fillRound(g2, x[0], y + ROW / 2 - 12, pw, 24, 10, r.hidden() ? Pal.CARD : pc[0], null);
            Ui.text(g2, label, x[0] + 10, base - 1, small, r.hidden() ? Pal.TXT3 : pc[1]);
            Ui.text(g2, Ui.ellipsize(mm, game.fileName(), width[1] - 16), x[1], base, mono, dim);
            String match = r.hasMeta() ? game.title() : "—";
            Ui.text(g2, Ui.ellipsize(bm, match, width[2] - 16), x[2], base, body, dim);
            Ui.text(g2, game.region().isEmpty() ? "—" : game.regionLabel(), x[3], base, body, Pal.TXT2);
            Ui.text(g2, Ui.size(game.size()), x[4], base, body, Pal.TXT2);
            boolean err = r.status() == RomCatalog.Status.ERROR;
            Ui.text(g2, err ? "—" : r.hasCover() ? t("yes") : t("no"), x[5], base, body, err ? Pal.TXT3 : r.hasCover() ? Pal.OK : Pal.WARN);
            Ui.text(g2, err || !metaLoaded ? "—" : r.hasMeta() ? t("yes") : t("no"), x[6], base, body, err || !metaLoaded ? Pal.TXT3 : r.hasMeta() ? Pal.OK : Pal.WARN);
        }
        g2.dispose();
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return ROW; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - ROW; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
