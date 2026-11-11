package dev.superjavendo.frontend2;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Smaller custom widgets: navigation rows, search field, dark scroll bars. */
final class Widgets {
    private Widgets() { }

    /** A row of the left column: label on the left, count on the right; the active one is highlighted. */
    static final class NavRow extends JComponent {
        private final String label;
        private final String count;
        private boolean active, hover;

        NavRow(String label, int count, boolean active, Runnable onClick) {
            this.label = label;
            this.count = count < 0 ? "" : String.valueOf(count);
            this.active = active;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(100, 40));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { onClick.run(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            if (active) Ui.fillRound(g2, 0, 2, getWidth(), getHeight() - 4, 16, Pal.ACTIVE, null);
            else if (hover) Ui.fillRound(g2, 0, 2, getWidth(), getHeight() - 4, 16, Pal.ROW_SEL, null);
            Font f = Pal.ui(14, active ? 2 : 0);
            FontMetrics fm = g2.getFontMetrics(f), cm = g2.getFontMetrics(Pal.ui(12, 0));
            int cw = cm.stringWidth(count);
            Ui.text(g2, Ui.ellipsize(fm, label, getWidth() - 40 - cw), 14, (getHeight() + fm.getAscent() - fm.getDescent()) / 2, f, active ? Pal.TXT : Pal.TXT2);
            Ui.text(g2, count, getWidth() - 14 - cw, (getHeight() + cm.getAscent() - cm.getDescent()) / 2, Pal.ui(12, 0), Pal.TXT3);
            g2.dispose();
        }
    }

    /** Rounded search box with a magnifier and a placeholder. */
    static final class Search extends JTextField {
        private final String placeholder;

        Search(String placeholder) {
            this.placeholder = placeholder;
            setOpaque(false);
            setFont(Pal.ui(14, 0));
            setForeground(Pal.TXT);
            setCaretColor(Pal.TXT);
            setBorder(new EmptyBorder(0, 44, 0, 14));
            setPreferredSize(new Dimension(300, 44));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 16, Pal.CARD, isFocusOwner() ? Pal.ACCENT : Pal.CARD_LINE);
            g2.setColor(Pal.TXT3);
            g2.setStroke(new BasicStroke(1.6f));
            g2.drawOval(16, getHeight() / 2 - 8, 11, 11);
            g2.drawLine(25, getHeight() / 2 + 2, 30, getHeight() / 2 + 7);
            g2.dispose();
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D p = Ui.g2(g);
                p.setFont(getFont());
                p.setColor(Pal.TXT3);
                FontMetrics fm = p.getFontMetrics();
                p.drawString(placeholder, 44, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                p.dispose();
            }
        }
    }

    /** A scroll pane with thin dark bars and no border. */
    static JScrollPane scroll(JComponent c, Color bg) {
        JScrollPane sp = new JScrollPane(c, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getViewport().setBackground(bg);
        sp.setBackground(bg);
        sp.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() { thumbColor = Pal.CARD_LINE; trackColor = bg; }
            @Override protected JButton createDecreaseButton(int o) { return zero(); }
            @Override protected JButton createIncreaseButton(int o) { return zero(); }
            private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
            @Override protected void paintThumb(Graphics g, JComponent comp, Rectangle r) {
                Graphics2D g2 = Ui.g2(g);
                Ui.fillRound(g2, r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, Pal.CARD_LINE, null);
                g2.dispose();
            }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { g.setColor(bg); g.fillRect(r.x, r.y, r.width, r.height); }
        });
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(12, 0));
        sp.getVerticalScrollBar().setUnitIncrement(24);
        return sp;
    }

    static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }
}
