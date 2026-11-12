#!/usr/bin/env python3
"""S-DD1 test cartridge: DMA of 16 pseudo-random compressed streams (every combination of the two header fields that
select the bitplane layout and the context model) into WRAM $7E:2000.. .

There is no encoder: random bits are a valid input for the decoder (any bit pattern decodes to *something*), so the
oracle (snes9x, which emulates the S-DD1) and we must produce the same 0x200 bytes for every stream.
Stream n sits at ROM $10000 + n*$1000 (seen as $C1:n*$1000 through the default bank windows)."""
import os, random, sys
sys.path.insert(0, os.path.dirname(__file__))
from asm import *

SIZE_KB = 2048
STREAM_AT, STREAM_STEP, OUT = 0x10000, 0x1000, 0x200

a = Asm(); prologue(a); clear_results(a)
a.lda_imm(0x00); a.sta_abs(0x2181); a.lda_imm(0x20); a.sta_abs(0x2182); a.lda_imm(0x00); a.sta_abs(0x2183)   # WRAM address $2000
for n in range(16):
    src = STREAM_AT + n * STREAM_STEP
    a.lda_imm(0x01); a.sta_abs(0x4800); a.sta_abs(0x4801)                      # channel 0 goes through the S-DD1
    a.lda_imm(0x00); a.sta_abs(0x4300)                                         # A -> B, increment, mode 0
    a.lda_imm(0x80); a.sta_abs(0x4301)                                         # B bus $2180: WRAM data port
    a.lda_imm(src & 0xFF); a.sta_abs(0x4302); a.lda_imm((src >> 8) & 0xFF); a.sta_abs(0x4303)
    a.lda_imm(0xC0 | (src >> 16)); a.sta_abs(0x4304)
    a.lda_imm(OUT & 0xFF); a.sta_abs(0x4305); a.lda_imm(OUT >> 8); a.sta_abs(0x4306)
    a.lda_imm(0x01); a.sta_abs(0x420B)
epilogue(a)

out = os.path.join(os.path.dirname(__file__), 'out'); os.makedirs(out, exist_ok=True)
path = os.path.join(out, 'sdd1.sfc')
rom = bytearray(build_rom(a.finish(), 'SDD1 TEST', 0x43, size_kb=SIZE_KB, map_mode=0x32))
rng = random.Random(0x5DD1)
for n in range(16):
    base = STREAM_AT + n * STREAM_STEP
    data = bytearray(rng.randrange(256) for _ in range(STREAM_STEP))
    data[0] = ((n & 3) << 6) | ((n >> 2) << 4) | (data[0] & 0x0F)          # planes field, context field, random start bits
    rom[base:base + STREAM_STEP] = data
open(path, 'wb').write(rom)
print('written', path)
