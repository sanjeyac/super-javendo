#!/usr/bin/env python3
"""Build a test master cartridge.

usage: build.py dsp1 [--refresh]

--refresh recomputes the expected checksums by running the cartridge on the reference emulator (bsnes) and
writes them to expected/<name>.json; without it the frozen json is used and no emulator is needed.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import framework  # noqa: E402
import emus  # noqa: E402

SUITES = {
    'dsp1': dict(module='dsp1_master', title='DSP1 MASTER', rom_type=0x03, header='DSP-1 TEST MASTER'),
}


def build(name, refresh=False):
    spec = SUITES[name]
    mod = __import__(spec['module'])
    groups = mod.groups()
    exp_path = os.path.join(HERE, 'expected', name + '.json')
    out = os.path.join(HERE, 'out', f'test_master_{name}.sfc')
    if refresh or not os.path.exists(exp_path):
        framework.build(spec['title'], spec['rom_type'], groups, {}, out, header_text=spec['header'])
        res = emus.run_all(out, only=['bsnes'])['bsnes']
        expected = {g: c['actual'] for g, c in zip([g.name for g in groups], res['groups'])}
        os.makedirs(os.path.dirname(exp_path), exist_ok=True)
        json.dump(expected, open(exp_path, 'w'), indent=1, sort_keys=True)
        print('expected checksums written to', exp_path)
    expected = json.load(open(exp_path))
    meta = framework.build(spec['title'], spec['rom_type'], groups, expected, out, header_text=spec['header'])
    print(f'{out}: {len(groups)} groups, {meta["size_kb"]} KB')
    return out


if __name__ == '__main__':
    build(sys.argv[1], '--refresh' in sys.argv)
