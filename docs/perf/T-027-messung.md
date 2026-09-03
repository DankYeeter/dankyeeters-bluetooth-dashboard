# T-027 — Kalibriermessung M-11

Rolle: `performance-tuner`. Auftrag: `docs/tasks/T-027.md`. Grundlage:
`docs/research/R-005.md`, `R-006.md`, `R-007.md`.

**Director-Korrektur nach Phase 0 (2026-09-02, siehe Übergabebericht):** Die
Gate-Frage war auf das falsche Feld gerichtet. `Priority` ist widerlegt als
Pin-Marker, aber der eigentliche Zweck des Gates — Stufenkonstanz während
Störung nachweisbar machen — ist über eine **Dreifachprüfung**
(`mCodecSpecific1`, `LDAC quality mode`, ABR-Zeilen-Abwesenheit) erfüllt.
**Gate gilt als bestanden. Phase 1–3 laufen.** Diese Datei wird fortlaufend
aktualisiert; Abschnitt 1 (Gate) bleibt unverändert als Beleg stehen.

**Stand dieser Fassung (Auswertungslauf, performance-tuner, 2026-09-02,
Rohdaten-Auswertung ohne Geräteeingriff):** Phase 0 abgeschlossen (unten).
Fixtures aus der Gate-Session gesichert (Abschnitt 6). Phase 1 (Ruhelauf,
gepinnt 660) **abgeschlossen** (Abschnitt 7). Phase 2 (7-Zellen-Reihe, Last
auf dem 5-GHz-Link) **abgeschlossen und ausgewertet** (Abschnitt 9,
Zellentabelle vollständig — die zuvor offene `level1_1stream`-Zeile ist
vervollständigt, `level2`/`level4`/`level8`/`level16`/`level0b_return` sind
neu ausgewertet, Abschnitt 10 fasst das Gesamtergebnis zusammen). Diese
Auswertung wurde ausschließlich aus bereits vom Gerät gezogenen Rohdaten
erstellt (`C:\Users\Daniel\t027-rawdata\t027p1`,
`C:\Users\Daniel\t027-rawdata\t027p2`, plus die zufällig noch vorhandenen
Vorher/Nachher-Volldumps und das Sink-Server-Log im Scratchpad derselben
Session, s. Abschnitt 9.3) — kein `adb`, kein Geräteeingriff in diesem Lauf.

---

## 0. Rahmen

- Gerät: Pixel 11 Pro `67011FDKX004XG`, Android 17 (SDK 37), USB-Kabel.
- Kopfhörer verbunden, Musik durchgehend (vom Nutzer vor Sessionbeginn
  hergestellt), warm.
- Ausgangszustand laut Übergabe: LDAC-Wiedergabequalität in den
  Entwickleroptionen auf „Ausgewogene Audio- und Verbindungsqualität"
  (660 kbps), **nicht** adaptiv — vor jeder eigenen Änderung per Read-back
  bestätigt (Abschnitt 1).
- Werkzeug: **ausschließlich**
  `/c/Users/Daniel/tools/android-sdk/platform-tools/adb.exe`
  (`~/tools/android-sdk/platform-tools/adb.exe`). Vor Sessionbeginn geprüft:
  `C:\RSL\2.1HF5\adb\adb.exe` liegt **nicht** im PATH dieser Shell und wurde
  zu keinem Zeitpunkt aufgerufen. `adb kill-server` / `start-server` einmal
  zu Beginn, danach durchgehend derselbe Server.
- Für Phase 0 war ein **UI-Eingriff** nötig (Entwickleroptionen-Dialog
  „Bluetooth Audio LDAC Codec: Playback Quality" hat keine `settings put`-
  oder `getprop`-Entsprechung — geprüft, siehe Abschnitt 1.1). Navigation
  per `adb shell input tap/swipe/keyevent` anhand von `adb exec-out
  screencap`-Screenshots, keine dritte Werkzeugebene. Kein Root, kein
  App-Code, kein `core-monitor`/`app`-Eingriff.
- `umask 077; mkdir -p /data/local/tmp/btperf/t027` zu Beginn angelegt —
  **blieb leer**, da das Gate vor jeder Aufzeichnung abgebrochen hat.
  Verzeichnis am Ende entfernt (Abschnitt 6).
- Rohdumps (volle `dumpsys bluetooth_manager`-Ausgaben, 3000+ Zeilen,
  enthalten u. a. Geräte-/Verbindungshistorie anderer Codecs) liegen nur im
  Scratchpad, nicht im Repo:
  `C:\Users\Daniel\AppData\Local\Temp\claude\C--Users-Daniel-Desktop-
  ClaudeCode\2318bcfa-d4e4-4383-883c-503395c73f98\scratchpad\
  gate_initial_full.txt`, `gate_step2_330.txt`, `gate_step3_abr.txt`,
  `gate_step4_restored.txt`, plus 13 Screenshots derselben Session
  (`screen_00`…`screen_20`). Diese Dateien sind **nicht** versioniert und
  verfallen mit dem Scratchpad — falls sie über diese Session hinaus
  gebraucht werden, müssen sie vor Ablauf gesichert werden.

### 0.1 Methodischer Vorbehalt (Auftragspflicht)

USB-3 strahlt ins 2,4-GHz-Band ab. Das Gerät hing während der gesamten
Session am Kabel (wie vom Nutzer hergestellt). Für Phase 0 (reiner
Feld-Vergleich, keine Verlustrate) ist das ohne Belang; **für eine
spätere Phase 2 mit 2,4-GHz-Störhebel wäre das Kabel potenziell Teil des
Messgegenstands** — benannt, nicht aufgelöst, wie im Auftrag verlangt.

---

## 1. Das Gate — Frage, Vorgehen, Ergebnis

**Frage (aus T-027):** Ist der projekteigene Pin-Marker `Priority: 1000000`
dasselbe Feld wie `quality_mode_index`/`codecSpecific1` im LDAC-Encoder,
oder ein unabhängiges Feld (A2DP-Codec-Auswahlpriorität)?

### 1.1 Erster Fund vor jeder eigenen Änderung: die zwei Felder existieren nebeneinander im selben Dump

Ein frischer Read-back (`dumpsys bluetooth_manager`, 17:06:11 Gerätezeit,
vor jedem Eingriff) zeigt im menschenlesbaren Block:

```
A2DP LDAC State:
  Priority: 1000000
  ...
  LDAC quality mode                                       : MID
  LDAC transmission bitrate (Kbps)                        : 660
```

und an anderer Stelle desselben Dumps, in der internen `CODEC_CONFIG_CHANGED`-
Ereignishistorie (`BluetoothCodecConfig`-Objektdarstellung), **zwei getrennte
Felder für denselben Codec-Eintrag**:

```
mCodecConfig: {codecName:LDAC, mCodecType:4, mCodecPriority:1000000,
  mSampleRate:0x8(96000), mBitsPerSample:0x4(32), mChannelMode:0x2(STEREO),
  mCodecSpecific1:1001, mCodecSpecific2:0, mCodecSpecific3:0, mCodecSpecific4:0}
```

`mCodecPriority` und `mCodecSpecific1` sind im Quellobjekt **strukturell
zwei verschiedene Member**, nicht dasselbe Feld unter zwei Namen. Das
beantwortet die Frage strenggenommen bereits strukturell — die Aufgabe
verlangt aber ausdrücklich den empirischen Beleg über eine Zustandsänderung,
nicht nur die Struktur. Das folgt in 1.2.

Zusätzlich zeigt derselbe Dump `Priority`-Werte für die **anderen**
verfügbaren Codecs (`LHDCv5: 5002`, `AptX-HD: 4001`, aus der
Selectable-Codec-Liste auch `AptX: 3001`, `AAC: 2001`, `Opus: 1501`,
`SBC: 1001`) — ein Muster, das zu einer Codec-**Auswahlrangfolge** passt
(je exotischer/hochwertiger der Codec, desto höher die Grundpriorität),
nicht zu einem Feld, das die interne Qualitätsstufe *eines* Codecs codiert.

Kein `settings`- oder `getprop`-Schlüssel für den Dialog existiert (geprüft:
`settings list global/secure | grep -i "ldac|a2dp|codec"` und
`getprop | grep -i "ldac|a2dp|codec"` — beide leer bis auf unrelated
`bluetooth.profile.a2dp.source.enabled`). Die Änderung musste über den
Entwickleroptionen-Dialog erfolgen (UI, kein Anwendungscode).

### 1.2 Der empirische Test: drei Zustandswechsel, live beobachtet

Ausgangszustand (17:06:11, vor jeder eigenen Änderung):
`Priority: 1000000`, `LDAC quality mode: MID`, Bitrate 660, `mCodecSpecific1: 1001`.

| Schritt | Aktion (UI, Entwickleroptionen) | Gerätezeit Read-back | `Priority` (Textblock) | `mCodecPriority` (Historie) | `mCodecSpecific1` (Historie) | `LDAC quality mode` | Bitrate | ABR-Zeilen (Index/Adjustments) |
|---|---|---|---|---|---|---|---|---|
| 0 (Start) | — | 17:06:11 | 1000000 | 1000000 | 1001 | MID | 660 | abwesend |
| 1 | „Optimised for Connection Quality" gewählt | 17:13:0x | **1000000** (unverändert) | **1000000** (unverändert) | **1002** (geändert) | LOW | 330 | abwesend |
| 2 | „Best Effort (Adaptive Bit Rate)" gewählt | 17:13:58 / Read 17:14 | **1000000** (unverändert) | **1000000** (unverändert) | **1003** (geändert) | ABR | 990 | **anwesend**: Index 0, Adjustments 2 |
| 3 (Rückstellung) | „Balanced Audio And Connection Quality" gewählt | 17:15:37 / Read 17:18 | **1000000** (unverändert) | **1000000** (unverändert) | **1001** (zurück auf Ausgangswert) | MID | 660 | abwesend |

**Befund, unmissverständlich:** Über drei aktive Zustandswechsel (660 → 330
→ ABR → 660) bleibt `Priority`/`mCodecPriority` **konstant bei 1000000** —
es bewegt sich **nicht mit**. `mCodecSpecific1` dagegen bewegt sich bei
**jedem** Wechsel und nimmt drei unterscheidbare Werte an (1001/1002/1003),
die eindeutig mit der gewählten Stufe korrespondieren (MID/660 → LOW/330 →
ABR/990 → zurück MID/660 exakt auf denselben Wert 1001).

**Gate-Ergebnis: Die Gleichsetzung trägt NICHT.**
`Priority` (`mCodecPriority`) ist **nicht** dasselbe Feld wie
`quality_mode_index`/`codecSpecific1`. Es ist ein unabhängiges Feld — nach
Struktur und Wertemuster identifizierbar als die **A2DP-Codec-
Auswahlpriorität** (welcher Codec — LDAC/AAC/SBC/… — bevorzugt wird),
nicht die interne LDAC-Qualitätsstufe. `mCodecSpecific1` ist das Feld, das
tatsächlich mit der Stufe wandert; sein numerisches Muster (1001/1002/1003,
mit einer Differenz von genau 1 zwischen benachbarten festen Stufen) ist
mit einem um eine Konstante verschobenen `quality_mode_index`/EQMID-
Codewert vereinbar, wurde hier aber nicht bis auf die Konstante
zurückgeführt — das wäre eine zusätzliche Behauptung über die genaue
Kodierung, die dieser Test nicht verlangt und nicht belegt.

**Beide Richtungen belegt, wie gefordert:**
- *Ändert sich die Stufe, bewegt sich Priority nicht* — dreifach gezeigt
  (Schritte 1, 2, 3).
- *Ändert sich die Stufe, bewegt sich codecSpecific1* — dreifach gezeigt,
  inklusive Rückkehr auf exakt den Ausgangswert bei Rückkehr auf die
  Ausgangsstufe (kein Drift, keine Hysterese in diesem Feld).

### 1.3 Nebenbefund, nicht Teil der Gate-Frage, aber relevant für die Einordnung

Aus Schritt 2 bestätigt sich der R-007-Mechanismus **unabhängig von der
Gate-Frage**: Die beiden ABR-Zeilen (`LDAC adaptive bit rate encode quality
mode index`, `... adjustments`) sind in den Schritten 0/1/3 (feste Stufe)
**abwesend** und erscheinen **nur** in Schritt 2 (`ABR`) — deckungsgleich
mit der bereits vorliegenden Projekterfahrung
(`project_ldac_990_no_abr_lines`, T-022) und mit T-008s Read-back-Befund
(„Felder verschwunden — Regler aus" beim Pinnen auf 990/HIGH). Der Wechsel
zu ABR ließ die Bitrate sofort auf 990 kbps springen (deckt sich mit dem
dokumentierten Befund „ABR probiert 990 von sich aus", T-011).

**Das ändert nichts am Gate-Ergebnis oben.** Ob die LDAC-Stufe fest oder
ABR ist, lässt sich direkt und zuverlässig am Textfeld `LDAC quality mode`
und an der An-/Abwesenheit der ABR-Zeilen ablesen — **unabhängig** davon,
was `Priority` gerade zeigt. Das ist eine Beobachtung für den `director`,
keine eigenmächtige Fortsetzung: der Auftrag verlangt exakt die
`Priority`-Gleichsetzung als Bedingung für Phase 1–3, nicht irgendeinen
funktionierenden Nachweis der Stufen-Konstanz. Ob eine überarbeitete
Auftragsfassung „konstante Stufe" künftig über das Textfeld statt über
`Priority` definieren will, ist eine Entscheidung des `director`, keine,
die dieser Lauf treffen darf.

### 1.4 Rückstellung — belegt

Auftrag: „Stellst du den Modus fuer diesen Test um, stelle ihn danach
wieder auf 660 fest zurueck und belege das per Read-back." Erfüllt, siehe
Schritt 3 der Tabelle und Rohdatei `gate_step4_restored.txt`
(17:18:16 Gerätezeit): `Priority: 1000000`, `LDAC quality mode: MID`,
`LDAC transmission bitrate (Kbps): 660`, `mCodecSpecific1: 1001` — exakt
der Ausgangszustand aus Abschnitt 1.1.

Nicht durch mich verändert und nicht geprüft: `Bluetooth audio codec`
(LDAC), `sample rate` (96 kHz), `bits per sample` (32), `channel mode`
(Stereo) — diese Dialoge wurden versehentlich einmal geöffnet
(„Bits Per Sample", „Channel Mode", s. u.) und **beide Male ausschließlich
per Zurück-Taste verlassen, nie mit „OK" bestätigt** — die bereits aktive
Auswahl (32 bits/sample bzw. Stereo) blieb unangetastet. Kein Soll-Ist-
Vergleich dieser Felder nötig, da nie ein Bestätigungs-Tap erfolgte.

---

## 2. Abbruch gemäß Auftrag

„Traegt die Gleichsetzung nicht, brichst du hier ab, meldest
`STATUS: teilweise` und faehrst Phase 1-3 NICHT." — befolgt. Es wurde
**keine** Störstärke appliziert, **keine** Kennlinie aufgenommen, **keine**
der drei in Phase 3 verlangten Fixtures aufgezeichnet.

M-11 bleibt nach diesem Ergebnis **im ursprünglich definierten Sinn**
(Konstanz über `Priority` verifizierbar) unmessbar, und R-E bleibt in dem
Sinn endgültig. Ob der Nebenbefund aus 1.3 (Konstanz stattdessen über das
Textfeld `LDAC quality mode` + ABR-Zeilen verifizierbar) einen neuen Anlauf
rechtfertigt, ist eine Frage an den `director` — siehe Abschnitt „Fehlerfunde
und Empfehlungen" im Übergabebericht.

---

## 3. Was NICHT gemessen wurde und warum

- **Keine Kennlinie Störstärke↔Verlustrate.** Gate negativ, Phase 2 entfällt
  per Auftrag.
- **Keine der drei Phase-3-Fixtures** (echter Verlust-Dump, Dump ohne
  ABR-Zeilen, je ein Dump pro Rung) aufgenommen — sie bleiben offen, wie vor
  diesem Lauf.
  - Kleiner Fortschritt am Rande, der für Phase 3 nützlich sein könnte,
    aber nicht dafür erhoben wurde: Die Gate-Session hat nebenbei belegte,
    stufenreine Kurz-Dumps für 660/330/ABR(990) mit je ~0 `dropped`
    produziert (`gate_initial_full.txt`, `gate_step2_330.txt`,
    `gate_step3_abr.txt`) — **keine Verlust-Fixture** (Ziel 1 von Phase 3
    bleibt offen), aber möglicher Rohstoff für Ziel 3 („je ein Dump pro
    Rung"), sofern der `director` Phase 3 unabhängig vom Gate freigibt.
    Diese Dumps liegen nur im Scratchpad (s. Abschnitt 0), nicht im Repo,
    und verfallen mit der Session.
- **Kein Urteil über die genaue Kodierung von `codecSpecific1`** (ob
  1001/1002/1003 direkt `A2DP_LDAC_QUALITY_*`-Konstanten plus Offset 1000
  sind) — die Musterbeobachtung reicht für die Gate-Frage, eine genaue
  Rückführung auf AOSP-Konstanten wäre ein eigener Rechercheauftrag.
- **Keine Aussage zur Hörbarkeit** — wie vom Auftrag verlangt, an keiner
  Stelle enthalten.

---

## 4. Aufgeräumt (SR-012)

- `/data/local/tmp/btperf/t027` auf dem Gerät entfernt (`rm -rf`), war
  durchgehend leer.
- Übriger Inhalt von `/data/local/tmp/btperf/` (T-008/T-011-Altlasten:
  `A1`…`S4b`, `m5/`, `run.sh`, `snapshot.sh`, …) **nicht** angefasst — nicht
  von diesem Lauf erzeugt, außerhalb des Auftrags.
  Zeitstempel zeigen bereits am 2026-08-30/-09-02 08:xx angelegte Dateien.
- Entwickleroptionen-Bildschirm verlassen, Gerät zurück auf Homescreen
  (`input keyevent 3` [KEYCODE_HOME]).
- Kein neuer Prozess auf dem Gerät gestartet, kein Force-Stop, keine App
  installiert/deinstalliert.
- `adb`-Server läuft weiter (kein `kill-server` am Ende — unkritisch, kein
  R-2-Risiko, da nur die eine Binary je verwendet wurde).

---

## 5. Zustandsbuch (kompakt)

| Feld | Vor Session (Übergabe) | Nach Session (belegt 17:18:16) |
|---|---|---|
| `Priority` | 1000000 | **1000000** — unverändert |
| `LDAC quality mode` | MID | **MID** — unverändert |
| `LDAC transmission bitrate (Kbps)` | 660 | **660** — unverändert |
| `mCodecSpecific1` | 1001 (aus Historie vor Sessionbeginn, 17:01:40) | **1001** — unverändert |
| Bluetooth-Verbindung | verbunden, Musik läuft | verbunden, Musik läuft (nicht unterbrochen) |
| App-Installationszustand | nicht geprüft, nicht Teil des Auftrags | nicht angefasst |
| adb-Binary | — | ausschließlich platform-tools, `C:\RSL` nie aufgerufen |

---

## 6. Director-Entscheidung und Fixture-Sicherung (Ergänzung A)

Der `director` hat das Gate-Ergebnis aus Abschnitt 1 bestätigt (`Priority`
≠ `codecSpecific1`, sauber belegt), aber die Gate-**Frage** neu gefasst: der
eigentliche Zweck war Stufenkonstanz-Nachweis, nicht das Feld `Priority`
speziell. Mit der Dreifachprüfung
(`mCodecSpecific1` konstant + `LDAC quality mode` konstant + ABR-Zeilen
abwesend) — die diese Session bereits als Nebenbefund geliefert hat (1.3) —
gilt das Gate als bestanden. Verbindlich für Phase 1–3: **jede Zelle
protokolliert alle drei Größen**, nicht nur eine. Weicht eine ab, ist die
Zelle konfundiert und als solche zu markieren.

Die drei stufenreinen Kurzdumps aus der Gate-Session (Abschnitt 1.2, keine
Verluste, aber sauberer Beleg für „ABR-Zeilen an/abwesend je nach Modus")
wurden als verbatim-Fixtures gesichert, bevor der Scratchpad verfällt:

| Datei | Repo-Pfad | Deckt |
|---|---|---|
| `bt_manager_pixel11_ldac_pinned_660.txt` | `core-monitor/src/test/resources/dumps/` | Dump ohne ABR-Zeilen, fest 660/MID |
| `bt_manager_pixel11_ldac_pinned_330.txt` | dito | Dump ohne ABR-Zeilen, fest 330/LOW |
| `bt_manager_pixel11_ldac_best_effort_990_freshly_selected.txt` | dito | Dump MIT ABR-Zeilen, direkt nach Moduswechsel auf ABR |

Provenienznotizen in `core-monitor/src/test/resources/dumps/README.md`
ergänzt (Tabelle, gleiche Konvention wie die bestehenden Einträge). Kein
MAC in den beiden Blöcken (`A2DP State:`/`A2DP LDAC State:`) — Redaktion
nicht nötig, geprüft. Deckt zwei der drei Phase-3-Lücken aus T-027 ab
(„Dump ohne ABR-Zeilen", „je ein Dump pro [fester] Stufe" — die dritte,
Verlust-Dump, fällt erst in Phase 2 an, s. u.). **Kein** dieser drei Dumps
zeigt `dropped`/`dropouts` != 0 — Ruhezustand, keine Verlust-Fixture.

---

## 7. Phase 1 — Ruhelauf bei gepinnten 660 kbps — ABGESCHLOSSEN

Gestartet 17:27:00, gestoppt 18:06:57, ausgewertete Spanne 17:27:07,66 –
18:06:54,60 Gerätezeit (aus den Sample-Zeitstempeln selbst, nicht aus den
Shell-Aufrufzeiten).

### 7.1 Methodik

- Vorher-Read-back (17:27:00, vollständiger Dump): Dreifachprüfung bestanden
  — `mCodecSpecific1: 1001`, `LDAC quality mode: MID`, ABR-Zeilen abwesend,
  `Priority: 1000000`, `dropped`/`dropouts` Stand 0/0 (kumulativ seit
  Sessionbeginn).
- Werkzeug: `docs/perf/tools/m5_run.sh`-Methodik unverändert übernommen
  (reduzierte Zwei-Block-Aufzeichnung `A2DP State:` + `A2DP LDAC State:` je
  Sample, ~1 s Kadenz, Gerätewalluhrzeit je Sample), device-seitig unter
  `/data/local/tmp/btperf/t027p1/series_run.sh`, lief als Hintergrundprozess
  über die Bash-Tool-Session (kein `nohup`-Detach nötig, die adb-Shell blieb
  für die volle Laufzeit offen). Gestoppt per `stop`-Marker, nicht
  abgeschnitten — auf Weisung des `director` erst nach Erreichen der
  Ziellänge.
- Kein Stör-Stimulus in dieser Phase — reine Referenz, wie M-5.
- Nachher-Read-back (18:07:09, vollständiger Dump): Dreifachprüfung erneut
  bestanden — `mCodecSpecific1: 1001` (Zeile `mCodecConfig: {codecName:LDAC,
  ...}`, unverändert), `LDAC quality mode: MID`, ABR-Zeilen abwesend,
  `Priority: 1000000`, `Packet counts (expected/dropped): 431323 / 0`.
- Rohreihe (`series.log`, 3,4 MB, 61 413 Zeilen, 1861 Samples) liegt wie in
  T-007/T-008/T-011 **nur im Scratchpad**, nicht im Repo:
  `t027p1_series.log`/`.csv` unter dem in Abschnitt 0 genannten Pfad.
- Zustandsbuch bei Laufende (nicht Teil der Dreifachprüfung, aber
  festgehalten): App weiterhin nicht installiert (`pm list packages`,
  rc=1), WLAN weiterhin auf dem 5-GHz-Link desselben APs (`Frequency:
  5200MHz`, unverändert zum Stand aus Abschnitt 8), Bildschirm im
  `Dozing`-Zustand (unbeaufsichtigter Lauf, erwartbar), Thermal Status 0
  (kein Throttling).

### 7.2 Auswertung — jeder Sample-Übergang geprüft, nicht nur die Eckwerte

| Größe | Wert |
|---|---|
| Samples | 1861 |
| Geprüfte Übergänge | **1860 von 1860** |
| Dauer | 2386,9 s = **39,78 min** (> M-5s 38,93 min) |
| Kadenz | Ø 1,283 s (2386,9 s / 1860 Intervalle) |
| `LDAC quality mode`-Abweichungen | **0 von 1861** — durchgehend `MID` |
| ABR-Zeilen-Abweichungen | **0 von 1861** — durchgehend abwesend |
| Negative Zähler-Deltas (Reset-Indiz) | **0** bei `dropped`, `dropouts`, `underflow` |
| `dropped` | 0 → 0, **Δ = 0** |
| `dropouts` | 0 → 0, **Δ = 0** |
| `underflow` | 2 → 1353, **Δ = 1351**, **33,96/min** |

**Dreifachprüfung bestanden, an jedem der 1860 Übergänge, nicht nur an den
Eckwerten** — exakt die Disziplin, die laut Auftrag M-5 belastbar gemacht
hat. Diese Zelle ist **nicht konfundiert**.

**Dreierregel-Obergrenze für `dropped`/`dropouts` bei gepinnten 660 kbps,
ohne Störung:** 0 Ereignisse über 39,78 min ⇒ **0,0754/min** (3 / 39,78).
Dies ist die **erste** Ruheraten-Referenz bei gepinnter (nicht-ABR) Stufe
im Projekt — die Lücke, die T-027 ausdrücklich benannt hatte (M-5 maß nur
unter ABR). Nicht mit der ABR-Ruherate aus `docs/perf/baselines.md`
gepoolt — andere Bedingung (Stufe gepinnt statt ABR), eigener Abschnitt
dort angelegt (s. Abschnitt 7.3).

**Nebenbefund, kein Verdikt (nach T-009/R-D-Konvention):** `underflow`
liegt mit 33,96/min deutlich über der ABR-Ruherate aus M-5 (0,591/min,
39-min-Lauf) und auch über der WLAN-aus-Referenz aus T-007 (0/514 s). Das
ist eine ~57-fache Differenz zwischen zwei Ruheläufen, die sich nur in der
LDAC-Stufe (gepinnt 660 vs. ABR) unterscheiden — auffällig, aber `underflow`
trägt laut Projektregel weiterhin **kein Verdikt** und wird hier nur
festgehalten, nicht bewertet. Mögliche Erklärungsansätze (z. B. andere
Host-Scheduling-Charakteristik ohne laufenden `ldac_ABR_Proc()`) wären
Recherche, nicht Messung — nicht hier verfolgt.

### 7.3 Baseline-Eintrag

Als echter Vorher/Nachher-Messwert mit Budget-Markierung in
`docs/perf/baselines.md` unter einem **neuen** Szenario-Abschnitt
„LDAC-Wiedergabe, Ruherate bei GEPINNTEN 660 kbps" eingetragen — nicht mit
dem bestehenden ABR-Ruherate-Abschnitt vermischt, da die Stufe (gepinnt vs.
ABR) selbst die Szenario-Definition ist.

---

## 8. Phase 2 — blockiert: 2,4-GHz-Stimulus nicht ohne Weiteres herstellbar

**Rückfrage an den `director`, bevor Phase 2 beginnt.**

R-007 (Hebel B, höchster Rang) setzt „kontrollierte 2,4-GHz-Belegung" über
WLAN-Dauerlast an. Read-back zeigt: Das Gerät ist **mit dem 5-GHz-Link**
desselben Access Points assoziiert (`dumpsys wifi`: `SSID: "SSID_A"`,
`Frequency: 5200MHz`, WPA3-SAE, MLO-fähiger AP). Der AP bietet zusätzlich
einen 2,4-GHz-Link (`MloLink{2.4GHz, channel: 6, ...}`), der aber laut
demselben Dump **`MLO_LINK_STATE_UNASSOCIATED`** ist — die Datenverbindung
läuft faktisch nicht über 2,4 GHz.

**Warum das die Kennlinie entwertet, wenn ungeprüft weitergemacht wird:**
Der in R-007 unterstellte Mechanismus (PTA-Arbitrierung zwischen
Bluetooth- und WLAN-**2,4-GHz**-Sendeanteil im Kombichip) hängt strukturell
an gemeinsamer 2,4-GHz-Funknutzung. Last auf dem 5-GHz-Link träfe
mutmaßlich ein anderes, schwächer belegtes Chipset-internes
Konkurrenzverhältnis (falls überhaupt eines) — R-007 selbst benennt genau
diesen Nebeneffekt als unquantifiziert. Eine Kennlinie unter 5-GHz-Last als
„2,4-GHz-Belegung" auszugeben wäre keine kleine Ungenauigkeit, sondern eine
falsche Beschriftung der ganzen Phase-2-Messreihe.

**Geprüfte, nicht-invasive Auswege — beide ausgeschlossen ohne weiteren
Eingriff:**
- Entwickleroptionen/Wi-Fi-Einstellungen auf ein „Frequenzband
  bevorzugen"-Feld durchsucht (Suchfunktion, Stichwort „frequency band") —
  **kein Treffer** auf diesem Build.
- `adb shell cmd wifi` bietet `connect-network <ssid> ... -b <bssid>`, mit
  dem sich gezielt an die 2,4-GHz-BSSID desselben Netzes
  (`AP_BSSID`, aus der Verbindungshistorie bekannt) binden ließe —
  **braucht das WLAN-Passwort** (WPA3-SAE), das ich nicht habe und nicht
  raten werde. Selbst damit ist unklar, ob ein MLO-fähiger AP die Bindung an
  nur einen Link überhaupt zulässt oder zurück auf den besseren Link
  roamt — ungeprüft, keine Quelle dazu eingesehen.

**Optionen für den `director`, keine Entscheidung von mir:**
1. WLAN-Passwort bereitstellen, ich versuche `-b`-Pin auf die 2,4-GHz-BSSID
   und prüfe per Read-back, ob die Bindung hält (`dumpsys wifi`,
   `MloLink{2.4GHz,...}` Zustand).
2. Zweiten Störhebel aus R-007 freigeben, der kein WLAN-Passwort braucht —
   R-007 nennt als Alternative zu iperf3 ein **zweites, aktiv Traffic
   erzeugendes Bluetooth-Gerät** (Audio-/BLE-Dauerverkehr); ich habe keins
   zur Verfügung, müsste eins vom Nutzer bekommen.
3. Belegen, dass 5-GHz-Last trotzdem einen messbaren Effekt auf die
   Bluetooth-Verlustzähler hat, **mit expliziter Kennzeichnung** als
   „WLAN-Last auf 5 GHz, Mechanismus ungeklärt" statt „2,4-GHz-Belegung" —
   ändert die Aussagekraft der ganzen Kennlinie, deshalb keine Entscheidung,
   die ich allein treffen will.
4. Phase 2 hier stoppen, Phase 1 (läuft bereits) und die gesicherten
   Fixtures als Ergebnis dieses Laufs stehen lassen.

Phase 1 läuft unabhängig von dieser Frage weiter und wird nicht durch den
Block aufgehalten.

---

## 9. Director-Entscheidung zu Phase 2 und Beschriftungsregel

**Entschieden (2026-09-02): Option 3.** Phase 2 läuft mit Last auf dem
vorhandenen 5-GHz-Link. **Verbindliche Beschriftung, ab hier durchgehend:**
Der Stimulus heißt **nicht** „2,4-GHz-Belegung". Er heißt „Last auf dem
5-GHz-Link desselben Access Points, bei einem Gerät, dessen 2,4-GHz-Link
unassoziiert ist". **Der wirkende Mechanismus ist ungeklärt** — R-007s
Hebel B setzt an der PTA-Arbitrierung mit dem 2,4-GHz-Sendeanteil an; ob
5-GHz-Last denselben, einen schwächeren oder gar keinen
Konkurrenzmechanismus trifft, ist nicht belegt. **Diese Kennlinie ist keine
Kalibrierung von Verlustrate gegen Störstärke im Sinne von M-11** — sie ist
ein Vorlauf, der zeigt, ob dieser Stimulus überhaupt greift. Ein negatives
Ergebnis (kein messbarer Verlust auch auf der höchsten Dosierstufe) ist ein
vollwertiges, sogar nützliches Ergebnis und wird als solches gemeldet, ohne
weitere Ersatzstimuli zu erfinden. Kein WLAN-Passwort angefragt, keins
verwendet — die vorhandene, bereits authentifizierte Verbindung des
Geräts wird genutzt, nichts Neues verbunden.

### 9.1 Stimulus-Mechanik (vorbereitet, noch nicht am Gerät ausgeführt)

- **Host-Sender/-Senke:** Python-TCP-Sink-Server
  (`t027_sink_server.py`, Scratchpad, außerhalb des Repos) auf
  `IP_1:5501` (dieser Host, dasselbe LAN wie das Telefon,
  `IP_2`, beide am AP „SSID_A"). Nimmt beliebig viele parallele
  Verbindungen an, liest und verwirft, protokolliert alle 5 s
  Verbindungszahl und tatsächlichen Durchsatz (Mbit/s) — das dokumentiert
  die **tatsächlich erreichte** Last je Stufe, nicht nur die Dosier-Stellung.
  Per Loopback-Test verifiziert; Windows-Firewall erlaubt eingehende
  `python.exe`-TCP-Verbindungen auf dem `Private`-Profil, das aktive Profil
  von „SSID_A" ist `Private` (per `Get-NetConnectionProfile` geprüft) — kein
  Firewall-Eingriff nötig oder vorgenommen.
- **Geräteseitiger Lastgenerator:** `t027p2_cell.sh` (Scratchpad, auf Gerät
  unter `/data/local/tmp/btperf/t027p2/cell.sh` abgelegt, noch nicht
  ausgeführt). Dosierhebel: **Anzahl paralleler `dd if=/dev/zero | nc
  <host> <port>`-Ströme** (0/1/2/4/8/16 vorgesehen, s. u.) — jeder Strom
  drückt Nullbytes mit der Rate, die `dd`/`nc`/die WLAN-Verbindung
  hergeben, `timeout` begrenzt die Laufzeit je Zelle serverseitig
  identisch zur Sampler-Laufzeit. Kein neues Geräte-Binary — `nc`, `dd`,
  `timeout`, `/dev/zero` sind alle bereits vorhandene Toybox-Bordmittel
  (geprüft vor Einsatz).
- **Sampler je Zelle:** dieselbe reduzierte Zwei-Block-Methodik wie Phase 1
  (`A2DP State:`/`A2DP LDAC State:`, ~1 s Kadenz), in einer **eigenen**
  `series.log` je Zelle unter `/data/local/tmp/btperf/t027p2/<label>/` —
  bestätigt `LDAC quality mode` und ABR-Zeilen-An-/Abwesenheit bei **jedem**
  Sample, nicht nur an den Rändern.
- **`mCodecSpecific1`** steht nicht im reduzierten Zwei-Block-Ausschnitt
  (nur in der `CODEC_CONFIG_CHANGED`-Historie des vollen Dumps). Es wird
  daher wie in T-008/T-011/Phase 1 **zweimal je Zelle** per vollem
  `dumpsys bluetooth_manager` geprüft — unmittelbar vor und unmittelbar
  nach jeder Zelle — statt bei jedem Sample, um den Beobachtereffekt nicht
  in jede Sekunde zu tragen (dieselbe Begründung wie in
  `docs/perf/baselines.md` „Messmethodik"-Abschnitt). Weicht eine der drei
  Prüfgrößen an einer Zellgrenze ab, wird die Zelle als konfundiert
  markiert.
- **Geplante Stufen:** 0 (Kontrollzelle, kein Stimulus, direkt nach Phase 1
  zur Anschlusskontrolle), 1, 2, 4, 8, 16 parallele Ströme — sechs Zellen,
  monoton steigend, danach eine Rückkehr auf 0 (A/B/A'-Disziplin nach
  T-008-Vorbild). Dauer je Zelle **240 s** — deutlich kürzer als M-5s
  ~39 min, bewusst so gewählt: Phase 2 ist laut Director-Entscheidung
  ausdrücklich ein **Vorlauf**, keine M-11-Kalibrierung; die Dauer reicht
  für ~240 Samples je Zelle und eine belastbare Rate, ohne die Sitzung auf
  mehrere Stunden auszudehnen. Wird nach Abschluss mit den tatsächlichen
  Werten belegt, nicht vorab als Zusage behandelt.
- **Zeitstempel je Stufe** werden aus `meta.txt` (Gerätewalluhrzeit, Start/
  Ende je Zelle) übernommen, damit der `director` Nutzer-Rückmeldungen den
  Zellen zuordnen kann. Kein Wort zur Hörbarkeit von mir.

**Stand zum Zeitpunkt der ursprünglichen Fassung (historisch):** Kein
`nc`/`dd`-Strom war zu diesem Zeitpunkt vom Gerät gestartet worden, Phase 1
lief noch. **Beide inzwischen abgeschlossen:** Phase 1 s. Abschnitt 7,
Phase 2 (alle 7 Zellen gelaufen und ausgewertet) s. Abschnitt 9.2–9.3,
Gesamtergebnis s. Abschnitt 10.

### 9.2 Phase 2 — vollständige Zellentabelle (Auswertungslauf, s. Abschnitt 9.3 zur Methodik)

**Sink-Server:** ein sauberer Prozess auf `IP_1:5501` (zwei
frühere Testinstanzen aus dem Loopback-Smoketest identifiziert und beendet,
bevor die erste echte Zelle lief — sonst hätten zwei Listener um
eingehende Verbindungen konkurriert). Alle sieben Zellen sind gelaufen und
sauber protokolliert (`t027-rawdata/t027p2/<label>/{meta.txt,series.log}`).

Je Zelle: `mSpec1` = `mCodecSpecific1` aus dem vollen `mCodecConfig`-Block
(nicht die Historie), `Qmode` = `LDAC quality mode`, `ABR` = Anwesenheit der
beiden ABR-Zeilen. Dreifachprüfung „bestanden" heißt: `mSpec1`=1001,
`Qmode`=MID, `ABR`=abwesend — **zusätzlich** an **jedem** Sample der
reduzierten Zwei-Block-Reihe (nicht nur Vorher/Nachher) für `Qmode` und
`ABR` geprüft (s. Abschnitt 9.3.2); `mSpec1` nur an den beiden vollen
Dumps je Zelle, wie geplant (Abschnitt 9.1). **Start/Ende (Gerätezeit)** in
der Tabelle unten ist der Zeitpunkt, den `cell.sh` selbst in `meta.txt`
festhält (Beginn/Ende des Stimulus- und Sampler-Laufs) — nicht der
Zeitpunkt der vollen Vorher/Nachher-Dumps, die einige Sekunden davor bzw.
danach liegen (s. 9.3.1/9.3.3 zur zeitlichen Einordnung dieser Dumps).

| Zelle | Stufe | Start (Gerätezeit) | Ende (Gerätezeit) | Dreifachprüfung vorher | Dreifachprüfung nachher | `dropped`/`dropouts` (roh, Δ über die Zelle) | `dropped`/`dropouts` je Minute | Ist-Durchsatz (Sink-Log, korreliert, s. 9.3.1) | Status |
|---|---|---|---|---|---|---|---|---|---|
| `level0_control` | 0 (kein Stimulus) | 18:11:43 | 18:15:48 | bestanden (`mSpec1`=1001, `Qmode`=MID, `ABR` abwesend) | bestanden (identisch) | 0/0 | 0,00 / 0,00 | **0 Mbit/s durchgehend**, 0 aktive Verbindungen (187 Samples, 244,13 s) | **abgeschlossen, nicht konfundiert** |
| `level1_1stream` | 1 Strom | 18:16:19 | 18:20:24 | bestanden | bestanden | 0/0 | 0,00 / 0,00 | **297,9 Mbit/s** Mittel (sd 60,6), Spanne 178,8–372,6, 1 aktive Verbindung durchgehend (185 Samples, 243,75 s) | **abgeschlossen, nicht konfundiert** |
| `level2_2stream` | 2 Ströme | 18:22:15 | 18:26:20 | bestanden | bestanden | 0/0 | 0,00 / 0,00 | **283,6 Mbit/s** Mittel (sd 83,8), Spanne 136,1–434,3, 2 aktive Verbindungen durchgehend (180 Samples, 244,02 s) | **abgeschlossen, nicht konfundiert** |
| `level4_4stream` | 4 Ströme | 18:26:51 | 18:30:56 | bestanden | bestanden | 0/0 | 0,00 / 0,00 | **353,8 Mbit/s** Mittel (sd 71,6), Spanne 62,9–453,7 (ein einzelner Tiefpunkt kurz vor Zellende, s. 9.3.1), 4 aktive Verbindungen durchgehend (176 Samples, 243,50 s) | **abgeschlossen, nicht konfundiert** |
| `level8_8stream` | 8 Ströme | 18:31:20 | 18:35:25 | bestanden | bestanden | 0/0 | 0,00 / 0,00 | **363,0 Mbit/s** Mittel (sd 27,2), Spanne 291,9–454,4 — engste Streuung aller Zellen, 8 aktive Verbindungen durchgehend (179 Samples, 243,60 s) | **abgeschlossen, nicht konfundiert** |
| `level16_16stream` | 16 Ströme | 18:35:49 | 18:39:55 | bestanden | bestanden | 0/0 | 0,00 / 0,00 | **326,1 Mbit/s** Mittel (sd 94,6), Spanne 69,1–434,8 (ein ~25-s-Einbruch auf ~70 Mbit/s in der Zellmitte, s. 9.3.1), 16 aktive Verbindungen durchgehend (178 Samples, 244,44 s) | **abgeschlossen, nicht konfundiert** |
| `level0b_return` | 0 (Rückkehr, A/B/A') | 18:40:20 | 18:44:25 | bestanden | bestanden | 0/0 | 0,00 / 0,00 | **0 Mbit/s durchgehend**, 0 aktive Verbindungen (179 Samples, 243,04 s) | **abgeschlossen, nicht konfundiert — s. 9.3.4 zur A/B/A'-Auswertung** |

**Dreifachprüfung bestanden in allen 7 Zellen, an beiden vollen Dumps (vor
und nach jeder Zelle) und an jedem Sample der reduzierten Reihe** — keine
Zelle ist konfundiert. `LDAC saved transmit queue length` war zusätzlich in
**allen 1264 Samples aller 7 Zellen exakt 0** (kein einziger Wert > 0) —
selbst bei 16 parallelen Strömen und ~370 Mbit/s Ist-Last kein Rückstau in
der LDAC-Sendequeue.

`dropped` und `dropouts` blieben in **jeder** Zelle über **jeden**
Sample-Übergang bei 0 — geprüft, nicht nur an den Eckwerten (Methodik s.
9.3.2). Über alle 7 Zellen kombiniert (1706,48 s = 28,44 min, 1264 geprüfte
Übergänge): **0 Ereignisse**.

**Ist-Durchsatz bereits bei einem einzelnen Strom erheblich** (~298 Mbit/s
Mittel, nach kurzem TCP-Slow-Start) und bleibt bei allen fünf
Dosierstufen (1–16 Ströme) im selben Grössenbereich (~280–365 Mbit/s Mittel)
— der 5-GHz-Link ist bei jeder Dosierstufe substanziell belastet, auch wenn
der wirkende Mechanismus auf die Bluetooth-Verlustzähler ungeklärt bleibt
(s. Beschriftungsregel oben). Die Streuung selbst ist Teil des Befunds und
kein Messfehler: Sie folgt sichtbar dem Wettbewerb zwischen den parallelen
`dd|nc`-Strömen um denselben Link, nicht einer instabilen Messung (s. 9.3.1).

### 9.3 Methodik dieser Auswertung

Dieser Abschnitt (9.3) und Abschnitt 10 sind der **Auswertungslauf** dieser
Datei — erstellt ausschließlich aus vorliegenden Rohdaten, ohne jeden
Geräte- oder `adb`-Zugriff. Rohdatenquellen:

- `C:\Users\Daniel\t027-rawdata\t027p1\` (Phase 1: `series.log`, `count`,
  `donemark`, `series_run.sh` — bereits in Abschnitt 7 vollständig
  ausgewertet, hier unverändert übernommen).
- `C:\Users\Daniel\t027-rawdata\t027p2\` (Phase 2: sieben Zellverzeichnisse
  `level0_control`, `level1_1stream`, `level2_2stream`, `level4_4stream`,
  `level8_8stream`, `level16_16stream`, `level0b_return`, je mit
  `meta.txt` und `series.log`, plus `cell.sh` als Erzeugerskript).
- Ergänzend, aus dem Scratchpad **derselben Session** (nicht einer fremden,
  beendeten Session — der Scratchpad-Pfad dieser Auswertung ist identisch
  mit dem in `t027-rawdata` referenzierten): 14 volle
  `dumpsys bluetooth_manager`-Dumps (`p2_level<N>_pre.txt`/`_post.txt`, je
  einer vor und nach jeder der 7 Zellen) und `t027_sink_server.log`
  (host-seitiges Durchsatzprotokoll, 5-s-Kadenz, 708 Zeilen,
  18:09:49–19:08:49 Hostzeit). **Beide waren entgegen der ursprünglichen
  Annahme im Auftrag nicht verloren** — s. 9.3.3.

Kein Wort zur Hörbarkeit; keine der beiden Quellen wurde dafür herangezogen
oder ausgewertet.

#### 9.3.1 Korrelation des Sink-Logs mit den Geräte-Zellen — Uhrenversatz

Das Gerät schreibt seine eigenen Zeitstempel (`meta.txt`: `start=`/`end=`
in Nanosekunden seit Epoche, aus `date +%s%N` auf dem Gerät). Der Host, auf
dem der Sink-Server läuft, führt eine eigene, unsynchronisierte Uhr.
Direkter Abgleich (Gerätezeitstempel unverändert als Hostzeit interpretiert)
zeigte eine klare Verschiebung: In der so berechneten `level0_control`-
Fensterspanne erschien bereits Datenverkehr, der eindeutig zu
`level1_1stream` gehört (1 aktive Verbindung, ansteigender Durchsatz) —
unmöglich für eine Kontrollzelle mit `n_streams=0`.

**Kalibrierung über einen unabhängigen Anker:** Die 14 vollen
Vorher/Nachher-Dumps tragen Datei-Zeitstempel in Host-Wanduhrzeit (NTFS-
`mtime`, z. B. `p2_level0_pre.txt` → 18:10:04,10 Host). Der Dump-Inhalt
selbst enthält keine Geräte-Uhrzeit, aber Abschnitt 9.2/9.3.2 dieser Datei
weist bereits **vor** diesem Auswertungslauf dieselben Zellen mit
Geräte-Zeit-Zeitstempeln aus (z. B. `level0_control` Pre-Read-back
18:11:35 Gerätezeit, aus einer früheren Fassung dieses Dokuments — s.
Git-Historie). Differenz: 18:11:35 − 18:10:04,10 ≈ **90,9 s**, Gerät vor
Host. Das deckt sich, bis auf ~2 s, mit dem in T-008/T-011 unabhängig
belegten Versatz von **89 s** (Gerät vor Host, s.
`docs/perf/T-011-messung.md` Abschnitt 3) — **derselbe Versatz, mit
unabhängiger Methode reproduziert.** Verwendet: **Host-Zeit = Geräte-Zeit
− 89 s.**

Mit dieser Korrektur verschwindet die Anomalie vollständig:
`level0_control` zeigt **0 Mbit/s über die volle Fensterspanne**, keine
einzige aktive Verbindung — wie für eine Kontrollzelle erwartet. Dasselbe
gilt für `level0b_return`. Die fünf Stimulus-Zellen zeigen exakt die
erwartete Anzahl aktiver Verbindungen (`max active_conns` = 1/2/4/8/16,
deckungsgleich mit `n_streams` aus `meta.txt`) — ein weiterer unabhängiger
Beleg, dass die Dosierung sauber griff und keine Zelle mit einer
Nachbarzelle vermischt ist.

**Auffällige Einzelwerte, benannt, nicht bewertet:**
- `level4_4stream`: ein einzelner Tiefpunkt auf 62,9 Mbit/s in der
  vorletzten 5-s-Kachel der Zelle (Rest der Zelle 214–454 Mbit/s) — zeitlich
  am Zellende, konsistent mit dem Verbindungsabbau der `timeout`-begrenzten
  `dd|nc`-Prozesse, keine Auffälligkeit in den BT-Zählern zu diesem
  Zeitpunkt.
- `level8_8stream`: derselbe Rand-Effekt, 36,5 Mbit/s in der letzten Kachel.
- `level16_16stream`: ein **~25 s anhaltender** Einbruch auf 69–72 Mbit/s
  in der **Zellmitte** (nicht am Rand), fünf aufeinanderfolgende
  5-s-Kacheln, danach Rückkehr auf 340+ Mbit/s. Ursache nicht untersucht
  (auf dem Host oder im WLAN, nicht am BT-Stack — die
  Dreifachprüfung und `dropped`/`dropouts` bleiben über exakt dieses
  Fenster unverändert bei 0). Für den Zweck dieser Phase (Kennlinie
  Verlustrate) folgenlos — festgehalten, weil eine spätere Auswertung mit
  anderer Fragestellung (z. B. WLAN-Durchsatzstabilität) das brauchen
  könnte.

Alle Rohwerte (5-s-Kacheln je Zelle) sind über `t027_sink_server.log`
nachvollziehbar; hier nur Mittel/sd/Min/Max berichtet (Streuung s. Tabelle
9.2).

#### 9.3.2 `dropped`/`dropouts`/`underflow` — jeder Sample-Übergang geprüft, nicht nur die Eckwerte

Wie in Phase 1 (Abschnitt 7.2) und nach M-5-Vorbild: für jede der 7 Zellen
wurde **jeder** Sample-zu-Sample-Übergang der reduzierten Zwei-Block-Reihe
auf ein Delta > 0 bei `dropped`/`dropouts` und auf negative Deltas
(Zähler-Reset-Indiz) geprüft — nicht nur Anfangs-/Endwert.

| Zelle | Samples | Geprüfte Übergänge | Kadenz Ø / sd | Lücken > 4 s | `dropped` Δ | `dropouts` Δ | Negative Deltas (`dropped`/`dropouts`/`underflow`) | `underflow` Δ | `underflow`/min |
|---|---|---|---|---|---|---|---|---|---|
| `level0_control` | 187 | 186/186 | 1,313 s / 0,059 s | 0 | 0 | 0 | 0/0/0 | 4 | 0,983 |
| `level1_1stream` | 185 | 184/184 | 1,325 s / 0,044 s | 0 | 0 | 0 | 0/0/0 | 2 | 0,492 |
| `level2_2stream` | 180 | 179/179 | 1,363 s / 0,059 s | 0 | 0 | 0 | 0/0/0 | 2 | 0,492 |
| `level4_4stream` | 176 | 175/175 | 1,391 s / 0,061 s | 0 | 0 | 0 | 0/0/0 | 0 | 0,000 |
| `level8_8stream` | 179 | 178/178 | 1,369 s / 0,059 s | 0 | 0 | 0 | 0/0/0 | 1 | 0,246 |
| `level16_16stream` | 178 | 177/177 | 1,381 s / 0,039 s | 0 | 0 | 0 | 0/0/0 | 4 | 0,982 |
| `level0b_return` | 179 | 178/178 | 1,365 s / 0,053 s | 0 | 0 | 0 | 0/0/0 | 2 | 0,494 |
| **Kombiniert** | **1264** | **1257/1257** | 1,355 s / 0,099 s (gepoolt über alle 7 Zellen; sd höher als je Einzelzelle, weil die Kadenz selbst leicht mit der Dosierstufe steigt, s. u.) | **0** | **0** | **0** | **0/0/0** | **15** | **0,527** |

**`dropped`/`dropouts` blieben in jeder Zelle und über die volle
kombinierte Dauer (1706,48 s = 28,44 min) exakt bei 0.** Kadenz steigt
leicht mit der Dosierstufe (1,313 s → 1,391 s Mittel bei `level4`) — die
`dumpsys`-Antwortzeit dehnt sich unter WLAN-Sättigung minimal, aber nie
über 1,57 s (Einzelwert-Maximum), keine einzige Lücke > 4 s in irgendeiner
Zelle.

**`underflow` (Nebenbefund, kein Verdikt — R-D/T-009-Konvention, wie in
Phase 1):** Rate über Phase 2 kombiniert 0,527/min, in derselben
Grössenordnung wie Phase 1 (33,96/min — **nicht** vergleichbar, da Phase 1
39,78 min lief und diese sieben ~4-min-Zellen erheblich kürzer sind und
zudem die Zellgrenzen selbst kleine Zählerdellen erzeugen können; kein
Versuch, die beiden Raten direkt zu vergleichen). Kein Muster erkennbar,
das mit der Dosierstufe korreliert (`level4` bei 4 Strömen 0/min, `level0`
bei 0 Strömen 0,983/min) — **kein Hinweis auf einen dosisabhängigen
Effekt**, aber `underflow` trägt ohnehin kein Verdikt.

`LDAC saved transmit queue length` war, wie in 9.2 vermerkt, in **allen**
1264 Samples aller 7 Zellen `0` — keine einzige Ausnahme.

#### 9.3.3 Rohdaten-Fund: Sink-Log und Volldumps waren nicht verloren

Der Auftrag ging davon aus, dass `t027_sink_server.log` „im Scratchpad
einer beendeten Session lief" und „vermutlich weg" ist. Bei der Suche nach
Rohdaten für diese Auswertung stellte sich heraus: Der Scratchpad-Pfad
dieser Session ist **derselbe** wie der in `docs/perf/T-027-messung.md`
bereits referenzierte
(`…\2318bcfa-d4e4-4383-883c-503395c73f98\scratchpad\`) — die Session war
nicht beendet bzw. ihr Scratchpad nicht verfallen. Gefunden und für diese
Auswertung verwendet, **ohne jede Schätzung oder Rekonstruktion, nur
gelesen**:

- `t027_sink_server.log` (708 Zeilen, 18:09:49–19:08:49 Hostzeit) — deckt
  alle 7 Zellen lückenlos ab, s. 9.3.1.
- 14 volle `dumpsys bluetooth_manager`-Dumps
  (`p2_level{0,1,2,4,8,16,0b}_{pre,post}.txt`) — liefern die
  `mCodecSpecific1`-Komponente der Dreifachprüfung für **alle** 7 Zellen
  (nicht nur `level0`/`level1`, wie im ursprünglichen Auftragsstand
  dokumentiert), s. Tabelle 9.2.

**Nicht gefunden und nicht rekonstruiert** (Auftragsvorgabe befolgt): die
genauen Host-Sendezeitpunkte einzelner `dd|nc`-Prozessstarts (nur der
Sink-seitige Empfang ist protokolliert, kein geräteseitiges Sendeprotokoll
über die reine Bytezahl/Verbindungszahl hinaus) sowie jede Angabe zur
Hörbarkeit (nicht Gegenstand dieser Auswertung, ohnehin nicht ermittelbar,
s. `docs/tasks/T-027.md`).

**Einordnung für den `director`:** Diese beiden Dateien lagen im
Scratchpad, nicht im Repo — sie sind mit dem Ende dieser Session weiterhin
verfallgefährdet. Falls sie über diese Auswertung hinaus als Rohbeleg
gebraucht werden (z. B. für eine Nachprüfung der Korrelation in 9.3.1),
müssten sie gesichert werden; das ist außerhalb des Umfangs dieses
Auftrags (der beschränkt sich auf `docs/perf/T-027-messung.md` und den
Phase-1-Eintrag in `docs/perf/baselines.md`).

#### 9.3.4 `level0b_return` als A/B/A'-Beleg

Die Rückkehrzelle ist nach demselben Muster wie `level0_control`
ausgewertet, nicht nur als weitere Stimulus-Zelle:

| Grösse | `level0_control` (A, vor dem Stimulus) | `level0b_return` (A', nach dem Stimulus) | Übereinstimmung |
|---|---|---|---|
| Ist-Durchsatz | 0 Mbit/s durchgehend, 0 Verbindungen | 0 Mbit/s durchgehend, 0 Verbindungen | **identisch** |
| `dropped`/`dropouts` | 0/0 über 187 Samples | 0/0 über 179 Samples | **identisch** |
| `underflow`/min | 0,983 | 0,494 | ähnliche Grössenordnung, kein Verdikt (s. 9.3.2) |
| `LDAC saved transmit queue length` | 0 in allen Samples | 0 in allen Samples | **identisch** |
| Dreifachprüfung (`mSpec1`/`Qmode`/ABR) | bestanden, vor und nach | bestanden, vor und nach | **identisch** |
| Kadenz Ø | 1,313 s | 1,365 s | im erwarteten Streubereich (s. 9.3.2) |

**`level0b_return` verhält sich in jeder geprüften Grösse wie
`level0_control`.** Das ist die A/B/A'-Voraussetzung dafür, ein
etwaiges Ergebnis der Stimulus-Zellen dem Stimulus zuzuschreiben statt
einer Drift über die Messsitzung (z. B. Erwärmung, Akkuzustand,
Hintergrundlast) — erfüllt. Da die Stimulus-Zellen selbst aber **keinen**
Effekt auf `dropped`/`dropouts` zeigten (s. 9.2/9.3.2), ist die A/B/A'-
Prüfung hier vor allem ein **Negativbeleg**: Sie schliesst aus, dass ein
unbeobachteter Drift-Effekt ein eigentlich vorhandenes Signal maskiert
haben könnte — die Rückkehrzelle liegt exakt dort, wo auch die
Kontrollzelle lag, es gibt also keine Drift, die etwas verdeckt haben
könnte.

---

## 10. Phase 2 — Gesamtergebnis

**In einem Satz:** Last auf dem 5-GHz-WLAN-Link (1 bis 16 parallele
TCP-Ströme, real gemessen ~280–365 Mbit/s Ist-Durchsatz je Dosierstufe, bis
zu 16 gleichzeitige Verbindungen) hat über 7 Zellen und 28,44 min
kombinierter Laufzeit bei gepinnten 660 kbps LDAC **keinen messbaren
Verlust erzeugt** — `dropped` und `dropouts` blieben in jeder einzelnen
Zelle und kombiniert exakt bei 0.

**Was das trägt und was nicht:**

- **Obergrenze nach der Dreierregel** (3 hypothetische Ereignisse /
  Gesamtdauer), je Zelle einzeln und kombiniert:

  | Zelle | Dauer | Obergrenze |
  |---|---|---|
  | `level0_control` | 244,13 s | 0,737/min |
  | `level1_1stream` | 243,75 s | 0,738/min |
  | `level2_2stream` | 244,02 s | 0,738/min |
  | `level4_4stream` | 243,50 s | 0,739/min |
  | `level8_8stream` | 243,60 s | 0,739/min |
  | `level16_16stream` | 244,44 s | 0,736/min |
  | `level0b_return` | 243,04 s | 0,741/min |
  | **Kombiniert (alle 7 Zellen)** | **1706,48 s (28,44 min)** | **0,105/min** |

  Diese Obergrenze ist **deutlich lockerer** als die von Phase 1
  (0,0754/min über 39,78 min) — Phase 2 lief in kürzeren Einzelzellen, um
  fünf Dosierstufen abzudecken, nicht um die tiefstmögliche Obergrenze zu
  erreichen (Auftragsvorgabe: „deutlich kürzer als M-5s ~39 min, bewusst so
  gewählt", Abschnitt 9.1). Eine Verlustrate zwischen 0,105/min und der
  in Phase 1 etablierten Obergrenze 0,0754/min könnte durch Phase 2 also
  **nicht** zuverlässig von Null unterschieden werden — dieser Vorbehalt
  gehört zur Aussage dazu.
- **Kein dosisabhängiges Muster in irgendeiner erhobenen Grösse**, weder
  bei `dropped`/`dropouts` (durchgehend 0) noch bei `underflow` (keine
  Korrelation mit Streamzahl, s. 9.3.2) noch bei der Sendequeue
  (durchgehend 0). Wäre der 5-GHz-Stimulus über einen PTA-artigen
  Mechanismus wirksam gewesen, wäre bei 16 Strömen (~326 Mbit/s Mittel,
  höchste Dosierstufe) am ehesten ein Effekt zu erwarten gewesen — auch
  dort: nichts.
- **`level0b_return` bestätigt, dass kein unbeobachteter Drift ein
  Signal hätte verdecken können** (Abschnitt 9.3.4) — das Nullergebnis ist
  nicht das Artefakt einer über die Sitzung driftenden Baseline.
- **Was diese Phase nicht ist, laut Director-Entscheidung (Abschnitt 9):**
  keine M-11-Kalibrierung, kein Beleg oder Widerlegung des in R-007
  unterstellten PTA-Mechanismus (der an **2,4-GHz**-Koexistenz ansetzt,
  nicht an 5-GHz-Last) — nur ein Vorlauf, der zeigt, ob **dieser konkrete,
  ersatzweise verfügbare** Stimulus greift. Das negative Ergebnis ist nach
  Director-Vorgabe „ein vollwertiges, sogar nützliches Ergebnis" und wird
  hier genau so gemeldet, ohne einen Ersatzstimulus zu erfinden.
- **Methodischer Vorbehalt bleibt bestehen** (Abschnitt 0.1): Das Gerät
  hing die gesamte Sitzung am USB-3-Kabel, das potenziell selbst ins
  2,4-GHz-Band abstrahlt. Auch dieser Punkt ist mit einer 5-GHz-Störung
  nicht aufgelöst, nur weiterhin benannt.

**Was aus den Rohdaten nicht mehr rekonstruierbar war** (Auftragsvorgabe:
markieren, nicht schätzen):

- Der **exakte geräteseitige Sendezeitpunkt** der einzelnen `dd|nc`-Prozesse
  (nur `timeout`-Startzeit relativ zum Zellstart aus `cell.sh`-Logik
  bekannt, kein Zeitstempel je Prozess). Für die Zellzuordnung folgenlos
  (die Zellgrenzen selbst sind über `meta.txt` exakt bekannt), aber die
  TCP-Slow-Start-Rampe innerhalb der ersten ~1–2 s jeder Zelle ist damit
  nicht auf die Sekunde einem einzelnen Strom zuzuordnen.
- Jede Aussage zur **Ursache** des ~25-s-Durchsatzeinbruchs in
  `level16_16stream` (9.3.1) — außerhalb des BT-Stacks, nicht untersucht,
  da folgenlos für `dropped`/`dropouts`.
- **Nichts** an den beiden zentralen Grössen (`dropped`/`dropouts`,
  Dreifachprüfung) ist unrekonstruierbar — beide sind für alle 7 Zellen
  vollständig belegt.
