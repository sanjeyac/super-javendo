package dev.superjavendo.frontend.modern;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo/redo of the user's reversible actions in the frontend (favourites, hide/show, main file). Each action pushes how to
 * undo and redo itself plus a text for the confirmation notice. A new action discards what could still be redone.
 * Not thread-safe: used from the event thread only.
 */
final class UndoStack {
    private record Entry(String label, Runnable undo, Runnable redo) { }

    static final int LIMIT = 200;

    private final Deque<Entry> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    /** Records an action that has just been done. */
    void push(String label, Runnable undoAction, Runnable redoAction) {
        undo.push(new Entry(label, undoAction, redoAction));
        redo.clear();
        while (undo.size() > LIMIT) undo.removeLast();
    }

    /** Undoes the last action; returns its label, or null if there was nothing to undo. */
    String undo() {
        Entry e = undo.poll();
        if (e == null) return null;
        e.undo().run();
        redo.push(e);
        return e.label();
    }

    /** Redoes the last undone action; returns its label, or null if there is nothing to redo. */
    String redo() {
        Entry e = redo.poll();
        if (e == null) return null;
        e.redo().run();
        undo.push(e);
        return e.label();
    }

    boolean canUndo() { return !undo.isEmpty(); }

    boolean canRedo() { return !redo.isEmpty(); }
}
