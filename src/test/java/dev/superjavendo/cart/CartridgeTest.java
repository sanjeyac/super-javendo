package dev.superjavendo.cart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CartridgeTest {
    @TempDir Path tmp;

    /** A ROM with a valid header at the LoROM or HiROM location. */
    private static byte[] rom(boolean hi, int sizeKb, int romType, int sramCode, boolean copierHeader) {
        byte[] d = new byte[sizeKb * 1024];
        int h = hi ? 0xFFC0 : 0x7FC0;
        byte[] title = "TEST CART".getBytes();
        System.arraycopy(title, 0, d, h, title.length);
        d[h + 0x15] = (byte) (hi ? 0x21 : 0x20);
        d[h + 0x16] = (byte) romType;
        d[h + 0x18] = (byte) sramCode;
        d[h + 0x1C] = (byte) 0xFF; d[h + 0x1D] = (byte) 0xFF;          // checksum complement + checksum = $FFFF: valid
        d[h + 0x3C] = 0x00; d[h + 0x3D] = (byte) 0x80;                  // reset vector $8000
        if (!copierHeader) return d;
        byte[] withHeader = new byte[d.length + 512];
        System.arraycopy(d, 0, withHeader, 512, d.length);
        return withHeader;
    }

    @Test void loRomHeaderIsParsed() {
        Cartridge c = new Cartridge(rom(false, 64, 0x02, 3, false));
        assertEquals(Cartridge.Mapping.LOROM, c.mapping);
        assertEquals("TEST CART", c.title);
        assertEquals(0x8000, c.resetVector);
        assertTrue(c.checksumValid);
        assertEquals(8192, c.sram.length);                              // 1 KB << 3
        assertEquals(Chip.NONE, c.chip);
        assertNull(c.coprocessor);
    }

    @Test void hiRomIsDetected() {
        Cartridge c = new Cartridge(rom(true, 128, 0x02, 0, false));
        assertEquals(Cartridge.Mapping.HIROM, c.mapping);
        assertEquals(0, c.sram.length);
    }

    @Test void copierHeaderIsStripped() {
        Cartridge c = new Cartridge(rom(false, 64, 0x00, 0, true));
        assertEquals(64 * 1024, c.rom.length);
        assertEquals("TEST CART", c.title);
    }

    @Test void loRomReadsRomAndSram() {
        byte[] data = rom(false, 64, 0x02, 3, false);
        data[0x8000] = 0x11;                                           // $01:8000
        data[0x0010] = 0x22;                                           // $00:8010 -> file offset $0010
        Cartridge c = new Cartridge(data);
        assertEquals(0x22, c.read(0x00, 0x8010));
        assertEquals(0x11, c.read(0x01, 0x8000));
        assertEquals(0x11, c.read(0x81, 0x8000));                      // $80+ mirrors $00+
        assertEquals(0, c.read(0x00, 0x1234));                          // unmapped low half of the bank
        c.write(0x70, 0x0004, 0x5A);
        assertEquals(0x5A, c.read(0x70, 0x0004));
        assertEquals(0x5A, c.sram[4] & 0xFF);
        c.write(0x00, 0x8000, 0x99);                                    // ROM is read-only
        assertEquals(0, c.rom[0]);
    }

    @Test void hiRomReadsRomAndSram() {
        byte[] data = rom(true, 128, 0x02, 3, false);
        data[0x10000] = 0x33;                                          // $01:0000 or $C1:0000
        Cartridge c = new Cartridge(data);
        assertEquals(0x33, c.read(0xC1, 0x0000));
        assertEquals(0x33, c.read(0x41, 0x0000));
        assertEquals(0, c.read(0x00, 0x1000));
        c.write(0x20, 0x6003, 0x44);                                    // SRAM window $20-$3F:$6000-$7FFF
        assertEquals(0x44, c.read(0x20, 0x6003));
        assertEquals(0x44, c.sram[3] & 0xFF);
        c.write(0x00, 0x6003, 0x77);                                    // not SRAM
        assertEquals(0x44, c.sram[3] & 0xFF);
    }

    @Test void sramRoundTripsThroughAFile() throws Exception {
        Cartridge c = new Cartridge(rom(false, 64, 0x02, 3, false));
        c.sram[0] = 1; c.sram[100] = 7;
        Path f = tmp.resolve("save.srm");
        c.saveSram(f);
        assertEquals(c.sram.length, Files.size(f));
        Cartridge d = new Cartridge(rom(false, 64, 0x02, 3, false));
        d.loadSram(f);
        assertEquals(1, d.sram[0]);
        assertEquals(7, d.sram[100]);
        d.loadSram(tmp.resolve("missing.srm"));                         // missing file: nothing happens
        assertEquals(7, d.sram[100]);
        Files.write(f, new byte[]{9});                                  // a short file only fills the start
        d.loadSram(f);
        assertEquals(9, d.sram[0]);
    }

    @Test void cartridgeWithoutSramIgnoresSaveAndLoad() throws Exception {
        Cartridge c = new Cartridge(rom(false, 64, 0x00, 0, false));
        Path f = tmp.resolve("none.srm");
        c.saveSram(f);
        assertFalse(Files.exists(f));
        c.loadSram(f);
    }

    @Test void loadReadsAPlainFile() throws Exception {
        Path f = tmp.resolve("game.sfc");
        Files.write(f, rom(false, 64, 0x02, 0, false));
        Cartridge c = Cartridge.load(f);
        assertEquals("TEST CART", c.title);
        assertNull(c.msu);
    }

    @Test void coprocessorsAreCreatedFromTheRomType() {
        assertInstanceOf(Dsp1.class, new Cartridge(rom(false, 64, 0x03, 0, false)).coprocessor);
        assertInstanceOf(SuperFx.class, new Cartridge(rom(false, 64, 0x13, 0, false)).coprocessor);
        assertInstanceOf(Obc1.class, new Cartridge(rom(false, 64, 0x25, 3, false)).coprocessor);
        assertInstanceOf(Sa1.class, new Cartridge(rom(false, 64, 0x35, 3, false)).coprocessor);
        assertInstanceOf(Sdd1.class, new Cartridge(rom(false, 64, 0x43, 0, false)).coprocessor);
        assertInstanceOf(Cx4.class, new Cartridge(rom(false, 64, 0xF3, 0, false)).coprocessor);
    }

    @Test void chipDecodingFromTheRomTypeByte() {
        assertEquals(Chip.NONE, Chip.fromRomType(0x00));
        assertEquals(Chip.NONE, Chip.fromRomType(0x02));
        assertEquals(Chip.DSP, Chip.fromRomType(0x03));
        assertEquals(Chip.SUPERFX, Chip.fromRomType(0x15));
        assertEquals(Chip.OBC1, Chip.fromRomType(0x23));
        assertEquals(Chip.SA1, Chip.fromRomType(0x34));
        assertEquals(Chip.SDD1, Chip.fromRomType(0x45));
        assertEquals(Chip.SRTC, Chip.fromRomType(0x55));
        assertEquals(Chip.CX4, Chip.fromRomType(0xF3));
        assertEquals(Chip.SPC7110, Chip.fromRomType(0xF5));
        assertEquals(Chip.SPC7110, Chip.fromRomType(0xF9));
        assertEquals(Chip.SETA, Chip.fromRomType(0xF6));
        assertEquals(Chip.OTHER, Chip.fromRomType(0xF4));
        assertEquals(Chip.OTHER, Chip.fromRomType(0x93));
        for (Chip c : Chip.values()) assertFalse(c.label.isEmpty());
        assertTrue(Chip.NONE.supported() && Chip.SA1.supported() && Chip.SDD1.supported());
        assertFalse(Chip.SPC7110.supported() || Chip.SRTC.supported() || Chip.SETA.supported() || Chip.OTHER.supported());
    }
}
