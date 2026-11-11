#!/usr/bin/env python3
"""OBC1 test cartridge: exercises $7FF0-$7FF6 and the $6000-$7FFF RAM window.
WRAM $7E:4000.. gets register read-backs per (base, index) combination;
WRAM $7E:2000..$3FFF gets a copy of the whole window at the end."""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from asm import *

a = Asm(); prologue(a); clear_results(a)

# 1. fill $6000..$7FEF with a pattern
a.ldx_imm(0)
a.label('fill'); a.txa(); a.eor_imm(0xA5); a.sta_abs_x(0x6000); a.inx(); a.cpx_imm(0x1FF0); a.bne('fill')

# 2. register sequences
slot = 0x7E4000
for base in (0, 1):
    a.lda_imm(base); a.sta_abs(0x7FF5)
    for idx in (0, 3, 5, 127):
        a.lda_imm(idx); a.sta_abs(0x7FF6)
        for reg, v in ((0x7FF0, 0x11), (0x7FF1, 0x22), (0x7FF2, 0x33), (0x7FF3, 0x44), (0x7FF4, 0x02 | idx)):
            a.lda_imm(v + idx); a.sta_abs(reg)
        for reg in range(0x7FF0, 0x7FF7):
            a.lda_abs(reg); a.sta_long(slot); slot += 1
        slot = (slot + 15) & ~15

# 3. copy the window to WRAM
a.ldx_imm(0)
a.label('copy'); a.lda_abs_x(0x6000); a.sta_long_x(0x7E2000); a.inx(); a.cpx_imm(0x2000); a.bne('copy')
epilogue(a)

out = os.path.join(os.path.dirname(__file__), 'out'); os.makedirs(out, exist_ok=True)
path = os.path.join(out, 'obc1.sfc')
build_rom(a.finish(), 'METAL COMBAT', 0x25, sram_kb_code=3, path=path)   # type $25 = OBC1 + RAM, 8 KB
print('written', path)
