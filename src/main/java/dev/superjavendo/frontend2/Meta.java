package dev.superjavendo.frontend2;

import dev.superjavendo.cart.RomFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** CRC32 of ROM files (of the ROM data, without a copier header), cached by path, size and modification time. */
final class Meta {
    private final Path cacheFile;
    private final Map<String, String[]> cache = new HashMap<>();      // path -> {size, mtime, crc}

    Meta(Path cacheFile) { this.cacheFile = cacheFile; }

    void load() {
        cache.clear();
        if (!Files.exists(cacheFile)) return;
        try {
            for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length == 4) cache.put(f[0], new String[]{f[1], f[2], f[3]});
            }
        } catch (IOException ignored) { }
    }

    void save() {
        List<String> out = new ArrayList<>();
        for (var e : cache.entrySet()) out.add(e.getKey() + "\t" + String.join("\t", e.getValue()));
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, out, StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    /** The cached CRC32 if the file is unchanged, else null. */
    String cached(Path rom) {
        try {
            String[] c = cache.get(rom.toString());
            if (c != null && c[0].equals(String.valueOf(Files.size(rom))) && c[1].equals(String.valueOf(Files.getLastModifiedTime(rom).toMillis()))) return c[2];
        } catch (IOException ignored) { }
        return null;
    }

    /** Computes (and remembers) the CRC32; null if the file cannot be read. */
    String compute(Path rom) {
        try {
            byte[] d = RomFile.read(rom);
            int off = d.length % 1024 == 512 ? 512 : 0;
            CRC32 c = new CRC32();
            c.update(d, off, d.length - off);
            String hex = String.format("%08X", c.getValue());
            cache.put(rom.toString(), new String[]{String.valueOf(Files.size(rom)), String.valueOf(Files.getLastModifiedTime(rom).toMillis()), hex});
            return hex;
        } catch (Exception e) {
            return null;
        }
    }
}
