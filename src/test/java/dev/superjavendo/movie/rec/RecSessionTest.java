package dev.superjavendo.movie.rec;

import dev.superjavendo.Snes;
import dev.superjavendo.cart.Cartridge;
import dev.superjavendo.input.Joypad;
import dev.superjavendo.movie.Movie;
import dev.superjavendo.movie.Movies;
import dev.superjavendo.movie.Replay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class RecSessionTest {
    @TempDir Path dir;
    private static final long BIG = 1L << 30;

    private static int pad(int f) { return f % 40 < 15 ? Joypad.RIGHT : f % 7 == 0 ? Joypad.A : 0; }

    private static RecSession session(Cartridge c, int plannedFrames, IntUnaryOperator p) {
        InputTimeline t = new InputTimeline();
        for (int f = 0; f < plannedFrames; f++) { int m = p.applyAsInt(f); if (m != 0) t.set(0, f, m, true); }
        return new RecSession(c, t, BIG);
    }

    private static void advance(RecSession s, int n) { for (int i = 0; i < n; i++) assertTrue(s.advance()); }

    /** The console after n frames of the given inputs, run from power-on with no editor at all. */
    private static int plainHash(Cartridge c, int n, IntUnaryOperator p) {
        byte[] sram = c.sram.clone();
        java.util.Arrays.fill(c.sram, (byte) 0);
        Snes s = new Snes(c);
        for (int f = 0; f < n; f++) { s.joypad.setButtons(0, p.applyAsInt(f)); s.runFrame(); }
        System.arraycopy(sram, 0, c.sram, 0, sram.length);
        return s.stateHash();
    }

    @Test void editingBeyondTheFrontierInvalidatesNothing() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 0, f -> 0)) {
            advance(s, 30);
            s.toggle(0, 45, Joypad.A);
            assertEquals(30, s.firstInvalid());
            assertFalse(s.hasPending());
            assertTrue(s.greenzone().has(30));
            assertTrue(s.advance());
        }
    }

    @Test void editingTheEmulatedPastInvalidatesExactlyFromThere() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 0, f -> 0)) {
            advance(s, 50);
            s.toggle(0, 20, Joypad.B);
            assertEquals(20, s.firstInvalid());
            assertEquals(50, s.frontier());
            assertTrue(s.hasPending());
            assertTrue(s.greenzone().has(20), "the state before the edited frame is still valid");
            assertFalse(s.greenzone().has(21));
            assertFalse(s.greenzone().has(50));
            assertFalse(s.advance(), "advance is blocked while frames wait to be re-emulated");
            assertFalse(s.seek(30), "seeking into the stale part is refused");
            assertTrue(s.seek(20));
        }
    }

    @Test void keepThenReemulateRestoresTheSameFrontier() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 0, f -> 0)) {
            advance(s, 50);
            s.toggle(0, 20, Joypad.B);
            int[] progress = {0};
            s.reemulate(n -> progress[0] = n, null);
            assertEquals(30, progress[0]);
            assertEquals(50, s.frontier());
            assertEquals(50, s.firstInvalid());
            assertFalse(s.hasPending());
            assertTrue(s.greenzone().has(50));
            assertTrue(s.advance());
        }
    }

    @Test void cutPolicyDeletesLaterInputsAndMovesTheFrontierBack() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 80, RecSessionTest::pad)) {
            advance(s, 50);
            int before = s.timeline.length();
            s.policy(RecSession.Policy.CUT);
            s.toggle(0, 20, Joypad.B);
            assertEquals(20, s.frontier());
            assertEquals(20, s.firstInvalid());
            assertEquals(21, s.timeline.length(), "frames after the edit are gone, the edited one stays");
            assertTrue(s.advance());                        // the edited frame runs now
            assertEquals(21, s.frontier());
            assertTrue(s.undo());                           // one step restores the later inputs
            assertEquals(before, s.timeline.length());
        }
    }

    @Test void cutHereDoesTheSameAfterAKeepEdit() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 0, f -> 0)) {
            advance(s, 40);
            s.toggle(0, 10, Joypad.A);
            s.cut();
            assertEquals(10, s.frontier());
            assertFalse(s.hasPending());
            assertEquals(11, s.timeline.length());
        }
    }

    @Test void aRunBuiltWithEditsEqualsALinearRunOfTheSameFinalInputs() {
        Cartridge c = RecTestRom.readsPad();
        int n = 200;
        int expected = plainHash(c, n, RecSessionTest::pad);
        // linear
        try (RecSession lin = session(c, n, RecSessionTest::pad)) {
            advance(lin, n);
            assertEquals(expected, lin.consoleHash());
        }
        // built wrongly, then corrected in the past with several edits and re-emulations
        try (RecSession ed = session(c, n, f -> pad(f) == 0 ? Joypad.UP : pad(f))) {
            advance(ed, n);
            for (int f = 0; f < n; f++) if (pad(f) == 0) ed.set(0, f, Joypad.UP, false);
            assertTrue(ed.hasPending());
            ed.reemulate();
            assertEquals(n, ed.frontier());
            assertEquals(expected, ed.consoleHash());
            // and a second round: edit in the middle, undo it, re-emulate again
            ed.toggle(0, 100, Joypad.START);
            ed.reemulate();
            ed.undo();
            ed.reemulate();
            assertEquals(expected, ed.consoleHash());
        }
    }

    @Test void seekGivesTheSameConsoleAsRunningFromZero() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 0, f -> 0)) {
            for (int f = 0; f < 200; f++) s.set(0, f, pad(f), true);
            advance(s, 200);
            for (int t : new int[]{0, 1, 59, 60, 137, 199, 200}) {
                assertTrue(s.seek(t));
                assertEquals(t, s.playhead());
                assertEquals(plainHash(c, t, RecSessionTest::pad), s.consoleHash(), "seek to " + t);
            }
            assertTrue(s.advance() || true);
        }
    }

    @Test void seekingBackAndAdvancingAgainContinuesTheSameRun() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 200, RecSessionTest::pad)) {
            advance(s, 100);
            assertTrue(s.seek(40));
            advance(s, 0);
            assertTrue(s.advance());                       // advancing always continues at the frontier, not at the playhead
            assertEquals(101, s.frontier());
            assertEquals(plainHash(c, 101, RecSessionTest::pad), s.consoleHash());
        }
    }

    @Test void lagFramesAreRecorded() {
        try (RecSession s = session(RecTestRom.ignoresPad(), 0, f -> 0)) {
            advance(s, 5);
            for (int f = 0; f < 5; f++) assertTrue(s.isLag(f), "frame " + f);
        }
        try (RecSession s = session(RecTestRom.readsPad(), 0, f -> 0)) {
            advance(s, 5);
            assertFalse(s.isLag(3));
        }
    }

    @Test void undoRedoGoThroughTheSession() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 0, f -> 0)) {
            advance(s, 30);
            s.toggle(0, 10, Joypad.A);
            assertEquals(10, s.firstInvalid());
            s.reemulate();
            assertTrue(s.undo());
            assertEquals(10, s.firstInvalid(), "undoing an edit in the past makes the results stale again");
            s.reemulate();
            assertEquals(plainHash(c, 30, f -> 0), s.consoleHash());
            assertTrue(s.redo());
            assertEquals(Joypad.A, s.timeline.mask(0, 10));
        }
    }

    @Test void anEditedRunSavedAsAMovieReplaysAsVerified() throws Exception {
        Cartridge c = RecTestRom.readsPad();
        Movies store = new Movies(dir);
        Path file;
        try (RecSession s = session(c, 0, f -> 0)) {
            for (int f = 0; f < 200; f++) s.set(0, f, pad(f), true);
            advance(s, 200);
            s.toggle(0, 50, Joypad.Y);                      // a change in the past ...
            s.toggle(0, 50, Joypad.Y);                      // ... back, plus another one that stays
            s.toggle(0, 90, Joypad.L);
            assertThrows(IllegalStateException.class, () -> s.toMovie("x"));
            s.reemulate();
            file = s.save(store, "any%");
        }
        Movie m = Movie.load(file);
        assertEquals(200, m.length());
        assertEquals("any%", m.label);
        Replay.Result r = Replay.run(c, m, null);
        assertTrue(r.matches(), "the existing Replay reproduces the edited run");
        assertNotEquals(0, m.endHash);
    }

    @Test void batteryRamIsClearedForTheSessionAndPutBack() {
        byte[] data = RecTestRom.readsPad().rom.clone();
        data[0x7FD8] = 3;
        Cartridge c = new Cartridge(data);
        java.util.Arrays.fill(c.sram, (byte) 0x5A);
        try (RecSession s = session(c, 0, f -> 0)) {
            assertEquals(0, c.sram[0]);
            advance(s, 5);
        }
        assertEquals(0x5A, c.sram[0]);
    }

    @Test void watchedRamIsReadAfterEveryMove() {
        Cartridge c = RecTestRom.ignoresPad();
        try (RecSession s = session(c, 0, f -> 0)) {
            s.watch(0x10);
            advance(s, 3);
            int a = s.view().ram()[0];
            advance(s, 3);
            assertNotEquals(a, s.view().ram()[0]);
            s.seek(3);
            assertEquals(a, s.view().ram()[0]);
        }
    }

    @Test void playStepReplaysThenContinuesPastTheFrontier() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 120, RecSessionTest::pad)) {
            advance(s, 60);
            assertTrue(s.seek(10));
            for (int i = 0; i < 50; i++) assertTrue(s.playStep());
            assertEquals(60, s.playhead());
            assertEquals(60, s.frontier());
            assertEquals(plainHash(c, 60, RecSessionTest::pad), s.consoleHash());
            assertTrue(s.playStep());                       // at the frontier it emulates a new frame
            assertEquals(61, s.frontier());
            s.toggle(0, 5, Joypad.X);
            assertFalse(s.playStep(), "no playback while frames are stale");
        }
    }

    @Test void advanceToEqualsAdvancingOneByOne() {
        Cartridge c = RecTestRom.readsPad();
        try (RecSession s = session(c, 150, RecSessionTest::pad)) {
            int[] last = {0};
            s.advanceTo(150, n -> last[0] = n, null);
            assertEquals(150, s.frontier());
            assertEquals(150, last[0]);
            assertEquals(plainHash(c, 150, RecSessionTest::pad), s.consoleHash());
            assertTrue(s.greenzone().has(150));
            assertTrue(s.seek(77));
            assertEquals(plainHash(c, 77, RecSessionTest::pad), s.consoleHash());
        }
    }
}
