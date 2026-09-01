# T-008 — Drei Eingriffsexperimente

Fortsetzung von T-007, gleicher Agent, gleiche Skripte, gleiche Kadenz.

**Stand:** **E-2 ist vollstaendig** — A0 → B → A' gefahren, mit Hoereindruck
in allen drei Armen. E-1 und E-3 bleiben mangels Hebel offen.

Schwaerzung wie in T-007: MACs gekuerzt, Geraetenamen nur als Rolle.

---

## 0. Das wichtigste Einzelergebnis: der Kalibrierpunkt der Hoerbarkeitsgrenze

`UI_SPEC.md` fordert im Abschnitt „Die Hoerbarkeitsgrenze" einen Bezugspunkt
zwischen gemessenen Zaehlern und tatsaechlicher Wahrnehmung. Der liegt jetzt
zum ersten Mal vor — aus drei Armen mit Hoereindruck des App Designers:

| Arm | Zustand | **Dropouts (gemessen)** | **Hoereindruck** |
|---|---|---|---|
| A0 | ABR, 492/660 | **0** / 97,6 s | keine Aussetzer |
| **B** | **990 gepinnt** | **21** / 97,0 s = **12,99/min** | **„hoere nach wie vor Aussetzer"** — durchgehend |
| A' | ABR zurueck | **0** / 97,8 s | **„keine Aussetzer mehr"** |

**Zaehler und Ohr stimmen ueberein, in beide Richtungen.** Der Uebergang ist
in beiden Richtungen sauber und wurde in derselben Sitzung, am selben Geraet,
mit demselben Hoerer und demselben Musikmaterial belegt.

Was daraus **belegt** ist:

- **~13 Dropouts/min (≈ alle 4,6 s) sind deutlich hoerbar.**
- **0 Dropouts/min sind unhoerbar.**

Was daraus **nicht** folgt: wo genau zwischen 0 und 13/min die Schwelle
liegt. Wir haben zwei Punkte, keine Kurve. Eine Anzeige darf daraus **nicht**
ableiten, dass z. B. 3/min hoerbar oder unhoerbar sind — das ist ungemessen.

**Zweiter, davon unabhaengiger Kalibrierpunkt:** Die Verlustzaehler des
Stacks sind fuer die Hoerbarkeit **die richtige Groesse**. Die
`Counts (underflow)` blieben in **allen** Armen bei 0 — auch im hoerbar
gestoerten Arm B. Wer Aussetzer anzeigen will, muss
**`Counts (dropped/dropouts)`** lesen, nicht `underflow`. Eine Anzeige, die
nur Underflows zeigt, haette den hoerbar kaputten Arm B als einwandfrei
gemeldet.

---

## 1. Phasenprotokoll

Geraetezeit laeuft **88 s vor** der Hostzeit. Der App Designer hoert am
Geraet mit, deshalb ist die **Geraetezeit** massgeblich.

| Phase | Host | **Geraet** | Zustand | Hoereindruck |
|---|---|---|---|---|
| Zustandsbuch | 21:52 | **21:53** | ABR | — |
| **Arm A0 — Referenz** | 21:52:36–21:54:15 | **21:54:04–21:55:44** | ABR, 492/660 | keine Aussetzer |
| E-1 Hebelversuch (`appops`) | ~21:56 | **~21:57** | ohne Wirkung | — |
| Zustandsabgleich | 21:59 | **22:01** | identisch mit Buch | — |
| *App Designer pinnt 990 von Hand* | — | **zw. 22:01 und 22:07** | ABR → HIGH | — |
| Read-back Pin | 22:05:38 | **22:07:06** | **990 bestaetigt** | — |
| **Arm B — Standard** | 22:06:00–22:07:39 | **22:07:28–22:09:07** | **990 gepinnt** | **Aussetzer** |
| **Arm B — schnell** | 22:07:50–22:08:51 | **22:09:19–22:10:20** | **990 gepinnt** | **Aussetzer** |
| *Pin blieb stehen (Warten auf Hoereindruck)* | — | **bis ~22:20** | 990 | **Aussetzer** |
| Stichprobe Director | — | **22:19:30** | 990 | — |
| *App Designer stellt auf adaptiv zurueck* | — | **zw. 22:19:30 und 22:21:42** | HIGH → ABR | — |
| Read-back Rueckflip | 22:20:14 | **22:21:42** | **ABR bestaetigt** (mit Vorbehalt, s. 6) | — |
| **Arm A' — Rueckkehr** | 22:20:42–22:22:21 | **22:22:10–22:23:50** | ABR | **keine Aussetzer** |
| Endzustand | 22:23:22 | **22:24:50** | ABR, 492 | — |

---

## 2. Read-backs

**Pin gesetzt (22:07:06)** — vier unabhaengige Belege:

| Feld | A0 | gepinnt |
|---|---|---|
| `LDAC quality mode` | `ABR` | **`HIGH`** |
| `LDAC transmission bitrate` | 492/660 wechselnd | **990 konstant** |
| `LDAC adaptive bit rate …` | vorhanden | **Felder verschwunden** — Regler aus |
| `Priority` (LDAC) | 5001 | **1000000** — Pin-Marker |

Der Pin hielt ueber beide B-Arme: in **allen 230 Samples** stand 990.

**Pin geloest (22:21:42)** — teilweise:

| Feld | Soll (A0) | nach Rueckflip | |
|---|---|---|---|
| `LDAC quality mode` | `ABR` | **`ABR`** | OK |
| `LDAC adaptive bit rate` Felder | vorhanden | **vorhanden** (idx 1, adj 11) | OK |
| Bitrate | wechselnd | **wechselnd** | OK |
| **`Priority` (LDAC)** | **5001** | **1000000** | **ABWEICHUNG — s. 6** |

Der funktional relevante Schalter (Quality Mode) ist zurueck; ABR regelt
wieder. Arm A' ist damit gueltig.

---

## 3. Ergebnis E-2: A0 → B → A'

| Groesse | **A0 (ABR)** | **B (990 gepinnt)** | **B schnell (990)** | **A' (ABR zurueck)** |
|---|---|---|---|---|
| Kadenz | 1415 ± 33 ms | 1407 ± 32 ms | 379 ± 20 ms | 1417 ± 20 ms |
| Dauer | 97,6 s | 97,0 s | 60,3 s | 97,8 s |
| Stufe | 492/660 | **990 konstant** | **990 konstant** | 396/492/660/990 → 492/660 |
| Stufenwechsel | 6 (3,69/min) | **0** | **0** | 8 (4,91/min) |
| **Dropped packets** | **0** | **525** (324,7/min) | **325** (323,4/min) | **0** |
| **Dropouts** | **0** | **21** (12,99/min) | **13** (12,94/min) | **0** |
| Underflows | 0 | **0** | **0** | 0 |
| Flushes | 0 | 0 | 0 | 0 |
| `SavedTxQueue` ≠ 0 | 1/70 (Wert 2) | **55/70 (79 %)** | **129/160 (81 %)** | **0/70 (0 %)** |
| `SavedTxQueue` Mittel / Max | ~0 | **7,69 / 24** | **7,69 / 23** | **0 / 0** |
| Enqueue-Rate | 2999,0/min | 3001,5/min | 3000,0/min | 3001,2/min |
| **Hoereindruck** | keine Aussetzer | **Aussetzer** | **Aussetzer** | **keine Aussetzer** |

**Das A/B/A schliesst sauber.** Die Verluste gehen von 0 auf 525 und wieder
auf **exakt 0** zurueck. Die Sendeschlange ist in A' sogar **in allen 70
Samples leer** — sauberer als in A0, das ein Sample mit Wert 2 hatte.

Die **Enqueue-Rate ist ueber alle vier Arme konstant** (2999,0 – 3001,5/min,
Spanne 0,08 %). Der Vergleich ist damit nicht durch eine veraenderte
Grundrate verzerrt: der Encoder liefert in jedem Arm gleich viel, nur die
Strecke traegt es unterschiedlich.

### 3.1 Mechanismus: Warteschlangenueberlauf, nicht Quellenmangel

- **`Counts (underflow)` bleibt in allen Armen 0.** Der Encoder bekommt immer
  genug PCM. Es ist **kein** Zuliefer-Problem.
- **`Counts (dropped)` steigt im 990er-Arm auf 324/min.** Die
  Sendewarteschlange laeuft ueber, weil der Funk nicht schnell genug abholt.
- `Counts (max dropped): 26` — die Verluste kommen in **Buendeln bis 26
  Paketen**, nicht als gleichmaessiges Rieseln. Das passt zum Hoereindruck
  „Aussetzer" statt „Rauschen".
- `Dequeue overdue scheduling time … max` steigt von **26 ms** (T-007) auf
  **62 ms**.
- LDAC selbst meldet `Packet counts (expected/dropped): 78260 / 0` — **der
  Encoder arbeitet fehlerfrei**. Verworfen wird erst danach, in der TxQueue.

Das deckt sich exakt mit dem R-001-Mechanismus: ABR regelt anhand der
Sendeschlangentiefe. Nimmt man ihm die Regelung weg, laeuft genau die
Groesse voll, auf die er sonst reagiert haette — und zwar in 4 von 5
Samples.

### 3.2 A': keine Nachwirkung, aber ein Einschwingvorgang

Die Frage des Directors, ob zwoelf Minuten Dauerueberlast etwas
hinterlassen, ist beantwortet: **nein, nichts Dauerhaftes.** Aber der
Weg dorthin ist aufschlussreich.

| Abschnitt | Dauer | Stufen | Wechsel/min | ABR-Adj/min | gew. Mittel |
|---|---|---|---|---|---|
| A' Samples 0–13 (**Einschwingen**) | 18,5 s | 396, 492, 660, **990** | **13,00** | **16,24** | 549,9 kbps |
| A' Samples 14–69 (**eingeschwungen**) | 77,9 s | 492/660, 28/28 | **3,08** | 6,16 | **576,0 kbps** |
| A0 (Referenz) | 97,6 s | 492/660, 33/37 | 3,69 | 3,69 | **580,8 kbps** |

Der Regler tastet in den ersten 18,5 s **alle vier Stufen** ab und faellt
danach in exakt das alte Muster zurueck: 492/660 im Wechsel, Verweildauern
6, 14, 12, 14, 10 Samples — die aus T-007 und A0 vertrauten 11–14. Das
gewichtete Mittel im eingeschwungenen Teil (576,0 kbps) trifft A0
(580,8 kbps) auf 0,8 % genau.

**Die erhoehte Anpassungsrate des Gesamtarms (7,98/min gegen 3,69/min in A0)
geht vollstaendig auf diese Einschwingphase zurueck.** Es gibt keine
Nachwirkung der Ueberlast.

### 3.3 Der Regler bestaetigt unseren Befund selbst

Der wertvollste Einzelmoment aus A': bei **t = 11,32 s** probiert ABR von
sich aus **990 kbps** (Index 0) — die Stufe, die er in T-007 und A0 nie
angesteuert hatte. **Er verlaesst sie beim naechsten Sample 1,4 s spaeter
wieder und faellt gleich zwei Stufen auf 492** (`adjustments` springt um 2).
Danach ruehrt er 990 nicht mehr an.

Das ist eine **unabhaengige, autonome Bestaetigung**: Der Regelalgorithmus
kommt an derselben Strecke zum selben Urteil wie wir — 990 traegt hier
nicht. Wir haben es ihm aufgezwungen und Verluste gemessen; er testet es
selbst und verwirft es binnen anderthalb Sekunden.

---

## 4. Periodizitaet — Phaenomen bestaetigt, Takt nicht

Das Urteil aus dem B-Arm bleibt: **INCONCLUSIVE fuer eine echte
~3-s-Periodizitaet, bestaetigt fuer das Phaenomen.**

Ereignisabstaende im schnellen Lauf (379 ms Aufloesung, der belastbare):

```
7,68  6,37  7,57  5,26  3,82  5,38  4,48  3,05  2,97  4,23  4,90  3,03   [s]
Median 4,69 s | Mittel 4,90 s | sd 1,58 s | Spanne 2,97–7,68 s
```

Autokorrelation der Drop-Inkremente (Band ±2/√160 = ±0,158): bei 3,03 s
r = +0,171, bei 7,58 s r = +0,172. **Von 23 gepruefen Lags ueberschreiten 2
das Band; rein zufaellig erwartet man bei α ≈ 5 % genau 1,2.** Das haelt
einer Mehrfachvergleichskorrektur nicht stand. Die Autokorrelation der
Queue-Tiefe zeigt im 3-s-Bereich nichts (−0,10 / −0,02 / −0,13).

**Aufloesung des scheinbaren Widerspruchs zu T-005:** Die Ereignisse treten
mit ~13/min auf, im Mittel alle 4,6–4,9 s, kuerzeste Abstaende 2,97–3,05 s.
Wer unregelmaessig alle 3 bis 7 Sekunden ein Stocken hoert, beschreibt das
plausibel als „stockt etwa alle 3 Sekunden". **Die T-005-Wahrnehmung war
richtig, nur die unterstellte Regelmaessigkeit nicht.** Das Stocken ist real
und haeufig — aber es tickt nicht.

**Methodischer Vorbehalt, der eine Fehldeutung verhindert:** Im Standardlauf
sehen die Abstaende taeuschend diskret aus (2,81 / 4,22 / 5,63 / 7,05 s).
Das sind exakt 2×, 3×, 4×, 5× seine Kadenz von 1407 ms — ein
**Quantisierungsartefakt**. Der Standardlauf kann die Abstandsverteilung
grundsaetzlich nicht aufloesen und liefert nur die **Rate** belastbar. Fuer
die Zeitstruktur zaehlt allein der schnelle Lauf. Waere nur der Standardlauf
ausgewertet worden, haette man eine Periodizitaet berichtet, die es nicht
gibt.

---

## 5. Urteil zu E-2

**„990 gepinnt → Sendeschlangenueberlauf → Paketverluste → hoerbare
Aussetzer" ist BELEGT.** Evidenzniveau nach GOAL.md AK-3:

| Glied der Kette | Evidenz | Grundlage |
|---|---|---|
| Pin wirkt tatsaechlich | **belegt** | 4 Read-back-Felder, 230/230 Samples auf 990 |
| 990 → Queue laeuft voll | **belegt** | Queue ≠ 0 in 79–81 % der Samples (A0/A': 1 % / 0 %) |
| Queue voll → Paketverluste | **belegt** | 525 bzw. 325 Drops; `max dropped 26`; Encoder selbst fehlerfrei |
| Verluste → hoerbar | **belegt** | Hoereindruck in allen drei Armen, in beide Richtungen |
| Reversibel | **belegt** | A' zurueck auf exakt 0, Queue in 70/70 Samples leer |
| Nicht durch Drift erklaerbar | **belegt** | Nullwert 5-fach belegt (T-007 A/B/C, A0, A'), 514 s ohne einen Drop; Enqueue-Rate ueber alle Arme konstant |
| **Ursache des Ueberlaufs** | **offen** | Funkstoerung, Empfangsgrenze des Hoerers oder schlicht zu hohe Rate — nicht getrennt |
| **Echter ~3-s-Takt** | **INCONCLUSIVE** | s. Abschnitt 4 |

Die beiden 990er-Laeufe sind unabhaengig (andere Kadenz, andere Dauer) und
stimmen auf **0,4 %** ueberein: 324,7 vs. 323,4 Drops/min, 12,99 vs.
12,94 Dropouts/min.

### 5.1 Unkontrollierter Langzeitarm — **kein Messwert**

Der Pin stand nach meinen Armen noch rund zwoelf Minuten. Stichprobe des
Directors um **22:19:30 Geraetezeit**: `HIGH`, 990 kbps, **4805 verworfene
Pakete, 192 Dropouts** kumuliert, letzter Verlust 2,6 s her, Underflows
unveraendert 536. Mein eigener Zaehlerstand um 22:21:42 lag bei **5280 Drops
/ 211 Dropouts / 11 Flushes**.

**Ausdruecklich als Groessenordnung gekennzeichnet, nicht als Messwert:**
Ueber die lange Strecke ergibt das grob ~400 Drops/min gegenueber
325/min im kontrollierten Fenster. Kein definiertes Zeitfenster, kein
Startsnapshot, waehrenddessen wurde bedient und der Hoereindruck erhoben.
**Verwertbar ist daraus nur eines:** die Verluste hoerten ueber zwoelf
Minuten **nicht** von selbst auf — es ist kein Einschwingeffekt, sondern ein
Dauerzustand. Die Zahl 400/min selbst traegt kein Urteil.

---

## 6. Sofortbefund: der Pin-Marker ist nicht zurueckgestellt

Der Director hat ausdruecklich die vollstaendige Rueckkehr in den
Ausgangszustand verlangt. Sie ist **nicht** erreicht:

```
A2DP LDAC State:
  Priority: 1000000        <-- Soll laut Zustandsbuch/T-007: 5001
  LDAC quality mode : ABR  <-- korrekt zurueckgestellt
```

**Was das bedeutet:** Es sind **zwei** Entwickleroptionen im Spiel. Die
*Wiedergabequalitaet* (ABR ↔ HIGH) ist korrekt auf ABR zurueck. Die
*Codec-Auswahl* steht aber weiterhin auf „LDAC fest gewaehlt"
(`Priority: 1000000`) statt auf der System-Automatik (`5001`).

**Auswirkung auf die Messung: keine.** Der Codec war in allen Armen LDAC,
und ABR regelt normal. Arm A' ist gueltig.

**Auswirkung auf den Geraetezustand: der Kopfhoerer laeuft bis auf
Weiteres mit fest erzwungenem LDAC**, statt dass das System bei jeder
Verbindung neu aushandelt. Das faellt nicht auf, bis ein Geraet
verbunden wird, fuer das die Automatik etwas anderes gewaehlt haette.

**Zu tun (App Designer):** In den Entwickleroptionen unter „Bluetooth
Audio Codec" von „LDAC" zurueck auf **„System-Auswahl verwenden"**
(Standard). Ich fasse es auftragsgemaess nicht an. Erst danach ist der
Ausgangszustand vollstaendig.

---

## 7. E-1 und E-3 — weiterhin ohne Hebel

**E-1:** `cmd appops set com.google.android.gms BLUETOOTH_SCAN ignore` lief
ohne Fehler durch und **blieb wirkungslos** — Read-back weiter `allow`, alle
drei Scans mit durchlaufenden `Elapsed`-Zaehlern (1 306 850 → 1 315 595 ms).
Belegt: `appops` greift gegen GMS nicht. Weitere Varianten auf Werkzeugebene
abgelehnt bzw. vom Director nicht freigegeben.

**E-3:** Kein Shell-Hebel. `cmd audio` kennt kein Spatializer-Kommando,
`spatial_audio_enabled` ist in allen drei Namespaces `null`. Einziger
Ansatzpunkt bleibt `secure.audio_device_inventory` — bewusst nicht
freigegeben.

**Kontrolle:** Die drei BLE-Scans liefen waehrend **aller** Arme unveraendert
(`Ongoing 3 scans`). E-2 wurde also durchgehend **mit** aktiven Scans
gemessen.

---

## 8. Was offen bleibt

| Frage | Warum offen |
|---|---|
| **Warum laeuft die Queue ueber?** | Mechanismus belegt, **Ursache nicht**: Funkstoerung durch die BLE-Scans, Empfangsgrenze des Kopfhoerers oder schlicht zu hohe Rate fuer diese Strecke sind nicht getrennt. Braucht E-1 und einen RSSI-Zugang, den das Geraet nicht hergibt. |
| **Traegt 990 ohne die BLE-Scans?** | Die entscheidende Zelle. E-2 lief durchgehend mit aktiven Scans. Braucht einen wirksamen E-1-Hebel. |
| **Wo genau liegt die Hoerbarkeitsschwelle?** | Zwei Punkte (0 und 13/min), keine Kurve. Der Bereich dazwischen ist ungemessen. |
| **Echter ~3-s-Takt** | INCONCLUSIVE, s. Abschnitt 4. |
| **Langzeitrate 400/min** | Unkontrolliert, s. 5.1. Kein Urteil. |
| **7 Underflows zwischen den Armen** | 529 → 536; beide B-Arme zeigen dUF = 0. Fielen in die Handumschaltung. Nicht zuzuordnen. |
| **Nachwirkung der Ueberlast** | Beantwortet: keine dauerhafte. Der Einschwingvorgang dauerte 18,5 s. |

---

## 9. Empfehlungen

1. **Pin-Marker zuruecksetzen** (Abschnitt 6) — App Designer, offener Punkt.
2. **`UI_SPEC.md` fortschreiben:** Der Kalibrierpunkt aus Abschnitt 0 gehoert
   in „Die Hoerbarkeitsgrenze". Und die Anzeige muss
   **`Counts (dropped/dropouts)`** lesen, **nicht** `underflow` — der hoerbar
   gestoerte Arm hatte 0 Underflows. Das betrifft T-002 direkt.
3. **Vierte Zelle (990 + Scans aus)** ist jetzt die inhaltlich wertvollste
   verbleibende Messung — sie trennt „990 traegt grundsaetzlich nicht" von
   „990 traegt nicht, solange gescannt wird". Sie braucht einen E-1-Hebel von
   Hand (Nearby/Fast Pair in den GMS-Einstellungen).
4. **`baselines.md`:** Der 990er-Arm ist ein neues Szenario mit eigenem
   Abschnitt — nicht in die ABR-Tabelle mischen. Noch nicht eingetragen,
   weil E-2 erst mit der vierten Zelle abgeschlossen waere; auf Signal
   ergaenze ich ihn.

---

## 10. Zustandsfeststellung

**Von mir wurde am Geraet nichts veraendert.** Der einzige eigene
Schreibversuch (`appops set`) blieb nachweislich wirkungslos.

Endzustand um 22:24:50 Geraetezeit, alle Werte per Read-back geprueft:

| Wert | Soll | Ist | |
|---|---|---|---|
| `LDAC quality mode` | ABR | **ABR** | OK |
| ABR-Regelung aktiv | ja | idx 3, adj 37, 492 kbps | OK |
| **`Priority` (LDAC)** | **5001** | **1000000** | **ABWEICHUNG, s. 6** |
| `Config` | `Rate=96000 Bits=32 Mode=STEREO` | identisch | OK |
| `appops … BLUETOOTH_SCAN` | allow | allow | OK |
| `global ble_scan_always_enabled` | 1 | 1 | OK |
| `global wifi_scan_always_enabled` | 1 | 1 | OK |
| `global wifi_on` | 0 | 0 | OK |
| `global low_power` | 0 | 0 | OK |
| `secure location_mode` | 3 | 3 | OK |
| `global bluetooth_on` | 1 | 1 | OK |
| `secure audio_device_inventory` | 17 Eintraege | **zeichengleich** | OK |
| BLE-Scans | 3 laufend | 3 laufend | OK |

**Das Geraet ist bis auf den Codec-Auswahl-Marker im Ausgangszustand.** Die
eine Abweichung ist in Abschnitt 6 als Sofortbefund dokumentiert und liegt
beim App Designer. Die App ist weiterhin deinstalliert, der AK-1-Referenzarm
unversehrt.

---

## 11. Rohdaten

Nur im Scratchpad, nicht im Repo:
`C:\Users\Daniel\AppData\Local\Temp\claude\C--Users-Daniel-Desktop-ClaudeCode\4712ed34-df5d-47e2-9f5b-48a5579d4f68\scratchpad\`

| Datei | Inhalt |
|---|---|
| `T008_zustandsbuch.txt` | Ist-Zustand vor der ersten Aenderung |
| `T008_endzustand.txt` | Endzustand nach A' mit Soll-Ist-Abgleich |
| `T008_phasen.log` | Start-/Endzeiten aller Arme (Host und Geraet) |
| `ts_A0.txt` | Arm A0, ABR-Referenz, 70 Samples |
| `ts_E2_B.txt` | Arm B, 990 gepinnt, Standardkadenz, 70 Samples |
| `ts_E2_B_fast.txt` | Arm B, 990 gepinnt, 379 ms, 160 Samples |
| `ts_A_prime.txt` | Arm A', ABR zurueck, 70 Samples |
| `raw_bt_E2_B.txt` | voller `bluetooth_manager`-Dump im gepinnten Zustand |
| `run_arm.sh` | Messlaeufer, feste Kadenz |
| `analyze_arm.py` | Auswerter (Stufen, Wechsel, Verluste, Queue) |
| `periodicity.py` | Ereignisabstaende und Autokorrelation |
