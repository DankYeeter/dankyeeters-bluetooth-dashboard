# Stand — 2026-09-22

Kurzfassung fuer die Agenten. Zielbild in `GOAL.md`, Historie in `docs/archiv/HANDOVER.md`,
Entwurf in `ARCHITECTURE.md`, Oberflaeche in `UI_SPEC.md`, Befunde in
`qa/findings.md`. Details stehen dort, nicht hier.

**K-3 (Nutzer 03.09.): Erledigtes fliegt SOFORT raus, nicht bei der naechsten
Verdichtung.** Diese Datei ist das Nervensystem des Teams — was hier veraltet
steht, lesen Agenten als Tatsache und melden es weiter. Genau das ist am 03.09.
passiert: eine erledigte PII-Notiz liess den `archivist` einen Falschbefund
melden. Wer einen Punkt abschliesst, loescht ihn hier im selben Zug.

## Hier geht es weiter

**Laeuft (22.09.): T-040 — Bereinigung nach Ponytail-Audit** (`docs/tasks/T-040.md`).
a/b `developer` (Loeschungen, ohne Verhaltensaenderung), c `architect`
(vier Umbauten: Oboe→AudioTrack, MiniJson, AIDL-Protokoll, Kalibrierung +
Room 3→4). Danach: Vorlauf `security-reviewer` fuer den Helfer (AK-6), dann
Umbau-Auftraege, dann **eine** Pruefphase am Quellstand. Release: sammeln.
Suite vor T-040: **2488 / 0 Failures** (Director, 22.09. 22:00, `5f62b15`).
Messbefunde und Tuning-Grundlagen (frueher hier):
`docs/archiv/state-messbefunde-2026-09-03.md`.

**Mit Geraet:** 
T-036, die Trennmessung.
30 min bei gepinnten 990 **mit** 2,4-GHz-WLAN-Assoziation gegen 30 min **ohne**.
Sie leistet drei Dinge auf einmal:

1. Klaert den Widerspruch **T-029 gegen T-032** (siehe unten).
2. Testet **Massnahme 1** aus R-010 — die einzige mit eigener Messung.
3. Liefert die **Doppelaufnahme**, die AK-T009-24 am Geraetedump belegbar macht.

**Bedingungen an den Lauf:**
- Kein `dumpsys bluetooth_manager` zur Zwischenkontrolle — **das leert die
  BQR-Queue** (R-011). Das gilt ab jetzt fuer jeden Auftrag.
- Fuer die Doppelaufnahme: zwei Dumps im Abstand von ~4 min aus **einem
  Intervall, in dem `underflow` sich nicht bewegt, waehrend `dropouts`
  zaehlen**. Grund: `UI_SPEC.md:2361` formuliert AK-T009-24 als Snapshot ueber
  ein **Fenster** (`underflows` = 0, `dropouts` = 21 **in 97 s**), also
  Fensterwerte statt absoluter Zaehlerstaende. Genau so trat es in T-022 auf
  (`underflow` 623 → 623 bei steigenden `dropouts`).
- Zustandsbuch **je Abschnitt** pruefen, nicht nur am Anfang.

**Danach T-037, eigener kurzer Lauf:** Callback registrieren, echtes
BQR-Ereignis abwarten, dann **einmalig** pruefen, ob es im Dump noch steht.
Beantwortet die letzte Frage zu AK-7. Braucht Stoerung, deshalb nach der
2,4-GHz-Zelle — und **getrennt**, damit der `dumpsys`-Aufruf T-036 nicht
verfaelscht.

**Ohne Geraet offen: nichts mehr.** QA-014 bis QA-018 sind behoben und
gegengeprueft (03.09.) — Details in `qa/findings.md`.

## AK-17 / T-039 — Beobachtungslauf: Spec liegt, zwei Fragen offen

**AK-17 steht in `GOAL.md`** (Nutzer 03.09.): Bei ABR zeigt die Oberflaeche
waehrend eines Beobachtungslaufs **Minimum**, **Zeitanteil ueber einer
waehlbaren Schwelle** und **Verweildauer je Stufe** — damit der Nutzer belegen
kann, ob eine feste Stufe traegt, statt es zu vermuten. **Nur bei offener
Oberflaeche, keine Persistenz** — AK-4 bleibt unangetastet.

**Spec geliefert:** `UI_SPEC.md` ab Z. 2454, **AK-T039-1..16**. Ausserdem
AK-T002-11 erweitert (`low` neben `peak`) und AK-T002-13 praezisiert.
Neue Messanforderungen **M-15** und **M-16**.

Tragende Festlegungen der Spec:
- **Ausdruecklich gestarteter Lauf, kein gleitendes Fenster.** `{T}` ist die
  **Summe der abgedeckten Intervalle**, nie `jetzt - Start` — so kann keine
  unbeobachtete Sekunde mitgerechnet werden.
- **Eigener Abschnitt** unter `TraceSection`, getrennt von der 60-s-Caption.
  Ruhezustand: eine Zeile plus Chip.
- **Rechnung ueber Intervalle statt ueber Lesungen** — immun gegen den
  Kadenzwechsel 1/2/5 s, erzeugt keine Rate (R-F auch dem Geist nach gehalten).
- **Peak bleibt in der Caption, bekommt `low` daneben**; im Lauf fuehrt das
  Minimum, ein Hoechstwert erscheint dort gar nicht.
- **Zahlen erst ab 120 s und 30 Lesungen**, davor „Collecting“. Pause,
  ausgefallene Lesung, Hintergrund = Luecke. Trennung, Stufen-/Codecwechsel,
  Luecke > 2 min = **Lauf endet mit genanntem Grund**. Screen verlassen
  verwirft ihn.

**Vom Nutzer entschieden (03.09.):**
- Lauf wird **von Hand gestartet**, nicht automatisch beim Oeffnen.
- **Einmaliger Hinweis** beim Start, dass Verlassen des Bildschirms den Lauf
  verwirft.

**Vom Nutzer entschieden (22.09.), beide wie von der Spec empfohlen:**
1. Zeitbezug steht **bei jeder der drei Zahlen** („Niedrigster Wert: 606 kbps
   · ueber 24 min beobachtet“).
2. Schwelle waehlbar **nur aus den pinnbaren Stufen** (330/660/990 bzw.
   303/606/909, Default die mittlere).

**Vorbehalt der Spec, nicht ueberdehnen:** `RUN_MIN_OBSERVED_MS` = 120 s und
`RUN_GAP_MAX_MS` = 120 s sind **begruendete Kanten, keine Messung**. M-15
schliesst die erste aus einem bereits moeglichen 30-min-ABR-Lauf; M-16
(500 ms gegen 5 s) sagt, wie stark „Lowest reading“ untertreibt. **Bis M-16
beantwortet ist, darf der Wortlaut nicht zu „lowest rate“ werden.**

**Bereit zur Umsetzung:** `developer` gegen AK-T039-1..16, danach
`qa-engineer`. Kein Geraet noetig. Startet nach T-040.

## Stand des Codes

`gradlew test`: **2488 Tests, 0 Failures** (03.09. nach T-038, von `developer`
und `qa-engineer` unabhaengig gezaehlt). Verlauf des Tages: 2470 vor T-034 →
2482 → 2488. Die frueher notierten „2390“ passen zu dieser Zaehlweise nicht.

**T-038 abgeschlossen** (`374be69`, `5218455`, `de2454b`, `5f605b1`): die fuenf
Befunde QA-014..QA-018 sind behoben und gegengeprueft. **Zwei Lehren daraus
stehen in `qa/findings.md`** und gelten weiter: (1) Ein Kommentar- oder
Textbefund ist erst erledigt, wenn **projektweit gesucht** wurde — nicht, wenn
die genannten Stellen erledigt sind. (2) Ein **positiver** Testfall kann eine
Fensterbreite strukturell nicht binden; dafuer braucht es einen negativen.

**T-034 abgenommen** (Commit `0dbea4e`): Golden-Test bindet den echten
990er-Verlustdump an behauptende Tests. QA-Retest hat alle vier
Rot-vorher-Mutationen reproduziert und **14 eigene** gefahren; die Datei faengt
zusaetzlich Zaehlertausch, **beide Abschnittsgrenzen**, die zweite `assertNull`
und den Vorzug des verbundenen Geraets. Berichte: `docs/tasks/T-034-bericht.md`,
`T-034-retest-bericht.md`.
**Einschraenkung, die zur Abnahme gehoert:** Die Datei prueft **AK-T009-24
nicht** — der wieder eingebaute QA-001-Fehler laesst alle sechs Tests gruen. Die
Zielaussage gilt fuer den Zaehler- und Bitratenpfad, **nicht** fuer den
Verlust-Verdikt-Pfad.

## Rahmen und Geraet

- Pixel 11 Pro `67011FDKX004XG`, Android 17, per Kabel. Sink: Noble FoKus
  Prestige Encore, effektive MTU **883** gegen verhandelte 1005, `EDR: true`,
  `Support 3Mbps: true`. **Die MTU ist Eigenschaft der Paarung, nicht des
  Telefons** — nicht auf andere Geraete uebertragen (AK-15).
- Toolchain: JDK 21 `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot` (Rechner DESKTOP-P5UI1KI, 22.09.; der alte Pfad `~/tools/jdk/...` existiert hier nicht), NDK gepinnt
  `27.3.13750724`, build-tools 35.0.0, AGP 8.9.3. Branch heisst **`master`**.
- **Risiko R-2:** zwei adb-Binaries (`C:\RSL\2.1HF5\adb\adb.exe` und
  `platform-tools\adb.exe`) killen sich den Server. Je Messung nur **eines**.
- **`sdkmanager` liefert bei Erfolg Exit-Code 127** — Erfolg am Dateisystem
  pruefen, nie am Exit-Code.
- Kein Emulator (kein Hypervisor). Alles ausser Geraetetests laeuft ueber
  Unit-Tests und Robolectric.
- **Verfahrensregel:** Read-back deckt das **vollstaendige** Zustandsbuch ab,
  je Abschnitt. Anlass: unbemerktes WLAN entwertete die vierte T-008-Zelle,
  und in T-027 ging die Assoziation mitten im Lauf verloren.
- **PII-Konvention:** Platzhalter `SSID_A`, `AP_BSSID`, `IP_1` (Host), `IP_2`
  (Telefon); MACs als `xx:xx:xx:xx:ab:cd`. Seriennummer und Produktnamen von
  Kopfhoerern bleiben im Klartext.

## Offene Sicherheitsbefunde

**SR-001 und SR-009** — weltles- und -schreibbare Dumps bzw. Helper-Log in
`/data/local/tmp`, **ueberleben die Deinstallation**. Behebung ist T-006/U-0..U-6,
braucht Geraet. `AudioEffectSessionReceiver` exportiert — eigenes Review offen.

## Laufende und offene Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-036 | performance-tuner | Trennmessung 2,4 GHz bei 990 + Doppelaufnahme | **naechster Schritt**, braucht Geraet |
| T-037 | performance-tuner | Callback-Probe, letzte AK-7-Frage | nach T-036 |
| T-040 | developer ×2, architect | Bereinigung nach Ponytail-Audit | **laeuft** 22.09. |
| T-039 | developer | AK-17 Beobachtungslauf | bereit, nach T-040 |
| T-006 | architect nach developer | Transport SR-001/SR-009 | Entwurf abgenommen, Umsetzung offen |
| T-001 | performance-tuner | Vergleichslauf gegen Block 1 | offen, **vor** dem Transport-Messlauf |
| T-008 | performance-tuner | E-1/E-3 (Nearby-Scans, Spatializer aus) | offen, **kein Shell-Hebel**, nur von Hand |
| SR-012 | performance-tuner | `umask 077` in `docs/perf/tools/*.sh` | zurueckgestellt bis Ende der Messreihe |
| QA-012 / QA-013 | developer | vakuum-gruene Grep-Regel, schwacher Test | offen; QA-012 faellt in V-1 |
| T-005 | architect | Scan-Entwurf S-1..S-7 | **ruht** (Nutzer 02.09.) |

## Zwei offene Fixture-Luecken

1. Kein Dump eines Builds **ohne** die beiden ABR-Zeilen.
2. **Nur ein Rung-Wert aufgenommen** (Index 4 / 396 kbps). Die Paare 660/1 und
   492/3 stehen nur in der Messdoku, nicht in einer Fixture.

Die dritte (990er-Verlustfall) ist geschlossen und seit T-034 an Tests gebunden.

## Zurueckgestellt

- Kein `CHANGELOG.md`, kein gebautes Artefakt, keine Installationsanleitung —
  fuer den `power-user` gibt es deshalb noch keinen Ausgangspunkt.
- QA-005: zwei ABR-Felder ohne Konsumenten. **Kopplung fuer den UI-Zyklus:**
  `A2dpTxProbe.sampleBetween` kopiert nur `bitrateKbps` und `qualityModeLabel` —
  der Nahaufnahme-Kanal bekommt die neuen Felder nicht, und G-4/AK-T009-41
  braucht den Zaehler genau dort.
- Widerspruch R-001 gegen Messung: 492 kbps ist gemessen, gilt dort aber nicht
  als Nominalstufe. Leiter fuer 96 kHz/32 bit unverstanden.
- **Der 660er-Verlustfall aus T-027 ist NICHT als Hebel weiterzuverwenden** —
  in T-028 ueber acht gueltige Abschnitte nicht reproduziert. Wer daran
  anknuepfen will, muesste zuerst die Reproduzierbarkeit herstellen.
- Vierte T-008-Zelle bleibt **INCONCLUSIVE** (WLAN-Konfundierer).
- Git-Historie: eine LAN-IP steht in aelteren Commits von
  `T-029-990-korrelation.md`. **Empfehlung des Directors: nichts tun**, solange
  das Repo privat ist. Vor einer Veroeffentlichung neu bewerten.
- Zeilenenden: drei Dateien liegen mit LF im Arbeitsbaum, der Rest CRLF.
