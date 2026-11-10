package dev.superjavendo.apu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApuTest {
    static final class Ram implements Spc700.Bus {
        final byte[] m = new byte[0x10000];
        public int read(int a) { return m[a] & 0xFF; }
        public void write(int a, int v) { m[a] = (byte) v; }
    }

    private Ram ram;
    private Spc700 spc;

    private void run(int steps, int... code) {
        ram = new Ram();
        for (int i = 0; i < code.length; i++) ram.m[0x200 + i] = (byte) code[i];
        spc = new Spc700(ram);
        spc.reset(0x200);
        for (int i = 0; i < steps; i++) spc.step();
    }

    @Test void adcAndStore() {
        run(3, 0xE8, 0x05, 0x88, 0x03, 0xC4, 0x10); // MOV A,#5; ADC A,#3 (C clear); MOV $10,A
        assertEquals(8, spc.a);
        assertEquals(8, ram.m[0x10]);
    }

    @Test void loopWithDecAndBne() {
        // MOV X,#3 ; loop: DEC X ; BNE loop
        run(1 + 3 * 2, 0xCD, 0x03, 0x1D, 0xD0, 0xFD);
        assertEquals(0, spc.x);
    }

    @Test void mulAndDiv() {
        run(3, 0x8D, 0x07, 0xE8, 0x09, 0xCF); // MOV Y,#7; MOV A,#9; MUL YA
        assertEquals(63, spc.a | (spc.y << 8));
        run(4, 0x8D, 0x00, 0xE8, 100, 0xCD, 7, 0x9E); // Y=0 A=100 X=7; DIV YA,X
        assertEquals(14, spc.a);
        assertEquals(2, spc.y);
    }

    @Test void callAndRet() {
        // CALL !$0210 ; MOV X,#1 ... $0210: MOV Y,#2 ; RET
        ram = new Ram();
        int[] main = {0x3F, 0x10, 0x02, 0xCD, 0x01};
        for (int i = 0; i < main.length; i++) ram.m[0x200 + i] = (byte) main[i];
        ram.m[0x210] = (byte) 0x8D; ram.m[0x211] = 2; ram.m[0x212] = 0x6F;
        spc = new Spc700(ram); spc.reset(0x200);
        for (int i = 0; i < 4; i++) spc.step();
        assertEquals(2, spc.y); assertEquals(1, spc.x);
    }

    @Test void iplRomPresentsBootSignature() {
        Apu apu = new Apu();
        apu.tick(200_000);
        assertEquals(0xAA, apu.readPort(0));
        assertEquals(0xBB, apu.readPort(1));
    }

    @Test void iplUploadHandshakeStartsProgram() {
        Apu apu = new Apu();
        apu.tick(200_000);
        // Start transfer at $0300 with 1 byte, then execute at $0300.
        apu.writePort(2, 0x00); apu.writePort(3, 0x03);
        apu.writePort(1, 0x01); apu.writePort(0, 0xCC);
        apu.tick(5_000);
        assertEquals(0xCC, apu.readPort(0));
        apu.writePort(1, 0xEA); apu.writePort(0, 0x00); // data byte 0 ($EA = NOP... stored to $0300)
        apu.tick(5_000);
        assertEquals(0x00, apu.readPort(0));
        // finish: execute at $0300
        apu.writePort(2, 0x00); apu.writePort(3, 0x03);
        apu.writePort(1, 0x00); apu.writePort(0, 0x02);
        apu.tick(5_000);
        assertEquals(0x03, apu.spc.pc >> 8, "SPC should be running the uploaded code near $03xx");
    }

    @Test void dspProducesSoundFromBrrVoice() {
        byte[] ram = new byte[0x10000];
        Dsp d = new Dsp(ram);
        d.reset();
        d.write(0x6C, 0x00);                // unmute, no reset
        d.write(0x0C, 0x7F); d.write(0x1C, 0x7F); // main volume
        d.write(0x5D, 0x02);                // DIR = $0200
        ram[0x200] = 0x00; ram[0x201] = 0x10; ram[0x202] = 0x00; ram[0x203] = 0x10; // start/loop = $1000
        ram[0x1000] = (byte) 0xC3;          // range 12, filter 0, loop + end
        for (int i = 1; i < 9; i++) ram[0x1000 + i] = 0x77; // +7 samples
        d.write(0x00, 0x7F); d.write(0x01, 0x7F);   // voice 0 volume
        d.write(0x02, 0x00); d.write(0x03, 0x10);   // pitch $1000 (1:1)
        d.write(0x04, 0x00);                        // SRCN 0
        d.write(0x05, 0x00); d.write(0x07, 0x7F);   // GAIN direct, max
        d.write(0x4C, 0x01);                        // KON voice 0
        int peak = 0;
        for (int i = 0; i < 200; i++) { d.sample(); peak = Math.max(peak, Math.abs(d.outL)); }
        assertTrue(peak > 1000, "expected audible output, peak=" + peak);
    }
}
