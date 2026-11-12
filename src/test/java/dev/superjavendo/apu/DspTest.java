package dev.superjavendo.apu;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DspTest {
    private final byte[] ram = new byte[0x10000];
    private final Dsp d = new Dsp(ram);

    /** Voice 0 playing a constant BRR loop at full volume. */
    private void playVoice0(int flg) {
        d.reset();
        d.write(0x6C, flg);
        d.write(0x0C, 0x7F); d.write(0x1C, 0x7F);
        d.write(0x5D, 0x02);
        ram[0x200] = 0x00; ram[0x201] = 0x10; ram[0x202] = 0x00; ram[0x203] = 0x10;
        ram[0x1000] = (byte) 0xC3;
        for (int i = 1; i < 9; i++) ram[0x1000 + i] = 0x77;
        d.write(0x00, 0x7F); d.write(0x01, 0x7F);
        d.write(0x02, 0x00); d.write(0x03, 0x10);
        d.write(0x04, 0x00);
        d.write(0x05, 0x00); d.write(0x07, 0x7F);
        d.write(0x4C, 0x01);
    }

    private int peak(int samples) {
        int p = 0;
        for (int i = 0; i < samples; i++) { d.sample(); p = Math.max(p, Math.max(Math.abs(d.outL), Math.abs(d.outR))); }
        return p;
    }

    @Test void registersReadBackAndMirrorAbove7F() {
        d.reset();
        d.write(0x0C, 0x55);
        assertEquals(0x55, d.read(0x0C));
        assertEquals(0x55, d.read(0x8C));          // $80-$FF mirror $00-$7F
        d.write(0x9D, 0x33);
        assertEquals(0x33, d.read(0x1D));
    }

    @Test void silentWhenNoVoiceIsKeyedOn() {
        d.reset();
        d.write(0x6C, 0x00);
        d.write(0x0C, 0x7F); d.write(0x1C, 0x7F);
        assertEquals(0, peak(100));
    }

    @Test void muteFlagSilencesTheOutput() {
        playVoice0(0x00);
        assertTrue(peak(200) > 1000);
        playVoice0(0x40);                           // FLG bit 6 = mute
        assertEquals(0, peak(200));
    }

    @Test void zeroMasterVolumeSilencesTheOutput() {
        playVoice0(0x00);
        d.write(0x0C, 0); d.write(0x1C, 0);
        assertEquals(0, peak(200));
    }

    @Test void endBlockSetsEndxAndWritingEndxClearsIt() {
        playVoice0(0x00);
        peak(2000);
        assertEquals(1, d.read(0x7C) & 1, "block with the end flag must set ENDX bit 0");
        d.write(0x7C, 0xFF);
        assertEquals(0, d.read(0x7C));
    }

    @Test void outxFollowsTheVoiceSample() {
        playVoice0(0x00);
        peak(200);
        assertNotEquals(0, d.read(0x09), "OUTX of voice 0 reflects the last sample");
    }

    @Test void resetSilencesEverything() {
        playVoice0(0x00);
        peak(50);
        d.reset();
        assertEquals(0, d.read(0x7C));
        assertEquals(0, d.read(0x08));              // ENVX
    }

    @Test void stateRoundTripGivesTheSameSamples() throws Exception {
        playVoice0(0x00);
        peak(37);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        d.save(new StateOut(bo));
        Dsp copy = new Dsp(ram);
        copy.load(new StateIn(new ByteArrayInputStream(bo.toByteArray())));
        for (int i = 0; i < 100; i++) {
            d.sample(); copy.sample();
            assertEquals(d.outL, copy.outL, "left sample " + i);
            assertEquals(d.outR, copy.outR, "right sample " + i);
        }
    }

    // ---- envelopes

    private int maxEnvx(int samples) {
        int m = 0;
        for (int i = 0; i < samples; i++) { d.sample(); m = Math.max(m, d.read(0x08)); }
        return m;
    }

    @Test void adsrRisesDecaysAndHoldsAtTheSustainLevel() {
        playVoice0(0x00);
        d.write(0x05, 0x80 | 0x0F | (7 << 4));                // ADSR on, fastest attack, fast decay
        d.write(0x06, (1 << 5) | 0x00);                       // sustain level 1/8, sustain rate 0 (hold)
        d.write(0x4C, 0x01);
        int peak = maxEnvx(3000);
        assertTrue(peak > 0x40, "attack reached " + peak);
        assertTrue(d.read(0x08) < peak, "decayed below the peak: " + d.read(0x08));
        assertTrue(d.read(0x08) > 0, "holds at the sustain level");
    }

    @Test void keyOffReleasesTheEnvelopeToZero() {
        playVoice0(0x00);
        d.write(0x05, 0x80 | 0x0F);
        d.write(0x06, 0xE0);                                  // sustain at 7/8
        d.write(0x4C, 0x01);
        assertTrue(maxEnvx(2000) > 0x40);
        d.write(0x4C, 0x00);
        d.write(0x5C, 0x01);                                  // KOFF voice 0
        run(1000);
        assertEquals(0, d.read(0x08));
    }

    private void run(int samples) { for (int i = 0; i < samples; i++) d.sample(); }

    /** Sets GAIN, runs a while and returns ENVX. */
    private int envxAfter(int gain, int samples) {
        d.write(0x07, gain);
        run(samples);
        return d.read(0x08);
    }

    @Test void gainModesMoveTheEnvelopeInTheirDirection() {
        playVoice0(0x00);
        d.write(0x05, 0x00);                                  // GAIN instead of ADSR
        d.write(0x4C, 0x01);
        int linearUp = envxAfter(0xC0 | 0x1F, 100);           // mode 2: linear increase
        assertTrue(linearUp > 0, "linear increase: " + linearUp);
        int top = envxAfter(0xC0 | 0x1F, 2000);
        assertTrue(top >= linearUp);
        int linearDown = envxAfter(0x80 | 0x1F, 100);         // mode 0: linear decrease
        assertTrue(linearDown < top, "linear decrease: " + linearDown + " < " + top);
        envxAfter(0xC0 | 0x1F, 2000);                         // back to the top
        int expDown = envxAfter(0xA0 | 0x1F, 100);            // mode 1: exponential decrease
        assertTrue(expDown < top, "exponential decrease: " + expDown + " < " + top);
        int low = envxAfter(0x80 | 0x1F, 3000);
        int bent = envxAfter(0xE0 | 0x1F, 50);                // mode 3: bent increase
        assertTrue(bent > low, "bent increase: " + bent + " > " + low);
    }

    @Test void directGainSetsTheLevelImmediately() {
        playVoice0(0x00);
        d.write(0x05, 0x00);
        d.write(0x07, 0x40);                                  // direct: level = value * 16
        run(100);
        assertEquals(0x40, d.read(0x08));
    }

    // ---- BRR

    @Test void everyBrrFilterProducesSound() {
        for (int filter = 0; filter < 4; filter++) {
            playVoice0(0x00);
            ram[0x1000] = (byte) (0xC0 | filter << 2 | 0x03);    // range 12, filter n, loop + end
            for (int i = 1; i < 9; i++) ram[0x1000 + i] = 0x77;
            d.write(0x4C, 0x01);
            assertTrue(peak2(300) > 100, "filter " + filter + " silent");
        }
    }

    private int peak2(int samples) {
        int p = 0;
        for (int i = 0; i < samples; i++) { d.sample(); p = Math.max(p, Math.abs(d.outL)); }
        return p;
    }

    @Test void blocksWithoutAnEndFlagContinueIntoTheNextBlock() {
        playVoice0(0x00);
        ram[0x1000] = (byte) 0xC0;                            // no flags: keep going
        for (int i = 1; i < 9; i++) ram[0x1000 + i] = 0x77;
        ram[0x1009] = (byte) 0xC3;                            // second block ends and loops
        for (int i = 10; i < 18; i++) ram[0x1000 + i] = 0x77;
        d.write(0x4C, 0x01);
        assertTrue(peak2(600) > 1000);
        assertEquals(1, d.read(0x7C) & 1, "the second block set ENDX");
    }
}
