package dev.superjavendo.bus;

import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.timing.Timing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BusRegistersTest {
    private static Bus bus() {
        byte[] rom = new byte[0x8000];
        rom[0x7FD5] = 0x20;
        return new Bus(new Cartridge(rom));
    }

    @Test void wramPortAutoIncrementsAndAddressRegistersSelectTheByte() {
        Bus b = bus();
        b.write(0x2181, 0x10); b.write(0x2182, 0x00); b.write(0x2183, 0x01);      // WRAM address $10010
        b.write(0x2180, 0xAA); b.write(0x2180, 0xBB);
        assertEquals(0xAA, b.read(0x7F0010));
        assertEquals(0xBB, b.read(0x7F0011));
        b.write(0x2181, 0x10); b.write(0x2182, 0x00); b.write(0x2183, 0x01);
        assertEquals(0xAA, b.read(0x2180));
        assertEquals(0xBB, b.read(0x2180));
    }

    @Test void wramMirrorsAtTheStartOfEveryLowBank() {
        Bus b = bus();
        b.write(0x7E0123, 0x5A);
        assertEquals(0x5A, b.read(0x000123));
        assertEquals(0x5A, b.read(0x3F0123));
        assertEquals(0x5A, b.read(0x800123));
        b.write(0x7F0000, 0x66);
        assertEquals(0x66, b.read(0x7F0000));
        assertEquals(0x00, b.read(0x7E0000));
    }

    @Test void hardwareMultiplyAndDivide() {
        Bus b = bus();
        b.write(0x4202, 12); b.write(0x4203, 13);
        assertEquals(156, b.read(0x4216) | b.read(0x4217) << 8);
        b.write(0x4204, 0x10); b.write(0x4205, 0x27);                               // 10000
        b.write(0x4206, 7);
        assertEquals(1428, b.read(0x4214) | b.read(0x4215) << 8);
        assertEquals(4, b.read(0x4216) | b.read(0x4217) << 8);                      // remainder
        b.write(0x4206, 0);                                                         // divide by zero
        assertEquals(0xFFFF, b.read(0x4214) | b.read(0x4215) << 8);
        assertEquals(10000, b.read(0x4216) | b.read(0x4217) << 8);
    }

    @Test void ioPortAndMemorySelectRegisters() {
        Bus b = bus();
        assertEquals(0xFF, b.read(0x4213));
        b.write(0x4201, 0x3C);
        assertEquals(0x3C, b.read(0x4213));
        b.write(0x420D, 1);                                                         // fast ROM select: accepted
    }

    @Test void irqTimerRegistersFeedTheTimingUnit() {
        Bus b = bus();
        b.write(0x4207, 0x34); b.write(0x4208, 0x01);                               // H = $134
        b.write(0x4209, 0x56); b.write(0x420A, 0x01);                               // V = $156
        assertEquals(0x134, b.timing.htime);
        assertEquals(0x156, b.timing.vtime);
        b.write(0x4200, 0x30);
        b.timing.v = 0x156;
        assertEquals(0x134 * 4 + 14, b.timing.irqClock());
    }

    @Test void hvCounterLatchAndReadToggles() {
        Bus b = bus();
        b.timing.h = 400; b.timing.v = 100;
        b.read(0x2137);                                                             // latch
        assertEquals(100, b.read(0x213C));                                          // H low
        assertEquals(0, b.read(0x213C));                                            // H high bit
        assertEquals(100, b.read(0x213D));                                          // V low
        assertEquals(0, b.read(0x213D));
        b.read(0x213F);                                                             // reading STAT78 resets the toggles
        assertEquals(100, b.read(0x213C));
    }

    @Test void stat78ReportsTheInterlaceField() {
        Bus b = bus();
        int a = b.read(0x213F);
        assertEquals(0x03, a & 0x7F);
        b.ppu.frameStart();
        int c = b.read(0x213F);
        assertNotEquals(a & 0x80, c & 0x80, "the field bit toggles every frame");
    }

    @Test void ppuAndApuRegistersAreReachable() {
        Bus b = bus();
        b.write(0x2121, 3); b.write(0x2122, 0xFF); b.write(0x2122, 0x7F);           // CGRAM[3]
        b.write(0x2121, 3);
        assertEquals(0xFF, b.read(0x213B));
        assertEquals(0x7F, b.read(0x213B));
        b.apu.tick(200_000);
        assertEquals(0xAA, b.read(0x2140));                                         // IPL boot signature on port 0
    }

    @Test void openBusKeepsTheLastValueForUnmappedRegisterBits() {
        Bus b = bus();
        b.write(0x7E0000, 0x77);
        b.read(0x7E0000);
        assertEquals(0x77 & 0x7F, b.read(0x4211) & 0x7F);
        assertEquals(0x77 & 0x3E, b.read(0x4212) & 0x3E);
    }

    @Test void stateRoundTrip() throws Exception {
        Bus b = bus();
        b.write(0x7E0042, 0x99); b.write(0x4201, 0x12); b.write(0x4207, 0x77);
        var bo = new java.io.ByteArrayOutputStream();
        b.save(new dev.superjavendo.state.StateOut(bo));
        Bus c = bus();
        c.load(new dev.superjavendo.state.StateIn(new java.io.ByteArrayInputStream(bo.toByteArray())));
        assertEquals(0x99, c.read(0x7E0042));
        assertEquals(0x12, c.read(0x4213));
        assertEquals(0x77, c.timing.htime & 0xFF);
    }
}
