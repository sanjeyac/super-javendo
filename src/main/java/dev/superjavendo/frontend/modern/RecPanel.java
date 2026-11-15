package dev.superjavendo.frontend.modern;

import static dev.superjavendo.i18n.I18n.t;

import dev.superjavendo.config.AppPaths;
import dev.superjavendo.config.Settings;
import dev.superjavendo.input.GamepadDevices;
import dev.superjavendo.input.GamepadMonitor;
import dev.superjavendo.movie.Movies;
import dev.superjavendo.movie.rec.InputTimeline;
import dev.superjavendo.movie.rec.RecEngine;
import dev.superjavendo.movie.rec.RecSession;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Content of the Rec editor window: top bar, timeline, video and cards, piano roll and pad hints. It owns the
 * {@link RecEngine}; every change goes to the engine as a command, and what is drawn comes from the latest immutable
 * {@link RecEngine.Status}. Nothing here touches the console.
 */
final class RecPanel extends JPanel {
    private static final int[] SPEEDS = {1, 2, 4, 8};

    private final RecSession session;
    private final RecEngine engine;
    private final Settings settings;
    private final Movies store;
    private final String gameTitle, runName, label;
    private final Runnable onClose;
    private volatile RecEngine.Status status;
    private RecSession.Policy policy;
    private int pad;
    private boolean bannerHidden;
    private final TreeSet<Integer> markers = new TreeSet<>();
    private InputTimeline.Frozen savedInputs;
    private int savedFrontier;
    private String toast = "";
    private long toastUntil;
    private int prevPending;

    final RecTimelineBar timeline;
    final RecPianoRoll roll;
    private final RecCards.CurrentFrame current;
    private final RecCards.Step step;
    private final RecCards.EditPolicy editPolicy;
    private final RecCards.RamWatch ram;
    private final VideoView video = new VideoView();
    private final TopBar top;
    private final Footer footer = new Footer();
    private final JSplitPane split;
    private final Map<Integer, GamepadMonitor> monitors = new HashMap<>();

    /**
     * @param existingFrames frames of an opened movie: the run is brought to its end at full speed (0 for a new run)
     * @param withPad        listen to gamepads (off for screenshots)
     */
    RecPanel(RecSession session, Settings settings, Movies store, AppPaths paths, String gameTitle, String runName, String label,
             int existingFrames, boolean withPad, Runnable onClose) {
        super(new BorderLayout());
        this.session = session;
        this.settings = settings;
        this.store = store;
        this.gameTitle = gameTitle;
        this.runName = runName;
        this.label = label;
        this.onClose = onClose;
        policy = "cut".equals(settings.recPolicy) ? RecSession.Policy.CUT : RecSession.Policy.KEEP;
        session.policy(policy);
        setBackground(Pal.MAIN);
        savedInputs = session.view().inputs();
        savedFrontier = existingFrames;
        status = new RecEngine.Status(session.view(), false, 0, 0, false, 1, null);

        timeline = new RecTimelineBar(this);
        roll = new RecPianoRoll(this);
        current = new RecCards.CurrentFrame(this);
        step = new RecCards.Step(this);
        editPolicy = new RecCards.EditPolicy(this);
        ram = new RecCards.RamWatch(this);
        top = new TopBar();

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        north.add(top);
        north.add(timeline);
        add(north, BorderLayout.NORTH);

        JPanel cards = new JPanel(new CardsLayout());
        cards.setOpaque(false);
        cards.add(current); cards.add(step); cards.add(editPolicy); cards.add(ram);
        JScrollPane cardScroll = Widgets.scroll(cards, Pal.MAIN);
        JPanel work = new JPanel(null) {
            @Override public void doLayout() {
                int pad = 16, h = getHeight() - 2 * pad, vw = Math.min(h * 4 / 3, getWidth() * 45 / 100);
                video.setBounds(20, pad, vw, Math.min(h, vw * 3 / 4));
                cardScroll.setBounds(20 + vw + 16, pad, getWidth() - vw - 56, h);
            }
        };
        work.setBackground(Pal.MAIN);
        work.add(video);
        work.add(cardScroll);

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, work, roll);
        split.setBackground(Pal.MAIN);
        split.setDividerSize(6);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        split.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
            @Override public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics g) { g.setColor(Pal.SEP); g.fillRect(0, 0, getWidth(), getHeight()); }
                };
            }
        });
        split.setBorder(null);                          // after setUI: the UI installs its own border otherwise
        roll.setMinimumSize(new Dimension(100, 200));
        work.setMinimumSize(new Dimension(100, 200));
        add(split, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        bindKeys();

        engine = new RecEngine(session, st -> SwingUtilities.invokeLater(() -> accept(st)));
        engine.run(s -> s.policy(policy));
        if (existingFrames > 0) engine.runLong(existingFrames, (s, p, c) -> s.advanceTo(existingFrames, p, c));
        if (withPad) startPad(paths);
    }

    // ------------------------------------------------------------------ state

    RecEngine.Status status() { return status; }
    RecSession.View view() { return status.view(); }
    boolean disabled() { return status.busy(); }
    int pad() { return pad; }
    RecSession.Policy policy() { return policy; }
    TreeSet<Integer> markers() { return markers; }
    boolean bannerHidden() { return bannerHidden; }

    void pad(int p) { pad = p; repaintAll(); }

    private void accept(RecEngine.Status st) {
        status = st;
        RecSession.View v = st.view();
        if (v.pending() > 0 && prevPending == 0) bannerHidden = false;
        prevPending = v.pending();
        roll.follow(v.playhead());
        step.refresh(v.pending() == 0, st.playing(), st.speed(), st.busy());
        if (st.error() != null) flash(st.error());
        repaintAll();
    }

    private void repaintAll() { top.repaint(); timeline.repaint(); roll.repaint(); current.repaint(); step.repaint(); editPolicy.repaint(); ram.repaint(); video.repaint(); }

    private void flash(String text) { toast = text; toastUntil = System.currentTimeMillis() + 3500; top.repaint(); }

    private boolean dirty() { return status.view().inputs() != savedInputs || status.view().frontier() != savedFrontier; }

    // ------------------------------------------------------------------ commands (from the UI, to the engine)

    private final java.util.concurrent.atomic.AtomicInteger wantedSeek = new java.util.concurrent.atomic.AtomicInteger(-1);

    /** Moves the playhead; during a drag only the latest target is sent. */
    void requestSeek(int frame) {
        if (disabled()) return;
        if (wantedSeek.getAndSet(frame) == -1) engine.run(s -> { int t = wantedSeek.getAndSet(-1); s.seek(t); });
    }

    void advance() { if (!disabled() && !engine.playing()) engine.run(RecSession::advance); }
    void back() { seekBy(-1); }
    void seekBy(int d) { RecSession.View v = view(); requestSeek(Math.max(0, Math.min(v.firstInvalid(), v.playhead() + d))); }
    void togglePlay() { if (!disabled() && view().pending() == 0) engine.playing(!engine.playing()); }
    void cycleSpeed() { int i = 0; for (int k = 0; k < SPEEDS.length; k++) if (SPEEDS[k] == engine.speed()) i = k; engine.speed(SPEEDS[(i + 1) % SPEEDS.length]); engine.run(s -> { }); }

    void reemulate() {
        int pending = view().pending();
        if (pending <= 0 || disabled()) return;
        engine.playing(false);
        engine.runLong(pending, (s, p, c) -> s.reemulate(p, c));
    }

    void cutHere() { if (view().pending() > 0 && !disabled()) engine.run(RecSession::cut); }
    void toggle(int p, int frame, int row) { engine.run(s -> s.toggle(p, frame, InputTimeline.BUTTONS[row])); }
    void setCell(int p, int from, int to, int row, boolean on) { engine.run(s -> s.setRange(p, from, to, InputTimeline.BUTTONS[row], on)); }
    void clearRange(int p, int from, int to) { engine.run(s -> s.clearRange(p, from, to)); }
    void paste(int p, int at, int[] masks) { engine.run(s -> s.paste(p, at, masks)); }
    void insertFrame(int frame) { engine.run(s -> s.insert(frame)); }
    void deleteFrame(int frame) { engine.run(s -> s.delete(frame)); }
    void undo() { if (!disabled()) engine.run(RecSession::undo); }
    void redo() { if (!disabled()) engine.run(RecSession::redo); }
    void watch(int[] addresses) { engine.run(s -> s.watch(addresses)); }

    int[] copyRange(int p, int from, int to) {
        InputTimeline.Frozen in = view().inputs();
        int[] out = new int[Math.max(0, to - from + 1)];
        for (int i = 0; i < out.length; i++) out[i] = in.mask(p, from + i) & 0xFFF0;
        return out;
    }

    void policy(RecSession.Policy p) {
        policy = p;
        settings.recPolicy = p == RecSession.Policy.CUT ? "cut" : "keep";
        settings.save();
        engine.run(s -> s.policy(p));
        repaintAll();
    }

    void toggleMarker() {
        int f = view().playhead();
        if (!markers.remove(f)) markers.add(f);
        timeline.repaint();
    }

    // ------------------------------------------------------------------ saving and closing

    void save() {
        if (disabled()) return;
        engine.run(s -> {
            try {
                Path f = s.save(store, label);
                RecSession.View v = s.view();
                SwingUtilities.invokeLater(() -> { savedInputs = v.inputs(); savedFrontier = v.frontier(); flash(t("rec.saved", f.getFileName())); repaintAll(); });
            } catch (java.io.IOException | IllegalStateException e) {
                SwingUtilities.invokeLater(() -> flash(t("rec.savefail", e.getMessage())));
            }
        });
    }

    /** Esc / B: dismisses the re-emulate banner first; pressed again it closes. */
    void escape() {
        if (view().pending() > 0 && !bannerHidden) { bannerHidden = true; roll.repaint(); return; }
        requestClose();
    }

    void requestClose() {
        if (engine.busy()) engine.cancelLong();
        if (dirty() && view().frontier() > 0) {
            int r = JOptionPane.showConfirmDialog(this, t("rec.closeq"), t("rec.window", gameTitle), JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
            if (r == JOptionPane.YES_OPTION) {
                try {
                    engine.close();                                    // the console must not run while it is read
                    session.save(store, label);
                } catch (Exception e) { flash(t("rec.savefail", e.getMessage())); return; }
            }
        }
        shutdown();
        onClose.run();
    }

    /** Stops the engine and puts the battery RAM back. */
    void shutdown() {
        monitors.values().forEach(GamepadMonitor::close);
        engine.close();
        session.close();
    }

    // ------------------------------------------------------------------ keyboard

    private void bindKeys() {
        key(KeyEvent.VK_PERIOD, 0, this::advance);
        key(KeyEvent.VK_COMMA, 0, this::back);
        key(KeyEvent.VK_PAGE_DOWN, 0, () -> seekBy(10));
        key(KeyEvent.VK_PAGE_UP, 0, () -> seekBy(-10));
        key(KeyEvent.VK_SPACE, 0, this::togglePlay);
        key(KeyEvent.VK_R, 0, this::reemulate);
        key(KeyEvent.VK_K, Shell.shortcutMask(), this::cutHere);
        key(KeyEvent.VK_M, 0, this::toggleMarker);
        key(KeyEvent.VK_Z, Shell.shortcutMask(), this::undo);
        key(KeyEvent.VK_Y, Shell.shortcutMask(), this::redo);
        key(KeyEvent.VK_Z, Shell.shortcutMask() | java.awt.event.InputEvent.SHIFT_DOWN_MASK, this::redo);
        key(KeyEvent.VK_ESCAPE, 0, this::escape);
        key(KeyEvent.VK_F9, 0, this::save);
        key(KeyEvent.VK_S, Shell.shortcutMask(), this::save);
    }

    private void key(int code, int mods, Runnable r) {
        String name = "rec" + code + "_" + mods;
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(code, mods), name);
        getActionMap().put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { r.run(); } });
    }

    // ------------------------------------------------------------------ gamepad

    private void startPad(AppPaths paths) {
        Thread t = new Thread(() -> {
            while (true) {
                for (int d : GamepadDevices.present())
                    if (!monitors.containsKey(d)) {
                        Map<String, String> tokens = PadNav.tokensFor(paths, d);
                        monitors.put(d, new GamepadMonitor(d, 12000, (token, down) -> {
                            String b = tokens.get(token);
                            if (b != null && down) SwingUtilities.invokeLater(() -> padPress(b));
                        }));
                    }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "rec-pad");
        t.setDaemon(true);
        t.start();
    }

    private void padPress(String b) {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w == null || !w.isActive()) return;
        switch (b) {
            case "left" -> roll.moveCursor(-1, 0, false);
            case "right" -> roll.moveCursor(1, 0, false);
            case "up" -> roll.moveCursor(0, -1, false);
            case "down" -> roll.moveCursor(0, 1, false);
            case "a" -> roll.toggleCursor();
            case "b" -> escape();
            case "x" -> reemulate();
            case "y" -> cutHere();
            case "l" -> back();
            case "r" -> advance();
            case "start" -> togglePlay();
            case "select" -> pad(1 - pad);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ screenshots

    /** For screenshots: emulates {@code frames} frames (the pad of frame f presses something so the roll has content), optionally edits the past. */
    void demo(int frames, boolean edit) throws InterruptedException {
        if (view().frontier() < frames) {
            engine.run(s -> {
                for (int f = 0; f < frames; f++) {
                    if (f % 40 < 15) s.set(0, f, InputTimeline.BUTTONS[9], true);
                    if (f % 7 == 0) s.set(0, f, InputTimeline.BUTTONS[0], true);
                    if (f % 90 > 70) s.set(0, f, InputTimeline.BUTTONS[5], true);
                }
            });
            engine.runLong(frames, (s, p, c) -> s.advanceTo(frames, p, c));
        }
        waitIdle(60000, v -> v.frontier() >= frames);
        if (edit) {
            engine.run(s -> s.toggle(0, Math.max(0, frames - 60), InputTimeline.BUTTONS[2]));
            waitIdle(10000, v -> v.pending() > 0);
        }
        engine.run(s -> s.seek(Math.max(0, s.firstInvalid() - 30)));
        waitIdle(10000, v -> true);
        Thread.sleep(300);
    }

    private void waitIdle(long ms, java.util.function.Predicate<RecSession.View> done) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            RecEngine.Status st = status;
            if (!st.busy() && done.test(st.view())) return;
            Thread.sleep(50);
        }
    }

    /** Paints the panel at the given size without a window (headless screenshots). */
    BufferedImage render(int w, int h) {
        setSize(w, h);
        doLayoutDeep(this);
        split.setDividerLocation(h - 48 - 88 - 32 - 308 - 6);
        doLayoutDeep(this);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        printAll(g);
        g.dispose();
        return img;
    }

    private static void doLayoutDeep(Container c) {
        c.doLayout();
        for (Component k : c.getComponents()) if (k instanceof Container cc) doLayoutDeep(cc);
    }

    // ------------------------------------------------------------------ pieces

    /** The game picture at the playhead, 4:3, with a time label. */
    private final class VideoView extends JComponent {
        private int[] shown;
        private BufferedImage img;

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            RecSession.View v = view();
            Ui.fillRound(g2, 0, 0, getWidth(), getHeight(), 12, Color.BLACK, Pal.CARD_LINE);
            if (v.video().length > 0) {
                if (shown != v.video()) {
                    shown = v.video();
                    img = new BufferedImage(v.videoWidth(), v.videoHeight(), BufferedImage.TYPE_INT_RGB);
                    System.arraycopy(shown, 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, shown.length);
                }
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, 12, 12));
                g2.drawImage(img, 1, 1, getWidth() - 2, getHeight() - 2, null);
                g2.setClip(null);
            }
            String s = t("rec.legend.playhead") + " · " + RecFormat.time(v.playhead());
            Font f = Pal.ui(12, 1);
            FontMetrics fm = g2.getFontMetrics(f);
            Ui.fillRound(g2, 12, 12, fm.stringWidth(s) + 20, 26, 8, new Color(0x1b2029), Pal.CARD_LINE);
            Ui.text(g2, s, 22, 29, f, Pal.TXT2);
            g2.dispose();
        }
    }

    /** 48 px bar: REC pill, game and run, frontier readout, Save and Close. */
    private final class TopBar extends JComponent {
        private final Ui.Btn save = new Ui.Btn(t("rec.save"), Pal.CARD, Pal.TXT, Pal.CARD_LINE).onClick(RecPanel.this::save);
        private final Ui.Btn close = new Ui.Btn(t("rec.close"), null, Pal.TXT2, null).onClick(RecPanel.this::requestClose);

        TopBar() {
            setLayout(null);
            setPreferredSize(new Dimension(100, 48));
            add(save); add(close);
        }

        @Override public void doLayout() {
            close.setBounds(getWidth() - 20 - 64, 8, 64, 32);
            save.setBounds(getWidth() - 20 - 64 - 8 - 76, 8, 76, 32);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            int w = getWidth();
            g2.setColor(Pal.BAR);
            g2.fillRect(0, 0, w, getHeight());
            g2.setColor(Pal.SEP);
            g2.drawLine(0, getHeight() - 1, w, getHeight() - 1);
            Font pf = Pal.ui(12, 2).deriveFont(Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f));
            FontMetrics pm = g2.getFontMetrics(pf);
            String rec = t("rec.pill");
            int pw = pm.stringWidth(rec) + 44;
            Ui.fillRound(g2, 20, 11, pw, 26, 26, new Color(0x2a1a20), new Color(0x4a2630));
            g2.setColor(Pal.REC);
            g2.fillOval(32, 21, 8, 8);
            Ui.text(g2, rec, 46, 28, pf, Pal.ERR);
            int x = 20 + pw + 16;
            int tw = Ui.text(g2, Ui.ellipsize(g2.getFontMetrics(Pal.title(20)), gameTitle, Math.max(80, w / 3)), x, 31, Pal.title(20), Pal.TXT);
            Ui.text(g2, runName, x + tw + 14, 30, Pal.ui(13, 0), Pal.TXT3);
            // frontier readout, left of the buttons
            RecSession.View v = view();
            int right = w - 20 - 64 - 8 - 76 - 20;
            String val = t("rec.frame", RecFormat.num(v.frontier())) + " · " + RecFormat.time(v.frontier());
            Font vf = Pal.ui(14, 2);
            int vw = g2.getFontMetrics(vf).stringWidth(val);
            Ui.text(g2, val, right - vw, 32, vf, Pal.TXT);
            Font lf = Pal.ui(10, 2).deriveFont(Map.of(java.awt.font.TextAttribute.TRACKING, 0.12f));
            Ui.text(g2, t("rec.frontier").toUpperCase(), right - g2.getFontMetrics(lf).stringWidth(t("rec.frontier").toUpperCase()) - (vw - g2.getFontMetrics(lf).stringWidth(t("rec.frontier").toUpperCase())) , 17, lf, Pal.TXT3);
            if (System.currentTimeMillis() < toastUntil) {
                String s = Ui.ellipsize(g2.getFontMetrics(Pal.ui(13, 1)), toast, Math.max(80, right - vw - 40 - (x + tw + 14)));
                Ui.text(g2, s, right - vw - 24 - g2.getFontMetrics(Pal.ui(13, 1)).stringWidth(s), 29, Pal.ui(13, 1), Pal.OK);
            }
            g2.dispose();
        }
    }

    /** Key hints for the gamepad. */
    private static final class Footer extends JComponent {
        Footer() { setPreferredSize(new Dimension(100, 32)); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = Ui.g2(g);
            g2.setColor(Pal.BAR);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(Pal.SEP);
            g2.drawLine(0, 0, getWidth(), 0);
            String[][] hints = {{"A", "rec.h.toggle"}, {"B", "rec.h.back"}, {"LB", "rec.h.minus"}, {"RB", "rec.h.plus"},
                    {"X", "rec.h.reemu"}, {"Y", "rec.h.cut"}, {"Start", "rec.h.play"}, {"Select", "rec.h.pad"}};
            Font kf = Pal.ui(11, 2), lf = Pal.ui(12, 0);
            FontMetrics km = g2.getFontMetrics(kf);
            int x = 20;
            for (String[] h : hints) {
                int kw = Math.max(18, km.stringWidth(h[0]) + 12);
                Ui.fillRound(g2, x, 7, kw, 18, 18, Pal.CARD, Pal.CARD_LINE);
                Ui.text(g2, h[0], x + (kw - km.stringWidth(h[0])) / 2, 20, kf, Pal.TXT);
                x += kw + 8;
                x += Ui.text(g2, t(h[1]), x, 20, lf, Pal.TXT2) + 22;
            }
            g2.dispose();
        }
    }

    /** One or two columns of cards depending on the width; the height follows the content. */
    private static final class CardsLayout implements LayoutManager {
        private static final int GAP = 16;
        @Override public void addLayoutComponent(String n, Component c) { }
        @Override public void removeLayoutComponent(Component c) { }
        private static int columns(int width) { return width >= 700 ? 2 : 1; }
        @Override public Dimension preferredLayoutSize(Container p) { return new Dimension(300, height(p, p.getWidth() > 0 ? p.getWidth() : 700)); }
        @Override public Dimension minimumLayoutSize(Container p) { return new Dimension(200, 100); }
        private static int height(Container p, int width) {
            int cols = columns(width), y = 0, n = p.getComponentCount();
            for (int i = 0; i < n; i += cols) {
                int rowH = 0;
                for (int j = i; j < Math.min(n, i + cols); j++) rowH = Math.max(rowH, p.getComponent(j).getPreferredSize().height);
                y += rowH + (i + cols < n ? GAP : 0);
            }
            return y;
        }
        @Override public void layoutContainer(Container p) {
            int w = p.getWidth(), cols = columns(w), cw = (w - (cols - 1) * GAP) / cols, y = 0, n = p.getComponentCount();
            for (int i = 0; i < n; i += cols) {
                int rowH = 0;
                for (int j = i; j < Math.min(n, i + cols); j++) rowH = Math.max(rowH, p.getComponent(j).getPreferredSize().height);
                for (int j = i; j < Math.min(n, i + cols); j++) p.getComponent(j).setBounds((j - i) * (cw + GAP), y, cw, rowH);
                y += rowH + GAP;
            }
            p.setPreferredSize(new Dimension(w, height(p, w)));
        }
    }
}
