package dev.superjavendo.movie.rec;

import dev.superjavendo.input.Joypad;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RecEngineTest {
    @Test void commandsRunOnTheEngineThreadAndPublishImmutableStatus() throws Exception {
        RecSession s = new RecSession(RecTestRom.readsPad(), new InputTimeline(), 1L << 28);
        CopyOnWriteArrayList<RecEngine.Status> seen = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread[] where = new Thread[1];
        try (RecEngine e = new RecEngine(s, st -> { seen.add(st); if (st.view().frontier() == 3) done.countDown(); })) {
            for (int i = 0; i < 3; i++) e.run(x -> { where[0] = Thread.currentThread(); x.advance(); });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread(), where[0]);
            var last = seen.get(seen.size() - 1);
            assertEquals(3, last.view().frontier());
            e.run(x -> x.toggle(0, 1, Joypad.A));
            e.run(x -> { });
            Thread.sleep(300);
            // statuses already published do not change when the session moves on
            assertEquals(3, last.view().frontier());
            assertEquals(1, seen.get(seen.size() - 1).view().firstInvalid());
        } finally { s.close(); }
    }

    @Test void commandErrorsAreReportedNotThrown() throws Exception {
        RecSession s = new RecSession(RecTestRom.readsPad(), new InputTimeline(), 1L << 28);
        CopyOnWriteArrayList<RecEngine.Status> seen = new CopyOnWriteArrayList<>();
        try (RecEngine e = new RecEngine(s, seen::add)) {
            e.run(x -> x.toMovie("nothing yet"));
            Thread.sleep(300);
            assertNotNull(seen.get(seen.size() - 1).error());
        } finally { s.close(); }
    }

    @Test void playingAdvancesAndStopsWhenAskedAndLongCommandsReportProgress() throws Exception {
        RecSession s = new RecSession(RecTestRom.readsPad(), new InputTimeline(), 1L << 28);
        CopyOnWriteArrayList<RecEngine.Status> seen = new CopyOnWriteArrayList<>();
        try (RecEngine e = new RecEngine(s, seen::add)) {
            e.speed(8);
            e.playing(true);
            Thread.sleep(600);
            e.playing(false);
            Thread.sleep(300);
            int f = seen.get(seen.size() - 1).view().frontier();
            assertTrue(f > 5, "frontier " + f);
            Thread.sleep(300);
            assertEquals(f, seen.get(seen.size() - 1).view().frontier(), "stopped");
            e.runLong(40, (x, progress, cancelled) -> x.advanceTo(f + 40, progress, cancelled));
            Thread.sleep(2500);
            var last = seen.get(seen.size() - 1);
            assertFalse(last.busy());
            assertEquals(f + 40, last.view().frontier());
            assertTrue(seen.stream().anyMatch(RecEngine.Status::busy));
        } finally { s.close(); }
    }
}
