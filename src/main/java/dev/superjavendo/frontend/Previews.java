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
        String name = rom.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        for (String ext : new String[]{"png", "jpg", "jpeg"}) {
            Path c = paths.coversDir().resolve(name + "." + ext);
            try {
                if (Files.exists(c)) {
                    BufferedImage img = ImageIO.read(c.toFile());
                    if (img != null) return new Preview(img, "Copertina");
                }
            } catch (Exception ignored) { }
        }
        return new Preview(null, "Nessuna anteprima (copertina: game-covers/" + name + ".png)");
    }
}
