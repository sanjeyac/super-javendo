#!/usr/bin/env python3
"""Run a test master ROM on every emulator and print the group x emulator matrix.

usage: run.py ROM.sfc [suite]     (suite = module name for the group names, default dsp1)
"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import emus  # noqa: E402

rom = sys.argv[1]
suite = sys.argv[2] if len(sys.argv) > 2 else 'dsp1'
names = [g.name for g in __import__(suite + '_master').groups()]
res = emus.run_all(rom)
cols = list(res)
print(f'{"group":16}' + ''.join(f'{c:>10}' for c in cols))
for i, n in enumerate(names):
    row = ''
    for c in cols:
        r = res[c]
        row += f'{"-" if not r["ok"] else ("FAIL" if r["groups"][i]["fail"] else "ok"):>10}'
    print(f'{n:16}{row}')
print(f'{"passed":16}' + ''.join(f'{(str(r["passed"]) + "/" + str(len(names))) if r["ok"] else "error":>10}' for r in res.values()))
for c, r in res.items():
    if not r['ok']:
        print(c, r.get('error'))
