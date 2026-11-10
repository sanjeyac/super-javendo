#!/usr/bin/env python3
"""
Headless, deterministic runner for a libretro core (default: snes9x), used as the reference
emulator. Same scripting as SuperJavendo's --headless mode:

  refrun.py ROM --frames N [--every K PREFIX] [--shot OUT.png] [--press BTN:from:to]... [--press2 ...]

Frames are counted from power-on, exactly like `superjavendo --headless`.
"""
import argparse
import ctypes as C
import os
import sys
import tempfile
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngio import write_png  # noqa: E402

DEFAULT_CORE = "/usr/lib/libretro/snes9x_libretro.so"
BUTTON_IDS = {"b": 0, "y": 1, "select": 2, "start": 3, "up": 4, "down": 5,
              "left": 6, "right": 7, "a": 8, "x": 9, "l": 10, "r": 11}

ENV_GET_CAN_DUPE, ENV_GET_SYSTEM_DIR, ENV_SET_PIXEL_FORMAT = 3, 9, 10
ENV_GET_SAVE_DIR = 31
ENV_GET_LOG = 27


class GameInfo(C.Structure):
    _fields_ = [("path", C.c_char_p), ("data", C.c_void_p), ("size", C.c_size_t), ("meta", C.c_char_p)]


class AVInfo(C.Structure):
    _fields_ = [("base_w", C.c_uint), ("base_h", C.c_uint), ("max_w", C.c_uint), ("max_h", C.c_uint),
                ("aspect", C.c_float), ("fps", C.c_double), ("sample_rate", C.c_double)]


ENV_CB = C.CFUNCTYPE(C.c_bool, C.c_uint, C.c_void_p)
VIDEO_CB = C.CFUNCTYPE(None, C.c_void_p, C.c_uint, C.c_uint, C.c_size_t)
AUDIO_CB = C.CFUNCTYPE(C.c_size_t, C.POINTER(C.c_int16), C.c_size_t)
AUDIO1_CB = C.CFUNCTYPE(None, C.c_int16, C.c_int16)
POLL_CB = C.CFUNCTYPE(None)
INPUT_CB = C.CFUNCTYPE(C.c_int16, C.c_uint, C.c_uint, C.c_uint, C.c_uint)


def parse_presses(items):
    out = []
    for it in items or []:
        name, a, b = it.split(":")
        out.append((BUTTON_IDS[name.lower()], int(a), int(b)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rom")
    ap.add_argument("--core", default=DEFAULT_CORE)
    ap.add_argument("--frames", type=int, default=60)
    ap.add_argument("--every", nargs=2, metavar=("K", "PREFIX"))
    ap.add_argument("--shot")
    ap.add_argument("--press", action="append")
    ap.add_argument("--press2", action="append")
    ap.add_argument("--audio-stats", action="store_true")
    args = ap.parse_args()

    core = C.CDLL(args.core)
    presses = [parse_presses(args.press), parse_presses(args.press2)]
    state = {"frame": 0, "fmt": 2, "img": None, "samples": [], "dir": tempfile.mkdtemp(prefix="refrun_")}
    keep = []  # keep ctypes callbacks alive

    def env(cmd, data):
        if cmd == ENV_SET_PIXEL_FORMAT:
            state["fmt"] = C.cast(data, C.POINTER(C.c_int)).contents.value
            return True
        if cmd == ENV_GET_CAN_DUPE:
            C.cast(data, C.POINTER(C.c_bool)).contents.value = True
            return True
        if cmd in (ENV_GET_SYSTEM_DIR, ENV_GET_SAVE_DIR):
            b = state["dir"].encode()
            keep.append(b)
            C.cast(data, C.POINTER(C.c_char_p)).contents.value = b
            return True
        return False

    def video(data, w, h, pitch):
        if not data:
            return  # duplicated frame, keep the previous image
        buf = (C.c_uint8 * (pitch * h)).from_address(data)
        a = np.frombuffer(buf, np.uint8).copy()
        fmt = state["fmt"]
        if fmt == 1:  # XRGB8888 (little endian BGRA in memory)
            px = a.reshape(h, pitch // 4, 4)[:, :w, :]
            state["img"] = px[:, :, [2, 1, 0]].copy()
        else:  # 2 = RGB565, 0 = 0RGB1555
            v = a.view(np.uint16).reshape(h, pitch // 2)[:, :w].astype(np.uint32)
            if fmt == 2:
                r, g, b = (v >> 11) & 31, (v >> 5) & 63, v & 31
                r, g, b = (r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)
            else:
                r, g, b = (v >> 10) & 31, (v >> 5) & 31, v & 31
                r, g, b = (r << 3) | (r >> 2), (g << 3) | (g >> 2), (b << 3) | (b >> 2)
            state["img"] = np.stack([r, g, b], axis=2).astype(np.uint8)

    def audio_batch(ptr, frames):
        if args.audio_stats:
            arr = np.ctypeslib.as_array(ptr, shape=(frames * 2,)).copy()
            state["samples"].append(arr)
        return frames

    def audio_one(l, r):
        pass

    def poll():
        pass

    def inp(port, device, index, bid):
        if device != 1 or port > 1:
            return 0
        f = state["frame"]
        for b, lo, hi in presses[port]:
            if b == bid and lo <= f < hi:
                return 1
        return 0

    cbs = [ENV_CB(env), VIDEO_CB(video), AUDIO1_CB(audio_one), AUDIO_CB(audio_batch), POLL_CB(poll), INPUT_CB(inp)]
    keep.extend(cbs)
    core.retro_set_environment(cbs[0])
    core.retro_init()
    core.retro_set_video_refresh(cbs[1])
    core.retro_set_audio_sample(cbs[2])
    core.retro_set_audio_sample_batch(cbs[3])
    core.retro_set_input_poll(cbs[4])
    core.retro_set_input_state(cbs[5])

    rom_bytes = open(args.rom, "rb").read()
    rom_buf = C.create_string_buffer(rom_bytes, len(rom_bytes))
    gi = GameInfo(args.rom.encode(), C.cast(rom_buf, C.c_void_p), len(rom_bytes), None)
    core.retro_load_game.restype = C.c_bool
    if not core.retro_load_game(C.byref(gi)):
        sys.exit("core refused the ROM")
    av = AVInfo()
    core.retro_get_system_av_info(C.byref(av))
    print(f"core ok: {av.base_w}x{av.base_h} @ {av.fps:.4f} fps, audio {av.sample_rate:.0f} Hz, "
          f"pixel format {state['fmt']}")

    every = int(args.every[0]) if args.every else 0
    t0 = time.time()
    for f in range(args.frames):
        state["frame"] = f
        core.retro_run()
        if every and (f + 1) % every == 0 and state["img"] is not None:
            write_png(f"{args.every[1]}_{f + 1:05d}.png", state["img"])
    dt = time.time() - t0
    print(f"ran {args.frames} frames in {dt:.1f}s ({args.frames / max(dt, 1e-9):.0f} fps)")
    if args.shot and state["img"] is not None:
        write_png(args.shot, state["img"])
        print("saved", args.shot, state["img"].shape)
    if args.audio_stats and state["samples"]:
        s = np.concatenate(state["samples"]).astype(np.float64)
        print(f"audio: {len(s) // 2} stereo samples, peak={np.abs(s).max():.0f} rms={np.sqrt((s ** 2).mean()):.0f}")
    core.retro_unload_game()
    core.retro_deinit()


if __name__ == "__main__":
    main()
