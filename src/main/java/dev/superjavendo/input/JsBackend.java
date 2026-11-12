package dev.superjavendo.input;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** The Linux joystick API: {@code /dev/input/jsN}, named from {@code /sys/class/input/jsN/device/name}. No native code. */
public final class JsBackend implements GamepadBackend {
    @Override public boolean connected(int index) { return Files.isReadable(Path.of("/dev/input/js" + index)); }

    @Override public String name(int index) {
        try { return Files.readString(Path.of("/sys/class/input/js" + index + "/device/name"), StandardCharsets.UTF_8).trim(); }
        catch (IOException e) { return ""; }
    }

    @Override public List<Integer> present() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) if (Files.exists(Path.of("/dev/input/js" + i))) out.add(i);
        return out;
    }

    @Override public String label(int index) { return "js" + index; }

    @Override public void read(int index, Sink sink, BooleanSupplier running) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream("/dev/input/js" + index))) {
            parse(in, sink, running);
        }
    }

    /**
     * Parses js_event records (8 bytes) until {@code running} turns false or the stream ends. Reads block: do NOT poll
     * {@code available()}, which throws "Illegal seek" on /dev/input/js* (and looked like a disconnect). The reader runs
     * on a daemon thread, so a stop request takes effect on the next event. Package-private for tests.
     */
    static void parse(DataInputStream in, Sink sink, BooleanSupplier running) throws IOException {
        byte[] raw = new byte[8];
        while (running.getAsBoolean()) {
            in.readFully(raw);
            decode(raw, sink);
        }
    }

    /** One js_event. Package-private for tests. */
    static void decode(byte[] raw, Sink sink) {
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.getInt();                                   // timestamp
        short value = b.getShort();
        int type = b.get() & 0xFF, number = b.get() & 0xFF;
        boolean initial = (type & 0x80) != 0;
        type &= 0x7F;
        if (type == 1) sink.button(number, value != 0, initial);
        else if (type == 2) sink.axis(number, value, initial);
    }
}
