# roadmap.md — cosa manca per un emulatore SNES completo

Stato di partenza: 65816, DMA/HDMA, PPU modi 0–4 e 7, SPC700+DSP, save state, frontend minimo.
Confronto con snes9x su 164 ROM di test: 90 identiche, 13 hi-res/interlacciate da fare, 66 con chip non supportati
(vedi [diff_results.md](diff_results.md)). Priorità: **P1** alta resa/bassa fatica, **P2** media, **P3** lunga o rara.

## 1. Accuratezza del nucleo

| # | Voce | Pri | Note |
|---|------|-----|------|
| 1.1 | Open bus completo | P1 | oggi solo `$4210-$4212`; estendere ai registri PPU/APU non letti e alle aree non mappate (usare `Bus.openBus`) |
| 1.2 | Residui `CPUTest/CPU/MSC` (0.56%) e `Mode7/StarWars` (1.0%) | P1 | usare `-Dctrace` e `refrun.py --wram` per trovare la prima divergenza |
| 1.3 | Timing a livello di ciclo (HV-IRQ, DMA stall, refresh DRAM, WAI/STP) | P2 | oggi scanline-level; serve per giochi sensibili al timing e per HDMA a metà riga |
| 1.4 | Velocità di accesso memoria (SlowROM/FastROM, `$420D`, cicli 6/8/12) | P2 | oggi ciclo CPU fisso |
| 1.5 | Moltiplicazione/divisione hardware con latenze reali | P3 | `$4202-$4206`: risultato immediato oggi |
| 1.6 | Confronto frame-per-frame di giochi reali con input scriptati | P2 | estendere `tools/diff/` a una suite di ROM commerciali locali |

## 2. PPU

| # | Voce | Pri | Note |
|---|------|-----|------|
| 2.1 | Modi 5 e 6 + hi-res 512 px | P1 | 13 ROM di test li usano; l'uscita diventa 512×224/448, finestra e filtro vanno adattati |
| 2.2 | Pseudo hi-res (SETINI bit 3) | P1 | 4 ROM HiColor64PerTileRowPseudoHiRes |
| 2.3 | Interlacciato (SETINI bit 0/1, OBJ interlace, 448 righe) | P1 | 6 ROM Interlace |
| 2.4 | Offset-per-tile (modi 2, 4, 6) verificato | P2 | già in modo 2/4, manca confronto con riferimento |
| 2.5 | Mosaico completo (anche in modo 5/6 e con Mode 7) | P2 | |
| 2.6 | EXTBG Mode 7, Mode 7 con finestre e color math | P2 | |
| 2.7 | Sprite: limiti 32 per riga / 34 tile, range-over, priorità OAM rotazione | P2 | |
| 2.8 | Color math: casi limite (sub-screen, half, fixed color con finestre) | P2 | |
| 2.9 | Overscan 239 righe (SETINI bit 2) | P3 | |
| 2.10 | Rendering per pixel (non per riga) per effetti a metà riga | P3 | HDMA/IRQ a metà scanline |

## 3. Audio

| # | Voce | Pri | Note |
|---|------|-----|------|
| 3.1 | Confronto delle forme d'onda con snes9x | P1 | `refrun.py` salva già statistiche audio; servono dump WAV e misura (correlazione/RMS per finestra) |
| 3.2 | DSP: eco/FIR, noise, pitch modulation, BRR edge case | P2 | verificare con le ROM `SPC700/*` |
| 3.3 | Timer e porte SPC700 cycle-accurate, IPL boot | P2 | |
| 3.4 | Resampling di qualità (interpolazione gaussiana originale → 48 kHz) | P3 | |
| 3.5 | Sincronizzazione audio/video (buffer adattivo, niente crepitii) | P2 | |

## 4. Coprocessori e periferiche

Ordine consigliato (vedi anche [docs/special-chip-games.md](special-chip-games.md)):

| # | Chip | Pri | Giochi tipici | Note |
|---|------|-----|---------------|------|
| 4.1 | DSP-1 (HLE) | P1 | Super Mario Kart, Pilotwings | molti racing |
| 4.2 | S-DD1, OBC1 | P2 | SF Alpha 2, Metal Combat | decompressore / OAM — fatti (S-DD1 verificato su SFA2 e Star Ocean) |
| 4.3 | Cx4 | P2 | Mega Man X2/X3 | HLE — fatto (da provare in gioco) |
| 4.4 | SuperFX (GSU-1/2) | ✅ (parziale) | Star Fox, Yoshi's Island, Stunt Race FX… | Implementato; 58 ROM di test e Yoshi identici a snes9x. Aperti: Doom (texture corrotte), timing preciso, arbitraggio ROM/RAM |
| 4.5 | SA-1 | P2 | Super Mario RPG, Kirby Super Star | secondo 65816 — fatto (SMRPG, Kirby SS giocabili) |
| 4.6 | SPC7110, ST010/011/018, S-RTC | P3 | titoli giapponesi | |
| 4.7 | MSU-1 | P3 | homebrew; 8 ROM di test, servono i file `.msu/.pcm` | |
| 4.8 | Multitap (giocatori 3–4), mouse, Super Scope | P2 | `controllers.cfg` accetta già `player3/4`; oggi solo porte 1 e 2 |
| 4.9 | Super Game Boy, BS-X | P3 | richiede un core Game Boy: fuori ambito iniziale |

## 5. Cartuccia e salvataggi

| # | Voce | Pri | Note |
|---|------|-----|------|
| 5.1 | Mapper: ExHiROM, SuperFX/SA-1 maps, copier header ovunque | P2 | |
| 5.2 | SRAM in `~/.config/superjavendo/` invece che accanto alla ROM | P2 | oggi `<rom>.srm` accanto al file |
| 5.3 | Rilevamento chip dall'header (`$FFD6`) con messaggio chiaro | P1 | stampare il coprocessore richiesto all'avvio |
| 5.4 | Database ROM (CRC32 → titolo, mapper, chip) | P3 | |
| 5.5 | Rewind e save state compressi, slot con anteprima nel frontend | P3 | |
| 5.6 | Cheat (Game Genie / Pro Action Replay) | P3 | |

## 6. Frontend e UX

| # | Voce | Pri | Note |
|---|------|-----|------|
| 6.1 | Fullscreen (F11) e filtro scelto salvato in config | P1 | già offerto, non fatto |
| 6.2 | Frontend: grafica "SNES visto dall'alto" | P3 | un primo tentativo è stato scartato: ripartire dai mockup dell'utente |
| 6.3 | Frontend: scansione ricorsiva con cartelle salvate, copertine | P2 | |
| 6.4 | Configuratore controller: rilevamento profilo da nome joystick, test live | P2 | verificare mapping EasySMX con `jstest` |
| 6.5 | Shader (CRT, scanline, NTSC) | P3 | oggi solo il filtro sharp-bilinear |
| 6.6 | Hotkey configurabili (turbo, pausa, frame advance, screenshot) | P2 | |
| 6.7 | Menu opzioni (audio, video, regione PAL/NTSC) | P2 | PAL: 50 Hz e 312 righe oggi non gestiti |
| 6.8 | Pacchetto distribuibile (jar eseguibile + script, release) | P2 | compilare in una cartella separata per non rompere un'esecuzione in corso |

## 7. Qualità e strumenti

| # | Voce | Pri | Note |
|---|------|-----|------|
| 7.1 | Pulizia strumentazione di debug in `Main`/`Ppu`/`Bus` (`-Dtrace`, `-Dwatch`, `--dump`, `--layers`, campi pubblici) | P1 | tenere solo `-Dctrace` e `-Dwramdump`, documentati |
| 7.2 | CI: build + test + suite `tools/diff` su ROM di test (con soglia) | P2 | le ROM di test non sono nel repo (licenza) |
| 7.3 | Test per regressioni: ogni bug trovato dalla suite → un test unitario | P1 | già fatto per riga +1 e open bus |
| 7.4 | Test ROM extra: blargg (CPU/SPC/PPU), krom, undisbeliever | P2 | |
| 7.5 | Rapporto automatico `diff_results.md` rigenerato da `suite.py` | P2 | oggi scritto a mano; correggere anche `--skip` (i percorsi iniziano con `PeterLemon/`) |
| 7.6 | Profilazione e ottimizzazione (target 60 fps con margine su hardware modesto) | P3 | |
| 7.7 | Documentazione: aggiornare `CLAUDE.md` ad ogni voce chiusa | P1 | |

## Traguardi suggeriti

1. **Compatibilità base:** 1.1, 1.2, 2.1–2.3, 5.3, 6.1, 7.1 → suite di test senza differenze oltre ai chip.
2. **Giochi 3D:** 4.1 (DSP-1), poi 4.4 (SuperFX).
3. **Audio verificato:** 3.1–3.3, 3.5.
4. **Biblioteca completa:** 4.2, 4.3, 4.5, 5.1, 5.2, 4.8.
5. **Rifinitura:** accuratezza a ciclo (1.3, 1.4, 2.10), shader, frontend grafico, distribuzione.
