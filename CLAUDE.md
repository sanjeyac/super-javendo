# CLAUDE.md — guida per continuare lo sviluppo

Emulatore SNES in Java (`superjavendo`). Scopo del progetto e stato: vedi `README.md`.
L'utente scrive in italiano; rispondi in italiano. Codice e commenti in inglese.

## Comandi

```sh
mvn -q package                      # compila + test → target/superjavendo.jar
mvn -q package -DskipTests          # solo compilazione
java -jar target/superjavendo.jar ROM --headless N --shot out.png [--every K prefisso] [--press btn:da:a]... [--wav out.wav]
java -jar target/superjavendo.jar --state-shot file.state out.png
```
Bottoni di `--press`: `up down left right a b x y l r start select`; frame contati dall'accensione.
Per verificare la grafica senza toccare il desktop dell'utente: `--headless` + screenshot, oppure Xvfb
(`xvfb-run`) + `import -window root` per le finestre Swing. Non c'è xdotool.

## Mappa del codice (`src/main/java/dev/superjavendo/`)

- `Snes.java`: scheduler (262 linee × 1364 clock master, 6 clock/ciclo CPU, VBlank alla linea 225), save state (`STATE_VERSION`).
- `cpu/Cpu65816` · `bus/Bus,Memory` · `cart/Cartridge` · `dma/Dma` · `ppu/Ppu` · `apu/{Spc700,Dsp,Apu}`.
- `input/`: `ControllerInput` (strategy), `ControllerProfile` (dati immutabili), `ProfileParser`/`ProfileFile`/`ProfileStore`
  (formato `.cfg`), `ControllerConfig` (assegnazione profili → giocatori, `controllers.cfg`, override `-p1/-p2`).
- `ui/EmuWindow` (loop di emulazione, hotkey, reset), `ui/AudioOut`.
- `frontend/`: `LauncherWindow`, `ControllerConfigWindow`, `PadView`, `GameList`, `Previews`, `GameLauncher`.
- `config/AppPaths`: tutto sotto `~/.config/superjavendo/` (o `$XDG_CONFIG_HOME`).
- Profili di default: `src/main/resources/profiles/*.cfg` (copiati in config solo se mancano; **mai sovrascrivere i file dell'utente**).

## Verità hardware e trappole già incontrate

- `BGnHOFS/VOFS` hanno un latch di scrittura: tenere i registri a 16 bit, non mascherare a 10.
- HDMA: gira alla **fine** di ogni linea 0..224; i canali abilitati a metà frame partono dai `$43x8/$43xA` che il gioco ha già caricato
  (`hdmaInit` marca tutti i canali attivi, `doTransfer=false`). SF2 abilita HDMA a frame in corso.
- SF2: IRQ alle linee 22/215 per il letterbox (forceblank fuori 22–215); parallasse via HDMA ch1/ch2 da una tabella in WRAM 7F.
- Base dei caratteri BG: `<<13` (parole), non `<<14`.
- LoROM: la metà bassa dei banchi ≥ $40 rispecchia la ROM.
- I test CPU in modalità emulazione richiedono `S` normalizzato alla pagina 1.
- I bit bassi di `ControllerInput.poll()` (`SAVE_STATE`, `LOAD_STATE`) sono azioni dell'emulatore, non arrivano alla console.

## Convenzioni

- Java 21 come target, nessuna dipendenza runtime. Test con JUnit 5.
- **I test non devono dipendere dall'hardware** (es. un gamepad collegato): usare override `-pN`/profili tastiera e `@TempDir` per la config.
- Test sulla config usano sempre `AppPaths(tempDir)`, mai la home vera.
- Nuovi tipi di dispositivo: registrarli in `ControllerFactory` (strategy), non toccare `EmuWindow`.
- Nuovi componenti con stato → implementare `Stateful` e incrementare `STATE_VERSION` se cambia il formato.

## Strumenti di confronto

`diff.md` + `tools/diff/` (`refrun.py` esegue il core libretro di snes9x in modo headless con gli stessi `--press`;
`compare.py` produce striscia nostro|snes9x|diff). Attenzione: SF2 diverge per RNG/timing → per confronti pixel-per-pixel
usare ROM deterministiche (`test_roms/`, licenza non dichiarata: solo locale, non committare in repo pubblici).

## Cose aperte

- **Sospetto offset verticale di 1 riga** nei fondali BG/Mode 7 rispetto a snes9x (schermata "LICENSED BY NINTENDO": identica con il nostro frame spostato su di 1).
  Probabile fix: `y = riga + 1 + vofs` in `Ppu.renderBg`/`renderMode7`; aggiornare `PpuTest`, che assume la riga 0. Non applicato.
- Pulizia della strumentazione di debug in `Main` (`-Dtrace`, `-Dwatch`, `--dump`, `--layers`, campi pubblici di debug in `Ppu`/`Bus`) (il `.gitignore` esiste già).
- Chip speciali: ordine consigliato in `docs/special-chip-games.md` (S-DD1, OBC1, DSP-1, Cx4, SuperFX, …).
- Multitap (oggi solo porte 1 e 2; il formato `controllers.cfg` accetta già `player3/4`), modi 5/6, interlacciato.
- Frontend: la grafica "SNES visto dall'alto" è da fare (l'utente manderà dei mockup); scansione ricorsiva senza cartelle salvate; SRAM ancora accanto alla ROM (non in config).
- Mapping dell'EasySMX (`easysmx.cfg`) dedotto, da verificare con `jstest`.
