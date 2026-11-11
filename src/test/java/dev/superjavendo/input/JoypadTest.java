package dev.superjavendo.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JoypadTest {
    @Test void manualReadShiftsOutTheButtonsAfterAStrobe() {
        Joypad j = new Joypad();
        j.setButtons(0, Joypad.B | Joypad.START);
        j.writeStrobe(1); j.writeStrobe(0);
        int[] bits = new int[16];
        for (int i = 0; i < 16; i++) bits[i] = j.readBit(0);
        assertEquals(1, bits[0]);                 // B
        assertEquals(1, bits[3]);                 // Start
        assertEquals(0, bits[1]);                 // Y
        assertEquals(1, j.readBit(0));            // after 16 bits the pad answers 1
    }

    @Test void afterTheAutoReadTheSerialRegistersAreEmpty() {
        // Games (the Super Multitap routine of Dragon: The Bruce Lee Story) read $4016/$4017 without strobing first and
        // expect the 1s a standard pad shifts out once the auto-read has clocked all its bits.
        Joypad j = new Joypad();
        j.setButtons(0, 0);
        j.autoRead();
        assertEquals(1, j.readBit(0));
        assertEquals(1, j.readBit(1));
        // ... and a new strobe loads the current buttons again (the auto-read registers are a separate copy)
        j.setButtons(0, Joypad.B);
        j.writeStrobe(1); j.writeStrobe(0);
        assertEquals(1, j.readBit(0));
        assertEquals(0, j.readBit(0));
    }
}
