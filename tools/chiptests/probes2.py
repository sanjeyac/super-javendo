import os, sys, subprocess
sys.path.insert(0, os.path.dirname(__file__))
from asm import *
out = os.path.join(os.path.dirname(__file__), 'out')
def run(p):
    r = subprocess.run([sys.executable, 'run.py', p, '--dump'], capture_output=True, text=True); print(r.stdout.strip()[:300])

# SA-1: I-RAM at $3000 (2KB), BW-RAM at $6000 (8KB window), 2 reads each
a = Asm(); prologue(a); clear_results(a)
a.lda_imm(0x5A); a.sta_long(0x3000); a.lda_imm(0xA5); a.sta_long(0x3001)
a.lda_imm(0x11); a.sta_long(0x6000)
a.lda_long(0x3000); a.sta_long(0x7E2000); a.lda_long(0x3001); a.sta_long(0x7E2001); a.lda_long(0x6000); a.sta_long(0x7E2002)
a.lda_long(0x2300); a.sta_long(0x7E2003)
epilogue(a)
p = os.path.join(out, 'sa1b.sfc'); build_rom(a.finish(), 'SA1 PROBE B', 0x34, sram_kb_code=3, size_kb=64, path=p, map_mode=0x23); print('sa1'); run(p)

# S-DD1: ROM bank remap: each 1MB block ... map bank C0 to block N and read first byte; fill ROM blocks with their index
a = Asm(); prologue(a); clear_results(a)
for n in range(4):
    a.lda_imm(n); a.sta_long(0x4804)
    a.lda_long(0xC00000); a.sta_long(0x7E2000 + n)
epilogue(a)
code = a.finish()
rom = bytearray(code) + bytes(0)
p = os.path.join(out, 'sdd1b.sfc')
data = build_rom(bytes(code), 'SDD1 PROBE B', 0x43, size_kb=64, path=p, map_mode=0x32); print('sdd1'); run(p)
