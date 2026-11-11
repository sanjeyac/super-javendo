package dev.superjavendo;

import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.config.AppPaths;
import dev.superjavendo.input.Joypad;
import dev.superjavendo.ppu.Ppu;
import dev.superjavendo.ui.EmuWindow;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Usage:
 *   superjavendo [rom.sfc] [-p1 profile[,profile]] [-p2 ...]   open window (file chooser when no ROM given);
 *                                                profile = name of joystick/<name>.cfg, optional @N = /dev/input/jsN
 *   superjavendo rom.sfc --headless N [--shot out.png] [--press BTN:from:to]...
 */
public final class Main {
    private static final Map<String, Integer> BUTTONS = Map.ofEntries(
            Map.entry("up", Joypad.UP), Map.entry("down", Joypad.DOWN), Map.entry("left", Joypad.LEFT),
            Map.entry("right", Joypad.RIGHT), Map.entry("a", Joypad.A), Map.entry("b", Joypad.B),
            Map.entry("x", Joypad.X), Map.entry("y", Joypad.Y), Map.entry("l", Joypad.L), Map.entry("r", Joypad.R),
            Map.entry("start", Joypad.START), Map.entry("select", Joypad.SELECT));

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--state-shot")) { // --state-shot <file.state> <out.png>
            var info = Snes.readInfo(java.nio.file.Files.readAllBytes(Path.of(args[1])));
            if (!info.hasScreenshot()) { System.err.println("This state has no screenshot"); System.exit(1); }
            java.nio.file.Files.write(Path.of(args[2]), info.screenshotPng);
            System.out.println(info.gameTitle + " saved " + java.time.Instant.ofEpochMilli(info.savedAtMillis) + " -> " + args[2]);
            return;
        }
        if (args.length == 1 && args[0].equals("--controllers")) {   // open only the controller configurator
            SwingUtilities.invokeLater(() -> {
                var w = new dev.superjavendo.frontend.ControllerConfigWindow();
                w.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                w.setVisible(true);
            });
            return;
        }
        Path rom = null;
        int headlessFrames = -1;
        String shot = null;
        int every = 0;
        int layerMask = 0x1F;
        boolean dump = false;
        String wav = null;
        String everyPrefix = null;
        List<int[]> presses = new ArrayList<>();
        Map<Integer, String> profiles = new java.util.TreeMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--headless": headlessFrames = Integer.parseInt(args[++i]); break;
                case "--shot": shot = args[++i]; break;
                case "--layers": layerMask = Integer.parseInt(args[++i], 16); break;
                case "--dump": dump = true; break;
                case "--wav": wav = args[++i]; break;
                case "--every": every = Integer.parseInt(args[++i]); everyPrefix = args[++i]; break;
                case "-p1": case "-p2": case "-p3": case "-p4":
                    profiles.put(args[i].charAt(2) - '0', args[++i]);
                    break;
                case "--press": {
                    String[] p = args[++i].split(":");
                    presses.add(new int[]{BUTTONS.get(p[0].toLowerCase()), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                    break;
                }
                default: rom = Path.of(args[i]);
            }
        }
        if (rom == null && headlessFrames < 0) {
            SwingUtilities.invokeLater(() -> new dev.superjavendo.frontend.LauncherWindow(profiles).setVisible(true));
            return;
        }
        if (headlessFrames < 0) {
            try {
                dev.superjavendo.input.ControllerConfig.check(AppPaths.forCurrentUser(), profiles);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(2);
            }
            dev.superjavendo.frontend.GameLauncher.open(rom, profiles, true);
            return;
        }
        Cartridge cart = Cartridge.load(rom);
        String name = rom.getFileName().toString();
        Path srm = rom.resolveSibling(name.replaceFirst("\\.[^.]*$", "") + ".srm");
        AppPaths paths = AppPaths.forCurrentUser();
        paths.migrateLegacyState(rom);
        cart.loadSram(srm);
        System.out.printf("Title: %s | %s | Chip: %s | ROM %d KB | SRAM %d KB | checksum %s | reset $%04X%n",
                cart.title, cart.mapping, cart.chip.label, cart.rom.length / 1024, cart.sram.length / 1024,
                cart.checksumValid ? "ok" : "bad", cart.resetVector);
        Snes snes = new Snes(cart);

        snes.bus.ppu.debugMask = layerMask;
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        if (wav != null) snes.bus.apu.setSink((l, r) -> { pcm.write(l); pcm.write(l >> 8); pcm.write(r); pcm.write(r >> 8); });
        java.io.PrintStream ctraceOut = null;
        java.io.PrintStream gtraceOut = null;
        if (System.getProperty("gtrace") != null && snes.cart.coprocessor instanceof dev.superjavendo.cart.SuperFx) gtraceOut = new java.io.PrintStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(System.getProperty("gtrace")), 1 << 20), false);
        for (int f = 0; f < headlessFrames; f++) {
            snes.bus.watch = System.getProperty("watch") != null && f == Integer.getInteger("watch");
            snes.bus.watch2 = System.getProperty("watch2") != null && f >= Integer.getInteger("watch2") && f < Integer.getInteger("watch2End", Integer.getInteger("watch2") + 3);
            if (System.getProperty("rhist") != null && f == Integer.getInteger("rhist")) snes.bus.readHist = new java.util.TreeMap<>();
            snes.bus.ioTrace = System.getProperty("iotrace") != null && f == Integer.getInteger("iotrace");
            if (System.getProperty("scr") != null && f >= Integer.getInteger("scr") && f < Integer.getInteger("scr") + 3) System.out.println("frame " + (f - 1) + "\n" + snes.bus.ppu.dumpScroll());
            if (System.getProperty("wram") != null && f == Integer.getInteger("wram")) { StringBuilder sb = new StringBuilder(); for (int i = 0x10; i < 0x80; i += 2) sb.append(String.format("%d ", snes.bus.read(i) | (snes.bus.read(i + 1) << 8))); System.out.println("WRAMTAB " + sb); }
            if (System.getProperty("ctrace") != null) {          // -Dctrace=FILE -Dctrace.from=N -Dctrace.to=M : per-instruction CPU trace
                if (ctraceOut == null) ctraceOut = new java.io.PrintStream(new java.io.BufferedOutputStream(new java.io.FileOutputStream(System.getProperty("ctrace")), 1 << 20), false);
                snes.cpu.trace = f >= Integer.getInteger("ctrace.from", 0) && f < Integer.getInteger("ctrace.to", Integer.MAX_VALUE) ? ctraceOut : null;
            }
            if (gtraceOut != null) ((dev.superjavendo.cart.SuperFx) snes.cart.coprocessor).trace = f >= Integer.getInteger("gtrace.from", 0) && f < Integer.getInteger("gtrace.to", Integer.MAX_VALUE) ? gtraceOut : null;
            snes.pcTrace = System.getProperty("pctrace") != null && f == Integer.getInteger("pctrace");
            snes.bus.dma.trace = snes.pcTrace || (System.getProperty("dtrace") != null && f == Integer.getInteger("dtrace"));
            snes.bus.ppu.trace = System.getProperty("trace") != null && f >= Integer.getInteger("trace") && f < Integer.getInteger("traceEnd", Integer.getInteger("trace") + 1);
            snes.bus.ppu.traceFrame = f;
            int mask = 0;
            for (int[] p : presses) if (f >= p[1] && f < p[2]) mask |= p[0];
            snes.joypad.setButtons(0, mask);
            snes.runFrame();
            if (every > 0 && (f + 1) % every == 0) save(snes, String.format("%s_%05d.png", everyPrefix, f + 1));
        }
        if (ctraceOut != null) ctraceOut.flush();
        if (gtraceOut != null) gtraceOut.flush();
        if (System.getProperty("wramdump") != null) { byte[] w = new byte[0x20000]; for (int i = 0; i < w.length; i++) w[i] = (byte) snes.bus.read(0x7E0000 + i); java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("wramdump")), w); }
        if (snes.bus.readHist != null) snes.bus.readHist.forEach((k, v) -> System.out.printf("read %04X x%d%n", k, v));
        var c = snes.cpu;
        System.out.printf("After %d frames: PC=%06X A=%04X X=%04X Y=%04X S=%04X P=%02X cycles=%d spcPC=%04X%n",
                snes.frameCount, c.pcFull(), c.a, c.x, c.y, c.s, c.getP(), c.cycles, snes.bus.apu.spc.pc);
        if (snes.cart.coprocessor instanceof dev.superjavendo.cart.SuperFx fx) {
            System.out.println(fx.status());
            if (System.getProperty("cartram") != null) java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("cartram")), fx.ram());
        }
        if (dump) { System.out.println(snes.bus.ppu.dump()); System.out.println(snes.bus.ppu.dumpScroll()); }
        if (wav != null) writeWav(wav, pcm.toByteArray());
        if (shot != null) { save(snes, shot); System.out.println("Saved " + shot); }
    }

    private static void writeWav(String file, byte[] pcm) throws Exception {
        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(32000, 16, 2, true, false);
        var in = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(pcm), fmt, pcm.length / 4);
        javax.sound.sampled.AudioSystem.write(in, javax.sound.sampled.AudioFileFormat.Type.WAVE, new File(file));
        long peak = 0, sum = 0; int n = pcm.length / 2;
        for (int i = 0; i < n; i++) { int v = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8)); peak = Math.max(peak, Math.abs(v)); sum += (long) v * v; }
        System.out.printf("WAV %s: %d samples, peak=%d rms=%.0f%n", file, n / 2, peak, Math.sqrt(sum / Math.max(1.0, n)));
    }

    private static void save(Snes snes, String file) throws Exception {
        BufferedImage img = new BufferedImage(Ppu.WIDTH, Ppu.HEIGHT, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, Ppu.WIDTH, Ppu.HEIGHT, snes.bus.ppu.frame, 0, Ppu.WIDTH);
        ImageIO.write(img, "png", new File(file));
    }
}
