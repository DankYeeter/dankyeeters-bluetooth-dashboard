# Stand — 2026-09-02

Kurzfassung fuer die Agenten. Zielbild in `GOAL.md`, Historie in
`HANDOVER.md`. Details stehen in den genannten Dateien, nicht hier.

## Die tragenden Befunde (belegt, nicht mehr zu diskutieren)

- **„990 gepinnt → Warteschlangenueberlauf → hoerbare Aussetzer" ist belegt**
  (T-008, `docs/perf/T-008-experimente.md`). A/B/A: ABR 0 Drops / 0 Dropouts,
  990 gepinnt 525/21 (13/min, durchgehend hoerbar), zurueck auf ABR wieder 0.
  Der Regler bestaetigt es autonom: ABR probiert 990 selbst an und verlaesst
  die Stufe nach 1,4 s zwei Stufen tiefer.
- **Underflow ist als Leitgroesse untauglich.** `Counts (underflow)` blieb in
  ALLEN Armen 0 — auch im hoerbar kaputten. Wer Aussetzer anzeigen will, muss
  `dropped`/`dropouts` lesen.
- **Die zwei Hoerbarkeitspunkte sind konfundiert.** 0 Dropouts gab es nur bei
  492/660, 13/min nur bei 990. Belegt ist „990 gepinnt klingt kaputt, adaptiv
  nicht" — **nicht** „13/min sind hoerbar, unabhaengig von der Stufe".
- **Die Ruherate ist null, jetzt belastbar (M-5, T-011).** 38,93 min lueckenlos, 1795 Samples: `dropped` und `dropouts` bleiben 0 — geprueft an **jedem** der 1794 Sample-Uebergaenge, nicht nur an den Eckwerten. Kombinierte Dreierregel-Obergrenze (514 s + 2335,82 s) = **0,063/min**; `LOSS_NOTICE_RATE_PER_MIN` = 1/min liegt ~16-fach darueber statt ~3-fach. **Der Wert bleibt, eine Sustain-Bedingung ist nicht noetig.**
- **`underflow` ist im langen Lauf NICHT null:** 2 -> 25 (0,591/min), anders als in allen kurzen Referenzen. Das bestaetigt R-D von der anderen Seite — underflow taugt weder fuer Ueberlast noch fuer Ruhe als Verdikt. Datenpunkt fuer AK-T002-16.
- **ABR probiert 990 kbps von sich aus, n=31:** 30-mal fuer genau ein Sample, nie mit Verlust. Der T-008-Einzelbefund ist damit vielfach bestaetigt.
- **Ursache des Queue-Ueberlaufs bleibt offen.** Ausgeschlossen: WLAN,
  Energiespar-/Doze-Mechanismen, CPU-Knappheit. Gestuetzt, aber **nicht
  belegt**: „990 ist fuer diese Strecke schlicht zu schnell" — in allen vier
  Armen dieselbe Groessenordnung (284–337 Drops/min) quer ueber zwei
  Scanner-Konfigurationen und beide WLAN-Zustaende. Die stoerungsfreie
  Vergleichsbedingung fehlt.

## Was daraus folgt

- **T-009 hat `UI_SPEC.md` auf die richtige Leitgroesse gestellt:** zwei
  Regime, zwei Groessen, ausdruecklich keine gemeinsame Ampel. Normalbetrieb
  → ABR-Stufe; Ueberlast → `dropped`/`dropouts` je Minute. 14 neue
  Akzeptanzkriterien AK-T009-24..37; AK-T009-24 ist der Regressionstest gegen
  den Befund selbst.
- **R-E ist dauerhaft, nicht vorlaeufig:** keine abstufenden Woerter und kein
  mehrstufiges Bildzeichen fuer Raten zwischen 0 und 12/min. Grund: **M-11 ist
  derzeit nicht messbar** — Zwischenpunkte muessten bei *gleicher* Stufe
  erhoben werden, und der einzige bekannte Hebel (Pinnen auf 990) aendert die
  Stufe per Konstruktion. Das Wort „audible" kommt in der Oberflaeche nicht
  mehr vor (AK-T009-43, Grep-Regel).
- **D-11, Voraussetzung fuer alles Weitere:** `A2dpLinkDumpParser.kt:401-404`
  liest die `LDAC adaptive bit rate`-Zeilen (Index, `adjustments`) **gar
  nicht** — genau die Groessen, auf denen T-007/T-008 ausgewertet haben. Ohne
  sie unterzaehlt jede Wechselrate systematisch. Offen, wartet auf Toolchain.
- **Bitratenverlauf:** kein neuer Graph noetig, die Linie existiert
  (`TracePoint.bitrateKbps`) — sie war falsch gezeichnet. Acht Regeln G-1..G-8,
  tragend: Treppe statt Kurve, keine Glaettung (bei mehr Lesungen als
  Pixelspalten Min/Max je Spalte, nie Mittelwert), Luecken werden nicht als
  Wissen gezeichnet.

## T-006 Transport — Entwurf abgenommen, Umsetzung offen

`ARCHITECTURE.md` AD-010..AD-014, `security/findings.md` SR-013..SR-022,
Auflagen A6–A16, Schrittfolge U-0..U-6.

- **Deskriptor statt Pfad**, ohne Groessenschwelle: jede Uebergabe ueber einen
  Pfad ist zwingend weltlesbar (vier Plattformmechanismen geprueft).
- Bauform-Reihenfolge **3' → 4 → 1 → 2 → 3**.
- **SR-016:** Der Helper ueberlebt die Deinstallation und pollt unbegrenzt
  weiter → Abbruchbedingung auf Paket-Existenz, dann selbst aufraeumen.
- Sichtbare Aktion **„Stop the helper"** (Entscheidung App Designer 01.09.),
  drei Stufen, keine Summenzeile; danach steht der Monitor auf `CANNOT_TELL`,
  nie auf `CLEAN`. **A16 haengt nicht am Dateimodus**, sondern daran, dass
  keine Datei dieser Form mehr existiert.

## Wichtige Klarstellung 02.09.: „gesetzt" heisst NICHT „gebaut"

Die Parameter aus T-009 (`LOSS_NOTICE_RATE_PER_MIN`, `LOSS_ALERT_RATE_PER_MIN`,
`LOSS_WINDOW_MS`, `LOSS_CLEAR_HOLD_MS`, `SETTLE_AFTER_TRANSITION_MS`,
`RATE_MIN_EVENTS_IN_WINDOW`, `LOSS_EVENT_COOLDOWN_MS`) sind in `UI_SPEC.md`
**festgelegt** — im Code existiert **keine** davon. Belegt in T-021 per Grep.
Die einzige Verlust-Konstante im Code ist `LADDER_QUEUE_PRESSURE_FRACTION`.

Dasselbe gilt fuer den `SETTLING`-Zustand der Verlustanzeige und fuer die
Zustandsmaschine CLEAN/OCCASIONAL/DISTURBED/CANNOT_TELL insgesamt. Die
heutige `LossRow` rechnet eine rohe Poll-zu-Poll-Differenz, sonst nichts.

**Folge fuer die Berichtslage:** Wo frueher stand „Wert gesetzt" oder „M-5
bestaetigt den Wert", ist gemeint: die **Vorgabe** steht und ist durch eine
Messung gedeckt. Gebaut ist sie nicht. Rund **25 Akzeptanzkriterien** haben
deshalb keinen Test — nicht aus Nachlaessigkeit, sondern weil ihr Gegenstand
fehlt. Die vollstaendige Liste steht im T-021-Bericht.

## Stand 02.09. Nachmittag — was heute gebaut wurde

**Die Kette ist offen und laeuft.** 2332 -> **2390 Tests, 0 Failures**.
Neun Commits sind auf `origin/master` gepusht, der Rest liegt lokal.

- **D-11 behoben:** Der Parser liest die beiden `LDAC adaptive bit rate`-Zeilen.
- **Zwei Fehlalarme beseitigt**, beide vom selben Typ „Anzeige schlaegt im
  gesunden Alltag Alarm": Underflow im Verlust-Verdikt (~23 rote Meldungen je
  39 min fehlerfreier Musik) und „Bluetooth is falling behind" (feuerte bei
  0–1,4 % der Lesungen). Underflow bleibt **sichtbar**, verliert nur das
  Verdikt (AK-2).
- **Die strukturelle Ursache ist weg:** Die Verlustdefinition stand dreimal
  von Hand im Code. Jetzt einmal, als `TxLossChannel` + `lossByChannel`. Ein
  neuer Kanal **kompiliert nicht**, bevor die Oberflaeche ein Wort dafuer hat
  — vom `qa-engineer` per Mutation belegt (5 Fehlschlaege ueber 3 Pfade).
- **AK-T002-12 erfuellt:** „Audio lost:", „No loss this window.",
  „no loss in this window" sind ersetzt bzw. gestrichen.
- **12 Akzeptanzkriterien maschinell verankert** (vorher: eines).
- **Vier neue Fixtures**, darunter die **erste mit echten Verlusten**.

## Laufende Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-010 | release-manager | Toolchain einrichten | **erledigt** 02.09. |
| T-011 | performance-tuner | M-5: Ruherate >= 30 min ABR | **erledigt** 02.09., `docs/perf/T-011-messung.md` |
| T-012 | developer | `ndkVersion` gepinnt, B-2-Kommentar | **erledigt und verifiziert** durch nativen Bau |
| T-001 | performance-tuner | Vergleichslauf gegen Block 1 | offen; **vor** dem Transport-Messlauf (U-6/S-6), sonst konfundiert |
| T-005 | architect | Scan-Entwurf | geliefert (`docs/scan/T-005-ENTWURF.md`); Umsetzungsschnitt S-1..S-7 |
| T-006 | architect → developer | Transport SR-001/SR-009 | Entwurf abgenommen; Umsetzung braucht Geraet + Toolchain |
| T-008 | performance-tuner | Eingriffsexperimente | E-2 fertig; E-1/E-3 offen (nur von Hand, kein Shell-Hebel) |
| T-021 | developer | AK-Verankerung (QA-008) | **geliefert**; Retest offen |
| T-022 | performance-tuner | Fixtures: Verlustfall, ABR-Stufen, Pause | **geliefert**; Read-back laeuft |
| SR-012 | performance-tuner | `umask 077` in `docs/perf/tools/*.sh` + Reste loeschen | zurueckgestellt bis Ende der Messreihe |

## Rahmen (korrigiert 02.09.)

**Die alte Angabe „Zweitrechner: kein JDK, kein SDK, kein Gradle" war falsch.**
Vorhanden, nur nicht verdrahtet: Temurin **17.0.20.1+1** unter
`~\tools\jdk`, ein halbes Android-SDK unter `~\tools\android-sdk` (2,4 GB)
mit `cmdline-tools`, `platform-tools` 37.0.1 und **NDK r27d**, Lizenz bereits
akzeptiert. Es fehlte Konfiguration, nicht Software. Belege und Plan in
`docs/release/ROLLOUT.md`.

- Entscheidung Nutzer 02.09.: **Temurin 21 wird geladen**, Rahmen aus
  `GOAL.md` bleibt. Der Null-Download-Weg ueber JDK 17 ist verworfen.
- Entscheidung Director 02.09.: **NDK pinnen statt laden** (`27.3.13750724`).
- **T-010 ausgefuehrt 02.09. — die Kette ist offen.** `gradlew test` laeuft: **2332 Tests, 0 Failures** in 3:27, Zweitlauf mit `--rerun-tasks` bestaetigt. JDK 21 unter `~/tools/jdk/jdk-21.0.12.1+1`, `JAVA_HOME`/`ANDROID_HOME`/PATH in HKCU. Der NDK-Pin ist durch einen **echten nativen Bau** belegt (`CMakeCache.txt`: `ndk/27.3.13750724`, drei ABIs, `LOAD align = 0x4000` — die 16-KB-Zusage haelt). **build-tools 35.0.0** genuegt AGP 8.9.3, nicht 36.0.0 wie geplant.
- **Rueckweg der Systemaenderung:** `~/tools/toolchain-backup-2026-09-02/` (Registry-Export HKCU-Environment + roher User-Path).
- **`sdkmanager` liefert bei Erfolg Exit-Code 127.** Jede Automatisierung muss den Erfolg am Dateisystem pruefen, nie am Exit-Code.
- **Der T-010-Lauf war nicht isoliert:** Gradle, AGP und `platforms/android-36` kamen aus dem geteilten `GRADLE_USER_HOME` eines Fremdprojekts (`pension-manager`). Fuer eine wirklich frische Maschine kommen ~193 MiB hinzu. Die Abnahme ist davon unberuehrt, die Aussage „so richtet man eine leere Maschine ein" nicht vollstaendig belegt.
- **Risiko R-2:** zwei adb-Binaries (`C:\RSL\2.1HF5\adb\adb.exe` und
  `platform-tools\adb.exe`) killen sich gegenseitig den Server. Waehrend einer
  Messung darf nur **eines** benutzt werden.
- Emulator existiert nicht (kein Hypervisor). Geraet: Pixel 11 Pro
  `67011FDKX004XG`, per Kabel.
- **Methodischer Vorbehalt:** USB-3 strahlt ins 2,4-GHz-Band; alle Messungen
  liefen am Kabel. Kontrollmessung ueber drahtloses adb steht aus.
- **Verfahrensregel (verschaerft nach T-008):** Read-back deckt das
  **vollstaendige** Zustandsbuch ab, nicht nur die geaenderte Variable. Anlass:
  unbemerkt eingeschaltetes WLAN machte die vierte T-008-Zelle wertlos.

## Offen — braucht den Nutzer

- **Die sechs T-005-Entscheidungen sind nicht im Repo.** `docs/state.md` fuehrte
  sie als offen, aber keine davon ist irgendwo aufgeschrieben; der
  `architect`-Bericht existierte nur im Chat. `docs/scan/T-005-ENTWURF.md`
  verweist bei S-6 auf „Antwort auf Frage 1", ohne dass die Frage dasteht.
  **Nicht rekonstruieren — neu herleiten lassen oder vom Nutzer holen.**
- **Codec-Pin ist nicht mehr gesetzt** — Read-back im T-011-Lauf zeigt `Priority: 5001` (System-Auswahl), nicht den Marker `1000000` aus T-008. Die Angabe „LDAC gepinnt" ist damit ueberholt. Fuer M-5 folgenlos (LDAC/ABR lief so oder so). Woher der Ruecksprung kam, ist nicht geklaert.
- E-1 (Nearby-Scans aus) und E-3 (Spatializer aus) haben **keinen Shell-Hebel**
  — am Geraet vollstaendig geprueft. Nur von Hand.
- Vierte T-008-Zelle bleibt **INCONCLUSIVE** (WLAN-Konfundierer). Wiederholung
  waere „990 plus Scans aus" unter kontrolliertem WLAN.

## T-013 geliefert (D-11), QA laeuft

Der Parser liest jetzt `LDAC adaptive bit rate encode quality mode index` und
`adjustments` — die Groessen, auf denen T-007/T-008/T-011 ausgewertet haben.
Zwei neue Felder in `LdacStackState`, Commit `5e03694` auf `master`, **noch
nicht gepusht**. 2332 -> **2344 Tests, 0 Failures**. Keine sichtbare
Aenderung an der Oberflaeche.

- **Der Index ist KEINE Bitrate.** Gemessen: 660<->1, 492<->3, 396<->4 — die
  Leiter ist nicht monoton. Eine Abbildung Index->Rate waere geraten und wurde
  bewusst nicht gebaut. Nicht als Rate beschriften.
- **Entscheidung Director 02.09.: AK-T009-24 bleibt stehen, unveraendert.**
  Die Verdikt-Ebene (`DISTURBED`, `CANNOT_TELL`, `LOSS_*_RATE_PER_MIN`)
  existiert im Code nicht; das Kriterium ist heute eine Spezifikation fuer
  Code, den es noch nicht gibt. **Das ist der Zweck einer Spec.** Es auf das
  umzuformulieren, was zufaellig schon existiert, wuerde die Latte senken.
  Abgedeckt ist bisher die **Datenhaelfte** (underflow=0 unterdrueckt keinen
  anderen Kanal); die Verdikthaelfte gehoert in den UI-Zyklus.

### Drei Fixture-Luecken — Auftrag fuer die naechste Geraetesitzung

1. **Kein aufgenommener Dump aus dem 990er-Arm.** Im Repo liegt keine Fixture
   mit `dropped`/`dropouts` != 0. AK-T009-24 laeuft ueber **gesetzte**
   Zaehlerstaende — ein Test ueber die Arithmetik, **kein** Beleg, dass der
   Parser einen echten 990er-Dump liest. **Wer je wieder auf 990 pinnt: Dump
   mitnehmen.** Daraus wird eine Golden-Fixture.
2. Kein Dump eines Builds **ohne** die beiden ABR-Zeilen (heute aus der echten
   Aufnahme entfernt).
3. **Nur ein Rung-Wert aufgenommen** (Index 4 / 396 kbps). 660<->1 und 492<->3
   stehen nur in der Messdoku, nicht in einer Fixture. Ein Dump je Stufe waere
   der Beleg, dass der Index wirklich nicht monoton laeuft.

### Offene Kopplung fuer den UI-Zyklus

`A2dpTxProbe.sampleBetween` kopiert nur `bitrateKbps` und `qualityModeLabel`
in den `TxProbeSample`. Der 60-s-Pfad bekommt die neuen Felder geschenkt, der
**Nahaufnahme-Kanal nicht** — und G-4/AK-T009-41 braucht den Zaehler genau
dort. Feldergaenzung an einem Typ, den die UI-Trace-Modelle lesen.

## Zurueckgestellt

- Kein `CHANGELOG.md`, kein gebautes Artefakt, keine Installationsanleitung —
  fuer den `power-user` gibt es deshalb noch keinen Ausgangspunkt. Der Weg zur
  Auslieferung ist ein eigener Abschnitt nach dem gruenen Testlauf.
- Aufnahme gegen R-001..R-004 abgleichen (der tuner hat sie nie gelesen).
- Widerspruch R-001 vs. Messung: 492 kbps ist gemessen, gilt dort aber nicht
  als Nominalstufe. Leiter fuer 96 kHz/32 bit unverstanden.
- `AudioEffectSessionReceiver` exportiert — eigenes Review ausstehend.
