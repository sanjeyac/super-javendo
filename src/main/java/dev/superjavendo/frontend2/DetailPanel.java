package dev.superjavendo.frontend2;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.config.AppPaths;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Right column of the library: cover, title, GIOCA, data, the five save-state slots and the file details. */
final class DetailPanel extends JComponent implements Scrollable {
    private static final int PAD = 24;

    private final Covers covers;
    private final LibraryModel model;
    private final java.util.function.Predicate<Game> running;
    private Game game;
    private Covers.Slot[] slots;

    private Runnable onPlay = () -> { }, onStar = () -> { }, onAssignCover = () -> { };
    private Rectangle playRect = new Rectangle(), starRect = new Rectangle(), resumeRect = new Rectangle(), assignRect = new Rectangle();
    private int contentH = 600;

    DetailPanel(Covers covers, LibraryModel model, java.util.function.Predicate<Game> running) {
        this.covers = covers;
        this.model = model;
        this.running = running;
        setCursor(Cursor.getDefaultCursor());
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (game == null) return;
                if (playRect.contains(e.getPoint()) || resumeRect.contains(e.getPoint())) onPlay.run();
                else if (starRect.contains(e.getPoint())) onStar.run();
                else if (assignRect.contains(e.getPoint())) onAssignCover.run();
            }
        });
    }

    void onPlay(Runnable r) { onPlay = r; }
    void onStar(Runnable r) { onStar = r; }
    void onAssignCover(Runnable r) { onAssignCover = r; }

    void setGame(Game g) {
        game = g;
        slots = null;
        revalidate();
        repaint();
        if (g != null) covers.loadSlots(g.path(), s -> SwingUtilities.invokeLater(() -> { if (game == g) { slots = s; repaint(); } }));
    }

    @Override public Dimension getPreferredSize() { return new Dimension(340, contentH); }

    private Covers.Slot latest() {
        Covers.Slot best = null;
        if (slots != null) for (Covers.Slot s : slots) if (s.exists() && (best == null || s.savedAt() > best.savedAt())) best = s;
        return best;
    }

    private static String slotName(int s) { return s == 0 ? t("slot.quick") : t("slot.n", s); }

    private static String when(long ms) {
        return ms <= 0 ? "" : Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.g2(g);
        int w = getWidth();
        g2.setColor(Pal.SIDE);
        g2.fillRect(0, 0, w, getHeight());
        playRect = starRect = resumeRect = assignRect = new Rectangle();
        if (game == null) {
            Ui.text(g2, t("detail.none"), PAD, 60, Pal.ui(14, 0), Pal.TXT3);
            g2.dispose();
            return;
        }
        int cw = w - 2 * PAD, y = PAD;
        int ch = cw * 3 / 4;
        BufferedImage cover = covers.get(game.path());
        if (cover != null) Ui.coverFit(g2, cover, PAD, y, cw, ch, 12);
        else {
            Ui.fillRound(g2, PAD, y, cw, ch, 12, Pal.CARD, null);
            Ui.dashedRound(g2, PAD, y, cw, ch, 12, Pal.CARD_LINE);
            Ui.pictureIcon(g2, PAD + cw / 2, y + ch / 2 - 16, Pal.TXT3);
            String t = t("detail.assign");
            FontMetrics fm = g2.getFontMetrics(Pal.ui(13, 1));
            int tx = PAD + (cw - fm.stringWidth(t)) / 2;
            Ui.text(g2, t, tx, y + ch / 2 + 24, Pal.ui(13, 1), Pal.ACCENT_L);
            g2.setColor(Pal.ACCENT_L);
            g2.drawLine(tx, y + ch / 2 + 27, tx + fm.stringWidth(t), y + ch / 2 + 27);
            assignRect = new Rectangle(tx, y + ch / 2 + 8, fm.stringWidth(t), 24);
        }
        y += ch + 22;
        FontMetrics tm = g2.getFontMetrics(Pal.title(26));
        Ui.text(g2, Ui.ellipsize(tm, game.title(), cw), PAD, y + 22, Pal.title(26), Pal.TXT);
        y += 34;
        String sub = (game.publisher().isEmpty() ? "" : game.publisher()) + (game.year() > 0 ? (game.publisher().isEmpty() ? "" : " · ") + game.year() : "");
        Ui.text(g2, sub.isEmpty() ? game.region() : sub, PAD, y + 16, Pal.ui(14, 0), Pal.TXT2);
        y += 36;
        boolean run = running.test(game);
        playRect = new Rectangle(PAD, y, cw - 56, 48);
        Ui.fillRound(g2, playRect.x, playRect.y, playRect.width, playRect.height, 10, run ? Pal.RUN : Pal.PLAY, null);
        String pt = run ? t("detail.running") : t("detail.play");
        Font pf = Pal.ui(15, 2).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.06f));
        FontMetrics pm = g2.getFontMetrics(pf);
        int pw = pm.stringWidth(pt) + 24, px = playRect.x + (playRect.width - pw) / 2;
        g2.setColor(Pal.PLAY_TXT);
        g2.fillPolygon(new int[]{px, px, px + 11}, new int[]{y + 18, y + 30, y + 24}, 3);
        Ui.text(g2, pt, px + 24, y + 29, pf, Pal.PLAY_TXT);
        starRect = new Rectangle(PAD + cw - 48, y, 48, 48);
        Ui.fillRound(g2, starRect.x, starRect.y, 48, 48, 10, Pal.CARD, Pal.CARD_LINE);
        boolean fav = model.favorite(game);
        Ui.star(g2, starRect.x + 24, starRect.y + 24.5, 10, fav, fav ? Pal.STAR : Pal.TXT);
        y += 48 + 24;
        int half = cw / 2;
        data(g2, t("field.genre"), game.genre().isEmpty() ? "—" : game.genre(), PAD, y, half);
        data(g2, t("field.players"), Ui.players(game.players()), PAD + half, y, half);
        y += 50;
        data(g2, t("field.lastplayed"), Ui.lastPlayed(model.lastPlayed(game)), PAD, y, half);
        data(g2, t("field.playtime"), Ui.playTime(model.playSeconds(game)), PAD + half, y, half);
        y += 62;
        // ---- save states
        Ui.label(g2, t("detail.states"), PAD, y);
        int have = 0;
        if (slots != null) for (Covers.Slot s : slots) if (s.exists()) have++;
        String hs = t(have == 1 ? "detail.saved1" : "detail.savedn", have);
        FontMetrics hm = g2.getFontMetrics(Pal.ui(12, 0));
        Ui.text(g2, hs, PAD + cw - hm.stringWidth(hs), y, Pal.ui(12, 0), Pal.TXT3);
        y += 14;
        Covers.Slot best = latest();
        int sh = cw * 7 / 8;
        if (best != null) {
            Ui.fillRound(g2, PAD, y, cw, sh + 46, 10, Pal.CARD, Pal.CARD_LINE);
            if (best.shot() != null) Ui.fit(g2, best.shot(), PAD + 1, y + 1, cw - 2, sh - 1, 10);
            Ui.text(g2, slotName(best.slot()), PAD + 14, y + sh + 28, Pal.ui(14, 2), Pal.TXT);
            Ui.text(g2, " · " + when(best.savedAt()), PAD + 14 + g2.getFontMetrics(Pal.ui(14, 2)).stringWidth(slotName(best.slot())), y + sh + 28, Pal.ui(13, 0), Pal.TXT3);
            String rs = t("detail.resume");
            FontMetrics rm = g2.getFontMetrics(Pal.ui(14, 2));
            Ui.text(g2, rs, PAD + cw - 14 - rm.stringWidth(rs), y + sh + 28, Pal.ui(14, 2), Pal.PLAY);
            resumeRect = new Rectangle(PAD + cw - 14 - rm.stringWidth(rs), y + sh + 8, rm.stringWidth(rs), 28);
            y += sh + 46 + 12;
        } else if (slots != null) {
            Ui.fillRound(g2, PAD, y, cw, 70, 10, Pal.CARD, Pal.CARD_LINE);
            Ui.dashedRound(g2, PAD, y, cw, 70, 10, Pal.CARD_LINE);
            Ui.text(g2, t("detail.nostate"), PAD + 16, y + 30, Pal.ui(14, 2), Pal.TXT2);
            Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(Pal.ui(12, 0)), t("detail.statehint"), cw - 32), PAD + 16, y + 52, Pal.ui(12, 0), Pal.TXT3);
            y += 70 + 12;
        } else y += 12;
        // all five slots, also the empty ones
        int n = AppPaths.MAX_SLOT + 1, gap = 8, tw = (cw - (n - 1) * gap) / n, th = tw * 7 / 8;
        for (int s = 0; s < n; s++) {
            int tx = PAD + s * (tw + gap);
            Covers.Slot sl = slots != null ? slots[s] : null;
            if (sl != null && sl.exists() && sl.shot() != null) Ui.fit(g2, sl.shot(), tx, y, tw, th, 6);
            else { Ui.fillRound(g2, tx, y, tw, th, 6, Pal.CARD, null); Ui.dashedRound(g2, tx, y, tw, th, 6, Pal.CARD_LINE); }
            if (sl != null && sl.exists()) { g2.setColor(Pal.ACCENT_L); g2.setStroke(new BasicStroke(1.5f)); g2.drawRoundRect(tx, y, tw - 1, th - 1, 6, 6); g2.setStroke(new BasicStroke(1f)); }
            String nm = s == 0 ? "R" : String.valueOf(s);
            FontMetrics nmf = g2.getFontMetrics(Pal.ui(11, 2));
            Ui.text(g2, nm, tx + (tw - nmf.stringWidth(nm)) / 2, y + th + 15, Pal.ui(11, 2), sl != null && sl.exists() ? Pal.TXT2 : Pal.TXT3);
        }
        y += th + 28;
        g2.setColor(Pal.SEP);
        g2.drawLine(PAD, y, PAD + cw, y);
        y += 20;
        Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(Pal.mono(12)), game.fileName(), cw), PAD, y, Pal.mono(12), Pal.TXT2);
        y += 20;
        String info = (game.region().isEmpty() ? "" : game.regionLabel() + " · ") + (game.crc32() == null ? t("detail.crcwait") : "CRC32 " + game.crc32());
        Ui.text(g2, info, PAD, y, Pal.mono(12), Pal.TXT3);
        y += PAD;
        if (y != contentH) { contentH = y; SwingUtilities.invokeLater(this::revalidate); }
        g2.dispose();
    }

    private void data(Graphics2D g, String label, String value, int x, int y, int w) {
        Ui.label(g, label, x, y + 8);
        Ui.text(g, Ui.ellipsize(g.getFontMetrics(Pal.ui(15, 1)), value, w - 8), x, y + 30, Pal.ui(15, 1), Pal.TXT);
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 30; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 30; }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
