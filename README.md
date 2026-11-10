<p align="center">
  <img src="docs/splash.jpg" alt="Duke (Java) e Tux giocano a Street Fighter II su un Super Nintendo" width="720">
</p>

# SuperJavendo

Un emulatore del **Super Nintendo (SNES)** scritto in **Java**, senza librerie esterne: solo JDK, Swing e `javax.sound`.

## Perché

Questo progetto nasce per **ristudiare le architetture delle console del passato che hanno fatto la storia** e
reimplementarle da zero. Scegliere Java per un lavoro del genere, un tempo, sarebbe stato poco sensato: la
macchina virtuale, il garbage collector e le prestazioni non lasciavano margine per emulare in tempo reale un
hardware con più processori, due chip video e audio dedicati. Oggi, tra JIT moderni, hardware veloce e buoni
strumenti di sviluppo, è invece non solo possibile ma comodo: il codice resta leggibile, portabile e testabile.

Il SNES è il primo bersaglio. L'idea è di arrivare, un'architettura alla volta, a un piccolo "museo" di console
riprodotte con la stessa filosofia: capire come funzionava l'hardware, scrivere il modello più semplice che
lo riproduce fedelmente, validarlo con test automatici e ROM di prova.

## Stato attuale

Gira **Street Fighter II (USA)** con grafica, parallasse a scorrimento e audio.

| Componente | Stato |
|---|---|
| CPU 65816 | completa, validata con i test per-istruzione di *SingleStepTests* |
| Temporizzazione | scheduler a scanline (262 linee × 1364 clock master), VBlank, NMI e IRQ H/V, auto-lettura del joypad |
| DMA / HDMA | diretto e indiretto; HDMA con le particolarità dei giochi reali |
| PPU | modi 0–4 e 7, sprite, finestre, mosaico, color math (schermo principale e secondario) |
| Audio | SPC700 con IPL ROM reale, DSP (BRR, ADSR/GAIN, rumore, eco, interpolazione), uscita a 48 kHz |
| Cartucce | LoROM e HiROM, SRAM con batteria su file `.srm` |
| Input | tastiera e gamepad Linux (`/dev/input/js*`), profili configurabili, più giocatori sulle porte 1 e 2 |
| Save state | 4 slot da tastiera + slot rapido da gamepad, con screenshot incorporato |
| Frontend | lista giochi con scansione cartelle, anteprima, reset, configuratore grafico dei controller |

### Cosa manca

- **Chip speciali** (SuperFX, SA-1, DSP-n, Cx4, S-DD1, …): elenco e ordine consigliato in [`docs/special-chip-games.md`](docs/special-chip-games.md).
- Modi video ad alta risoluzione (5/6) e interlacciato.
- Multitap (oltre 2 giocatori) e periferiche particolari (mouse, Super Scope).
- Il livello di precisione è "a scanline" (stile snes9x), non a ciclo (stile bsnes): i giochi più esigenti sul timing possono divergere.

## Requisiti

- JDK 21 o superiore (sviluppato con JDK 27)
- Maven 3.9+
- Linux (il supporto gamepad usa l'API joystick del kernel; il resto è Java puro)

Serve una **ROM di tua proprietà**: nessuna ROM commerciale è inclusa né verrà mai inclusa.

## Compilare ed eseguire

```sh
mvn package                 # compila ed esegue i test → target/superjavendo.jar

java -jar target/superjavendo.jar                       # apre la lista giochi
java -jar target/superjavendo.jar gioco.sfc             # avvia direttamente una ROM
java -jar target/superjavendo.jar gioco.sfc -p1 easysmx -p2 keyboard2   # profili controller per giocatore
java -jar target/superjavendo.jar --controllers         # solo il configuratore dei controller
```

### Comandi nel gioco

| Tasto | Azione |
|---|---|
| frecce, Z, X, A, S, Q, W, Invio, Shift | D-pad, B, A, Y, X, L, R, Start, Select (profilo `keyboard`, modificabile) |
| `1`…`4` / Shift+`1`…`4` | salva / carica lo slot 1…4 |
| F5 / F9, oppure i tasti `save_state`/`load_state` del pad | salva / carica lo slot rapido (0) |
| `P` | pausa |
| `Esc` | esci |

## Frontend

- **Lista giochi**: «Scansiona cartella…» cerca ROM in modo ricorsivo e le registra in `~/.config/superjavendo/game.list`.
  Selezionando un gioco appare lo screenshot dell'ultimo salvataggio oppure la copertina
  (`~/.config/superjavendo/game-covers/<nome rom>.png`; le copertine hanno copyright e non sono nel repository).
- **START / RESET** avviano e riavviano il gioco, che gira in una finestra separata.
- **Configuratore controller**: immagine di un pad SNES, si clicca un pulsante, si vedono i tasti associati e si
  aggiunge o rimuove un'assegnazione premendo il tasto (o il pulsante del joystick).

## Configurazione

Tutto sta in `~/.config/superjavendo/` (o `$XDG_CONFIG_HOME/superjavendo/`):

```
controllers.cfg          # quali profili usa ogni giocatore: player1=keyboard,easysmx@0
joystick/<nome>.cfg      # un profilo per dispositivo (creati al primo avvio: keyboard, keyboard2, easysmx, xbox)
states/<rom>.state       # slot 0;  <rom>.N.state per gli slot 1…4
game.list                # giochi trovati dalla scansione
game-covers/             # copertine opzionali
```

Un profilo è un file di testo, un pulsante per riga:

```
type=gamepad
name=EasySMX
up=AXIS_7-,AXIS_1-       # più input sullo stesso pulsante, separati da virgola
b=BUTTON_0
l=BUTTON_6,BUTTON_8      # L1 e L2 insieme
save_state=BUTTON_13
```

Tastiera: nomi Linux (`KEY_UP`, `KEY_Z`, …). Joystick: `BUTTON_n`, `AXIS_n+`, `AXIS_n-` (numeri di `jstest /dev/input/js0`).

## Architettura

Il codice è in `src/main/java/dev/superjavendo/`, un package per componente hardware:

```
cpu/        CPU 65816
bus/        bus indirizzi, mappa memoria, registri I/O
cart/       header, LoROM/HiROM, SRAM
timing/     costanti di temporizzazione
dma/        DMA e HDMA
ppu/        PPU: renderer a scanline
apu/        SPC700, DSP e porte di comunicazione
input/      joypad, strategie di controller (tastiera, gamepad), profili
state/      serializzazione dei save state
config/     percorsi dell'applicazione
ui/         finestra di emulazione e uscita audio
frontend/   lista giochi e configuratore controller
Snes.java   collega i componenti ed esegue lo scheduler
```

Scelte di progetto: i controller sono *strategy* intercambiabili (`ControllerInput`), i profili sono dati, lo stato
dei componenti è serializzabile (`Stateful`) così il save state è deterministico.

## Test e confronto con altri emulatori

- `mvn test`: test unitari su CPU, PPU, APU, scheduler, save state, input e frontend.
- [`test_roms/`](test_roms/README.md): ROM homebrew di test (PeterLemon/SNES) per verifiche deterministiche, uso locale.
- [`diff.md`](diff.md): procedura per confrontare frame per frame SuperJavendo con snes9x (core libretro)
  usando gli stessi input; strumenti in `tools/diff/`.

## Note legali

SuperJavendo è un progetto educativo. Non contiene né distribuisce ROM, BIOS o copertine protette da copyright.
*Super Nintendo* e *SNES* sono marchi di Nintendo; questo progetto non è affiliato né approvato da Nintendo.
