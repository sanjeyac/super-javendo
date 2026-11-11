# Super Javendo – Specifica interfaccia

Nov 11, 2026 · @Sanje

## Scopo e principi

La nuova interfaccia di Super Javendo è un frontend a libreria, in stile Steam scuro, con tre schermate: Libreria, ROM Manager e Controller. Questo documento descrive il mockup così com'è, come base per l'implementazione in Java Swing senza dipendenze esterne.

- **Uso principale:** PC con tastiera, mouse e joystick. La navigazione completa col pad è prevista ma non prioritaria.
- **Scala:** la libreria deve reggere diverse centinaia di giochi, quindi ricerca, filtri e raccolte sono funzioni di base, non accessorie.
- **Gioco separato:** il gioco si avvia sempre in una finestra a parte; il launcher resta aperto e mostra cosa è in esecuzione.
- **Dati visibili:** ogni gioco mostra i dati principali (anno, editore, genere, giocatori, regione), l'ultima partita, il tempo di gioco e i save state con il loro screenshot.
- **Configurazione:** profili controller illimitati, gestiti in un'unica schermata, e un ROM Manager per sistemare file, copertine e metadati.

## Struttura generale

Tutte le schermate condividono una barra superiore e la stessa disposizione su tre colonne: filtri a sinistra, contenuto al centro, dettaglio a destra.

| Zona | Contenuto |
| --- | --- |
| Barra superiore | Logo SUPER JAVENDO; schede Libreria, ROM Manager, Controller, Impostazioni; indicatore del gioco in esecuzione con «Porta in primo piano» e «Chiudi»; stato dei pad (es. «P1 EasySMX · P2 Tastiera») |
| Colonna sinistra (220–260 px) | Raccolte, filtri, cartelle o profili, secondo la schermata |
| Area centrale (flessibile) | Barra strumenti (ricerca, ordinamento, vista) e contenuto principale |
| Colonna destra (300–380 px) | Dettaglio dell'elemento selezionato e azioni principali |
| Barra comandi (solo Libreria) | Legenda dei tasti del pad: A Gioca, B Indietro, Y Preferito, X Save state, L/R Cambia raccolta, SELECT Cerca; a destra «Doppio clic o Invio per avviare» |

L'indicatore «In esecuzione» compare solo mentre un gioco è aperto nella sua finestra. La scheda Impostazioni è presente nella barra ma non ha ancora una schermata.

## Schermata Libreria

La Libreria è la schermata iniziale: si sceglie un gioco dalla griglia di copertine, se ne vedono i dati a destra e lo si avvia con GIOCA.

### Colonna sinistra: raccolte e filtri

- **Raccolte**, con il numero di giochi accanto: Tutti i giochi, Giocati di recente, Preferiti, Senza copertina.
- **Genere:** Tutti più un elenco (Azione, Avventura, Corse, GdR, Piattaforme, Picchiaduro, Quiz, Simulazione, Sport), ciascuno con il conteggio. Un secondo clic sul genere attivo lo toglie.
- **Giocatori:** tre pulsanti Tutti, 1, 2+.
- Raccolta, genere e giocatori si combinano tra loro e con la ricerca.

### Area centrale: catalogo

- **Barra strumenti:** campo di ricerca su titolo ed editore; menu Ordina (Titolo A–Z, Giocati di recente, Anno di uscita); interruttore vista griglia/lista.
- **Intestazione:** nome della raccolta attiva (più «· genere» se filtrato) e numero di giochi trovati.
- **Vista griglia:** colonne automatiche con larghezza minima 168 px; copertine in proporzione 4:3, come le scatole SNES americane.
  - Sotto ogni copertina: titolo (troncato con …) e riga «anno · genere · regione».
  - Stella dei preferiti in alto a destra, sempre cliccabile.
  - Badge «IN ESECUZIONE» in basso a sinistra sul gioco aperto.
  - Badge «N versioni» in basso a destra quando più file appartengono allo stesso gioco.
  - Copertina mancante: riquadro scuro con bordo tratteggiato, icona immagine, titolo e scritta «Copertina mancante».
  - Selezione: contorno lilla di 3 px staccato dalla copertina. Doppio clic avvia il gioco.
- **Vista lista:** tabella con Preferito, Titolo, Anno, Genere, Giocatori, Regione, Ultima partita, Tempo. La riga selezionata è evidenziata.
- **Nessun risultato:** messaggio «Nessun gioco corrisponde ai filtri» con il collegamento «Azzera filtri».

### Colonna destra: dettaglio gioco

1. Copertina grande; se manca, il collegamento «Assegna dal ROM Manager».
2. Titolo, editore e anno.
3. Pulsante verde GIOCA (diventa IN ESECUZIONE se il gioco è aperto) e pulsante stella per i preferiti.
4. Griglia di quattro dati: Genere, Giocatori, Ultima partita, Tempo di gioco.
5. **Save state:** lo stato più recente in grande con lo screenshot 256×224 (proporzione 8:7), il nome dello slot, la data e «Riprendi ▸». Sotto, fino a tre altri stati in miniatura. Senza stati: riquadro con l'indicazione del tasto per salvare.
6. In fondo: nome del file ROM, regione e CRC32.

## Schermata ROM Manager

Il ROM Manager serve a trovare e sistemare i file problematici: duplicati, copertine mancanti, ROM non riconosciute o illeggibili.

### Colonna sinistra: cartelle

- Elenco delle cartelle sorgente, ciascuna con percorso, numero di file e data dell'ultima scansione (nel mockup: `~/roms/snes`, `~/roms/snes-hack`, `~/roms/covers`).
- Pulsante «+ Aggiungi cartella».
- In fondo: «Scansiona di nuovo» (azione principale), «Aggiorna metadati» e il nome del file metadati con il numero di righe.

### Area centrale: riepilogo e tabella

- **Cinque contatori:** ROM totali, Riconosciute, Senza copertina, Duplicati/versioni, Errori di lettura. Ogni contatore ha un colore diverso.
- **Filtri a schede:** Tutte, Da sistemare (predefinito, con conteggio), Senza copertina, Duplicati, Non riconosciute, Nascoste.
- **Ricerca** su nome file, titolo o CRC.
- **Tabella** con colonne Stato, File, Abbinato a, Regione, Dimensione, Copertina, Metadati. Lo stato è un'etichetta colorata:

| Stato | Significato |
| --- | --- |
| OK | File riconosciuto, con copertina e metadati |
| Duplicato | Più file per lo stesso gioco |
| No copertina | Riconosciuto ma senza immagine |
| Non riconosciuta | Nessuna corrispondenza nei metadati |
| Errore | File illeggibile o vuoto |

- **Azioni sulla selezione multipla:** Rinomina secondo lo standard, Nascondi dalla libreria, Cerca copertine.

### Colonna destra: dettaglio del file o del gruppo

Nel mockup è selezionato il gruppo «F-Zero · 2 file».

1. **File principale:** scelta a pulsanti radio tra i file del gruppo. Ogni voce mostra nome file, qualità del dump (verificato o difettoso, con il CRC) e numero di save state. Nella libreria compare un solo gioco per gruppo.
2. **Copertina:** anteprima, percorso dell'immagine e «Cambia immagine…».
3. **Abbinamento metadati:** campo con la riga di metadati collegata (titolo, anno, editore, genere).
4. **Azioni:** «Nascondi il duplicato» (principale) e «Sposta \[b1\] nel cestino…» (in rosso, con conferma).

## Schermata Controller

La schermata Controller sostituisce la vecchia finestra: disegno del pad ed elenco delle assegnazioni sono collegati dalla stessa selezione, e il tasto scelto si modifica nel pannello a destra.

### Colonna sinistra: profili

- Ricerca profili, perché i profili possono essere tanti.
- Elenco dei profili con nome e stato: EasySMX (Porta 1, js0 collegato), Tastiera (Porta 2), 8BitDo SN30 Pro e Xbox Wireless (non collegati).
- Pulsanti «+ Nuovo» e «Duplica».

### Area centrale: editor del profilo

- **Intestazione:** nome del profilo modificabile; menu Dispositivo (js0 · EasySMX collegato, js1 · 8BitDo non collegato, Tastiera); selettore «Usato su» Porta 1 / Porta 2.
- **Due schede:** «Pulsanti SNES» e «Scorciatoie emulatore».
- **Disegno del pad** (scheda Pulsanti): ogni tasto (L, R, croce, SELECT, START, X, Y, A, B) è cliccabile. Il tasto selezionato diventa viola con contorno lilla. I tasti frontali usano i colori A rosso, B giallo, X blu, Y verde.
- **Elenco unico delle assegnazioni** sotto il disegno, su due colonne: nome del tasto e input assegnati come etichette (es. `AXIS_7 −`). Un tasto senza input mostra «Non assegnato» in giallo.

Assegnazioni del profilo EasySMX nel mockup:

| Tasto SNES | Input |
| --- | --- |
| Su | AXIS\_7−, AXIS\_1− |
| Giù | AXIS\_7+, AXIS\_1+ |
| Sinistra | AXIS\_6−, AXIS\_0− |
| Destra | AXIS\_6+, AXIS\_0+ |
| SELECT / START | BUTTON\_10 / BUTTON\_11 |
| L / R | BUTTON\_6 / BUTTON\_7 |
| Y / X | BUTTON\_3 / BUTTON\_4 |
| B / A | BUTTON\_0 / BUTTON\_1 |

- **Scheda Scorciatoie emulatore:** tabella Azione / Combinazione sul pad / Tastiera.

| Azione | Pad | Tastiera |
| --- | --- | --- |
| Salva stato (slot attivo) | SELECT + R | F5 |
| Carica stato (slot attivo) | SELECT + L | F7 |
| Slot successivo / precedente | SELECT + ▶ / ◀ | F6 / Maiusc+F6 |
| Menu di pausa | SELECT + START | Esc |
| Avanti veloce (tieni premuto) | — | Tab |
| Screenshot | — | F12 |
| Reset | — | Ctrl + R |

### Colonna destra: tasto selezionato

1. Nome del tasto e numero di input assegnati («ognuno di questi input attiva il tasto»).
2. Un riquadro per input: codice, tipo (Croce digitale/hat, Levetta sinistra, Asse analogico, Pulsante del pad) e pulsante × per rimuoverlo.
3. «+ Aggiungi input» apre lo stato «In ascolto…»: si preme un tasto o si muove un asse, con chiusura automatica dopo 5 secondi e pulsante Annulla.
4. **Test dal vivo:** premendo i tasti sul pad, il tasto SNES corrispondente si illumina nel disegno; sotto compare l'ultimo input rilevato.
5. In fondo: avviso «Modifiche non salvate», «Salva profilo», «Annulla modifiche», «Predefiniti».

## Stile visivo

Tema scuro grigio-blu con un solo accento viola per selezioni e azioni principali; il verde è riservato a GIOCA e allo stato «in esecuzione».

| Ruolo | Colore |
| --- | --- |
| Fondo barre superiore e inferiore | #0d1016 |
| Fondo area centrale | #12151c |
| Fondo colonne laterali | #151921 |
| Campi e card | #1b2029, bordo #2b3240 |
| Bordi e separatori | #232835 |
| Voce attiva / riga selezionata | #2a2f45 / #232838 |
| Testo principale / secondario / terziario | #e6e9ef / #aab2c2 / #8a93a5 |
| Accento (pulsanti principali, tasto selezionato) | #5b4bd6 |
| Accento chiaro (contorno selezione, link) | #b4aaff |
| GIOCA (testo #06180d) / in esecuzione | #3fbf6f / #4fd17f |
| Preferito (stella piena) | #f5c542 |
| Stati: OK, attenzione, duplicato, non riconosciuta, errore | #8be8ad, #f6d48a, #a9c7ff, #dcc4ff, #ffb3bc |

- **Tipografia:** Space Grotesk per logo e titoli, Inter per il resto; corpo 14 px, etichette di sezione 11 px in maiuscolo con spaziatura larga, codici e nomi file in monospazio. In Swing i due font vanno inclusi come TTF nelle risorse del jar.
- **Forme:** angoli arrotondati di 6–8 px; copertine 4:3, screenshot 8:7.
- **Interazione:** aree cliccabili alte almeno 34–44 px; selezione sempre visibile come contorno lilla di 3 px, così funziona anche con la navigazione da tastiera e pad.

## Punti aperti

Il mockup usa dati di esempio inventati (tempi, date, conteggi, colori al posto delle copertine) e in alcuni punti non corrisponde ancora al codice reale descritto nel report di analisi.

- [ ] **Slot dei save state:** il codice ha 5 slot fissi (0 rapido + 1–4); il pannello deve mostrarli tutti, anche vuoti, invece di «Auto / Slot 1…».
- [ ] **Scorciatoie:** save/load da pad sono tasti del profilo (`save_state=BUTTON_13`, `load_state=BUTTON_14`), non combinazioni SELECT+R/L; da tastiera valgono 1–4 / Maiusc+1–4, F5/F9 per lo slot 0, P pausa, Esc chiudi. Avanti veloce, screenshot e menu di pausa in finestra non esistono ancora.
- [ ] **Giocatori e profili:** ogni giocatore può sommare più profili (es. P1 = tastiera + EasySMX@js0); il selettore «Usato su Porta 1/2» va sostituito da una sezione Giocatori.
- [ ] **L e R:** nel profilo reale hanno due input ciascuno (BUTTON\_6, BUTTON\_8 e BUTTON\_7, BUTTON\_9), il mockup ne mostra uno.
- [ ] **Nome del dispositivo:** oggi i pad sono solo js0…js15; il nome si può leggere da `/sys/class/input/jsN/device/name`.
- [ ] **Metadati:** formato TSV con chiave CRC32 (non CSV); preferiti e statistiche in un file separato. Il CRC32 oggi non viene calcolato.
- [ ] **Ultima partita e tempo di gioco:** non vengono ancora registrati; servono un'interfaccia di sessione e un evento di chiusura della finestra di gioco.
- [ ] **Rinomina nel ROM Manager:** save state e SRAM sono legati al nome del file, quindi rinominare oggi spezzerebbe il collegamento.
- [ ] **Navigazione col pad nel launcher:** richiede un lettore di input dedicato, oggi presente solo nella finestra di gioco.
- [ ] **Impostazioni:** schermata ancora da progettare (video, audio, percorsi).
