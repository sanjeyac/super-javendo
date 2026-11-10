package dev.superjavendo;

import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.input.Joypad;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnesTest {
    /** Tiny assembler helpers (emulation mode, 8-bit). */
    static final class Asm {
        final List<Integer> b = new ArrayList<>();
        Asm st(int imm, int addr) {
            b.add(0xA9); b.add(imm); b.add(0x8D); b.add(addr & 0xFF); b.add(addr >> 8); return this;
        }
        Asm raw(int... v) { for (int x : v) b.add(x); return this; }
        Asm loop() { int at = 0x8000 + b.size(); return raw(0x4C, at & 0xFF, at >> 8); }
        int[] bytes() { return b.stream().mapToInt(i -> i).toArray(); }
    }

    /** 32 KB LoROM: code at $8000, NMI handler at $8100, IRQ handler at $8200, data at $9000/$9100. */
    static Snes snes(Asm code, int[] nmi, int[] irq, int[] data9000, int[] data9100) {
        byte[] rom = new byte[0x8000];
        put(rom, 0, code.bytes());
        if (nmi != null) put(rom, 0x100, nmi);
        if (irq != null) put(rom, 0x200, irq);
        if (data9000 != null) put(rom, 0x1000, data9000);
        if (data9100 != null) put(rom, 0x1100, data9100);
        java.util.Arrays.fill(rom, 0x7FC0, 0x7FC0 + 21, (byte) ' ');
        rom[0x7FD5] = 0x20;
        rom[0x7FDC] = (byte) 0xFF; rom[0x7FDD] = (byte) 0xFF; // checksum complement
        rom[0x7FFC] = 0x00; rom[0x7FFD] = (byte) 0x80;        // reset
        rom[0x7FFA] = 0x00; rom[0x7FFB] = (byte) 0x81;        // NMI (emulation)
        rom[0x7FFE] = 0x00; rom[0x7FFF] = (byte) 0x82;        // IRQ (emulation)
        return new Snes(new Cartridge(rom));
    }

    static void put(byte[] rom, int at, int[] v) {
        for (int i = 0; i < v.length; i++) rom[at + i] = (byte) v[i];
    }

    @Test void frameTakesAboutOneFrameOfCycles() {
        Snes s = snes(new Asm().loop(), null, null, null, null);
        s.runFrame();
        assertEquals(1, s.frameCount);
        assertTrue(s.cpu.cycles > 59000 && s.cpu.cycles < 60100, "cycles=" + s.cpu.cycles);
    }

    @Test void nmiFiresOncePerFrame() {
        Asm a = new Asm().st(0x80, 0x4200).loop();
        Snes s = snes(a, new int[]{0xE6, 0x10, 0x40}, null, null, null); // INC $10; RTI
        for (int i = 0; i < 3; i++) s.runFrame();
        assertEquals(3, s.bus.read(0x10));
    }

    @Test void nmiDisabledDoesNotFire() {
        Snes s = snes(new Asm().loop(), new int[]{0xE6, 0x10, 0x40}, null, null, null);
        s.runFrame();
        assertEquals(0, s.bus.read(0x10));
    }

    @Test void vIrqFiresOnceAtVtime() {
        Asm a = new Asm().st(100, 0x4209).st(0, 0x420A).st(0x20, 0x4200).raw(0x58).loop(); // CLI
        // INC $11; LDA $4211 (acknowledge); RTI
        Snes s = snes(a, null, new int[]{0xE6, 0x11, 0xAD, 0x11, 0x42, 0x40}, null, null);
        s.runFrame();
        assertEquals(1, s.bus.read(0x11));
    }

    @Test void generalDmaCopiesToWramPort() {
        Asm a = new Asm()
                .st(0, 0x2181).st(0, 0x2182).st(0, 0x2183)   // WRAM address 0
                .st(0x00, 0x4300).st(0x80, 0x4301)           // mode 0, B-bus $2180
                .st(0x00, 0x4302).st(0x90, 0x4303).st(0x00, 0x4304) // source $00:9000
                .st(0x04, 0x4305).st(0x00, 0x4306)           // 4 bytes
                .st(0x01, 0x420B)
                .loop();
        Snes s = snes(a, null, null, new int[]{1, 2, 3, 4}, null);
        s.runFrame();
        for (int i = 0; i < 4; i++) assertEquals(i + 1, s.bus.read(i), "byte " + i);
        assertEquals(0, s.bus.read(4));
        assertEquals(0, s.bus.dma.readReg(0x05) | s.bus.dma.readReg(0x06)); // DAS cleared
    }

    @Test void hdmaWritesOneByteAfterAnother() {
        Asm a = new Asm()
                .st(0x00, 0x4300).st(0x80, 0x4301)
                .st(0x00, 0x4302).st(0x91, 0x4303).st(0x00, 0x4304)
                .st(0x01, 0x420C)
                .loop();
        // repeat x2: AA,BB ; single line: CC ; end
        Snes s = snes(a, null, null, null, new int[]{0x82, 0xAA, 0xBB, 0x01, 0xCC, 0x00});
        s.runFrame(); // setup runs after this frame's HDMA init: this first frame runs on stale state
        s.bus.write(0x2181, 0); s.bus.write(0x2182, 0); s.bus.write(0x2183, 0);
        for (int i = 0; i < 8; i++) s.bus.write(i, 0);
        s.runFrame();
        assertEquals(0xAA, s.bus.read(0));
        assertEquals(0xBB, s.bus.read(1));
        assertEquals(0xCC, s.bus.read(2));
        assertEquals(0x00, s.bus.read(3));
    }

    @Test void hdmaEnabledMidFrameRunsFromPreloadedState() {
        // Games (e.g. Street Fighter II) enable HDMA from an IRQ after the frame-start init and
        // preload the table pointer ($4308/9) and line counter ($430A) themselves.
        Asm a = new Asm()
                .st(0x00, 0x4300).st(0x80, 0x4301)
                .st(0x00, 0x4302).st(0x91, 0x4303).st(0x00, 0x4304)
                .st(0x00, 0x4308).st(0x91, 0x4309)           // current table address
                .st(0x01, 0x430A)                             // line counter: reload on the first line
                .st(0x01, 0x420C)
                .loop();
        Snes s = snes(a, null, null, null, new int[]{0x82, 0xAA, 0xBB, 0x00});
        s.runFrame();
        assertEquals(0xAA, s.bus.read(0));
        assertEquals(0xBB, s.bus.read(1));
    }

    @Test void joypadSerialRead() {
        Snes s = snes(new Asm().loop(), null, null, null, null);
        s.joypad.setButtons(0, Joypad.B | Joypad.START);
        s.bus.write(0x4016, 1); s.bus.write(0x4016, 0);
        int[] expect = {1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        for (int i = 0; i < expect.length; i++) assertEquals(expect[i], s.bus.read(0x4016) & 1, "bit " + i);
    }

    @Test void joypadAutoRead() {
        Asm a = new Asm().st(0x01, 0x4200).loop();
        Snes s = snes(a, null, null, null, null);
        s.joypad.setButtons(0, Joypad.B | Joypad.START | Joypad.A);
        s.runFrame();
        assertEquals(0x90, s.bus.read(0x4219));
        assertEquals(0x80, s.bus.read(0x4218));
    }

    @Test void multiplyAndDivide() {
        Snes s = snes(new Asm().loop(), null, null, null, null);
        s.bus.write(0x4202, 7); s.bus.write(0x4203, 9);
        assertEquals(63, s.bus.read(0x4216) | (s.bus.read(0x4217) << 8));
        s.bus.write(0x4204, 1000 & 0xFF); s.bus.write(0x4205, 1000 >> 8); s.bus.write(0x4206, 7);
        assertEquals(142, s.bus.read(0x4214) | (s.bus.read(0x4215) << 8));
        assertEquals(6, s.bus.read(0x4216) | (s.bus.read(0x4217) << 8));
    }

    @Test void hvBlankStatus() {
        Snes s = snes(new Asm().loop(), null, null, null, null);
        s.timing.v = 230;
        assertEquals(0x80, s.bus.read(0x4212) & 0x80);
        s.timing.v = 10;
        assertEquals(0, s.bus.read(0x4212) & 0x80);
    }
}
