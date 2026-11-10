# test_roms

ROM homebrew di test (non commerciali) per validare l'emulatore in modo deterministico: niente randomicità,
stessa immagine a ogni esecuzione. Da usare con `diff.md` (confronto con snes9x) o a mano.

Origine: <https://github.com/PeterLemon/SNES> (Peter Lemon, "krom"), solo i file `.sfc` precompilati, struttura delle cartelle invariata.
Il repo non dichiara una licenza: tenere i file solo per uso locale (non committarla in un repo pubblico).

| Cartella (`PeterLemon/…`) | Cosa verifica | Stato emulatore |
|---|---|---|
| `CPUTest/CPU` (23) | tutte le istruzioni 65816, esito a schermo | supportato |
| `CPUTest/SPC700` (7) | istruzioni SPC700 | supportato |
| `PPU/BGMAP`, `Blend`, `Window`, `Mosaic`, `HDMA`, `Mode7`, `Interlace`, `Rings`, `GreenSpace` | PPU: modi BG, color math, finestre, mosaico, HDMA, Mode 7 | supportato (interlace/hi-res da verificare) |
| `SPC700/*` | audio: BRR, rumore, pitch mod, brani | supportato |
| `BANK/*` | mappatura LoROM/HiROM, fast/slow, WRAM | supportato |
| `HelloWorld`, `Compress/LZ77`, `Games/MonsterFarmJump`, `PlotLine`, `PlotPixel` | demo varie | supportato |
| `INPUT/*` | latenza controller, tastiera XBand | tastiera XBand non supportata |
| `MSU/*` | MSU-1 (richiedono anche file `.msu`/`.pcm`, non inclusi) | **non supportato** |
| `CHIP/GSU/*` (58) | SuperFX: istruzioni, PlotPixel/Line, FillPoly | **non supportato** (da implementare) |
