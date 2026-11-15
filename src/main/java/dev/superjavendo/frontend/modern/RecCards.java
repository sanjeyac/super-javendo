package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.movie.rec.InputTimeline;
import dev.superjavendo.movie.rec.RecSession;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** The four cards of the work area: current frame, step, edit policy and RAM watch. */
final class RecCards {
    private RecCards() { }

    /** A card: rounded CARD background with an uppercase title; subclasses paint the content from y = 44. */
    abstract static class Card extends JPanel {
        private final String title;
        Card(String title, int height) {
            this.title = title;
            setOpaque(false);
            setPreferredSize(new Dimension(300, height));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 16, Pal.CARD, Pal.CARD_LINE);
            Ui.label(g2, title, 16, 28);
            content(g2, getWidth() - 32);
            g2.dispose();
        }
        abstract void content(Graphics2D g2, int cw);
    }

    /** Draws wrapped text; returns the y below the last line. */
    static int wrap(Graphics2D g2, String text, int x, int y, int w, Font f, Color c) {
        FontMetrics fm = g2.getFontMetrics(f);
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(trial) > w && line.length() > 0) { Ui.text(g2, line.toString(), x, y, f, c); y += fm.getHeight(); line = new StringBuilder(word); }
            else { line = new StringBuilder(trial); }
        }
        if (line.length() > 0) { Ui.text(g2, line.toString(), x, y, f, c); y += fm.getHeight(); }
        return y;
    }

    // ------------------------------------------------------------------ CURRENT FRAME

    static final class CurrentFrame extends Card {
        private final RecPanel panel;
        CurrentFrame(RecPanel panel) { super(t("rec.current"), 194); this.panel = panel; }

        @Override void content(Graphics2D g2, int cw) {
            RecSession.View v = panel.view();
            int x = 16, y = 70;
            String num = RecFormat.num(v.playhead());
            int nw = Ui.text(g2, num, x, y, Pal.title(26), Pal.TXT);
            Ui.text(g2, RecFormat.time(v.playhead()), x + nw + 12, y, Pal.ui(14, 0), Pal.TXT2);
            int gap = 8, pw = (cw - 5 * gap) / 6, ph = 24;
            int mask = v.inputs().mask(panel.pad(), v.playhead());
            Font f = Pal.ui(12, 1);
            FontMetrics fm = g2.getFontMetrics(f);
            for (int i = 0; i < 12; i++) {
                int px = x + (i % 6) * (pw + gap), py = 86 + (i / 6) * (ph + 8);
                boolean on = (mask & InputTimeline.BUTTONS[i]) != 0;
                Ui.fillRound(g2, px, py, pw, ph, 10, on ? Pal.ACCENT : Pal.MAIN, on ? null : Pal.CARD_LINE);
                String s = InputTimeline.BUTTON_NAMES[i];
                Ui.text(g2, s, px + (pw - fm.stringWidth(s)) / 2, py + 16, f, on ? Color.WHITE : Pal.TXT3);
            }
            Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(Pal.ui(12, 0)), t("rec.pad.hint", panel.pad() + 1), cw), x, 170, Pal.ui(12, 0), Pal.TXT3);
        }
    }

    // ------------------------------------------------------------------ STEP

    static final class Step extends Card {
        private final RecPanel panel;
        private final Ui.Btn back, advance, play, speed;

        Step(RecPanel panel) {
            super(t("rec.step"), 194);
            this.panel = panel;
            setLayout(null);
            back = new Ui.Btn(t("rec.back"), Pal.MAIN, Pal.TXT, Pal.CARD_LINE).onClick(panel::back);
            advance = new Ui.Btn(t("rec.advance"), Pal.ACCENT, Color.WHITE, null).onClick(panel::advance);
            play = new Ui.Btn("▶  " + t("rec.play"), Pal.MAIN, Pal.TXT, Pal.CARD_LINE).onClick(panel::togglePlay);
            speed = new Ui.Btn(t("rec.speed", 1), Pal.MAIN, Pal.TXT, Pal.CARD_LINE).onClick(panel::cycleSpeed);
            for (Ui.Btn b : new Ui.Btn[]{back, advance, play, speed}) { b.setFocusable(true); add(b); }
        }

        @Override public void doLayout() {
            int y = 44, h = 40;
            back.setBounds(16, y, 96, h);
            advance.setBounds(120, y, 130, h);
            play.setBounds(16, y + 52, 100, h);
            speed.setBounds(124, y + 52, 110, h);
        }

        void refresh(boolean canAdvance, boolean playing, int speedX, boolean disabled) {
            advance.setEnabled(canAdvance && !disabled);
            if (canAdvance && !disabled) advance.setColors(Pal.ACCENT, Color.WHITE, null); else advance.setColors(Pal.SEP, Pal.TXT3, null);
            back.setEnabled(!disabled);
            play.setText((playing ? "❚❚  " + t("rec.pause") : "▶  " + t("rec.play")));
            speed.setText(t("rec.speed", speedX));
            repaint();
        }

        @Override void content(Graphics2D g2, int cw) {
            wrap(g2, t("rec.step.help"), 16, 158, cw, Pal.ui(12, 0), Pal.TXT3);
        }
    }

    // ------------------------------------------------------------------ AFTER AN EDIT

    static final class EditPolicy extends Card {
        private final RecPanel panel;
        private Rectangle keepRect = new Rectangle(), cutRect = new Rectangle();

        EditPolicy(RecPanel panel) {
            super(t("rec.after"), 160);
            this.panel = panel;
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (keepRect.contains(e.getPoint())) panel.policy(RecSession.Policy.KEEP);
                    else if (cutRect.contains(e.getPoint())) panel.policy(RecSession.Policy.CUT);
                }
            });
        }

        @Override void content(Graphics2D g2, int cw) {
            int x = 16, y = 44, h = 36, sw = cw / 2;
            Ui.fillRound(g2, x, y, cw, h, 10, Pal.MAIN, Pal.CARD_LINE);
            keepRect = new Rectangle(x, y, sw, h);
            cutRect = new Rectangle(x + sw, y, cw - sw, h);
            boolean keep = panel.policy() == RecSession.Policy.KEEP;
            Font f = Pal.ui(13, 1);
            FontMetrics fm = g2.getFontMetrics(f);
            Rectangle sel = keep ? keepRect : cutRect;
            Ui.fillRound(g2, sel.x, sel.y, sel.width, sel.height, 10, Pal.ACTIVE, Pal.ACCENT_L);
            String a = t("rec.keep"), b = t("rec.cut");
            Ui.text(g2, Ui.ellipsize(fm, a, sw - 12), x + Math.max(6, (sw - fm.stringWidth(a)) / 2), y + 23, f, keep ? Pal.TXT : Pal.TXT2);
            Ui.text(g2, Ui.ellipsize(fm, b, sw - 12), x + sw + Math.max(6, (sw - fm.stringWidth(b)) / 2), y + 23, f, keep ? Pal.TXT2 : Pal.TXT);
            wrap(g2, t(keep ? "rec.keep.help" : "rec.cut.help"), x, y + 58, cw, Pal.ui(12, 0), Pal.TXT3);
        }
    }

    // ------------------------------------------------------------------ RAM WATCH

    static final class RamWatch extends Card {
        private final RecPanel panel;
        private final List<Integer> addresses = new ArrayList<>();
        private Rectangle addRect = new Rectangle();
        private final List<Rectangle> removeRects = new ArrayList<>();

        RamWatch(RecPanel panel) {
            super(t("rec.ram"), 160);
            this.panel = panel;
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (addRect.contains(e.getPoint())) add();
                    for (int i = 0; i < removeRects.size(); i++) if (removeRects.get(i).contains(e.getPoint())) { addresses.remove(i); changed(); return; }
                }
            });
        }

        int[] addresses() { return addresses.stream().mapToInt(Integer::intValue).toArray(); }

        private void add() {
            String in = JOptionPane.showInputDialog(this, t("rec.addr.prompt"), t("rec.ram"), JOptionPane.PLAIN_MESSAGE);
            if (in == null) return;
            try {
                int a = Integer.parseInt(in.trim().replaceFirst("^(\\$|0[xX])", ""), 16);
                if (a >= 0 && a < 0x20000 && !addresses.contains(a) && addresses.size() < 4) { addresses.add(a); changed(); }
            } catch (NumberFormatException ignored) { }
        }

        private void changed() { panel.watch(addresses()); repaint(); }

        @Override void content(Graphics2D g2, int cw) {
            RecSession.View v = panel.view();
            int y = 56;
            removeRects.clear();
            Font f = Pal.ui(13, 0), mono = Pal.mono(13);
            for (int i = 0; i < addresses.size(); i++) {
                Ui.text(g2, String.format("$%04X", addresses.get(i)), 16, y, f, Pal.TXT2);
                int val = i < v.ram().length ? v.ram()[i] : 0;
                String s = String.format("0x%02X  (%d)", val, val);
                FontMetrics fm = g2.getFontMetrics(mono);
                Ui.text(g2, s, 16 + cw - 18 - fm.stringWidth(s), y, mono, Pal.TXT);
                Ui.text(g2, "×", 16 + cw - 8, y, f, Pal.TXT3);
                removeRects.add(new Rectangle(16 + cw - 14, y - 12, 18, 16));
                g2.setColor(Pal.SEP);
                g2.drawLine(16, y + 8, 16 + cw, y + 8);
                y += 26;
            }
            int by = Math.max(y - 8, 44), bh = 34;
            if (by + bh > getHeight() - 8) by = getHeight() - bh - 8;
            addRect = new Rectangle(16, by, cw, bh);
            Ui.dashedRound(g2, 16, by, cw, bh, 10, Pal.CARD_LINE);
            Font bf = Pal.ui(13, 1);
            String s = t("rec.addaddr");
            Ui.text(g2, s, 16 + (cw - g2.getFontMetrics(bf).stringWidth(s)) / 2, by + 22, bf, Pal.TXT2);
        }
    }
}
