#!/usr/bin/env python3
"""Generate DSP-1 differential test ROMs: one ROM per command group, with a table of
pseudo-random input vectors in ROM.  Results land in WRAM $7E:2000.. (compare with run.py)."""
import os, random, struct, sys
sys.path.insert(0, os.path.dirname(__file__))
from asm import *

DR, SR = 0x308000, 0x30C000
OUT = os.path.join(os.path.dirname(__file__), 'out')


def s16(v): return struct.pack('<h', v)


def make(name, cmd, nparam_words, nresult_bytes, gen, count=120, seed=1):
    rnd = random.Random(seed)
    vecs = [gen(rnd) for _ in range(count)]
    table = b''.join(b''.join(s16(w) for w in v) for v in vecs)
    np_ = nparam_words * 2
    a = Asm(); prologue(a); clear_results(a)
    a.rep(0x10)                      # 16-bit index (A stays 8-bit)
    a.ldx_imm(0); a.ldy_imm(0)
    a.label('loop')
    a.lda_imm(cmd); a.sta_long(DR)
    for _ in range(np_):
        a.lda_abs_y(0)               # patched below: table base
        a._tbl_fix = getattr(a, '_tbl_fix', []) + [len(a.code) - 2]
        a.sta_long(DR); a.iny()
    for _ in range(nresult_bytes):
        a.lda_long(DR); a.sta_long_x(0x7E2000); a.inx()
    a.cpy_imm(len(table)); a.beq('done'); a.jmp('loop')
    a.label('done'); epilogue(a)
    code = bytearray(a.finish())
    base = 0x8000 + len(code)
    for off in a._tbl_fix:
        code[off] = base & 0xFF; code[off + 1] = base >> 8
    code += table
    assert len(code) < 0x7FC0, len(code)
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, name + '.sfc')
    build_rom(bytes(code), 'DSP1 ' + name[-8:], 0x03, path=p)
    return p


def make_seq(name, steps, gen, count=100, seed=1):
    """Like make(), but each vector drives several commands in a row: steps = [(cmd, input words, result bytes)].
    Needed for the commands that share state (the attitude matrices)."""
    rnd = random.Random(seed)
    vecs = [gen(rnd) for _ in range(count)]
    table = b''.join(b''.join(s16(w) for w in v) for v in vecs)
    a = Asm(); prologue(a); clear_results(a)
    a.rep(0x10)
    a.ldx_imm(0); a.ldy_imm(0)
    a.label('loop')
    fix = []
    for step in steps:
        cmd, nw, nr = step[:3]
        tail = step[3] if len(step) > 3 else []
        a.lda_imm(cmd); a.sta_long(DR)
        for _ in range(nw * 2):
            a.lda_abs_y(0); fix.append(len(a.code) - 2)
            a.sta_long(DR); a.iny()
        for _ in range(nr):
            a.lda_long(DR); a.sta_long_x(0x7E2000); a.inx()
        for b in tail:
            a.lda_imm(b); a.sta_long(DR)
    a.cpy_imm(len(table)); a.beq('done'); a.jmp('loop')
    a.label('done'); epilogue(a)
    code = bytearray(a.finish())
    base = 0x8000 + len(code)
    for off in fix:
        code[off] = base & 0xFF; code[off + 1] = base >> 8
    code += table
    assert len(code) < 0x7FC0, len(code)
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, name + '.sfc')
    build_rom(bytes(code), 'DSP1 ' + name[-8:], 0x03, path=p)
    return p


R = lambda rnd, lo=-32768, hi=32767: rnd.randint(lo, hi)

GROUPS = {
    'dsp1_multiply':  dict(cmd=0x00, np=2, nr=2, gen=lambda r: (R(r), R(r))),
    'dsp1_multiply2': dict(cmd=0x20, np=2, nr=2, gen=lambda r: (R(r), R(r))),
    'dsp1_inverse':   dict(cmd=0x10, np=2, nr=4, gen=lambda r: (R(r), R(r, -12, 3))),
    'dsp1_triangle':  dict(cmd=0x04, np=2, nr=4, gen=lambda r: (R(r), R(r))),
    'dsp1_radius':    dict(cmd=0x08, np=3, nr=4, gen=lambda r: (R(r, -2000, 2000), R(r, -2000, 2000), R(r, -2000, 2000))),
    'dsp1_range':     dict(cmd=0x18, np=4, nr=2, gen=lambda r: (R(r, -2000, 2000), R(r, -2000, 2000), R(r, -2000, 2000), R(r, 1, 4000))),
    'dsp1_distance':  dict(cmd=0x28, np=3, nr=2, gen=lambda r: (R(r, -2000, 2000), R(r, -2000, 2000), R(r, -2000, 2000))),
    'dsp1_rotate':    dict(cmd=0x0C, np=3, nr=4, gen=lambda r: (R(r), R(r, -2000, 2000), R(r, -2000, 2000))),
    'dsp1_polar':     dict(cmd=0x1C, np=6, nr=6, gen=lambda r: tuple(R(r) for _ in range(6))),
}

def _att(r): return (R(r, 0, 32767), R(r), R(r), R(r))
def _xyz(r): return tuple(R(r, -20000, 20000) for _ in range(3))

# (steps, generator): attitude A/B/C, then objective, subjective and scalar on the matrix just set
SEQ_GROUPS = {
    'dsp1_attitudeA': ([(0x01, 4, 0), (0x0D, 3, 6), (0x03, 3, 6), (0x0B, 3, 2)], lambda r: _att(r) + _xyz(r) + _xyz(r) + _xyz(r)),
    'dsp1_attitudeB': ([(0x11, 4, 0), (0x1D, 3, 6), (0x13, 3, 6), (0x1B, 3, 2)], lambda r: _att(r) + _xyz(r) + _xyz(r) + _xyz(r)),
    'dsp1_attitudeC': ([(0x21, 4, 0), (0x2D, 3, 6), (0x23, 3, 6), (0x2B, 3, 2)], lambda r: _att(r) + _xyz(r) + _xyz(r) + _xyz(r)),
}

def _param(r, zen=(0, 0x3800)):
    return (R(r, -3000, 3000), R(r, -3000, 3000), R(r, 1, 3000), R(r, 0, 0x4000), R(r, 1, 0x7000), R(r), R(r, *zen))
def _proj(r, zen=(0, 0x3800)):
    return _param(r, zen) + (R(r, -100, 100), R(r, -100, 100)) + (R(r, -3000, 3000), R(r, -3000, 3000), R(r, -3000, 3000)) + (R(r, -200, 200),)

# Parameter sets up the projection used by Target, Project and Raster (one line, then the 0x8000 terminator)
SEQ_GROUPS['dsp1_projection'] = ([(0x02, 7, 8), (0x0E, 2, 4), (0x06, 3, 6)], lambda r: _proj(r)[:12])
# Raster runs in continuous mode: it keeps producing the next line until the CPU writes $8000 to DR
SEQ_GROUPS['dsp1_raster'] = ([(0x02, 7, 8), (0x0A, 1, 22, [0x00, 0x80])], lambda r: _proj(r)[:7] + _proj(r)[12:])
SEQ_GROUPS['dsp1_gyrate'] = ([(0x14, 6, 6)], lambda r: tuple(R(r) for _ in range(3)) + tuple(R(r, -2000, 2000) for _ in range(3)))

def _wide(r):
    return tuple(R(r) for _ in range(14)) + (R(r),)
def _clip(r):
    zen = R(r, 0x3000, 0x7FFF) if r.random() < .5 else R(r, -32768, -0x3000)
    return (R(r, -3000, 3000), R(r, -3000, 3000), R(r, 0, 3000), R(r, 0, 0x4000), R(r, 1, 0x7000), R(r), zen,
            R(r, -100, 100), R(r, -100, 100), R(r, -3000, 3000), R(r, -3000, 3000), R(r, -3000, 3000))
SEQ_GROUPS['dsp1_projwide'] = ([(0x02, 7, 8), (0x0E, 2, 4), (0x06, 3, 6)], lambda r: _wide(r)[:12])      # full 16 bit ranges
SEQ_GROUPS['dsp1_projclip'] = ([(0x02, 7, 8), (0x0E, 2, 4), (0x06, 3, 6)], _clip)                         # zenith beyond the clip limit

if __name__ == '__main__':
    for n, (steps, g) in SEQ_GROUPS.items():
        print(make_seq(n, steps, g))
    for n, g in GROUPS.items():
        print(make(n, g['cmd'], g['np'], g['nr'], g['gen']))
