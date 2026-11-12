package dev.superjavendo.input;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GamepadInputTest {
    private static void ev(ByteArrayOutputStream o, int value, int type, int number) {
        o.write(0); o.write(0); o.write(0); o.write(0);
        o.write(value & 0xFF); o.write((value >> 8) & 0xFF);
        o.write(type); o.write(number);
    }

    private static int run(ControllerProfile p, ByteArrayOutputStream o) throws Exception {
        GamepadInput g = new GamepadInput(p);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(o.toByteArray()));
        JsBackend.parse(in, g.sink(), () -> { try { return in.available() > 0; } catch (java.io.IOException e) { return false; } });
        int m = g.poll();
        g.close();
        return m;
    }

    // device index 9: nothing on this machine, so the background thread stays idle
    private final ControllerProfile pad = ControllerProfile.xboxGamepad(9);

    @Test void profileButtonsDriveTheMask() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ev(o, 1, 1, 0); ev(o, 1, 1, 7); ev(o, 1, 1, 4);
        assertEquals(Joypad.B | Joypad.START | Joypad.L, run(pad, o));
    }

    @Test void releaseClearsButton() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ev(o, 1, 1, 1); ev(o, 0, 1, 1);
        assertEquals(0, run(pad, o));
    }

    @Test void hatAndStick() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ev(o, -32767, 2, 6); ev(o, 30000, 2, 1);
        assertEquals(Joypad.LEFT | Joypad.DOWN, run(pad, o));
    }

    @Test void initialStateFlagIsHonoured() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ev(o, 1, 0x81, 3);
        assertEquals(Joypad.X, run(pad, o));
    }

    @Test void customProfileRemapsButtons() throws Exception {
        ControllerProfile custom = ControllerProfile.gamepad("custom", 9, Map.of(0, Joypad.A), List.of(), 12000);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ev(o, 1, 1, 0); ev(o, 1, 1, 1);          // button 1 is unmapped in this profile
        assertEquals(Joypad.A, run(custom, o));
    }

    @Test void keyboardStrategyTracksKeys() {
        KeyboardInput k = new KeyboardInput(ControllerProfile.keyboardPlayer1());
        assertTrue(k.onKey(KeyEvent.VK_Z, true));
        assertTrue(k.onKey(KeyEvent.VK_LEFT, true));
        assertEquals(Joypad.B | Joypad.LEFT, k.poll());
        k.onKey(KeyEvent.VK_Z, false);
        assertEquals(Joypad.LEFT, k.poll());
        assertFalse(k.onKey(KeyEvent.VK_F12, true));
    }

    @Test void portsAreIndependentAndSwappable() {
        ControllerFactory f = new ControllerFactory();
        ControllerPorts ports = new ControllerPorts();
        ports.plug(0, f.create(ControllerProfile.keyboardPlayer1()));
        ports.plug(1, f.create(ControllerProfile.keyboardPlayer2()));
        ports.onKey(KeyEvent.VK_Z, true);     // P1 B
        ports.onKey(KeyEvent.VK_J, true);     // P2 left
        Joypad jp = new Joypad();
        ports.apply(jp);
        jp.writeStrobe(1); jp.writeStrobe(0);
        assertEquals(1, jp.readBit(0));       // P1 B is the first bit serialised
        assertEquals(0, jp.readBit(1));       // P2 has no B pressed
        ports.plug(0, new NullInput());
        ports.apply(jp);
        jp.writeStrobe(1); jp.writeStrobe(0);
        assertEquals(0, jp.readBit(0));
    }

    @Test void compositeOrsInputsAndFactoryRegistersNewKinds() {
        ControllerInput a = () -> Joypad.A, b = () -> Joypad.B;
        assertEquals(Joypad.A | Joypad.B, new CompositeInput(List.of(a, b)).poll());
        ControllerFactory f = new ControllerFactory().register(ControllerProfile.Type.GAMEPAD, p -> a);
        assertEquals(Joypad.A, f.create(ControllerProfile.xboxGamepad(0)).poll());
    }
}
