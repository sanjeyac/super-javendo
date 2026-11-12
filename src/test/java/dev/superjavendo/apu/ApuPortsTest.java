package dev.superjavendo.apu;

import dev.superjavendo.cart.Msu1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ApuPortsTest {
    @TempDir Path tmp;

    @Test void communicationPortsPassBytesBothWays() {
        Apu a = new Apu();
        a.write(0xF4, 0x12);                                   // SPC -> CPU on port 0
        assertEquals(0x12, a.readPort(0));
        a.write(0xF7, 0x34);
        assertEquals(0x34, a.readPort(3));
        a.writePort(1, 0x56);                                  // CPU -> SPC on port 1
        assertEquals(0x56, a.read(0xF5));
        a.writePort(5, 0x77);                                  // port numbers wrap modulo 4: port 1 again
        assertEquals(0x77, a.read(0xF5));
    }

    @Test void controlRegisterClearsInputPortsAndTogglesTheIpl() {
        Apu a = new Apu();
        a.writePort(0, 1); a.writePort(1, 2); a.writePort(2, 3); a.writePort(3, 4);
        a.write(0xF1, 0x80 | 0x10);                            // clear ports 0/1, keep the IPL ROM
        assertEquals(0, a.read(0xF4)); assertEquals(0, a.read(0xF5));
        assertEquals(3, a.read(0xF6)); assertEquals(4, a.read(0xF7));
        a.write(0xF1, 0x80 | 0x20);
        assertEquals(0, a.read(0xF6)); assertEquals(0, a.read(0xF7));
        assertEquals(0xCD, a.read(0xFFC0));                    // IPL visible
        a.write(0xF1, 0x00);
        assertEquals(0x00, a.read(0xFFC0));                    // IPL hidden: RAM shows through
    }

    @Test void dspRegistersAreReachableThroughF2AndF3() {
        Apu a = new Apu();
        a.write(0xF2, 0x0C); a.write(0xF3, 0x55);
        assertEquals(0x0C, a.read(0xF2));
        assertEquals(0x55, a.read(0xF3));
        assertEquals(0x55, a.dsp.read(0x0C));
        a.write(0xF2, 0x8C); a.write(0xF3, 0x11);              // $80+ is read-only
        assertEquals(0x55, a.dsp.read(0x0C));
    }

    @Test void scratchRegistersAndRamBehaveLikeMemory() {
        Apu a = new Apu();
        a.write(0xF8, 0xAB); a.write(0xF9, 0xCD); a.write(0x1234, 0xEF);
        assertEquals(0xAB, a.read(0xF8)); assertEquals(0xCD, a.read(0xF9)); assertEquals(0xEF, a.read(0x1234));
        assertEquals(0, a.read(0xF0));                         // unused register
    }

    @Test void timersCountAndAreClearedByReading() {
        Apu a = new Apu();
        a.write(0xFA, 2);                                      // timer 0 target 2 (period 128 cycles)
        a.write(0xFC, 16);                                     // timer 2 target 16 (period 16 cycles)
        a.write(0xF1, 0x80 | 0x05);                            // enable timers 0 and 2
        a.tick(21_477 * 2);                                    // about 2 ms = 2048 SPC cycles
        int t0 = a.read(0xFD), t2 = a.read(0xFF);
        assertTrue(t0 >= 7 && t0 <= 9, "timer 0 ticked " + t0 + " times");        // 2048 / (128 * 2)
        assertTrue(t2 >= 7 && t2 <= 9, "timer 2 ticked " + t2 + " times");        // 2048 / (16 * 16)
        assertEquals(0, a.read(0xFD));                          // reading cleared it
        assertEquals(0, a.read(0xFE));                          // timer 1 was never enabled
    }

    @Test void timerTargetZeroMeans256AndDisabledTimersStayStill() {
        Apu a = new Apu();
        a.write(0xFA, 0);
        a.write(0xF1, 0x80 | 0x01);
        a.tick(21_477 * 2);
        assertEquals(0, a.read(0xFD));                          // 2048 cycles / (128 * 256) rounds down to 0
        a.write(0xF1, 0x80);
        a.tick(21_477);
        assertEquals(0, a.read(0xFD));
    }

    @Test void runningProducesDspSamplesForTheSink() {
        Apu a = new Apu();
        AtomicInteger n = new AtomicInteger();
        a.setSink((l, r) -> n.incrementAndGet());
        a.tick(21_477 * 10);                                   // 10 ms
        assertTrue(n.get() >= 300 && n.get() <= 340, "samples: " + n.get());     // 32 kHz
    }

    @Test void msuAudioIsMixedIntoTheSink() throws Exception {
        Apu a = new Apu();
        a.msu = new Msu1(tmp.resolve("game.sfc"));             // no companion files: silence, but the path is exercised
        AtomicInteger n = new AtomicInteger();
        a.setSink((l, r) -> n.incrementAndGet());
        a.tick(21_477 * 5);
        assertTrue(n.get() > 100);
    }

    @Test void resetRestoresTheBootState() {
        Apu a = new Apu();
        a.tick(200_000);
        assertEquals(0xAA, a.readPort(0));
        a.write(0x500, 0x42);
        a.reset();
        assertEquals(0, a.read(0x500));
        assertEquals(0xFFC0, a.spc.pc);
        a.tick(200_000);
        assertEquals(0xAA, a.readPort(0));
    }

    @Test void stateRoundTripResumesTheSameExecution() throws Exception {
        Apu a = new Apu();
        a.tick(100_000);
        a.write(0xFA, 5); a.write(0xF1, 0x81);
        var bo = new java.io.ByteArrayOutputStream();
        a.save(new dev.superjavendo.state.StateOut(bo));
        Apu b = new Apu();
        b.load(new dev.superjavendo.state.StateIn(new java.io.ByteArrayInputStream(bo.toByteArray())));
        assertEquals(a.spc.pc, b.spc.pc);
        a.tick(50_000); b.tick(50_000);
        assertEquals(a.spc.pc, b.spc.pc);
        assertEquals(a.read(0xFD), b.read(0xFD));
    }
}
