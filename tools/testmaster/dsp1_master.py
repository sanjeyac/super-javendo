"""DSP-1 test master: every command and every opcode alias of the chip, with fixed-seed vectors.

Each step is (opcode, input words, result bytes, kind[, bytes written after the results]); `kind` names the generator
that produces the step's input words.
"""
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from framework import Group, WRAM  # noqa: E402

DR = 0x308000


def R(r, lo=-32768, hi=32767):
    return r.randint(lo, hi)


def near(r, k, n=3):
    return tuple(r.randint(-k, k) for _ in range(n))


KINDS = {
    'full2': lambda r: (R(r), R(r)),
    'inv': lambda r: (R(r), R(r, -12, 3)),
    'near2000': lambda r: near(r, 2000),
    'range': lambda r: near(r, 2000) + (R(r, 1, 4000),),
    'mixed3': lambda r: near(r, r.choice([50, 200, 700, 2500, 9000, 18000])),
    'rotate': lambda r: (R(r), R(r, -2000, 2000), R(r, -2000, 2000)),
    'full6': lambda r: tuple(R(r) for _ in range(6)),
    'att': lambda r: (R(r, 0, 32767), R(r), R(r), R(r)),
    'xyz': lambda r: near(r, 20000),
    'gyrate': lambda r: (R(r), R(r), R(r)) + near(r, 2000),
    'param': lambda r: (R(r, -3000, 3000), R(r, -3000, 3000), R(r, 1, 3000), R(r, 0, 0x4000), R(r, 1, 0x7000), R(r),
                        R(r, 0, 0x3800)),
    'param_wide': lambda r: tuple(R(r) for _ in range(7)),
    'param_clip': lambda r: (R(r, -3000, 3000), R(r, -3000, 3000), R(r, 0, 3000), R(r, 0, 0x4000), R(r, 1, 0x7000), R(r),
                             R(r, 0x3000, 0x7FFF) if r.random() < .5 else R(r, -32768, -0x3000)),
    'target': lambda r: near(r, 100, 2),
    'point': lambda r: near(r, 3000),
    'line': lambda r: (R(r, -200, 200),),
    'full1': lambda r: (R(r),),
    'wide2': lambda r: (R(r), R(r)),
    'wide3': lambda r: tuple(R(r) for _ in range(3)),
}


def group(name, steps, count, seed):
    rnd = random.Random(seed)
    table = bytearray()
    for _ in range(count):
        for st in steps:
            words = KINDS[st[3]](rnd)
            assert len(words) == st[1], (name, st)
            table += b''.join(struct.pack('<h', w) for w in words)
    out_per = sum(st[2] for st in steps)

    def emit(a):
        for st in steps:
            cmd, nw, nr = st[:3]
            tail = st[4] if len(st) > 4 else []
            a.lda_imm(cmd); a.sta_long(DR)
            for _ in range(nw * 2):
                a.lda_dp_long_y(0); a.sta_long(DR); a.iny()
            for _ in range(nr):
                a.lda_long(DR); a.sta_long_x(WRAM); a.inx()
            for b in tail:
                a.lda_imm(b); a.sta_long(DR)
    return Group(name, table, out_per * count, emit)


def matrix_steps(attitude, objective, subjective, scalar):
    return [(attitude, 4, 0, 'att'), (objective, 3, 6, 'xyz'), (subjective, 3, 6, 'xyz'), (scalar, 3, 2, 'xyz')]


def projection_steps(p, t, j, kind='param'):
    return [(p, 7, 8, kind), (t, 2, 4, 'target'), (j, 3, 6, 'point')]


def groups():
    return [
        group('MULTIPLY', [(0x00, 2, 2, 'full2'), (0x20, 2, 2, 'full2')], 120, 11),
        group('INVERSE', [(0x10, 2, 4, 'inv'), (0x30, 2, 4, 'inv')], 120, 12),
        group('TRIANGLE', [(0x04, 2, 4, 'full2'), (0x24, 2, 4, 'full2')], 120, 13),
        group('RADIUS', [(0x08, 3, 4, 'near2000')], 120, 14),
        group('RANGE', [(0x18, 4, 2, 'range'), (0x38, 4, 2, 'range')], 120, 15),
        group('DISTANCE', [(0x28, 3, 2, 'mixed3')], 300, 16),
        group('ROTATE', [(0x0C, 3, 4, 'rotate'), (0x2C, 3, 4, 'rotate')], 120, 17),
        group('POLAR', [(0x1C, 6, 6, 'full6'), (0x3C, 6, 6, 'full6')], 100, 18),
        group('ATTITUDE A', matrix_steps(0x01, 0x0D, 0x03, 0x0B), 100, 19),
        group('ATTITUDE B', matrix_steps(0x11, 0x1D, 0x13, 0x1B), 100, 20),
        group('ATTITUDE C', matrix_steps(0x21, 0x2D, 0x23, 0x2B), 100, 21),
        group('ATT ALIASES',
              [(0x05, 4, 0, 'att'), (0x09, 3, 6, 'xyz'), (0x33, 3, 6, 'xyz'), (0x3B, 3, 2, 'xyz'),
               (0x35, 4, 0, 'att'), (0x39, 3, 6, 'xyz'), (0x15, 4, 0, 'att'), (0x19, 3, 6, 'xyz'),
               (0x25, 4, 0, 'att'), (0x29, 3, 6, 'xyz'), (0x31, 4, 0, 'att'), (0x3D, 3, 6, 'xyz')], 60, 22),
        group('GYRATE', [(0x14, 6, 6, 'gyrate'), (0x34, 6, 6, 'gyrate')], 100, 23),
        group('PROJECTION', projection_steps(0x02, 0x0E, 0x06), 100, 24),
        group('PROJ ALIASES',
              projection_steps(0x12, 0x1E, 0x16) + projection_steps(0x22, 0x2E, 0x26) + projection_steps(0x32, 0x3E, 0x36),
              40, 25),
        group('PROJ WIDE', projection_steps(0x02, 0x0E, 0x06, 'param_wide'), 100, 26),
        group('PROJ CLIP', projection_steps(0x02, 0x0E, 0x06, 'param_clip'), 100, 27),
        group('RASTER', [(0x02, 7, 8, 'param'), (0x0A, 1, 22, 'line', [0x00, 0x80])], 100, 28),
        group('RASTER WIDE', [(0x02, 7, 8, 'param_wide'), (0x0A, 1, 22, 'full1', [0x00, 0x80])], 100, 29),
        group('MEMORY', [(0x0F, 1, 2, 'full1'), (0x07, 1, 2, 'full1'), (0x2F, 1, 2, 'full1'), (0x27, 1, 2, 'full1')], 20, 30),
    ]
