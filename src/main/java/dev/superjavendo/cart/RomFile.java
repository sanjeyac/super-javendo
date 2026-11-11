package dev.superjavendo.cart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads ROM images from plain files or from the first ROM found inside a .zip archive. */
public final class RomFile {
    public static final Set<String> EXTENSIONS = Set.of("sfc", "smc", "swc", "fig");

    private RomFile() { }

    public static boolean isRomName(String name) { return EXTENSIONS.contains(extension(name)); }

    public static boolean isZip(Path p) { return extension(p.getFileName().toString()).equals("zip"); }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Name of the first ROM entry of the archive, or null if it contains none (or cannot be read). */
    public static String firstRomEntry(Path zip) {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            return z.stream().filter(e -> !e.isDirectory() && isRomName(e.getName())).map(ZipEntry::getName).findFirst().orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Reads the ROM bytes: the file itself, or the first ROM inside a zip. */
    public static byte[] read(Path path) throws IOException {
        if (!isZip(path)) return Files.readAllBytes(path);
        try (ZipFile z = new ZipFile(path.toFile())) {
            String name = z.stream().filter(e -> !e.isDirectory() && isRomName(e.getName())).map(ZipEntry::getName).findFirst().orElse(null);
            if (name == null) throw new IOException(path.getFileName() + " does not contain a SNES ROM (.sfc, .smc, .swc, .fig)");
            try (InputStream in = z.getInputStream(z.getEntry(name))) { return in.readAllBytes(); }
        }
    }
}
