package dev.superjavendo.movie;

import dev.superjavendo.Snes;
import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.input.Joypad;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class MovieTest {
    /** Auto-joypad on; the main loop adds the pad's high byte ($4219) into WRAM $10 forever, so the run depends on every input. */
    private static Cartridge rom() {
        byte[] rom = new byte[0x8000];
        int[] code = {0xA9, 0x81, 0x8D, 0x00, 0x42,              // LDA #$81 : STA $4200
                0xAD, 0x19, 0x42, 0x18, 0x65, 0x10, 0x85, 0x10,   // loop: LDA $4219 : CLC : ADC $10 : STA $10
                0x4C, 0x05, 0x80};                                // JMP loop
        for (int i = 0; i < code.length; i++) rom[i] = (byte) code[i];
        java.util.Arrays.fill(rom, 0x7FC0, 0x7FC0 + 21, (byte) ' ');
        rom[0x7FD5] = 0x20;
        rom[0x7FDC] = (byte) 0xFF; rom[0x7FDD] = (byte) 0xFF;
        rom[0x7FFD] = (byte) 0x80;                                 // reset $8000
        rom[0x7FFB] = (byte) 0x81; rom[0x7FFF] = (byte) 0x82;      // NMI/IRQ handlers: RTI
        rom[0x100] = 0x40; rom[0x200] = 0x40;
        return new Cartridge(rom);
    }

    private static Movie script(Cartridge c, int frames, IntUnaryOperator pad) {
        Movie m = new Movie(Movie.crcOf(new Snes(c)), "t");
        for (int f = 0; f < frames; f++) m.append(pad.applyAsInt(f), 0);
        return m;
    }

    private static int wram10(Snes s) { return s.bus.read(0x10); }

    @Test void roundTripKeepsEveryFrame() throws Exception {
        Cartridge c = rom();
        Movie m = script(c, 500, f -> f % 90 < 30 ? Joypad.RIGHT : f % 7 == 0 ? Joypad.A : 0);
        m.endHash = 0x1234;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        m.write(o);
        Movie r = Movie.read(new ByteArrayInputStream(o.toByteArray()));
        assertEquals(m.romCrc, r.romCrc);
        assertEquals(0x1234, r.endHash);
        assertEquals(500, r.length());
        for (int f = 0; f < 500; f++) { assertEquals(m.pad1(f), r.pad1(f)); assertEquals(m.pad2(f), r.pad2(f)); }
    }

    @Test void runsOfIdenticalFramesAreCompressed() throws Exception {
        Movie m = script(rom(), 100000, f -> Joypad.B);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        m.write(o);
        assertTrue(o.size() < 200, "size " + o.size());
    }

    @Test void garbageIsRejected() {
        assertThrows(java.io.IOException.class, () -> Movie.read(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    @Test void replayReproducesTheLiveRun() {
        Cartridge c = rom();
        IntUnaryOperator pad = f -> f % 50 < 20 ? Joypad.RIGHT : f % 13 == 0 ? Joypad.UP : 0;
        Snes live = new Snes(c);
        Movie m = new Movie(Movie.crcOf(live), "t");
        for (int f = 0; f < 300; f++) { m.append(pad.applyAsInt(f), 0); live.joypad.setButtons(0, pad.applyAsInt(f)); live.runFrame(); }
        m.endHash = Movie.pictureHash(live);
        int[] seen = new int[1];
        Replay.Result r = Replay.run(c, m, s -> seen[0] = wram10(s));
        assertTrue(r.matches());
        assertEquals(300, r.frames());
        assertEquals(wram10(live), seen[0]);
        assertNotEquals(0, seen[0], "the program must actually depend on the input");
    }

    @Test void differentInputsGiveADifferentRun() {
        Cartridge c = rom();
        int[] a = new int[1], b = new int[1];
        Replay.run(c, script(c, 200, f -> Joypad.RIGHT), s -> a[0] = wram10(s));
        Replay.run(c, script(c, 200, f -> Joypad.LEFT), s -> b[0] = wram10(s));
        assertNotEquals(a[0], b[0]);
    }

    @Test void replayNeverTouchesBatteryRam() {
        Cartridge c = rom();
        // a cartridge with battery RAM: header byte $7FD8 = 3 (8 KB)
        byte[] data = c.rom.clone();
        data[0x7FD8] = 3;
        Cartridge withSram = new Cartridge(data);
        java.util.Arrays.fill(withSram.sram, (byte) 0x5A);
        Replay.run(withSram, script(withSram, 10, f -> 0), null);
        for (byte b : withSram.sram) assertEquals(0x5A, b);
    }

    @Test void resetFlagRestartsTheConsole() {
        Cartridge c = rom();
        // reset at frame 100, then 20 more frames: must equal a fresh 20-frame run (the byte counter wraps, so compare exactly)
        Movie reset = script(c, 120, f -> f == 100 ? Joypad.RIGHT | Movie.RESET : Joypad.RIGHT);
        Movie fresh = script(c, 20, f -> Joypad.RIGHT);
        int[] a = new int[1], b = new int[1];
        Replay.run(c, reset, s -> a[0] = wram10(s));
        Replay.run(c, fresh, s -> b[0] = wram10(s));
        assertEquals(b[0], a[0]);
    }
}
