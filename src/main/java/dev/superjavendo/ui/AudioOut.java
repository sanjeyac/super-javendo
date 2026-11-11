package dev.superjavendo.ui;

import dev.superjavendo.apu.Apu;

import javax.sound.sampled.*;

/** Collects 32 kHz DSP samples, resamples to 48 kHz and feeds a non-blocking SourceDataLine. */
public final class AudioOut implements Apu.Sink {
    private static final int OUT_RATE = 48000;
    private SourceDataLine line;
    private final short[] in = new short[32000 * 2]; // up to 1 s of stereo input
    private int inCount;
    private double pos;
    private int prevL, prevR;
    private byte[] outBuf = new byte[0];
    private volatile int volume = 100;

    /** 0..100. */
    public void setVolume(int percent) { volume = Math.max(0, Math.min(100, percent)); }

    public AudioOut() {
        try {
            AudioFormat fmt = new AudioFormat(OUT_RATE, 16, 2, true, false);
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, OUT_RATE / 5 * 4);
            line.start();
        } catch (Exception | Error e) {
            System.err.println("Audio disabled: " + e);
            line = null;
        }
    }

    @Override
    public synchronized void sample(int l, int r) {
        if (line == null || inCount + 2 > in.length) return;
        in[inCount++] = (short) l;
        in[inCount++] = (short) r;
    }

    /** Called once per frame from the emulation thread. */
    public synchronized void flush() {
        if (line == null) { inCount = 0; return; }
        int frames = inCount / 2;
        if (frames == 0) return;
        double step = 32000.0 / OUT_RATE;
        int maxOut = (int) (frames / step) + 2;
        if (outBuf.length < maxOut * 4) outBuf = new byte[maxOut * 4];
        int n = 0;
        // pos is the position between prev (index -1) and in[0..]
        while (true) {
            int i = (int) Math.floor(pos);
            if (i + 1 >= frames) break;
            double f = pos - i;
            int l0 = i < 0 ? prevL : in[i * 2], r0 = i < 0 ? prevR : in[i * 2 + 1];
            int l1 = in[(i + 1) * 2], r1 = in[(i + 1) * 2 + 1];
            int l = (int) (l0 + (l1 - l0) * f) * volume / 100, r = (int) (r0 + (r1 - r0) * f) * volume / 100;
            outBuf[n++] = (byte) l; outBuf[n++] = (byte) (l >> 8);
            outBuf[n++] = (byte) r; outBuf[n++] = (byte) (r >> 8);
            pos += step;
        }
        pos -= frames;
        prevL = in[(frames - 1) * 2]; prevR = in[(frames - 1) * 2 + 1];
        inCount = 0;
        int writable = Math.min(n, line.available() & ~3);
        if (writable > 0) line.write(outBuf, 0, writable);
    }

    public void close() { if (line != null) { line.drain(); line.close(); } }
}
