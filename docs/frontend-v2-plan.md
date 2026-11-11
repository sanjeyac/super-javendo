# Frontend v2 — piano di realizzazione

Specifica e mockup: `docs/ui-design/`. La v2 sta in un package separato (`dev.superjavendo.frontend.modern`) e **non tocca la v1**
(`frontend/legacy/`): si avvia di default; `--ui-legacy` apre la v1. Riuso dalla v1 solo codice non grafico
(`GameList`, `Previews`, `GameLauncher`, `AppPaths`, `ProfileStore`…).

## Decisioni

- Swing puro, nessuna dipendenza. Font **Inter** e **Space Grotesk** (OFL) in `src/main/resources/fonts/`, caricati con `Font.createFont`
  (se mancano si usa il font di sistema).
- Componenti disegnati a mano (pulsanti, griglia copertine, chip) sui colori della specifica (`Pal`); niente Look&Feel esterni.
- Logica (filtri/ordinamento/raccolte) separata dalla grafica (`LibraryModel`) così si testa senza finestre.
- Dati utente (preferiti, ultima partita, tempo di gioco) in `library-user.tsv` nella config, **separati** dalla lista giochi e dai metadati.
- Metadati: file `games.tsv` opzionale (`CRC32<TAB>titolo<TAB>anno<TAB>editore<TAB>genere<TAB>giocatori<TAB>regione`). Senza di esso anno/genere
  risultano «—» e i filtri per genere si nascondono. Il CRC32 si calcola in background e si mette in cache (`crc.cache`).
- Verifica visiva senza desktop: `--ui2-shot out.png [LARGHEZZA ALTEZZA] [libreria|rom|controller]` disegna la finestra su immagine.

## Milestone

| # | Contenuto | Stato |
|---|-----------|-------|
| M1 | Guscio (barra superiore, schede, tre colonne, tema, font) + **Libreria** su dati reali: raccolte, genere, giocatori, ricerca, ordina, griglia/lista, preferiti, dettaglio con 5 slot di save state, indicatore «in esecuzione» con porta-in-primo-piano/chiudi, registrazione ultima partita e tempo | fatto |
| M2 | CRC32 + `games.tsv` + gruppi di versioni, **ROM Manager** (cartelle sorgente in `sources.tsv`, contatori, tabella stati con selezione multipla, scelta del file principale, nascondi/mostra, cestino, copertine da file, «Cerca copertine»). Decisioni in `library-overrides.tsv`. «Rinomina secondo lo standard» è solo un avviso: arriva con M4 | fatto |
| M3 | **Controller**: `ControllerScreen` (profili con ricerca, + Nuovo / Duplica, nome, dispositivo da `/sys/class/input/jsN/device/name`, «Usato su» Giocatore 1/2 con più profili per giocatore, disegno del pad cliccabile `PadCanvas`, elenco assegnazioni, «+ Aggiungi input» in ascolto 5 s, test dal vivo con `GamepadMonitor`, scorciatoie, salva/annulla/predefiniti). Logica in `input/` (`ProfileLibrary`, `ControllerAssignments`, `GamepadMonitor`, `GamepadDevices`) e `ProfileEditor` | fatto |

## Punti aperti della specifica che toccano il core

Slot dei save state (5 fissi: 0 + 1–4) mostrati tutti; scorciatoie reali (tastiera: 1–4, Maiusc+1–4, F5/F9, P, Esc; pad: `save_state`/`load_state` del profilo);
avanti veloce / screenshot / menu di pausa non esistono ancora in `EmuWindow`; rinomina ROM spezzerebbe save state e SRAM (legati al nome file).

## Lingue (i18n)

`i18n/I18n` legge `src/main/resources/i18n/messages[_xx].properties` (UTF-8): inglese (radice, fallback), italiano, spagnolo, francese, tedesco, portoghese, olandese, polacco.
Segnaposto `{0}`, `{1}`. La lingua sta in `settings.cfg` (`language=`, vuoto = lingua di sistema); cambiandola dalle Impostazioni la finestra si ricostruisce subito
(un gioco in esecuzione continua ma esce dall'indicatore). Per provare una lingua senza toccare la config: `java -Dsj.lang=de -jar … --ui2-shot …`.
Per aggiungere una lingua: nuovo file `messages_xx.properties` con **le stesse chiavi** (lo verifica `I18nTest`) e una riga in `I18n.LANGUAGES`.
Le stringhe di `EmuWindow` (toast in gioco) sono tradotte; la v1 (`frontend/legacy/`) e i messaggi di errore del core restano come sono.
