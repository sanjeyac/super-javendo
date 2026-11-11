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
    'cx4': dict(module='cx4_master', title='CX4 MASTER', rom_type=0xF3, header='CX4 TEST MASTER', oracle='snes9x', own=('TRIANGLE', 'IMMEDIATE', 'IMM QUIRKS')),
    'dsp1': dict(module='dsp1_master', title='DSP1 MASTER', rom_type=0x03, header='DSP-1 TEST MASTER'),
}


def build(name, refresh=False):
    spec = SUITES[name]
    mod = __import__(spec['module'])
    groups = mod.groups()
    extra = {'fixed_blob': mod.blob()} if hasattr(mod, 'blob') else {}
    exp_path = os.path.join(HERE, 'expected', name + '.json')
    out = os.path.join(HERE, 'out', f'test_master_{name}.sfc')
    if refresh or not os.path.exists(exp_path):
        framework.build(spec['title'], spec['rom_type'], groups, {}, out, header_text=spec['header'], **extra)
        res = emus.run_all(out, only=[spec.get('oracle', 'bsnes')])[spec.get('oracle', 'bsnes')]
        expected = {g: c['actual'] for g, c in zip([g.name for g in groups], res['groups'])}
        if spec.get('own'):                       # groups where the reference emulator is known to deviate: freeze our own result
            own = emus.run_all(out, only=['ours'])['ours']
            for i, g in enumerate(groups):
                if g.name in spec['own']:
                    expected[g.name] = own['groups'][i]['actual']
        os.makedirs(os.path.dirname(exp_path), exist_ok=True)
        json.dump(expected, open(exp_path, 'w'), indent=1, sort_keys=True)
        print('expected checksums written to', exp_path)
    expected = json.load(open(exp_path))
    meta = framework.build(spec['title'], spec['rom_type'], groups, expected, out, header_text=spec['header'], **extra)
    print(f'{out}: {len(groups)} groups, {meta["size_kb"]} KB')
    return out


if __name__ == '__main__':
    build(sys.argv[1], '--refresh' in sys.argv)
