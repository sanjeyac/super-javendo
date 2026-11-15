package dev.superjavendo.movie;

import dev.superjavendo.Snes;

import java.io.*;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * An input movie: the pad masks of both ports for every frame, starting from power-on with empty battery RAM.
 * Because the console is deterministic, replaying the inputs reproduces the run exactly; {@link #endHash} (a
 * checksum of the last picture) lets anyone verify that a replay really ended where the author's run did.
 *
 * <p>Bit 0 of the port 1 mask (unused by the pad itself) flags a console reset before that frame.
 */
public final class Movie {
    public static final int RESET = 1;
    private static final int MAGIC = 0x534A4D56; // "SJMV"
    private static final int VERSION = 2;   // v2 adds label, creation time and verification result to the header (v1 is still readable)
    public static final int UNVERIFIED = 0, VERIFIED = 1, DESYNC = 2;

    public final int romCrc;
    public final String title;
    private int[] p1 = new int[1024], p2 = new int[1024];
    private int length;
    /** Hash of the final picture; 0 until the movie is finished. */
    public int endHash;
    /** Name chosen by the user; empty = show the date. */
    public String label = "";
    public long createdAt = System.currentTimeMillis();
    /** Result of the last full replay: {@link #UNVERIFIED}, {@link #VERIFIED} or {@link #DESYNC}. */
    public int verified;

    /** What the header says about a movie: enough to list it without reading the inputs. */
    public record Info(int romCrc, String title, int frames, int endHash, String label, long createdAt, int verified) {
        public double seconds() { return frames / 60.0988; }
    }

    public Movie(int romCrc, String title) { this.romCrc = romCrc; this.title = title; }

    public void append(int pad1, int pad2) {
        if (length == p1.length) { p1 = Arrays.copyOf(p1, length * 2); p2 = Arrays.copyOf(p2, length * 2); }
        p1[length] = pad1; p2[length] = pad2; length++;
    }

    public int length() { return length; }
    public int pad1(int frame) { return p1[frame]; }
    public int pad2(int frame) { return p2[frame]; }
    public double seconds() { return length / 60.0988; }

    /** CRC of the ROM image a movie belongs to. */
    public static int crcOf(Snes snes) {
        CRC32 c = new CRC32();
        c.update(snes.cart.rom);
        return (int) c.getValue();
    }

    /** Checksum of the picture the console just finished. */
    public static int pictureHash(Snes snes) {
        var ppu = snes.bus.ppu;
        int n = ppu.outputWidth() * ppu.outputHeight();
        int[] px = ppu.outputPixels();
        int h = n;
        for (int i = 0; i < n; i++) h = h * 31 + px[i];
        return h;
    }

    public void write(OutputStream out) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(out);
        DataOutputStream o = new DataOutputStream(gz);
        o.writeInt(MAGIC); o.writeInt(VERSION); o.writeInt(romCrc); o.writeUTF(title);
        o.writeInt(length); o.writeInt(endHash);
        o.writeUTF(label); o.writeLong(createdAt); o.writeByte(verified);
        for (int i = 0; i < length; ) {                    // run-length encoded: held buttons repeat for many frames
            int j = i + 1;
            while (j < length && p1[j] == p1[i] && p2[j] == p2[i]) j++;
            o.writeShort(p1[i]); o.writeShort(p2[i]); o.writeInt(j - i);
            i = j;
        }
        o.flush();
        gz.finish();
    }

    private static Movie header(DataInputStream d, int[] frames) throws IOException {
        if (d.readInt() != MAGIC) throw new IOException("Not a SuperJavendo movie");
        int version = d.readInt();
        if (version > VERSION) throw new IOException("Unsupported movie version " + version);
        Movie m = new Movie(d.readInt(), d.readUTF());
        frames[0] = d.readInt();
        m.endHash = d.readInt();
        if (version >= 2) { m.label = d.readUTF(); m.createdAt = d.readLong(); m.verified = d.readUnsignedByte(); }
        else m.createdAt = 0;
        return m;
    }

    /** Reads only the header (cheap: the inputs are not decompressed). */
    public static Info readInfo(java.nio.file.Path file) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            int[] n = new int[1];
            Movie m = header(new DataInputStream(new GZIPInputStream(new BufferedInputStream(in))), n);
            return new Info(m.romCrc, m.title, n[0], m.endHash, m.label, m.createdAt, m.verified);
        }
    }

    public static Movie read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(new GZIPInputStream(in));
        int[] count = new int[1];
        Movie m = header(d, count);
        int n = count[0];
        while (m.length < n) {
            int a = d.readUnsignedShort(), b = d.readUnsignedShort(), run = d.readInt();
            if (run <= 0 || m.length + run > n) throw new IOException("Corrupt movie");
            for (int k = 0; k < run; k++) m.append(a, b);
        }
        return m;
    }

    public void save(java.nio.file.Path file) throws IOException {
        java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream o = java.nio.file.Files.newOutputStream(file)) { write(o); }
    }

    public static Movie load(java.nio.file.Path file) throws IOException {
        try (InputStream i = java.nio.file.Files.newInputStream(file)) { return read(new BufferedInputStream(i)); }
    }
}
