# Stand — 2026-09-29

Kurzfassung fuer die Agenten. Zielbild in `GOAL.md`, Historie in `docs/archiv/HANDOVER.md`,
Entwurf in `ARCHITECTURE.md`, Oberflaeche in `UI_SPEC.md`, Befunde in
`qa/findings.md`. Details stehen dort, nicht hier. Verlauf des Zyklus 5
(28./29.09.): `docs/archiv/state-verlauf-2026-09-28-zyklus5.md`.

**K-3 (Nutzer 03.09.): Erledigtes fliegt SOFORT raus, nicht bei der naechsten
Verdichtung.** Diese Datei ist das Nervensystem des Teams — was hier veraltet
steht, lesen Agenten als Tatsache und melden es weiter. Genau das ist am 03.09.
passiert: eine erledigte PII-Notiz liess den `archivist` einen Falschbefund
melden. Wer einen Punkt abschliesst, loescht ihn hier im selben Zug.

## Hier geht es weiter

**Autonomer Lauf 28.09. 22:51 – 29.09. (Obergrenze 05:30), ohne Geraet,
Branch `auto/2026-09-23-saeule3`, endet pausiert mit PR, nie gemerged.**
Gebaut und am Quellstand geprueft (Berichte `docs/berichte/T-05x/T-06x-*`,
Register `qa/findings.md`, `security/findings.md`):
- Pruefphase Saeule 3 (T-051) → Fixzyklus T-055 (AD-039..043): Messdienst bei
  Display aus (AK-4-Ausnahme), LDAC-Sperre im Vergleich, Profil-Codec im
  Rueckweg, Ausweg aus unlesbarem Ledger, F-011/F-014..F-021, SR-031..033.
- Audit-Bereinigung T-056 (−268 Zeilen netto).
- Features AK-18..AK-21 (P-001..P-004; T-058, AD-044..047): Pruefstand der
  sechs Massnahmen, Umgebung am Verlustereignis, Sprung zu WLAN-/BT-
  Einstellungen, WLAN-Band ohne Standortrecht (`ACCESS_NETWORK_STATE`).
- T-060: SR-039 (Pairing-Log nur Debug), DR-007, falsche „no INTERNET“-Texte,
  README berichtigt. T-061a/T-062: B5, B7, B6 (AD-048 Helfer-Timeout 15 s).
- T-064: SR-041 (Frist fuer die Vorwertlesung), DR-008, Log-Haertung, SR-042
  (Hoerdaten nicht mehr im Logcat). Retest T-065: Security PASS, Design PASS,
  QA CONCERNS (F-025 → naechster Zyklus, architect: Zeitgrenze fuer `exec`).
Suite **2743/0** (Director 29.09. 02:17, `9ae5bae`, `clean test --no-build-cache`).
Lauf beendet 02:2x: `sync-out`, PR gegen `auto/2026-09-23` (gestapelt auf PR #1).

**Abgleich GOAL.md (29.09.):** AK-4, AK-11..AK-16, AK-18..AK-21 am Quellstand
durch QA belegt (T-059a, T-063a); **Geraeteabnahme fehlt** fuer AK-4
(Display aus, AD-039), AK-13/14 (Vergleich am Geraet), AK-20 (Landeort der
Spruenge, W4), AK-21 (Frequenz ohne Standort am Pixel), AK-1 (Last der
Zustandslesung je Takt, ungemessen). AK-7 zurueckgestellt; AK-17 offen M-16.

**Nutzerentscheide 29.09. abends (Reihenfolge: Geraet → Play-Stufe → P-005..7):**
- **Google Play** als neue Stufe, GOAL.md AK-22..AK-27 (Vollfassung mit
  Helfer, nur EU/EWR, Planung mit Einnahmen, Hoerfunktion bleibt mit ehrlichem
  Wortlaut, keine anwaltliche MDR-Pruefung). AK-21 nicht mehr "bedingt"
  (Streichvorschlag 2.3). Streichvorschlag 2.1 folgt aus AK-22.
- P-005, P-006, P-007 freigegeben (Warteschlange, nach der Play-Stufe).
- SR-040 **beheben** (developer, mit der Play-Stufe). Streichvorschlag 2.2
  ohne Entscheid.
- Retro BD-L-004, BD-L-005, BD-L-006 angenommen; Umsetzung in `_rahmen.md`,
  `developer.md`, `director.md` offen (Agenten-Repo).
- Draft-PRs #1/#2 merged der Nutzer nach gruener Geraeteabnahme.

**Beim Nutzer — offen:**
1. Geraetetermin (Pruefliste unten), zugesagt 29.09., volles Programm ca. 2 h.
2. UI-Entscheide (Sprungknoepfe an beiden Orten, P-001-Panel nicht
   einklappbar, Rueckweg-Banner) — Nutzer sieht sie am Geraet an.
3. AK-7 nach bestandener Probe G0–G4 (AD-049) freigeben?
4. Monetarisierung endgueltig (vor Einreichung, AK-27).

**Naechste Geraetesession (Pruefliste):** AD-039-Probe (Arm A 15 min Display
aus auf Akku, Stop vom Sperrbildschirm, FGS-Verweigerung A17); W4/SR-037
(`ScanMode|Discoverable|Discovering` bei offener BT-Seite, nicht waehrend
Messung); AD-045 Landeort beider Spruenge; AD-046 Frequenz 2,4/5 GHz ohne
Standort, `dumpsys package`; AD-049 Probe G0–G4 (T-037, passiv); F-013 Dump bei
`cmd bluetooth_manager disable`; 0-Byte-Ledger mit echtem DataStore; SR-006;
W-7..W-9 (AD-025); T-048 3c (Nutzer: Kopfhoerer aus/an), 4, 5b; T-008 E-1/E-3
(von Hand); Log ohne MAC (`logcat -s DeviceConnectionWatcher`). **Kein
`dumpsys bluetooth_manager` zur Zwischenkontrolle — leert die BQR-Queue
(R-011); App und Helfer vor jeder Messung beenden.**

**Beschlossen, nicht beauftragt:** F-025 (Zeitgrenze fuer `exec` Client+Server,
erst architect), F-004, F-005, F-009 (QA-C), SR-023, SR-024,
F-013 (Behebung nach Geraetedump), F-023 (Default von `loggingEnabled`
strukturell nicht testbar), KDoc `MonitorDatabase.kt:58-59` („months later“
gegen 24 h Aufbewahrung), README-Abschnitte „Wo die Daten liegen“/„Wenn etwas
nicht funktioniert“ (technical-writer, vor einem Release), neu-C am Geraet
bestaetigt. Messbefunde: `docs/archiv/state-messbefunde-2026-09-03.md`.

## AK-17 / T-039 — Beobachtungslauf: gebaut (T-039b), Fixe in T-044

Spec `UI_SPEC.md` T-039-Abschnitt (AK-T039-1..17), Vorgaben wörtlich in
`docs/tasks/T-039.md`. Nutzerentscheide 03.09./22.09. dort. Vorbehalt bleibt:
`RUN_MIN_OBSERVED_MS`/`RUN_GAP_MAX_MS` = 120 s sind begruendete Kanten, keine
Messung (M-15, M-16); bis M-16 kein Wortlaut „lowest rate“. Seit 28.09.:
Sample-Rate-Wechsel beendet den Lauf (Nutzerentscheid).

## Stand des Codes

`gradlew test`: **2743 Tests, 0 Failures** (Director, 29.09. 02:17, `9ae5bae`,
`clean test --no-build-cache`; F-006 Flaky offen).

**Lehren, die weiter gelten** (`qa/findings.md`): (1) Ein Kommentar- oder
Textbefund ist erst erledigt, wenn **projektweit gesucht** wurde. (2) Ein
**positiver** Testfall kann eine Fensterbreite strukturell nicht binden; dafuer
braucht es einen negativen. (3) Neu 29.09.: Ein Grep auf Konstruktoraufrufe
findet Reflection-Instanziierung nicht (F-022 verworfen, `MonitorConditionBookTest`).

**T-034:** Golden-Test bindet den echten 990er-Verlustdump an behauptende Tests;
prueft **AK-T009-24 nicht** (Verlust-Verdikt-Pfad).

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
SR-037 (Geraet), SR-040 (Nutzer).

## Laufende und offene Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-036 | performance-tuner | Doppelaufnahme AK-T009-24 | offen, braucht Verlustphase am Geraet |
| T-037 | performance-tuner | Callback-Probe G0–G4 nach AD-049 (Messschritt, nicht Bau) | offen, Geraet |
| T-006 | architect nach developer | Transport SR-001/SR-009 | Entwurf abgenommen, Umsetzung offen |
| T-001 | performance-tuner | Vergleichslauf gegen Block 1 | offen, **vor** dem Transport-Messlauf |
| T-008 | performance-tuner | E-1/E-3 (Nearby-Scans, Spatializer aus) | offen, **kein Shell-Hebel**, nur von Hand |
| SR-012 | performance-tuner | `umask 077` in `docs/perf/tools/*.sh` | zurueckgestellt bis Ende der Messreihe |
| T-005 | architect | Scan-Entwurf S-1..S-7 | **ruht** (Nutzer 02.09.) |

## Zwei offene Fixture-Luecken

1. Kein Dump eines Builds **ohne** die beiden ABR-Zeilen.
2. **Nur ein Rung-Wert aufgenommen** (Index 4 / 396 kbps). Die Paare 660/1 und
   492/3 stehen nur in der Messdoku, nicht in einer Fixture.

## Zurueckgestellt

- Kein `CHANGELOG.md`, kein gebautes Artefakt — fuer den `power-user` gibt es
  deshalb noch keinen Ausgangspunkt.
- QA-005: zwei ABR-Felder ohne Konsumenten; `adaptiveBitrateIndex` streichen
  vom Nutzer angenommen (28.09.), nicht beauftragt. `A2dpTxProbe.sampleBetween`
  kopiert nur `bitrateKbps` und `qualityModeLabel`.
- Widerspruch R-001 gegen Messung: 492 kbps ist gemessen, gilt dort aber nicht
  als Nominalstufe. Leiter fuer 96 kHz/32 bit unverstanden.
- **Der 660er-Verlustfall aus T-027 ist NICHT als Hebel weiterzuverwenden** —
  in T-028 ueber acht gueltige Abschnitte nicht reproduziert.
- Vierte T-008-Zelle bleibt **INCONCLUSIVE** (WLAN-Konfundierer).
- Git-Historie: eine LAN-IP steht in aelteren Commits von
  `T-029-990-korrelation.md`. **Empfehlung des Directors: nichts tun**, solange
  das Repo privat ist. Vor einer Veroeffentlichung neu bewerten.
- Zeilenenden: drei Dateien liegen mit LF im Arbeitsbaum, der Rest CRLF.
