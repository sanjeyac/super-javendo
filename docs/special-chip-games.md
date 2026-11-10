# Giochi SNES con chip speciali

Elenco dei titoli da procurarsi per testare i coprocessori man mano che li implementiamo.
Compilato a memoria: i nomi regionali cambiano e qualche assegnazione chip↔gioco è da **verificare con l'header
della ROM** (vedi sezione "Come riconoscerli"). Dove non sono sicuro l'ho scritto.

Segna `[x]` quando trovi la ROM. In grassetto il **titolo consigliato per i primi test** di ogni chip.

## Ordine di implementazione consigliato

| # | Chip | Giochi | Difficoltà | Resa |
|---|------|--------|-----------|------|
| 1 | S-DD1 | pochi ma famosi | bassa (decompressore) | media |
| 2 | OBC1 | 1 gioco | molto bassa | bassa |
| 3 | DSP-1 | ~8 giochi, molti racing | media (HLE del DSP) | alta |
| 4 | Cx4 | Mega Man X2/X3 | media (HLE) | media |
| 5 | SuperFX (GSU-1/2) | ~9 giochi 3D | alta (CPU RISC + framebuffer) | alta |
| 6 | SPC7110 | 3 giochi (JP) | media | bassa |
| 7 | SA-1 | ~30 giochi | molto alta (secondo 65816) | alta |
| 8 | ST010/ST011/ST018 | 3 giochi (JP) | alta | molto bassa |

## DSP-1 / 1A / 1B (matematica 3D, rotazioni, proiezione)
- [ ] **Super Mario Kart** (USA/EUR/JP)
- [ ] Pilotwings
- [ ] Ace wo Nerae! (JP)
- [ ] Super Air Diver / Lock On (JP/USA)
- [ ] Suzuka 8 Hours (JP)
- [ ] Michael Andretti's Indy Car Challenge
- [ ] Armored Trooper Votoms: The Battling Road (JP)
- [ ] Super Bases Loaded 2 (da verificare)

## DSP-2 / DSP-3 / DSP-4 (varianti, un solo gioco ciascuna)
- [ ] Dungeon Master (JP) — DSP-2
- [ ] SD Gundam GX (JP) — DSP-3
- [ ] Top Gear 3000 / Planet's Champ (USA/EUR) — DSP-4

## SuperFX (GSU-1 e GSU-2, grafica 3D poligonale)
- [ ] **Star Fox** (USA) / Starwing (EUR)
- [ ] Star Fox 2 (mai uscito all'epoca, ROM disponibile da SNES Classic)
- [ ] Stunt Race FX / Wild Trax
- [ ] Super Mario World 2: Yoshi's Island (GSU-2)
- [ ] Doom (SNES, GSU-2)
- [ ] Dirt Trax FX
- [ ] Vortex
- [ ] Winter Gold / FX Skiing (GSU-2, da verificare)

## SA-1 (secondo 65816 a 10 MHz)
- [ ] **Super Mario RPG: Legend of the Seven Stars**
- [ ] Kirby Super Star / Kirby's Fun Pak
- [ ] Kirby's Dream Land 3 / Hoshi no Kirby 3
- [ ] Dragon Ball Z: Hyper Dimension (JP)
- [ ] Jumpin' Derby (JP)
- [ ] Masters New: Harukanaru Augusta 3 (JP)
- [ ] Pebble Beach no Hotou ni Yoseru (JP)
- [ ] SD Gundam G NEXT (JP)
- [ ] Super Bomberman: Panic Bomber W (JP)
- [ ] Daisenryaku Expert WWII (JP)
- [ ] J.League '96 Dream Stadium (JP)
- [ ] Itoi Shigesato no Bass Tsuri No. 1 (JP)
- (altri titoli giapponesi minori usano il SA-1: se ne trovi, controlla l'header)

## Capcom CX4 (trigonometria e wireframe)
- [ ] **Mega Man X2**
- [ ] Mega Man X3

## S-DD1 (decompressione grafica in tempo reale)
- [ ] **Street Fighter Alpha 2** (USA/EUR) / Street Fighter Zero 2 (JP)
- [ ] Star Ocean (JP)

## SPC7110 (decompressione + RTC opzionale)
- [ ] **Far East of Eden Zero** / Tengai Makyou Zero (JP)
- [ ] Momotarou Dentetsu Happy (JP)
- [ ] Super Power League 4 (JP)

## OBC1 (gestione OAM)
- [ ] **Metal Combat: Falcon's Revenge**

## Seta ST010 / ST011 / ST018 (IA, tutti giapponesi o rari)
- [ ] F1 ROC II: Race of Champions — ST010
- [ ] Hayazashi Nidan Morita Shougi — ST011
- [ ] Hayazashi Nidan Morita Shougi 2 — ST018

## Altri casi speciali (non hanno un chip "matematico" ma richiedono hardware extra)
- [ ] Daikaijuu Monogatari II (JP) — orologio S-RTC
- [ ] Super Game Boy / Super Game Boy 2 — contiene un Game Boy completo: servirebbe un core GB (lavoro enorme)
- [ ] BS-X / Satellaview (JP) — hardware di trasmissione via satellite, probabilmente da escludere
- Homebrew con MSU-1 (audio/video da file): possibile in futuro, non è hardware originale

## Come riconoscerli

Basta guardare l'header della ROM (byte `$FFD6` per HiROM, `$7FD6` per LoROM, offset di file `0x7FD6` o `0xFFD6`),
il campo "ROM type":

| Valore | Significato |
|--------|-------------|
| `0x00-0x02` | solo ROM / RAM / RAM+batteria (nessun chip) |
| `0x03` | DSP |
| `0x04` / `0x05` / `0x06` | DSP + RAM / DSP + RAM + batteria / DSP + batteria |
| `0x13` / `0x14` / `0x15` / `0x1A` | SuperFX (con RAM / batteria) |
| `0x25` | OBC1 |
| `0x32` / `0x34` / `0x35` | SA-1 |
| `0x43` / `0x45` | S-DD1 |
| `0x55` | S-RTC |
| `0xE3` / `0xE5` | Super Game Boy / Satellaview |
| `0xF3` | Cx4 |
| `0xF5` / `0xF9` | SPC7110 (il secondo anche con RTC) |
| `0xF6` | ST010 / ST011 / ST018 (distinti da un byte extra in `$FFBF`) |

Il nostro emulatore stampa già titolo e mappatura all'avvio; quando aggiungeremo i chip stamperà anche questo
campo, così basterà lanciare una ROM per sapere che coprocessore richiede.

## Note pratiche

- Le dimensioni tipiche: DSP-1 da 1 a 2 MB, SuperFX da 1 a 2 MB (Yoshi's Island 2 MB), SA-1 da 2 a 4 MB, S-DD1 e SPC7110 fino a 5-6 MB.
- Per i DSP-n, SuperFX, Cx4 e ST01x alcuni emulatori richiedono un file di firmware/ROM del chip
  (es. `dsp1.rom`). Per il DSP-1 possiamo evitarlo implementando le funzioni ad alto livello (HLE), per gli altri
  decideremo caso per caso.
- Prima di cercare i giochi più rari, i titoli consigliati in grassetto (uno per chip) bastano a validare l'implementazione.
