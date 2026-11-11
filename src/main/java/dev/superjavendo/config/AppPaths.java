package dev.superjavendo.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        AppPaths p = new AppPaths(base.resolve("superjavendo"));
        p.migrateControllers();
        return p;
    }

    public Path configDir() { return configDir; }

    /** Controller profiles: <code>joystick/&lt;name&gt;.cfg</code>. */
    public Path joystickDir() { return configDir.resolve("joystick"); }

    /**
     * The file holding the {@code playerN=profile[@device],...} lines (which profiles each player uses): the same
     * {@code settings.cfg} as the other settings. They used to live in {@code controllers.cfg}, see {@link #migrateControllers()}.
     */
    public Path controllersFile() { return settingsFile(); }

    /**
     * One-time move of the old {@code controllers.cfg}: its {@code playerN=} lines are appended to {@code settings.cfg}
     * (unless that already has a line for the player) and the old file is renamed to {@code controllers.cfg.old}, not deleted.
     */
    public void migrateControllers() {
        Path old = configDir.resolve("controllers.cfg");
        if (!Files.exists(old)) return;
        try {
            java.util.List<String> have = Files.exists(settingsFile()) ? Files.readAllLines(settingsFile(), StandardCharsets.UTF_8) : new java.util.ArrayList<>();
            java.util.List<String> add = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(old, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.startsWith("player") || !t.contains("=")) continue;
                String key = t.substring(0, t.indexOf('=')).trim() + "=";
                if (have.stream().noneMatch(h -> h.trim().startsWith(key))) add.add(line.trim());
            }
            if (!add.isEmpty()) {
                have = new java.util.ArrayList<>(have);
                have.add("");
                have.add("# Players: profiles (joystick/<name>.cfg) per player, \"@N\" picks /dev/input/jsN; several profiles on one player add up");
                have.addAll(add);
                Files.createDirectories(configDir);
                Files.write(settingsFile(), have, StandardCharsets.UTF_8);
            }
            Path bak = configDir.resolve("controllers.cfg.old");
            if (!Files.exists(bak)) Files.move(old, bak);
        } catch (IOException e) {
            System.err.println("Cannot merge controllers.cfg into settings.cfg: " + e.getMessage());
        }
    }

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

    /** Optional No-Intro DAT (clrmamepro format) used to mark verified dumps in the ROM Manager. */
    public Path noIntroFile() { return configDir.resolve("nointro.dat"); }

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
