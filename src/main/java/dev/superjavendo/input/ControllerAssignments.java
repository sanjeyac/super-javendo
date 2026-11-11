package dev.superjavendo.input;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Which profiles each player uses: the {@code playerN=profile[@device],…} lines of {@code controllers.cfg}.
 * Editing rewrites only those lines and keeps comments and everything else.
 */
public final class ControllerAssignments {
    public record Entry(String profile, int device) { }

    public static final int PLAYERS = 2;
    private final Path file;

    public ControllerAssignments(Path file) { this.file = file; }

    private List<String> lines() {
        try { return new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)); }
        catch (IOException e) { return new ArrayList<>(List.of(ControllerConfig.DEFAULT.split("\n"))); }
    }

    private static String valueOf(String line, int player) {
        String l = line.contains("#") ? line.substring(0, line.indexOf('#')).trim() : line.trim();
        return l.startsWith("player" + player + "=") ? l.substring(l.indexOf('=') + 1) : null;
    }

    /** The profiles of a player (1-based), in order. A missing "@N" is device -1. */
    public List<Entry> of(int player) {
        List<Entry> out = new ArrayList<>();
        for (String line : lines()) {
            String v = valueOf(line, player);
            if (v == null) continue;
            for (String item : v.split(",")) {
                item = item.trim();
                if (item.isEmpty()) continue;
                int at = item.indexOf('@'), dev = -1;
                if (at >= 0) try { dev = Integer.parseInt(item.substring(at + 1).trim()); } catch (NumberFormatException ignored) { }
                out.add(new Entry(at < 0 ? item : item.substring(0, at), dev));
            }
            return out;
        }
        return out;
    }

    public boolean uses(int player, String profile) { return of(player).stream().anyMatch(e -> e.profile().equals(profile)); }

    /** Adds or removes the profile for a player. {@code device} (gamepad profiles, else -1) is written as {@code @N}. */
    public void set(int player, String profile, boolean on, int device) throws IOException {
        List<Entry> entries = new ArrayList<>(of(player));
        entries.removeIf(e -> e.profile().equals(profile));
        if (on) entries.add(new Entry(profile, device));
        write(player, entries);
    }

    /** Changes the device of the profile wherever it is used. */
    public void setDevice(String profile, int device) throws IOException {
        for (int p = 1; p <= PLAYERS; p++) {
            List<Entry> entries = new ArrayList<>(of(p));
            boolean changed = false;
            for (int i = 0; i < entries.size(); i++)
                if (entries.get(i).profile().equals(profile) && entries.get(i).device() != device) { entries.set(i, new Entry(profile, device)); changed = true; }
            if (changed) write(p, entries);
        }
    }

    private void write(int player, List<Entry> entries) throws IOException {
        StringBuilder sb = new StringBuilder("player" + player + "=");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(entries.get(i).profile());
            if (entries.get(i).device() >= 0) sb.append('@').append(entries.get(i).device());
        }
        List<String> lines = lines();
        boolean done = false;
        for (int i = 0; i < lines.size(); i++)
            if (valueOf(lines.get(i), player) != null) { lines.set(i, sb.toString()); done = true; break; }
        if (!done) lines.add(sb.toString());
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
