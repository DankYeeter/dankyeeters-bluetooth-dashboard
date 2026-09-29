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

**Session 29.09. abends (Nutzer anwesend, Geraet am Kabel).** Autonomer Lauf
28./29.09. steht in PR #2 und `docs/archiv/state-verlauf-2026-09-28-zyklus5.md`.

**Geraeteabnahme T-066 (laeuft, qa-engineer, Berichte `docs/berichte/T-066a/c-*`):**
PASS am Geraet: AK-20 (Landeort, Neulesen), AK-21 bei 2,4 GHz und bei
getrenntem WLAN, AK-4 Leerlauf (kein `exec` ohne Monitor/Messung), Stop vom
Sperrbildschirm, Log ohne MAC, W4/SR-037. Offen: AK-21 5 GHz (Handgriff
Nutzer), Vergleich A2 auf Akku 2x15 min (AK-4/AD-039, AK-13, AK-14),
FGS-Verweigerung, Retest F-026/SR-040/F-025 am neuen Stand, T-066b
(G0–G4, AK-1-Last, T-048 3c/4/5b, T-008 E-1/E-3, SR-006, W-7..W-9), F-013-Dump.
**Kein `dumpsys bluetooth_manager` zur Zwischenkontrolle — leert die
BQR-Queue (R-011); App und Helfer vor jeder Messung beenden.** F-1 (Stop-Benachrichtigung auf dem
Sperrbildschirm nur bei `lock_screen_show_silent_notifications`=1):
Director 21:20 — AK-4 erfuellt, Nutzereinstellung.
**Geraet vom Test veraendert:** Autoapply fuer Noble FoKus pausiert, LDAC-Stufe
330, Pin 990 — Nutzer stellt Autoapply/Adaptive am Ende zurueck.
adb-Freigabe fuer die Agenten: `.claude/settings.local.json` (lokal, nach dem
Test loeschen).

**Gebaut diese Session (gemergt, Suite 2712/0, Director 22:45, `3c14ad0`,
`clean test --parallel`):** F-026 (T-068), SR-040 (T-069), Ponytail-Bereinigung
T-071 (−1344 Zeilen Code, 23 Testmethoden mit weg), AD-050 Manifest ohne
QAP/BLUETOOTH_PRIVILEGED (T-072), AD-053 exec-Frist/F-025 (T-073). Alles ohne
Geraet; Retest am Geraet offen.

**Play-Stufe (AK-22..27, Entwurf AD-050..054):** Vorlauf T-070a: **FAIL fuer
die Einreichung** wegen SR-001 (hoch) auf Kaeufergeraeten → **T-006 (Transport
ohne Dateisystem) ist Vorbedingung der Einreichung** (Director 21:55, kein
WAIVED). Vorgaben A1..C4 in `docs/berichte/T-070a-security-reviewer.md`
(D1–D4, E1–E5 fuer H-3 offen). C-002 (Kaufpreis EU/EWR): GELB, A-021..A-030.
Naechste Laeufe: T-W (technical-writer + ui-ux: privacy.txt, Impressum,
Lizenzen, Store-Text, AK-24-Wortliste aus C-002 Befund 8, 19 Zeilen), dann H-3
(AD-051/052 Code + D/E-Vorgaben), dann T-006, dann QA.

**Nutzerentscheide 29.09. abends:** Google Play (Vollfassung mit Helfer, nur
EU/EWR, **Kaufpreis im Store**), Datenschutzerklaerung in **eigenem
oeffentlichen Mini-Repo** (GitHub Pages, DankYeeter), Hoerfunktion bleibt,
Texte auf "Klang nach Hoerprofil", **kein Anwalt**, MDR/HWG-Restrisiko traegt
der Nutzer; **Pflegezusage 2 Jahre**; Nebentaetigkeit/Gewerbe/Steuer laut
Nutzer geklaert. P-005..P-007 freigegeben (nach Play-Stufe). Retro BD-L-004..006
angenommen, Umsetzung in Agenten-Repo offen. Draft-PRs #1/#2 merged der Nutzer
nach gruener Geraeteabnahme.

**Beim Nutzer — offen:**
1. Geraet: WLAN auf 5 GHz, danach Vergleich auf Akku (Handgriffe kommen gebuendelt).
2. Oeffentliche Anschrift fuer Impressum/Traderstatus (C-002 Befund 1/2) — haelt nur die Einreichung.
3. UI-Entscheide am Geraet (Sprungknoepfe, P-001-Panel, Rueckweg-Banner); Wortlaut
   "Only known EQ apps are recognized …" (T-072).
4. AK-7 nach G0–G4; AK-T009-41 (`adaptiveBitrateAdjustments` ohne Leser) umsetzen
   oder aufgeben; Streichvorschlag 2.2.

**Beschlossen, nicht beauftragt:** Klasse "Ausnahmetext in UI" (33 Kandidaten,
`docs/berichte/T-068-developer.md`), `LiveCodecSnapshot.rawCodecName` ohne Leser,
veraltete Stellen nach T-071 in ARCHITECTURE.md/UI_SPEC.md (Bericht T-071),
SR-016-Hypothese Handover-Eigentuemer (T-070a), F-004, F-005, F-009 (QA-C), SR-023, SR-024,
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
