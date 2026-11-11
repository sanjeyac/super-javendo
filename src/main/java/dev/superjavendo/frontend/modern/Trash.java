package dev.superjavendo.frontend.modern;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Moves a file to the desktop trash, or to a folder of ours when there is none. Never deletes for good. */
final class Trash {
    private Trash() { }

    static void move(Path file, Path fallbackDir) throws IOException {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                    && Desktop.getDesktop().moveToTrash(file.toFile())) return;
        } catch (RuntimeException ignored) { }
        Files.createDirectories(fallbackDir);
        Path target = fallbackDir.resolve(file.getFileName());
        for (int i = 1; Files.exists(target); i++) target = fallbackDir.resolve(i + "-" + file.getFileName());
        Files.move(file, target);
    }
}
