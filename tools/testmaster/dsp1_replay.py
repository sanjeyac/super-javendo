#!/usr/bin/env python3
"""Replay a recorded stream of DSP-1 accesses (java -Ddsp1log=FILE ...) on a reference emulator.

The cartridge writes the recorded writes and, for every recorded read, compares what the chip returns with the value
our emulator returned; the first mismatches are listed (chunk, offset, actual, expected).

usage: dsp1_replay.py LOG [--emu bsnes|snes9x]
"""
import os, struct, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'chiptests'))
from asm import Asm, prologue, build_rom, clear_results  # noqa: E402
sys.path.insert(0, HERE)
import emus  # noqa: E402

DR = 0x308000
WRAM = 0x7E2000
MAXREC = 200


def build(log, path):
    nchunks = (len(log) + 0x7FFF) // 0x8000
    a = Asm(); prologue(a)
    a.lda_imm(0); a.ldx_imm(0)
    a.label('clr'); a.sta_long_x(WRAM); a.inx(); a.cpx_imm(0x1100); a.bne('clr')
    a.ldx_imm(0x10)                                   # X = next record slot
    patches = []
    for c in range(nchunks):
        n = min(0x8000, len(log) - c * 0x8000)
        a.lda_imm(0); a.sta_dp(0); a.lda_imm(0x80); a.sta_dp(1)
        a.lda_imm(c + 1); a.sta_dp(2)
        a.ldy_imm(0)
        a.label(f'c{c}_loop')
        a.lda_dp_long_y(0); a.iny(); a.cmp_imm(0); a.bne(f'c{c}_read')
        a.lda_dp_long_y(0); a.iny(); a.sta_long(DR); a.bra(f'c{c}_next')
        a.label(f'c{c}_read')
        a.lda_dp_long_y(0); a.iny(); a.sta_dp(3)
        a.lda_long(DR); a.sta_dp(4); a.cmp_dp(3); a.beq(f'c{c}_next')
        a.cpx_imm(0x10 + 5 * MAXREC); a.bcs(f'c{c}_next')
        a.lda_imm(c); a.sta_long_x(WRAM); a.inx()
        a.rep(0x20); a.tya(); a.sta_long_x(WRAM); a.inx(); a.inx(); a.sep(0x20)
        a.lda_dp(4); a.sta_long_x(WRAM); a.inx(); a.lda_dp(3); a.sta_long_x(WRAM); a.inx()
        a.label(f'c{c}_next')
        a.cpy_imm(n); a.beq(f'c{c}_end'); a.jmp(f'c{c}_loop')
        a.label(f'c{c}_end')
    a.rep(0x20); a.txa(); a.sta_long(WRAM + 2); a.sep(0x20)
    for k, ch in enumerate(b'DSPR'):
        a.lda_imm(ch); a.sta_long(WRAM + 0x1000 + k)
    a.lda_imm(0xA5); a.sta_long(WRAM + 4)
    a.label('halt'); a.bra('halt')
    code = bytearray(a.finish())
    assert len(code) < 0x7FC0
    rom = bytearray(code.ljust(0x8000, b'\xFF')) + log
    size_kb = 1 << ((len(rom) + 1023) // 1024 - 1).bit_length()
    build_rom(bytes(rom), 'DSP1 REPLAY', 0x03, size_kb=size_kb, path=path)


def main():
    log = open(sys.argv[1], 'rb').read()
    emu = sys.argv[sys.argv.index('--emu') + 1] if '--emu' in sys.argv else 'bsnes'
    path = os.path.join(HERE, 'out', 'dsp1_replay.sfc')
    build(log, path)
    emus.FRAMES = 1200
    with tempfile.TemporaryDirectory() as t:
        if emu == 'bsnes':
            st = os.path.join(t, 'b.state')
            subprocess.run([sys.executable, emus.REF, path, '--frames', str(emus.FRAMES), '--core', emus.BSNES, '--state', st], check=True, capture_output=True)
            d = open(st, 'rb').read()
            i = d.find(b'DSPR')
            assert i >= 0, 'marker not found in the bsnes state'
            m = d[i - 0x1000:i - 0x1000 + 0x1100]
        else:
            w = os.path.join(t, 's.w')
            subprocess.run([sys.executable, emus.REF, path, '--frames', str(emus.FRAMES), '--wram', w], check=True, capture_output=True)
            m = open(w, 'rb').read()[0x2000:0x2000 + 0x1100]
    done = m[4] == 0xA5
    end = struct.unpack('<H', m[2:4])[0]
    print('finished' if done else 'NOT finished', 'records', (end - 0x10) // 5)
    for k in range((end - 0x10) // 5):
        c, y, act, exp = struct.unpack('<BHBB', m[0x10 + 5 * k:0x10 + 5 * k + 5])
        print(f'event {c * 0x4000 + y // 2:7d}  ours={exp:02X} ref={act:02X}')


main()
