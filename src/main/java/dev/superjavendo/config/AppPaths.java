package dev.superjavendo.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where the application keeps its files: <code>~/.config/superjavendo/</code>
 * (or <code>$XDG_CONFIG_HOME/superjavendo/</code> when that variable is set).
 * Future configuration (controller profiles, settings...) goes under {@link #configDir()} too.
 */
public final class AppPaths {
    private final Path configDir;

    public AppPaths(Path configDir) { this.configDir = configDir; }

    /** Default location for the current user. */
    public static AppPaths forCurrentUser() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return new AppPaths(base.resolve("superjavendo"));
    }

    public Path configDir() { return configDir; }

    /** Controller profiles: <code>joystick/&lt;name&gt;.cfg</code>. */
    public Path joystickDir() { return configDir.resolve("joystick"); }

    /** Which profiles each player uses. */
    public Path controllersFile() { return configDir.resolve("controllers.cfg"); }

    /** Games found by the frontend scanner: one {@code path<TAB>title} per line. */
    public Path gameListFile() { return configDir.resolve("game.list"); }

    /** Per-game user data of the v2 frontend (favourites, last played, play time). */
    public Path userDataFile() { return configDir.resolve("library-user.tsv"); }

    /** Application settings (language, video, audio). */
    public Path settingsFile() { return configDir.resolve("settings.cfg"); }

    /** ROM folders the library is built from (path, last scan, file count). */
    public Path sourcesFile() { return configDir.resolve("sources.tsv"); }

    /** Per-file library decisions: hidden files and the chosen main file of each version group. */
    public Path overridesFile() { return configDir.resolve("library-overrides.tsv"); }

    /** Where trashed ROMs go when the desktop has no trash. */
    public Path trashDir() { return configDir.resolve("trash"); }

    /** Optional game metadata (TSV keyed by CRC32). */
    public Path metadataFile() { return configDir.resolve("games.tsv"); }

    /** Cover images: {@code game-covers/<rom name>.png|jpg}. */
    public Path coversDir() { return configDir.resolve("game-covers"); }

    /**
     * Folders searched for a ROM's cover, in priority order: the config's {@link #coversDir()}, then
     * {@code covers/} next to the ROM, then {@code covers/} next to the ROM's folder (the usual
     * {@code snes/roms} + {@code snes/covers} layout). The first folder that has a match wins.
     */
    public java.util.List<Path> coverDirsFor(Path rom) {
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        dirs.add(coversDir());
        Path parent = rom.toAbsolutePath().getParent();
        if (parent != null) {
            dirs.add(parent.resolve("covers"));
            if (parent.getParent() != null) dirs.add(parent.getParent().resolve("covers"));
        }
        return dirs;
    }

    public Path statesDir() { return configDir.resolve("states"); }

    /** Save-state file of a ROM: <code>states/&lt;rom name without extension&gt;.state</code>. */
    public Path stateFile(Path rom) {
        String name = rom.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        return statesDir().resolve(name + ".state");
    }

    /** Highest numbered save slot reachable from the keyboard (keys 1..N, Shift+N loads). */
    public static final int MAX_SLOT = 4;

    /** Slot 0 is the quick slot used by the joystick ({@code <rom>.state}); slot N is {@code <rom>.N.state}. */
    public Path stateFile(Path rom, int slot) {
        if (slot == 0) return stateFile(rom);
        String name = rom.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        return statesDir().resolve(name + "." + slot + ".state");
    }

    /**
     * Older versions stored the state next to the ROM. If such a file exists and the new location is
     * still empty, moves it over so nothing is lost.
     */
    public void migrateLegacyState(Path rom) {
        Path target = stateFile(rom);
        String name = rom.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        Path legacy = rom.resolveSibling(name + ".state");
        try {
            if (Files.exists(legacy) && !Files.exists(target)) {
                Files.createDirectories(statesDir());
                Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Moved save state to " + target);
            }
        } catch (IOException e) {
            System.err.println("Could not migrate old save state: " + e.getMessage());
        }
    }
}
