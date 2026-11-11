"""Test master framework: self-checking, deterministic LoROM test cartridges.

A test cartridge is a list of *groups*.  Each group runs a fixed, pseudo-random (fixed seed) workload against the
hardware under test and appends its raw output bytes to WRAM.  After the group the ROM folds the output into a 16 bit
checksum (rotate-left + xor over the words) and compares it with the value expected at build time.

Results are published twice, so that any emulator can be used and compared:

* on screen: one line per group (name, PASS/FAIL, actual checksum) and a summary line;
* in WRAM (for emulators that can dump memory), at $7E:2000:

    +0000  'TMST'                      magic
    +0004  number of groups (byte)
    +0008  number of passed groups (word)
    +000A  $A5 once the whole run has finished (byte)
    +0010  per group, 4 bytes: actual checksum (word), expected checksum (word)
    +0400  per group, 1 byte: 0 = pass, 1 = fail
    +1000  raw output of all groups, back to back (the actual data, for diffing emulators byte by byte)

Nothing depends on timing, uninitialised memory or the video hardware being in a particular state: the ROM clears
what it uses and does all PPU access during forced blank.  The expected checksums are produced by running the same
ROM on a reference emulator (see build.py) and are frozen in a json file, so the cartridge can be rebuilt bit for
bit without any emulator installed.
"""
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'chiptests'))
from asm import Asm, prologue, build_rom  # noqa: E402

WRAM = 0x7E2000
OUT_BASE = 0x1000           # offset (from $7E:2000) of the raw output area
TABLE_ADDR = 0x10           # per group: actual, expected
FLAG_ADDR = 0x400           # per group: 0 pass / 1 fail
DONE_ADDR = 0x0A
PASS_COUNT_ADDR = 0x08
MAX_OUT = 0xE000

# 5x7 font, glyph rows as 5 bit patterns; only what the report needs
_GLYPHS = {
    ' ': '00 00 00 00 00 00 00', '-': '00 00 00 1F 00 00 00', '.': '00 00 00 00 00 0C 0C', ':': '00 0C 0C 00 0C 0C 00',
    '/': '01 01 02 04 08 10 10', '!': '04 04 04 04 04 00 04', '(': '02 04 08 08 08 04 02', ')': '08 04 02 02 02 04 08',
    '=': '00 00 1F 00 1F 00 00', '+': '00 04 04 1F 04 04 00', '_': '00 00 00 00 00 00 1F', '#': '0A 0A 1F 0A 1F 0A 0A',
    '0': '0E 11 13 15 19 11 0E', '1': '04 0C 04 04 04 04 0E', '2': '0E 11 01 02 04 08 1F', '3': '1F 02 04 02 01 11 0E',
    '4': '02 06 0A 12 1F 02 02', '5': '1F 10 1E 01 01 11 0E', '6': '06 08 10 1E 11 11 0E', '7': '1F 01 02 04 08 08 08',
    '8': '0E 11 11 0E 11 11 0E', '9': '0E 11 11 0F 01 02 0C',
    'A': '0E 11 11 1F 11 11 11', 'B': '1E 11 11 1E 11 11 1E', 'C': '0E 11 10 10 10 11 0E', 'D': '1C 12 11 11 11 12 1C',
    'E': '1F 10 10 1E 10 10 1F', 'F': '1F 10 10 1E 10 10 10', 'G': '0E 11 10 17 11 11 0F', 'H': '11 11 11 1F 11 11 11',
    'I': '0E 04 04 04 04 04 0E', 'J': '07 02 02 02 02 12 0C', 'K': '11 12 14 18 14 12 11', 'L': '10 10 10 10 10 10 1F',
    'M': '11 1B 15 15 11 11 11', 'N': '11 11 19 15 13 11 11', 'O': '0E 11 11 11 11 11 0E', 'P': '1E 11 11 1E 10 10 10',
    'Q': '0E 11 11 11 15 12 0D', 'R': '1E 11 11 1E 14 12 11', 'S': '0F 10 10 0E 01 01 1E', 'T': '1F 04 04 04 04 04 04',
    'U': '11 11 11 11 11 11 0E', 'V': '11 11 11 11 11 0A 04', 'W': '11 11 11 15 15 15 0A', 'X': '11 11 0A 04 0A 11 11',
    'Y': '11 11 0A 04 04 04 04', 'Z': '1F 01 02 04 08 10 1F',
}


def font_bytes():
    """96 glyphs (ASCII 32..127), 8 rows each, one byte per row (the left 5 pixels are used)."""
    out = bytearray()
    for code in range(32, 128):
        rows = _GLYPHS.get(chr(code), _GLYPHS.get(chr(code).upper(), '00 00 00 00 00 00 00')).split()
        for r in rows:
            out.append(int(r, 16) << 3)
        out.append(0)
    return bytes(out)


class Group:
    """One workload.  `table` is the data placed in ROM (read through the direct page pointer $00..$02 with Y);
    `emit(a)` writes the code of one run; it must leave X advanced by `out_bytes` and consume the whole table."""

    def __init__(self, name, table, out_bytes, emit):
        assert len(name) <= 14, name
        self.name, self.table, self.out_bytes, self.emit = name, bytes(table), out_bytes, emit


# ---------------------------------------------------------------------------------------------------------------
# runtime
# ---------------------------------------------------------------------------------------------------------------

def build(title, rom_type, groups, expected, path, sram_kb_code=0, map_mode=0x20, header_text='TEST MASTER', rom_kb=128,
          cart_title=None):
    n = len(groups)
    assert n <= 24, 'the report screen holds 24 groups'
    a = Asm()
    prologue(a)
    # -- clear WRAM used by the run -------------------------------------------------------------------------
    a.lda_imm(0); a.ldx_imm(0)
    a.label('clr'); a.sta_long_x(WRAM); a.inx(); a.cpx_imm(0xF000); a.bne('clr')
    a.lda_imm(ord('T')); a.sta_long(WRAM + 0); a.lda_imm(ord('M')); a.sta_long(WRAM + 1)
    a.lda_imm(ord('S')); a.sta_long(WRAM + 2); a.lda_imm(ord('T')); a.sta_long(WRAM + 3)
    a.lda_imm(n); a.sta_long(WRAM + 4)
    a.ldx_imm(OUT_BASE)
    # -- groups -----------------------------------------------------------------------------------------------
    table_offsets = []          # (bank, addr) of each group's table, filled after layout
    patches = []
    out = OUT_BASE
    layout = []
    for i, g in enumerate(groups):
        start = out
        out += g.out_bytes
        assert out <= MAX_OUT + OUT_BASE, 'outputs too large'
        assert g.out_bytes % 2 == 0
        layout.append((start, out))
        # pointer to the table (patched once the ROM layout is known)
        a.lda_imm(0); patches.append((len(a.code) - 1, i, 0)); a.sta_dp(0)
        a.lda_imm(0); patches.append((len(a.code) - 1, i, 1)); a.sta_dp(1)
        a.lda_imm(0); patches.append((len(a.code) - 1, i, 2)); a.sta_dp(2)
        a.ldy_imm(0)
        a.label(f'g{i}_loop')
        g.emit(a)
        a.cpy_imm(len(g.table)); a.beq(f'g{i}_done'); a.jmp(f'g{i}_loop')
        a.label(f'g{i}_done')
        # checksum over the group's output
        a.rep(0x20)
        a.lda_imm(0); a.ldx_imm(start)
        a.label(f'g{i}_cs'); a.asl_a(); a.adc_imm(0); a.eor_long_x(WRAM); a.inx(); a.inx(); a.cpx_imm(out); a.bne(f'g{i}_cs')
        a.sta_long(WRAM + TABLE_ADDR + 4 * i)
        a.eor_imm(expected.get(g.name, 0))
        a.beq(f'g{i}_ok')
        a.sep(0x20); a.lda_imm(1); a.sta_long(WRAM + FLAG_ADDR + i); a.rep(0x20); a.bra(f'g{i}_after')
        a.label(f'g{i}_ok')
        a.lda_long(WRAM + PASS_COUNT_ADDR); a.clc(); a.adc_imm(1); a.sta_long(WRAM + PASS_COUNT_ADDR)
        a.label(f'g{i}_after')
        a.lda_imm(expected.get(g.name, 0)); a.sta_long(WRAM + TABLE_ADDR + 4 * i + 2)
        a.sep(0x20)
        a.ldx_imm(out)
    # -- video: font, palette, report ---------------------------------------------------------------------------
    a.lda_imm(0x8F); a.sta_abs(0x2100)               # forced blank
    for reg, v in ((0x2105, 0), (0x2107, 0), (0x210B, 0x01), (0x212C, 0x01), (0x212D, 0), (0x2130, 0), (0x2131, 0),
                   (0x2133, 0), (0x2121, 0)):
        a.lda_imm(v); a.sta_abs(reg)
    for reg in (0x210D, 0x210E):                     # BG1 scroll = 0 (write twice)
        a.lda_imm(0); a.sta_abs(reg); a.sta_abs(reg)
    # palette: 0 black, 1 white; palette 1 colour 1 = green, palette 2 = red, palette 3 = yellow
    def color(idx, rgb555):
        a.lda_imm(idx); a.sta_abs(0x2121)
        a.lda_imm(rgb555 & 0xFF); a.sta_abs(0x2122); a.lda_imm(rgb555 >> 8); a.sta_abs(0x2122)
    color(0, 0x0000); color(1, 0x7FFF); color(5, 0x03E0 | 0x0010); color(9, 0x001F); color(13, 0x03FF)
    # font tiles at VRAM word $1000
    a.lda_imm(0x80); a.sta_abs(0x2115)
    a.lda_imm(0x00); a.sta_abs(0x2116); a.lda_imm(0x10); a.sta_abs(0x2117)
    a.ldx_imm(0)
    a.label('font_up'); a.lda_abs_x_label('font'); a.sta_abs(0x2118); a.stz_abs(0x2119); a.inx(); a.cpx_imm(96 * 8); a.bne('font_up')
    # clear the 32x32 tilemap at VRAM 0
    a.lda_imm(0); a.sta_abs(0x2116); a.sta_abs(0x2117)
    a.ldx_imm(0)
    a.label('map_clr'); a.sta_abs(0x2118); a.sta_abs(0x2119); a.inx(); a.cpx_imm(1024); a.bne('map_clr')

    def at(row, col):
        v = row * 32 + col
        a.lda_imm(v & 0xFF); a.sta_abs(0x2116); a.lda_imm(v >> 8); a.sta_abs(0x2117)

    def attr(v):
        a.lda_imm(v); a.sta_dp(8)

    def text(label):
        a._abs_label(0xA2, label); a.jsr('print_str')

    at(1, 1); attr(0x0C); text('s_title')
    at(2, 1); attr(0x00); text('s_sub')
    for i, g in enumerate(groups):
        at(4 + i, 1); attr(0x00); text(f's_g{i}')
        a.lda_long(WRAM + FLAG_ADDR + i); a.bne(f'rep{i}_fail')
        attr(0x04); text('s_pass'); a.bra(f'rep{i}_end')
        a.label(f'rep{i}_fail')
        attr(0x08); text('s_fail')
        a.lda_long(WRAM + TABLE_ADDR + 4 * i + 1); a.jsr('print_hex8')
        a.lda_long(WRAM + TABLE_ADDR + 4 * i); a.jsr('print_hex8')
        a.label(f'rep{i}_end')
    # summary
    at(4 + n + 1, 1)
    a.lda_long(WRAM + PASS_COUNT_ADDR); a.cmp_imm(n); a.bne('sum_fail')
    attr(0x04); text('s_all'); a.bra('sum_end')
    a.label('sum_fail')
    attr(0x08); text('s_some'); a.lda_long(WRAM + PASS_COUNT_ADDR); a.jsr('print_dec2')
    attr(0x00); text('s_of'); a.lda_long(WRAM + 4); a.jsr('print_dec2')
    a.label('sum_end')
    a.lda_imm(0x0F); a.sta_abs(0x2100)               # screen on
    a.lda_imm(0xA5); a.sta_long(WRAM + DONE_ADDR)
    a.label('halt'); a.bra('halt')

    # -- subroutines ------------------------------------------------------------------------------------------------
    a.label('print_str')                              # X = address of a zero terminated string, attribute in $08
    a.label('ps_loop'); a.lda_abs_x(0); a.beq('ps_end')
    a.sec(); a.sbc_imm(32); a.sta_abs(0x2118); a.lda_dp(8); a.sta_abs(0x2119); a.inx(); a.bra('ps_loop')
    a.label('ps_end'); a.rts()
    a.label('print_nib')                              # A = 0..15
    a.cmp_imm(10); a.bcc('pn_digit'); a.clc(); a.adc_imm(23); a.bra('pn_out')
    a.label('pn_digit'); a.clc(); a.adc_imm(16)
    a.label('pn_out'); a.sta_abs(0x2118); a.lda_dp(8); a.sta_abs(0x2119); a.rts()
    a.label('print_hex8')
    a.pha(); a.lsr_a(); a.lsr_a(); a.lsr_a(); a.lsr_a(); a.jsr('print_nib'); a.pla(); a.and_imm(0x0F); a.jsr('print_nib'); a.rts()
    a.label('print_dec2')                             # A = 0..99
    a.ldx_imm(0)
    a.label('pd_loop'); a.cmp_imm(10); a.bcc('pd_done'); a.sec(); a.sbc_imm(10); a.inx(); a.bra('pd_loop')
    a.label('pd_done'); a.pha(); a.txa(); a.jsr('print_nib'); a.pla(); a.jsr('print_nib'); a.rts()

    # -- data (bank 0) ------------------------------------------------------------------------------------------------
    a.label('font'); a.byte(*font_bytes())
    def string(label, txt):
        a.label(label); a.byte(*txt.encode('ascii'), 0)
    string('s_title', header_text)
    string('s_sub', 'SUPERJAVENDO MASTER SUITE')
    for i, g in enumerate(groups):
        string(f's_g{i}', g.name.ljust(14))
    string('s_pass', 'PASS')
    string('s_fail', 'FAIL ')
    string('s_all', 'ALL TESTS PASSED')
    string('s_some', 'PASSED ')
    string('s_of', ' OF ')
    code = bytearray(a.finish())
    assert len(code) < 0x7FC0, f'bank 0 overflow: {len(code)}'
    # -- data tables in the following banks (never straddling a bank boundary) ----------------------------------------
    rom = bytearray(code.ljust(0x8000, b'\xFF'))
    for i, g in enumerate(groups):
        if len(rom) % 0x8000 + len(g.table) > 0x8000:
            rom += b'\xFF' * (0x8000 - len(rom) % 0x8000)
        off = len(rom)
        bank, addr = 1 + off // 0x8000 - 1 + 0, 0x8000 + off % 0x8000
        bank = off // 0x8000
        for pos, gi, part in patches:
            if gi == i:
                code_val = (addr & 0xFF, addr >> 8, bank)[part]
                rom[pos] = code_val
        rom += g.table
    size_kb = max(rom_kb, ((len(rom) + 0x7FFF) // 0x8000) * 32)
    size_kb = 1 << (size_kb - 1).bit_length()
    full = build_rom(bytes(rom), cart_title or title, rom_type, sram_kb_code=sram_kb_code, size_kb=size_kb,
                     path=None, map_mode=map_mode)
    if path:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, 'wb').write(full)
    return dict(layout=layout, names=[g.name for g in groups], size_kb=size_kb)
