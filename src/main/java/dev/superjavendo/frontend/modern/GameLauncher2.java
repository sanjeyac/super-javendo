package dev.superjavendo.frontend.modern;

import dev.superjavendo.frontend.legacy.GameLauncher;
import dev.superjavendo.ui.EmuWindow;

import java.nio.file.Path;
import java.util.Map;

/** Starts a game in its own window for the v2 library (the launcher stays open). */
final class GameLauncher2 {
    private GameLauncher2() { }

    static EmuWindow open(Path rom, Map<Integer, String> profiles, int resumeSlot) throws Exception {
        return open(rom, profiles, resumeSlot, false, null);
    }

    static EmuWindow open(Path rom, Map<Integer, String> profiles, int resumeSlot, boolean record, Path movie) throws Exception {
        return GameLauncher.open(rom, profiles, false, resumeSlot, record, movie);
    }
}
