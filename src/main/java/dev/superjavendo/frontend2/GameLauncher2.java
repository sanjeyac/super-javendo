package dev.superjavendo.frontend2;

import dev.superjavendo.frontend.GameLauncher;
import dev.superjavendo.ui.EmuWindow;

import java.nio.file.Path;
import java.util.Map;

/** Starts a game in its own window for the v2 library (the launcher stays open). */
final class GameLauncher2 {
    private GameLauncher2() { }

    static EmuWindow open(Path rom, Map<Integer, String> profiles) throws Exception {
        return GameLauncher.open(rom, profiles, false);
    }
}
