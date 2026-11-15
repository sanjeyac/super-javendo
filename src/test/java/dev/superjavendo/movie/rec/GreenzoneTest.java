package dev.superjavendo.movie.rec;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GreenzoneTest {
    private static byte[] noise(int seed, int n) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    @Test void roundTripAndNearest() {
        Greenzone g = new Greenzone(1 << 30);
        byte[] a = noise(1, 5000);
        g.put(0, noise(0, 5000));
        g.put(60, a);
        assertArrayEquals(a, g.get(60));
        assertEquals(60, g.nearestAtOrBefore(100));
        assertEquals(0, g.nearestAtOrBefore(59));
        assertNull(g.get(61));
    }

    @Test void invalidateFromDropsThatFrameAndLater() {
        Greenzone g = new Greenzone(1 << 30);
        for (int k = 0; k <= 10; k++) g.put(k, noise(k, 1000));
        g.invalidateFrom(6);
        assertTrue(g.has(5));
        assertFalse(g.has(6));
        assertEquals(6, g.count());
    }

    @Test void policyKeepsEverySixtiethAndTheLastHundredTwenty() {
        Greenzone g = new Greenzone(1 << 30);
        for (int k = 0; k <= 400; k++) g.put(k, noise(k, 200));
        g.enforce(400);
        for (int k = 0; k <= 400; k++) {
            boolean expected = k == 0 || k % 60 == 0 || k >= 400 - 120;
            assertEquals(expected, g.has(k), "frame " + k);
        }
    }

    @Test void memoryCapThinsOldestFirstAndNeverFrameZeroOrTheNewest() {
        byte[] sample = noise(7, 20000);
        Greenzone probe = new Greenzone(1 << 30);
        probe.put(0, sample);
        long one = probe.bytes();
        long cap = one * 12;                          // room for about twelve snapshots
        Greenzone g = new Greenzone(cap);
        for (int k = 0; k <= 3000; k++) {
            g.put(k, noise(k, 20000));
            g.enforce(k);
            assertTrue(g.bytes() <= cap + one, "over the cap at " + k + ": " + g.bytes());
        }
        assertTrue(g.has(0));
        assertTrue(g.has(3000));
        assertTrue(g.bytes() <= cap);
        // the recent frames outlive the old ones: if any dense frame was dropped, no older sparse frame is denser than it
        int lastDense = -1;
        for (int k = 3000; k > 2880; k--) if (g.has(k)) lastDense++;
        int oldKept = 0;
        for (int k = 1; k < 2000; k++) if (g.has(k)) oldKept++;
        assertTrue(lastDense >= oldKept || oldKept <= 4, "old=" + oldKept + " recent=" + lastDense);
    }
}
