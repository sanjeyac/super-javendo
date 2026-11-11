#!/usr/bin/env python3
"""Run a generated test ROM on SuperJavendo and on snes9x and compare WRAM[$2000..$3FFF].

usage: run.py ROM.sfc [--frames N] [--dump]
"""
import argparse, os, subprocess, sys, tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
JAR = os.path.join(ROOT, 'target', 'superjavendo.jar')
REF = os.path.join(ROOT, 'tools', 'diff', 'refrun.py')
LO, HI = 0x2000, 0x4100
BSNES = '/usr/lib/libretro/bsnes_libretro.so'


def bsnes_wram(rom, frames, t, ref_wram):
    """WRAM of the bsnes core (no libretro memory access): serialize the state and locate the 128 KB block by
    searching for the first bytes of the reference dump (the result area is identical on both cores in the
    common case); returns None if the block cannot be located."""
    st = os.path.join(t, 'b.state')
    subprocess.run([sys.executable, REF, rom, '--frames', str(frames), '--core', BSNES, '--state', st],
                   capture_output=True, check=True)
    data = open(st, 'rb').read()
    i = data.find(ref_wram[LO:LO + 32])
    if i < 0:
        i = 0x40284 + LO          # offset seen in this core build when the heads differ
    return data[i - LO:i - LO + 0x20000][LO:HI]


def dumps(rom, frames):
    with tempfile.TemporaryDirectory() as t:
        o, r = os.path.join(t, 'o.w'), os.path.join(t, 'r.w')
        subprocess.run(['java', f'-Dwramdump={o}', '-jar', JAR, rom, '--headless', str(frames)],
                       capture_output=True, check=True)
        subprocess.run([sys.executable, REF, rom, '--frames', str(frames), '--wram', r],
                       capture_output=True, check=True)
        ref = open(r, 'rb').read()
        return open(o, 'rb').read()[LO:HI], ref[LO:HI], bsnes_wram(rom, frames, t, ref)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('rom'); ap.add_argument('--frames', type=int, default=10)
    ap.add_argument('--dump', action='store_true')
    a = ap.parse_args()
    ours, ref, bs = dumps(a.rom, a.frames)
    diff = [i for i in range(len(ours)) if ours[i] != ref[i]]
    n = max((i for i in range(len(ref)) if ref[i] or ours[i]), default=-1) + 1
    if a.dump:
        print('ours', ours[:n].hex(' ')[:600]); print('ref ', ref[:n].hex(' ')[:600])
    print(f'{os.path.basename(a.rom)}: {"IDENTICAL" if not diff else f"{len(diff)} bytes differ, first at +{diff[0]:#x}"} ({n} bytes used)')
    print(f'   vs bsnes: ours {sum(x != y for x, y in zip(ours, bs))} bytes differ, snes9x {sum(x != y for x, y in zip(ref, bs))} bytes differ')
    return 1 if diff else 0


if __name__ == '__main__':
    sys.exit(main())
