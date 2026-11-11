# diff.md — confronto di giocabilità: superjavendo vs snes9x

Runbook per Claude (e per te): fa girare la stessa ROM con **gli stessi input, agli stessi frame** su
superjavendo e su snes9x, salva screenshot e li confronta.

Come riferimento si usa il **core libretro di snes9x** (`/usr/lib/libretro/snes9x_libretro.so`, è lo stesso
snes9x che hai installato) pilotato da un runner headless in Python: nessuna finestra, nessuna iniezione di
input sul desktop, risultati riproducibili (~600 fps).

## Strumenti (`tools/diff/`)

> Suite automatica su tutte le ROM di `test_roms/`: `suite.py` (vedi [diff_results.md](diff_results.md) per l'ultimo risultato).

| File | Scopo |
|------|-------|
| `refrun.py` | runner headless del core libretro (snes9x). Stessa CLI di `--headless` di superjavendo |
| `suite.py` | esegue tutte le ROM di test su entrambi gli emulatori, produce `results.json` e le strisce di confronto |
| `compare.py` | confronta due PNG → stampa % pixel diversi e scrive una striscia `[nostro \| snes9x \| diff]` |
| `pngio.py` | lettura/scrittura PNG con solo numpy+zlib (niente Pillow) |

Dipendenze: `python3` + `numpy`, `java` 21+, jar compilato (`mvn -q package -DskipTests` → `target/superjavendo.jar`).

## Convenzioni (importanti per confronti sensati)

- I frame sono contati **dall'accensione**; lo screenshot `--every K` / `--shot` è preso *dopo* l'N-esimo frame.
- Input: `--press btn:da:a` = bottone premuto per i frame `da <= f < a`. Bottoni: `up down left right a b x y l r start select`.
  Porta 2 solo su `refrun.py` con `--press2` (superjavendo: configurare il profilo).
- Stessi argomenti `--press` su entrambi, **copia-incolla la stessa riga**.
- Risoluzione: 256x224 su entrambi. Il core libretro usa RGB565 (arrotondamenti): `compare.py` tollera delta ≤ 8 per canale.
- Cartelle di lavoro: usare lo scratchpad, non `/tmp`.

## Procedura

```sh
cd /home/sanjeya/Projects/superjavendo
ROM="$HOME/Emu/snes/Street Fighter II (USA).sfc"
OUT=<scratchpad>/diff && mkdir -p $OUT
# sequenza input di riferimento per SF2: menu → start → scelta personaggio → combattimento
P="--press start:700:710 --press start:1000:1010 --press a:1150:1160 --press a:1300:1310 --press a:1450:1460 --press a:1600:1610"

# 1. nostro emulatore (screenshot ogni 250 frame, fino a 1750)
java -jar target/superjavendo.jar "$ROM" --headless 1750 --every 250 $OUT/our $P

# 2. snes9x di riferimento (stessi frame e input)
python3 tools/diff/refrun.py "$ROM" --frames 1750 --every 250 $OUT/ref $P

# 3. confronto per ogni coppia (nomi: <prefisso>_<frame a 5 cifre>.png — verificare i nomi reali con ls)
for f in $OUT/our_*.png; do n=${f##*_}; python3 tools/diff/compare.py $f $OUT/ref_$n $OUT/cmp_$n; done
```

Poi **guardare le strisce `cmp_*.png` con Read** (immagine): a sinistra il nostro, al centro snes9x, a destra
la diff (rosso = pixel diversi, resto = nostro frame scurito).

Prova di sanità: `refrun.py` stampa `core ok: 256x224 @ 60.0988 fps ...` e, con `--audio-stats`, picco/RMS audio
(utile per confrontare ordini di grandezza con il nostro `--wav`).

## Come leggere i risultati

1. **Prima di tutto, sincronia**: se le scene sono diverse (personaggi/stage diversi) non è un bug grafico.
   Prova svolta (frame 1750 di SF2): lo stesso input ha portato a **stage e avversario diversi** (Ken vs Blanka). Il
   gioco sceglie in base a contatori/RNG legati al timing esatto (frame di arrivo ai menu, lettura joypad), quindi
   una piccola differenza di timing diverge subito. Per confronti pixel-per-pixel usare **scene deterministiche**:
   schermata del titolo, attract mode, demo, ROM di test (PeterLemon) o confrontare prima dell'ingresso in combattimento.
2. Differenze localizzate (rosso in poche zone): sprite, finestre, color math, mosaico → bug PPU.
3. Differenze a righe orizzontali: HDMA/scroll per scanline → bug DMA/timing.
4. Differenze costanti di colore su tutta l'immagine: gamma/brightness, color math, CGRAM.
5. Differenze solo nelle righe 0-21 e 215-223: normale in SF2 (letterbox con forceblank, vedi sotto).

Riga in `compare.py`: `differing rows: a..b` aiuta a localizzare la banda.

## Report da produrre

Per ogni confronto, riportare in italiano:

```
Gioco / scena: <nome>, frame <N>, input <riga --press>
Esito: ✅ identico | ⚠️ differenze minori | ❌ differenze gravi | ⏱️ non sincronizzati
% pixel diversi: <x>   righe: <a..b>
Osservazioni: <cosa si vede, ipotesi sul componente (PPU/DMA/CPU/APU)>
Screenshot: <percorso cmp_*.png>
```

## Differenze note / attese

- SF2 manda in forceblank le righe fuori 22–215 (letterbox) tramite IRQ: lo emuliamo, snes9x mostra le stesse bande nere.
- Audio: il core libretro esce a 32040 Hz, noi a 48000 Hz (resample dal DSP a 32 kHz). Confrontare solo livelli/durata, non campioni.
- Timing: superjavendo è a livello scanline (non per ciclo): differenze di pochi cicli possono cambiare RNG e flusso dei giochi.

## Confronto "manuale" con snes9x-gtk (opzionale)

`snes9x-gtk` non ha CLI utile (apre sempre la GUI) e non c'è xdotool: per giocarci a mano apri la ROM dalla GUI e usa
gli screenshot di snes9x (tasto configurato in Opzioni → Tasti) o `scrot -u`/`import -window` sulla sua finestra.
Per Claude conviene sempre `refrun.py`, che è scriptabile e deterministico.

## Idee future

- Salvare/caricare **save state** equivalenti (formati diversi: non intercambiabili) per partire dallo stesso punto in combattimento.
- Confronto automatico su una suite di ROM di test (CPU/PPU) con soglia di % pixel diversi e fallimento in CI.
- Registrare frame hash per frame (`--every 1`) e trovare il primo frame in cui divergono.
