package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.movie.rec.InputTimeline;
import dev.superjavendo.movie.rec.RecSession;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * The inputs of one pad: rows = the twelve buttons, columns = frames. Cells are drawn according to what the frame is:
 * emulated, lag, planned (right of the frontier) or waiting to be re-emulated. Click, drag and keys edit the inputs.
 */
final class RecPianoRoll extends JComponent {
    static final int LABEL_W = 72, SIDE = 20, STEP_X = 12, STEP_Y = 18, CELL_W = 11, CELL_H = 16, GROUP_GAP = 6;
    private static final int TOOLBAR = 36, RULER = 22;
    private static final int[] GROUP_START = {0, 4, 6, 10};         // B Y A X | L R | Up Down Left Right | Start Select

    private final RecPanel panel;
    private int first;                           // first visible frame
    private int curFrame, curRow;                // keyboard cursor
    private int selFrom = -1, selTo = -1;        // selected frames (inclusive), -1 = none
    private int hoverFrame = -1, hoverRow = -1;
    private int dragRow = -1, dragLast, dragValue;
    private int[] clip = new int[0];
    private Rectangle pad1Rect = new Rectangle(), pad2Rect = new Rectangle(), reemuRect = new Rectangle(), cutRect = new Rectangle();

    RecPianoRoll(RecPanel panel) {
        this.panel = panel;
        setFocusable(true);
        setPreferredSize(new Dimension(100, 308));
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); press(e); }
            @Override public void mouseDragged(MouseEvent e) { drag(e); }
            @Override public void mouseReleased(MouseEvent e) { dragRow = -1; }
            @Override public void mouseMoved(MouseEvent e) { hover(e); }
            @Override public void mouseExited(MouseEvent e) { hoverFrame = hoverRow = -1; repaint(); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { scrollTo(first + e.getWheelRotation() * 10); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
        addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { repaint(); }
            @Override public void focusLost(FocusEvent e) { repaint(); }
        });
        bindKeys();
    }

    // ------------------------------------------------------------------ view

    int firstFrame() { return first; }
    int visibleFrames() { return Math.max(1, (getWidth() - 2 * SIDE - LABEL_W) / STEP_X); }
    int cursorFrame() { return curFrame; }
    int cursorRow() { return curRow; }
    int selectionFrom() { return selFrom; }
    int selectionTo() { return selTo; }

    void scrollTo(int frame) {
        int max = Math.max(0, panel.view().inputs().length() + visibleFrames());
        first = Math.max(0, Math.min(frame, Math.max(max, panel.view().frontier() + visibleFrames())));
        repaint();
        panel.timeline.repaint();
    }

    /** Keeps the playhead in view (called when it moves). */
    void follow(int frame) {
        int n = visibleFrames();
        if (frame < first || frame >= first + n) scrollTo(Math.max(0, frame - n / 2));
    }

    private int rowY(int row) {
        int group = 0;
        for (int i = 0; i < GROUP_START.length; i++) if (row >= GROUP_START[i]) group = i;
        return TOOLBAR + RULER + row * STEP_Y + group * GROUP_GAP + 4;
    }

    private int colX(int frame) { return SIDE + LABEL_W + (frame - first) * STEP_X; }

    private int frameAt(int px) { return px < SIDE + LABEL_W ? -1 : first + (px - SIDE - LABEL_W) / STEP_X; }

    private int rowAt(int py) {
        for (int r = 0; r < 12; r++) if (py >= rowY(r) && py < rowY(r) + STEP_Y) return r;
        return -1;
    }

    // ------------------------------------------------------------------ mouse

    private void press(MouseEvent e) {
        if (pad1Rect.contains(e.getPoint())) { panel.pad(0); return; }
        if (pad2Rect.contains(e.getPoint())) { panel.pad(1); return; }
        if (reemuRect.contains(e.getPoint())) { panel.reemulate(); return; }
        if (cutRect.contains(e.getPoint())) { panel.cutHere(); return; }
        if (panel.disabled()) return;
        if (e.getY() < TOOLBAR + RULER) {                                  // ruler: move the playhead (only into the emulated part)
            if (e.getY() >= TOOLBAR) { int f = frameAt(e.getX()); if (f >= 0) panel.requestSeek(f); }
            return;
        }
        int f = frameAt(e.getX()), r = rowAt(e.getY());
        if (f < 0 || r < 0) return;
        curFrame = f; curRow = r;
        if (e.isShiftDown() && selFrom >= 0) { selTo = f; repaint(); return; }
        selFrom = selTo = -1;
        boolean on = (panel.view().inputs().mask(panel.pad(), f) & InputTimeline.BUTTONS[r]) != 0;
        dragRow = r; dragLast = f; dragValue = on ? 0 : 1;
        panel.setCell(panel.pad(), f, f, r, dragValue == 1);
        repaint();
    }

    private void drag(MouseEvent e) {
        if (dragRow < 0 || panel.disabled()) return;
        int f = frameAt(e.getX());
        if (f < 0 || f == dragLast) return;
        panel.setCell(panel.pad(), Math.min(f, dragLast + (f > dragLast ? 1 : -1)), Math.max(f, dragLast + (f > dragLast ? 1 : -1)), dragRow, dragValue == 1);
        dragLast = f;
        curFrame = f;
        repaint();
    }

    private void hover(MouseEvent e) {
        int f = frameAt(e.getX()), r = rowAt(e.getY());
        if (f != hoverFrame || r != hoverRow) { hoverFrame = f; hoverRow = r; repaint(); }
    }

    // ------------------------------------------------------------------ keyboard (cursor, selection, clipboard)

    private void bindKeys() {
        key(KeyEvent.VK_LEFT, 0, () -> moveCursor(-1, 0, false));
        key(KeyEvent.VK_RIGHT, 0, () -> moveCursor(1, 0, false));
        key(KeyEvent.VK_UP, 0, () -> moveCursor(0, -1, false));
        key(KeyEvent.VK_DOWN, 0, () -> moveCursor(0, 1, false));
        key(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK, () -> moveCursor(-1, 0, true));
        key(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK, () -> moveCursor(1, 0, true));
        key(KeyEvent.VK_ENTER, 0, this::toggleCursor);
        key(KeyEvent.VK_DELETE, 0, this::clearSelection);
        key(KeyEvent.VK_DELETE, Shell.shortcutMask(), () -> { if (!panel.disabled()) panel.deleteFrame(curFrame); });
        key(KeyEvent.VK_INSERT, 0, () -> { if (!panel.disabled()) panel.insertFrame(curFrame); });
        key(KeyEvent.VK_C, Shell.shortcutMask(), this::copy);
        key(KeyEvent.VK_V, Shell.shortcutMask(), this::paste);
    }

    private void key(int code, int mods, Runnable r) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(code, mods), "k" + code + "_" + mods);
        getActionMap().put("k" + code + "_" + mods, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { r.run(); } });
    }

    void moveCursor(int df, int dr, boolean extend) {
        if (extend && selFrom < 0) selFrom = curFrame;
        curFrame = Math.max(0, curFrame + df);
        curRow = Math.max(0, Math.min(11, curRow + dr));
        if (extend) selTo = curFrame; else selFrom = selTo = -1;
        if (curFrame < first) scrollTo(curFrame);
        else if (curFrame >= first + visibleFrames()) scrollTo(curFrame - visibleFrames() + 1);
        repaint();
    }

    void toggleCursor() {
        if (panel.disabled()) return;
        panel.toggle(panel.pad(), curFrame, curRow);
    }

    private void clearSelection() {
        if (panel.disabled()) return;
        int a = selFrom < 0 ? curFrame : Math.min(selFrom, selTo), b = selFrom < 0 ? curFrame : Math.max(selFrom, selTo);
        panel.clearRange(panel.pad(), a, b);
    }

    private void copy() {
        int a = selFrom < 0 ? curFrame : Math.min(selFrom, selTo), b = selFrom < 0 ? curFrame : Math.max(selFrom, selTo);
        clip = panel.copyRange(panel.pad(), a, b);
    }

    private void paste() {
        if (panel.disabled() || clip.length == 0) return;
        panel.paste(panel.pad(), curFrame, clip);
    }

    // ------------------------------------------------------------------ painting

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = Ui.g2(g);
        int w = getWidth();
        g2.setColor(Pal.SIDE);
        g2.fillRect(0, 0, w, getHeight());
        RecSession.View v = panel.view();
        boolean off = panel.disabled();
        int n = visibleFrames(), pad = panel.pad();
        toolbar(g2, w, v, n);
        if (off) g2.setComposite(AlphaComposite.SrcOver.derive(0.4f));
        ruler(g2, v, n);
        Font lf = Pal.ui(12, 0);
        FontMetrics lm = g2.getFontMetrics(lf);
        for (int r = 0; r < 12; r++) {
            String name = InputTimeline.BUTTON_NAMES[r];
            Ui.text(g2, name, SIDE + LABEL_W - 10 - lm.stringWidth(name), rowY(r) + 13, lf, Pal.TXT2);
        }
        // selection behind the cells
        if (selFrom >= 0) {
            int a = Math.min(selFrom, selTo), b = Math.max(selFrom, selTo);
            int x0 = colX(a), x1 = colX(b + 1);
            g2.setColor(Pal.ROW_SEL);
            g2.fillRect(x0, rowY(0) - 2, x1 - x0, rowY(11) + STEP_Y - rowY(0) + 2);
        }
        for (int i = 0; i < n; i++) {
            int f = first + i;
            int x = colX(f);
            int mask = v.inputs().mask(pad, f);
            boolean lagged = f < v.firstInvalid() && v.lag().get(f);
            for (int r = 0; r < 12; r++) cell(g2, v, f, r, x, rowY(r), (mask & InputTimeline.BUTTONS[r]) != 0, lagged);
        }
        // playhead column outline, frontier line, selection outline
        if (v.playhead() >= first && v.playhead() < first + n) {
            int x = colX(v.playhead());
            g2.setColor(Pal.ACCENT_L);
            for (int r = 0; r < 12; r++) g2.drawRect(x, rowY(r), CELL_W - 1, CELL_H - 1);
        }
        if (v.frontier() >= first && v.frontier() <= first + n) {
            g2.setColor(Pal.TXT);
            g2.fillRect(colX(v.frontier()) - 2, TOOLBAR + RULER - 2, 2, rowY(11) + STEP_Y - TOOLBAR - RULER + 2);
        }
        if (selFrom >= 0) {
            int a = Math.min(selFrom, selTo), b = Math.max(selFrom, selTo);
            g2.setColor(Pal.ACCENT_L);
            g2.drawRect(colX(a) - 1, rowY(0) - 3, colX(b + 1) - colX(a), rowY(11) + STEP_Y - rowY(0) + 3);
        }
        if (isFocusOwner() && curFrame >= first && curFrame < first + n) {
            g2.setColor(Pal.ACCENT_L);
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(colX(curFrame) - 1, rowY(curRow) - 1, CELL_W + 1, CELL_H + 1);
            g2.setStroke(new BasicStroke(1f));
        }
        g2.dispose();
    }

    private void cell(Graphics2D g2, RecSession.View v, int f, int r, int x, int y, boolean on, boolean lagged) {
        boolean stale = f >= v.firstInvalid() && f < v.frontier();
        boolean planned = f >= v.frontier();
        Color fill, line = null;
        if (planned) { fill = on ? Pal.PLANNED_ON : Pal.MAIN; if (!on) line = Pal.CARD; }
        else if (stale) { fill = on ? Pal.ACCENT : Pal.INVALID_BG; line = on ? Pal.AMBER : Pal.INVALID_LINE; }
        else if (on) fill = Pal.ACCENT;
        else fill = lagged ? Pal.SEP : Pal.CARD;
        if (!on && f == hoverFrame && r == hoverRow && !panel.disabled()) fill = Pal.ROW_SEL;
        Ui.fillRound(g2, x, y, CELL_W, CELL_H, 4, fill, line);
    }

    private void ruler(Graphics2D g2, RecSession.View v, int n) {
        Font f = Pal.ui(11, 0);
        int y0 = TOOLBAR;
        for (int i = 0; i < n; i++) {
            int fr = first + i, x = colX(fr);
            if (fr % 10 == 0) Ui.text(g2, RecFormat.num(fr), x - 4, y0 + 12, f, Pal.TXT3);
            if (fr < v.firstInvalid() && v.lag().get(fr)) { g2.setColor(Pal.TXT3); g2.fillRect(x, y0 + RULER - 5, 11, 3); }
        }
        if (v.playhead() >= first && v.playhead() < first + n) {
            int cx = colX(v.playhead()) + CELL_W / 2;
            g2.setColor(Pal.ACCENT_L);
            g2.fillPolygon(new int[]{cx - 5, cx + 5, cx}, new int[]{y0 + RULER - 8, y0 + RULER - 8, y0 + RULER - 1}, 3);
        }
    }

    private void toolbar(Graphics2D g2, int w, RecSession.View v, int n) {
        Font f = Pal.ui(13, 1);
        FontMetrics fm = g2.getFontMetrics(f);
        int y = 6, h = 24, x = SIDE;
        for (int p = 0; p < 2; p++) {
            String label = t("rec.pad", p + 1);
            int bw = fm.stringWidth(label) + 28;
            Rectangle r = new Rectangle(x, y, bw, h);
            if (p == 0) pad1Rect = r; else pad2Rect = r;
            boolean sel = panel.pad() == p;
            Ui.fillRound(g2, r.x, r.y, r.width, r.height, 8, sel ? Pal.ACTIVE : Pal.CARD, sel ? Pal.ACCENT_L : Pal.CARD_LINE);
            Ui.text(g2, label, r.x + 14, r.y + 17, f, sel ? Pal.TXT : Pal.TXT2);
            x += bw + 4;
        }
        x += 12;
        String info = t("rec.frames", RecFormat.num(first), RecFormat.num(first + n - 1));
        Ui.text(g2, info, x, y + 17, Pal.ui(13, 0), Pal.TXT3);
        reemuRect = cutRect = new Rectangle();
        int pending = v.pending();
        if (pending > 0) {
            String msg = t("rec.edited", RecFormat.num(v.firstInvalid()), RecFormat.num(pending));
            Font bf = Pal.ui(13, 0), btf = Pal.ui(13, 2);
            FontMetrics bm = g2.getFontMetrics(bf), tm = g2.getFontMetrics(btf);
            String re = t("rec.reemulate"), cu = t("rec.cuthere");
            int reW = tm.stringWidth(re) + 24, cuW = tm.stringWidth(cu) + 24;
            int total = 14 + bm.stringWidth(msg) + 14 + reW + 6 + cuW + 6;
            int bx = w - SIDE - total, by = 3;
            Ui.fillRound(g2, bx, by, total, 30, 8, Pal.INVALID_BG, Pal.INVALID_LINE);
            Ui.text(g2, msg, bx + 14, by + 20, bf, Pal.AMBER);
            int rx = bx + 14 + bm.stringWidth(msg) + 14;
            reemuRect = new Rectangle(rx, by + 4, reW, 22);
            Ui.fillRound(g2, reemuRect.x, reemuRect.y, reW, 22, 6, Pal.PLAY, null);
            Ui.text(g2, re, rx + 12, by + 19, btf, Pal.PLAY_TXT);
            cutRect = new Rectangle(rx + reW + 6, by + 4, cuW, 22);
            Ui.fillRound(g2, cutRect.x, cutRect.y, cuW, 22, 6, new Color(0x4a2630), null);
            Ui.text(g2, cu, cutRect.x + 12, by + 19, btf, Pal.ERR);
        } else {
            String hint = t("rec.hint.cells");
            Font hf = Pal.ui(12, 0);
            FontMetrics hm = g2.getFontMetrics(hf);
            Ui.text(g2, Ui.ellipsize(hm, hint, Math.max(40, w - x - fm.stringWidth(info) - 2 * SIDE)), w - SIDE - Math.min(hm.stringWidth(hint), Math.max(40, w - x - fm.stringWidth(info) - 2 * SIDE)), y + 17, hf, Pal.TXT3);
        }
    }
}
