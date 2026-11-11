package dev.superjavendo.frontend;

import dev.superjavendo.Snes;
import dev.superjavendo.config.AppPaths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Picks the image shown for a game: screenshot of its most recent save state, else its cover, else nothing. */
public final class Previews {
    public record Preview(BufferedImage image, String caption) { }

    private Previews() { }

    public static Preview of(Path rom, AppPaths paths) {
        Path best = null;
        long bestTime = -1;
        for (int slot = 0; slot <= AppPaths.MAX_SLOT; slot++) {
            Path f = paths.stateFile(rom, slot);
            try {
                if (Files.exists(f) && Files.getLastModifiedTime(f).toMillis() > bestTime) {
                    bestTime = Files.getLastModifiedTime(f).toMillis();
                    best = f;
                }
            } catch (Exception ignored) { }
        }
        if (best != null) {
            try {
                BufferedImage img = Snes.readInfo(Files.readAllBytes(best)).screenshot();
                if (img != null) {
                    return new Preview(img, "Ultimo salvataggio " + LocalDateTime.ofInstant(Instant.ofEpochMilli(bestTime),
                            ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
                }
            } catch (Exception ignored) { }
        }
        BufferedImage cover = coverOf(rom, paths);
        if (cover != null) return new Preview(cover, "Copertina");
        String name = rom.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        return new Preview(null, "Nessuna anteprima (copertina: game-covers/" + name + ".png)");
    }

    /** The cover image of a ROM (see {@link AppPaths#coverDirsFor}), or null when it has none or it cannot be read. */
    public static BufferedImage coverOf(Path rom, AppPaths paths) {
        String name = rom.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        for (Path dir : paths.coverDirsFor(rom)) {
            Path cover = findCover(name, dir);
            if (cover == null) continue;
            try {
                BufferedImage img = ImageIO.read(cover.toFile());
                if (img != null) return img;
            } catch (Exception ignored) { }
            return null;
        }
        return null;
    }

    private static final String[] COVER_EXTS = {"png", "jpg", "jpeg"};

    /**
     * Finds the cover for a ROM name (no extension): identical file name first, else the single cover whose
     * normalized title equals the ROM's. Ambiguous or missing → null (deterministic: never picks by file order).
     */
    public static Path findCover(String romName, Path coversDir) {
        for (String ext : COVER_EXTS) {
            Path c = coversDir.resolve(romName + "." + ext);
            if (Files.isRegularFile(c)) return c;
        }
        String key = normalizeTitle(romName);
        if (key.isEmpty() || !Files.isDirectory(coversDir)) return null;
        Path found = null;
        try (var files = Files.list(coversDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String fn = f.getFileName().toString();
                int dot = fn.lastIndexOf('.');
                if (dot < 0 || !isCoverExt(fn.substring(dot + 1))) continue;
                if (!normalizeTitle(fn.substring(0, dot)).equals(key)) continue;
                if (found != null) return null;      // ambiguous
                found = f;
            }
        } catch (java.io.IOException e) {
            return null;
        }
        return found;
    }

    private static boolean isCoverExt(String ext) {
        for (String e : COVER_EXTS) if (e.equalsIgnoreCase(ext)) return true;
        return false;
    }

    /**
     * Title key shared by GoodSNES ("Addams Family, The (U) [!]") and No-Intro ("The Addams Family (USA)") names:
     * drops (..) and [..] tags, moves a trailing ", The/A/An" to the front, "_" → "'", "&" → "and", keeps [a-z0-9].
     */
    public static String normalizeTitle(String name) {
        String s = name.replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]", "").trim();
        s = s.replace('_', '\'');
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?),\\s*(The|A|An)\\b(.*)$").matcher(s);
        if (m.matches()) s = m.group(2) + " " + m.group(1) + m.group(3);
        return s.toLowerCase(java.util.Locale.ROOT).replace("&", "and").replaceAll("[^a-z0-9]", "");
    }
}
