package dev.superjavendo.bus;

import dev.superjavendo.cart.Cartridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusOpenBusTest {
    private static Bus bus() {
        byte[] rom = new byte[0x8000];
        rom[0x7FD5] = 0x20;  // LoROM header bytes so the cartridge is accepted
        return new Bus(new Cartridge(rom));
    }

    @Test void rdnmiBits4to6ComeFromLastBusValue() {
        Bus b = bus();
        b.write(0x7E0000, 0x42);
        b.read(0x7E0000);                                  // $42 on the data bus, like the operand high byte of BIT $4210
        assertEquals(0x40, b.read(0x4210) & 0x70);         // V would be set by BIT: this is what CPUPHL relies on
    }

    @Test void rdnmiKeepsVersionAndFlag() {
        Bus b = bus();
        b.timing.nmiFlag = true;
        b.write(0x7E0000, 0x00);
        b.read(0x7E0000);
        assertEquals(0x82, b.read(0x4210));
        assertEquals(0x02, b.read(0x4210));                // flag cleared by the read
    }
}
