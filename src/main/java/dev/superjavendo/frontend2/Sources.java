package dev.superjavendo.frontend2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The ROM folders the library is built from, with the time of their last scan. Persisted as {@code <path><TAB><scan millis>}. */
public final class Sources {
    public record Folder(Path path, long lastScan) { }

    private final Path file;
    private final List<Folder> folders = new ArrayList<>();

    public Sources(Path file) { this.file = file; }

    public List<Folder> folders() { return List.copyOf(folders); }

    public boolean isEmpty() { return folders.isEmpty(); }

    public void add(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        for (Folder f : folders) if (f.path().equals(abs)) return;
        folders.add(new Folder(abs, 0));
    }

    public void remove(Path dir) {
        Path abs = dir.toAbsolutePath().normalize();
        folders.removeIf(f -> f.path().equals(abs));
    }

    public void markScanned(Path dir, long when) {
        Path abs = dir.toAbsolutePath().normalize();
        for (int i = 0; i < folders.size(); i++) if (folders.get(i).path().equals(abs)) folders.set(i, new Folder(abs, when));
    }

    /** Number of listed ROMs located under the folder. */
    public static int countUnder(Path dir, List<Path> roms) {
        Path abs = dir.toAbsolutePath().normalize();
        int n = 0;
        for (Path r : roms) if (r.toAbsolutePath().normalize().startsWith(abs)) n++;
        return n;
    }

    /** First-run seed: the outermost folders that contain the already-listed ROMs. */
    public void seedFrom(List<Path> roms) {
        List<Path> dirs = new ArrayList<>(roms.stream().map(r -> r.toAbsolutePath().normalize().getParent()).filter(d -> d != null).distinct().toList());
        dirs.sort(Comparator.comparingInt(Path::getNameCount));
        List<Path> kept = new ArrayList<>();
        for (Path d : dirs) if (kept.stream().noneMatch(d::startsWith)) kept.add(d);
        kept.sort(Comparator.naturalOrder());
        for (Path d : kept) folders.add(new Folder(d, 0));
    }

    public void load() {
        folders.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                long t = 0;
                try { if (f.length > 1) t = Long.parseLong(f[1]); } catch (NumberFormatException ignored) { }
                folders.add(new Folder(Path.of(f[0]), t));
            }
        } catch (IOException e) {
            System.err.println("Cannot read " + file + ": " + e.getMessage());
        }
    }

    public void save() {
        List<String> out = new ArrayList<>();
        out.add("# SuperJavendo ROM folders: <path><TAB><last scan, epoch ms>");
        for (Folder f : folders) out.add(f.path() + "\t" + f.lastScan());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot save " + file + ": " + e.getMessage());
        }
    }
}
