package dev.superjavendo.frontend.modern;

import dev.superjavendo.config.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UndoTest {
    @Test void undoAndRedoRunInOppositeOrderAndReturnTheLabel() {
        UndoStack s = new UndoStack();
        List<String> log = new ArrayList<>();
        s.push("one", () -> log.add("undo1"), () -> log.add("redo1"));
        s.push("two", () -> log.add("undo2"), () -> log.add("redo2"));
        assertEquals("two", s.undo());
        assertEquals("one", s.undo());
        assertNull(s.undo());
        assertEquals("one", s.redo());
        assertEquals("two", s.redo());
        assertNull(s.redo());
        assertEquals(List.of("undo2", "undo1", "redo1", "redo2"), log);
    }

    @Test void aNewActionDiscardsTheRedoHistory() {
        UndoStack s = new UndoStack();
        s.push("a", () -> { }, () -> { });
        s.undo();
        assertTrue(s.canRedo());
        s.push("b", () -> { }, () -> { });
        assertFalse(s.canRedo());
        assertNull(s.redo());
    }

    @Test void historyIsBounded() {
        UndoStack s = new UndoStack();
        for (int i = 0; i < UndoStack.LIMIT + 50; i++) s.push("a" + i, () -> { }, () -> { });
        int n = 0;
        while (s.undo() != null) n++;
        assertEquals(UndoStack.LIMIT, n);
    }

    @Test void favouriteCanBeUndone(@TempDir Path tmp) {
        AppPaths paths = new AppPaths(tmp);
        UserData user = new UserData(paths.userDataFile());
        LibraryModel model = new LibraryModel(user, g -> false);
        Path rom = tmp.resolve("a.sfc");
        assertFalse(user.of(rom).favorite);
        model.setFavorite(rom, true);
        assertTrue(user.peek(rom).favorite);
        model.setFavorite(rom, false);
        assertFalse(user.peek(rom).favorite);
        // saved to disk each time
        UserData again = new UserData(paths.userDataFile());
        again.load();
        assertFalse(again.of(rom).favorite);
    }

    @Test void hideAndMainFileCanBeRestored(@TempDir Path tmp) {
        AppPaths paths = new AppPaths(tmp);
        LibraryData data = new LibraryData(paths, new Covers(paths));
        Path a = tmp.resolve("a.sfc"), b = tmp.resolve("b.sfc");
        Map<Path, Boolean> before = data.hiddenState(List.of(a, b));
        assertEquals(Map.of(a, false, b, false), before);
        data.setHidden(List.of(a, b), true);
        assertTrue(data.overrides.isHidden(a) && data.overrides.isHidden(b));
        data.restoreHidden(before);
        assertFalse(data.overrides.isHidden(a) || data.overrides.isHidden(b));

        assertNull(data.primaryOf("k"));
        data.overrides.setPrimary("k", a);
        assertEquals(a.toAbsolutePath().normalize(), data.primaryOf("k"));
        data.restorePrimary("k", b);
        assertEquals(b.toAbsolutePath().normalize(), data.primaryOf("k"));
        data.restorePrimary("k", null);
        assertNull(data.primaryOf("k"));
    }
}
