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



