package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.movie.rec.RecEngine;
import dev.superjavendo.movie.rec.RecSession;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Progress bar of the whole run: emulated (with the greenzone strip), to re-emulate (hatched), planned (dashed),
 * the frontier, the playhead and the part visible in the piano roll. Click or drag on the emulated part seeks.
 */
final class RecTimelineBar extends JComponent {
    private static final int PAD_X = 20, TRACK_Y = 34, TRACK_H = 18;
    private static final TexturePaint HATCH = hatch();

    private final RecPanel panel;
    private int dragging;                     // 0 none, 1 playhead, 2 viewport
    private int dragOffset;
    private int hoverFrame = -1;

    RecTimelineBar(RecPanel panel) {
        this.panel = panel;
        setPreferredSize(new Dimension(100, 88));
        setFocusable(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); press(e); }
            @Override public void mouseDragged(MouseEvent e) { drag(e); }
            @Override public void mouseReleased(MouseEvent e) { dragging = 0; }
            @Override public void mouseMoved(MouseEvent e) { hoverFrame = frameAt(e.getX()); repaint(); }
            @Override public void mouseExited(MouseEvent e) { hoverFrame = -1; repaint(); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
    }

    @Override public String getToolTipText(MouseEvent e) {
        int f = frameAt(e.getX());
        return f < 0 ? null : t("rec.frame", RecFormat.num(f)) + " · " + RecFormat.time(f);
    }

    private static TexturePaint hatch() {
        BufferedImage b = new BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = b.createGraphics();
        g.setColor(Pal.AMBER);
        g.drawLine(0, 6, 6, 0);
        g.drawLine(-1, 1, 1, -1);
        g.drawLine(5, 7, 7, 5);
        g.dispose();
        return new TexturePaint(b, new Rectangle(0, 0, 6, 6));
    }

    // ------------------------------------------------------------------ geometry

    private int span() {
        RecSession.View v = panel.view();
        int planned = v.inputs().length();
        return Math.max(1800, (int) (Math.max(v.frontier(), planned) * 1.15) + 60);
    }

    private int trackW() { return getWidth() - 2 * PAD_X; }
    private int x(int frame) { return PAD_X + (int) ((long) frame * trackW() / span()); }

    private int frameAt(int px) {
        if (px < PAD_X || px > PAD_X + trackW()) return -1;
        return (int) ((long) (px - PAD_X) * span() / trackW());
    }

    private Rectangle viewportRect() {
        int first = panel.roll.firstFrame(), n = panel.roll.visibleFrames();
        int x0 = x(first), x1 = Math.max(x0 + 6, x(first + n));
        return new Rectangle(x0, TRACK_Y - 3, x1 - x0, TRACK_H + 6);
    }

    // ------------------------------------------------------------------ interaction

    private void press(MouseEvent e) {
        if (panel.disabled()) return;
        RecSession.View v = panel.view();
        int px = x(v.playhead());
        if (Math.abs(e.getX() - px) <= 6 && e.getY() > TRACK_Y - 8 && e.getY() < TRACK_Y + TRACK_H + 8) { dragging = 1; return; }
        Rectangle vr = viewportRect();
        if (vr.contains(e.getPoint())) { dragging = 2; dragOffset = e.getX() - vr.x; return; }
        click(frameAt(e.getX()));
        dragging = 1;
    }

    private void drag(MouseEvent e) {
        if (panel.disabled()) return;
        if (dragging == 1) click(frameAt(Math.max(PAD_X, Math.min(PAD_X + trackW(), e.getX()))));
        else if (dragging == 2) panel.roll.scrollTo(frameAt(Math.max(PAD_X, Math.min(PAD_X + trackW(), e.getX() - dragOffset))));
    }

    /** On the emulated part: seek. On the rest: only the piano roll scrolls (no seeking into the future). */
    private void click(int frame) {
        if (frame < 0) return;
        RecSession.View v = panel.view();
        if (frame <= v.firstInvalid()) panel.requestSeek(frame);
        else panel.roll.scrollTo(frame - panel.roll.visibleFrames() / 2);
    }

    // ------------------------------------------------------------------ painting

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.g2(g);
        int w = getWidth();
        g2.setColor(Pal.MAIN);
        g2.fillRect(0, 0, w, getHeight());
        RecSession.View v = panel.view();
        RecEngine.Status st = panel.status();
        Ui.label(g2, t("rec.timeline"), PAD_X, 24);
        legend(g2, w);

        int tw = trackW();
        Shape clip = new RoundRectangle2D.Float(PAD_X, TRACK_Y, tw, TRACK_H, 12, 12);
        Ui.fillRound(g2, PAD_X, TRACK_Y, tw, TRACK_H, 12, Pal.CARD, null);
        Shape old = g2.getClip();
        g2.clip(clip);
        int xi = x(v.firstInvalid()), xf = x(v.frontier());
        g2.setColor(hoverIn(0, v.firstInvalid()) ? Pal.ACTIVE.brighter() : Pal.ACTIVE);
        g2.fillRect(PAD_X, TRACK_Y, xi - PAD_X, TRACK_H);
        g2.setColor(Pal.OK.darker());
        g2.setColor(new Color(0x3fbf6f));
        for (int k = v.states().nextSetBit(0); k >= 0 && k <= v.firstInvalid(); k = v.states().nextSetBit(k + 1)) {
            int a = x(k), b = Math.min(xi, Math.max(a + 2, x(k + 60)));
            g2.fillRect(a, TRACK_Y + TRACK_H - 3, b - a, 3);
        }
        if (xf > xi) {
            g2.setColor(hoverIn(v.firstInvalid(), v.frontier()) ? Pal.INVALID_BG.brighter() : Pal.INVALID_BG);
            g2.fillRect(xi, TRACK_Y, xf - xi, TRACK_H);
            g2.setPaint(HATCH);
            g2.fillRect(xi, TRACK_Y, xf - xi, TRACK_H);
            if (st != null && st.busy() && st.total() > 0) {            // progress of a re-emulation or of opening a run
                g2.setColor(new Color(0x4fd17f));
                g2.fillRect(xi, TRACK_Y, (int) ((long) (xf - xi) * st.done() / st.total()), TRACK_H);
            }
        } else if (st != null && st.busy() && st.total() > 0) {
            g2.setColor(new Color(0x4fd17f));
            g2.fillRect(PAD_X, TRACK_Y, (int) ((long) tw * st.done() / Math.max(st.total(), span())), TRACK_H);
        }
        g2.setClip(old);
        g2.setColor(Pal.CARD_LINE);
        g2.draw(new RoundRectangle2D.Float(PAD_X + 0.5f, TRACK_Y + 0.5f, tw - 1, TRACK_H - 1, 12, 12));
        // planned: dashed outline from the frontier to the end
        int px0 = Math.max(xf, PAD_X + 6);
        g2.setColor(hoverIn(v.frontier(), span()) ? Pal.TXT3 : Pal.PLANNED_LINE);
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{4, 3}, 0));
        g2.draw(new RoundRectangle2D.Float(px0 + 0.5f, TRACK_Y + 0.5f, PAD_X + tw - px0 - 1, TRACK_H - 1, 12, 12));
        g2.setStroke(new BasicStroke(1f));

        // ticks every 15 s
        Font tf = Pal.ui(11, 0);
        for (int sec = 0; ; sec += 15) {
            int f = (int) (sec * RecFormat.FPS);
            if (f > span()) break;
            int tx = x(f);
            g2.setColor(Pal.SEP);
            g2.drawLine(tx, TRACK_Y + TRACK_H + 2, tx, TRACK_Y + TRACK_H + 8);
            Ui.text(g2, String.format("%d:%02d", sec / 60, sec % 60), Math.min(tx, PAD_X + tw - 24), TRACK_Y + TRACK_H + 22, tf, Pal.TXT3);
        }

        // markers
        g2.setColor(Pal.STAR);
        for (int mk : panel.markers()) g2.fillOval(x(mk) - 3, TRACK_Y - 10, 6, 6);

        // viewport, frontier, playhead
        Rectangle vr = viewportRect();
        g2.setColor(Pal.ACCENT_L);
        g2.drawRoundRect(vr.x, vr.y, vr.width, vr.height, 4, 4);
        g2.setColor(Pal.TXT);
        g2.fillRect(xf - 1, TRACK_Y - 5, 2, TRACK_H + 10);
        g2.setColor(Pal.ACCENT_L);
        g2.fillRect(x(v.playhead()) - 1, TRACK_Y - 5, 2, TRACK_H + 10);
        if (isFocusOwner()) {
            g2.setColor(Pal.ACCENT_L);
            g2.setStroke(new BasicStroke(2f));
            g2.draw(new RoundRectangle2D.Float(PAD_X - 2, TRACK_Y - 2, tw + 4, TRACK_H + 4, 16, 16));
        }
        g2.dispose();
    }

    private boolean hoverIn(int a, int b) { return hoverFrame >= a && hoverFrame < b; }

    private void legend(Graphics2D g2, int w) {
        Font f = Pal.ui(12, 0);
        FontMetrics fm = g2.getFontMetrics(f);
        String[] names = {t("rec.legend.emulated"), t("rec.legend.stale"), t("rec.legend.planned"), t("rec.frontier"), t("rec.legend.playhead")};
        int total = 0;
        for (String n : names) total += 18 + fm.stringWidth(n) + 18;
        int x = w - PAD_X - total + 18;
        for (int i = 0; i < names.length; i++) {
            int y = 10;
            switch (i) {
                case 0 -> { g2.setColor(Pal.ACTIVE); g2.fillRoundRect(x, y + 2, 12, 8, 3, 3); g2.setColor(new Color(0x3fbf6f)); g2.fillRect(x, y + 8, 12, 2); }
                case 1 -> { g2.setColor(Pal.AMBER); g2.fillRoundRect(x, y + 2, 12, 8, 3, 3); }
                case 2 -> { g2.setColor(Pal.PLANNED_LINE); g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{2, 2}, 0)); g2.drawRoundRect(x, y + 2, 11, 7, 3, 3); g2.setStroke(new BasicStroke(1f)); }
                case 3 -> { g2.setColor(Pal.TXT); g2.fillRect(x + 5, y, 2, 12); }
                default -> { g2.setColor(Pal.ACCENT_L); g2.fillRect(x + 5, y, 2, 12); }
            }
            Ui.text(g2, names[i], x + 18, 21, f, Pal.TXT2);
            x += 18 + fm.stringWidth(names[i]) + 18;
        }
    }
}
