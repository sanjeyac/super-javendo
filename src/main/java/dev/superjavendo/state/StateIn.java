package dev.superjavendo.state;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Counterpart of {@link StateOut}. Array readers fill the existing array and verify its size. */
public final class StateIn {
    private final DataInputStream d;

    public StateIn(InputStream in) { this.d = new DataInputStream(in); }

    public int i() throws IOException { return d.readInt(); }
    public long l() throws IOException { return d.readLong(); }
    public boolean b() throws IOException { return d.readBoolean(); }
    public String str() throws IOException { return d.readUTF(); }

    private void expect(int len, int actual) throws IOException {
        if (len != actual) throw new IOException("Save state is incompatible (array size " + len + " != " + actual + ")");
    }

    /** Reads a blob written by {@link StateOut#blob}; empty blobs come back as an empty array. */
    public byte[] blob() throws IOException {
        int n = d.readInt();
        if (n < 0 || n > 16_000_000) throw new IOException("Corrupt save state (blob size " + n + ")");
        byte[] a = new byte[n];
        d.readFully(a);
        return a;
    }
    public void bytes(byte[] a) throws IOException { expect(d.readInt(), a.length); d.readFully(a); }
    public void ints(int[] a) throws IOException { expect(d.readInt(), a.length); for (int k = 0; k < a.length; k++) a[k] = d.readInt(); }
    public void bools(boolean[] a) throws IOException { expect(d.readInt(), a.length); for (int k = 0; k < a.length; k++) a[k] = d.readBoolean(); }
    public void ints2(int[][] a) throws IOException { expect(d.readInt(), a.length); for (int[] r : a) ints(r); }
}
