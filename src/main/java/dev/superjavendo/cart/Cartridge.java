package dev.superjavendo.cart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** SNES cartridge: ROM parsing, LoROM/HiROM mapping, battery SRAM. */
public final class Cartridge {
    public enum Mapping { LOROM, HIROM }

    public final byte[] rom;
    public final byte[] sram;
    public final Mapping mapping;
    public final String title;
    public final int resetVector;
    public final boolean checksumValid;

    public Cartridge(byte[] file) {
        int offset = (file.length % 1024 == 512) ? 512 : 0; // strip copier header
        byte[] data = new byte[file.length - offset];
        System.arraycopy(file, offset, data, 0, data.length);
        this.rom = data;

        int lo = score(data, 0x7FC0), hi = score(data, 0xFFC0);
        this.mapping = hi > lo ? Mapping.HIROM : Mapping.LOROM;
        int h = mapping == Mapping.HIROM ? 0xFFC0 : 0x7FC0;

        this.title = new String(data, h, 21, StandardCharsets.US_ASCII).trim();
        int sramKb = data[h + 0x18] & 0xFF;
        int sramBytes = sramKb == 0 ? 0 : Math.min(0x20000, 1024 << Math.min(sramKb, 7));
        this.sram = new byte[sramBytes];
        this.resetVector = u16(data, h + 0x3C);
        this.checksumValid = ((u16(data, h + 0x1C) + u16(data, h + 0x1E)) & 0xFFFF) == 0xFFFF;
    }

    public static Cartridge load(Path path) throws IOException {
        return new Cartridge(Files.readAllBytes(path));
    }

    /** Loads battery RAM from a .srm file if present. */
    public void loadSram(Path file) {
        try {
            if (sram.length > 0 && Files.exists(file)) {
                byte[] d = Files.readAllBytes(file);
                System.arraycopy(d, 0, sram, 0, Math.min(d.length, sram.length));
            }
        } catch (IOException ignored) { }
    }

    public void saveSram(Path file) {
        try {
            if (sram.length > 0) Files.write(file, sram);
        } catch (IOException e) {
            System.err.println("Cannot save SRAM: " + e.getMessage());
        }
    }

    private static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    /** Heuristic score of a candidate header location. */
    private static int score(byte[] d, int h) {
        if (h + 0x40 > d.length) return -1;
        int s = 0;
        if (((u16(d, h + 0x1C) + u16(d, h + 0x1E)) & 0xFFFF) == 0xFFFF) s += 4;
        int map = d[h + 0x15] & 0xFF;
        boolean hiHeader = h == 0xFFC0;
        if ((map & 0x0F) == 1 && hiHeader) s += 2;
        if ((map & 0x0F) == 0 && !hiHeader) s += 2;
        if ((map & 0xE0) == 0x20) s += 1;
        if (u16(d, h + 0x3C) >= 0x8000) s += 2;
        return s;
    }

    public int read(int bank, int off) {
        bank &= 0xFF;
        if (mapping == Mapping.LOROM) {
            int b7 = bank & 0x7F;
            if (off < 0x8000) {
                if (sram.length > 0 && b7 >= 0x70 && b7 <= 0x7D)
                    return sram[((b7 & 0x0F) * 0x8000 + off) % sram.length] & 0xFF;
                if (b7 < 0x40) return 0;
            }
            return rom[(b7 * 0x8000 + (off & 0x7FFF)) % rom.length] & 0xFF;
        }
        int b = bank & 0x7F;
        if (b < 0x40 && off < 0x8000) {
            if (sram.length > 0 && b >= 0x20 && off >= 0x6000)
                return sram[(((b - 0x20) * 0x2000) + off - 0x6000) % sram.length] & 0xFF;
            return 0;
        }
        return rom[((bank & 0x3F) * 0x10000 + off) % rom.length] & 0xFF;
    }

    public void write(int bank, int off, int value) {
        bank &= 0xFF;
        if (sram.length == 0) return;
        if (mapping == Mapping.LOROM) {
            if (off < 0x8000 && (bank & 0x7F) >= 0x70 && (bank & 0x7F) <= 0x7D)
                sram[((bank & 0x0F) * 0x8000 + off) % sram.length] = (byte) value;
        } else {
            int b = bank & 0x7F;
            if (b >= 0x20 && b < 0x40 && off >= 0x6000 && off < 0x8000)
                sram[(((b - 0x20) * 0x2000) + off - 0x6000) % sram.length] = (byte) value;
        }
    }
}
