#!/usr/bin/env python3
"""
Runs every test ROM on SuperJavendo and on the snes9x libretro core with no input, compares the frame
after N frames and writes a table (sorted worst first) plus side-by-side strips.

  suite.py [--frames 300] [--out DIR] [--roms test_roms] [--jobs 4] [--skip REGEX] [--md results.md]
"""
import argparse, concurrent.futures as cf, os, re, subprocess, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from pngio import read_png, write_png  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def run_one(rom, out, frames):
    rel = os.path.relpath(rom, args.roms)
    key = re.sub(r"[^A-Za-z0-9]+", "_", rel[:-4]).strip("_")
    ours, ref = f"{out}/{key}_ours.png", f"{out}/{key}_ref.png"
    r = {"rel": rel, "key": key}
    try:
        p = subprocess.run(["java", "-jar", f"{ROOT}/target/superjavendo.jar", rom, "--headless", str(frames), "--shot", ours],
                           capture_output=True, text=True, timeout=120)
        r["ours_ok"] = os.path.exists(ours)
        m = re.search(r"Title: (.*?) \|", p.stdout)
        r["title"] = m.group(1) if m else ""
        if not r["ours_ok"]:
            r["error"] = (p.stderr.strip().splitlines() or ["no output"])[-1][:120]
    except subprocess.TimeoutExpired:
        r["ours_ok"] = False; r["error"] = "timeout"
    try:
        p = subprocess.run([sys.executable, f"{HERE}/refrun.py", rom, "--frames", str(frames), "--shot", ref],
                           capture_output=True, text=True, timeout=120)
        r["ref_ok"] = os.path.exists(ref)
        if not r["ref_ok"]: r["error"] = r.get("error") or "ref: " + (p.stdout + p.stderr).strip()[-100:]
    except subprocess.TimeoutExpired:
        r["ref_ok"] = False
    if r["ours_ok"] and r["ref_ok"]:
        a, b = read_png(ours).astype(int), read_png(ref).astype(int)
        h, w = min(a.shape[0], b.shape[0]), min(a.shape[1], b.shape[1])
        a, b = a[:h, :w], b[:h, :w]
        d = np.abs(a - b).max(axis=2); bad = d > 8
        r["pct"] = float(bad.mean() * 100)
        rows = np.where(bad.any(axis=1))[0]
        r["rows"] = f"{rows.min()}-{rows.max()}" if len(rows) else "-"
        r["blank_ours"] = bool((a.max(axis=(0, 1)) < 8).all())
        r["blank_ref"] = bool((b.max(axis=(0, 1)) < 8).all())
        diff = np.zeros((h, w, 3), np.uint8); diff[bad] = (255, 0, 0); diff[~bad] = (a[~bad] // 4).astype(np.uint8)
        write_png(f"{out}/{key}_cmp.png", np.concatenate([a.astype(np.uint8), b.astype(np.uint8), diff], axis=1))
    return r


ap = argparse.ArgumentParser()
ap.add_argument("--frames", type=int, default=300)
ap.add_argument("--out", required=True)
ap.add_argument("--roms", default=f"{ROOT}/test_roms")
ap.add_argument("--jobs", type=int, default=4)
ap.add_argument("--skip", default=r"^(CHIP/|MSU/)")
ap.add_argument("--md", default=None)
args = ap.parse_args()
os.makedirs(args.out, exist_ok=True)

roms, skipped = [], []
for d, _, fs in os.walk(args.roms):
    for f in fs:
        if f.lower().endswith(".sfc"):
            full = os.path.join(d, f)
            (skipped if re.search(args.skip, os.path.relpath(full, args.roms)) else roms).append(full)
roms.sort()
print(f"{len(roms)} ROMs to run, {len(skipped)} skipped", flush=True)
with cf.ThreadPoolExecutor(args.jobs) as ex:
    res = list(ex.map(lambda r: run_one(r, args.out, args.frames), roms))

import json
json.dump({"frames": args.frames, "results": res, "skipped": [os.path.relpath(s, args.roms) for s in skipped]},
          open(f"{args.out}/results.json", "w"), indent=1)
ok = [r for r in res if "pct" in r]
print(f"compared {len(ok)}; identical(<0.05%): {sum(r['pct'] < 0.05 for r in ok)}; failed to run: {len(res) - len(ok)}")
