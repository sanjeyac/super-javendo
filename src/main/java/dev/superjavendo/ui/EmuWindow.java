package dev.superjavendo.ui;

import dev.superjavendo.Snes;
import dev.superjavendo.config.AppPaths;
import dev.superjavendo.input.ControllerConfig;
import dev.superjavendo.input.ControllerFactory;
import dev.superjavendo.input.ControllerInput;
import dev.superjavendo.input.ControllerPorts;
import dev.superjavendo.ppu.Ppu;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import dev.superjavendo.i18n.I18n;
import java.nio.file.Path;

/** Swing front-end: video, keyboard input, audio, 60 fps pacing. */
public final class EmuWindow extends JFrame {
    private static final double FRAME_NANOS = 1e9 / 60.0988;

    private volatile Snes snes;
    private volatile boolean resetRequest;

    /** How the 256x224 frame is scaled to the window; F cycles through them. */
    private enum Filter {
        NEAREST, SHARP, SMOOTH;
        String label() { return dev.superjavendo.i18n.I18n.t("game.filter." + name().toLowerCase()); }
    }
    private volatile Filter filter = Filter.SHARP;
    private BufferedImage prescaleBuf;
    private int prescaleK;

    /**
     * "Sharp bilinear": enlarge by the smallest whole factor that covers the target width with nearest-neighbour
     * (keeps pixel edges crisp), so the final bilinear step only has to smooth a fractional remainder.
     */
    private BufferedImage prescaled(int targetWidth) {
        BufferedImage image = this.image;
        int k = Math.max(1, (int) Math.ceil(targetWidth / (double) image.getWidth()));
        if (k == 1) return image;
        if (prescaleBuf == null || prescaleK != k || prescaleBuf.getWidth() != image.getWidth() * k || prescaleBuf.getHeight() != image.getHeight() * k) {
            prescaleBuf = new BufferedImage(image.getWidth() * k, image.getHeight() * k, BufferedImage.TYPE_INT_RGB);
            prescaleK = k;
        }
        Graphics2D pg = prescaleBuf.createGraphics();
        pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        pg.drawImage(image, 0, 0, prescaleBuf.getWidth(), prescaleBuf.getHeight(), null);
        pg.dispose();
        return prescaleBuf;
    }
    private final Path sramFile;
    private final java.util.function.IntFunction<Path> stateFiles;
    private int prevActions;
    private volatile int stateRequest; // 0 none, 1 save, 2 load
    private volatile String toast = "";
    private volatile long toastUntil;
    /** The frame being shown. Hi-res and interlaced frames are bigger (512 wide, up to 448 rows): the image is replaced when the size changes. */
    private volatile BufferedImage image = new BufferedImage(Ppu.WIDTH, Ppu.HEIGHT, BufferedImage.TYPE_INT_RGB);
    private final AudioOut audio = new AudioOut();
    private final ControllerPorts ports = new ControllerPorts();
    private volatile boolean running = true, paused;

    public EmuWindow(Snes snes, Path sramFile, java.util.function.IntFunction<Path> stateFiles,
                     java.util.Map<Integer, String> profileOverrides, boolean exitOnClose) {
        super("SuperJavendo - " + snes.cart.title);
        this.snes = snes;
        this.sramFile = sramFile;
        this.stateFiles = stateFiles;
        dev.superjavendo.config.Settings settings = dev.superjavendo.config.Settings.load(AppPaths.forCurrentUser());
        dev.superjavendo.i18n.I18n.set(settings.language);
        filter = Filter.valueOf(settings.filter.toUpperCase());
        audio.setVolume(settings.volume);
        snes.bus.apu.setSink(audio);
        // Profiles and player assignments live in ~/.config/superjavendo (joystick/*.cfg, controllers.cfg).
        ControllerConfig.configure(ports, new ControllerFactory(), AppPaths.forCurrentUser(), profileOverrides);

        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                int w = getWidth(), h = getHeight();
                double aspect = 256.0 / 224.0;
                int dw = w, dh = (int) (w / aspect);
                if (dh > h) { dh = h; dw = (int) (h * aspect); }
                BufferedImage src = image;       // always shown at 4:3 of the 256x224 picture, whatever its resolution
                Object hint = RenderingHints.VALUE_INTERPOLATION_BILINEAR;
                if (filter == Filter.NEAREST) hint = RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR;
                else if (filter == Filter.SHARP) src = prescaled(dw);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
                if (System.currentTimeMillis() < toastUntil) {
                    g.setFont(g.getFont().deriveFont(Font.BOLD, 16f));
                    FontMetrics fm = g.getFontMetrics();
                    int tw = fm.stringWidth(toast);
                    g.setColor(new Color(0, 0, 0, 170));
                    g.fillRoundRect(10, 10, tw + 16, fm.getHeight() + 8, 10, 10);
                    g.setColor(Color.WHITE);
                    g.drawString(toast, 18, 10 + 4 + fm.getAscent());
                }
            }
        };
        panel.setPreferredSize(new Dimension(Ppu.WIDTH * settings.scale, Ppu.HEIGHT * settings.scale));
        panel.setBackground(Color.BLACK);
        setContentPane(panel);
        fullscreen = settings.fullscreen;
        if (fullscreen) setUndecorated(true);                  // must be decided before the window is displayable
        pack();
        setLocationRelativeTo(null);
        setDefaultCloseOperation(exitOnClose ? EXIT_ON_CLOSE : DISPOSE_ON_CLOSE);

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int slot = e.getKeyCode() - KeyEvent.VK_0;
                if (slot >= 1 && slot <= AppPaths.MAX_SLOT) { stateRequest = (slot << 2) | (e.isShiftDown() ? 2 : 1); return; }
                if (ports.onKey(e.getKeyCode(), true)) return;
                if (e.getKeyCode() == KeyEvent.VK_F) {
                    filter = Filter.values()[(filter.ordinal() + 1) % Filter.values().length];
                    show(I18n.t("game.filter", filter.label()));
                } else if (e.getKeyCode() == KeyEvent.VK_P) paused = !paused;
                else if (e.getKeyCode() == KeyEvent.VK_F11) setFullscreen(!fullscreen);
                else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { if (fullscreen) setFullscreen(false); else dispose(); }
            }
            @Override public void keyReleased(KeyEvent e) {
                ports.onKey(e.getKeyCode(), false);
            }
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { if (!switching) shutdown(); }
            @Override public void windowClosed(java.awt.event.WindowEvent e) { if (!switching) shutdown(); }
        });
        setVisible(true);
        if (fullscreen) enterFullscreenDevice();
        requestFocus();
        Thread t = new Thread(this::loop, "emulation");
        t.setDaemon(true);
        t.start();
    }

    private boolean fullscreen;
    private volatile boolean switching;

    /** True while the window is being re-created to enter or leave full screen: its close events are not a real close. */
    public boolean isSwitching() { return switching; }

    private static final Cursor BLANK_CURSOR = Toolkit.getDefaultToolkit().createCustomCursor(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");

    private void enterFullscreenDevice() {
        GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().setFullScreenWindow(this);
        getContentPane().setCursor(BLANK_CURSOR);
        requestFocus();
    }

    /** Enters or leaves full screen (event thread). The window must be re-created because decorations cannot change while displayed. */
    public void setFullscreen(boolean on) {
        if (on == fullscreen) return;
        GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        switching = true;
        if (on) {
            dispose();
            setUndecorated(true);
            fullscreen = true;
            setVisible(true);
            enterFullscreenDevice();
        } else {
            dev.setFullScreenWindow(null);
            dispose();
            setUndecorated(false);
            fullscreen = false;
            getContentPane().setCursor(Cursor.getDefaultCursor());
            pack();
            setLocationRelativeTo(null);
            setVisible(true);
            requestFocus();
        }
        SwingUtilities.invokeLater(() -> switching = false);   // the close event of the old window is queued before this
    }

    /** Loads a save state (0 = quick slot) on the emulation thread, as if the load key had been pressed. */
    public void requestLoadSlot(int slot) { stateRequest = (slot << 2) | 2; }

    /** Power-cycles the console (battery RAM is kept). Applied on the emulation thread. */
    public void requestReset() { resetRequest = true; }

    private void shutdown() {
        if (!running) return;
        running = false;
        ports.close();
        snes.cart.saveSram(sramFile);
        audio.close();
    }

    private void loop() {
        long next = System.nanoTime();
        int fps = 0;
        long fpsT = System.nanoTime();
        while (running) {
            if (resetRequest) {
                resetRequest = false;
                Snes fresh = new Snes(snes.cart);
                fresh.bus.apu.setSink(audio);
                snes = fresh;
                show(I18n.t("game.reset"));
            }
            int act = ports.actions(), rising = act & ~prevActions;
            prevActions = act;
            if ((rising & ControllerInput.SAVE_STATE) != 0) stateRequest = 1;       // slot 0
            else if ((rising & ControllerInput.LOAD_STATE) != 0) stateRequest = 2;
            int req = stateRequest;
            if (req != 0) { stateRequest = 0; handleState(req); }
            if (paused) { sleep(20); next = System.nanoTime(); SwingUtilities.invokeLater(this::repaint); continue; }
            ports.apply(snes.joypad);
            snes.runFrame();
            present();
            audio.flush();
            SwingUtilities.invokeLater(this::repaint);
            fps++;
            long now = System.nanoTime();
            if (now - fpsT >= 1_000_000_000L) {
                final int f = fps;
                SwingUtilities.invokeLater(() -> setTitle("SuperJavendo - " + snes.cart.title + " - " + f + " fps" + " - " + ports.describe()));
                fps = 0; fpsT = now;
            }
            next += (long) FRAME_NANOS;
            long wait = next - System.nanoTime();
            if (wait > 0) sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            else if (wait < -100_000_000L) next = System.nanoTime(); // fell behind, resync
        }
    }

    private void show(String msg) {
        toast = msg;
        toastUntil = System.currentTimeMillis() + 2000;
        SwingUtilities.invokeLater(this::repaint);
    }

    /** Runs on the emulation thread between frames, so the console state is consistent. */
    /** Copies the last completed frame of the console into the image the panel draws. */
    private void present() {
        var ppu = snes.bus.ppu;
        int w = ppu.outputWidth(), h = ppu.outputHeight();
        BufferedImage img = image;
        if (img.getWidth() != w || img.getHeight() != h) {
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            System.arraycopy(ppu.outputPixels(), 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, w * h);
            image = img;
        } else System.arraycopy(ppu.outputPixels(), 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0, w * h);
    }

    private void handleState(int request) {
        int req = request & 3, slot = request >> 2;
        Path stateFile = stateFiles.apply(slot);
        String tag = " (" + I18n.t("game.slot", slot) + ")";
        try {
            if (req == 1) {
                byte[] data = snes.saveState();
                java.nio.file.Files.createDirectories(stateFile.getParent());
                Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
                java.nio.file.Files.write(tmp, data);
                java.nio.file.Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                show(I18n.t("game.saved") + tag);
            } else {
                if (!java.nio.file.Files.exists(stateFile)) { show(I18n.t("game.nostate") + tag); return; }
                byte[] data = java.nio.file.Files.readAllBytes(stateFile);
                snes.loadState(data);
                present();
                long at = Snes.readInfo(data).savedAtMillis;
                show(at == 0 ? I18n.t("game.loaded") + tag : I18n.t("game.loaded") + tag + " (" + I18n.t("game.savedat") + " "
                        + java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")) + ")");
            }
        } catch (Exception ex) {
            show(I18n.t(req == 1 ? "game.savefail" : "game.loadfail", ex.getMessage()));
        }
    }

    private static void sleep(long ms) { sleep(ms, 0); }
    private static void sleep(long ms, int nanos) {
        try { Thread.sleep(ms, nanos); } catch (InterruptedException ignored) { }
    }
}
