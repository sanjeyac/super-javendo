#!/usr/bin/env python3
"""Probe: does snes9x answer DSP-1 commands?  Multiply $4000*$4000, then read SR and 2 result bytes."""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from asm import *

DR, SR = 0x308000, 0x30C000
a = Asm(); prologue(a); clear_results(a)
for b in (0x00, 0x00, 0x40, 0x00, 0x40):          # cmd 0 (multiply), A=$4000, B=$4000
    a.lda_imm(b); a.sta_long(DR)
a.lda_long(SR); a.sta_long(0x7E2000)
a.lda_long(DR); a.sta_long(0x7E2001)
a.lda_long(DR); a.sta_long(0x7E2002)
a.lda_long(SR); a.sta_long(0x7E2003)
epilogue(a)
out = os.path.join(os.path.dirname(__file__), 'out'); os.makedirs(out, exist_ok=True)
build_rom(a.finish(), 'DSP1 PROBE', 0x03, path=os.path.join(out, 'dsp1probe.sfc'))
