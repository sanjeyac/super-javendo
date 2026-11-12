package dev.superjavendo.input;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class GamepadBackendTest {
    /** A scripted pad: replays the events, then waits until the reader stops. */
    private static final class Fake implements GamepadBackend {
        final CountDownLatch delivered = new CountDownLatch(1);
        @Override public boolean connected(int index) { return index == 0; }
        @Override public String name(int index) { return "Fake pad"; }
        @Override public List<Integer> present() { return List.of(0); }
        @Override public String label(int index) { return "fake" + index; }
        @Override public void read(int index, Sink sink, BooleanSupplier running) throws IOException {
            sink.button(0, true, true);        // replay of the state at open time
            sink.button(7, true, false);
            sink.axis(1, 30000, false);
            delivered.countDown();
            while (running.getAsBoolean()) { try { Thread.sleep(2); } catch (InterruptedException e) { return; } }
        }
    }

    @AfterEach void restore() { GamepadBackends.use(null); }

    @Test void gamepadInputWorksOnAnyBackend() throws Exception {
        Fake f = new Fake();
        GamepadBackends.use(f);
        GamepadInput g = new GamepadInput(ControllerProfile.xboxGamepad(0));
        assertTrue(f.delivered.await(5, TimeUnit.SECONDS));
        int expected = Joypad.B | Joypad.START | Joypad.DOWN;
        long end = System.currentTimeMillis() + 2000;
        while (g.poll() != expected && System.currentTimeMillis() < end) Thread.sleep(5);
        assertEquals(expected, g.poll());
        g.close();
    }

    @Test void monitorAndCaptureUseTheBackend() throws Exception {
        GamepadBackends.use(new Fake());
        List<String> got = new ArrayList<>();
        GamepadMonitor m = new GamepadMonitor(0, 16000, (t, down) -> { synchronized (got) { got.add(t + (down ? "↓" : "↑")); } });
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) { synchronized (got) { if (got.size() >= 2) break; } Thread.sleep(5); }
        m.close();
        synchronized (got) { assertEquals(List.of("BUTTON_7↓", "AXIS_1+↓"), got); }

        CountDownLatch done = new CountDownLatch(1);
        String[] token = new String[1];
        GamepadCapture.next(0, 3000, t -> { token[0] = t; done.countDown(); }, e -> done.countDown());
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("BUTTON_7", token[0]);                   // the replayed initial state is skipped
    }

    @Test void devicesFollowTheBackend() {
        GamepadBackends.use(new Fake());
        assertEquals(List.of(0), GamepadDevices.present());
        assertEquals("Fake pad", GamepadDevices.name(0));
        assertTrue(GamepadDevices.connected(0));
        assertFalse(GamepadDevices.connected(1));
        assertEquals("fake0", GamepadDevices.label(0));
    }

    @Test void backendChoiceByOsAndOverride() {
        assertInstanceOf(JsBackend.class, GamepadBackends.choose(null, "Linux"));
        assertInstanceOf(Input4jBackend.class, GamepadBackends.choose(null, "Mac OS X"));
        assertInstanceOf(Input4jBackend.class, GamepadBackends.choose("", "Windows 11"));
        assertInstanceOf(Input4jBackend.class, GamepadBackends.choose("input4j", "Linux"));
        assertInstanceOf(JsBackend.class, GamepadBackends.choose(" JS ", "Windows 11"));
    }

    @Test void input4jWithoutPadsDegradesToNothing() {
        // headless CI: the library may start with no devices or not start at all; either way no pad and no exception
        Input4jBackend b = new Input4jBackend();
        assertDoesNotThrow(b::present);
        assertFalse(b.connected(99));
        assertEquals("", b.name(99));
        assertEquals("pad 3", b.label(3));
    }
}
