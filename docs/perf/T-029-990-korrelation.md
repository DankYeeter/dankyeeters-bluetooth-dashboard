# T-029 — Hoerbarkeit bei fest 990 kbps: Korrelation statt Leiter

Rolle: `performance-tuner`. Auftrag `docs/tasks/T-029.md`. Vorlauf:
`docs/perf/T-028-hoersitzung-reizplan.md` (Methodik, Uhrenkalibrierung),
`docs/perf/T-008-experimente.md` (990er-Befund, Grundlage dieses Ansatzes).

**Diese Datei wird waehrend des laufenden Messlaufs fortlaufend geschrieben**
(Auftragspflicht, mind. alle 2 Minuten) — der Stand unten ist Zwischenstand,
kein Endergebnis.

---

## 0. Vorbedingung — erster Anlauf abgebrochen, zweiter Anlauf verifiziert

**Erster Anlauf (21:12 Host-Zeit):** Read-back zeigte die Stufe noch auf
fest 660/MID (`mCodecSpecific1:1001`), nicht auf 990. Die vom Nutzer
begonnene Umstellung war zu diesem Zeitpunkt noch nicht angekommen. Kein
Lauf gestartet, an den Director gemeldet (`STATUS: teilweise`), nicht
selbst umgestellt.

**Zweiter Anlauf — Vorbedingung erfuellt.** Director meldet 21:14:49
Host-Zeit eigene Lesung: `LDAC quality mode: HIGH`, 990 Kbps. Eigener,
unabhaengiger Read-back direkt danach bestaetigt das (Abschnitt 1).

## 1. Dreifachpruefung — fest 990, vollstaendiges Zustandsbuch

Vollstaendiger `dumpsys bluetooth_manager`-Read-back, `platform-tools/adb.exe`
(R-2), Geraet `67011FDKX004XG` per Kabel, **21:16:0x Host-Zeit** (Datei nur
Scratchpad: `t029_preflight_990_full.txt`, `umask 077` beim Aufruf):

| Feld | Wert |
|---|---|
| `mConnectionState` (A2DP) | STATE_CONNECTED |
| `mIsPlaying` | true |
| `mCodecConfig.mCodecSpecific1` | **1000** — Ist-Wert fuer die 990er-Stufe, **protokolliert, nicht gegen 1001 geprueft** (1001 war MID/660 aus T-027/T-028) |
| `LDAC quality mode` | **HIGH** |
| `LDAC transmission bitrate (Kbps)` | **990** |
| ABR-Zeilen (`adaptive bit rate`) | **abwesend** (0 Treffer) — Pin wirkt |
| `Priority` (LDAC) | 1000000 — kein Aussagewert fuer die Stufe (Gate-Befund T-027: Priority ist die Codec-Auswahl-Prioritaet, nicht die Stufe) |
| `LDAC saved transmit queue length` | 0 |
| **Ausgangsstand `Counts (flushed/dropped/dropouts)`** | **1 / 900 / 39** — Referenz fuer alle Deltas dieses Laufs |
| `Counts (underflow)` | 2288 |
| LDAC-Encoder `Packet counts (expected/dropped)` | 16464 / 0 (encoder-eigener Zaehler, seit letztem Stufenwechsel neu; encoderseitig weiterhin 0 Drops — deckt sich mit T-008, der Encoder selbst verwirft nie) |

**Dreifachpruefung bestanden: fest 990/HIGH, `mCodecSpecific1`=1000, ABR-Zeilen
abwesend.** Stufe ist verifiziert fest, nicht ABR, nicht 660.

## 2. Uhrenkalibrierung — frisch gemessen

Host `date`/`date +%s%N` unmittelbar vor und nach `adb shell date +%s%N`,
21:16:1x Host-Zeit:

| Messung | Wert |
|---|---|
| Host vor | 1788376573,854785600 s (epoch) |
| Geraet | 1788376665,688740944 s (epoch) |
| Host nach | 1788376573,973362500 s (epoch) |
| Host-Mittel | 1788376573,914074 s |
| **Versatz** | **Geraet − Host ≈ +91,77 s, Geraet vor Host** |

Deckt sich mit den fruehren Kalibrierungen (T-028: +91 s / +91,2 s) —
**diesmal frisch gemessen, nicht uebernommen**, wie vom Director verlangt.
Fuer die Umrechnung in diesem Lauf gilt **+91,77 s** (Geraet minus 91,77 s
= Host-Zeit; Host plus 91,77 s = Geraetezeit-Naeherung).

## 3. Lauf — Start

**Kein externer Stoerreiz.** Werkzeug: bereits vorhandenes
`/data/local/tmp/btperf/t027p2/cell.sh` (aus T-027/T-028, unveraendert
wiederverwendet — samplet den reduzierten Block `A2DP State:`/`A2DP LDAC
State:` alle ~1 s), aufgerufen mit `n_streams=0` (kein Stimulus, keine
`dd`/`nc`-Stroeme) und `duration=1500` s (25 min). Aufruf:

```
adb shell "umask 077; sh /data/local/tmp/btperf/t027p2/cell.sh t029_990corr 0 1500 192.168.178.31 5502"
```

Host-IP/Port sind bei `n_streams=0` funktionslos (kein Verbindungsaufbau),
nur wegen der Skriptsignatur mitgegeben.

**HOST-STARTZEIT (Dispatch des Startkommandos, derselbe Werkzeugaufruf wie
der `adb shell`-Start):**

```
2026-09-02 21:16:30 Host-Zeit  (epoch_ns = 1788376590158025600)
```

Geraetezeit-Naeherung ueber den Versatz (+91,77 s): **≈ 21:18:01,8**
Geraetezeit — Naeherung, massgeblich ist der geraeteeigene
`meta.txt`-Zeitstempel (`date +%s%N`), der nach Laufende ausgelesen wird.

Der Lauf laeuft **im Hintergrund** (Background-Bash-ID `beybn3201`),
blockierend bis 1500 s + Sampler-Nachlauf. Erwartetes Ende: **≈ 21:41:30
Host-Zeit** (grob, 25 min nach Dispatch plus Sampler-Nachlauf ~5 s).

**Zwischenzuege der Rohreihe** werden alle ~2 min per `adb shell cat
.../series.log` vom Geraet gezogen und hier fortgeschrieben, damit bei
einem API-Abbruch nichts verloren geht.

---

## 4. Zeitreihe (wird fortlaufend ergaenzt)

Methodik: Rohreihe `series.log` alle ~1 s vom geraeteeigenen Sampler
(`cell.sh`) geschrieben, Zeitstempel `t_ns` (`date +%s%N`, Geraetewalluhr).
Zwischenzuege per `adb shell cat .../series.log` (nur Lesen, keine
Geraeteveraenderung), Auswertung per eigenem Parser (nur Scratchpad,
`t029_parse.py`) — extrahiert `Counts (flushed/dropped/dropouts)`, Queue-
Laenge, Bitrate, Quality Mode je Sample, bildet Deltas und gleitende
15-s-/30-s-Fensterraten.

**Zwischenzug 1 — 21:19:0x Host-Zeit (≈ 76 s nach Laufstart):**

| t_rel (s seit Laufstart) | `dropped` (kum.) | `dropouts` (kum.) | Δdropped | Δdropouts | Queue | Bitrate | Quality |
|---|---|---|---|---|---|---|---|
| 0,0 – 75,1 (alle 56 Samples) | **900 durchgehend** | **39 durchgehend** | **0** | **0** | 0 | 990 | HIGH |

**Kein einziger Verlust in den ersten 75 s.** Zaehler identisch zum
Ausgangsstand aus der Dreifachpruefung (Abschnitt 1: 1/900/39). Queue in
allen 56 Samples leer (0). Naechster Zug in ~2 min.

## 5. Fensterraten (15 s / 30 s) — Zwischenstand nach 75 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 56 | 0,00 | 0,00 | 100 % | 0,00 | 0,00 | 100 % |
| 30 s | 56 | 0,00 | 0,00 | 100 % | 0,00 | 0,00 | 100 % |

## 6. Verteilungsaussage — Zwischenstand

Bislang **keine Streuung ueberhaupt**: alle Fenster (15 s und 30 s) zeigen
exakt 0. Zu frueh fuer ein Urteil — der Lauf ist erst 75 von geplanten
1500 s (5 %) durchlaufen. Wird mit jedem Zwischenzug aktualisiert.

## 7. Antwort auf die Kernfrage — vorlaeufig, folgt final nach Laufende

**Schwankt die Verlustrate bei fest 990 genug, dass sich Zeitfenster
unterscheiden lassen?** Noch offen — bislang schlicht **keine** Verluste
aufgetreten (0/900/39 unveraendert). Endgueltige Antwort erst nach den
vollen 25 min.

## 4b. Zwischenzug 2 — 21:20:xx Host-Zeit (t_rel bis 276,0 s, ≈ 4,6 min seit Laufstart)

**Erster echter Verlust im Lauf, ab t_rel ≈ 139,8 s.** Bis dahin (0–~112 s
im vorigen Zug) durchgehend Δ0 — ab hier setzt Verlust bursthaft ein:

| t_rel (s) | Δ`dropped` | Δ`dropouts` | Queue bei diesem Sample | Host-Zeit (naeherungsweise, Offset +91,77 s abgezogen) | Geraetezeit (naeherungsweise) |
|---|---|---|---|---|---|
| 139,8 | **+100** | **+4** | 24 | ≈21:18:50,3 | ≈21:20:22,1 |
| 141,1 | **+100** | **+4** | 4 | ≈21:18:51,6 | ≈21:20:23,4 |
| 157,0 | +50 | +2 | 0 | ≈21:19:07,5 | ≈21:20:39,3 |
| 161,0 | +25 | +1 | 0 | ≈21:19:11,5 | ≈21:20:43,3 |
| 208,0 | +25 | +1 | 8 | ≈21:19:58,5 | ≈21:21:30,3 |

Danach (t_rel 208–276 s) wieder durchgehend Δ0. **Kumuliert ueber den
gesamten Zug: `dropped` 900→1200 (Δ300), `dropouts` 39→51 (Δ12).**
Host-/Geraetezeit-Naeherung ueber den in Abschnitt 2 gemessenen Versatz
(+91,77 s) aus dem geraeteeigenen `t_ns`-Zeitstempel je Sample — **die
massgebliche Groesse bleibt `t_ns`**, die Uhrzeitangabe ist eine
Umrechnung zur Orientierung fuer den Director, keine Neumessung.

**Das ist die gesuchte Schwankung:** ~112 s vollstaendig ruhig, dann ein
Cluster aus fuenf Verlustereignissen innerhalb von rund 70 s (139,8–208,0 s),
danach wieder ruhig. Fensterraten (15 s) reichen bereits jetzt von 0 bis
865,22 `dropped`/min — ein Unterschied von zwei Groessenordnungen zwischen
ruhigen und heftigen Fenstern desselben Laufs, bei durchgehend fest 990/HIGH
(Dreifachpruefung in jedem Sample: `LDAC quality mode HIGH`, `990`, kein
Stufenwechsel).

## 5b. Fensterraten — Zwischenstand nach 276 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 209 | 0,00 | **865,22** | 81,8 % | 0,00 | **34,61** | 81,8 % |
| 30 s | 209 | 0,00 | **573,91** | 70,3 % | 0,00 | **22,96** | 70,3 % |

Zum Vergleich der Groessenordnung: T-008s kontrollierter 990er-Arm B lag bei
**324,7 `dropped`/min bzw. 12,99 `dropouts`/min ueber den ganzen 97-s-Arm
gemittelt** — die jetzt beobachteten Spitzenfenster liegen darueber (865/min
bzw. 34,6/min), die ruhigen Fenster weit darunter (0). Das stuetzt die
Ausgangsthese: eine **Gesamtrate** haette genau diese Spitzen weggemittelt.

## 4c. Zwischenzug 3 — ≈21:24 Host-Zeit (t_rel bis 469,2 s, ≈ 7,8 min seit Laufstart)

Verlust setzt sich fort, die **Dichte nimmt zum Ende dieses Zugs deutlich
zu** — ab t_rel ≈ 404 s treten Ereignisse fast in jedem 1-s-Sample auf,
statt wie zuvor in isolierten 25er-Schritten alle paar Sekunden:

| Abschnitt (t_rel) | Charakter | Beispielwerte |
|---|---|---|
| 276–330 s | ruhig (kein Verlust bis 330,2) | — |
| 330,2–401,4 s | vereinzelte Ereignisse, ~+25/Sample, Abstand oft mehrere Sekunden | siehe Rohreihe |
| **404,0–408,0 s** | **dichter Ausbruch: +50, +100, +126 in drei aufeinanderfolgenden Samples** | 404,0 (+50/+2), 406,7 (+100/+4), 408,0 (+126/+5) |
| 409,4–467,9 s | wieder ueberwiegend +25/Sample, aber mit kuerzeren Abstaenden als im vorigen Abschnitt (fast lueckenlose Folge ab ~423 s) | siehe Rohreihe |

Kumuliert am Ende dieses Zugs: `dropped` 900→**2502** (Δ1602 seit Laufstart),
`dropouts` 39→**103** (Δ64). Durchgehend `LDAC quality mode HIGH`, 990 Kbps,
kein Stufenwechsel in einem der 355 Samples.

## 5c. Fensterraten — Zwischenstand nach 469 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 354 | 0,00 | **1532,40** | 61,3 % | 0,00 | **61,13** | 61,3 % |
| 30 s | 354 | 0,00 | **912,13** | 52,5 % | 0,00 | **36,40** | 52,5 % |

**Die Spanne zwischen ruhigen und heftigen Fenstern waechst mit laengerer
Laufzeit weiter** (15-s-Max von 865 auf 1532 `dropped`/min), waehrend der
0-Anteil sinkt (81,8 % → 61,3 %) — der Lauf wird nicht gleichmaessiger,
sondern die Verlustphasen werden haeufiger/dichter, ohne dass sich Stufe
oder Konfiguration aendern. Naechster Zug in ~2 min.

## 4d. Zwischenzug 4 — ≈21:26 Host-Zeit (t_rel bis 624,8 s, ≈ 10,4 min seit Laufstart)

**Dichte nimmt weiter zu.** Kumuliert: `dropped` 900→**3002** (Δ2102 seit
Laufstart), `dropouts` 39→**123** (Δ84). 18 Samples mit Verlust allein im
Fenster 469,2–624,8 s (155,6 s). Durchgehend fest 990/HIGH, keine
Stufenwechsel in allen 474 Samples.

## 5d. Fensterraten — Zwischenstand nach 625 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 473 | 0,00 | 1532,40 | 53,5 % | 0,00 | 61,13 | 53,5 % |
| 30 s | 473 | **52,04** | 912,13 | 44,6 % | **2,08** | 36,40 | 44,6 % |

**Der 30-s-Median ist jetzt erstmals von null verschieden (52,04/min)** —
der 0-Anteil sinkt weiter (30 s: 70,3 % → 52,5 % → 44,6 % ueber die letzten
drei Zuege), waehrend das Maximum stabil bei den in Zug 3 erreichten
Spitzenwerten bleibt. Das Bild bestaetigt sich: **die ersten ~112 s des
Laufs waren die Ausnahme, nicht die Regel** — der weitaus groessere Teil
des bisherigen Laufs zeigt Verlust in wechselnder Dichte, nicht in
konstanter Rate. Naechster Zug in ~2 min.

## 4e. Zwischenzug 5 — ≈21:29 Host-Zeit (t_rel bis 780,5 s, ≈ 13 min seit Laufstart, gut Halbzeit)

Muster haelt an: 30-s-Fensterrate weiterhin median ≈52/min ungleich null,
0-Anteil stabil um 47 %. Kein neuer Extremwert (Max bleibt bei den in Zug 3
erreichten 1532,40 `dropped`/min bzw. 61,13 `dropouts`/min je 15-s-Fenster)
— das Bild ist ab etwa der Haelfte des Laufs **eingeschwungen**: durchgehender
Wechsel zwischen ruhigen und aktiven Fenstern, ohne weiteres Anwachsen der
Spitzen.

## 5e. Fensterraten — Zwischenstand nach 780,5 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 593 | 0,00 | 1532,40 | 56,2 % | 0,00 | 61,13 | 56,2 % |
| 30 s | 593 | 51,83 | 912,13 | 47,0 % | 2,07 | 36,40 | 47,0 % |

Naechster Zug in ~2 min.

## 4f. Zwischenzug 6 — ≈21:32 Host-Zeit (t_rel bis 929,2 s, ≈ 15,5 min seit Laufstart)

Bild bleibt stabil eingeschwungen, keine neuen Extremwerte. 30-s-Median
weiterhin ≈51/min, 0-Anteil ≈47 %.

## 5f. Fensterraten — Zwischenstand nach 929,2 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 708 | 0,00 | 1532,40 | 57,5 % | 0,00 | 61,13 | 57,5 % |
| 30 s | 708 | 50,90 | 912,13 | 46,6 % | 2,04 | 36,40 | 46,6 % |

Naechster Zug in ~2 min.

## 4g. Zwischenzug 7 — ≈21:34 Host-Zeit (t_rel bis 1074,3 s, ≈ 17,9 min seit Laufstart)

30-s-Median steigt weiter an (101,20/min), 0-Anteil sinkt weiter (40,3 %).
Neuer 30-s-Maximalwert (1172,20 `dropped`/min ggue. zuvor 912,13) — die
Verlustphasen werden im spaeteren Laufabschnitt tendenziell dichter, nicht
nur wiederholt gleich stark.

## 5g. Fensterraten — Zwischenstand nach 1074,3 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 818 | 0,00 | 1532,40 | 50,2 % | 0,00 | 61,13 | 50,2 % |
| 30 s | 818 | **101,20** | **1172,20** | 40,3 % | **4,05** | 46,81 | 40,3 % |

Naechster Zug in ~2 min. Verbleibende Laufzeit bis 1500 s: ~7 min.

## 4h. Zwischenzug 8 — ≈21:36 Host-Zeit (t_rel bis 1221,8 s, ≈ 20,4 min seit Laufstart)

Bild weiterhin stabil im eingeschwungenen Bereich (30-s-Median ≈51/min,
0-Anteil ≈42 %), kein neuer Extremwert ggue. Zug 7. Rest bis 1500 s: ~5 min.

## 5h. Fensterraten — Zwischenstand nach 1221,8 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 928 | 0,00 | 1532,40 | 53,8 % | 0,00 | 61,13 | 53,8 % |
| 30 s | 928 | 51,15 | 1172,20 | 41,9 % | 2,05 | 46,81 | 41,9 % |

## 4i. Zwischenzug 9 — ≈21:38 Host-Zeit (t_rel bis 1371,8 s, ≈ 22,9 min seit Laufstart)

Bild unveraendert eingeschwungen (30-s-Median 50,64/min, 0-Anteil 46,1 %).
Kein neuer Extremwert. Rest bis 1500 s: ~2 min plus Sampler-Nachlauf.

## 5i. Fensterraten — Zwischenstand nach 1371,8 s

| Fenster | n | `dropped`/min Median | `dropped`/min Max | 0-Anteil | `dropouts`/min Median | `dropouts`/min Max | 0-Anteil |
|---|---|---|---|---|---|---|---|
| 15 s | 1040 | 0,00 | 1532,40 | 57,7 % | 0,00 | 61,13 | 57,7 % |
| 30 s | 1040 | 50,64 | 1172,20 | 46,1 % | 2,03 | 46,81 | 46,1 % |

---

# Abschlussauswertung — voller Lauf (1504,5 s / 25,075 min)

Director-Anweisung nach Laufende: vollstaendige Auswertung ueber den
gesamten Lauf statt der Zwischenstaende, plus vier gezielte Zusatzfragen
(Ereignisstruktur, Zeittrend, Ereignisabstaende, Dreifachpruefung/Endstand).
Die Abschnitte 4a–5i oben bleiben stehen als Beleg des fortlaufenden
Schreibens waehrend des Laufs (Auftragspflicht); diese Abschlussauswertung
ersetzt sie inhaltlich als massgebliche Fassung.

**Rohreihe final vom Geraet gezogen** (`adb shell cat .../series.log`,
unmittelbar nach Ruecklauf des Startbefehls), **1140 Samples, Spanne
1504,5 s** (Sampler-eigene `t_ns`-Zeitstempel, geraeteseitig). Geraeteeigener
`meta.txt`-Zeitstempel bestaetigt eine Skriptlaufzeit von **1505,95 s**
(`start=1788376682121571255`, `end=1788378188071671444`, Differenz
1505950100189 ns) — die kleine Differenz zum Sample-Span (1504,5 s) ist der
letzte, nicht mehr abgeschlossene Sample-Zyklus plus Aufraeumzeilen im
Skript, kein Datenverlust.

## 8. Dreifachpruefung nachher und Zaehlerendstand

Vollstaendiger `dumpsys bluetooth_manager`-Read-back, **21:42:49 Host-Zeit**
(Datei ausserhalb des Repos gesichert, s. Abschnitt 18):

| Feld | Vorher (Abschnitt 1) | Nachher |
|---|---|---|
| `LDAC quality mode` | HIGH | **HIGH** — unveraendert |
| `LDAC transmission bitrate (Kbps)` | 990 | **990** — unveraendert |
| `mCodecConfig.mCodecSpecific1` | 1000 | **1000** — unveraendert |
| ABR-Zeilen | abwesend (0) | **abwesend (0)** — Pin hielt durchgehend |
| `mConnectionState` / `mIsPlaying` | STATE_CONNECTED / true | **STATE_CONNECTED / true** |
| `Counts (flushed/dropped/dropouts)` | 1 / 900 / 39 | **1 / 4882 / 198** |
| `Counts (underflow)` | 2288 | 2298 (Delta 10 — kein Verdikt, Projektkonvention) |

**Dreifachpruefung nachher bestanden.** Kein Stufenwechsel ueber den
gesamten Lauf — bestaetigt zusaetzlich durch alle 1140 Samples der Rohreihe:
`LDAC quality mode` und Bitrate sind in **jedem einzelnen** Sample `HIGH`/`990`,
keine Ausnahme.

**Endstand der Zaehler dieses Laufs:** `dropped` 900 zu 4882 (**Delta 3982**),
`dropouts` 39 zu 198 (**Delta 159**), ueber 1504,5 s (25,075 min). Roh-Gesamtrate
(zum Vergleich, **kein** Ersatz fuer die Fensterraten, s. Abschnitt 15):
**158,8 `dropped`/min, 6,34 `dropouts`/min.**

## 9. Fensterraten — voller Lauf (1140 Samples)

| Fenster | n | dropped/min Median | dropped/min Max | dropped/min stdev | 0-Anteil | dropouts/min Median | dropouts/min Max | dropouts/min stdev | 0-Anteil |
|---|---|---|---|---|---|---|---|---|---|
| 15 s | 1139 | 0,00 | **1532,40** | 278,92 | **61,4 %** | 0,00 | **61,13** | 11,14 | 61,4 % |
| 30 s | 1139 | 0,00 | **1172,20** | 242,11 | **50,7 %** | 0,00 | **46,81** | 9,66 | 50,7 % |

Methodik: gleitendes Fenster, endend auf jedem Sample, Breite 15 s bzw. 30 s
(vorherige Samples innerhalb der Breite), Rate = Delta im Fenster geteilt durch
tatsaechliche Fensterspanne mal 60. 0-Anteil = Anteil der Fenster mit Rate
exakt null.

## 10. Ereignisstruktur — Raeumungsgroesse vs. Dropout-Episoden

127 der 1140 Samples (11,1 %) zeigen einen Verlust ungleich null. Verteilung
der `dropped`-Groesse je Ereignis:

| dropped je Ereignis | Anzahl | zugehoeriges dropouts je Ereignis |
|---|---|---|
| 25 | 101 | 1 |
| 26 | 4 | 1 |
| 50 | 15 | 2 |
| 51 | 2 | 2 |
| 75 | 1 | 3 |
| 100 | 3 | 4 |
| 126 | 1 | 5 |

**Das Verhaeltnis dropped/dropouts ist ueber alle 127 Ereignisse
konstant: Minimum 25,00, Maximum 26,00, Median 25,00, Mittel 25,04.** Jede
einzelne Kombination in der Tabelle ist ein ganzzahliges Vielfaches von
(25 dropped / 1 dropout) — 50/2, 75/3, 100/4, 126/5. **79,5 % der
Ereignisse (101 von 127) sind exakt die Grundeinheit 25/1.**

Das ist die Ereignisstruktur, die R-005 aus dem AOSP-Quelltext ableitet,
jetzt an einem realen 990er-Lauf bestaetigt: **eine Raeumungsepisode
(dropout) verwirft eine feste Groessenordnung von rund 25 Warteschlangen-
eintraegen (dropped).** Samples mit hoeheren Werten (50/2 usw.) sind keine
groesseren Einzelepisoden, sondern **mehrere 25er-Episoden, die innerhalb
desselben 1-s-Abtastintervalls stattfanden** — die 1-Hz-Abtastung loest sie
nicht einzeln auf, das Verhaeltnis bleibt aber exakt erhalten.

## 11. Zeitlicher Trend ueber die 25 Minuten

**Realer, wenn auch verrauschter Rueckgang der Verlustdichte.** Grobe
Aufteilung (Drittel und Haelften, robust gegen die Buendelung einzelner
Cluster):

| Abschnitt | dropped/min | dropouts/min |
|---|---|---|
| Drittel 1 [0–502 s] | 212,6 | 8,49 |
| Drittel 2 [502–1003 s] | 167,9 | 6,70 |
| Drittel 3 [1003–1504 s] | **96,0** | **3,83** |
| Haelfte 1 [0–752 s] | 191,6 | 7,66 |
| Haelfte 2 [752–1504 s] | **126,0** | **5,02** |

**Beide Aufteilungen zeigen denselben monotonen Rueckgang** — Drittel 1 zu 2 zu 3
faellt durchgehend, Haelfte 1 zu 2 ebenso. Der letzte 5-Minuten-Block
(1200–1500 s) liegt bei nur 5,0 dropped/min bzw. 0,20 dropouts/min; das
letzte Verlustereignis des gesamten Laufs liegt bei t = 1243,3 s, danach
**261,2 s vollstaendig ruhig bis Laufende**.

**Auf feinerer Aufloesung (100-s-Bins) ist der Trend statistisch schwach**
(lineare Regression: Steigung minus 15,99 dropped je Bin, Pearson r = minus 0,239) —
die Buendelung in Clustern erzeugt so viel Bin-zu-Bin-Streuung, dass die
100-s-Aufloesung den Rueckgang allein nicht zuverlaessig zeigt. Erst die
groebere Mittelung (Drittel/Haelften) legt ihn frei.

**Methodischer Befund, ueber T-029 hinaus:** Bei nur **einem** Lauf (n=1)
laesst sich nicht unterscheiden, ob dieser Rueckgang ein reproduzierbares
Phaenomen (z. B. Einschwing-/Aufwaermeffekt zu Laufbeginn, sich aendernde
Funkumgebung ueber die Zeit) oder ein Einzelfallmuster dieses konkreten
Laufs ist. **Was aber schon mit n=1 feststeht: fruehe und spaete Abschnitte
desselben Laufs sind nicht ohne Weiteres austauschbar.** Ein Vergleich, der
z. B. nur die ersten 5 Minuten eines 990er-Laufs gegen die letzten 5 Minuten
eines anderen 990er-Laufs stellt, koennte einen Unterschied zeigen, der
nichts mit der verglichenen Bedingung zu tun hat, sondern mit der Position
im Lauf. Empfehlung an den Director: bei kuenftigen 990er-Laeufen die
Position im Lauf (fruehe/mittlere/spaete Phase) als moegliche Nebenvariable
mitfuehren, bis eine Wiederholung zeigt, ob der Rueckgang stabil ist.

## 12. Ereignisabstaende — Verteilung, nicht nur Mittelwert

**Innerhalb von Clustern** (Abstand zum vorherigen Ereignis kleiner/gleich 30 s;
n = 117 Abstaende):

| Kennzahl | Wert |
|---|---|
| Minimum | 1,10 s |
| Median | 2,70 s |
| Mittel | 3,79 s |
| Maximum | 23,30 s |
| Streuung (stdev) | 3,45 s |

Histogramm: bis 1,5 s (praktisch aufeinanderfolgende Samples) **41**; 1,5–5 s
**45**; 5–15 s **29**; 15–30 s **2**. **Die Mehrzahl der Ereignisse innerhalb
eines Clusters liegt eng beieinander** (86 von 117 unter 5 s), mit einem
Auslaeufer bis knapp 25 s — kein einheitlicher Takt, aber deutlich dichter
gepackt als der Lauf im Mittel.

**Luecken zwischen Clustern** (Abstand groesser 30 s, inkl. Anfangs-/Endrand;
n = 11):

| Kennzahl | Wert |
|---|---|
| Minimum | 35,6 s |
| Median | 84,1 s |
| Mittel | 96,4 s |
| Maximum | **261,2 s** |

Vollstaendige Werteliste (s): 35,6 / 39,4 / 47,0 / 55,5 / 67,0 / 84,1 / 84,8
/ 122,2 / 123,9 / 139,8 / 261,2.

**Damit ist die Struktur eindeutig zweigipflig:** kurze
Ereignisabstaende (Median 2,7 s) *innerhalb* von zehn Clustern, dazwischen
lange Ruhephasen (Median 84 s, bis zu 261 s) *zwischen* den Clustern. Das
ist keine gleichmaessig verteilte Rate, sondern eine **Buendelstruktur**.

## 13. Cluster-Uebersicht mit Host- und Geraetezeit

Fuer die Zuordnung zu Hoermeldungen (die der Director getrennt fuehrt) —
Start/Ende jedes Clusters in beiden Zeitachsen. Umrechnung ueber den in
Abschnitt 2 gemessenen Versatz (+91,77 s, Geraet vor Host), angewandt auf
den geraeteeigenen `t_ns`-Zeitstempel jedes Samples — **die Geraetezeit-Spalte
ist die primaere, gemessene Groesse; die Host-Spalte ist die Umrechnung
fuer den Abgleich mit den Meldungen des Nutzers.**

| Cluster | t_rel (s) | Dauer | Ereignisse | Delta dropped | Delta dropouts | Rate im Cluster (dropped/dropouts je min) | Host-Zeit | Geraetezeit |
|---|---|---|---|---|---|---|---|---|
| 1 | 139,8–161,0 | 21,2 s | 4 | 275 | 11 | 778,3 / 31,13 | 21:18:50–21:19:11 | 21:20:21–21:20:43 |
| 2 (Einzelereignis) | 208,0 | 0 s | 1 | 25 | 1 | — | 21:19:58 | 21:21:30 |
| 3 | 330,2–564,3 | 234,1 s | 59 | 1802 | 72 | 461,9 / 18,45 | 21:22:00–21:25:54 | 21:23:32–21:27:26 |
| 4 | 688,2–726,2 | 38,0 s | 12 | 300 | 12 | 473,7 / 18,95 | 21:27:58–21:28:36 | 21:29:30–21:30:08 |
| 5 | 810,3–816,9 | 6,6 s | 2 | 75 | 3 | 681,8 / 27,27 | 21:30:00–21:30:07 | 21:31:32–21:31:39 |
| 6 (Einzelereignis) | 856,3 | 0 s | 1 | 25 | 1 | — | 21:30:46 | 21:32:18 |
| 7 | 911,8–1055,9 | 144,1 s | 45 | 1405 | 56 | 585,0 / 23,32 | 21:31:42–21:34:06 | 21:33:13–21:35:38 |
| 8 (Einzelereignis) | 1091,5 | 0 s | 1 | 25 | 1 | — | 21:34:41 | 21:36:13 |
| 9 (Einzelereignis) | 1176,3 | 0 s | 1 | 25 | 1 | — | 21:36:06 | 21:37:38 |
| 10 (Einzelereignis, letztes Ereignis des Laufs) | 1243,3 | 0 s | 1 | 25 | 1 | — | 21:37:13 | 21:38:45 |

Ruhephasen (kein Verlust) umgekehrt: 0–139,8 s (Laufanfang); 161,0–208,0 s;
208,0–330,2 s; 564,3–688,2 s; 726,2–810,3 s; 816,9–856,3 s; 856,3–911,8 s;
1055,9–1091,5 s; 1091,5–1176,3 s; 1176,3–1243,3 s; **1243,3–1504,5 s
(Laufende, 261,2 s ruhig)**.

## 14. Verteilungsaussage — final

Ueber den vollen Lauf: **61,4 % aller 15-s-Fenster und 50,7 % aller
30-s-Fenster sind exakt null.** Die restlichen Fenster reichen bis
**1532,4 dropped/min** (15 s) bzw. **1172,2 dropped/min** (30 s) — mehr
als das Vierfache der ueber den Gesamtlauf gemittelten Rate (158,8/min).
Die Streuung (stdev 278,92 bzw. 242,11 dropped/min) liegt in derselben
Groessenordnung wie der Mittelwert selbst — ein Kennzeichen einer stark
buendelnden, nicht gleichverteilten Groesse.

## 15. Antwort auf die Kernfrage

**Schwankt die Verlustrate bei fest 990 genug, dass sich Zeitfenster
unterscheiden lassen — JA, eindeutig.**

Beleg, unabhaengig gegengeprueft ueber mehrere Betrachtungsebenen:

- **Fensterraten:** 0-Anteil 50,7–61,4 %, gleichzeitig Maxima ueber
  1500 dropped/min bzw. 61 dropouts/min in Einzelfenstern — beides
  innerhalb desselben, ununterbrochenen Laufs bei unveraendert fest 990/HIGH.
- **Cluster-Struktur:** zehn klar abgegrenzte Verlust-Cluster (Dauer 0 bis
  234,1 s) mit dazwischenliegenden Ruhephasen von 35,6 bis 261,2 s Laenge —
  eine 261-Sekunden-Ruhephase und ein 234-Sekunden-Cluster koennen in
  keinem sinnvollen Sinn als "dieselbe Rate" gelten.
- **Zeittrend:** die Verlustdichte fiel ueber die drei Laufdrittel monoton
  von 212,6 auf 96,0 dropped/min — ein zweites, von der Cluster-Struktur
  unabhaengiges Anzeichen dafuer, dass die Rate nicht stationaer ist.

Alle drei Befunde stuetzen sich gegenseitig, ohne von derselben Kennzahl
abzuhaengen. Fuer die eingangs gestellte Projektfrage (T-029, ausgehend von
R-007/M-11) bedeutet das: **Der Lauf liefert die gesuchten "mehreren
Ratenpunkte bei konstanter Stufe"** — ruhige Fenster mit Rate null und
aktive Fenster mit Raten von mehreren hundert bis ueber tausend dropped/min,
in derselben 25-Minuten-Sitzung, ohne jeden Stufenwechsel. Ob diese
Zeitfenster mit den Hoermeldungen des Nutzers uebereinstimmen, ist Sache
des Directors (`docs/perf/T-027-hoereindruck.md`) — dazu **aeussert sich
dieser Bericht nicht.**

## 16. Nicht auswertbar / offen

- **n = 1.** Dieser Lauf ist eine einzelne, ununterbrochene 25-Minuten-
  Sitzung, keine Wiederholung. Ob die konkrete Cluster-Anordnung (Zeitpunkte,
  Dauer, Abstaende) reproduzierbar ist oder fuer diesen einen Abend spezifisch
  war, ist offen — dieselbe methodische Grenze wie bei jeder Einzelmessung.
- **Ursache der Cluster bleibt unbekannt.** Dieser Lauf hat, wie beauftragt,
  **keinen externen Stoerreiz** gesetzt (kein Sink-Server, keine WLAN-Last).
  Warum die Verlustdichte cluster-artig auftritt und ueber die Laufzeit
  abnimmt, ist eine Recherchefrage, keine, die aus dieser Messung folgt.
- **Der Zeittrend (Abschnitt 11) ist auf Basis eines einzelnen Laufs
  beschrieben, nicht auf Signifikanz gegen eine Streuung mehrerer Laeufe
  geprueft** — dafuer fehlen Wiederholungen. Die Aussage bleibt deskriptiv.
- **Keine Aussage zur Hoerbarkeit, keine Schwellenempfehlung** — beides
  ausserhalb des Auftragsumfangs, wie vorgegeben.
- Die WLAN-Assoziation wurde in diesem Lauf **nicht** gesondert geprueft
  (anders als in T-028, wo sie Auftragsgegenstand war) — hier war kein
  WLAN-Stoerhebel im Spiel, der Auftrag verlangte keine Assoziationspruefung.
  Falls der Director eine Erklaerung fuer die Cluster in Umgebungsfaktoren
  vermutet, waere das ein eigener, gezielter Nachlauf.

## 17. Stufe — unveraendert gelassen

Wie angewiesen: Stufe bleibt auf **fest 990/HIGH stehen**, nicht selbst
zurueckgestellt. Letzter bestaetigter Zustand (Abschnitt 8, Post-Check
21:42:49 Host-Zeit): `LDAC quality mode HIGH`, 990 Kbps,
`mCodecSpecific1 1000`, ABR-Zeilen abwesend, `mIsPlaying true`,
`ConnectionState STATE_CONNECTED`.

## 18. Aufgeraeumt

- Geraeteverzeichnis `/data/local/tmp/btperf/t027p2/t029_990corr`
  vollstaendig entfernt (`rm -rf`), per `test -d` bestaetigt (`ENTFERNT`).
  `cell.sh` und die fremden `level*`-Verzeichnisse des parallelen 5-GHz-Laufs
  aus T-027 **nicht** angefasst (fremde Rohdaten, nicht mein Auftrag).
- Jeder eigene Geraeteschreibzugriff lief unter `umask 077`
  (`adb shell "umask 077; ..."` bei Sampler-Start und bei beiden
  vollstaendigen dumpsys-Reads).
- **Rohreihe gesichert ausserhalb des Repos** (SR-012-Konvention, analog
  `C:\Users\Daniel\t027-rawdata`), da Geraetedaten:
  `C:\Users\Daniel\t029-rawdata\`
  - `series_full.log` — vollstaendige Rohreihe, 1140 Samples, 2,05 MB
  - `series_datarows_parsed.txt` — geparste Zeitreihe (t_rel, kumulierte
    Zaehler, Deltas, Queue, Bitrate, Quality Mode je Sample)
  - `preflight_990_full.txt` — vollstaendiger Read-back vor Laufstart
  - `postcheck_full.txt` — vollstaendiger Read-back nach Laufende
  - `preflight_first_attempt_660_full.txt` — Read-back des ersten,
    abgebrochenen Anlaufs (Stufe noch auf 660), zur Nachvollziehbarkeit
  - `t029_parse.py` — eigenes Auswerteskript (Parser + Fensterraten),
    ausserhalb des Produktivcodes, nicht ausgeliefert
- Scratchpad-Kopien (`t029_series_pull1..10.log`, `t029_parsed*.txt`,
  `t029_series_final.log`, `t029_preflight_*.txt`, `t029_postcheck_full.txt`,
  `t029_segments_epoch.txt`, `t029_datarows_final.txt`) verfallen mit dem
  Scratchpad dieser Session — die massgebliche Kopie liegt ab jetzt unter
  `t029-rawdata`.
- `adb`-Server durchgehend `platform-tools/adb.exe` (R-2), `C:\RSL\2.1HF5\adb\adb.exe`
  zu keinem Zeitpunkt aufgerufen. Kein `kill-server` am Ende (unkritisch,
  keine Fremdbenutzung des Servers in dieser Session).
- Bluetooth/A2DP-Verbindung durchgehend nicht angefasst, Musik lief laut
  letztem Read-back weiter (`mIsPlaying: true`). Stufe **nicht**
  zurueckgestellt (Director-Anweisung, s. Abschnitt 17).
