#!/usr/bin/env python3
"""compare.py OURS.png REF.png OUT.png  -> prints % differing pixels, writes [ours | ref | diff] strip."""
import os, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngio import read_png, write_png

a, b = read_png(sys.argv[1]), read_png(sys.argv[2])
h, w = min(a.shape[0], b.shape[0]), min(a.shape[1], b.shape[1])
a, b = a[:h, :w].astype(int), b[:h, :w].astype(int)
d = np.abs(a - b).max(axis=2)
bad = d > 8                      # tolerate RGB565/BGR555 rounding
diff = np.zeros((h, w, 3), np.uint8)
diff[bad] = (255, 0, 0)
diff[~bad] = (a[~bad] // 4).astype(np.uint8)
rows = np.where(bad.any(axis=1))[0]
print(f"{bad.mean() * 100:.2f}% pixels differ; max delta {d.max()}; "
      f"differing rows: {rows.min() if len(rows) else '-'}..{rows.max() if len(rows) else '-'}")
write_png(sys.argv[3], np.concatenate([a.astype(np.uint8), b.astype(np.uint8), diff], axis=1))
