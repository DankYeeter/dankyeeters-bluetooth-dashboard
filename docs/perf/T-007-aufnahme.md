# T-007 — Geraeteaufnahme Pixel 11 Pro, 2026-09-01

Read-only Ist-Aufnahme des Bluetooth-Audiopfads im laufenden Betrieb.
Referenzzustand nach GOAL.md AK-1: **App deinstalliert** (`pm list packages |
grep btdash` leer, rc=1), Musik laeuft durchgehend.

Evidenzniveaus nach GOAL.md AK-3: **belegt** = direkt gemessen/aus Dump
zitiert. **plausibel** = Mechanismus bekannt, Wirkung hier nicht isoliert
nachgewiesen. **spekulativ** = Hypothese ohne Messung.

**Schwaerzung (Director-Entscheidung):** MAC-Adressen gekuerzt, Geraetenamen
nur als Rolle. SSID/BSSID entfallen — WLAN war aus, es gab nichts zu
schwaerzen. Rohdumps liegen ausschliesslich ausserhalb des Repos (siehe
Abschnitt 9).

---

## 1. Umgebung

| Groesse | Wert | Quelle |
|---|---|---|
| Geraet | Pixel 11 Pro, `grizzly` | `adb devices -l` |
| Serial | `67011FDKX004XG` | — |
| Android | 17, SDK 37, Build `CD1A.260714.001.A9` | `getprop` |
| Verbindung | USB-Kabel, adb 31.0.2 (`C:\RSL\2.1HF5\adb\adb.exe`) | — |
| Adapter-MAC | `E0:1A:DF:…` | `settings get secure bluetooth_address` |
| BT-Adapter-Uptime | 14 h 21 m 50 s, 0 Crashes | `dumpsys bluetooth_manager` |
| Ladezustand | am Kabel, `mIsPowered=true`, Bildschirm an | `dumpsys power` |
| Player | TIDAL (`com.aspiro.tidal`, uid 10383, pid 10851), `state=PLAYING(3)` | `dumpsys media_session` |
| Senke | Kopfhoerer A (LDAC-faehig, in `baselines.md` benannt), `…:37:8F` | `dumpsys bluetooth_manager` |
| App unter Test | **nicht installiert** | `pm list packages` |

Aufnahmefenster ca. 21:19–21:35 lokal.

---

## 2. Der Audiopfad im Betrieb

### 2.1 Ausgehandelte Konfiguration (belegt)

| Groesse | Wert |
|---|---|
| Current Codec | **LDAC** |
| Config | `Rate=96000 Bits=32 Mode=STEREO` |
| Selectable / Local capability | `Rate=44100\|48000\|88200\|96000 Bits=16\|24\|32 Mode=STEREO` |
| LDAC quality mode | **ABR** (nicht gepinnt) |
| Encoder interval | 20 ms |
| Effective MTU | 883 Byte |
| Priority | LDAC 5001, darueber nur LHDCv5 5002 (Config: Invalid) |
| Packet counts (expected/dropped) | 210991 / **0** |
| PCM read counts (expected/actual) | 1061101 / 1060570 (Delta 531 = die kumulierten Underflows) |

Weitere Codecs im Stack sind vorhanden, aber `Config: Invalid` (LHDCv5,
AptX-HD, AptX, AAC, Opus, SBC).

### 2.2 Offload oder Host-Encoding? — **Host-Encoding, eindeutig (belegt)**

Woran es festgemacht ist, in der Reihenfolge der Beweiskraft:

1. **Die `btif_a2dp_source`-TxQueue-Zaehler leben.** Ueber 144 s stiegen
   `Counts (enqueue/dequeue/readbuf)` um 7224 / 15046 / 22167. Bei
   Hardware-Offload laeuft der Host-Encoder nicht und diese Zaehler stehen
   still. Sie stehen nicht still.
2. **`PCM read counts` steigen** (924375 → 1061101 zwischen zwei Dumps) — der
   Host liest PCM und kodiert es selbst.
3. **`LDAC adaptive bit rate adjustments` steigen** (102 → 118 → 134). Der
   ABR-Regler laeuft im Host-Stack.
4. `A2dpOffloadEnabled: true` bzw. `mA2dpOffloadEnabled: true` ist eine
   **Faehigkeitsangabe des Adapters**, kein Zustand dieses Streams. Passend
   dazu listet `codecConfigOffloading` nur SBC, AAC und Opus — und diese drei
   jeweils mit `mCodecPriority:0`, `mSampleRate:0x0(NONE)`. **LDAC steht gar
   nicht in der Offload-Liste.**
5. `ro.bluetooth.a2dp_offload.supported`, `persist.bluetooth.a2dp_offload.disabled`
   und `persist.bluetooth.bluetooth_audio_hal.disabled` sind **nicht gesetzt**.

Damit gilt: **die Zaehler bedeuten etwas.** Sie beschreiben genau den
Encoder, der diesen Stream erzeugt. Das bestaetigt die Annahme, unter der
`baselines.md` gemessen wurde.

### 2.3 Die Kette bis zum Encoder (belegt)

| Stufe | Wert | Quelle |
|---|---|---|
| Quelle TIDAL | `AudioTrack`, `channelMask=0x3` (Stereo), **`sampleRate=48000`**, `FLAG_DEEP_BUFFER`, `isSpatialized=false` | `dumpsys audio`, piid 6647 |
| Mixer-Thread | `AudioOut_54D`, tid 11364, **type 7 (SPATIALIZER)**, `Standby: no` — der einzige aktive Output-Thread | `dumpsys media.audio_flinger` |
| Thread-Rate | **96000 Hz**, `AUDIO_FORMAT_PCM_32_BIT`, HAL frame count 2048, HAL buffer 16384 B | dito |
| Mixer-Kanaele | **`Mixer channel Mask: 0x3f`** (front-L/R, center, LFE, back-L/R = 5.1) | dito |
| Output-Flag | `AUDIO_OUTPUT_FLAG_SPATIALIZER` | dito |
| Senke | `Output devices: 0x80 (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)` | dito |
| Encoder | LDAC @ 96000 Hz / 32 bit | s. 2.1 |

**Das heisst: 48 kHz Stereo-Quelle → 5.1-Mix bei 96 kHz float →
Stereo-Downmix → LDAC bei 96 kHz.** Der Pfad rechnet die Quelle auf die
doppelte Abtastrate hoch, bevor er sie ueber Funk schickt.

### 2.4 Spatial Audio sitzt im Pfad — aber differenziert (belegt)

Dem Vorabblick des Directors ist nachgegangen. Der Befund ist zweigeteilt und
die Unterscheidung ist wichtig:

**Der Spatializer-*Thread* ist im Pfad:**
- `dumpsys audio`: `mHasSpatializerEffect:true`, `isSpatializerEnabled:true`,
  `mSpatLevel:1`, `mSpatOutput:1357`.
- Ereignisprotokoll: `09-01 20:56:29 Enabling Spatial Audio since enabled for
  media device:AudioDeviceAttributes: role:output type:bt_a2dp addr:…:37:8F`.
- Der aktive AudioFlinger-Thread ist `type 7 (SPATIALIZER)` mit I/O-Handle
  1357 — identisch mit `mSpatOutput`. Er allein hat `Standby: no`.
- Ein `PARTIAL_WAKE_LOCK 'AudioSpatial'` (uid 1041 = audioserver, WorkChain zu
  uid 10383 = TIDAL) wird gehalten.

**Der Spatializer-*Effekt* rechnet nicht:**
- Effect ID 435, `Decibel Spatializer Library` (Google Pixel), UUID
  `6507a0e2-…`: `Enabled: false`, `Should spatialize: false`, `State: IDLE`.
- `Bypassed: false`, `Processor: created`, `Input channel: 6, Output channel: 2`,
  `Version: 0.3.2`.
- Head-Tracking: `mDesiredHeadTrackingMode:HEAD_TRACKING_MODE_RELATIVE_WORLD`,
  aber **`mActualHeadTrackingMode:HEAD_TRACKING_MODE_DISABLED`** und
  `Received head tracking count: 0`. Head-Tracking ist gewuenscht, laeuft aber
  nicht — der Kopfhoerer liefert keine Sensordaten.
- Der Track selbst: `isSpatialized=false`.

**Konsequenz (plausibel, nicht isoliert gemessen):** Der Stereo-Inhalt wird
nicht binaural verrechnet, aber die *Existenz* des Spatializer-Ausgangs
erzwingt 96 kHz und einen 5.1-breiten Mixer fuer ein Stereo-48-kHz-Signal.
Der Anteil davon an der 96-kHz-LDAC-Aushandlung ist hier **nicht** durch ein
A/B belegt — dazu muesste Spatial Audio umgeschaltet werden, was in diesem
read-only-Lauf ausgeschlossen war.

### 2.5 Thread-Gesundheit (belegt)

| Groesse | Wert |
|---|---|
| Total writes | 66294 |
| **Delayed writes** | **5** |
| Timestamp stats | `n=66296 disc=6 cold=1 nRdy=0 err=0 rate=1.00012` |
| Threadloop write latency | ave **20.54 ms**, sd 4.62, min 10.29, **max 38.47** |
| Process time | ave 0.60 ms, sd 0.077, max 1.93 ms |
| Hal write jitter | ave −0.019 ms, sd 4.64, min −6.11, max 17.90 |
| Normal mixer raw underrun | Thread `AudioOut_D`: `partial=0 empty=24`; Spatializer-Thread: keine ausgewiesen |
| Blocked in write | yes (Normalzustand beim Schreiben) |
| Suspend count | 0 |

Die Werte sind kumulativ ueber die gesamte Sitzung (66294 Writes ≈ 22 min bei
20 ms). 5 verzoegerte Writes und 6 Timestamp-Diskontinuitaeten auf 66294
Schreibvorgaenge = 0,0075 % bzw. 0,009 %. `max 38.47 ms` deckt sich mit
`latMax` 37,7–39,0 ms aus `baselines.md` Block 1.

---

## 3. Die Zeitreihe — das Kernstueck

Drei Laeufe. Alle drei mit laufender Musik, deinstallierter App, WLAN aus.

| Lauf | Kadenz (gemessen) | Dauer | Samples | Zweck |
|---|---|---|---|---|
| A | 1459 ms (sd 46) | 144,4 s | 100 | Verlustzaehler + Schlangenlaenge, Auftragsvorgabe |
| B | 1443 ms (sd 55) | 101,0 s | 70 | zusaetzlich ABR-Stufe und Bitrate |
| C | **459 ms** (sd 45) | 73,1 s | 160 | Aufloesung fuer die ~3-s-Frage |

Angeforderte 1-s-Kadenz: `sleep 1` plus ~150 ms fuer den Dump plus
Pipe-Overhead ergibt real ~1,46 s. Lauf C wurde ohne `sleep` gefahren und
erreicht 459 ms; sein Duty-Cycle im BT-Prozess liegt bei ~33 % und damit
**unter** den 48 %, die `baselines.md` Block 1 (S4/S4b) bereits als
verlustneutral belegt hat.

### 3.1 Ergebnis Lauf A (Auftragsvorgabe)

| Zaehler | Delta ueber 144,4 s | pro Minute |
|---|---|---|
| enqueue | 7224 | 3001,3 |
| dequeue | 15046 | — |
| readbuf | 22167 | — |
| **underflow** | **0** | **0,00** |
| **dropped** | **0** | 0 |
| **dropouts** | **0** | 0 |
| **flushed** | **0** | 0 |
| max dropped | 0 | — |
| enqueue deviation overdue | 6210 | 2579,2 |
| enqueue deviation premature | 917 | 381,0 |
| dequeue deviation overdue | 4765 | 1979,2 |
| dequeue deviation premature | 10183 | 4229,8 |
| **LDAC saved transmit queue length** | konstant **0** in allen 100 Samples | — |

Enqueue-Rate 50,04/s (sd 1,43 = 2,9 %).

### 3.2 Ergebnis Lauf C (hohe Aufloesung)

Ueber 73,1 s bei 459 ms: `dUF=0`, `dDrop=0`, `dDropouts=0`, `dFlush=0`,
`SavedTxQueue` in **allen 160 Samples 0**. 6 ABR-Anpassungen.

**Autokorrelation** des Enqueue-Raten-Residuums (Signifikanzband
±2/√160 = ±0,159):

| Lag | Zeit | r |
|---|---|---|
| 1 | 0,46 s | −0,538 (Abtast-Quantisierungsartefakt) |
| 2 | 0,92 s | +0,213 |
| 6 | **2,76 s** | **−0,022** |
| 7 | **3,22 s** | **+0,008** |
| 13 | 5,97 s | −0,210 |

**Im Bereich um 3 s liegt die Autokorrelation bei r ≈ 0 — tief innerhalb des
Rauschbandes.** Das negative r bei Lag 1 ist der erwartete Artefakt der
Zaehlerquantisierung (ein Sample, das etwas mehr Zaehlerinkremente
einsammelt, laesst dem naechsten entsprechend weniger).

### 3.3 **Antwort auf die 3-s-Frage: kein periodisches Muster sichtbar**

Klar und ohne Einschraenkung fuer den gemessenen Zustand:

- **Die Verlustzaehler bewegen sich ueber 318 s kumulierter Messzeit in drei
  Laeufen kein einziges Mal.** Null Underflows, null Dropouts, null Drops,
  null Flushes.
- Die Sendeschlangenlaenge ist in 260 von 262 Samples 0 (zu den zwei
  Ausnahmen s. 3.4).
- Bei 459-ms-Aufloesung zeigt die Autokorrelation **keine Periodizitaet bei
  ~3 s**.

**Der entscheidende Kontext dazu:** Das Geraet faehrt gerade **nicht** die
Stufe, bei der T-005 das Stocken belegt hat. Es laeuft bei 492/660 kbps. Die
990er Stufe wird von ABR nie angesteuert (s. 3.4). Diese Aufnahme
**widerlegt den T-005-Befund also nicht** — sie zeigt, dass in dem Regime, in
das sich das Geraet selbst einregelt, keine Verluste auftreten. Das ist ein
vollwertiges Ergebnis, aber es ist eine Aussage ueber 492/660, nicht ueber 990.

### 3.4 Was die Zeitreihe stattdessen zeigt: eine Bitratenschaukel (belegt)

Das ist der eigentliche Fund dieses Laufs.

- Es existieren im laufenden Betrieb genau **zwei Stufen**:
  **660 kbps (ABR-Index 1)** und **492 kbps (ABR-Index 3)**.
- In Lauf B: 35 Samples auf 660, 35 Samples auf 492 — **50/50**.
- `LDAC adaptive bit rate adjustments` stieg 102 → 118 → 125 → 134. In Lauf B
  9 Anpassungen auf 101 s = **5,3/min, im Mittel alle 11,2 s**.
- **Die 909/990er Stufe wird nie erreicht.** Ueber alle Laeufe hinweg
  erschienen ausschliesslich die Werte 492 und 660.

**Verweildauern in Lauf B** (Samples zu ~1,44 s):

| Stufe | Verweildauern (Samples) | in Sekunden |
|---|---|---|
| 660 | 4, 20, 2, 9 | 5,8 / 28,8 / 2,9 / 13,0 |
| 492 | 11, 11, 11, 2 | **15,8 / 15,8 / 15,8** / 2,9 |

Die 492er-Phasen dauern dreimal **exakt 11 Samples**. Das ist kein Rauschen,
das ist ein **fester Haltetimer von ~16 s**, nach dem der Regler wieder nach
oben probiert. Die 660er-Phasen sind dagegen unregelmaessig — dort bleibt der
Regler, **bis die Strecke ihn herunterzwingt**.

**Und sie zwingt ihn herunter, mit sichtbarem Ausloeser:** die einzigen zwei
Samples mit `SavedTxQueue != 0` in der gesamten Aufnahme liegen genau an
Abstiegen.

| t [s] | Ereignis | SavedTxQueue |
|---|---|---|
| 69,11 | 660 → 492 | **3** |
| 97,95 | letztes Sample auf 660 vor dem Abstieg bei 99,46 | **2** |

Die anderen zwei Abstiege (5,70 s und 50,38 s) fielen zwischen die Samples —
bei 1,44 s Abtastung und einem Rueckstau, der offenbar nur Bruchteile einer
Sekunde besteht, ist das zu erwarten.

**Lesart (belegt fuer die Korrelation, plausibel fuer die Kausalitaet):** Bei
660 kbps staut sich die Sendeschlange, der ABR-Regler nimmt zurueck auf 492,
haelt dort ~16 s, probiert wieder 660 — und der Zyklus beginnt von vorn. Der
Encoder verliert dabei kein einziges Sample: er *gibt vorher nach*. Deshalb
sind alle Verlustzaehler null. **Der Qualitaetsverlust dieser Strecke steht
nicht in den Verlustzaehlern, er steht in der Bitrate.**

### 3.5 Volle Zeitreihe Lauf B

Zeitstempel relativ zum Start. `STUFE` markiert einen Stufenwechsel,
`SavedTxQueue` ist fett, wenn ungleich null.

| t [s] | kbps | ABR-Idx | ABR-Adj kum. | SavedTxQueue | Underflow kum. | Dropouts kum. | Frames/s |
|---|---|---|---|---|---|---|---|
| 0.00 | 660 | 1 | 125 | 0 | 529 | 0 | - |
| 1.39 | 660 | 1 | 125 | 0 | 529 | 0 | 565 |
| 2.76 | 660 | 1 | 125 | 0 | 529 | 0 | 587 |
| 4.22 | 660 | 1 | 125 | 0 | 529 | 0 | 584 |
| 5.70 | 492 **STUFE** | 3 | 128 | 0 | 529 | 0 | 587 |
| 7.16 | 492 | 3 | 128 | 0 | 529 | 0 | 557 |
| 8.60 | 492 | 3 | 128 | 0 | 529 | 0 | 622 |
| 10.18 | 492 | 3 | 128 | 0 | 529 | 0 | 523 |
| 11.60 | 492 | 3 | 128 | 0 | 529 | 0 | 601 |
| 13.03 | 492 | 3 | 128 | 0 | 529 | 0 | 540 |
| 14.38 | 492 | 3 | 128 | 0 | 529 | 0 | 625 |
| 15.89 | 492 | 3 | 128 | 0 | 529 | 0 | 590 |
| 17.44 | 492 | 3 | 128 | 0 | 529 | 0 | 555 |
| 18.90 | 492 | 3 | 128 | 0 | 529 | 0 | 557 |
| 20.34 | 492 | 3 | 128 | 0 | 529 | 0 | 598 |
| 21.83 | 660 **STUFE** | 1 | 129 | 0 | 529 | 0 | 628 |
| 23.32 | 660 | 1 | 129 | 0 | 529 | 0 | 624 |
| 24.68 | 660 | 1 | 129 | 0 | 529 | 0 | 677 |
| 26.04 | 660 | 1 | 129 | 0 | 529 | 0 | 676 |
| 27.43 | 660 | 1 | 129 | 0 | 529 | 0 | 717 |
| 28.93 | 660 | 1 | 129 | 0 | 529 | 0 | 677 |
| 30.42 | 660 | 1 | 129 | 0 | 529 | 0 | 660 |
| 31.89 | 660 | 1 | 129 | 0 | 529 | 0 | 678 |
| 33.33 | 660 | 1 | 129 | 0 | 529 | 0 | 673 |
| 34.76 | 660 | 1 | 129 | 0 | 529 | 0 | 678 |
| 36.23 | 660 | 1 | 129 | 0 | 529 | 0 | 633 |
| 37.55 | 660 | 1 | 129 | 0 | 529 | 0 | 714 |
| 38.99 | 660 | 1 | 129 | 0 | 529 | 0 | 673 |
| 40.43 | 660 | 1 | 129 | 0 | 529 | 0 | 665 |
| 41.84 | 660 | 1 | 129 | 0 | 529 | 0 | 674 |
| 43.26 | 660 | 1 | 129 | 0 | 529 | 0 | 683 |
| 44.69 | 660 | 1 | 129 | 0 | 529 | 0 | 672 |
| 46.12 | 660 | 1 | 129 | 0 | 529 | 0 | 662 |
| 47.52 | 660 | 1 | 129 | 0 | 529 | 0 | 689 |
| 48.96 | 660 | 1 | 129 | 0 | 529 | 0 | 676 |
| 50.38 | 492 **STUFE** | 3 | 130 | 0 | 529 | 0 | 598 |
| 51.80 | 492 | 3 | 130 | 0 | 529 | 0 | 587 |
| 53.24 | 492 | 3 | 130 | 0 | 529 | 0 | 555 |
| 54.64 | 492 | 3 | 130 | 0 | 529 | 0 | 580 |
| 56.06 | 492 | 3 | 130 | 0 | 529 | 0 | 576 |
| 57.48 | 492 | 3 | 130 | 0 | 529 | 0 | 571 |
| 58.88 | 492 | 3 | 130 | 0 | 529 | 0 | 583 |
| 60.36 | 492 | 3 | 130 | 0 | 529 | 0 | 584 |
| 61.86 | 492 | 3 | 130 | 0 | 529 | 0 | 586 |
| 63.36 | 492 | 3 | 130 | 0 | 529 | 0 | 486 |
| 64.58 | 492 | 3 | 130 | 0 | 529 | 0 | 665 |
| 66.08 | 660 **STUFE** | 1 | 131 | 0 | 529 | 0 | 583 |
| 67.60 | 660 | 1 | 131 | 0 | 529 | 0 | 572 |
| 69.11 | 492 **STUFE** | 3 | 132 | **3** | 529 | 0 | 578 |
| 70.63 | 492 | 3 | 132 | 0 | 529 | 0 | 562 |
| 72.11 | 492 | 3 | 132 | 0 | 529 | 0 | 586 |
| 73.63 | 492 | 3 | 132 | 0 | 529 | 0 | 572 |
| 75.12 | 492 | 3 | 132 | 0 | 529 | 0 | 579 |
| 76.64 | 492 | 3 | 132 | 0 | 529 | 0 | 562 |
| 78.10 | 492 | 3 | 132 | 0 | 529 | 0 | 599 |
| 79.65 | 492 | 3 | 132 | 0 | 529 | 0 | 564 |
| 81.18 | 492 | 3 | 132 | 0 | 529 | 0 | 577 |
| 82.72 | 492 | 3 | 132 | 0 | 529 | 0 | 587 |
| 84.31 | 492 | 3 | 132 | 0 | 529 | 0 | 563 |
| 85.84 | 660 **STUFE** | 1 | 133 | 0 | 529 | 0 | 634 |
| 87.38 | 660 | 1 | 133 | 0 | 529 | 0 | 666 |
| 88.87 | 660 | 1 | 133 | 0 | 529 | 0 | 675 |
| 90.40 | 660 | 1 | 133 | 0 | 529 | 0 | 672 |
| 91.91 | 660 | 1 | 133 | 0 | 529 | 0 | 690 |
| 93.44 | 660 | 1 | 133 | 0 | 529 | 0 | 643 |
| 94.92 | 660 | 1 | 133 | 0 | 529 | 0 | 695 |
| 96.44 | 660 | 1 | 133 | 0 | 529 | 0 | 669 |
| 97.95 | 660 | 1 | 133 | **2** | 529 | 0 | 673 |
| 99.46 | 492 **STUFE** | 3 | 134 | 0 | 529 | 0 | 569 |
| 100.97 | 492 | 3 | 134 | 0 | 529 | 0 | 579 |

---

## 4. Konfiguration

### 4.1 Settings (belegt)

| Schluessel | Wert | Bewertung |
|---|---|---|
| `global.bluetooth_on` | 1 | — |
| `global.bluetooth_disabled_profiles` | 0 | kein Profil deaktiviert |
| **`global.wifi_on`** | **0** | **WLAN ist aus** |
| `global.wifi_scan_always_enabled` | **1** | scannt trotz ausgeschaltetem WLAN |
| `global.ble_scan_always_enabled` | **1** | — |
| `global.ble_scan_low_power_interval_ms` / `_window_ms` | 1400 / 140 | Duty 10 % |
| `global.ble_scan_balanced_interval_ms` / `_window_ms` | 730 / 183 | **Duty 25 %** |
| `global.low_power` | 0 | kein Akkusparmodus |
| `global.airplane_mode_on` | 0 | — |
| `global.app_standby_enabled` | 1 | — |
| `secure.adaptive_connectivity_enabled` | 0 | — |
| `secure.bluetooth_automatic_turn_on` | 1 | — |
| `system.volume_music_bt_a2dp` | 20 | von 25 |

**Codec-Pinning: nicht gesetzt.** Es existiert in keinem der drei Namespaces
ein Schluessel, der Codec, Samplerate oder LDAC-Qualitaet pinnt
(`settings list global|secure|system` gefiltert auf
`bluetooth|bt_|a2dp|ldac|codec|scan|absolute`). Das deckt sich mit
`LDAC quality mode : ABR`. Die Entwickleroptionen stehen auf Werkszustand.

### 4.2 Properties (belegt)

Gesetzt und relevant:
- `bluetooth.profile.a2dp.source.enabled = true`
- `bluetooth.core.le.dsa_transport_preference = iso-sw,le-acl`
- `bluetooth.core.le_audio.codec_extension_aidl.enabled = true`
- `ro.bluetooth.leaudio_switcher.supported = true`,
  `ro.bluetooth.leaudio_broadcast_switcher.supported = true`
- `bluetooth.sco.managed_by_audio = true`

**Nicht gesetzt** (explizit abgefragt, leere Rueckgabe):
`ro.bluetooth.a2dp_offload.supported`, `persist.bluetooth.a2dp_offload.disabled`,
`persist.bluetooth.bluetooth_audio_hal.disabled`,
`persist.bluetooth.disableabsvol`, `bluetooth.a2dp_offload.supported`,
`ro.vendor.bluetooth.soc`, `persist.vendor.btstack.enable.splita2dp`,
`ro.bluetooth.emb_wp_mode`.

Kein Vendor-Property zwingt oder verbietet Offload — der Stack entscheidet
selbst, und er entscheidet fuer Host-Encoding (s. 2.2).

### 4.3 Absolute Volume (belegt)

- `mAvrcpAbsVolSupported: true`; der Kopfhoerer meldet
  `avrcpSupportsAbsoluteVolume … support=true`.
- `Absolute volume devices with their volume driving streams:` enthaelt
  `Device type: 0x80, driving stream 3` — A2DP haengt an STREAM_MUSIC.
- `pre-scale for bluetooth absolute volume = disabled`.
- `setAvrcpVolume: index:20`, `STREAM_MUSIC streamVolume:20 / Max: 25`,
  `Muted: false`.
- Hoerschutz aktiv: `mEnableCsd=true`, `mCurrentCsd=0.0`. Die letzte
  CSD-Attenuation-Meldung (18:19:52) lautet **0.00 dB** — es wird aktuell
  nichts abgesenkt. (Frueher am Tag standen dort 8,50 dB und 6,80 dB.)

### 4.4 LE Audio (belegt)

**Nicht aktiv.** `currentlyActiveGroupId: -1`, `mActiveAudioOutDevice: null`,
`mActiveAudioInDevice: null`, `isDualModeAudioEnabled: false`,
`mExposedActiveDevice: null`. Letzte Native-Meldung
`groupId: -1, status: Inactive`. Ebenso `Offload start pending handle: 0`,
`Offload started handle: 0`. Der Stream ist klassisches A2DP.

---

## 5. Was gegen die Uebertragung arbeitet

Geordnet nach Staerke des Verdachts.

### 5.1 Drei permanente, aktive BLE-Scans von GMS — **belegt**

`dumpsys bluetooth_manager`, Abschnitt `com.google.android.gms (Registered)`:

| Tag | Scan-Modus | Typ | Laufzeit bei Aufnahme | Ergebnisse |
|---|---|---|---|---|
| `nearby_fast_pair` | BALANCED | **ACTIVE** | 1 653 389 ms (27,6 min) | 271 |
| `nearby_sharing` | AMBIENT_DISCOVERY | **ACTIVE** | 1 651 440 ms | 0 |
| `nearby_connections` | AMBIENT_DISCOVERY | **ACTIVE** | 1 657 452 ms | 68 |

Alle drei sind mit `(Forced)` markiert und laufen **gleichzeitig und
ununterbrochen**. `MATCH_MODE AGGRESSIVE`, `CALLBACK_TYPE ALL_MATCHES`,
`RESULT_TYPE FULL`, `PHY 1M`, `RSSI -128` (kein Schwellwert-Filter).

Warum das relevant ist: **`SCAN_TYPE ACTIVE` heisst, das Funkteil sendet**
SCAN_REQ-Pakete, es hoert nicht nur zu. Damit belegt es dieselben
2,4-GHz-Kanaele wie der A2DP-Link, und zwar sendend.

Kumulativ ueber die Adapter-Laufzeit (14 h 21 m = 51 710 s):

```
LE scans (Started/Stopped)      : 575 / 572
Scan time (Active/Total)        : 27 851 879 ms
Scan time per mode (ms)
  Opp/LowPower/Balanced/LowLat/AmbientDiscovery
  = 0 / 1 107 706 / 9 115 901 / 799 118 / 16 829 154
Scan mode counter               : 0 / 2 / 202 / 186 / 185
Number of results (Off/On/Total): 4 / 6295 / 6299
```

27 852 s Scanzeit auf 51 710 s Adapter-Uptime. **Achtung bei der Deutung:**
die Zeit wird pro Scanner summiert, drei gleichzeitige Scanner zaehlen
dreifach. Die Zahl ist also **kein** 54-%-Duty-Cycle des Radios. Belegt ist
aber: **zum Zeitpunkt der Aufnahme liefen drei aktive Scans gleichzeitig,
seit ueber 27 Minuten ununterbrochen.**

Dass GMS dabei `Current Consumption Severities: NORMAL` meldet, ist eine
Aussage ueber Akkuverbrauch, nicht ueber Funkkoexistenz.

**Evidenz: belegt** fuer Existenz, Modus, Dauer und Sendecharakter der Scans.
**Plausibel**, nicht belegt, ist der kausale Beitrag zur Bitratenschaukel aus
3.4 — dazu waere ein A/B mit abgeschalteten Scans noetig, was read-only nicht
zulaesst.

### 5.2 Dichte 2,4-GHz-Nachbarschaft — **belegt**

`BluetoothRemoteDevices` fuehrt neben 3 gekoppelten Geraeten **`Other
devices: 56`** — 56 in Reichweite gesehene BLE-Geraete (ueberwiegend
Ohrhoerer, die aktiv annoncieren). Das ist die Umgebung, in der die drei
aktiven Scans 271 bzw. 68 Treffer eingesammelt haben.

### 5.3 WLAN — **als Stoerer fuer diese Aufnahme ausgeschlossen (belegt)**

`dumpsys wifi`: **`Wi-Fi is disabled`**, `WifiState 0`, `WifiStateApm false`,
`WifiStateBt false`, `WifiStateUser 0`. Alle `txLinkSpeedCount*`-Histogramme
(2g, 5gLow/Mid/High, 6gLow/Mid/High) sind leer. `settings get global wifi_on`
= 0.

**Das ist ein wichtiges Negativergebnis.** Die frueher beobachtete
Wi-Fi-7-MLO-Konstellation (2,4 GHz Kanal 6 **und** 5 GHz Kanal 100 am selben
AP) war zum Aufnahmezeitpunkt **nicht aktiv** — und der Link schaukelt
trotzdem zwischen 492 und 660. **Wi-Fi-Koexistenz kann die Bitratenschaukel
aus 3.4 also nicht erklaeren.** Band, Frequenz, Standard, MLO-Links, RSSI und
Link-Speed konnten folgerichtig nicht erhoben werden: cannot check — Radio
aus.

Zu beachten bleibt: `wifi_scan_always_enabled = 1`. Scannen kann trotz
ausgeschaltetem WLAN stattfinden; ob im Aufnahmefenster tatsaechlich
2,4-GHz-Scans liefen, ist aus dem Dump **nicht** ablesbar (cannot check).

### 5.4 Thermik — **leichtes Throttling aktiv (belegt)**

`dumpsys thermalservice`: **`Thermal Status: 1`** (= `THROTTLING_LIGHT`).

| Sensor | Wert | Status |
|---|---|---|
| `soc_therm` | 43,97 °C | **1** |
| `quiet_therm` | 36,84 °C | **1** |
| `north_therm` | 34,60 °C | **1** |
| `VIRTUAL-SKIN-CHARGE-WIRED` | 36,39 °C | **1** |
| `BG-TASKS-THROTTLING-HINT` | 3,0 | **3** |
| `BIG` (CPU-Cluster) | 86,0 °C | 0 |
| `MID` / `MIDLL` | 72,0 / 70,0 °C | 0 |
| `GPU` | 52,0 °C | 0 |
| `battery` | 35,5 °C | 0 |
| `charging_therm` | 41,48 °C | 0 |

Das Geraet laedt am Kabel und ist dabei leicht thermisch gedrosselt.
`BG-TASKS-THROTTLING-HINT` auf Severity 3 bedeutet, dass das System
Hintergrundarbeit aktiv zurueckdraengt.

**Evidenz: belegt** fuer den Zustand. **Spekulativ**, ob das den Encoder
beeinflusst — die Encoder-Scheduling-Zaehler zeigen keine Auffaelligkeit, und
Status 1 ist die niedrigste Drosselstufe.

### 5.5 Energiesparmechanismen — **als Ursache ausgeschlossen (belegt)**

| Pruefung | Ergebnis |
|---|---|
| `dumpsys deviceidle` | `mState=ACTIVE mLightState=ACTIVE`, `mForceIdle=false`, `mCharging=true`, `mScreenOn=true` |
| Akkusparmodus | `mSettingBatterySaverEnabled=false`, `global.low_power=0` |
| Standby-Bucket TIDAL | **5** (EXEMPTED) |
| Standby-Bucket GMS | **5** (EXEMPTED) |
| Doze-Whitelist | `user,com.aspiro.tidal,10383` — vom Nutzer von der Akkuoptimierung ausgenommen |
| Doze-Whitelist GMS | `system-excidle,com.google.android.gms,10309` + `system,…` |
| `appops RUN_ANY_IN_BACKGROUND` (TIDAL) | `allow` |
| Wakefulness | `mWakefulness=Awake`, Display haelt seit 20:56:11 |

**Kein Doze, kein App-Standby, kein Akkusparmodus, keine Einschraenkung der
Audio-App.** Die Akku-/Standby-Einstellungen von TIDAL sind bereits optimal
gesetzt — hier ist nichts zu gewinnen.

### 5.6 Vordergrunddienste und Wakelocks — **belegt**

Genau **ein** Vordergrunddienst laeuft: TIDAL,
`foregroundId=100 types=0x00000002` (mediaPlayback),
`channel=tidal_now_playing_notification_channel`.

Gehaltene Wakelocks (5):

| Wakelock | uid | gehalten seit | Bemerkung |
|---|---|---|---|
| `ExoPlayer:WakeLockManager` | 10383 (TIDAL) | 29 m 32 s | erwartbar |
| `bluetooth_timer` | 1002 (bluetooth) | 29 m 31 s | erwartbar |
| `AudioSpatial` | 1041 (audioserver) | 1 m 3 s | **Spatializer im Pfad**, WorkChain zu TIDAL |
| `com.facebook.react.HeadlessJsTaskService` | 10353 | — | **DISABLED**, `isFrozen=true` — inaktiv |
| `*alarm*` | 1000 | 242 ms | transient |

Nichts Auffaelliges ausser `AudioSpatial`, das 2.4 bestaetigt.

### 5.7 CPU waehrend der Wiedergabe — **belegt**

Aus `/proc/<pid>/stat` (utime+stime), Fenster 20,54 s, `CLK_TCK=100` —
procfs, beruehrt den BT-Stack nicht (Stufe 1 der Methodik aus `baselines.md`):

| Prozess | Ticks | Anteil eines Kerns |
|---|---|---|
| `com.google.android.bluetooth` | 306 | **14,9 %** |
| `com.aspiro.tidal` | 150 | 7,3 % |
| `/system/bin/audioserver` | 87 | 4,2 % |
| `com.android.systemui` | 26 | 1,3 % |

Die 14,9 % fuer den BT-Prozess liegen im Bereich der Referenz aus
`baselines.md` (16,8–16,9 % ohne Dumpsys-Last, Streuung ±2,0 %) — leicht
darunter, also unauffaellig.

### 5.8 Verbundene Geraete und Profile — **belegt**

`ConnectionState: STATE_CONNECTED`, `MaxConnectedAudioDevices: 5`,
`Discovering: false`. Gekoppelt sind 3 Geraete, **verbunden ist genau eines**:

| Rolle | ACL | Zustand |
|---|---|---|
| Kopfhoerer A (`…:37:8F`, DUAL) | **BR/EDR: Y**, LE: N | **aktiv**, A2DP + AVRCP, LDAC |
| Lautsprecher (`…:C0:D7`, BR/EDR) | N | `STATE_DISCONNECTED` seit 09-01 13:02:30 |
| Kopfhoerer B (`…:35:6A`, DUAL) | N | nicht verbunden |

Kopfhoerer A ist verschluesselt mit `keySize=16`. Der A2DP-Stream ist der
einzige aktive Audio-Link — **kein zweites Audiogeraet konkurriert**.

Ein Verbindungsabbruch steht im Protokoll: `Connection Events: 09-01
07:03:52.529 DISCONNECTED …:37:8f reason=22`. Das war heute frueh, nicht im
Aufnahmefenster.

---

## 6. Auffaelligkeit gegenueber dem hinterlegten Budget

`baselines.md` fuehrt fuer das Szenario "LDAC-Wiedergabe, Pixel 11 Pro" eine
Signifikanzschwelle von **±1,03 %** auf `Enqueue-Deviation overdue/min`
(Mittel 2517,3, sd 12,9).

| Groesse | Budget-Mittel | Lauf A | Abweichung | Schwelle |
|---|---|---|---|---|
| Enqueue-Rate /min | 3015,7 | 3001,3 | −0,48 % | ±1,61 % — **im Rahmen** |
| Enqueue-Deviation overdue /min | 2517,3 | **2579,2** | **+2,46 %** | ±1,03 % — **ueberschritten (≈4,8 sd)** |
| Dequeue-Deviation overdue /min | 1883,6 | 1979,2 | **+5,08 %** | ±1,80 % — **ueberschritten (≈5,7 sd)** |
| CPU `bluetooth` | 16,9 % | 14,9 % | −2,0 Punkte | ±2,0 % — Grenzfall |

**Das ist als moegliche Regression zu melden, nicht als gesicherte.** Dagegen
spricht nichts an den Zahlen; dafuer, es vorsichtig zu lesen, sprechen drei
Konfundierungen:

1. **Andere Messkadenz.** Block 1 nahm je Lauf genau 2 `bluetooth_manager`-
   Dumps (Anfang/Ende) und subtrahierte. Lauf A nimmt 100. Der Duty-Cycle im
   BT-Prozess ist damit ~10 % statt ~0 %.
2. **Die Richtung passt nicht zum bekannten Beobachtereffekt.** Block 1 hat
   belegt, dass *mehr* Dumpsys-Last die Overdue-Zaehler **senkt** (S4: −5,9 %).
   Hier steigen sie trotz hoeherer Last. Das macht den Befund eher
   interessanter als erklaerbar.
3. **Anderer ABR-Zustand.** Lauf A lief durch beide Stufen (492/660). Welche
   Stufenverteilung die Block-1-Laeufe hatten, ist dort nicht protokolliert —
   `baselines.md` nennt nur die Spanne 492–660 kbps.

Die Enqueue-Rate ist mit −0,48 % stabil, die Zaehler sind also nicht durch
eine veraenderte Grundrate verzerrt. **Empfehlung: der `director` entscheidet,
ob dieser Punkt einen eigenen Vergleichslauf mit Block-1-Methodik (2 Dumps,
180 s) bekommt, bevor irgendetwas optimiert wird.**

---

## 7. Cluster 4 — Sicherheitsfragen aus dem Review

Beide read-only geprueft. **Beide Befunde sind schlechter als im Review
angenommen.**

### SR-009 — `/data/local/tmp/btdash_helper.log`

```
stat /data/local/tmp/btdash_helper.log
  Size: 11837   regular file
  Access: (0666/-rw-rw-rw-)  Uid: (2000/shell)  Gid: (2000/shell)
  Modify: 2026-08-30 19:41:03
```

- **Die Datei existiert weiterhin — sie hat die Deinstallation ueberlebt.**
- Der Modus ist **0666**, nicht 0644. Sie ist nicht nur welt-**lesbar**,
  sondern **welt-schreibbar**.
- Die Auflage des Reviews lautete: bei 0644 ist der Befund hochzustufen.
  0666 ist strikt schlimmer — **Hochstufung ist faellig**, und zusaetzlich
  kommt eine Integritaetsfrage dazu, die im Review noch nicht adressiert war:
  jede App im System kann diese Logdatei **manipulieren**, nicht nur mitlesen.

### SR-001 — Spill-Dateien in `/data/local/tmp`

```
drwxrwx--x  4 shell shell 233472 2026-08-30 19:36 .
-rw-rw-rw-  1 shell shell 118487 2026-08-30 19:41 btdash_exec_current.out
-rw-rw-rw-  1 shell shell  11837 2026-08-30 19:41 btdash_helper.log
drwxrwxrwx 15 shell shell   3452 2026-08-30 19:43 btperf
```

- **Ja, Reste ueberleben die Deinstallation.** `btdash_exec_current.out`
  (118 487 Byte) liegt weiterhin da, ebenso das Verzeichnis `btperf`.
- Rechte: **0666** fuer beide Dateien, **0777** fuer das Verzeichnis
  `btperf`. Eigentuemer durchgehend `shell:shell`.
- Damit ist die Kernfrage aus T-006 beantwortet: **die Deinstallation raeumt
  nicht auf.** Der Inhalt (Geraetenamen, BT-MACs aus `dumpsys`-Ausgaben)
  bleibt fuer jede App auf dem Geraet lesbar liegen, zeitlich unbegrenzt.
- Kein `btdash_exec_*`-Muster ausser `btdash_exec_current.out` gefunden.

Nicht geprueft: der Inhalt der Dateien und von `btperf` wurde **nicht**
gelesen — nicht noetig fuer die Rechtefrage und ausserhalb des Auftrags.

---

## 8. Was diese Aufnahme nicht beantworten konnte

Nach GOAL.md AK-3 — kein falscher Freispruch.

| Frage | Warum offen |
|---|---|
| **Zeigt sich der ~3-s-Takt bei 990 kbps?** | **Nicht pruefbar gewesen.** ABR faehrt nur 492/660; 990 wird nie angesteuert. Es dort hinzuzwingen haette Pinning erfordert = Schreibzugriff = read-only-Grenze. Die Aussage aus 3.3 gilt **nur** fuer 492/660. |
| **Verursachen die BLE-Scans die Bitratenschaukel?** | Korrelation nicht pruefbar ohne A/B. Scans abschalten ist ein Schreibzugriff. Bleibt **plausibel**, nicht belegt. |
| **Zwingt Spatial Audio die 96-kHz-Aushandlung?** | Waere nur durch Umschalten von Spatial Audio zu belegen — Schreibzugriff. Der 96-kHz-Thread und die 96-kHz-LDAC-Config sind belegt, der **Kausalzusammenhang** ist es nicht. |
| **Wieviel bringt 48 kHz statt 96 kHz?** | Nicht gemessen. `state.md` haelt fest, dass 48 kHz den Funk nicht entlastet (gleiche Bitratenleiter). Ob es die *Qualitaet pro Bit* hebt, ist hier **nicht** gemessen worden. |
| **WLAN-Band/Frequenz/MLO/RSSI/Link-Speed** | **cannot check** — `Wi-Fi is disabled`. Alle Link-Histogramme leer. |
| **Laufen trotz `wifi_on=0` 2,4-GHz-Scans im Aufnahmefenster?** | **cannot check** — `wifi_scan_always_enabled=1` ist gesetzt, aber der Dump weist keine Scan-Ereignisse mit Zeitstempel im Fenster aus. |
| **Ist die Abweichung aus Abschnitt 6 eine echte Regression?** | Nicht entscheidbar: Messkadenz und ABR-Stufenverteilung unterscheiden sich von Block 1. Braucht einen Lauf mit Block-1-Methodik. |
| **RSSI / Link-Qualitaet des A2DP-Links** | **cannot check** — kein RSSI fuer die aktive ACL-Verbindung im Dump; `hcitool`/`btmgmt` sind auf diesem Android nicht vorhanden, HCI-Snoop war laut Auftrag tabu. |
| **Thread-Prioritaet des Encoder-Threads** | Teilweise. `/proc/11364/sched` wurde ueber den AudioFlinger-Dump nur als `se.*`-Auszug sichtbar; ein expliziter `nice`/`rt_priority`-Wert fuer den `btif_a2dp_source`-Thread wurde **nicht** erhoben. |
| **Underflow-Herkunft der 527/529 kumulierten Ereignisse** | Sie stammen aus der Zeit **vor** dem Aufnahmefenster (`Last update time ago in ms (underflow) : 31851` beim ersten Dump). Zwischen Lauf A und Lauf B stiegen sie um 2 — dieses Ereignis fiel in keine der drei Messreihen. Ursache unbekannt. |
| **`AudioOut_D`: `Normal mixer raw underrun: empty=24`** | Dieser Thread ist in Standby und **nicht** der A2DP-Pfad. Ob die 24 Ereignisse jemals den A2DP-Stream betrafen, ist aus dem Dump nicht ableitbar. |

---

## 9. Rohdaten

Ausserhalb des Repos, wie vom Director angeordnet (enthalten MACs,
Geraetenamen):

`C:\Users\Daniel\AppData\Local\Temp\claude\C--Users-Daniel-Desktop-ClaudeCode\4712ed34-df5d-47e2-9f5b-48a5579d4f68\scratchpad\`

| Datei | Inhalt |
|---|---|
| `raw_bluetooth_manager_pre.txt` | voller Dump vor Lauf A |
| `raw_bluetooth_manager_post.txt` | voller Dump nach Lauf A |
| `raw_audio_flinger.txt` | `dumpsys media.audio_flinger` |
| `raw_audio.txt` | `dumpsys audio` |
| `raw_wifi.txt` | `dumpsys wifi` |
| `raw_thermal.txt` | `dumpsys thermalservice` |
| `raw_deviceidle.txt` | `dumpsys deviceidle` |
| `raw_power.txt` | `dumpsys power` |
| `raw_services.txt` | `dumpsys activity services` |
| `cfg_settings.txt` | Settings aller drei Namespaces |
| `cfg_getprop.txt` | gefilterte Properties |
| `cpu_sample.txt` | procfs-CPU-Fenster |
| `timeseries_a2dp.txt` | Lauf A, 100 Samples |
| `timeseries_ldac_abr.txt` | Lauf B, 70 Samples |
| `timeseries_fast.txt` | Lauf C, 160 Samples |
| `parse_ts.py`, `parse_abr.py` | Auswerteskripte |
| `table_abr.md` | erzeugte Tabelle aus 3.5 |

---

## 10. Eingriffsbilanz

Die Aufnahme war strikt read-only. Ausgefuehrt wurden ausschliesslich:
`adb devices`, `getprop`, `settings get`, `settings list`, `pm list packages`,
`cmd package list packages`, `dumpsys` (bluetooth_manager, audio,
media.audio_flinger, media_session, wifi, thermalservice, deviceidle, power,
activity services, usagestats), `am get-standby-bucket`, `cmd appops get`,
`ls`, `stat`, `id`, `ps`, `cat /proc/<pid>/stat`, `cat /proc/<pid>/cmdline`,
`date`, `getconf`.

**Kein `settings put`, kein `setprop`, kein schreibendes `cmd bluetooth`, kein
Pinning, kein Codec- oder Bitratenwechsel, kein Dienstneustart, kein
HCI-Snoop, keine Installation, keine Datei auf dem Geraet angelegt oder
veraendert.** Der AK-1-Referenzarm ist unversehrt.
