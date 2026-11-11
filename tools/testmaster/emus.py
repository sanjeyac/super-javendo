"""Run a test master ROM on every available emulator and parse the result table it leaves in WRAM."""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
JAR = os.path.join(ROOT, 'target', 'superjavendo.jar')
REF = os.path.join(ROOT, 'tools', 'diff', 'refrun.py')
BSNES = '/usr/lib/libretro/bsnes_libretro.so'
FRAMES = 1500
OUT_BASE = 0x1000


def parse(wram, ngroups_hint=None):
    """wram = bytes starting at $7E:2000."""
    if wram[:4] != b'TMST':
        return dict(ok=False, error='no result table (ROM did not start?)')
    n = wram[4]
    done = wram[0x0A] == 0xA5
    groups = []
    for i in range(n):
        a = int.from_bytes(wram[0x10 + 4 * i:0x12 + 4 * i], 'little')
        e = int.from_bytes(wram[0x12 + 4 * i:0x14 + 4 * i], 'little')
        groups.append(dict(actual=a, expected=e, fail=wram[0x400 + i] != 0))
    return dict(ok=True, done=done, groups=groups, passed=int.from_bytes(wram[8:10], 'little'), raw=wram)


def run_ours(rom, t):
    o = os.path.join(t, 'o.w')
    subprocess.run(['java', f'-Dwramdump={o}', '-jar', JAR, rom, '--headless', str(FRAMES)], capture_output=True, check=True)
    return parse(open(o, 'rb').read()[0x2000:0x2000 + 0x10000])


def run_snes9x(rom, t):
    w = os.path.join(t, 's.w')
    subprocess.run([sys.executable, REF, rom, '--frames', str(FRAMES), '--wram', w], capture_output=True, check=True)
    return parse(open(w, 'rb').read()[0x2000:0x2000 + 0x10000])


def run_bsnes(rom, t):
    st = os.path.join(t, 'b.state')
    subprocess.run([sys.executable, REF, rom, '--frames', str(FRAMES), '--core', BSNES, '--state', st],
                   capture_output=True, check=True)
    data = open(st, 'rb').read()
    i = -1
    while True:                                   # the magic followed by the "run finished" marker
        i = data.find(b'TMST', i + 1)
        if i < 0:
            return dict(ok=False, error='result table not found in the bsnes state')
        if data[i + 0x0A] == 0xA5:
            return parse(data[i:i + 0x10000])


EMUS = {'ours': run_ours, 'snes9x': run_snes9x, 'bsnes': run_bsnes}


def run_all(rom, only=None):
    res = {}
    with tempfile.TemporaryDirectory() as t:
        for name, fn in EMUS.items():
            if only and name not in only:
                continue
            try:
                res[name] = fn(rom, t)
            except Exception as ex:               # an emulator that is not installed must not stop the others
                res[name] = dict(ok=False, error=str(ex)[:200])
    return res
