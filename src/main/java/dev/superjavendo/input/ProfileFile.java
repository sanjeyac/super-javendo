package dev.superjavendo.input;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Editable text model of a profile {@code .cfg} (what the configurator changes), as opposed to the
 * immutable {@link ControllerProfile} used while playing. Leading comment lines are kept on save;
 * other comments are not.
 */
public final class ProfileFile {
    /** Every assignable button, in display order. */
    public static final List<String> BUTTONS = List.of("up", "down", "left", "right", "a", "b", "x", "y", "l", "r",
            "start", "select", "save_state", "load_state");

    public String type = "keyboard", name = "";
    public int threshold = 12000;
    public final Map<String, List<String>> bindings = new LinkedHashMap<>();
    private final List<String> header = new ArrayList<>();

    public static ProfileFile read(Path file) throws IOException {
        ProfileFile f = new ProfileFile();
        f.name = file.getFileName().toString().replaceFirst("\\.cfg$", "");
        boolean inHeader = true;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = raw.trim();
            if (t.startsWith("#") && inHeader) { f.header.add(raw); continue; }
            if (t.isEmpty()) continue;
            inHeader = false;
            int hash = t.indexOf('#');
            if (hash >= 0) t = t.substring(0, hash).trim();
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase(Locale.ROOT), v = t.substring(eq + 1).trim();
            switch (k) {
                case "type" -> f.type = v.toLowerCase(Locale.ROOT);
                case "name" -> f.name = v;
                case "threshold" -> { try { f.threshold = Integer.parseInt(v); } catch (NumberFormatException ignored) { } }
                default -> {
                    for (String tok : v.split(","))
                        if (!tok.isBlank()) f.bindings.computeIfAbsent(k, x -> new ArrayList<>()).add(tok.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return f;
    }

    public boolean isKeyboard() { return type.equals("keyboard"); }

    public List<String> of(String button) { return bindings.getOrDefault(button, List.of()); }

    public void add(String button, String token) {
        List<String> l = bindings.computeIfAbsent(button, x -> new ArrayList<>());
        if (!l.contains(token)) l.add(token);
    }

    public void remove(String button, String token) {
        List<String> l = bindings.get(button);
        if (l != null) { l.remove(token); if (l.isEmpty()) bindings.remove(button); }
    }

    public List<String> toLines() {
        List<String> out = new ArrayList<>(header);
        if (!header.isEmpty()) out.add("");
        out.add("type=" + type);
        out.add("name=" + name);
        if (!isKeyboard()) out.add("threshold=" + threshold);
        out.add("");
        for (String b : BUTTONS) if (!of(b).isEmpty()) out.add(b + "=" + String.join(",", of(b)));
        return out;
    }

    /** Validates (throws IllegalArgumentException on a bad token) and writes the file. */
    public void write(Path file) throws IOException {
        List<String> lines = toLines();
        ProfileParser.parse(file.getFileName().toString(), lines);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Token for an AWT key code in a keyboard profile, or null if the key has no Linux name in our table. */
    public static String keyToken(int awtKeyCode) { return KeyNames.name(awtKeyCode); }
}
