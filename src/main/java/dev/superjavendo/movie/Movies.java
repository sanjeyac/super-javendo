package dev.superjavendo.movie;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The folder of recorded movies. A file is named {@code <rom crc>-<date>.sjm}, so the movies of one game are found
 * without opening the others; everything else (name, length, verification) lives in the file's own header.
 */
public final class Movies {
    public record Entry(Path file, Movie.Info info) {
        /** What to show as the movie's name: the user's label, or the recording date. */
        public String displayName() {
            if (!info.label().isEmpty()) return info.label();
            long at = info.createdAt() > 0 ? info.createdAt() : file.toFile().lastModified();
            return java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        }
    }

    private final Path dir;

    public Movies(Path dir) { this.dir = dir; }

    public static String hex(int crc) { return String.format("%08x", crc); }

    /** A path for a new recording of the game with this ROM CRC. */
    public Path newFile(int crc) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path f = dir.resolve(hex(crc) + "-" + stamp + ".sjm");
        for (int i = 2; Files.exists(f); i++) f = dir.resolve(hex(crc) + "-" + stamp + "-" + i + ".sjm");
        return f;
    }

    /** The movies of one game, newest first. Unreadable files are skipped. */
    public List<Entry> list(int crc) {
        List<Entry> out = new ArrayList<>();
        String prefix = hex(crc) + "-";
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.sjm")) {
            for (Path p : ds) {
                if (!p.getFileName().toString().startsWith(prefix)) continue;
                try {
                    Movie.Info i = Movie.readInfo(p);
                    if (i.romCrc() == crc) out.add(new Entry(p, i));
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        out.sort(Comparator.comparing((Entry e) -> e.info().createdAt()).thenComparing(e -> e.file().getFileName().toString()).reversed());
        return out;
    }

    public void rename(Path file, String label) throws IOException {
        Movie m = Movie.load(file);
        m.label = label.strip();
        rewrite(file, m);
    }

    public void setVerified(Path file, boolean ok) throws IOException {
        Movie m = Movie.load(file);
        m.verified = ok ? Movie.VERIFIED : Movie.DESYNC;
        rewrite(file, m);
    }

    private static void rewrite(Path file, Movie m) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        m.save(tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
