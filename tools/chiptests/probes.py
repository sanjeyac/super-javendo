#!/usr/bin/env python3
"""Probe ROMs: which coprocessors does the reference core (snes9x libretro) actually emulate?"""
import os, sys, subprocess
sys.path.insert(0, os.path.dirname(__file__))
from asm import *

out = os.path.join(os.path.dirname(__file__), 'out'); os.makedirs(out, exist_ok=True)

def make(name, rom_type, reads, writes=(), sram=0, size_kb=32):
    a = Asm(); prologue(a); clear_results(a)
    for addr, v in writes:
        a.lda_imm(v); a.sta_long(addr)
    for i, addr in enumerate(reads):
        a.lda_long(addr); a.sta_long(0x7E2000 + i)
    epilogue(a)
    p = os.path.join(out, name + '.sfc')
    build_rom(a.finish(), name.upper(), rom_type, sram_kb_code=sram, size_kb=size_kb, path=p)
    return p

probes = {
  # MSU-1: $2000 status, $2002-$2007 = "S-MSU1"
  'msu1':  make('msu1', 0x00, [0x2000, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007]),
  # S-DD1: write/read MMC regs $4804..$4807, $4800
  'sdd1':  make('sdd1', 0x43, [0x4804, 0x4805, 0x4806, 0x4807], [(0x4804, 3), (0x4805, 2), (0x4806, 1), (0x4807, 0)], size_kb=64),
  # SA-1: read SFR/version $2300, $230E, write $2200 CCNT
  'sa1':   make('sa1', 0x34, [0x2300, 0x2301, 0x230E], [(0x2200, 0x20)], sram=3, size_kb=64),
  # S-RTC: read $2800 after write $2801 mode
  'srtc':  make('srtc', 0x55, [0x2800, 0x2800, 0x2800], [(0x2801, 0x0D)]),
  # Cx4: write RAM $6000, read back; status $7F5E
  'cx4':   make('cx4', 0xF3, [0x6000, 0x7F5E, 0x7F4F], [(0x6000, 0x5A)], size_kb=64),
}
for n, p in probes.items():
    r = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), 'run.py'), p, '--dump'], capture_output=True, text=True)
    print(n); print(r.stdout.strip()[:400])
