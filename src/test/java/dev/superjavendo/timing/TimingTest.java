package dev.superjavendo.timing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimingTest {
    @Test void blankingPositions() {
        Timing t = new Timing();
        t.v = 224; assertFalse(t.inVBlank());
        t.v = Timing.VBLANK_START; assertTrue(t.inVBlank());
        t.h = 1095; assertFalse(t.inHBlank());
        t.h = 1096; assertTrue(t.inHBlank());
    }

    @Test void counterLatchKeepsTheCurrentPosition() {
        Timing t = new Timing();
        t.h = 4 * 77 + 3; t.v = 123;
        t.latch();
        t.h = 0; t.v = 0;
        assertEquals(77, t.latchedH);
        assertEquals(123, t.latchedV);
    }

    @Test void irqClockForEachMode() {
        Timing t = new Timing();
        t.htime = 100; t.vtime = 50; t.v = 50;
        t.nmitimen = 0x00; assertEquals(-1, t.irqClock());
        t.nmitimen = 0x10; assertEquals(100 * 4 + 14, t.irqClock());            // every line
        t.v = 7;           assertEquals(100 * 4 + 14, t.irqClock());
        t.nmitimen = 0x20; t.v = 50; assertEquals(14, t.irqClock());            // V only
        t.v = 51;                    assertEquals(-1, t.irqClock());
        t.nmitimen = 0x30; t.v = 50; assertEquals(100 * 4 + 14, t.irqClock());  // H and V
        t.v = 51;                    assertEquals(-1, t.irqClock());
        t.htime = 340; t.v = 50;     assertEquals(-1, t.irqClock());            // H beyond the line never fires
        t.nmitimen = 0x10;           assertEquals(-1, t.irqClock());
    }

    @Test void stateRoundTrip() throws Exception {
        Timing t = new Timing();
        t.v = 10; t.h = 20; t.nmiFlag = true; t.htime = 33; t.vtime = 44; t.nmitimen = 0x81;
        var bo = new java.io.ByteArrayOutputStream();
        t.save(new dev.superjavendo.state.StateOut(bo));
        Timing u = new Timing();
        u.load(new dev.superjavendo.state.StateIn(new java.io.ByteArrayInputStream(bo.toByteArray())));
        assertEquals(10, u.v); assertEquals(20, u.h); assertTrue(u.nmiFlag);
        assertEquals(33, u.htime); assertEquals(44, u.vtime); assertEquals(0x81, u.nmitimen);
    }
}
