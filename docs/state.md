# Stand — 2026-09-23

Kurzfassung fuer die Agenten. Zielbild in `GOAL.md`, Historie in `docs/archiv/HANDOVER.md`,
Entwurf in `ARCHITECTURE.md`, Oberflaeche in `UI_SPEC.md`, Befunde in
`qa/findings.md`. Details stehen dort, nicht hier.

**K-3 (Nutzer 03.09.): Erledigtes fliegt SOFORT raus, nicht bei der naechsten
Verdichtung.** Diese Datei ist das Nervensystem des Teams — was hier veraltet
steht, lesen Agenten als Tatsache und melden es weiter. Genau das ist am 03.09.
passiert: eine erledigte PII-Notiz liess den `archivist` einen Falschbefund
melden. Wer einen Punkt abschliesst, loescht ihn hier im selben Zug.

## Hier geht es weiter

**Autonomer Lauf 22.09. 23:51 – 23.09. 04:30 beendet**, Branch
`auto/2026-09-23`, PR offen, **nicht gemerged** (Merge beim Nutzer).
Gebaut und abgenommen: T-040..T-042 (Bereinigung; Zyklus gesamt inkl. neuem
Feature `git diff --shortstat 5f62b15..60e7a44` ohne Schemas: +2534/−3184 in
Code/Build; 7 Abhaengigkeits-Deklarationen entfernt, 1 Testabhaengigkeit neu), T-041 Umbauten AD-026..028, T-039 AK-17
Beobachtungslauf, T-044 Fixe + D-001 (DataStore-Sperre). Pruefphase T-043:
Security PASS, Design ship-ready; QA-Retest T-044e **PASS**. Suite **2498/0**
(Director 23.09. 04:14, `4507182`, `clean test --no-build-cache`;
QA 04:23/04:25 je 2498/0). Retrospektive: `docs/lessons.md` BD-L-004..006,
drei Vorschlaege fuers Agenten-Repo beim Nutzer.
**T-045 Bestandsaufnahme** (`docs/berichte/T-045-architect.md`): von AK-8..16
nur AK-10 gebaut (leer), AK-8/9/15 teilweise, **AK-11..14 und AK-16 offen** —
Saeule 3 fehlt. Schnitt P-1..P-9.

**Zyklus 4 (Session 23.09. 16:35, Branch `auto/2026-09-23-saeule3`, auf dem
offenen PR-Branch aufgesetzt): Saeule 3 bauen, alles ohne Geraet, sammeln statt
Release.** **Bau abgeschlossen (Gate):** T-046 (Device test raus, Pause >
`RUN_GAP_MAX` beendet den Lauf, P-1, Whitelist-KDoc) und T-047 Saeule 3
(AD-030..038; S3-1..S3-6 plus T-047j) auf `8d98a80`. Suite **2607/0**
(Director 19:14, `6a92e20` = Code-Stand von `8d98a80`, `clean test
--no-build-cache`). Code seit `deda17e`: 58 Dateien, +4380/−1144 (`*.kt`,
`*.xml`). Security-Vorlauf CONCERNS, Auflagen M1–M18 eingebaut
(`docs/berichte/T-047b-security-reviewer.md`). **Offen vor Pruefphase:**
Wortlaute, die S3-5/S3-6 selbst gesetzt haben (`docs/berichte/T-047i-developer.md`,
T-047h-Antwort: Dialog „Back to before", Banner bei unlesbarem Ledger,
Pin-Satz, „Start Arm B", „device discovery") → ui-ux-designer Review;
Pruefphase mit QA am Quellstand; Geraeteabnahme zusammen mit T-036.
Nicht gebaut: Schutz, falls ein Arm nach Stufen-/Codecwechsel ungepinnt
neu startet (T-047i an Director) — Beschlossen, nicht beauftragt.
**Geraet:** T-048 (QA-Pruefliste) pausiert seit 18:11, Telefon gesperrt,
wartet auf Entsperren; T-049a..c (Inventur, T-036, T-037) danach.

**Beim Nutzer — offen (am Laufende fragen):**
0. UI_SPEC.md:3512 — Kategoriename „Workarounds" und zweiter Rueckweg-Knopf
   im Vergleichsergebnis (gebaut nach Empfehlung der Spec).
1. Sample-Rate-Wechsel im Beobachtungslauf beenden?
2. Oboe → AudioTrack: `GOAL.md:169` nach Geraetebeweis aendern.
3. Drei Retrospektive-Vorschlaege fuers Agenten-Repo (`docs/lessons.md`).

**Geraetesession 23.09. abends:** W-7..W-9 (AD-025) mit Geraetevergleich;
T-036/T-037 — **Monitor-Ansicht dabei geschlossen halten**, sie pollt
`dumpsys bluetooth_manager` alle 0,5–5 s (`DumpsysLinkSource.kt:19`) und leert
damit laut R-011 die BQR-Queue (T-045); Pruefliste aus
`docs/berichte/T-043a-qa-engineer.md` („Nur mit Geraet“, 10 Punkte);
Einstellungs-Inventur P-3 (T-045), dabei F-011 klaeren.

**Beschlossen, nicht beauftragt:** F-004, F-005, F-009 (QA-C), SR-023,
SR-024, F-011 (Nutzertext „only a rooted phone can change“ in
`DeviceProfilesScreen.kt:316` und `BluetoothSystemControls.kt:105` — laut
T-045 falsch und Root-Hinweis gegen AK-10; faellt mit T-047 P-4).
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


## AK-17 / T-039 — Beobachtungslauf: gebaut (T-039b), Fixe in T-044

Spec `UI_SPEC.md` T-039-Abschnitt (AK-T039-1..17), Vorgaben wörtlich in
`docs/tasks/T-039.md`. Nutzerentscheide 03.09./22.09. dort. Vorbehalt bleibt:
`RUN_MIN_OBSERVED_MS`/`RUN_GAP_MAX_MS` = 120 s sind begruendete Kanten, keine
Messung (M-15, M-16); bis M-16 kein Wortlaut „lowest rate“.

## Stand des Codes

`gradlew test`: **2607 Tests, 0 Failures** (Director, 23.09. 19:14, `6a92e20`,
`clean test --no-build-cache`, einmal; F-006 Flaky offen).

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
| T-006 | architect nach developer | Transport SR-001/SR-009 | Entwurf abgenommen, Umsetzung offen |
| T-001 | performance-tuner | Vergleichslauf gegen Block 1 | offen, **vor** dem Transport-Messlauf |
| T-008 | performance-tuner | E-1/E-3 (Nearby-Scans, Spatializer aus) | offen, **kein Shell-Hebel**, nur von Hand |
| SR-012 | performance-tuner | `umask 077` in `docs/perf/tools/*.sh` | zurueckgestellt bis Ende der Messreihe |
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
