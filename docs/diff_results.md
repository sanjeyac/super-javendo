# diff_results.md — SuperJavendo vs snes9x sulle ROM di test

> **Nota (hi-res, ultimo run):** con modi 5/6, pseudo hi-res e interlacciato implementati le 13 ROM "risoluzione diversa" (+3 `TEST/`) sono **identiche** a snes9x; la suite completa (frame 300) dà **135/164 identiche** (prima 122). Le tabelle sotto sono del run precedente.

> **Nota (SuperFX):** dopo l'implementazione del chip le 58 ROM `CHIP/GSU` girano (RAM del chip identica a snes9x; 122/164 schermate identiche nell'ultimo run completo). Le tabelle sotto sono del run precedente, prima del chip.

> Aggiornato **dopo la correzione dello scarto di 1 riga** nel PPU (`y = riga + 1 + vofs`). Risultati precedenti: 15 identiche + 52 con scarto, 18 con residui.

Eseguito con `tools/diff/suite.py --frames 300`: **164 ROM** di `test_roms/` lanciate senza input su SuperJavendo e sul core libretro di snes9x, confrontato il frame 300 (tolleranza ±8 per canale, vedi [diff.md](diff.md)).
Le strisce `[nostro | snes9x | diff]` non sono nel repository (si rigenerano col comando in fondo).

## Sintesi

| Esito | ROM | Significato |
|---|---:|---|
| ✅ identiche (< 0.5% pixel diversi) | 82 | uguali a snes9x (9 sono ROM audio a schermo vuoto, poco significative) |
| ⚠️ quasi uguali | 3 | residuo 0.5–2.2% |
| ❌ ancora diverse | 0 | |
| 🖥️ risoluzione diversa | 13 | snes9x produce 512×224 o 512×448 (hi-res/interlacciato), noi sempre 256×224 |
| ⛔ chip non supportati | 66 | SuperFX (58) e MSU-1 (8) |

Il fix della riga ha risolto anche tutti gli effetti HDMA che sembravano bug a parte (WaveHDMA, Mode7HDMA, HiColor64/128PerTileRow, WindowHDMA, Mode7/Perspective): erano solo conseguenza dello scarto.

Per categoria:

| Categoria | ROM | identiche | residuo | risoluzione | chip |
|---|---:|---:|---:|---:|---:|
| BANK | 5 | 5 | 0 | 0 | 0 |
| CHIP | 58 | 0 | 0 | 0 | 58 |
| CPUTest/CPU | 23 | 21 | 2 | 0 | 0 |
| CPUTest/SPC700 | 7 | 7 | 0 | 0 | 0 |
| Compress | 1 | 0 | 0 | 1 | 0 |
| Games | 1 | 0 | 0 | 1 | 0 |
| HelloWorld | 1 | 1 | 0 | 0 | 0 |
| INPUT | 2 | 2 | 0 | 0 | 0 |
| MSU | 8 | 0 | 0 | 0 | 8 |
| PPU/BGMAP | 10 | 10 | 0 | 0 | 0 |
| PPU/Blend | 3 | 3 | 0 | 0 | 0 |
| PPU/GreenSpace | 1 | 1 | 0 | 0 | 0 |
| PPU/HDMA | 19 | 15 | 0 | 4 | 0 |
| PPU/Interlace | 6 | 0 | 0 | 6 | 0 |
| PPU/Mode7 | 3 | 2 | 1 | 0 | 0 |
| PPU/Mosaic | 2 | 1 | 0 | 1 | 0 |
| PPU/Rings | 1 | 1 | 0 | 0 | 0 |
| PPU/Window | 2 | 2 | 0 | 0 | 0 |
| PlotLine | 1 | 1 | 0 | 0 | 0 |
| PlotPixel | 1 | 1 | 0 | 0 | 0 |
| SPC700 | 9 | 9 | 0 | 0 | 0 |

## Problemi rimasti (in ordine di resa)

### 1. ~~Scarto di 1 riga~~ — risolto
Fix in `Ppu.renderBg`/`renderMode7`, coperto da `PpuTest` (`bgLineZeroShowsSecondTileRow`, `mode7LineZeroSamplesSecondRow`).

### 2. CPU: il test PLP (`CPUTest/CPU/PHL`) fallisce e la ROM si ferma
Da noi la schermata resta ferma su `PLP (Opcode: $28)` con `BIN,8 $25  NVZC 0011  FAIL`; snes9x supera il test e arriva a `PLY ($7A)` (residuo 2.2%).
PLP/PHP passano i test unitari (`CpuTest.plp*`), quindi la causa è altrove: serve tracciare la ROM. `CPUTest/CPU/MSC` ha un residuo dello 0.56%.

### 3. Alta risoluzione e interlacciato (non implementati)
13 ROM producono 512 pixel di larghezza in snes9x: **Interlace** (6), **Mosaic Mode 5**, **HiColor64PerTileRowPseudoHiRes** (4), più `Compress/LZ77WRAMGFX` e `Games/MonsterFarmJump`.
Servono modi 5/6, pseudo hi-res (SETINI bit 3) e interlacciato; l'uscita diventa 512×224/448 e la finestra deve gestirlo.

### 4. Mode 7 StarWars (1.0%)
Residuo piccolo, da guardare (arrotondamenti della matrice o animazione).

### 5. Non supportati (previsti)
- **SuperFX / GSU**: 58 ROM. **MSU-1**: 8 ROM, richiedono anche i file dati `.msu/.pcm`.

### 6. Audio non confrontato
Le ROM `SPC700/*` mostrano solo testo/schermo vuoto: serve un confronto delle forme d'onda (`--wav` da noi, `refrun.py --audio-stats`).

## ROM con residuo

| ROM | % diversi |
|---|---:|
| CPUTest/CPU/PHL/CPUPHL.sfc | 2.21 |
| PPU/Mode7/StarWars/StarWars.sfc | 1.01 |
| CPUTest/CPU/MSC/CPUMSC.sfc | 0.56 |

## ROM con risoluzione diversa

- Compress/LZ77/LZ77WRAMGFX/LZ77WRAMGFX.sfc — snes9x 512×448
- Games/MonsterFarmJump/MonsterFarmJump.sfc — snes9x 512×448
- PPU/HDMA/HiColor64PerTileRowPseudoHiRes/HiColor64PerTileRowPseudoHiRes.sfc — snes9x 512×224
- PPU/HDMA/HiColor64PerTileRowPseudoHiRes/TEST/RGB_24bits_palette_color_test_chart64PerTileRowHiRes.sfc — snes9x 512×224
- PPU/HDMA/HiColor64PerTileRowPseudoHiRes/TEST/lenna64PerTileRowHiRes.sfc — snes9x 512×224
- PPU/HDMA/HiColor64PerTileRowPseudoHiRes/TEST/mandrill64PerTileRowHiRes.sfc — snes9x 512×224
- PPU/Interlace/InterlaceFont/InterlaceFont.sfc — snes9x 512×448
- PPU/Interlace/InterlaceMoogle/InterlaceMoogle.sfc — snes9x 512×448
- PPU/Interlace/InterlaceMystHDMA/InterlaceMystHDMA.sfc — snes9x 512×448
- PPU/Interlace/InterlaceRPG/InterlaceRPG.sfc — snes9x 512×448
- PPU/Interlace/InterlaceScroll/InterlaceScroll.sfc — snes9x 512×448
- PPU/Interlace/InterlaceSimpsonsHDMA/InterlaceSimpsonsHDMA.sfc — snes9x 512×448
- PPU/Mosaic/Mode5/MosaicMode5.sfc — snes9x 512×448

## Come rigenerare

```sh
mvn -q package -DskipTests
OUT=<cartella-di-lavoro>
python3 tools/diff/suite.py --frames 300 --out $OUT --jobs 4   # ~2.5 minuti, scrive $OUT/results.json e le strisce *_cmp.png
```

Limite noto: tutte le ROM sono eseguite senza input a 300 frame; le demo animate (Wave, Perspective, Interlace…) dipendono anche dalla fase dell'animazione, quindi per queste il confronto andrebbe rifatto a più frame o con `--every`.
