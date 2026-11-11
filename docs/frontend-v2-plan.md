# Frontend v2 — piano di realizzazione

Specifica e mockup: `docs/ui-design/`. La v2 sta in un package separato (`dev.superjavendo.frontend2`) e **non tocca la v1**
(`frontend/`): si avvia di default; `--ui-legacy` apre la v1. Riuso dalla v1 solo codice non grafico
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
| M3 | **Controller**: profili con ricerca, nome dispositivo da `/sys/class/input/jsN/device/name`, editor con disegno del pad, assegnazioni multiple, «aggiungi input» in ascolto, test dal vivo, sezione Giocatori (più profili per giocatore), scorciatoie | da fare |
| M4 | Impostazioni (video, audio, percorsi), navigazione col pad nel launcher, «Riprendi» da uno slot, rinomina sicura (con spostamento di save state/SRAM) | da fare |

## Punti aperti della specifica che toccano il core

Slot dei save state (5 fissi: 0 + 1–4) mostrati tutti; scorciatoie reali (tastiera: 1–4, Maiusc+1–4, F5/F9, P, Esc; pad: `save_state`/`load_state` del profilo);
avanti veloce / screenshot / menu di pausa non esistono ancora in `EmuWindow`; rinomina ROM spezzerebbe save state e SRAM (legati al nome file).
