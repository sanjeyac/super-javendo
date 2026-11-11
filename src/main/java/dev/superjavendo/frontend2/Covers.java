package dev.superjavendo.frontend2;

import dev.superjavendo.Snes;
import dev.superjavendo.config.AppPaths;
import dev.superjavendo.frontend.Previews;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads covers and save-state screenshots off the event thread and keeps small copies in memory. */
final class Covers {
    private static final int MAX_W = 420;

    private final AppPaths paths;
    private final Map<Path, Optional<BufferedImage>> covers = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> pending = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "covers"); t.setDaemon(true); return t; });
    private volatile Runnable onLoaded = () -> { };

    Covers(AppPaths paths) { this.paths = paths; }

    void onLoaded(Runnable r) { onLoaded = r; }

    /** Cover folder contents, indexed once: exact file names (without extension) and normalized titles (null when ambiguous). */
    private static final class Index {
        final Map<String, Path> exact = new java.util.HashMap<>(), norm = new java.util.HashMap<>();
    }

    private final Map<Path, Index> indexes = new ConcurrentHashMap<>();
    private final Map<Path, Optional<Path>> found = new ConcurrentHashMap<>();

    private Index index(Path dir) {
        return indexes.computeIfAbsent(dir, d -> {
            Index ix = new Index();
            if (!Files.isDirectory(d)) return ix;
            try (var files = Files.list(d)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String fn = f.getFileName().toString();
                    int dot = fn.lastIndexOf('.');
                    if (dot < 0) continue;
                    String ext = fn.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                    if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) continue;
                    String base = fn.substring(0, dot);
                    ix.exact.putIfAbsent(base, f);
                    String key = Previews.normalizeTitle(base);
                    if (!key.isEmpty()) ix.norm.merge(key, f, (a, b) -> a.equals(b) ? a : AMBIGUOUS);
                }
            } catch (java.io.IOException ignored) { }
            return ix;
        });
    }

    private static final Path AMBIGUOUS = Path.of("?");

    /** The cover file for a ROM: same name first, else the one cover with the same normalized title; first folder that has one. */
    Path coverFile(Path rom) {
        return found.computeIfAbsent(rom, r -> {
            String name = r.getFileName().toString().replaceFirst("\\.[^.]*$", "");
            for (Path dir : paths.coverDirsFor(r)) {
                Index ix = index(dir);
                Path p = ix.exact.get(name);
                if (p == null) { p = ix.norm.get(Previews.normalizeTitle(name)); if (AMBIGUOUS.equals(p)) p = null; }
                if (p != null) return Optional.of(p);
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** Forgets everything found so far (cover folders are read again on the next lookup). */
    void reset() { indexes.clear(); found.clear(); covers.clear(); }

    /** Copies {@code image} into the config's cover folder as {@code <rom name>.<ext>}, which then wins over any other cover. */
    Path assign(Path rom, Path image) throws java.io.IOException {
        String fn = image.getFileName().toString();
        String ext = fn.contains(".") ? fn.substring(fn.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT) : ".png";
        String base = rom.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        Files.createDirectories(paths.coversDir());
        Path target = paths.coversDir().resolve(base + ext);
        if (!image.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) Files.copy(image, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        reset();
        return target;
    }

    /** Number of cover images in the config's cover folder. */
    int ownCount() {
        try (var s = Files.list(paths.coversDir())) { return (int) s.filter(f -> f.toString().matches("(?i).*\\.(png|jpe?g)")).count(); }
        catch (Exception e) { return 0; }
    }

    /** True if a cover file exists for the ROM (cheap: no image decoding). */
    boolean exists(Path rom) { return coverFile(rom) != null; }

    /** The cover if already loaded, else null (a load is started and {@code onLoaded} fires when it is ready). */
    BufferedImage get(Path rom) {
        Optional<BufferedImage> c = covers.get(rom);
        if (c != null) return c.orElse(null);
        if (pending.putIfAbsent(rom, true) == null) {
            pool.execute(() -> {
                BufferedImage img = null;
                try { Path f = coverFile(rom); if (f != null) img = ImageIO.read(f.toFile()); } catch (Exception ignored) { }
                covers.put(rom, Optional.ofNullable(img == null ? null : shrink(img)));
                pending.remove(rom);
                onLoaded.run();
            });
        }
        return null;
    }

    private static BufferedImage shrink(BufferedImage img) {
        if (img.getWidth() <= MAX_W) return img;
        int h = img.getHeight() * MAX_W / img.getWidth();
        BufferedImage out = new BufferedImage(MAX_W, h, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(img, 0, 0, MAX_W, h, null);
        g.dispose();
        return out;
    }

    /** A save slot of a game: what the detail panel shows. */
    record Slot(int slot, boolean exists, long savedAt, BufferedImage shot) { }

    /** Reads the five slots (0 = quick, 1–4) on a background thread and hands them to {@code done}. */
    void loadSlots(Path rom, java.util.function.Consumer<Slot[]> done) {
        pool.execute(() -> {
            Slot[] out = new Slot[AppPaths.MAX_SLOT + 1];
            for (int s = 0; s <= AppPaths.MAX_SLOT; s++) {
                Path f = paths.stateFile(rom, s);
                if (!Files.exists(f)) { out[s] = new Slot(s, false, 0, null); continue; }
                try {
                    var info = Snes.readInfo(Files.readAllBytes(f));
                    long t = info.savedAtMillis > 0 ? info.savedAtMillis : Files.getLastModifiedTime(f).toMillis();
                    out[s] = new Slot(s, true, t, info.screenshot());
                } catch (Exception e) {
                    out[s] = new Slot(s, true, 0, null);
                }
            }
            done.accept(out);
        });
    }
}
