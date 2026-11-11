# Test master — self-checking test cartridges

`tools/testmaster/` builds deterministic ROMs that exercise a piece of hardware (today the DSP-1: every command and
every opcode alias) and **judge themselves**: each group of fixed-seed vectors is folded into a 16 bit checksum and
compared with the expected value embedded in the ROM. The ROM shows the verdict on screen (one line per group, green
PASS / red FAIL with the actual checksum) and leaves a machine-readable table in WRAM, so it runs unchanged on any
emulator or on real hardware.

```sh
python3 tools/testmaster/build.py dsp1            # rebuild out/test_master_dsp1.sfc from expected/dsp1.json
python3 tools/testmaster/build.py dsp1 --refresh  # recompute expected/dsp1.json on bsnes (reference)
python3 tools/testmaster/run.py tools/testmaster/out/test_master_dsp1.sfc   # matrix: ours | snes9x | bsnes
```

Result table at `$7E:2000` (`framework.py`): magic `TMST`, group count, pass count, `$A5` at `+0A` when finished,
(actual, expected) checksum per group at `+10`, fail flag per group at `+400`, raw outputs from `+1000`.

Status (DSP-1): ours 20/20, bsnes 20/20, snes9x 17/20 (it is wrong on MULTIPLY, DISTANCE, GYRATE).
Expected values follow bsnes; they are not measured on real hardware. Adding a chip = a module with `groups()` and an
entry in `SUITES` in `build.py`; a cartridge carries one chip, so there is one master ROM per chip. The ROM is also
checked in CI-style by `TestMasterTest` (copy in `src/test/resources/testmaster/`).
