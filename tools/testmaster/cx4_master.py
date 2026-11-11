"""Cx4 test master: every command of the chip with fixed-seed vectors.

Chip RAM is at $00:6000-$6BFF and the registers at $00:7F00-$7FFF (so the code addresses them as $00:6000 + offset).
A vector is a byte string; the ops of a group consume it in order: ('w', addr, n) writes n table bytes at chip address
addr, ('c', addr, value) writes a constant, ('r', addr, n) appends n bytes read from addr to the output.
"""
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from framework import Group, WRAM  # noqa: E402

BASE = 0x006000            # chip window as seen from bank 0
CMD = 0x1F4F
BLOB = 0x018000            # fixed cartridge data, see blob()


def R(r, lo=0, hi=255):
    return r.randint(lo, hi)


def le(v, n):
    return (v & ((1 << (8 * n)) - 1)).to_bytes(n, 'little')


def be16(v):
    return (v & 0xFFFF).to_bytes(2, 'big')


def blob():
    """Data the chip reads from the cartridge: vertices, a line list, sprite lists and bytes for the copy command."""
    rnd = random.Random(2024)
    b = bytearray(0x8000)
    for k in range(24):                                                   # vertices at $018000, big endian x y z
        b[6 * k:6 * k + 6] = be16(rnd.randint(-60, 60)) + be16(rnd.randint(-60, 60)) + be16(rnd.randint(-60, 60))
    for k in range(40):                                                   # line list at $018100: p1, p2, colour
        if k in (5, 17, 29):
            p1 = b'\xFF\xFF'                                              # "same start as the previous line"
        else:
            p1 = be16(0x8000 + 6 * rnd.randrange(24))
        b[0x100 + 5 * k:0x100 + 5 * k + 5] = p1 + be16(0x8000 + 6 * rnd.randrange(24)) + bytes([rnd.randrange(4)])
    for k in range(8):                                                    # sprite lists at $018300 + 0x20 * k
        n = rnd.randint(1, 5)
        b[0x300 + 0x20 * k] = n
        for e in range(n):
            b[0x300 + 0x20 * k + 1 + 4 * e:0x300 + 0x20 * k + 5 + 4 * e] = bytes(
                [rnd.choice([0, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0]), rnd.randrange(256), rnd.randrange(256), rnd.randrange(256)])
    b[0x300 + 0x20 * 8] = 0                                               # an empty list: plain sprite
    for k in range(256):                                                  # bytes for the copy command at $018400
        b[0x400 + k] = rnd.randrange(256)
    return bytes(b)


def group(name, ops, gen, count, seed, ram_read_bytes=None):
    rnd = random.Random(seed)
    need = sum(o[2] for o in ops if o[0] == 'w')
    ops = list(ops)
    table = bytearray()
    for _ in range(count):
        v = gen(rnd)
        assert len(v) == need, (name, len(v), need)
        table += v
    out_per = sum(o[2] for o in ops if o[0] == 'r')

    def emit(a):
        for op in ops:
            kind = op[0]
            if kind == 'w':
                addr, n = op[1], op[2]
                if n <= 8:
                    for k in range(n):
                        a.lda_dp_long_y(0); a.iny(); a.sta_long(BASE + addr + k)
                else:                                                      # bulk: copy through X, keep the output pointer
                    a.phx(); a.ldx_imm(0)
                    lbl = f'_w{len(a.code)}'
                    a.label(lbl); a.lda_dp_long_y(0); a.iny(); a.sta_long_x(BASE + addr); a.inx(); a.cpx_imm(n); a.bne(lbl)
                    a.plx()
            elif kind == 'f':                                              # fill n bytes of chip RAM with a constant
                addr, n, val = op[1], op[2], op[3]
                a.phx(); a.ldx_imm(0); a.lda_imm(val)
                lbl = f'_f{len(a.code)}'
                a.label(lbl); a.sta_long_x(BASE + addr); a.inx(); a.cpx_imm(n); a.bne(lbl)
                a.plx()
            elif kind == 'c':
                a.lda_imm(op[2]); a.sta_long(BASE + op[1])
            else:
                addr, n = op[1], op[2]
                if n <= 8:
                    for k in range(n):
                        a.lda_long(BASE + addr + k); a.sta_long_x(WRAM); a.inx()
                else:                                                      # bulk copy to WRAM with a block move
                    a.phy()
                    a.rep(0x20)
                    a.txa(); a.clc(); a.adc_imm(0x2000); a.tay()
                    a.ldx_imm(addr | 0x6000)
                    a.lda_imm(n - 1)
                    a.mvn(0x7E, 0x00)
                    a.tya(); a.sec(); a.sbc_imm(0x2000); a.tax()
                    a.sep(0x20)
                    a.phk(); a.plb()
                    a.ply()
    return Group(name, table, out_per * count, emit)


def W(addr, n):
    return ('w', addr, n)


def C(addr, v):
    return ('c', addr, v)


def F(addr, n, val=0):
    return ('f', addr, n, val)


def Rd(addr, n):
    return ('r', addr, n)


REGS = 0x1F80


def groups():
    g = []
    # --- 24 bit register maths --------------------------------------------------------------------------------
    g.append(group('TRIANGLE', [W(REGS, 6), C(CMD, 0x10), Rd(0x1F86, 6), W(REGS, 6), C(CMD, 0x13), Rd(0x1F86, 6)],
                   lambda r: bytes(R(r) for _ in range(12)), 100, 41))
    g.append(group('MUL SQUARE', [W(REGS, 6), C(CMD, 0x25), Rd(REGS, 3), W(REGS, 3), C(CMD, 0x54), Rd(REGS + 3, 6)],
                   lambda r: bytes(R(r) for _ in range(9)), 100, 42))
    g.append(group('SUM', [W(0, 2048), C(CMD, 0x40), Rd(REGS, 2)], lambda r: bytes(R(r) for _ in range(2048)), 4, 43))
    # the 24 bit pointer in register 0 selects where the constants go; each op writes 48 bytes there
    imm_ops = []
    for k in range(0x5E, 0x7E, 2):
        imm_ops += [C(REGS, 0), C(REGS + 1, 1), C(REGS + 2, 0), C(CMD, k), Rd(REGS, 3), Rd(0x100, 48)]
    g.append(group('IMMEDIATE', imm_ops, lambda r: b'', 1, 44))
    # snes9x differs from bsnes here (5C clears the pointer first; 89 also loads register 1): expected = bsnes behaviour
    g.append(group('IMM QUIRKS', [W(REGS, 3), C(CMD, 0x5C), Rd(REGS, 3), Rd(0, 48), C(CMD, 0x89), Rd(REGS, 6)],
                   lambda r: le(R(r, 0x100, 0xBE0), 3), 4, 45))
    # --- simple 16 bit maths ----------------------------------------------------------------------------------
    g.append(group('PROPULSION', [W(0x1F81, 2), W(0x1F83, 2), C(CMD, 0x05), Rd(REGS, 2)],
                   lambda r: bytes(R(r) for _ in range(4)), 120, 46))
    g.append(group('VECTOR LEN', [W(REGS, 2), W(0x1F83, 2), W(0x1F86, 2), C(CMD, 0x0D), Rd(0x1F89, 2), Rd(0x1F8C, 2)],
                   lambda r: struct.pack('<hhh', R(r, -3000, 3000), R(r, -3000, 3000), R(r, 1, 3000)), 120, 47))
    g.append(group('PYTHAGORAS', [W(REGS, 2), W(0x1F83, 2), C(CMD, 0x15), Rd(REGS, 2)],
                   lambda r: struct.pack('<hh', R(r, -20000, 20000), R(r, -20000, 20000)), 120, 48))
    g.append(group('ANGLE', [W(REGS, 2), W(0x1F83, 2), C(CMD, 0x1F), Rd(0x1F86, 2)],
                   lambda r: struct.pack('<hh', R(r, -30000, 30000), R(r, -30000, 30000)), 150, 49))
    g.append(group('TRAPEZOID', [W(REGS, 2), W(0x1F83, 2), W(0x1F86, 2), W(0x1F89, 2), W(0x1F8C, 2), W(0x1F8F, 2), W(0x1F93, 2),
                                 C(CMD, 0x22), Rd(0x800, 225), Rd(0x900, 225)],
                   lambda r: struct.pack('<hhhhHHh', R(r, -100, 300), R(r, 0, 300), R(r, -100, 300), R(r, -100, 100),
                                         R(r, 0, 0x1FF), R(r, 0, 0x1FF), R(r, 0, 300)), 10, 50))
    g.append(group('TRANSFORM', [W(0x1F81, 2), W(0x1F84, 2), W(0x1F87, 2), W(0x1F89, 1), W(0x1F8A, 1), W(0x1F8B, 1), W(0x1F90, 2),
                                 C(CMD, 0x2D), Rd(REGS, 2), Rd(0x1F83, 2)],
                   lambda r: struct.pack('<hhhBBBh', R(r, -100, 100), R(r, -100, 100), R(r, -100, 100), R(r, 0, 255), R(r, 0, 255),
                                         R(r, 0, 255), R(r, 0, 600)), 120, 51))
    g.append(group('TEST CMD', [C(0x1F4D, 0x0E), W(CMD, 1), Rd(REGS, 1)], lambda r: bytes([R(r) & 0x3C]), 60, 52))
    # --- wireframes ---------------------------------------------------------------------------------------------
    wf = [W(0x295, 1), C(0x1F80, 0x00), C(0x1F81, 0x81), C(0x1F82, 0x01), W(0x1F86, 3), W(0x1F90, 1)]
    wf_gen = lambda r: bytes([R(r, 1, 40), R(r, 0, 127), R(r, 0, 127), R(r, 0, 127), R(r, 20, 200)])
    g.append(group('WIREFRAME 01', [C(0x1F4D, 0)] + wf + [C(CMD, 0x01), Rd(0x300, 2304)], wf_gen, 2, 53))
    g.append(group('WIREFRAME 00', [C(0x1F4D, 8)] + wf + [C(CMD, 0x00), Rd(0x300, 2304)], wf_gen, 2, 54))
    # --- sprite / bitmap helpers --------------------------------------------------------------------------------
    def sr_gen(r):
        w, h = r.choice([8, 16, 24, 32]), r.choice([8, 16, 24, 32])
        bitmap = bytes(R(r) for _ in range(w * h // 2))
        bitmap += bytes(0x400 - len(bitmap)) if len(bitmap) < 0x400 else b''
        return struct.pack('<hhhBBhh', r.choice([0, 128, 256, 384, R(r, 0, 511)]), R(r, 0, w), R(r, 0, h), w, h,
                           R(r, 0x400, 0x4000), R(r, 0x400, 0x4000)) + bitmap[:0x400]
    sr = [W(0x600, 0x400), W(REGS, 2), W(0x1F83, 2), W(0x1F86, 2), W(0x1F89, 1), W(0x1F8C, 1), W(0x1F8F, 2), W(0x1F92, 2)]
    # reorder: the generator emits the parameters first, then the bitmap
    sr = [W(REGS, 2), W(0x1F83, 2), W(0x1F86, 2), W(0x1F89, 1), W(0x1F8C, 1), W(0x1F8F, 2), W(0x1F92, 2), W(0x600, 0x400)]
    g.append(group('SCALE ROTATE', [C(0x1F4D, 3), F(0, 0x400)] + sr + [C(CMD, 0x00), Rd(0, 0x400)], sr_gen, 3, 55))
    g.append(group('SCALE ROTATE2', [C(0x1F4D, 7), F(0, 0x400)] + sr + [C(CMD, 0x00), Rd(0, 0x400)], sr_gen, 3, 56))
    g.append(group('DISINTEGRATE', [C(0x1F4D, 0x0B), F(0, 0x400)] + sr + [C(CMD, 0x00), Rd(0, 0x400)], sr_gen, 3, 57))

    def wave_gen(r):
        return bytes([R(r, 0, 127)]) + bytes(R(r, 0, 31) for _ in range(32)) + bytes(R(r) for _ in range(128))
    g.append(group('BITPLANE WAVE', [C(0x1F4D, 0x0C), W(0x1F83, 1), W(0xA00, 32), W(0xB00, 128), C(CMD, 0x00), Rd(0, 0xA00)],
                   wave_gen, 2, 58))

    def oam_gen(r):
        n = R(r, 0, 8)
        head = bytes([n, R(r, 0, 100)]) + struct.pack('<hh', R(r, 0, 100), R(r, 0, 100))
        ents = b''
        for _ in range(8):
            ptr = 0x018300 + 0x20 * R(r, 0, 8)
            ents += struct.pack('<hh', R(r, 0, 300), R(r, 0, 250)) + bytes([R(r), R(r), R(r, 0, 0xC0) & 0xC0]) + le(ptr, 3) + bytes(6)
        return head + ents
    g.append(group('BUILD OAM', [C(0x1F4D, 0), W(0x620, 1), W(0x626, 1), W(0x621, 2), W(0x623, 2), W(0x220, 128),
                                 C(CMD, 0x00), Rd(0, 0x220)], oam_gen, 8, 59))
    # transform lines: vertices (16 bytes each) at ram 0, line pairs at 0xB02
    def tl_gen(r):
        nv, nl = R(r, 1, 8), R(r, 1, 10)
        vert = b''.join(bytes(1) + struct.pack('<h', R(r, -60, 60)) + bytes(2) + struct.pack('<h', R(r, -60, 60)) + bytes(2)
                        + struct.pack('<h', R(r, -60, 60)) + bytes(5) for _ in range(8))
        pairs = bytes(x for _ in range(10) for x in (R(r, 0, nv - 1), R(r, 0, nv - 1)))
        return bytes([nv, 0, R(r, 0, 127), R(r, 0, 127), R(r, 0, 127), R(r, 20, 200)]) + struct.pack('<H', nl) + vert + pairs
    g.append(group('XFORM LINES', [C(0x1F4D, 5), W(0x1F80, 2), W(0x1F83, 1), W(0x1F86, 1), W(0x1F89, 1), W(0x1F8C, 1),
                                       W(0xB00, 2), W(0, 128), W(0xB02, 20), C(CMD, 0x00), Rd(0, 0x80), Rd(0x600, 0x60)],
                   lambda r: (lambda t: t[:2] + t[2:5] + t[5:6] + t[6:8] + t[8:])(tl_gen(r)), 6, 60))
    # --- memory copy from the cartridge ---------------------------------------------------------------------------
    g.append(group('TRANSFER', [C(0x1F40, 0), C(0x1F41, 0x84), C(0x1F42, 0x01), W(0x1F43, 2), W(0x1F45, 2), C(0x1F47, 0), Rd(0x100, 64)],
                   lambda r: struct.pack('<HH', R(r, 1, 64), R(r, 0x100, 0x130)), 20, 61))
    return g
