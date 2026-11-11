package dev.superjavendo.input;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GamepadMonitorTest {
    private static byte[] ev(int value, int type, int number) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putShort((short) value).put((byte) type).put((byte) number).array();
    }

    @Test void turnsJsEventsIntoTokens() {
        List<String> got = new ArrayList<>();
        // device 99 does not exist, so the thread only waits; decode() is driven by hand
        GamepadMonitor m = new GamepadMonitor(99, 16000, (t, down) -> got.add(t + (down ? "↓" : "↑")));
        m.decode(ev(1, 1, 3));
        m.decode(ev(0, 1, 3));
        m.decode(ev(-30000, 2, 7));
        m.decode(ev(-30000, 2, 7));            // still past the threshold: no new event
        m.decode(ev(5000, 2, 7));
        m.decode(ev(30000, 2, 7));
        m.decode(ev(1, 0x81, 4));              // initial state replay is ignored
        m.close();
        assertEquals(List.of("BUTTON_3↓", "BUTTON_3↑", "AXIS_7-↓", "AXIS_7-↑", "AXIS_7+↓"), got);
    }
}
