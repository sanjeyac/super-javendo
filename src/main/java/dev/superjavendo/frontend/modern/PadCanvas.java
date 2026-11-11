package dev.superjavendo.frontend.modern;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** A SNES pad drawn in the dark theme; every button is clickable. The selected one is purple, the ones pressed on the real pad light up. */
final class PadCanvas extends JComponent {
    private static final double DW = 1000, DH = 440;
    private static final Color BODY = new Color(0xdad9e2), SHOULDER = new Color(0xb9b8c4), WELL = new Color(0xc8c7d2), DARK = new Color(0x2c2d38), PILL = new Color(0x6f7080);

    private String selected = "up";
    private Set<String> lit = Set.of();
    private Consumer<String> onPick = b -> { };
    private final Map<String, Shape> shapes = new LinkedHashMap<>();

    PadCanvas() {
        setPreferredSize(new Dimension(640, 290));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                String hit = null;
                for (var s : shapes.entrySet()) if (s.getValue().contains(e.getPoint())) hit = s.getKey();
                if (hit != null) onPick.accept(hit);
            }
        });
    }

    void onPick(Consumer<String> c) { onPick = c; }
    void setSelected(String b) { selected = b; repaint(); }
    void setLit(Set<String> l) { lit = l; repaint(); }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.g2(g);
        double s = Math.min(getWidth() / DW, getHeight() / DH);
        g2.translate((getWidth() - DW * s) / 2, (getHeight() - DH * s) / 2);
        g2.scale(s, s);
        AffineTransform toPx = g2.getTransform();
        shapes.clear();

        shoulder(g2, toPx, "l", 170, "L");
        shoulder(g2, toPx, "r", 600, "R");
        g2.setColor(BODY);
        g2.fill(new RoundRectangle2D.Double(60, 60, 880, 340, 340, 340));
        g2.setColor(WELL);
        g2.fill(new Ellipse2D.Double(160, 120, 220, 220));
        g2.fill(new Ellipse2D.Double(660, 120, 220, 220));

        // d-pad
        double cx = 270, cy = 230;
        g2.setColor(DARK);
        g2.fill(new Rectangle2D.Double(cx - 30, cy - 30, 60, 60));
        arm(g2, toPx, "up", new Rectangle2D.Double(cx - 30, cy - 90, 60, 60), 0);
        arm(g2, toPx, "down", new Rectangle2D.Double(cx - 30, cy + 30, 60, 60), 180);
        arm(g2, toPx, "left", new Rectangle2D.Double(cx - 90, cy - 30, 60, 60), 270);
        arm(g2, toPx, "right", new Rectangle2D.Double(cx + 30, cy - 30, 60, 60), 90);

        // select / start
        pill(g2, toPx, "select", 440, 262, "SELECT");
        pill(g2, toPx, "start", 540, 262, "START");

        // face buttons
        face(g2, toPx, "x", 770, 160, new Color(0x3e62b5), "X");
        face(g2, toPx, "y", 700, 230, new Color(0x3f9b5e), "Y");
        face(g2, toPx, "a", 840, 230, new Color(0xc9444f), "A");
        face(g2, toPx, "b", 770, 300, new Color(0xe0b43c), "B");
        g2.dispose();
    }

    private boolean on(String b) { return b.equals(selected); }

    private void register(String b, Shape design, AffineTransform toPx) { shapes.put(b, toPx.createTransformedShape(design)); }

    private void mark(Graphics2D g, String b, Shape sh, Color normal) {
        boolean sel = on(b), isLit = lit.contains(b);
        g.setColor(isLit ? new Color(0xe9fff1) : sel ? Pal.ACCENT : normal);
        g.fill(sh);
        if (sel || isLit) {
            g.setColor(isLit ? Pal.RUN : Pal.ACCENT_L);
            g.setStroke(new BasicStroke(sel ? 5f : 6f));
            g.draw(sh);
            g.setStroke(new BasicStroke(1f));
        }
    }

    private void shoulder(Graphics2D g, AffineTransform px, String b, double x, String label) {
        Shape sh = new RoundRectangle2D.Double(x, 10, 230, 90, 50, 50);
        mark(g, b, sh, SHOULDER);
        g.setFont(Pal.ui(30, 2));
        g.setColor(on(b) ? Color.WHITE : new Color(0x4a4b58));
        g.drawString(label, (float) (x + 115 - g.getFontMetrics().stringWidth(label) / 2.0), 50);
        register(b, sh, px);
    }

    private void arm(Graphics2D g, AffineTransform px, String b, Rectangle2D r, int deg) {
        Shape sh = new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 10, 10);
        mark(g, b, sh, DARK);
        g.setColor(on(b) || lit.contains(b) ? (lit.contains(b) ? DARK : Color.WHITE) : new Color(0xe6e9ef));
        double mx = r.getCenterX(), my = r.getCenterY();
        Path2D tri = new Path2D.Double();
        tri.moveTo(0, -12); tri.lineTo(13, 10); tri.lineTo(-13, 10); tri.closePath();
        AffineTransform t = AffineTransform.getTranslateInstance(mx, my);
        t.rotate(Math.toRadians(deg));
        g.fill(t.createTransformedShape(tri));
        register(b, new Rectangle2D.Double(r.getX() - 4, r.getY() - 4, r.getWidth() + 8, r.getHeight() + 8), px);
    }

    private void pill(Graphics2D g, AffineTransform px, String b, double cx, double cy, String label) {
        AffineTransform keep = g.getTransform();
        g.rotate(Math.toRadians(-25), cx, cy);
        Shape sh = new RoundRectangle2D.Double(cx - 42, cy - 13, 84, 26, 26, 26);
        mark(g, b, sh, PILL);
        AffineTransform rot = g.getTransform();
        register(b, new Rectangle2D.Double(cx - 46, cy - 18, 92, 36), rot);
        g.setTransform(keep);
        g.setFont(Pal.ui(15, 2));
        g.setColor(on(b) ? Pal.ACCENT : new Color(0x4a4b58));
        g.drawString(label, (float) (cx - g.getFontMetrics().stringWidth(label) / 2.0 + 4), (float) (cy + 44));
    }

    private void face(Graphics2D g, AffineTransform px, String b, double cx, double cy, Color c, String label) {
        Shape sh = new Ellipse2D.Double(cx - 44, cy - 44, 88, 88);
        mark(g, b, sh, c);
        g.setFont(Pal.ui(34, 2));
        g.setColor(b.equals("b") && !on(b) && !lit.contains(b) ? new Color(0x2a2208) : lit.contains(b) ? DARK : Color.WHITE);
        g.drawString(label, (float) (cx - g.getFontMetrics().stringWidth(label) / 2.0), (float) (cy + 12));
        register(b, sh, px);
    }
}
