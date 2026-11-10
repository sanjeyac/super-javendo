package dev.superjavendo.state;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Tiny binary writer used by every component's {@code save}. */
public final class StateOut {
    private final DataOutputStream d;

    public StateOut(OutputStream out) { this.d = new DataOutputStream(out); }

    public void i(int v) throws IOException { d.writeInt(v); }
    public void l(long v) throws IOException { d.writeLong(v); }
    public void b(boolean v) throws IOException { d.writeBoolean(v); }
    public void str(String s) throws IOException { d.writeUTF(s); }

    /** Variable-length blob (null is written as empty). */
    public void blob(byte[] a) throws IOException { if (a == null) a = new byte[0]; d.writeInt(a.length); d.write(a); }
    public void bytes(byte[] a) throws IOException { d.writeInt(a.length); d.write(a); }
    public void ints(int[] a) throws IOException { d.writeInt(a.length); for (int v : a) d.writeInt(v); }
    public void bools(boolean[] a) throws IOException { d.writeInt(a.length); for (boolean v : a) d.writeBoolean(v); }
    public void ints2(int[][] a) throws IOException { d.writeInt(a.length); for (int[] r : a) ints(r); }

    public void flush() throws IOException { d.flush(); }
}
