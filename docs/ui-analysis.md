# Report: stato attuale della UI di SuperJavendo

Analisi di sola lettura del codice (percorso base: `src/main/java/dev/superjavendo/`), pensata come input per la progettazione della nuova interfaccia.

## 1. Struttura UI attuale

**Classi**

| Classe | Ruolo |
|---|---|
| `frontend/legacy/LauncherWindow` (JFrame) | Libreria: titolo, `CoverGrid` in uno `JScrollPane`, bottoni START, RESET, Scansiona e Controller, riga di stato. |
| `frontend/legacy/CoverGrid` (JComponent + `Scrollable`) | Griglia di copertine con paint custom. |
| `frontend/legacy/ControllerConfigWindow` (JFrame) + `PadView` | Editor dei profili: disegno del pad, lista dei binding, cattura del prossimo input. |
| `frontend/legacy/GameList`, `GameEntry`, `Previews`, `GameLauncher`, `Theme` | Catalogo, record del gioco, copertine/anteprime, avvio ROM, palette. |
| `ui/EmuWindow` (JFrame) | Finestra di gioco con il loop di emulazione. |
| `ui/AudioOut` | Uscita audio. |

**Comunicazione con il core**
- `GameLauncher.open()` fa tre cose in sequenza: `Cartridge.load` → `new Snes(cart)` → `new EmuWindow(...)`.
- `LauncherWindow` tiene un solo riferimento `EmuWindow current`. L'unico canale verso il gioco è `current.requestReset()`.
- Non esiste un'interfaccia di controllo (`Session`/`Controller`): la UI parla direttamente con `EmuWindow`.

**Look&Feel**
- Non c'è nessun `UIManager`/`setLookAndFeel` nel progetto. Si usa il Metal di default, con colori forzati su ogni componente (`Theme`, `LauncherWindow.button()`).
- `Theme` ha una palette in stile SNES e due font `SANS_SERIF`.
- `JFileChooser`, `JOptionPane`, `JSpinner` e `JComboBox` restano con l'aspetto Metal, quindi stonano con il tema.

**Componenti custom**
- `CoverGrid` fa il paint delle sole celle nel clip. Celle di 210×164 px, `columns = width / CELL_W`.
- Le copertine si caricano su un thread singolo `cover-loader`, vengono scalate a metà passo per volta e finiscono in una cache LRU da 300 (`LinkedHashMap` con `accessOrder`).
- Senza copertina viene disegnato un rettangolo nero con il titolo a capo (`drawCentered`/`wrap`).
- Navigazione da tastiera con frecce, PgUp/PgDn, Home/End e Invio, tramite `InputMap`. La selezione è un rettangolo rosso da 3 px.
- `PadView` è un altro componente con paint custom.

**Problemi evidenti**
- **Thread EDT**
  - `GameLauncher.open()` è chiamato dall'EDT in `LauncherWindow.start()`: carica l'intera ROM (anche dentro lo zip) sull'EDT, quindi con ROM grandi la UI si blocca.
  - Il costruttore di `EmuWindow` fa partire il thread di emulazione e chiama `setVisible`.
  - `GameList.load()` è chiamato nel costruttore di `LauncherWindow`, sull'EDT. La scansione invece gira correttamente in un `SwingWorker`.
- **Logica nella UI**
  - Il parsing del nome (`titleOf`) è in `GameList` e la normalizzazione dei titoli in `Previews`.
  - `LauncherWindow` costruisce testi di dialogo, sceglie la cartella di default (`~/Emu/snes`) e gestisce il caso dello zip senza ROM.
  - `EmuWindow` mescola rendering, pacing, I/O dei save state, scorciatoie e audio in 223 righe.
- **Duplicazione**
  - `rom.getFileName().replaceFirst("\\.[^.]*$","")` compare in `AppPaths` (3 volte), `Previews`, `GameLauncher` e `GameList.titleOf`.
  - `AppPaths.forCurrentUser()` è richiamato in `LauncherWindow`, `ControllerConfigWindow`, `GameLauncher` ed `EmuWindow`, senza un'istanza condivisa.
  - La ricerca dello state più recente è in `Previews.of` e non riusabile altrove.
- **Altro**
  - `CoverGrid.setGames` fa `games.indexOf(keep)` in tempo lineare.
  - `Previews.of` (decodifica PNG di uno stato) non è chiamato dalla griglia, quindi oggi le anteprime dei save state non compaiono nella libreria.
  - Le stringhe sono in italiano nel frontend e in inglese in `EmuWindow`, senza i18n.

## 2. Modello dati dei giochi

**Scansione** (`GameList.scan`)
- `Files.walk` ricorsivo.
- Estensioni accettate: `sfc`, `smc`, `swc`, `fig` (`RomFile.EXTENSIONS`) più `.zip`.
- Uno zip senza ROM interna va in `rejectedZips()` e la UI avvisa l'utente.
- Le voci il cui file non esiste più vengono eliminate.
- Il risultato è ordinato per titolo, senza distinguere maiuscole e minuscole.

**Parsing del nome**
- Non esiste. `titleOf(Path)` toglie solo l'estensione. Regione, `[!]`, `[b1]` e versioni restano nel titolo (es. `7th Saga, The (U) [!]`).
- L'unico parsing di tag è `Previews.normalizeTitle`, usato solo per abbinare le copertine: toglie `(..)` e `[..]`, sposta `, The/A/An` davanti, trasforma `_` in `'` e `&` in `and`, e tiene solo `[a-z0-9]`.

**Classe del gioco**
```java
public record GameEntry(Path path, String title) {}
```
Due soli campi. `equals`/`hashCode` sono quelli del record, quindi `CoverGrid` li usa come chiave di cache: se cambia il titolo, la cache non lo riconosce più.

**Copertine**
- Il legame con la ROM passa dal nome del file, senza CRC.
- `Previews.findCover` cerca prima `<nome>.png|jpg|jpeg`; poi cerca un'unica copertina con lo stesso titolo normalizzato. Se i candidati sono più d'uno, restituisce null.
- Cartelle cercate (`AppPaths.coverDirsFor`), in ordine di priorità:
  1. `~/.config/superjavendo/game-covers/`
  2. `<cartella della ROM>/covers/`
  3. `<cartella padre>/covers/`
- La prima cartella con un match vince.

**CRC/hash/header**
- **CRC32 della ROM: no.** Non c'è `CRC32` in nessun punto di `src/main`.
- **Hash:** esiste solo `Snes.romId()`, cioè `Arrays.hashCode(rom)*31 + length`. Serve a evitare di caricare uno stato nel gioco sbagliato ed è privato.
- **Header interno: sì**, ma solo in `Cartridge` (a caricamento completo della ROM):
  - titolo di 21 byte ASCII;
  - mappatura LoROM/HiROM (euristica a punteggio);
  - tipo di chip (`Chip.fromRomType`);
  - dimensione SRAM;
  - checksum valido;
  - reset vector.
- Non vengono letti il byte della regione/destinazione (`$FFD9`), la versione (`$FFDB`) né la dimensione ROM. L'header non è accessibile senza costruire un `Cartridge` (che alloca ROM e SRAM e istanzia il coprocessore).

**Persistenza**

Tutto sta sotto `~/.config/superjavendo/` (o `$XDG_CONFIG_HOME`), tramite `config/AppPaths`.

| Percorso | Contenuto |
|---|---|
| `game.list` | Testo UTF-8: `# commento` e righe `<path><TAB><title>`. |
| `controllers.cfg` | `player1=profilo,profilo@N`, `player2=…`. |
| `joystick/<nome>.cfg` | Profili controller (formato `key=value`). |
| `game-covers/` | Copertine (opzionale). |
| `states/` | Save state. |

- La SRAM sta accanto alla ROM come `<nome>.srm`. Per i file dentro uno zip finisce quindi accanto allo zip.
- Non c'è un file di impostazioni generale: scala, filtro, cartella scansionata e dimensione/posizione della finestra non vengono ricordati. Il filtro video si resetta a ogni avvio e la cartella proposta nel dialogo di scansione è fissa (`~/Emu/snes`).

## 3. Save state e sessioni

**Formato** (`Snes.saveState` / `readInfo`)
- Stream gzip (`.state`) con un header: magic `SJVS`, versione (`STATE_VERSION=2`), `romId`, titolo, timestamp in millisecondi e screenshot come PNG incorporato. Seguono i dati del core (CPU, bus, coprocessore, MSU).
- Lo screenshot è un PNG 256×224 RGB ricavato da `ppu.frame`, senza correzione di aspect ratio.
- `Snes.readInfo(byte[])` legge solo l'header e non richiede una console. Con le versioni vecchie (v1) restituisce uno `StateInfo` senza screenshot.
- Non c'è un file separato per lo screenshot. `Previews.of` fa `Files.readAllBytes` dell'intero file, anche se serve solo l'header.

**Percorso e slot**
- `states/<nome ROM senza estensione>.state` per lo slot 0 e `…<N>.state` per gli slot 1..4 (`AppPaths.MAX_SLOT = 4`): 5 slot in totale.
- Lo slot 0 è quello "rapido" da joystick. I tasti 1-4 salvano e Shift+1-4 caricano.
- La scrittura è atomica (`.tmp` + `ATOMIC_MOVE`) ed è eseguita sul thread di emulazione tra due frame.
- `migrateLegacyState` sposta i vecchi `.state` accanto alla ROM nella cartella `states/`.
- La chiave del file è il **nome del file**, non il contenuto. Due ROM con lo stesso nome in cartelle diverse condividono gli stati (il caricamento fallisce solo se il `romId` è diverso).

**Ultima volta giocato / tempo di gioco**
- Non vengono registrati. L'unico dato temporale è `savedAtMillis` negli state, usato da `Previews.of` per la didascalia "Ultimo salvataggio".
- Punto più pulito per aggiungerli: `GameLauncher.open()` più un callback di chiusura della finestra. All'avvio si registra `lastPlayed`, alla chiusura si accumula il tempo.
- Per il tempo di gioco il contatore giusto è `Snes.frameCount` (60,0988 fps), che esclude pause e reset; lo si legge in `EmuWindow.shutdown()`. Misurare con l'orologio includerebbe le pause.
- `EmuWindow` oggi non espone un listener di chiusura: va aggiunto (vedi sezione 5).

## 4. Controller

**Lettura su Linux** (`input/GamepadInput`)
- API joystick `/dev/input/js<N>` con `FileInputStream` e record `js_event` da 8 byte. Niente evdev, niente librerie native.
- Un thread daemon per profilo gamepad.
- **Hotplug:** il thread controlla `dev.exists() && canRead()` ogni 2 secondi. Se la lettura va in `IOException` (scollegamento), azzera la maschera e riprova dopo 1 secondo. Non c'è inotify né un evento verso la UI. `name()` aggiunge "(not connected)" e il titolo di `EmuWindow` lo mostra ogni secondo.
- `GamepadCapture` (usato da `ControllerConfigWindow`) legge il prossimo evento con soglia fissa 16000.
- Il nome vero del dispositivo (ioctl `JSIOCGNAME`) non viene letto. Si identificano i pad solo come `js0..js15` (spinner `device`).

**Profili e assegnazioni**
- `ProfileParser` legge `key=value` con `#` come commento. Tipi: `keyboard` e `gamepad`.
- **Più input per lo stesso tasto:** separati da virgola oppure su righe ripetute (`bindings.merge`).
- **Assi con segno:** `AXIS_n+` / `AXIS_n-` diventano `AxisBinding(axis, direction, mask)`; si attiva se `direction * valore > threshold` (default 12000). Un hat D-pad (assi 6/7 su alcuni pad) si configura come asse (`up=AXIS_7-`).
- `ControllerProfile` è immutabile, con `withDevice(n)`.
- **Assegnazione ai giocatori** (`ControllerConfig`): `controllers.cfg` con `player1=keyboard,easysmx@0`. Profili multipli sullo stesso giocatore si sommano (`CompositeInput`). `@N` seleziona `/dev/input/jsN`. Il parser accetta `player3/4`, ma `configure` emula solo le porte 1 e 2 (`ControllerPorts` ha due campi fissi).
- Override da riga di comando: `-p1`, `-p2`.
- Le tastiere usano nomi Linux (`KEY_Z`, `KEY_ENTER`…) tradotti in key code AWT da `KeyNames`.

**Save/load da pad e tastiera**
- Dal profilo: `save_state=` e `load_state=` (slot 0). Sono bit bassi della maschera, estratti dal fronte di salita in `EmuWindow.loop` (`ports.actions()`).
- Da tastiera in gioco: `1`–`4` salvano, `Shift+1`–`4` caricano. `F5`/`F9` sono i default di slot 0 in `keyboard.cfg`. `F` cambia filtro, `P` pausa, `Esc` chiude.
- **Il launcher non è pilotabile da joystick**: il polling del pad esiste solo dentro `EmuWindow` (`ControllerPorts`). Per navigare la libreria col pad serve un lettore di input dedicato (può riusare la logica di `GamepadInput.read`).

## 5. Interfaccia del core verso la UI

| Funzione | Esiste? |
|---|---|
| Avvio ROM | Sì: `GameLauncher.open(rom, profiles, exitOnClose)` → `EmuWindow`. |
| Pausa / riprendi | Solo con il tasto `P` (campo `paused` privato, non esposto). |
| Reset | `EmuWindow.requestReset()` (pubblico). |
| Stop | `dispose()` della finestra (chiude audio, salva SRAM). Non c'è un'API `stop()`. |
| Salva/carica stato | Solo tramite tasti e pad (`stateRequest` è privato). |
| Screenshot | Solo in headless (`--shot`). Nessun metodo in finestra. |
| Avanti veloce | **No.** `FRAME_NANOS` è costante, non c'è un moltiplicatore. |

- Il core `Snes` espone `runFrame()`, `saveState()`, `loadState()` e `readInfo()` (statico). Non ha concetto di sessione.
- La finestra di gioco è **un JFrame separato**. Con `exitOnClose=false` usa `DISPOSE_ON_CLOSE`.
- **Notifica di chiusura:** non c'è. `LauncherWindow` controlla solo `current.isDisplayable()` prima di un reset o di un nuovo avvio. `EmuWindow` ha un `WindowAdapter` che chiama il privato `shutdown()`, senza callback verso il launcher.
- **Thread:** il thread `emulation` (daemon) tocca il campo volatile `snes` e il `BufferedImage` condiviso con l'EDT (copia in un `int[]`, paint su un altro thread). Funziona, ma non c'è sincronizzazione esplicita.

Per la nuova UI serve un'interfaccia di sessione minima, ad esempio `EmulatorSession` con `pause()/resume()/reset()/stop()/saveState(slot)/loadState(slot)/screenshot()/setSpeed(x)` e un listener `onClosed(SessionStats)`. Oggi tutto questo è incapsulato in `EmuWindow` e va estratto.

## 6. Vincoli per la nuova UI

**Java e build**
- Il pom ha `maven.compiler.release=21`. Il JDK installato è OpenJDK 27.
- Build con Maven: `mvn -q package` produce `target/superjavendo.jar` con `mainClass` nel manifest. Nessuna dipendenza runtime; l'unica dipendenza è JUnit 5.11.4 (scope test).
- **Risorse incluse:** solo i 4 profili in `src/main/resources/profiles/*.cfg`. Nessun font, icona o immagine; `Theme` usa `Font.SANS_SERIF`.
- I test del frontend sono in `FrontendTest` (geometria della griglia, copertine, scansione).

**Dove salvare i nuovi dati (metadati, preferiti, statistiche)**

Raccomandazione: **un solo file per i dati della libreria** (es. `library.tsv`, chiave = CRC32 della ROM) più **un file piccolo di impostazioni** (`settings.cfg`, `key=value`). Resta in linea con i formati attuali (testo, TAB/`key=value`, nessuna dipendenza).

| | Un file unico | Un file per gioco |
|---|---|---|
| Pro | Lettura/scrittura veloce all'avvio (1000+ giochi in una passata). Facile da salvare in backup e da editare in un foglio di calcolo. Scrittura atomica di tutto lo stato. | Nessuna riscrittura globale. Un file corrotto danneggia un solo gioco. Facile da sincronizzare. |
| Contro | Ogni scrittura riscrive tutto (si mitiga con `.tmp` + `ATOMIC_MOVE`, già usato per gli state). Rischio di perdere tutto se si rompe. | 1000+ file piccoli: scansione lenta all'avvio, più `open()` e più I/O, più difficile da ispezionare. |

- La chiave dovrebbe essere il **CRC32 del contenuto** (calcolato in background alla prima scansione e salvato), non il percorso. Il percorso resta come campo, così una ROM spostata si ritrova.
- Per i metadati esterni (stile No-Intro/Libretro DAT) basta `split` su TAB, senza librerie. TSV è preferibile al CSV perché evita l'escaping delle virgole nei titoli.
- Statistiche (`lastPlayed`, `playSeconds`, `launchCount`) e `favorite` conviene tenerle in un file **separato** dai metadati derivati o scaricati, che si possono rigenerare. Il file delle statistiche va protetto con scrittura atomica.

**Fattibilità in puro Swing**

- **Griglia virtualizzata per più di 1000 copertine con caricamento lazy:** fattibile, ed è già quasi così in `CoverGrid` (paint solo delle celle visibili, worker in background, cache LRU, `Scrollable` e geometria pronti). Per 1000+ serve:
  - un `ThreadPoolExecutor` con più thread e coda LIFO, che dia priorità alle celle visibili invece del FIFO attuale;
  - miniature su disco (cache a dimensione fissa, PNG/JPEG piccoli);
  - cancellazione dei caricamenti delle celle uscite dalla vista;
  - una cache limitata in byte e non in numero di voci (300 miniature da 190×144 ARGB sono circa 33 MB).
- **Tema scuro custom:** fattibile senza librerie. Opzioni: `Nimbus` con `UIDefaults` personalizzate, Metal con `UIManager.put` esteso, oppure componenti interamente custom come `CoverGrid`. Va fatto in un punto unico (oggi i colori sono impostati per componente). `JFileChooser` e `JOptionPane` sono i più difficili da uniformare: conviene un dialogo di scelta cartella proprio, oppure accettare l'aspetto di sistema solo lì.
- **Navigazione da tastiera/joystick con focus visibile:** fattibile. La tastiera c'è già in `CoverGrid`; va resa coerente sugli altri controlli (con i colori forzati il focus ring di Metal non si vede). Per il joystick serve il nuovo lettore di input nel launcher (sezione 4), che generi azioni di navigazione (su/giù/sinistra/destra/conferma/indietro) sull'EDT. Il formato dei profili può mappare anche i tasti di navigazione.
- **Limiti:** l'anti-aliasing dei font non è uniforme tra le distribuzioni Linux (serve `-Dawt.useSystemAAFontSettings=on` oppure `RenderingHints` ovunque). Con alcune pipeline grafiche le animazioni Swing sono lente; per animazioni fluide (scorrimento inerziale, transizioni) evitare `javax.swing.Timer` sotto i ~16 ms.

## Esempi reali (anonimizzati)

**File profilo controller** (`~/.config/superjavendo/joystick/easysmx.cfg`, uguale a quello incluso nel jar):
```ini
type=gamepad
name=EasySMX
threshold=12000

up=AXIS_7-,AXIS_1-
down=AXIS_7+,AXIS_1+
left=AXIS_6-,AXIS_0-
right=AXIS_6+,AXIS_0+
b=BUTTON_0
a=BUTTON_1
y=BUTTON_3
x=BUTTON_4
l=BUTTON_6,BUTTON_8
r=BUTTON_7,BUTTON_9
select=BUTTON_10
start=BUTTON_11

save_state=BUTTON_13
load_state=BUTTON_14
```
(Nel file reale ci sono anche commenti in italiano sul mapping e su `jstest`.)

**File di configurazione** (`controllers.cfg`):
```ini
# Assegna i profili (joystick/<nome>.cfg) ai giocatori. "@N" sceglie /dev/input/jsN (default 0).
# Più profili sullo stesso giocatore si sommano (es. tastiera + pad).
player1=keyboard,easysmx@0
player2=keyboard2,xbox@1
```

**`game.list`** (percorsi anonimizzati, separatore TAB):
```
# SuperJavendo game list: <path><TAB><title>. Regenerated by the folder scan.
/home/USER/Emu/snes/roms/7th Saga, The (U) [!].zip	7th Saga, The (U) [!]
/home/USER/Emu/snes/roms/A Bug's Life.zip	A Bug's Life
```

**Campi della classe del gioco** (`frontend/legacy/GameEntry`):
```java
public record GameEntry(Path path, String title)   // nessun altro campo
```
I dati ricavabili per un gioco ma oggi non memorizzati nel record: header (`Cartridge.title`, `mapping`, `chip`, SRAM, checksum), copertina (`Previews.coverOf`), stato più recente e relativo `savedAtMillis` (`Previews.of`).

## Sintesi delle priorità per la nuova UI

1. Estrarre da `EmuWindow` un'interfaccia di sessione, con listener di chiusura e stop/pausa/stato/screenshot/velocità.
2. Spostare `GameLauncher.open` fuori dall'EDT con un caricamento asincrono.
3. Estendere `GameEntry` (CRC32, regione, tag, preferito, ultima partita, tempo di gioco) con un catalogo persistito in `library.tsv` più `settings.cfg`.
4. Calcolare in background CRC32 e header leggero (senza costruire un `Cartridge`) durante la scansione.
5. Aggiungere un lettore di input per il launcher (joystick) con azioni di navigazione.
6. Centralizzare il tema (`UIManager` + `UIDefaults`) e sostituire `JFileChooser`/`JOptionPane` dove stonano.
