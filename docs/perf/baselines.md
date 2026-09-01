# Performance-Baselines

Messwerte je Szenario. Alte Zeilen bleiben stehen — die Historie ist der Zweck
der Datei. Genau eine Zeile je Szenario traegt `Budget = ja`; sie ist der
verbindliche Sollwert, gegen den spaetere Laeufe geprueft werden.

Werkzeuge: `docs/perf/tools/` (`snapshot.sh`, `run.sh`, `parse.sh`,
`compare.sh`). Sie liegen ausserhalb des Produktivcodes und werden nicht
ausgeliefert.

---

## Messmethodik: wie das Beobachterproblem aufgeloest ist

Die naheliegende Verlustmetrik — die `btif_a2dp_source`-Zaehler — steht in
`dumpsys bluetooth_manager`, also in genau dem Aufruf, dessen Wirkung gemessen
werden soll. Der Aufbau loest das dreistufig auf:

| Stufe | Quelle | Beruehrt den BT-Stack? | Abtastung |
|---|---|---|---|
| 1 | `/proc/<pid>/stat`, `/proc/<pid>/task/*/schedstat` | nein, reine procfs | beliebig, hier 2x je Lauf |
| 2 | `dumpsys media.audio_flinger` (A2DP-Output-Thread) | nein, geht nach `audioserver` | 2x je Lauf |
| 3 | `dumpsys bluetooth_manager` (`A2DP State:`) | **ja — das Messobjekt selbst** | **genau 2x je Lauf** |

Alle gelesenen Groessen sind **kumulative Zaehler**. Ein Lauf wird deshalb
gemessen, indem der Snapshot einmal am Anfang und einmal am Ende genommen und
subtrahiert wird — waehrend des Laufs tastet die Messung gar nichts ab. Die
Stufe-3-Beruehrung ist damit in jeder Bedingung identisch, auch in der
Referenz, und faellt beim Vergleich der Bedingungen als konstanter Offset
heraus.

Stufe 2 ist zusaetzlich ein **unabhaengiger Zeuge**: `media.audio_flinger`
erreicht `audioserver` und nimmt keine Sperre des Bluetooth-Stacks. Stallt der
Audiopfad, weil ein `bluetooth_manager`-Dump den Stack anhaelt, muss sich das
dort zeigen — in `Delayed writes`, `Timestamp disc` und der Streuung der
`Threadloop write latency`.

---

## Szenario: LDAC-Wiedergabe, Pixel 11 Pro

**Definition (unveraendert fuer alle Zeilen unten):**

- Geraet: Pixel 11 Pro `67011FDKX004XG`, Android 17 (SDK 37), per Kabel.
- Kopfhoerer: Noble FoKus Prestige Encore, A2DP/LDAC, `Rate=96000 Bits=32
  Mode=STEREO`, Quality-Mode `ABR`, gemessene Rate 492–660 kbps.
- Kodierung laeuft **host-seitig** (`btif_a2dp_source` fuehrt lebende
  TxQueue-Zaehler). `A2dpOffloadEnabled: true` ist eine Faehigkeitsangabe des
  Adapters und beschreibt diesen Stream nicht.
- Musik durchgehend, Bildschirm an, Geraet am Kabel.
- Lauflaenge 180 s je Bedingung, gemessene Spanne 192 s (Snapshot-Overhead).
- Build: HEAD `babe3d8`, `app-debug.apk`, md5 `5577039935641a5959ab8cb7420f389f`.

**Metriken:** `uflow/min` = `Counts (underflow)` aus `A2DP State:`.
`enqOvd/min`, `deqOvd/min` = Enqueue-/Dequeue-Deviation "overdue".
`tsDisc/min`, `delayW/min` = `Timestamp disc` und `Delayed writes` des
AudioFlinger-A2DP-Output-Threads. `cpu_bt` = Anteil eines Kerns fuer
`com.google.android.bluetooth`.

### Streuung und Signifikanzschwelle

Aus den sechs Laeufen mit 2000-ms-Kadenz (A1, A2, S1, S1b, S2, S3), die sich
in der Belastung unterscheiden, aber alle im ungestoerten Regime liegen:

| Groesse | Mittel | sd | 2sd = Schwelle |
|---|---|---|---|
| Enqueue-Deviation "overdue" /min | 2517,3 | 12,9 | **±1,03 %** |
| Dequeue-Deviation "overdue" /min | 1883,6 | 16,9 | **±1,80 %** |
| Enqueue-Rate /min (Konfundierungs-Check) | 3015,7 | 24,2 | ±1,61 % |
| CPU `com.google.android.bluetooth` | 16,9 % | 0,2 | ±2,0 % |

Die **Enqueue-Rate ist ueber alle Bedingungen konstant** (±1,6 %). Die
Deviation-Zaehler sind damit direkt vergleichbar und nicht durch die
ABR-Bitratenwanderung (492–660 kbps) verzerrt.

**Absolute Untergrenze:** Verlustzaehler von 0 pro Minute sind nicht
weiter verbesserbar. Wo `uflow/min = 0` und `delayW/min = 0` steht, ist
die Bedingung am Boden und keine Optimierung kann dort etwas gewinnen.

### Block 1 — Hypothesen-Diskriminator (App force-stopped in allen Armen)

Nicht die App wird hier gemessen, sondern **was ein `dumpsys` welcher Art dem
laufenden LDAC-Strom antut**. Drei Reize vergleichbarer CPU-Kosten, von denen
nur einer den Bluetooth-Stack betritt:

| Lauf | Reiz | Passes | CPU bt | uflow/min | enqOvd/min | deqOvd/min | tsDisc/min | delayW/min | latMax ms |
|---|---|---|---|---|---|---|---|---|---|
| A1 | keiner (Referenz) | – | 16,8 % | 0,31 | 2536,3 | 1895,2 | 0,62 | 0 | 38,5 |
| A2 | keiner (Referenz) | – | 16,9 % | 0 | 2513,7 | 1872,8 | 0 | 0 | 39,0 |
| S2 | `media.audio_flinger` @2 s | 85 | 16,7 % | 0 | 2497,2 | 1858,1 | 0 | 0 | 37,7 |
| S3 | CPU-Burn @2 s (172 ms) | 85 | 17,1 % | 0 | 2516,9 | 1878,0 | 0 | 0 | 39,0 |
| S1 | **`bluetooth_manager` @2 s** | 85 | 21,1 % | 0 | 2524,4 | 1903,2 | 0 | 0 | 38,5 |
| S1b | **`bluetooth_manager` @2 s** | 85 | 19,3 % | 0 | 2515,3 | 1894,4 | 0 | 0 | 39,0 |
| S4 | **`bluetooth_manager` @0,5 s** | 282 | 31,3 % | 0 | 2357,8 | 1771,7 | 0 | 0 | 39,0 |
| S4b | **`bluetooth_manager` @0,5 s** | 284 | 30,6 % | 0 | 2379,6 | 1774,3 | 0 | 0 | 39,0 |

Reizkosten gemessen: `bluetooth_manager` 172–176 ms, `media.audio_flinger`
110–118 ms, CPU-Burn 162 ms (auf 171 ms gegen den btdump kalibriert).
S4 laeuft mit **48 % Duty-Cycle** — fast die Haelfte der Laufzeit steckt das
Telefon in `dumpsys bluetooth_manager`.

**Befund:**

1. `dumpsys bluetooth_manager` kostet CPU **im Bluetooth-Prozess**, und zwar
   proportional zur Rate: +3,4 Punkte bei 2 s, +14,1 Punkte bei 0,5 s
   (16,9 % → 30,9 %, also fast eine Verdopplung). Die Kontrollen S2 und S3
   bleiben auf Referenzniveau — die Last entsteht wirklich im BT-Prozess und
   nicht nebenan.
2. **Keine einzige Verlustmetrik verschlechtert sich** — nicht bei 2 s, nicht
   bei 0,5 s: keine Underflows, keine Dropouts, keine `Delayed writes`, keine
   Timestamp-Diskontinuitaeten, unveraenderte Worst-Case-Schreiblatenz.
3. Die Scheduling-Deviationen werden unter Volllast sogar **besser**:
   −5,9 % enqueue-overdue (5,7-fache Schwelle) und −5,9 % dequeue-overdue
   (3,3-fache Schwelle). Plausibelste Erklaerung: die Dauerlast haelt Kerne
   aus dem tiefen Idle und den Takt oben, sodass der Encoder-Thread seine
   periodischen Weckungen pruempter serviert bekommt. Passend dazu steigt
   `enqueue premature` von ~490 auf 559/min.
4. Die einzigen Verlustereignisse der ganzen Serie (1 Underflow, 2
   Timestamp-Diskontinuitaeten) fielen in **A1 — die Referenz ohne jede Last**.



---

## Szenario: LDAC-Wiedergabe, App DEINSTALLIERT, WLAN aus — Pixel 11 Pro

**Neuer Abschnitt, nicht mit Block 1 vermischen.** Das Szenario unterscheidet
sich in drei Punkten vom Szenario oben, jeder davon reicht fuer eine eigene
Tabelle: die App ist **deinstalliert** (statt force-stopped), **WLAN ist aus**
(`Wi-Fi is disabled`), und die Verlustzaehler werden **waehrend** des Laufs
abgetastet (100 bzw. 160 `bluetooth_manager`-Dumps statt 2).

**Definition:**

- Geraet: Pixel 11 Pro `67011FDKX004XG`, Android 17 (SDK 37), am Kabel,
  Bildschirm an, `mIsPowered=true`.
- Kopfhoerer: derselbe wie oben, A2DP/LDAC, `Rate=96000 Bits=32 Mode=STEREO`,
  Quality-Mode `ABR`, **Encoder-Interval 20 ms, Effective MTU 883**.
- Kodierung host-seitig (in T-007 fuenffach belegt, s. `T-007-aufnahme.md` 2.2).
- Player: TIDAL, Quelle 48 kHz Stereo; Mixer-Thread ist der
  **Spatializer-Thread** (96 kHz, 5.1-Maske) — Spatial Audio fuer dieses
  A2DP-Geraet aktiviert, Effekt selbst `IDLE`.
- Umgebung: 3 gleichzeitige aktive BLE-Scans (GMS), 56 sichtbare BLE-Geraete,
  `Thermal Status: 1` (light throttling).
- Kein Codec-Pinning, keine veraenderten Entwickleroptionen.

**Neue Metrik gegenueber Block 1:** `ABR-Stufen` = beobachtete
LDAC-Bitratenstufen, `ABR-Adj/min` = Zuwachs von
`LDAC adaptive bit rate adjustments`.

| Datum | Commit | Umgebung | Lauf | Kadenz | Dauer | uflow/min | Dropouts | enqOvd/min | deqOvd/min | ABR-Stufen | ABR-Adj/min | Budget | Notiz |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-09-01 | 9796b84 (Repo; App nicht installiert) | Pixel 11 Pro, warm, App deinstalliert, WLAN aus | A | 1459 ms | 144,4 s | **0** | 0 | 2579,2 | 1979,2 | 492/660 | — | **ja** | T-007 Referenzarm AK-1 |
| 2026-09-01 | 9796b84 | dito | B | 1443 ms | 101,0 s | **0** | 0 | — | — | 492/660 (50/50) | 5,3 | nein | Stufen-/Bitratenreihe |
| 2026-09-01 | 9796b84 | dito | C | 459 ms | 73,1 s | **0** | 0 | — | — | 492/660 | 4,9 | nein | Hochaufloesung, 33 % Duty |

**Befund:**

1. **Null Verlustereignisse ueber 318 s kumuliert** in allen drei Laeufen:
   keine Underflows, keine Dropouts, keine Drops, keine Flushes.
   `LDAC saved transmit queue length` war in 260 von 262 Samples 0.
   Nach der Untergrenzen-Regel oben ist diese Bedingung **am Boden** — hier
   ist durch Optimierung nichts zu gewinnen.
2. **Kein periodisches Muster bei ~3 s.** Autokorrelation des
   Enqueue-Raten-Residuums aus Lauf C (459 ms, n=160): r = −0,022 bei 2,76 s
   und r = +0,008 bei 3,22 s, gegen ein Signifikanzband von ±0,159.
   **Wichtig:** das Geraet faehrt in diesem Szenario nie die 990er Stufe. Die
   Aussage gilt fuer 492/660 kbps und widerlegt den T-005-Befund **nicht**.
3. **Der eigentliche Fund ist eine Bitratenschaukel.** ABR pendelt zwischen
   genau zwei Stufen (660 kbps / Index 1 und 492 kbps / Index 3), 50/50, alle
   ~11 s eine Anpassung. Die 492er-Phasen dauern dreimal exakt 15,8 s (fester
   Haltetimer), die 660er-Phasen unregelmaessig (2,9–28,8 s). Die beiden
   einzigen Samples mit `SavedTxQueue != 0` liegen genau auf Abstiegen
   660 → 492. **Der Encoder verliert nichts, weil er vorher nachgibt — der
   Qualitaetsverlust steht in der Bitrate, nicht in den Verlustzaehlern.**
4. **Abweichung gegenueber dem Block-1-Budget, ungeklaert:**
   `enqOvd/min` 2579,2 gegen 2517,3 (+2,46 %, Schwelle ±1,03 %) und
   `deqOvd/min` 1979,2 gegen 1883,6 (+5,08 %, Schwelle ±1,80 %). Die
   Enqueue-Grundrate ist mit 3001,3 vs. 3015,7 (−0,48 %) stabil, die Zaehler
   sind also nicht durch eine veraenderte Grundrate verzerrt. Konfundiert
   durch die andere Messkadenz und die unbekannte ABR-Stufenverteilung der
   Block-1-Laeufe. Bemerkenswert: die Richtung **widerspricht** dem in Block 1
   belegten Beobachtereffekt (mehr Dumpsys-Last senkte dort die
   Overdue-Zaehler um 5,9 %). **Als moegliche Regression gemeldet; braucht
   einen Vergleichslauf mit Block-1-Methodik (2 Dumps, 180 s), bevor
   optimiert wird.**
5. CPU `com.google.android.bluetooth` 14,9 % (procfs, 20,5-s-Fenster, ohne
   Dumpsys-Last) gegen 16,8–16,9 % Referenz in Block 1.

Volle Aufnahme inkl. Konfiguration, Stoergroessen und Zeitreihentabelle:
`docs/perf/T-007-aufnahme.md`.
