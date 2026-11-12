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
        GamepadBackend.Sink sink = m.sink();
        JsBackend.decode(ev(1, 1, 3), sink);
        JsBackend.decode(ev(0, 1, 3), sink);
        JsBackend.decode(ev(-30000, 2, 7), sink);
        JsBackend.decode(ev(-30000, 2, 7), sink);            // still past the threshold: no new event
        JsBackend.decode(ev(5000, 2, 7), sink);
        JsBackend.decode(ev(30000, 2, 7), sink);
        JsBackend.decode(ev(1, 0x81, 4), sink);              // initial state replay is ignored
        m.close();
        assertEquals(List.of("BUTTON_3↓", "BUTTON_3↑", "AXIS_7-↓", "AXIS_7-↑", "AXIS_7+↓"), got);
    }
}
