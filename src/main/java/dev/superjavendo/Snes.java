package dev.superjavendo;

import dev.superjavendo.bus.Bus;
import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.cpu.Cpu65816;
import dev.superjavendo.input.Joypad;
import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateInfo;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.timing.Timing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** The whole console: CPU + bus + scanline scheduler. */
public final class Snes {
    /** Master clocks per CPU cycle. 6 is the fastest bus speed; real hardware mixes 6/8/12. */
    public static final int CLOCKS_PER_CYCLE = 6;

    public final Cartridge cart;
    public final Bus bus;
    public final Cpu65816 cpu;
    public final Timing timing;
    public final Joypad joypad;
    public long frameCount;
    private int carry; // master clocks the last instruction overshot into the next line

    public Snes(Cartridge cart) {
        this.cart = cart;
        this.bus = new Bus(cart);
        this.cpu = new Cpu65816(bus);
        this.timing = bus.timing;
        this.joypad = bus.joypad;
        bus.attachCpu(cpu);
        bus.apu.msu = cart.msu;
        cpu.reset();
    }

    /** Emulates one full NTSC frame (262 scanlines). */
    public void runFrame() {
        for (int v = 0; v < Timing.LINES; v++) runScanline(v);
        frameCount++;
    }

    public boolean pcTrace;

    private void runScanline(int v) {
        Timing t = timing;
        t.v = v;
        bus.ppu.traceLine = v;
        int clock = carry;

        if (v == 0) {
            t.nmiFlag = false;
            bus.ppu.frameStart();
            bus.dma.hdmaInit();
        }
        if (v >= 1 && v <= 224) bus.ppu.renderLine(v - 1);
        if (v == Timing.VBLANK_START) {
            bus.ppu.vblankStart();
            t.nmiFlag = true;
            if ((t.nmitimen & 0x80) != 0) cpu.nmiPending = true;
            if ((t.nmitimen & 1) != 0) joypad.autoRead();
        }
        t.autoJoyBusy = (t.nmitimen & 1) != 0 && v >= Timing.VBLANK_START && v < Timing.VBLANK_START + 3;

        if (pcTrace) System.out.printf("v=%d PC=%06X A=%04X X=%04X Y=%04X P=%02X nmitimen=%02X%n", v, cpu.pcFull(), cpu.a, cpu.x, cpu.y, cpu.getP(), t.nmitimen);
        int irqAt = t.irqClock();
        boolean irqFired = false;
        while (clock < Timing.LINE_CLOCKS) {
            if (!irqFired && irqAt >= 0 && clock >= irqAt) { irqFired = true; t.irqFlag = true; }
            cpu.irqLine = t.irqFlag || (cart.coprocessor != null && cart.coprocessor.irq());
            t.h = clock;
            int mc = cpu.step() * CLOCKS_PER_CYCLE + bus.takeStall();
            bus.apu.tick(mc);
            if (cart.coprocessor != null) cart.coprocessor.tick(mc);
            clock += mc;
        }
        if (v < Timing.VBLANK_START) clock += bus.dma.hdmaRun(); // HDMA runs in the hblank at the end of the line
        if (!irqFired && irqAt >= 0) t.irqFlag = true; // instruction straddled the IRQ point
        carry = clock - Timing.LINE_CLOCKS;
    }

    // ------------------------------------------------------------------ save states

    private static final int STATE_MAGIC = 0x534A5653; // "SJVS"
    private static final int STATE_VERSION = 2; // v2 adds timestamp + screenshot to the header (v1 is still readable)

    /** Identifies the ROM a state belongs to, so a state is never loaded into the wrong game. */
    private byte[] screenshotPng() throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                dev.superjavendo.ppu.Ppu.WIDTH, dev.superjavendo.ppu.Ppu.HEIGHT, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, dev.superjavendo.ppu.Ppu.WIDTH, dev.superjavendo.ppu.Ppu.HEIGHT,
                bus.ppu.frame, 0, dev.superjavendo.ppu.Ppu.WIDTH);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        return png.toByteArray();
    }

    /** Reads only the header of a save state: game, time and screenshot. Needs no console. */
    public static StateInfo readInfo(byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            StateIn in = new StateIn(gz);
            if (in.i() != STATE_MAGIC) throw new IOException("Not a SuperJavendo save state");
            int version = in.i();
            if (version > STATE_VERSION) throw new IOException("Unsupported save state version " + version);
            in.i();
            String title = in.str();
            if (version < 2) return new StateInfo(title, 0, null);
            long at = in.l();
            return new StateInfo(title, at, in.blob());
        }
    }

    private int romId() { return java.util.Arrays.hashCode(cart.rom) * 31 + cart.rom.length; }

    /** Serialises the whole console (taken between frames) into a compressed byte array. */
    public byte[] saveState() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 20);
        try (GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            StateOut o = new StateOut(gz);
            o.i(STATE_MAGIC); o.i(STATE_VERSION); o.i(romId()); o.str(cart.title);
            o.l(System.currentTimeMillis()); o.blob(screenshotPng());
            o.i(carry); o.l(frameCount);
            cpu.save(o);
            bus.save(o);
            if (cart.coprocessor != null) cart.coprocessor.save(o);
            if (cart.msu != null) cart.msu.save(o);   // only chip cartridges carry extra state
            o.flush();
        }
        return raw.toByteArray();
    }

    /**
     * Restores a state produced by {@link #saveState()}. On any problem the console is put back
     * exactly as it was before the call and an IOException is thrown.
     */
    public void loadState(byte[] data) throws IOException {
        byte[] backup = saveState();
        try {
            readState(data);
        } catch (IOException | RuntimeException e) {
            try { readState(backup); } catch (IOException ignored) { }
            if (e instanceof IOException io) throw io;
            throw new IOException("Corrupt save state: " + e, e);
        }
    }

    private void readState(byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            StateIn in = new StateIn(gz);
            if (in.i() != STATE_MAGIC) throw new IOException("Not a SuperJavendo save state");
            int version = in.i();
            if (version != STATE_VERSION) throw new IOException("Unsupported save state version " + version);
            int id = in.i();
            String title = in.str();
            if (id != romId()) throw new IOException("Save state belongs to another game (" + title + ")");
            if (version >= 2) { in.l(); in.blob(); }
            carry = in.i(); frameCount = in.l();
            cpu.load(in);
            bus.load(in);
            if (cart.coprocessor != null) cart.coprocessor.load(in);
            if (cart.msu != null) cart.msu.load(in);
        }
    }
}
