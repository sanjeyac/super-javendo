# chips-plan.md — feature da implementare per il massimo supporto dei coprocessori

Complemento di [roadmap.md](roadmap.md) §4 e di [special-chip-games.md](special-chip-games.md) (elenco giochi).
Oggi `Cartridge` conosce solo LoROM/HiROM + SRAM: nessun chip. Test ROM disponibili in `test_roms/PeterLemon/`:
**GSU** (58 ROM: `CHIP/GSU/{2BPP,4BPP,8BPP,GSUTest}`) e **MSU-1** (8 ROM). Per gli altri chip servono giochi o ROM homebrew.

## A. Infrastruttura comune (da fare per prima)

| # | Feature | Dettagli |
|---|---------|----------|
| A1 | Rilevamento chip dall'header | campo "ROM type" (`$FFD6`/`$7FD6`) + byte `$FFBF` per ST01x; enum `Chip` in `Cartridge`; stampa all'avvio ("Chip: SuperFX GSU-2") |
| A2 | Interfaccia `Coprocessor` | `read(bank,off)`, `write(bank,off,v)`, `tick(masterClocks)`, `reset()`, `Stateful` (save state), `claims(bank,off)` per i range di registri |
| A3 | Mappa indirizzi per cartuccia | oggi hard-coded LoROM/HiROM: serve un livello che delega al chip (registri, RAM del chip, ROM remappata) |
| A4 | Scheduler | `Snes` esegue `cart.coprocessor.tick()` insieme a CPU/APU; chip con clock proprio (GSU 10/21 MHz, SA-1 10.74 MHz) = accumulatore di clock master |
| A5 | Interrupt dei chip | linea IRQ aggiuntiva verso la CPU principale (GSU, SA-1, SPC7110 non ne ha) |
| A6 | Firmware esterno | ricerca `dsp1.rom`, `st010.rom`, `cx4.rom` in `~/.config/superjavendo/firmware/`; se manca → fallback HLE o messaggio chiaro |
| A7 | SRAM/RTC del chip | dimensione dall'header, persistenza `.srm`, orologio S-RTC/SPC7110 salvato su file |
| A8 | Save state | ogni chip implementa `Stateful` e porta `STATE_VERSION` a +1 |
| A9 | Test | un test unitario per istruzione/funzione del chip + ROM di test; regressioni nella suite `tools/diff` |
| A10 | Diagnostica | `-Dctrace` esteso al chip (istruzioni GSU/SA-1), dump registri, confronto con snes9x via `refrun.py` |

## B. Per chip, in ordine di resa

### B1. DSP-1/1A/1B (HLE) — Super Mario Kart, Pilotwings, Ace wo Nerae
**Stato: implementato** (`cart/Dsp1.java`): tutti i comandi, identici a snes9x e bsnes su vettori casuali (test `ChipRomTest`, 20 ROM). Non modellato Memory Dump; mapping solo LoROM ≤1 MB (DR `$30-3F:8000`, SR `$C000`); non provato con giochi.
- Registri a `$6000-$7FFF` (LoROM piccola) o `$30-$3F:8000-FFFF` (LoROM grande): DR (dati) e SR (stato) a 8/16 bit
- Macchina a comandi con tabelle: Multiply, Inverse, Triangle (sin/cos), Radius, Range, Distance, Rotate, Polar, Project/Parameter/Raster (modo 7 "Mario Kart"), Attitude, Objective, Subjective, Scalar, Gyrate, Target, Memory test/dump
- Implementazione HLE (senza firmware); tabelle `DSP1ROM` per sin/cos/inverse
- Varianti DSP-2 (Dungeon Master), DSP-3 (SD Gundam GX), DSP-4 (Top Gear 3000): comandi diversi, stesso involucro
- Test: giochi (nessuna ROM di test nel repo)

### B2. SuperFX (GSU-1/GSU-2) — Star Fox, Stunt Race FX, Yoshi's Island, Doom
**Stato: implementato** (`cart/SuperFx.java`). Verificato: 58 ROM di test + Yoshi's Island identici a snes9x (RAM del chip); Star Fox/Star Fox 2/Stunt Race FX/Dirt Trax FX/Vortex giocabili. Aperto: Doom (texture corrotte), timing/arbitraggio.
- **Core RISC**: 16 registri a 16 bit R0–R15 (R15 = PC, R14 = ROM address, R13 = loop), SFR (flag Z/CY/S/OV, G, IRQ, ALT1/2, B), PBR/ROMBR/RAMBR, CBR, SCBR, SCMR, VCR, COLR, POR, CFGR, CLSR, BRAMR
- **Istruzioni** (≈ 100): ALU a registro, prefissi ALT1/ALT2/ALT3 e TO/FROM/WITH, MULT/UMULT/FMULT/LMULT, LOOP, branch con delay slot, LINK, JMP/LJMP, LDx/STx/LMS/SMS/LMS, GETB/GETBH/GETBL/GETBS, MERGE, SWAP, SEX, LOB/HIB, DIV2, CMODE, COLOR/GETC, PLOT/RPIX, STOP, CACHE
- **Cache istruzioni** 512 byte (CBR) e finestre ROM/RAM
- **Plot engine**: bitmap in RAM di cartuccia con 2/4/8 bpp, SCMR (altezza 128/160/192/OBJ), dithering, transparency, pixel cache a 8 pixel
- **Accesso alla memoria**: arbitraggio ROM/RAM tra GSU e CPU (RON/RAN), velocità GSU-1 10.7 MHz / GSU-2 21.4 MHz, cicli di accesso ROM/RAM
- Interrupt IRQ verso la CPU a STOP; registri a `$3000-$32FF`
- Mapper SuperFX (LoROM speciale, RAM 32–64 KB/128 KB, ROM fino a 2 MB)
- Test: **58 ROM** `PeterLemon/CHIP/GSU` (grafica 2/4/8 bpp e GSUTest) + Star Fox
- Difficoltà alta; la resa è la più alta (giochi 3D + 58 ROM di test)

### B3. S-DD1 — Street Fighter Alpha 2, Star Ocean
- Decompressore aritmetico/Golomb in hardware: registri `$4800-$4807`, banchi ROM remappabili `$4804-$4807` (MMC), integrazione con il DMA ("DMA intercept": il chip decomprime durante il trasferimento DMA dai banchi $C0+)
- Difficoltà bassa/media (algoritmo noto, ≈ 300 righe)

### B4. OBC1 — Metal Combat: Falcon's Revenge
**Stato: implementato** (`cart/Obc1.java`, `Obc1Test`) dalla specifica documentata; snes9x non lo emula, quindi nessun confronto con l'oracolo.
- Registri `$7FF0-$7FF6` e finestra RAM a `$6000-$7FFF` che rimappa l'OAM in formato interleaved; nessun clock proprio
- Difficoltà molto bassa

### B5. Cx4 (HLE) — Mega Man X2, X3
- Registri/RAM a `$6000-$7FFF`, comandi a `$7F4F`: wireframe (rotazione e proiezione), trigonometria, sprite scale/rotate, bitmap→tile, calcolo distanza, esecuzione via HLE o emulazione del core Hitachi HG51B con firmware
- Difficoltà media

### B6. SA-1 — Super Mario RPG, Kirby Super Star, Kirby's Dream Land 3
- **Secondo core 65816** a 10.74 MHz con proprio reset/IRQ/NMI/stato di wait; riuso di `Cpu65816` con un bus separato
- Registri `$2200-$23FF`: controllo CPU (CCNT), vettori, SIE/SIC/CIC, mappa banchi ROM (CXB/DXB/EXB/FXB), BMAPS/BMAP, protezione scrittura, **DMA normale e DMA CC1/CC2 (bitmap→tile)**, **aritmetica hardware** (mul/div/cumulative sum), **variable-length bit processing (VBD)**, registri H/V, RAM I (2 KB) e BW-RAM (fino a 2 MB) con mappatura bitmap a 2/4 bpp
- Arbitraggio del bus (BW-RAM/ROM contesa), IRQ verso la CPU principale e viceversa, stato di sincronizzazione tra i due core
- Difficoltà molto alta (più grande di tutti); richiede timing coerente e test di regressione estesi

### B7. SPC7110 — Tengai Makyou Zero, Momotarou Dentetsu Happy, Super Power League 4
- Decompressore (Marcov/LZ-like con contesto), "data port" (`$4800-$4842`), banchi ROM `$4830-$4834`, aritmetica (mul/div con segno), orologio RTC-4513 opzionale
- Difficoltà media, pochi giochi (3 titoli giapponesi)

### B8. ST010 / ST011 / ST018 (Seta) — F1 ROC II, Morita Shougi 1/2
- ST010 (NEC µPD96050, 6 KB RAM, trigonometria/mappe 3D), ST011/ST018 (core ARM6 per l'IA dello shogi); servono ROM firmware o HLE parziale
- Difficoltà alta, resa molto bassa: ultimo

### B9. S-RTC e altri
- **S-RTC** (Daikaijuu Monogatari II): registri `$2800/$2801`, orologio tempo reale con modalità lettura/scrittura (usa l'ora di sistema)
- **Super Game Boy**: core Game Boy completo (CPU LR35902 + PPU + APU) → progetto a sé
- **BS-X / Satellaview**: hardware di broadcast, fuori ambito

### B10. MSU-1 (homebrew) — 8 ROM di test `PeterLemon/MSU`
**Stato: implementato** (`cart/Msu1.java`, `Msu1Test` con file generati): registri, dati in streaming, PCM con loop mixato nel DSP. Mancano i file `.msu/.pcm` reali per provare le ROM di PeterLemon.
- Registri `$2000-$2007`: stato, indirizzo dati a 32 bit, traccia audio, volume, controllo (play/repeat)
- File companion `<rom>.msu` (dati), `<rom>-N.pcm` (audio PCM 44.1 kHz stereo con header e punto di loop); lettura dati in streaming e mix dell'audio PCM nell'uscita del DSP (48 kHz)
- Difficoltà bassa (nessun core): serve però il mixer audio e il caricamento di file accanto alla ROM
- Per i test occorrono i file `.msu/.pcm` (non presenti nel repo)

## C. Ordine proposto

1. **A1–A9** (infrastruttura) + **B4 OBC1** come primo chip di prova per validare l'interfaccia (minimo codice)
2. **B1 DSP-1** (racing) e **B3 S-DD1**
3. **B2 SuperFX** (grande impatto: giochi 3D e 58 ROM di test)
4. **B5 Cx4**, **B10 MSU-1** (se si vuole il lato homebrew)
5. **B6 SA-1** (il più grande)
6. **B7 SPC7110**, **B9 S-RTC**, poi **B8** Seta; Super Game Boy e BS-X solo se il progetto cresce

## D. Criteri di completamento per ogni chip

- Header riconosciuto e messaggio all'avvio
- Registri e memoria mappati correttamente per i mapper previsti
- Save state e SRAM/RTC persistente
- Almeno un gioco o ROM di test identico a snes9x a un frame scelto (`tools/diff/suite.py`)
- Test unitari sulle funzioni principali
- `CLAUDE.md`, `roadmap.md` e `diff_results.md` aggiornati
