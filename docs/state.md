# Stand — 2026-09-03, Part 5

Kurzfassung fuer die Agenten. Zielbild in `GOAL.md`, Historie in `HANDOVER.md`,
Entwurf in `ARCHITECTURE.md`, Oberflaeche in `UI_SPEC.md`, Befunde in
`qa/findings.md`. Details stehen dort, nicht hier. **Diese Datei bleibt kurz;
Erledigtes fliegt raus.**

## Hier geht es weiter

**Naechster Schritt: T-036, die Trennmessung. Braucht das Geraet.**
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

**Ohne Geraet moeglich:** QA-014 bis QA-016 (`qa/findings.md`), klein, keiner
blockiert. **QA-014 vor dem naechsten Anfassen von `MonitorDatabase`.**

## Zielbild — neu gefasst und in Kraft (03.09.)

`GOAL.md` ist vom Nutzer abgenommen. Drei Saeulen: **Anzeigen → Stellen →
Optimieren**. AK-1..AK-6 unveraendert, **AK-8..AK-16 neu in Kraft**.

- **AK-7 zurueckgestellt, Nummer reserviert** — erst nach Klaerung der letzten
  BQR-Frage. **Bis dahin wird an diesem Kanal nichts gebaut.**
- **AK-14** lautet „ohne kuenstliche Stoerung“, weil kein Stresshebel belegt
  ist. Die Testsuite ist damit nicht blockiert.
- **Umkehr gegenueber 02.09.:** Die App **stellt Einstellungen selbst**, wo es
  ohne Root geht. Der Helfer darf wachsen. Gegengewicht: AK-10 (jedes Kommando
  einzeln vom `security-reviewer`) und AK-11 (Rueckweg garantiert).

## Die tragenden Befunde — belegt, nicht mehr zu diskutieren

**Die Grenze bei 990 ist eine Luftzeit-Bilanz, nicht „Signalqualitaet“.**
990 belegt auf einem 2-DH5-Link rund 70 % der Kapazitaet, 660 rund 47-59 %; die
Sendewarteschlange fasst 28 Pakete, bei 990 nur ~150 ms Audio. Funkfehler werden
per ARQ in Wiederholungen und damit ebenfalls in Luftzeit umgesetzt — „Latenz
gegen Signalqualitaet“ ist **keine** Trennung. Die Prozentrechnung ist eigene
Arithmetik auf belegten Konstanten, **kein Messwert**.

**Die Paarung liegt in der 2-DH5-Klasse** (T-032, BQR direkt: 24/25 Ereignisse
`2DH5`, eines `2DH3`). Nicht 2-DH3 — Massnahmen aus R-010 koennen also greifen.
**Wiederholrate 23,5-33,2 % bei RSSI -43 bis -58 dBm**: starkes Signal, ein
Viertel bis ein Drittel wiederholt. Der bisher staerkste Beleg der Luftzeit-These.

**Der Ueberlauf entsteht auf der Abflussseite** (R-010, Quelltext):
`tx_audio_queue` wird nur geleert, solange `l2c_bufs` unter der Schwelle bleibt.

**Zaehler-Semantik** (R-005, Quelltext): `dropped` = verworfene
Warteschlangeneintraege **variabler** Groesse, deshalb ohne Verdikt (Nutzer
02.09.). `dropouts` = Ueberlauf-Episoden, **die einzige saubere
Ereigniseinheit** und alleinige Leitgroesse. `underflow` = Encoder-Lesetakte mit
PCM-Fehlbetrag, **mit Stille aufgefuellt**, ohne Verdikt. `accumulated_stats`
werden bei Pause und Reconnect **nicht** genullt — nur bei Stack-Neustart.

**`Packet counts (expected/dropped)` sagt NICHTS ueber die Funkstrecke**
(R-010): der Zaehler zaehlt Encoder-Aufrufe und `ldacBT_encode()`-Fehler. Die
fruehere Director-Lesart „auf Paketebene geht nichts verloren“ ist als Deutung
dieses Zaehlers **widerlegt** — als Protokolleigenschaft bleibt sie richtig.

**Der Encoder-Thread laeuft `SCHED_FIFO` Prio 1** (R-008): CPU-Last scheidet als
Hebel endgueltig aus. **LDAC laeuft im Host-Pfad** (T-032, zwei Belege).

**„990 gepinnt = Warteschlangenueberlauf = hoerbare Aussetzer“ ist belegt**
(T-008): ABR 0/0, 990 gepinnt 525/21 durchgehend hoerbar, zurueck auf ABR 0/0.

**Es gibt keine Literaturschwelle** (R-006). Fuer A2DP-Musik existiert **keine**
belegte Hoerbarkeitsschwelle. Regel **R-E** ist dauerhaft: keine abstufenden
Woerter, kein mehrstufiges Bildzeichen fuer Raten echt zwischen 0 und 12/min.
Das Wort „audible“ kommt in der Oberflaeche nicht vor (Grep-Regel).

## Der offene Widerspruch — was T-036 klaeren muss

| Lauf | Bedingung | Ergebnis |
|---|---|---|
| T-029 (02.09.) | 990 gepinnt, WLAN **nie geprueft** | 10 Cluster in 25 min |
| T-032 (03.09.) | 990 gepinnt, WLAN **nicht assoziiert** | **0 Verluste in 27,78 min**, 1143 Samples, Nachweisgrenze 0,1080/min |

**Hypothese, nicht Erklaerung:** die WLAN-Assoziation macht den Unterschied.
n=1 gegen n=1, keine kontrollierte Variable; T-029 nennt selbst Ruhephasen bis
4,4 min. Das ist der erste ernsthafte Kandidat fuer einen belegten Hebel bei 990.

## Die sechs Massnahmen (R-010, Teil 1) — Grundlage des Tuning-Prozesses

Alle ohne Root, alle nur anleitbar bzw. stellbar, **keine unter 990 gemessen** —
die Rangfolge ist Belegstaerke, nicht Wirkungsgroesse.

1. **2,4-GHz-WLAN weg** (aus, oder Router-SSID auf 5 GHz) — eigene Messung
   (n=1 bei 660) plus Koexistenz-Mechanismus
2. **Keine Discovery/Scans** — Koppel-Bildschirm zu, Fast-Pair-Suche aus,
   scannende Apps finden. AOSP-Javadoc woertlich
3. **Kein zweites BT-Geraet, Multipoint am Sink aus** — Sony schaltet LDAC bei
   Multipoint ganz ab
4. **Koerper aus der Funkstrecke, Abstand klein** — Fachliteratur 10-21 dB
5. **USB-3-Kabel ab** — Sekundaerquelle; **990 wurde nie ohne Kabel gemessen**
6. **Sink-Modus, der LDAC zulaesst** — Herstellerdoku, nur Sony belegt

**„Ausweichen“, sichtbar so zu benennen** (Nutzer 03.09.): Stufe senken, ABR,
Codec-Wechsel, 44,1-kHz-Familie (909 statt 990). **990 existiert nur in der
48/96-kHz-Familie**; 96 → 48 kHz spart **keine** Luftzeit, nur Resampling.

**Widerlegt, erscheint nicht im Prozess:** Absolute Lautstaerke, AVRCP-Version,
Bittiefe, Akku-/App-Optimierung, BT-Cache, Neukoppeln, Flugmodus-Zyklus,
Schalter „Bluetooth-Scannen“, „max. Audiogeraete“, Neustart — und auf **diesem**
Geraet der A2DP-Offload-Schalter (LDAC laeuft im Host-Pfad).

## BQR — der staerkste Kanal, und seine Falle

`dumpsys bluetooth_manager` enthaelt einen **Bluetooth-Quality-Report-Abschnitt**
mit Pakettyp, RSSI, Sendeleistungsstufe, Wiederholrate, Nicht-Empfang und
AFH-Kanalauslass — echte Firmware-Telemetrie, ohne Root lesbar.

**Aber: Lesen leert die Queue** (R-011, Quelltext). `bqr::DebugDump()` dequeued
und loescht in einer Schleife; **kein Leser-Cursor**, eine prozessweite Instanz.
Wer liest, nimmt die Ereignisse **allen** weg. Zweiter, unabhaengiger
Verlustweg: die Queue fasst **25** Eintraege und verdraengt den aeltesten.
**`dumpsys bluetooth_manager` ist damit ein Eingriff, kein Read-back.**

**Der Ausweg ist offen** (T-035): Der nicht-destruktive Callback
`registerBluetoothQualityReportReadyCallback` verlangt `BLUETOOTH_PRIVILEGED` —
**uid 2000 haelt sie**, deklariert, gewaehrt und laufzeitdurchsetzbar, belegt mit
drei Verfahren inkl. echtem `checkPermission`-Aufruf. Methode existiert, `public`,
kein Hidden-API-Fehler.

**Die eine offene Frage:** Speist der Callback aus **derselben** Queue? R-011
legt einen eigenen Weg nahe, hat ihn aber nur einfach belegt. **Speist er aus
derselben, ist nichts gewonnen.** → T-037.

## Zaehler-Inventar — mehr als bisher genutzt

Sendeseite: `Counts (flushed/dropped/dropouts)`, `Counts (max dropped)`,
`LDAC saved transmit queue length`. Quellseite: `Counts (underflow)`,
`Bytes (underflow)`, `PCM read counts (expected/actual)`. Scheduling:
`Enqueue/Dequeue deviation counts (overdue/premature)` samt Zeitsummen —
**direkt messbarer Jitter, fuer Tuning die interessanteste Familie, bisher
ungenutzt.**

**Belegstand, nicht ueberdehnen:** Am Quelltext belegt sind nur `dropped`,
`dropouts`, `underflow`. Die Bedeutung von `max dropped` und den
Deviation-Zaehlern ist **Director-Lesart, unbelegt** — gehoert recherchiert,
bevor ein Messapparat darauf gebaut wird.

**`Frames per packet (ave)` taugt NICHT als Pakettyp-Indikator**, und meine
frueher notierte Begruendung war ueberdehnt: T-032 las `115111 / 4 / 13` (dort
ist `max` < `ave`, auffaellig), die 990er-Fixture liest `2763962 / 12 / 12`
(voellig stimmig). Der Wert ist auch nicht „konstant 13“ — das galt innerhalb
eines Laufs. **Was traegt:** weder 12 noch 13 passt zu den erwarteten ~2/~3.
**Offene Frage:** Warum liest dasselbe Feld am selben Geraet einmal 4/13 und
einmal 12/12? Verwendet wird der direkte BQR-Pakettyp.

## „Gesetzt“ heisst NICHT „gebaut“

Die T-009-Parameter stehen in `UI_SPEC.md` **festgelegt**; im Code existiert
**keine** davon. Ebenso fehlen `SETTLING` und die Maschine
CLEAN/OCCASIONAL/DISTURBED/CANNOT_TELL — von `developer` und `qa-engineer`
unabhaengig per Grep bestaetigt (03.09.). Die heutige `LossRow` rechnet eine rohe
Poll-zu-Poll-Differenz. **Rund 25 Akzeptanzkriterien sind dadurch nicht
ungetestet, sondern gegenstandslos.** Das schliesst V-1..V-7.

Entwurf dafuer liegt: `ARCHITECTURE.md` **AD-015..AD-024**. Kern: reine Logik in
`:core-monitor`, **keine Uhr** (Zeitstempel reisen in den Lesungen), **ein**
Fenster (`LossWindow`), `CANNOT_TELL` als versiegelter Zustand mit typisiertem
Grund, Verlust und Stufe als **zwei getrennte Maschinen ohne gemeinsamen Typ**
(R-E strukturell erzwungen). Buendelungskriterium in `UI_SPEC.md` ab Z. 1830
(AK-T030-1..14): Ausbruch = **3 `dropouts`-Episoden in 30 s**. Regel **R-F**:
keine Groesse je Minute/Sekunde in der Verlustanzeige. Regel **R-G**: das Wort
„packet“ steht nicht mehr fuer die Raeumungsfamilie.

## Stand des Codes

`gradlew test`: **2482 Tests, 0 Failures** (03.09., zweimal eigenhaendig
gemessen; Basislinie vor T-034 waren 2470). Die frueher notierten „2390“ passen
zu dieser Zaehlweise nicht.

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
- Toolchain: JDK 21 `~/tools/jdk/jdk-21.0.12.1+1`, NDK gepinnt
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
| QA-014..016 | developer | drei Befunde aus dem T-034-Retest | offen, kein Geraet noetig |
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
