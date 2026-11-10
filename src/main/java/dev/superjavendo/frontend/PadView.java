package dev.superjavendo.frontend;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JPanel;

/** Stylised SNES pad; every control is clickable and reports its button name ("up", "a", "save_state"...). */
final class PadView extends JPanel {
    private final Map<String, Shape> hit = new LinkedHashMap<>();
    private final Map<String, Color> colors = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    private String selected = "up";
    private java.util.Set<String> bound = java.util.Set.of();

    PadView(Consumer<String> onSelect) {
        setPreferredSize(new Dimension(560, 300));
        setBackground(Theme.BG);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                // later controls are drawn on top: iterate in reverse registration order
                String[] names = hit.keySet().toArray(new String[0]);
                for (int i = names.length - 1; i >= 0; i--) {
                    if (hit.get(names[i]).contains(e.getPoint())) { selected = names[i]; repaint(); onSelect.accept(selected); return; }
                }
            }
        });
        layoutControls();
    }

    void setBound(java.util.Set<String> b) { bound = b; repaint(); }

    String selected() { return selected; }

    private void ctl(String name, Shape s, Color c, String label) { hit.put(name, s); colors.put(name, c); labels.put(name, label); }

    private void layoutControls() {
        ctl("l", new RoundRectangle2D.Double(60, 40, 110, 30, 18, 18), Theme.BODY_DARK, "L");
        ctl("r", new RoundRectangle2D.Double(390, 40, 110, 30, 18, 18), Theme.BODY_DARK, "R");
        int cx = 120, cy = 160, a = 26;
        ctl("up", new Rectangle(cx - a / 2, cy - 3 * a / 2, a, a + 4), Color.DARK_GRAY, "▲");
        ctl("down", new Rectangle(cx - a / 2, cy + a / 2 - 4, a, a + 4), Color.DARK_GRAY, "▼");
        ctl("left", new Rectangle(cx - 3 * a / 2, cy - a / 2, a + 4, a), Color.DARK_GRAY, "◀");
        ctl("right", new Rectangle(cx + a / 2 - 4, cy - a / 2, a + 4, a), Color.DARK_GRAY, "▶");
        int fx = 440, fy = 160, d = 34, r = 20;
        ctl("x", new Ellipse2D.Double(fx - r, fy - d - r, 2 * r, 2 * r), Theme.BLUE, "X");
        ctl("y", new Ellipse2D.Double(fx - d - r, fy - r, 2 * r, 2 * r), Theme.GREEN, "Y");
        ctl("a", new Ellipse2D.Double(fx + d - r, fy - r, 2 * r, 2 * r), Theme.RED, "A");
        ctl("b", new Ellipse2D.Double(fx - r, fy + d - r, 2 * r, 2 * r), Theme.YELLOW, "B");
        ctl("select", new RoundRectangle2D.Double(220, 175, 50, 16, 16, 16), Theme.BODY_DARK, "SELECT");
        ctl("start", new RoundRectangle2D.Double(290, 175, 50, 16, 16, 16), Theme.BODY_DARK, "START");
        ctl("save_state", new RoundRectangle2D.Double(205, 235, 70, 24, 12, 12), Theme.PURPLE, "SAVE");
        ctl("load_state", new RoundRectangle2D.Double(285, 235, 70, 24, 12, 12), Theme.PURPLE, "LOAD");
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Theme.BODY);
        g.fill(new RoundRectangle2D.Double(30, 60, 500, 150, 90, 90));
        g.setColor(Theme.BODY_DARK);
        g.setStroke(new BasicStroke(2));
        g.draw(new RoundRectangle2D.Double(30, 60, 500, 150, 90, 90));
        g.setFont(Theme.TEXT.deriveFont(Font.BOLD, 11f));
        for (var e : hit.entrySet()) {
            String n = e.getKey();
            g.setColor(colors.get(n));
            g.fill(e.getValue());
            Rectangle b = e.getValue().getBounds();
            if (n.equals("select") || n.equals("start") || n.equals("save_state") || n.equals("load_state")) {
                g.setColor(Color.WHITE);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(labels.get(n), b.x + (b.width - fm.stringWidth(labels.get(n))) / 2, b.y + (b.height + fm.getAscent()) / 2 - 2);
            } else {
                g.setColor(Color.WHITE);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(labels.get(n), b.x + (b.width - fm.stringWidth(labels.get(n))) / 2, b.y + (b.height + fm.getAscent()) / 2 - 2);
            }
            if (bound.contains(n)) {                         // small dot: this control has at least one binding
                g.setColor(Theme.YELLOW);
                g.fill(new Ellipse2D.Double(b.getMaxX() - 7, b.y - 1, 8, 8));
            }
            if (n.equals(selected)) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(3));
                g.draw(e.getValue());
                g.setStroke(new BasicStroke(2));
            }
        }
    }
}
