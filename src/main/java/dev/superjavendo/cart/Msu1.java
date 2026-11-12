package dev.superjavendo.cart;

import dev.superjavendo.state.StateIn;
import dev.superjavendo.state.StateOut;
import dev.superjavendo.state.Stateful;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MSU-1: streaming data and CD-quality audio for homebrew. Registers $2000-$2007; companion files next to the ROM:
 * {@code name.msu} (raw data) and {@code name-N.pcm} (audio track N: "MSU1", 32-bit loop point in sample frames,
 * then 16-bit stereo little-endian samples at 44.1 kHz).
 *
 * <p>Audio is resampled to the DSP rate and added to its output through {@link #nextSample()}.
 */
public final class Msu1 implements Stateful {
    private static final double RATIO = 44100.0 / 32040.0;     // PCM frames per DSP output sample

    private final Path dataPath;
    private final String audioBase;     // path prefix of the .pcm files
    private RandomAccessFile data;
    private RandomAccessFile audio;
    private long dataOffset;            // read pointer
    private long seekOffset;            // 32-bit value written to $2000-$2003
    private int track;
    private int volume = 0xFF;
    private boolean play, repeat, audioError;
    private long loopPoint;            // in sample frames
    private long audioPos;             // in sample frames
    private double frac;
    private int lastL, lastR, nextL, nextR;
    private long audioFrames;

    public int mixL, mixR;             // output of the last nextSample()

    /** @param romPath path of the ROM: the companion files are {@code <rom without extension>.msu} / {@code -N.pcm} */
    public Msu1(Path romPath) {
        String s = romPath.toString();
        int dot = s.lastIndexOf('.');
        String stem = dot > s.lastIndexOf('/') ? s.substring(0, dot) : s;
        this.audioBase = stem;
        this.dataPath = Path.of(stem + ".msu");
        try { if (Files.exists(dataPath)) data = new RandomAccessFile(dataPath.toFile(), "r"); } catch (IOException ignored) { }
    }

    /** True if a data file exists next to the ROM (the cartridge then gets an MSU-1). */
    public static boolean present(Path romPath) {
        String s = romPath.toString();
        int dot = s.lastIndexOf('.');
        return Files.exists(Path.of((dot > s.lastIndexOf('/') ? s.substring(0, dot) : s) + ".msu"));
    }

    public int read(int reg) {
        switch (reg & 7) {
            case 0: return (audioError ? 0x08 : 0) | (play ? 0x10 : 0) | (repeat ? 0x20 : 0) | 0x01;   // data/audio never busy; revision 1
            case 1: return readData();
            case 2: return 'S'; case 3: return '-'; case 4: return 'M'; case 5: return 'S'; case 6: return 'U'; default: return '1';
        }
    }

    public void write(int reg, int v) {
        v &= 0xFF;
        switch (reg & 7) {
            case 0: seekOffset = (seekOffset & 0xFFFFFF00L) | v; break;
            case 1: seekOffset = (seekOffset & 0xFFFF00FFL) | ((long) v << 8); break;
            case 2: seekOffset = (seekOffset & 0xFF00FFFFL) | ((long) v << 16); break;
            case 3: seekOffset = (seekOffset & 0x00FFFFFFL) | ((long) v << 24); dataOffset = seekOffset; break;
            case 4: track = (track & 0xFF00) | v; break;
            case 5: track = (track & 0x00FF) | (v << 8); selectTrack(); break;
            case 6: volume = v; break;
            default:
                if (!audioError) { play = (v & 1) != 0; repeat = (v & 2) != 0; }
        }
    }

    private int readData() {
        try {
            if (data == null || dataOffset >= data.length()) { dataOffset++; return 0; }
            data.seek(dataOffset++);
            return data.read();
        } catch (IOException e) { return 0; }
    }

    private void selectTrack() {
        play = repeat = false;
        closeAudio();
        Path p = Path.of(audioBase + "-" + track + ".pcm");
        try {
            if (!Files.exists(p)) { audioError = true; return; }
            RandomAccessFile f = new RandomAccessFile(p.toFile(), "r");
            byte[] h = new byte[8];
            f.readFully(h);
            if (h[0] != 'M' || h[1] != 'S' || h[2] != 'U' || h[3] != '1') { f.close(); audioError = true; return; }
            loopPoint = (h[4] & 0xFFL) | ((h[5] & 0xFFL) << 8) | ((h[6] & 0xFFL) << 16) | ((h[7] & 0xFFL) << 24);
            audio = f;
            audioFrames = (f.length() - 8) / 4;
            audioPos = 0; frac = 0; audioError = false;
            lastL = lastR = nextL = nextR = 0;
            readFrame();
            readFrame();
        } catch (IOException e) { audioError = true; closeAudio(); }
    }

    private void closeAudio() { try { if (audio != null) audio.close(); } catch (IOException ignored) { } audio = null; }

    private void readFrame() {
        lastL = nextL; lastR = nextR;
        try {
            if (audioPos >= audioFrames) {
                if (repeat) audioPos = Math.min(loopPoint, Math.max(0, audioFrames - 1));
                else { play = false; nextL = nextR = 0; return; }
            }
            audio.seek(8 + audioPos * 4);
            int a = audio.read(), b = audio.read(), c = audio.read(), d = audio.read();
            nextL = (short) (a | (b << 8)); nextR = (short) (c | (d << 8));
            audioPos++;
        } catch (IOException e) { play = false; }
    }

    /** Produces the next DSP-rate audio sample into {@link #mixL}/{@link #mixR} (zero when nothing plays). */
    public void nextSample() {
        if (!play || audio == null) { mixL = mixR = 0; return; }
        frac += RATIO;
        while (frac >= 1.0) { frac -= 1.0; readFrame(); }
        double t = 1.0 - frac;     // linear interpolation between the last two frames
        mixL = (int) ((lastL * t + nextL * (1 - t)) * volume / 255.0);
        mixR = (int) ((lastR * t + nextR * (1 - t)) * volume / 255.0);
    }

    @Override public void save(StateOut o) throws IOException {
        o.l(dataOffset); o.l(seekOffset); o.i(track); o.i(volume);
        o.i((play ? 1 : 0) | (repeat ? 2 : 0) | (audioError ? 4 : 0));
        o.l(audioPos); o.l(loopPoint);
    }

    @Override public void load(StateIn in) throws IOException {
        dataOffset = in.l(); seekOffset = in.l(); track = in.i(); volume = in.i();
        int f = in.i(); play = (f & 1) != 0; repeat = (f & 2) != 0; audioError = (f & 4) != 0;
        long pos = in.l(); loopPoint = in.l();
        selectTrackKeepingState(pos);
    }

    private void selectTrackKeepingState(long pos) {
        boolean p = play, r = repeat;
        selectTrack();
        if (!audioError) {
            // selectTrack() primed the interpolation with the first two frames of the track: prime it with the frames
            // just before the saved position instead, or the first samples after a load would be a click.
            audioPos = Math.max(0, Math.min(pos, audioFrames) - 2);
            lastL = lastR = nextL = nextR = 0;
            readFrame(); readFrame();
            play = p; repeat = r;
        }
    }
}
