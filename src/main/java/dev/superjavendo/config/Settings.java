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

    public void save() {
        List<String> out = new ArrayList<>();
        out.add("# SuperJavendo settings");
        out.add("language=" + language + "     # empty = system language");
        out.add("video.filter=" + filter + "     # nearest | sharp | smooth");
        out.add("video.scale=" + scale);
        out.add("audio.volume=" + volume);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot save " + file + ": " + e.getMessage());
        }
    }
}
