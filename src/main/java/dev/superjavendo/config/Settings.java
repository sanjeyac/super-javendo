package dev.superjavendo.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Application settings, {@code settings.cfg} in the config folder ({@code key=value}). Unknown or invalid values fall back to the defaults. */
public final class Settings {
    public static final List<String> FILTERS = List.of("nearest", "sharp", "smooth");
    public static final int MIN_SCALE = 1, MAX_SCALE = 6;

    /** "" = follow the system language. */
    public String language = "";
    /** Default scaling of the game window: one of {@link #FILTERS}. */
    public String filter = "sharp";
    /** Initial size of the game window, in multiples of 256x224. */
    public int scale = 3;
    /** 0..100. */
    public int volume = 100;
    /** Start games in full screen (F11 toggles it in game, Esc leaves it). */
    public boolean fullscreen = false;

    private final Path file;

    public Settings(Path file) { this.file = file; }

    public static Settings load(AppPaths paths) {
        Settings s = new Settings(paths.settingsFile());
        if (!Files.exists(s.file)) return s;
        try {
            for (String line : Files.readAllLines(s.file, StandardCharsets.UTF_8)) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                switch (k) {
                    case "language" -> s.language = v;
                    case "video.filter" -> { if (FILTERS.contains(v)) s.filter = v; }
                    case "video.scale" -> s.scale = clamp(v, MIN_SCALE, MAX_SCALE, s.scale);
                    case "video.fullscreen" -> s.fullscreen = v.equalsIgnoreCase("true") || v.equals("1");
                    case "audio.volume" -> s.volume = clamp(v, 0, 100, s.volume);
                    default -> { }
                }
            }
        } catch (IOException e) {
            System.err.println("Cannot read " + s.file + ": " + e.getMessage());
        }
        return s;
    }

    private static int clamp(String v, int lo, int hi, int dflt) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(v))); } catch (NumberFormatException e) { return dflt; }
    }

    /**
     * Writes the settings. The file is shared with the {@code playerN=} lines (see {@link AppPaths#controllersFile()}),
     * so every line that is not one of ours is kept, and ours are updated where they are.
     */
    public void save() {
        java.util.LinkedHashMap<String, String> mine = new java.util.LinkedHashMap<>();
        mine.put("language", "language=" + language + "     # empty = system language");
        mine.put("video.filter", "video.filter=" + filter + "     # nearest | sharp | smooth");
        mine.put("video.scale", "video.scale=" + scale);
        mine.put("video.fullscreen", "video.fullscreen=" + fullscreen + "     # start games in full screen");
        mine.put("audio.volume", "audio.volume=" + volume);
        List<String> out = new ArrayList<>();
        try { if (Files.exists(file)) out.addAll(Files.readAllLines(file, StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        if (out.isEmpty()) out.add("# SuperJavendo settings");
        java.util.Set<String> done = new java.util.HashSet<>();
        for (int i = 0; i < out.size(); i++) {
            String t = out.get(i).trim();
            int eq = t.indexOf('=');
            if (eq <= 0 || t.startsWith("#")) continue;
            String key = t.substring(0, eq).trim();
            if (mine.containsKey(key)) { out.set(i, mine.get(key)); done.add(key); }
        }
        int at = 1;                                        // missing keys go right after the title comment, before the players
        for (var e : mine.entrySet()) if (!done.contains(e.getKey())) out.add(Math.min(at++, out.size()), e.getValue());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot save " + file + ": " + e.getMessage());
        }
    }
}
