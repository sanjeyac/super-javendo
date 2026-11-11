"""Python prototype of the DSP-1 HLE commands, checked against snes9x dumps."""
import math, os, random, struct, subprocess, sys
sys.path.insert(0, os.path.dirname(__file__))
import dsp1gen

def ref_dump(rom):
    r = '/tmp/dsp1_ref.w'
    subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), '..', 'diff', 'refrun.py'), rom, '--frames', '30', '--wram', r], capture_output=True, check=True)
    return open(r, 'rb').read()[0x2000:0x4000]

def vectors(group):
    g = dsp1gen.GROUPS[group]
    rnd = random.Random(1)
    return [g['gen'](rnd) for _ in range(120)], g

def words(d, n):
    return list(struct.unpack('<%dh' % (len(d) // 2), d[:n]))

def s16(v):
    v &= 0xFFFF
    return v - 0x10000 if v & 0x8000 else v

def check(group, model, show=3):
    vecs, g = vectors(group)
    d = ref_dump(os.path.join(dsp1gen.OUT, group + '.sfc'))
    nrw = g['nr'] // 2
    bad = 0
    for i, v in enumerate(vecs):
        ref = list(struct.unpack('<%dh' % nrw, d[i * g['nr']:(i + 1) * g['nr']]))
        got = [s16(x) for x in model(*v)]
        if got != ref:
            bad += 1
            if bad <= show: print('  ', v, 'ref', ref, 'got', got)
    print(f'{group}: {len(vecs) - bad}/{len(vecs)} match')
    return bad

# ---- sin/cos tables (candidate formulas) ----
_pos = [min(32767, math.floor(32768 * math.sin(2 * math.pi * i / 256))) for i in range(128)]
SIN_TABLE = (_pos + [-v for v in _pos]) * 2
MUL = [0, 3, 6, 9, 12, 15, 18, 21, 25, 28, 31, 34, 37, 40, 43, 47, 50, 53, 56, 59, 62, 65, 69, 72, 75, 78, 81, 84, 87, 91, 94, 97, 100, 103, 106, 109, 113, 116, 119, 122, 125, 128, 131, 135, 138, 141, 144, 147, 150, 153, 157, 160, 163, 166, 169, 172, 175, 179, 182, 185, 188, 191, 194, 197, 201, 204, 207, 210, 213, 216, 219, 223, 226, 229, 232, 235, 238, 241, 245, 248, 251, 254, 257, 260, 263, 267, 270, 273, 276, 279, 282, 285, 289, 292, 295, 298, 301, 304, 307, 311, 314, 317, 320, 323, 326, 329, 333, 336, 339, 342, 345, 348, 351, 355, 358, 361, 364, 367, 370, 373, 376, 380, 383, 386, 389, 392, 395, 398, 402, 405, 408, 411, 414, 417, 420, 424, 427, 430, 433, 436, 439, 442, 446, 449, 452, 455, 458, 461, 464, 468, 471, 474, 477, 480, 483, 486, 490, 493, 496, 499, 502, 505, 508, 512, 515, 518, 521, 524, 527, 530, 534, 537, 540, 543, 546, 549, 552, 556, 559, 562, 565, 568, 571, 574, 578, 581, 584, 587, 590, 593, 596, 600, 603, 606, 609, 612, 615, 618, 622, 625, 628, 631, 634, 637, 640, 644, 647, 650, 653, 656, 659, 662, 666, 669, 672, 675, 678, 681, 684, 688, 691, 694, 697, 700, 703, 706, 710, 713, 716, 719, 722, 725, 728, 731, 735, 738, 741, 744, 747, 750, 753, 757, 760, 763, 766, 769, 772, 775, 779, 782, 785, 788, 791, 794, 797, 801]

def dsp_sin(a):
    if a < 0:
        return 0 if a == -32768 else -dsp_sin(-a)
    s = SIN_TABLE[a >> 8] + ((MUL[a & 0xFF] * SIN_TABLE[0x40 + (a >> 8)]) >> 15)
    return min(s, 32767)

def dsp_cos(a):
    if a < 0:
        if a == -32768: return -32768
        a = -a
    s = SIN_TABLE[0x40 + (a >> 8)] - ((MUL[a & 0xFF] * SIN_TABLE[a >> 8]) >> 15)
    return -32767 if s < -32768 else s

models = {
  'dsp1_multiply':  lambda a, b: [(a * b) >> 15],
  'dsp1_multiply2': lambda a, b: [((a * b) >> 15) + 1],
  'dsp1_triangle':  lambda ang, r: [(dsp_sin(ang) * r) >> 15, (dsp_cos(ang) * r) >> 15],
  'dsp1_radius':    lambda x, y, z: [((x*x + y*y + z*z) * 2) & 0xFFFF, (((x*x + y*y + z*z) * 2) >> 16)],
  'dsp1_range':     lambda x, y, z, r: [(x*x + y*y + z*z - r*r) >> 15],
  'dsp1_distance':  lambda x, y, z: [int(math.isqrt(x*x + y*y + z*z))],
  'dsp1_rotate':    lambda ang, x1, y1: [((y1 * dsp_sin(ang)) >> 15) + ((x1 * dsp_cos(ang)) >> 15), ((y1 * dsp_cos(ang)) >> 15) - ((x1 * dsp_sin(ang)) >> 15)],
}

if __name__ == '__main__':
    for g in (sys.argv[1:] or models):
        check(g, models[g])
