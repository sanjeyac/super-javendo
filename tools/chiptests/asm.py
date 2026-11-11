"""Minimal 65816 assembler + LoROM builder for the chip test cartridges.

Only the instructions the tests need are supported.  Register widths are
tracked by hand: rep()/sep() update the assembler's idea of M and X so that
immediate operands get the right size.
"""
import struct


class Asm:
    def __init__(self, org=0x8000):
        self.org = org
        self.code = bytearray()
        self.labels = {}
        self.fix = []              # (offset, label, kind)
        self.m8 = True             # emulation mode starts 8-bit
        self.x8 = True

    # -- plumbing ---------------------------------------------------------
    @property
    def pc(self):
        return self.org + len(self.code)

    def emit(self, *b):
        self.code += bytes(b)

    def label(self, name):
        assert name not in self.labels, name
        self.labels[name] = self.pc

    def _imm(self, op, v, wide):
        if wide:
            self.emit(op, v & 0xFF, (v >> 8) & 0xFF)
        else:
            self.emit(op, v & 0xFF)

    # -- mode / flags -----------------------------------------------------
    def sei(self): self.emit(0x78)
    def cld(self): self.emit(0xD8)
    def clc(self): self.emit(0x18)
    def sec(self): self.emit(0x38)
    def xce(self): self.emit(0xFB)
    def nop(self): self.emit(0xEA)
    def stp(self): self.emit(0xDB)

    def rep(self, v):
        self.emit(0xC2, v)
        if v & 0x20: self.m8 = False
        if v & 0x10: self.x8 = False

    def sep(self, v):
        self.emit(0xE2, v)
        if v & 0x20: self.m8 = True
        if v & 0x10: self.x8 = True

    # -- loads / stores ---------------------------------------------------
    def lda_imm(self, v): self._imm(0xA9, v, not self.m8)
    def ldx_imm(self, v): self._imm(0xA2, v, not self.x8)
    def ldy_imm(self, v): self._imm(0xA0, v, not self.x8)
    def lda_abs(self, a): self.emit(0xAD, a & 0xFF, a >> 8)
    def lda_abs_x(self, a): self.emit(0xBD, a & 0xFF, a >> 8)
    def lda_long(self, a): self.emit(0xAF, a & 0xFF, (a >> 8) & 0xFF, a >> 16)
    def sta_abs(self, a): self.emit(0x8D, a & 0xFF, a >> 8)
    def sta_abs_x(self, a): self.emit(0x9D, a & 0xFF, a >> 8)
    def sta_long(self, a): self.emit(0x8F, a & 0xFF, (a >> 8) & 0xFF, a >> 16)
    def sta_long_x(self, a): self.emit(0x9F, a & 0xFF, (a >> 8) & 0xFF, a >> 16)
    def stx_abs(self, a): self.emit(0x8E, a & 0xFF, a >> 8)
    def stz_abs(self, a): self.emit(0x9C, a & 0xFF, a >> 8)
    def txa(self): self.emit(0x8A)
    def tax(self): self.emit(0xAA)
    def eor_imm(self, v): self._imm(0x49, v, not self.m8)
    def lda_abs_y(self, a): self.emit(0xB9, a & 0xFF, a >> 8)
    def iny(self): self.emit(0xC8)
    def cpy_imm(self, v): self._imm(0xC0, v, not self.x8)
    def tcs(self): self.emit(0x1B)
    def txs(self): self.emit(0x9A)
    def inx(self): self.emit(0xE8)
    def dex(self): self.emit(0xCA)
    def cmp_imm(self, v): self._imm(0xC9, v, not self.m8)
    def cpx_imm(self, v): self._imm(0xE0, v, not self.x8)

    # -- additions for the test master ------------------------------------
    def _abs_label(self, op, name):
        self.emit(op, 0, 0)
        self.fix.append((len(self.code) - 2, name, 'abs'))

    def lda_dp(self, d): self.emit(0xA5, d)
    def sta_dp(self, d): self.emit(0x85, d)
    def lda_dp_long_y(self, d): self.emit(0xB7, d)          # lda [d],y : 24 bit pointer in direct page
    def lda_long_x(self, a): self.emit(0xBF, a & 0xFF, (a >> 8) & 0xFF, a >> 16)
    def eor_long_x(self, a): self.emit(0x5F, a & 0xFF, (a >> 8) & 0xFF, a >> 16)
    def lda_abs_x_label(self, name): self._abs_label(0xBD, name)
    def ldx_abs(self, a): self.emit(0xAE, a & 0xFF, a >> 8)
    def asl_a(self): self.emit(0x0A)
    def lsr_a(self): self.emit(0x4A)
    def adc_imm(self, v): self._imm(0x69, v, not self.m8)
    def sbc_imm(self, v): self._imm(0xE9, v, not self.m8)
    def and_imm(self, v): self._imm(0x29, v, not self.m8)
    def ora_imm(self, v): self._imm(0x09, v, not self.m8)
    def pha(self): self.emit(0x48)
    def pla(self): self.emit(0x68)
    def bcc(self, n): self._branch(0x90, n)
    def bcs(self, n): self._branch(0xB0, n)
    def jsr(self, name): self._abs_label(0x20, name)
    def rts(self): self.emit(0x60)
    def tya(self): self.emit(0x98)
    def cmp_dp(self, d): self.emit(0xC5, d)

    # -- control flow -----------------------------------------------------
    def _branch(self, op, name):
        self.emit(op, 0)
        self.fix.append((len(self.code) - 1, name, 'rel'))

    def bne(self, n): self._branch(0xD0, n)
    def beq(self, n): self._branch(0xF0, n)
    def bra(self, n): self._branch(0x80, n)

    def jmp(self, name):
        self.emit(0x4C, 0, 0)
        self.fix.append((len(self.code) - 2, name, 'abs'))

    # -- data -------------------------------------------------------------
    def byte(self, *v): self.emit(*v)

    def finish(self):
        for off, name, kind in self.fix:
            t = self.labels[name]
            if kind == 'abs':
                self.code[off] = t & 0xFF
                self.code[off + 1] = t >> 8
            else:
                d = t - (self.org + off + 1)
                assert -128 <= d <= 127, f"branch to {name} out of range ({d})"
                self.code[off] = d & 0xFF
        return bytes(self.code)


def prologue(a):
    """Native mode, 16-bit index, stack at $1FFF, decimal off."""
    a.sei(); a.clc(); a.xce()
    a.rep(0x38)                   # 16-bit A/X/Y, decimal off
    a.ldx_imm(0x1FFF); a.txs()
    a.sep(0x20)                   # 8-bit accumulator, 16-bit index


def clear_results(a, size=0x2100):
    """Zero WRAM $7E:2000.. so that both emulators start from the same state (snes9x fills WRAM with $55)."""
    a.lda_imm(0); a.ldx_imm(0)
    a.label('_clr'); a.sta_long_x(0x7E2000); a.inx(); a.cpx_imm(size); a.bne('_clr')


def epilogue(a):
    a.label('_end')
    a.bra('_end')


def build_rom(code, title, rom_type, sram_kb_code=0, size_kb=32, path=None, map_mode=0x20):
    """LoROM image: `code` at $8000, header at $7FC0, reset vector $8000."""
    size = size_kb * 1024
    rom = bytearray([0xFF] * size)
    rom[0:len(code)] = code
    h = 0x7FC0
    rom[h:h + 21] = title.upper().ljust(21)[:21].encode('ascii')
    rom[h + 0x15] = map_mode                   # 0x20 LoROM, 0x23 SA-1, 0x32 S-DD1 ...
    rom[h + 0x16] = rom_type
    rom[h + 0x17] = (size_kb - 1).bit_length()  # log2(KB)
    rom[h + 0x18] = sram_kb_code
    rom[h + 0x19] = 0x01
    rom[h + 0x1A] = 0x33
    rom[h + 0x1B] = 0x00
    # vectors: everything points at reset ($8000); BRK/IRQ/NMI -> RTI stub not needed
    for vec in (0x24, 0x26, 0x2A, 0x2E, 0x34, 0x36, 0x3A, 0x3E):
        rom[h + vec:h + vec + 2] = struct.pack('<H', 0x8000)
    rom[h + 0x3C:h + 0x3E] = struct.pack('<H', 0x8000)
    rom[h + 0x1C:h + 0x20] = b'\0\0\0\0'
    rom[h + 0x1C:h + 0x20] = b'\xFF\xFF\x00\x00'          # complement + checksum fields as if the sum were 0
    s = sum(rom) & 0xFFFF
    rom[h + 0x1C:h + 0x1E] = struct.pack('<H', s ^ 0xFFFF)
    rom[h + 0x1E:h + 0x20] = struct.pack('<H', s)
    if path:
        open(path, 'wb').write(rom)
    return bytes(rom)
