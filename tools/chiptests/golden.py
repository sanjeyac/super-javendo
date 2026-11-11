#!/usr/bin/env python3
"""Freeze test ROMs + the bsnes results (the more accurate oracle; --snes9x for snes9x) into src/test/resources/chiptests/ (used by ChipRomTest).

usage: golden.py [--snes9x] name [name ...]     (ROMs are taken from tools/chiptests/out/)
"""
import os, shutil, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as runmod

HERE = os.path.dirname(os.path.abspath(__file__))
DEST = os.path.join(HERE, '..', '..', 'src', 'test', 'resources', 'chiptests')
REF = os.path.join(HERE, '..', 'diff', 'refrun.py')
LO, HI = 0x2000, 0x4100

os.makedirs(DEST, exist_ok=True)
use_snes9x = '--snes9x' in sys.argv
for name in [a for a in sys.argv[1:] if a != '--snes9x']:
    rom = os.path.join(HERE, 'out', name + '.sfc')
    tmp = '/tmp/golden.w'
    subprocess.run([sys.executable, REF, rom, '--frames', '30', '--wram', tmp], capture_output=True, check=True)
    ref = open(tmp, 'rb').read()
    if use_snes9x:
        d = ref[LO:HI]
    else:
        import tempfile
        with tempfile.TemporaryDirectory() as t:
            d = runmod.bsnes_wram(rom, 30, t, ref)
    n = max((i for i, v in enumerate(d) if v), default=-1) + 1
    shutil.copy(rom, os.path.join(DEST, name + '.sfc'))
    open(os.path.join(DEST, name + '.golden'), 'wb').write(d[:n])
    print(name, n, 'bytes')
