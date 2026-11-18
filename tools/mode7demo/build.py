"""Mode 7 test cartridge in the spirit of F-ZERO: a road seen in perspective, camera moving and steering.

Everything is made by this script (no third-party code or data): a 1024x1024 Mode 7 plane with a straight road, rumble
strips, lane dashes, boost pads and a grid-lined field; a matrix per scanline (HDMA channels 1 and 2 write M7A/M7B and
M7C/M7D, 4 bytes per line, one table pair per frame, 64 frames that loop seamlessly); sprites for a car and an energy bar.
The thin one-pixel lines and the fine dither on the road make aliasing visible, which is what an "enhanced Mode 7" must improve.

    python3 tools/mode7demo/build.py [out.sfc]          (default: tools/mode7demo/out/mode7demo.sfc)

Geometry (texture position of screen pixel (x, line s), camera at P looking along heading theta):
    t = s - HORIZON ; k = H / t (texels per pixel) ; d = H * FOCAL / t (texels ahead)
    T = P + (x - 128) * k * (cos, -sin) + d * (sin, cos)
With M7X = M7Y = 0, M7HOFS = 0 and M7VOFS = V0 the hardware computes (y = s + 1)
    X = (A * x + B * (V0 + y)) / 256 ,  Y = (C * x + D * (V0 + y)) / 256
so A, C carry the x terms and B, D are chosen per line to land on the constant part (texture is periodic, so mod 1024 is enough).
"""
import math
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'chiptests'))
from asm import Asm, prologue  # noqa: E402

FRAMES = 64
HORIZON, FIRST_ROAD = 60, 72           # lines above FIRST_ROAD show the backdrop (matrix of zeros hits a transparent texel)
H, FOCAL = 120.0, 128.0
V0 = -40
SKY = (40, 24, 90)
YAW = 0.45                              # radians, amplitude of the steering
LOOP_FORWARD = 576                      # texels travelled per loop: a multiple of the texture's period (64) in y


def bgr(c):
    r, g, b = c
    return (r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10)


# ---------------------------------------------------------------- texture
FIELD, GRID, ROAD, ROAD2, WHITE, RED, LANE, BOOST, CHEV = range(1, 10)
PALETTE = [SKY, (14, 22, 56), (58, 88, 176), (66, 66, 80), (80, 80, 96), (240, 240, 240), (226, 36, 110),
           (206, 206, 232), (255, 150, 28), (255, 238, 120)]
CX, HALF = 512, 64


def texel(x, y):
    y %= 64
    dx = x - CX
    if abs(dx) < HALF:                                              # road
        if 0 <= y < 24 and abs(dx) < 32:                            # boost pad with chevrons
            return CHEV if (y + abs(dx)) % 12 < 3 else BOOST
        if abs(abs(dx) - 32) < 1 and y % 32 < 16:                   # dashed lane lines, 2 px wide
            return LANE
        return ROAD2 if (x // 2 + y // 2) % 2 and abs(dx) % 16 < 8 else ROAD    # fine dither: high frequency on purpose
    if abs(dx) < HALF + 8:                                          # rumble strips
        return WHITE if (y // 8) % 2 else RED
    return GRID if (x % 16 == 0 or y % 16 == 0) else FIELD          # one-pixel grid


def build_plane():
    tiles, index, tmap = [], {}, []
    zero = bytes(64)
    tiles.append(zero); index[zero] = 0
    for ty in range(128):
        for tx in range(128):
            t = bytes(texel(tx * 8 + px, ty * 8 + py) for py in range(8) for px in range(8))
            if tx == 0 and ty == 0:
                t = zero                                            # the texel the sky lines point at: transparent
            if t not in index:
                index[t] = len(tiles); tiles.append(t)
            tmap.append(index[t])
    assert len(tiles) <= 256, len(tiles)
    pix = b''.join(tiles).ljust(256 * 64, b'\0')
    return bytes(tmap), pix, len(tiles)


# ---------------------------------------------------------------- per-frame matrices
def path():
    """Camera position and heading for every frame; ends where it began (mod the texture)."""
    th = [YAW * math.sin(2 * math.pi * f / FRAMES) for f in range(FRAMES)]
    speed = LOOP_FORWARD / sum(math.cos(t) for t in th)
    px, py, out = 512.0, 0.0, []
    for f in range(FRAMES):
        out.append((px, py, th[f]))
        px += speed * math.sin(th[f]); py += speed * math.cos(th[f])
    return out


def wrap(v):
    return (v + 512.0) % 1024.0 - 512.0


def s16(v):
    return max(-32768, min(32767, int(round(v))))


def tables(px, py, th):
    t1, t2 = bytearray(), bytearray()
    c, s = math.cos(th), math.sin(th)
    for line in range(224):
        if line < FIRST_ROAD:
            a = b = cc = d = 0
        else:
            t = line - HORIZON
            k, dist = H / t, H * FOCAL / t
            a, cc = 256 * c * k, -256 * s * k
            den = V0 + line + 1
            b = 256 * wrap(px - 128 * c * k + s * dist) / den
            d = 256 * wrap(py + 128 * s * k + c * dist) / den
        t1 += b'\x01' + struct.pack('<hh', s16(a), s16(b))
        t2 += b'\x01' + struct.pack('<hh', s16(cc), s16(d))
    return bytes(t1) + b'\x00', bytes(t2) + b'\x00'


# ---------------------------------------------------------------- sprites
CAR = [
    "......oooo......", ".....orrrro.....", ".....orccro.....", "....orrccrro....",
    "....orrrrrro....", "...orrrrrrrro...", "...orrrrrrrro...", "..orrrwwwwrrro..",
    "..orrrrrrrrrro..", ".orrrrrrrrrrrro.", ".orrrrrrrrrrrro.", "oorrrrrrrrrrrroo",
    "orro.orrrro.orro", "oooo.oooooo.oooo", "oo..........oo..", "................"]
CAR_COL = {'.': 0, 'r': 1, 'c': 2, 'o': 3, 'w': 4}


def tile4(rows):
    """8x8 tile of colour indexes -> 32 bytes of 4bpp planar data."""
    out = bytearray(32)
    for y, row in enumerate(rows):
        for x, c in enumerate(row):
            for p in range(4):
                if (c >> p) & 1:
                    out[(p // 2) * 16 + y * 2 + (p % 2)] |= 0x80 >> x
    return bytes(out)


def sprite_chr():
    art = [[CAR_COL[ch] for ch in r] for r in CAR]
    chars = bytearray(32 * 32)
    for ty in range(2):
        for tx in range(2):
            t = tile4([art[ty * 8 + y][tx * 8:tx * 8 + 8] for y in range(8)])
            n = ty * 16 + tx
            chars[n * 32:n * 32 + 32] = t
    bar = tile4([[1] * 8 for _ in range(8)])
    chars[4 * 32:5 * 32] = bar
    return bytes(chars)


def oam():
    o = bytearray()
    sprites = [(120, 190, 0, 0, True)]
    sprites += [(8 + 8 * i, 8, 4, 1, False) for i in range(14)]
    hi = bytearray(32)
    for i in range(128):
        if i < len(sprites):
            x, y, tile, pal, big = sprites[i]
            o += bytes([x & 255, y, tile, (pal << 1) | (3 << 4)])
            hi[i >> 2] |= ((2 if big else 0) | (x >> 8 & 1)) << ((i & 3) * 2)
        else:
            o += bytes([0, 240, 0, 0])
    return bytes(o) + bytes(hi)


def cgram():
    c = [bgr(x) for x in PALETTE] + [0] * (256 - len(PALETTE))
    spr = [(0, 0, 0), (214, 30, 40), (150, 220, 255), (24, 24, 40), (250, 250, 250)]
    for i, x in enumerate(spr):
        c[128 + i] = bgr(x)
    c[128 + 16 + 1] = bgr((40, 230, 90))
    return b''.join(struct.pack('<H', v) for v in c)


# ---------------------------------------------------------------- program
def build(path_out):
    tmap, pix, ntiles = build_plane()
    pal, sp, ob = cgram(), sprite_chr(), oam()
    frames = [tables(*p) for p in path()]

    rom = bytearray(0xFF for _ in range(256 * 1024))

    def put(bank, off, data):
        pos = bank * 0x8000 + off
        rom[pos:pos + len(data)] = data

    # bank 1: palette, OAM, sprite characters, tile map ; bank 2: Mode 7 pixels ; banks 3..: HDMA tables
    B1_PAL, B1_OAM, B1_CHR, B1_MAP = 0x8000, 0x8200, 0x8500, 0x8900
    put(1, B1_PAL - 0x8000, pal); put(1, B1_OAM - 0x8000, ob); put(1, B1_CHR - 0x8000, sp); put(1, B1_MAP - 0x8000, tmap)
    put(2, 0, pix)
    ptr, bank, off = [], 3, 0
    for t1, t2 in frames:
        if off + len(t1) + len(t2) > 0x8000:
            bank += 1; off = 0
        put(bank, off, t1); put(bank, off + len(t1), t2)
        ptr.append((0x8000 + off, 0x8000 + off + len(t1), bank))
        off += len(t1) + len(t2)

    a = Asm(0x8000)
    prologue(a)

    def w(reg, v):
        a.lda_imm(v); a.sta_abs(reg)

    def dma(mode, dest, bank_, addr, size):
        w(0x4300, mode); w(0x4301, dest)
        w(0x4302, addr & 255); w(0x4303, addr >> 8); w(0x4304, bank_)
        w(0x4305, size & 255); w(0x4306, size >> 8)
        w(0x420B, 1)

    w(0x2100, 0x8F)                                     # force blank
    w(0x4200, 0)
    w(0x420C, 0)
    w(0x2105, 7); w(0x2101, 0x03); w(0x212C, 0x11); w(0x211A, 0)
    w(0x211F, 0); w(0x211F, 0); w(0x2120, 0); w(0x2120, 0)          # M7X, M7Y = 0
    w(0x210D, 0); w(0x210D, 0)                                       # M7HOFS = 0
    w(0x210E, V0 & 255); w(0x210E, (V0 >> 8) & 0x1F)                 # M7VOFS = V0
    w(0x2121, 0); dma(0, 0x22, 1, B1_PAL, len(pal))
    w(0x2115, 0x00); w(0x2116, 0); w(0x2117, 0); dma(0, 0x18, 1, B1_MAP, len(tmap))
    w(0x2115, 0x80); w(0x2116, 0); w(0x2117, 0); dma(0, 0x19, 2, 0x8000, len(pix))
    w(0x2115, 0x80); w(0x2116, 0); w(0x2117, 0x60); dma(1, 0x18, 1, B1_CHR, len(sp))
    w(0x2102, 0); w(0x2103, 0); dma(0, 0x04, 1, B1_OAM, len(ob))
    w(0x4310, 3); w(0x4311, 0x1B); w(0x4320, 3); w(0x4321, 0x1D)    # HDMA: M7A/M7B and M7C/M7D, 4 bytes per line
    a.stz_abs(0x10); a.stz_abs(0x11)
    w(0x2100, 0x0F)
    w(0x4200, 0x80)
    a.label('idle'); a.bra('idle')

    a.label('nmi')
    a.lda_abs(0x4210)
    a.ldx_abs(0x10)
    for reg, tab in ((0x4312, 'lo1'), (0x4313, 'hi1'), (0x4314, 'bk'), (0x4322, 'lo2'), (0x4323, 'hi2'), (0x4324, 'bk')):
        a.lda_abs_x_label(tab); a.sta_abs(reg)
    w(0x420C, 0x06)
    a.inx(); a.cpx_imm(FRAMES); a.bne('keep'); a.ldx_imm(0)
    a.label('keep'); a.stx_abs(0x10)
    a.emit(0x40)                                         # RTI
    a.label('irq'); a.emit(0x40)
    for name, vals in (('lo1', [p[0] & 255 for p in ptr]), ('hi1', [p[0] >> 8 for p in ptr]),
                       ('lo2', [p[1] & 255 for p in ptr]), ('hi2', [p[1] >> 8 for p in ptr]), ('bk', [p[2] for p in ptr])):
        a.label(name); a.byte(*vals)
    code = a.finish()
    assert len(code) < 0x7FC0, len(code)
    rom[0:len(code)] = code

    h = 0x7FC0
    rom[h:h + 21] = b'MODE7 DEMO F-ZERO STYL'[:21].ljust(21)
    rom[h + 0x15], rom[h + 0x16], rom[h + 0x17], rom[h + 0x18], rom[h + 0x19] = 0x20, 0x00, 8, 0, 0x01
    rom[h + 0x1A], rom[h + 0x1B] = 0x33, 0x00
    for vec in (0x24, 0x26, 0x28, 0x2C, 0x2E, 0x34, 0x36, 0x38, 0x3C, 0x3E):
        rom[h + vec:h + vec + 2] = struct.pack('<H', 0x8000 if vec in (0x3C,) else a.labels['irq'])
    for vec in (0x2A, 0x3A):
        rom[h + vec:h + vec + 2] = struct.pack('<H', a.labels['nmi'])
    rom[h + 0x1C:h + 0x20] = b'\xFF\xFF\x00\x00'
    s = sum(rom) & 0xFFFF
    rom[h + 0x1C:h + 0x1E] = struct.pack('<H', s ^ 0xFFFF)
    rom[h + 0x1E:h + 0x20] = struct.pack('<H', s)
    os.makedirs(os.path.dirname(os.path.abspath(path_out)), exist_ok=True)
    open(path_out, 'wb').write(rom)
    print(f"{path_out}: {len(rom)} bytes, {ntiles} unique tiles, {len(frames)} frames, last table bank {bank}")


if __name__ == '__main__':
    build(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), 'out', 'mode7demo.sfc'))
