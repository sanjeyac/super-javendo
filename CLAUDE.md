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
- `frontend2/`: frontend v2 (definitivo, accanto alla v1 che resta intatta): `Shell` (finestra a tab), `LibraryScreen`/`GridView`/`ListView`/`DetailPanel`, `LibraryModel` (logica senza UI), `UserData` (`library-user.tsv`), `MetaDb` (`games.tsv` per CRC32), `Pal`/`Ui`/`Widgets` (disegno custom). Piano e milestone: `docs/frontend-v2-plan.md`. Senza ROM parte la v2 (default); `--ui-legacy` avvia la v1; screenshot headless `--ui2-shot out.png W H tab [list] [query]`. M1 (Libreria) fatta; ROM Manager/Controller/Impostazioni sono segnaposto.
- `config/AppPaths`: tutto sotto `~/.config/superjavendo/` (o `$XDG_CONFIG_HOME`).
- Profili di default: `src/main/resources/profiles/*.cfg` (copiati in config solo se mancano; **mai sovrascrivere i file dell'utente**).

## Verità hardware e trappole già incontrate

- GSU: i **branch (`BRA`/`Bxx`) non azzerano i prefissi** ALT1/ALT2/B/sreg/dreg: lo slot di ritardo li vede (Yoshi's Island usa `ALT2` + `BRA` + `AND #15`). Lo slot di ritardo prende l'operando dal bersaglio del salto. ALT3 si decodifica come ALT1 per `IBT/LMS/SMS` e `IWT/LM/SM`.

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

## Cartucce di test per i chip (`tools/chiptests/`)

Assemblatore 65816 minimo in Python (`asm.py`) + generatori di ROM; `run.py ROM` esegue la ROM su di noi e su snes9x e confronta la WRAM `$7E:2000..`
(la ROM azzera prima l'area: snes9x riempie la WRAM di `$55`). `golden.py nome…` congela ROM + risultato di snes9x in `src/test/resources/chiptests/`,
usati da `ChipRomTest` (non richiede snes9x). Oracoli: snes9x emula **DSP-1, Cx4, SA-1 (header map mode `$23`), S-DD1, SuperFX**; **non** OBC1 né MSU-1 (senza file); il core
core bsnes non espone WRAM/SRAM via libretro ma la si estrae dallo stato serializzato (`run.py`, `golden.py`): secondo oracolo, per il DSP-1 più accurato di snes9x. `dsp1model.py` = prototipo Python dei comandi DSP-1 (confronto con l'oracolo).

## Test master (`tools/testmaster/`, `docs/test-master.md`)

ROM deterministiche che si autovalutano (PASS/FAIL a schermo + tabella in WRAM `$7E:2000`), una per chip; oggi `dsp1` (20 gruppi). `build.py dsp1 [--refresh]`, `run.py ROM` (matrice nostro|snes9x|bsnes). Dopo modifiche al chip: ricopiare la ROM in `src/test/resources/testmaster/` (`TestMasterTest`).

## Strumenti di confronto

`docs/diff.md` + `tools/diff/` (`refrun.py` esegue il core libretro di snes9x in modo headless con gli stessi `--press`;
`compare.py` produce striscia nostro|snes9x|diff). Attenzione: SF2 diverge per RNG/timing → per confronti pixel-per-pixel
usare ROM deterministiche (`test_roms/`, licenza non dichiarata: solo locale, non committare in repo pubblici).

## Documentazione (`docs/`)

`roadmap.md` (cosa manca), `chips-plan.md` (piano coprocessori), `special-chip-games.md`, `diff.md` (runbook confronto con snes9x), `diff_results.md` (ultimo risultato suite). `README.md` e `CLAUDE.md` restano nella radice (GitHub e Claude Code li cercano lì).

## Cose aperte

- Offset verticale di 1 riga: **corretto** (`y = riga + 1 + vofs` in `renderBg`/`renderMode7`, coperto da `PpuTest`). Suite di confronto: 90/164 identiche.
- Test PLP (`CPUTest/CPU/PHL`): **risolto** con l'open bus su `$4210-$4212` (`Bus.openBus`). Residui: `CPUMSC` 0.56%, `StarWars` 1.0%.
- Pulizia della strumentazione di debug in `Main` (`-Dtrace`, `-Dwatch`, `--dump`, `--layers`, campi pubblici di debug in `Ppu`/`Bus`) (il `.gitignore` esiste già).
- Chip: **SuperFX implementato** (`cart/SuperFx`, interfaccia `cart/Coprocessor`, rilevamento in `cart/Chip`): RAM del chip identica a snes9x su tutte le 58 ROM `CHIP/GSU` e su Yoshi's Island (frame 100); Star Fox, Star Fox 2, Stunt Race FX, Dirt Trax FX e Vortex girano e combaciano con snes9x a meno di timing/animazioni; **Doom** renderizza con texture corrotte (da indagare: istruzioni di moltiplicazione/DIV2, dithering, 8bpp); mancano timing preciso e arbitraggio ROM/RAM CPU↔GSU. Gli altri chip: `docs/chips-plan.md`. Strumenti: `-Dgtrace=FILE -Dgtrace.from/.to` (traccia GSU), `-Dcartram=FILE`, `refrun.py --sram FILE` (confronto RAM cartuccia).
- **DSP-1** (`cart/Dsp1`, HLE): **tutti i comandi implementati**, identici a snes9x *e* bsnes su vettori casuali (Multiply2, Distance e Gyrate: seguiamo bsnes, snes9x sbaglia). Non modellato: Memory Dump (tabella ROM). **Super Mario Kart gira** (HiROM: DR `$6000`, SR `$7000` banchi `$00-1F`). Il protocollo è la macchina a stati di bsnes (ogni accesso a DR conta, anche le scritture mentre ci sono risultati pendenti): `-Ddsp1log=FILE` registra gli accessi di un gioco, `tools/testmaster/dsp1_replay.py LOG --emu bsnes` li rigioca sull'oracolo (0 differenze su 296k accessi di Mario Kart; snes9x diverge sul flush del raster). Secondo oracolo: `tools/chiptests/run.py` confronta anche con il core bsnes (WRAM estratta da `retro_serialize`, vedi `bsnes_wram`); `golden.py` congela i valori di bsnes. Il sorgente di bsnes in `other_emu/` (gitignored, GPL) serve solo da lettura: **non copiarne il codice**.
- **OBC1** (`cart/Obc1`) e **MSU-1** (`cart/Msu1`, file `<rom>.msu` + `<rom>-N.pcm`, mix in `Apu`) implementati, solo con test unitari (nessun oracolo: snes9x non emula OBC1, MSU-1 richiede i file). **S-DD1** (`cart/Sdd1`; Street Fighter Alpha 2, Star Ocean): decompressione in streaming agganciata al DMA (si ascoltano `$43x2-6`, risponde alle letture di `$C0-$FF` all'indirizzo programmato); algoritmo di Andreas Naive (pubblico dominio), scritto in proprio. Verificato a schermo contro snes9x (titolo SFA2 e Star Ocean identici a meno dell'animazione); `-Dsdd1log=true` stampa le decompressioni. Manca un test master (servirebbe un encoder). **SA-1** (`cart/Sa1`; Super Mario RPG, Kirby Super Star): secondo `Cpu65816` con bus proprio (`vectorOverride` per i vettori nei registri), mapper ROM `$2220-3`, BW-RAM = `cart.sram`, I-RAM, DMA normale + conversione carattere 1/2, moltiplicatore/divisore/somma cumulativa, lettore di bit a lunghezza variabile, timer, IRQ/NMI in entrambe le direzioni. Super Mario RPG e Kirby arrivano al gioco e coincidono con snes9x a meno dell'animazione. Non modellati: wait state di arbitraggio ROM/BW-RAM (solo +1 ciclo per BW-RAM), HDMA/IRQ fini; nessun test master ancora (oracolo snes9x, header `$23`).
- **NMI**: la CPU prende l'NMI **un'istruzione dopo** il flag di VBlank (`Snes.nmiDelay`), così un loop che legge `$4210` con NMI attivo può ancora vedere il flag (SFA2 ne dipende). Suite di confronto: 122/164 identiche.
- **Cx4** (`cart/Cx4`, HLE; Mega Man X2/X3): tutti i comandi; l'intro di MMX2 gira. Test master `tools/testmaster/cx4_master.py` (21 gruppi, `build.py cx4`, ROM copiata in `src/test/resources/testmaster/`): 18/21 identici a snes9x; `TRIANGLE`, `IMMEDIATE`, `IMM QUIRKS` divergono da snes9x per scelta (snes9x scrive registri a 16 bit / non incrementa il puntatore; noi seguiamo la semantica di bsnes letta dal sorgente) e il loro atteso è congelato da noi (`own=` in `build.py`). **bsnes libretro non esegue la ROM del master** (schermo nero con header Cx4 `$FFBF=$10`, anche con `bsnes_coprocessor_prefer_hle=ON`, che `refrun.py` ora risponde): causa non trovata, MMX2 reale invece funziona sotto bsnes. Costanti `IMMEDIATE`/`SIN24` verificate solo contro il sorgente di bsnes, non misurate su un oracolo.
- Le ROM possono essere `.zip` (`cart/RomFile`): prende la prima ROM dentro; zip senza ROM → dialog nella libreria.
- Multitap (oggi solo porte 1 e 2; il formato `controllers.cfg` accetta già `player3/4`), modi 5/6, interlacciato.
- Frontend: la grafica "SNES visto dall'alto" è da fare (l'utente manderà dei mockup); scansione ricorsiva senza cartelle salvate; SRAM ancora accanto alla ROM (non in config).
- Mapping dell'EasySMX (`easysmx.cfg`) dedotto, da verificare con `jstest`.
