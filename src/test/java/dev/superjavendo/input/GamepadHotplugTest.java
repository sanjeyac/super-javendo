package dev.superjavendo.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** A pad plugged in after the readers started must be picked up (no restart of the app). */
class GamepadHotplugTest {
    /** Device 0 appears when {@code plugged} turns true and then sends one button press. */
    private static final class LatePad implements GamepadBackend {
        volatile boolean plugged;
        @Override public boolean connected(int index) { return plugged && index == 0; }
        @Override public String name(int index) { return plugged ? "Late pad" : ""; }
        @Override public List<Integer> present() { return plugged ? List.of(0) : List.of(); }
        @Override public String label(int index) { return "late" + index; }
        @Override public void read(int index, Sink sink, BooleanSupplier running) throws IOException {
            sink.button(0, true, false);
            while (running.getAsBoolean()) { try { Thread.sleep(2); } catch (InterruptedException e) { return; } }
        }
    }

    @AfterEach void restore() { GamepadBackends.use(null); }

    @Test void monitorStartedBeforeThePadAppearsReceivesItsEvents() throws Exception {
        LatePad pad = new LatePad();
        GamepadBackends.use(pad);
        List<String> got = new CopyOnWriteArrayList<>();
        try (GamepadMonitor m = new GamepadMonitor(0, 12000, (t, down) -> got.add(t + (down ? "↓" : "↑")))) {
            Thread.sleep(150);
            assertTrue(got.isEmpty());
            pad.plugged = true;
            long end = System.currentTimeMillis() + 3000;
            while (got.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10);
            assertEquals(List.of("BUTTON_0↓"), got);
        }
    }

    @Test void gamepadInputStartedBeforeThePadAppearsPicksItUp() throws Exception {
        LatePad pad = new LatePad();
        GamepadBackends.use(pad);
        GamepadInput g = new GamepadInput(ControllerProfile.xboxGamepad(0));
        try {
            Thread.sleep(150);
            assertEquals(0, g.poll());
            pad.plugged = true;
            long end = System.currentTimeMillis() + 5000;      // GamepadInput looks again every 2 s
            while (g.poll() == 0 && System.currentTimeMillis() < end) Thread.sleep(20);
            assertNotEquals(0, g.poll());
        } finally { g.close(); }
    }
}
