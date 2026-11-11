package dev.superjavendo.frontend.modern;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The user's decisions about the library: files hidden from it and, for games with several files, which one is the main file.
 * Persisted as TSV ({@code H<TAB>path} / {@code P<TAB>group key<TAB>path}).
 */
public final class Overrides {
    private final Path file;
    private final Set<String> hidden = new HashSet<>();
    private final Map<String, String> primary = new HashMap<>();

    public Overrides(Path file) { this.file = file; }

    private static String key(Path p) { return p.toAbsolutePath().normalize().toString(); }

    public boolean isHidden(Path rom) { return hidden.contains(key(rom)); }

    public void setHidden(Path rom, boolean on) {
        if (on) hidden.add(key(rom)); else hidden.remove(key(rom));
    }

    /** The chosen main file of a group, or null. */
    public Path primaryOf(String groupKey) {
        String p = primary.get(groupKey);
        return p == null ? null : Path.of(p);
    }

    public void setPrimary(String groupKey, Path rom) { primary.put(groupKey, key(rom)); }

    public void load() {
        hidden.clear();
        primary.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length == 2 && f[0].equals("H")) hidden.add(f[1]);
                else if (f.length == 3 && f[0].equals("P")) primary.put(f[1], f[2]);
            }
        } catch (IOException e) {
            System.err.println("Cannot read " + file + ": " + e.getMessage());
        }
    }

    public void save() {
        List<String> out = new ArrayList<>();
        out.add("# SuperJavendo library decisions: H<TAB>hidden path | P<TAB>group key<TAB>main file");
        for (String h : hidden) out.add("H\t" + h);
        for (var e : primary.entrySet()) out.add("P\t" + e.getKey() + "\t" + e.getValue());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot save " + file + ": " + e.getMessage());
        }
    }
}
