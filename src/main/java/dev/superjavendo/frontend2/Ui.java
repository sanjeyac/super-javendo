package dev.superjavendo.frontend2;

import javax.swing.JComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Painting helpers and small custom widgets shared by the v2 screens. */
final class Ui {
    private Ui() { }

    static Graphics2D g2(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    static String ellipsize(FontMetrics fm, String s, int w) {
        if (fm.stringWidth(s) <= w) return s;
        int n = s.length();
        while (n > 1 && fm.stringWidth(s.substring(0, n) + "…") > w) n--;
        return s.substring(0, n).stripTrailing() + "…";
    }

    /** Draws {@code s} with its baseline at y; returns the width used. */
    static int text(Graphics2D g, String s, int x, int y, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, x, y);
        return g.getFontMetrics().stringWidth(s);
    }

    /** Section label: 11 px, upper case, wide letter spacing. */
    static void label(Graphics2D g, String s, int x, int y) {
        Font f = Pal.ui(11, 2).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f));
        text(g, s.toUpperCase(), x, y, f, Pal.TXT3);
    }

    static void fillRound(Graphics2D g, int x, int y, int w, int h, int r, Color fill, Color line) {
        RoundRectangle2D rr = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, r, r);
        if (fill != null) { g.setColor(fill); g.fill(rr); }
        if (line != null) { g.setColor(line); g.setStroke(new BasicStroke(1f)); g.draw(rr); }
    }

    static void dashedRound(Graphics2D g, int x, int y, int w, int h, int r, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5, 4}, 0));
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, r, r));
        g.setStroke(new BasicStroke(1f));
    }

    /** Draws an image scaled to cover the box (cropping the overflow), clipped to rounded corners. */
    static void coverFit(Graphics2D g, BufferedImage img, int x, int y, int w, int h, int r) {
        Shape old = g.getClip();
        g.clip(new RoundRectangle2D.Float(x, y, w, h, r, r));
        double s = Math.max(w / (double) img.getWidth(), h / (double) img.getHeight());
        int dw = (int) Math.ceil(img.getWidth() * s), dh = (int) Math.ceil(img.getHeight() * s);
        g.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
        g.setClip(old);
    }

    /** Draws an image scaled to fit inside the box without cropping (used for 8:7 screenshots). */
    static void fit(Graphics2D g, BufferedImage img, int x, int y, int w, int h, int r) {
        Shape old = g.getClip();
        g.clip(new RoundRectangle2D.Float(x, y, w, h, r, r));
        g.setColor(Color.BLACK);
        g.fillRect(x, y, w, h);
        double s = Math.min(w / (double) img.getWidth(), h / (double) img.getHeight());
        int dw = (int) (img.getWidth() * s), dh = (int) (img.getHeight() * s);
        g.drawImage(img, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, null);
        g.setClip(old);
    }

    static void star(Graphics2D g, double cx, double cy, double r, boolean filled, Color c) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5, rr = i % 2 == 0 ? r : r * 0.45;
            double px = cx + Math.cos(a) * rr, py = cy + Math.sin(a) * rr;
            if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.closePath();
        g.setColor(c);
        if (filled) g.fill(p); else { g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(p); g.setStroke(new BasicStroke(1f)); }
    }

    /** Small "picture" glyph for covers that are missing. */
    static void pictureIcon(Graphics2D g, int cx, int cy, Color c) {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(new RoundRectangle2D.Float(cx - 14, cy - 11, 28, 22, 5, 5));
        g.fillOval(cx - 8, cy - 6, 5, 5);
        Path2D m = new Path2D.Float();
        m.moveTo(cx - 12, cy + 9); m.lineTo(cx - 4, cy + 1); m.lineTo(cx + 2, cy + 6); m.lineTo(cx + 7, cy + 1); m.lineTo(cx + 12, cy + 7);
        g.draw(m);
        g.setStroke(new BasicStroke(1f));
    }

    static void gridIcon(Graphics2D g, Rectangle r) {
        int s = 7, gap = 3, x0 = r.x + (r.width - 2 * s - gap) / 2, y0 = r.y + (r.height - 2 * s - gap) / 2;
        for (int i = 0; i < 4; i++) g.fillRoundRect(x0 + (i % 2) * (s + gap), y0 + (i / 2) * (s + gap), s, s, 2, 2);
    }

    static void listIcon(Graphics2D g, Rectangle r) {
        int x0 = r.x + (r.width - 16) / 2, y0 = r.y + (r.height - 12) / 2;
        for (int i = 0; i < 3; i++) g.fillRoundRect(x0, y0 + i * 5, 16, 2, 2, 2);
    }

    static void chevron(Graphics2D g, int cx, int cy) {
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(cx - 4, cy - 2, cx, cy + 2);
        g.drawLine(cx, cy + 2, cx + 4, cy - 2);
        g.setStroke(new BasicStroke(1f));
    }

    // ------------------------------------------------------------------------------------------ formatting

    static String lastPlayed(long millis) {
        if (millis <= 0) return "Mai";
        LocalDate d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate(), today = LocalDate.now();
        if (d.equals(today)) return "Oggi";
        if (d.equals(today.minusDays(1))) return "Ieri";
        return d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    static String playTime(long seconds) {
        if (seconds < 60) return seconds <= 0 ? "—" : "< 1 min";
        long m = seconds / 60;
        return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }

    static String size(long bytes) {
        if (bytes <= 0) return "0 KB";
        return bytes >= 1 << 20 ? Math.round(bytes / 1048576.0) + " MB" : Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    static String shortDate(long millis) {
        return millis <= 0 ? "mai" : Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ITALIAN));
    }

    static String players(int n) { return n <= 0 ? "—" : n == 1 ? "1" : n + (n >= 2 ? "" : ""); }

    // ------------------------------------------------------------------------------------------ widgets

    /** Rounded button. Primary buttons use filled colours; others a card background with a thin border. */
    static class Btn extends JComponent {
        private String text;
        private Color bg, fg, line;
        private boolean hover;
        private Runnable action = () -> { };
        private int radius = 8;
        private Font font = Pal.ui(14, 2);
        private boolean chevron;
        private java.util.function.BiConsumer<Graphics2D, Rectangle> iconPainter;

        Btn(String text, Color bg, Color fg, Color line) {
            this.text = text; this.bg = bg; this.fg = fg; this.line = line;
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseReleased(MouseEvent e) { if (contains(e.getPoint()) && isEnabled()) action.run(); }
            });
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) { if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) action.run(); }
            });
            FocusAdapter f = new FocusAdapter() { @Override public void focusGained(FocusEvent e) { repaint(); } @Override public void focusLost(FocusEvent e) { repaint(); } };
            addFocusListener(f);
        }

        Btn onClick(Runnable r) { action = r; return this; }
        Btn font(Font f) { font = f; return this; }
        Btn radius(int r) { radius = r; return this; }
        Btn chevron() { chevron = true; return this; }
        /** Replaces the text by a drawn icon (painted in the button's text colour). */
        Btn icon(java.util.function.BiConsumer<Graphics2D, Rectangle> p) { iconPainter = p; return this; }
        void setText(String t) { text = t; revalidate(); repaint(); }
        void setColors(Color bg, Color fg, Color line) { this.bg = bg; this.fg = fg; this.line = line; repaint(); }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(font);
            return new Dimension(fm.stringWidth(text) + 32, 40);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = g2(g);
            if (!isEnabled()) g2.setComposite(AlphaComposite.SrcOver.derive(0.4f));
            Color b = bg;
            if (b != null && hover) b = b.brighter();
            Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), radius * 2, b, hover && b == null ? Pal.ACCENT_L : line);
            FontMetrics fm = g2.getFontMetrics(font);
            if (iconPainter != null) { g2.setColor(fg); iconPainter.accept(g2, new Rectangle(0, 0, getWidth(), getHeight())); }
            else {
                Ui.text(g2, text.stripTrailing(), (getWidth() - fm.stringWidth(text)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2, font, fg);
                if (chevron) { g2.setColor(fg); Ui.chevron(g2, getWidth() - 20, getHeight() / 2); }
            }
            if (isFocusOwner()) { g2.setColor(Pal.ACCENT_L); g2.setStroke(new BasicStroke(2f)); g2.draw(new RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, radius * 2, radius * 2)); }
            g2.dispose();
        }
    }
}
