package dev.superjavendo.frontend2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-game data the user generates by playing: favourite flag, last played time and total play time. Persisted as TSV. */
public final class UserData {
    public static final class Entry {
        public boolean favorite;
        public long lastPlayed;       // epoch millis, 0 = never
        public long playSeconds;
    }

    private final Path file;
    private final Map<String, Entry> entries = new HashMap<>();

    public UserData(Path file) { this.file = file; }

    private static String key(Path rom) { return rom.toAbsolutePath().normalize().toString(); }

    public Entry of(Path rom) { return entries.computeIfAbsent(key(rom), k -> new Entry()); }

    public Entry peek(Path rom) { return entries.get(key(rom)); }

    public void load() {
        entries.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                if (f.length < 4) continue;
                Entry e = new Entry();
                e.favorite = f[1].equals("1");
                try { e.lastPlayed = Long.parseLong(f[2]); e.playSeconds = Long.parseLong(f[3]); } catch (NumberFormatException ex) { continue; }
                entries.put(f[0], e);
            }
        } catch (IOException ex) {
            System.err.println("Cannot read " + file + ": " + ex.getMessage());
        }
    }

    public void save() {
        List<String> out = new ArrayList<>();
        out.add("# SuperJavendo user data: <rom path><TAB>favourite(0/1)<TAB>last played (epoch ms)<TAB>play time (s)");
        for (var e : entries.entrySet()) {
            Entry v = e.getValue();
            if (!v.favorite && v.lastPlayed == 0 && v.playSeconds == 0) continue;
            out.add(e.getKey() + "\t" + (v.favorite ? 1 : 0) + "\t" + v.lastPlayed + "\t" + v.playSeconds);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            System.err.println("Cannot save " + file + ": " + ex.getMessage());
        }
    }

    /** Records a finished play session. */
    public void recordSession(Path rom, long startMillis, long endMillis) {
        Entry e = of(rom);
        e.lastPlayed = endMillis;
        e.playSeconds += Math.max(0, (endMillis - startMillis) / 1000);
        save();
    }
}
