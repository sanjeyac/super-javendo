package dev.superjavendo.movie.rec;

import dev.superjavendo.input.Joypad;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InputTimelineTest {
    private static int[] dump(InputTimeline t) {
        int[] out = new int[t.length() * 2];
        for (int f = 0; f < t.length(); f++) { out[2 * f] = t.mask(0, f); out[2 * f + 1] = t.mask(1, f); }
        return out;
    }

    @Test void toggleReportsTheFirstChangedFrameAndNoneWhenNothingChanges() {
        InputTimeline t = new InputTimeline();
        assertEquals(5, t.toggle(0, 5, Joypad.A));
        assertEquals(Joypad.A, t.mask(0, 5));
        assertEquals(6, t.length());
        assertEquals(InputTimeline.NONE, t.set(0, 5, Joypad.A, true));
        assertEquals(InputTimeline.NONE, t.setRange(0, 5, 5, Joypad.A, true));
    }

    @Test void everyEditIsReversible() {
        InputTimeline t = new InputTimeline();
        t.setRange(0, 0, 9, Joypad.RIGHT, true);
        int[][] steps = new int[5][];
        steps[0] = dump(t);
        t.toggle(1, 3, Joypad.B);                    steps[1] = dump(t);
        t.insert(4);                                  steps[2] = dump(t);
        t.delete(0);                                  steps[3] = dump(t);
        t.paste(0, 2, new int[]{Joypad.A, Joypad.X}); steps[4] = dump(t);
        for (int i = 3; i >= 0; i--) { assertNotEquals(InputTimeline.NONE, t.undo()); assertArrayEquals(steps[i], dump(t), "undo to step " + i); }
        for (int i = 1; i <= 4; i++) { assertNotEquals(InputTimeline.NONE, t.redo()); assertArrayEquals(steps[i], dump(t), "redo to step " + i); }
        assertEquals(InputTimeline.NONE, t.redo());
    }

    @Test void insertAndDeleteShiftTheFuture() {
        InputTimeline t = new InputTimeline();
        t.toggle(0, 2, Joypad.A);
        t.toggle(0, 3, Joypad.B);
        assertEquals(2, t.insert(2));
        assertEquals(0, t.mask(0, 2));
        assertEquals(Joypad.A, t.mask(0, 3));
        assertEquals(Joypad.B, t.mask(0, 4));
        assertEquals(2, t.delete(2));
        assertEquals(Joypad.A, t.mask(0, 2));
    }

    @Test void groupIsOneUndoStepAndTruncateKeepsTheFrame() {
        InputTimeline t = new InputTimeline();
        t.setRange(0, 0, 19, Joypad.A, true);
        int lo = t.group(() -> { int f = t.toggle(0, 5, Joypad.A); t.truncateAfter(5); return f; });
        assertEquals(5, lo);
        assertEquals(6, t.length());
        assertEquals(0, t.mask(0, 5));
        assertEquals(5, t.undo());                       // one step undoes both the toggle and the cut
        assertEquals(20, t.length());
        assertEquals(Joypad.A, t.mask(0, 5));
    }

    @Test void copyPasteKeepsOtherPadAndResetFlag() {
        InputTimeline t = new InputTimeline();
        t.setRange(0, 0, 3, Joypad.L, true);
        t.toggle(1, 1, Joypad.Y);
        int[] clip = t.copy(0, 0, 3);
        t.paste(0, 10, clip);
        assertEquals(Joypad.L, t.mask(0, 12));
        assertEquals(Joypad.Y, t.mask(1, 1));
    }

    @Test void freezeIsACopyUntilTheNextChange() {
        InputTimeline t = new InputTimeline();
        t.toggle(0, 1, Joypad.A);
        var f = t.freeze();
        assertSame(f, t.freeze());
        t.toggle(0, 1, Joypad.A);
        assertEquals(Joypad.A, f.mask(0, 1));
        assertEquals(0, t.freeze().mask(0, 1));
    }
}
