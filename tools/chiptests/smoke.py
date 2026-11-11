#!/usr/bin/env python3
"""Framework smoke test: no chip, just stores a known pattern in WRAM."""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from asm import *

a = Asm(); prologue(a); clear_results(a)
for i, v in enumerate((0x12, 0x34, 0xAB, 0xFF)):
    a.lda_imm(v); a.sta_long(0x7E2000 + i)
a.rep(0x20); a.lda_imm(0xBEEF); a.sta_long(0x7E2010); a.sep(0x20)
a.ldx_imm(0)
a.label('l')
a.lda_imm(7); a.sta_long_x(0x7E2020); a.inx(); a.cpx_imm(4); a.bne('l')
epilogue(a)
out = os.path.join(os.path.dirname(__file__), 'out'); os.makedirs(out, exist_ok=True)
build_rom(a.finish(), 'SMOKE TEST', 0x00, path=os.path.join(out, 'smoke.sfc'))
print('written', os.path.join(out, 'smoke.sfc'))
