package dev.superjavendo.frontend.legacy;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JPanel;

/**
 * Line-art SNES pad. Every control is clickable and reports its button name ("up", "a", "save_state"...);
 * a legend around the pad lists what is currently bound to each control ("L: BUTTON_10").
 */
final class PadView extends JPanel {
    private static final int W = 620, H = 470;
    private static final Color PAPER = new Color(0xF4, 0xF2, 0xF7), LINE = new Color(0x22, 0x20, 0x2B);

    private final Map<String, Shape> hit = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();
    /** Legend entries: button -> {x, y, maxWidth}. */
    private final Map<String, int[]> legend = new LinkedHashMap<>();
    private final Map<String, Rectangle> legendHit = new LinkedHashMap<>();
    private String selected = "up";
    private Map<String, List<String>> bindings = Map.of();

    PadView(Consumer<String> onSelect) {
        setPreferredSize(new Dimension(W, H));
        setBackground(Theme.BG);
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                String hitName = null;
                for (var en : hit.entrySet()) if (en.getValue().contains(e.getPoint())) hitName = en.getKey(); // last drawn wins
                if (hitName == null) for (var en : legendHit.entrySet()) if (en.getValue().contains(e.getPoint())) hitName = en.getKey();
                if (hitName != null) { selected = hitName; repaint(); onSelect.accept(selected); }
            }
        });
        layoutControls();
    }

    void setBindings(Map<String, List<String>> b) { bindings = b; repaint(); }

    String selected() { return selected; }

    private void ctl(String name, Shape s, String label) { hit.put(name, s); labels.put(name, label); }

    private static Shape rotated(Shape s, double deg, double cx, double cy) {
        return AffineTransform.getRotateInstance(Math.toRadians(deg), cx, cy).createTransformedShape(s);
    }

    private static Shape pill(double cx, double cy, double len, double wid, double deg) {
        return rotated(new RoundRectangle2D.Double(cx - len / 2, cy - wid / 2, len, wid, wid, wid), deg, cx, cy);
    }

    private static Shape circle(double cx, double cy, double r) { return new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r); }

    private void layoutControls() {
        ctl("l", new RoundRectangle2D.Double(102, 98, 110, 28, 20, 20), "L");
        ctl("r", new RoundRectangle2D.Double(394, 98, 110, 28, 20, 20), "R");
        Area cross = new Area(new Rectangle2D.Double(136, 177, 35, 90));
        cross.add(new Area(new Rectangle2D.Double(108, 204, 91, 35)));
        ctl("dpad", cross, "");                                       // drawn only; the four arms below are the real targets
        hit.remove("dpad"); labels.remove("dpad");
        ctl("up", new Rectangle2D.Double(136, 177, 35, 36), "▲");
        ctl("down", new Rectangle2D.Double(136, 231, 35, 36), "▼");
        ctl("left", new Rectangle2D.Double(108, 204, 37, 35), "◀");
        ctl("right", new Rectangle2D.Double(162, 204, 37, 35), "▶");
        ctl("select", pill(257, 236, 40, 17, -35), "");
        ctl("start", pill(312, 236, 40, 17, -35), "");
        ctl("x", circle(450, 184, 19), "X");
        ctl("y", circle(400, 221, 19), "Y");
        ctl("a", circle(498, 223, 19), "A");
        ctl("b", circle(448, 260, 19), "B");
        ctl("save_state", new RoundRectangle2D.Double(232, 322, 70, 24, 12, 12), "SAVE");
        ctl("load_state", new RoundRectangle2D.Double(318, 322, 70, 24, 12, 12), "LOAD");

        int top = 62, col1 = 20, col2 = 232, col3 = 420;
        legend.put("l", new int[]{col1, 80, 190});
        legend.put("start", new int[]{col2, 58, 170});
        legend.put("select", new int[]{col2, 82, 170});
        legend.put("r", new int[]{col3, 80, 190});
        String[] dp = {"up", "down", "left", "right"};
        for (int i = 0; i < 4; i++) legend.put(dp[i], new int[]{col1, 385 + i * 24, 195});
        String[] fb = {"y", "x", "a", "b"};
        for (int i = 0; i < 4; i++) legend.put(fb[i], new int[]{col3, 385 + i * 24, 190});
        legend.put("save_state", new int[]{col2, 385, 170});
        legend.put("load_state", new int[]{col2, 409, 170});
    }

    private static String title(String n) { return n.toUpperCase().replace('_', ' '); }

    private static String fit(FontMetrics fm, String s, int max) {
        if (fm.stringWidth(s) <= max) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.translate((getWidth() - W) / 2, Math.max(0, (getHeight() - H) / 2));

        // shoulder buttons sit behind the body
        for (String n : new String[]{"l", "r"}) paint(g, n);

        Area body = new Area(circle(155, 222, 108));
        body.add(new Area(circle(450, 222, 108)));
        body.add(new Area(new Rectangle2D.Double(155, 113, 295, 193)));
        g.setColor(PAPER); g.fill(body);
        g.setColor(LINE); g.setStroke(new BasicStroke(2f)); g.draw(body);
        g.draw(circle(153, 221, 59));
        g.draw(circle(450, 222, 97));
        g.draw(pill(425, 203, 112, 46, -35));
        g.draw(pill(473, 242, 112, 46, -35));

        Area cross = new Area(new Rectangle2D.Double(136, 177, 35, 90));
        cross.add(new Area(new Rectangle2D.Double(108, 204, 91, 35)));
        g.setColor(PAPER); g.fill(cross);
        g.setColor(LINE); g.draw(cross);
        for (String n : new String[]{"up", "down", "left", "right", "select", "start", "x", "y", "a", "b", "save_state", "load_state"}) paint(g, n);

        // legend
        Font base = Theme.TEXT.deriveFont(13f);
        for (var e : legend.entrySet()) {
            String n = e.getKey();
            int[] p = e.getValue();
            g.setFont(base.deriveFont(n.equals(selected) ? Font.BOLD : Font.PLAIN));
            FontMetrics fm = g.getFontMetrics();
            List<String> bs = bindings.getOrDefault(n, List.of());
            String text = title(n) + ": " + (bs.isEmpty() ? "—" : String.join(", ", bs));
            text = fit(fm, text, p[2]);
            g.setColor(n.equals(selected) ? Theme.YELLOW : bs.isEmpty() ? Theme.BODY_DARK : Theme.INK);
            g.drawString(text, p[0], p[1] + fm.getAscent() / 2);
            legendHit.put(n, new Rectangle(p[0] + (getWidth() - W) / 2, p[1] - 10 + Math.max(0, (getHeight() - H) / 2), p[2], 20));
        }
    }

    private void paint(Graphics2D g, String n) {
        Shape s = hit.get(n);
        boolean sel = n.equals(selected), bound = bindings.containsKey(n);
        boolean small = n.equals("save_state") || n.equals("load_state");
        boolean arm = n.equals("up") || n.equals("down") || n.equals("left") || n.equals("right");
        Color fill = sel ? new Color(0xB8, 0xA3, 0xE8) : small ? Theme.PURPLE : bound && !arm ? new Color(0xE9, 0xE4, 0xF3) : PAPER;
        if (!arm || sel) { g.setColor(fill); g.fill(s); }
        g.setColor(arm && !sel ? new Color(0, 0, 0, 0) : LINE);
        g.setStroke(new BasicStroke(sel ? 3f : 2f));
        if (!arm || sel) g.draw(s);
        String t = labels.get(n);
        if (!t.isEmpty()) {
            g.setFont(Theme.TEXT.deriveFont(Font.BOLD, small ? 11f : 13f));
            FontMetrics fm = g.getFontMetrics();
            Rectangle b = s.getBounds();
            g.setColor(small ? Color.WHITE : LINE);
            g.drawString(t, b.x + (b.width - fm.stringWidth(t)) / 2, b.y + (b.height + fm.getAscent()) / 2 - 2);
        }
        if (bound && !small) {                         // dot: this control has at least one binding
            Rectangle b = s.getBounds();
            g.setColor(Theme.GREEN);
            g.fill(new Ellipse2D.Double(b.getMaxX() - 6, b.y - 2, 7, 7));
        }
    }
}
