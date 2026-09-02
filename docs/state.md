# Stand — 2026-09-02, Part 4

## UEBERGABE an die naechste Session — hier geht es weiter

**T-027 ist beauftragt, aber NICHT gelaufen.** Der Agent wurde durch ein
Prozessende abgebrochen, **bevor** er das Geraet angefasst hat. Er hat nichts
hinterlassen: kein `docs/perf/T-027-messung.md`, keine Geraeteaenderung, keine
halbfertige Zelle. Der Auftrag `docs/tasks/T-027.md` ist vollstaendig und kann
**unveraendert neu erteilt werden** — Rolle `performance-tuner`, Modell sonnet.

**Der Nutzer ist bereit:** Kopfhoerer verbunden, Musik laeuft (Stand 02.09.).

**Stufe fuer die Messung: 660 kbps**, in den Entwickleroptionen als
„Ausgewogene Audio- und Verbindungsqualitaet" — **nicht** „Adaptive Bitrate".
Begruendung: hoechste fest waehlbare Stufe mit **null** Verlusten in T-008, hat
also Luft nach oben; 990 ist untauglich, weil dort schon ohne Stoerung Verluste
auftreten und die Kennlinie gesaettigt startet; adaptiv waere der Konfundierer
selbst. In den Entwickleroptionen sind ohnehin nur 990 / 660 / 330 fest
waehlbar — 492 und 396 erscheinen nur unter ABR.

**Erster Schritt bleibt das Gate (Phase 0 in T-027):** traegt die Gleichsetzung
`Priority: 1000000` gleich `quality_mode_index` / `codecSpecific1`, ja oder
nein? Ohne belegtes Ja keine Phase 1-3.

**Danach, und erst danach: die Hoersitzung mit dem Nutzer.** Die Kennlinie sagt
nur „diese Stoerung erzeugt diese Rate". Die Zuordnung zum Hoereindruck
entsteht ausschliesslich dadurch, dass Daniel hoert und der Director
protokolliert — R-006 hat belegt, dass es dafuer keine Literaturschwelle gibt.
Kein Agent darf diese Zuordnung schaetzen.

**Offen aus dem Research-Block, nach der Messung in einem Rutsch:**
`UI_SPEC.md` nachziehen (`ui-ux-designer`, Spec-Modus, opus) — `dropped`
verliert das Verdikt, `encoder underflows` traegt in der T-009-Tabelle
faelschlich noch `OCCASIONAL`, und AD-019 braucht die entsprechende
`None`-Begruendung.


Kurzfassung fuer die Agenten. Zielbild in `GOAL.md`, Historie in `HANDOVER.md`,
Entwurf in `ARCHITECTURE.md`. Details stehen dort, nicht hier.

## Laufender Lauf: Rahmen und Ende

Auftrag des App Designers 02.09.: **erst Fundament, dann Bau.** Vor V-1 muessen
die Verlustwerte belastbar sein — die heutigen Schwellen wirken erfunden.
Obergrenze: bis der Schwerpunkt Verlustmechanik abgeschlossen und von QA gruen
abgenommen ist. Der Lauf endet dort, oder frueher bei kritischem
Sicherheitsbefund, Datenverlustverdacht oder zwei Zyklen ohne messbaren
Fortschritt.

Warteschlange (vom Nutzer freigegeben 02.09.):
1. Research-Block T-024/T-025/T-026 — **erledigt**, R-005/R-006/R-007
2. Kalibriermessung am Geraet — **naechster Schritt**, Zuschnitt steht (siehe unten)
3. V-1..V-7 Verlustmechanik bauen

## Der Research-Block — was er geaendert hat (02.09.)

Drei Antworten, die zusammen die Grundlage der Verlustmechanik verschieben.
Volltext in `docs/research/R-005.md`, `R-006.md`, `R-007.md`.

**R-005 — die Zaehler bedeuten nicht, was die Achse unterstellt.** Belegt an
AOSP `btif_a2dp_source.cc` (`main` und `android17-release` gegengeprueft):

- `dropped` = verworfene **Warteschlangen-Eintraege**, A2DP-Medienpakete mit
  **variabler** Anzahl gebuendelter Codec-Frames. **Kein festes Stueck
  Audiozeit.** Eine Rate „je Minute" addiert darauf ungleich grosse Dinge.
- `dropouts` = **Ueberlauf-Episoden** (Warteschlange am Stueck geleert). Saubere
  Ereignisgroesse; „je Minute" passt hier. Die Messung 525/21 = 25,0 Eintraege
  je Raeumung ist genau die Struktur, die der Quelltext erwarten laesst.
- `underflow` = Encoder-Lesetakte mit PCM-Fehlbetrag, bei LDAC fest 20 ms, aber
  **mit Stille aufgefuellt statt uebersprungen**. Keine „verlorenen
  Millisekunden". Erklaert strukturell, warum das Feld im Ruhelauf ungleich 0
  war und im hoerbar kaputten Arm 0 blieb.
- **Nebenbefund, nicht bestellt:** Die sichtbaren `accumulated_stats` werden bei
  Pause, Reconnect und Codec-Wechsel **nicht** genullt — nur ein vollstaendiger
  Neustart des A2DP-Source-Moduls ohne vorher aktiven Peer setzt zurueck. Das
  stuetzt die Poll-zu-Poll-Differenz staerker ab als vom Entwurf angenommen.
  **Folge fuer AD-020:** `COUNTERS_RESET` bleibt noetig, ist aber seltener als
  gedacht; der Ausloeser ist Stack-Neustart, nicht Pause.

**R-006 — es gibt keine Literaturschwelle, und die Achse ist die falsche.**

- Fuer **A2DP-Musik** existiert **keine belegte Hoerbarkeitsschwelle** auf
  irgendeiner Groesse. `LOSS_ALERT_RATE_PER_MIN` = 12/min hat ausserhalb dieses
  Projekts keinen Rueckhalt.
- Wo Literatur existiert (VoIP, PHY-Konformitaet, Streaming-QoE), ist die
  tragende Groesse **Verlust in Prozent bzw. verlorener Zeitanteil**, ergaenzt
  um **Lueckenlaenge und Burst-Muster**. „Ereignisse je Minute" taucht als
  normierte Achse in **keiner** gefundenen Quelle auf.
- Android kennt `QUALITY_REPORT_ID_A2DP_AUDIO_CHOPPY`, der ausloesende Wert
  steckt aber in der Chip-Firmware und ist nicht oeffentlich.
- **LDAC-Concealment ist oeffentlich undokumentiert.** Existiert eines, laufen
  Zaehlerstand und Hoereindruck systematisch auseinander — dann ist jede
  zaehlerbasierte Schwelle ein Stellvertreter und muss so beschriftet werden.

**R-007 — M-11 ist moeglicherweise messbar, und die Konfundierung war ein
Fehler der Versuchsanordnung, nicht des Geraets.**

- Belegt an AOSP `a2dp_vendor_ldac_encoder.cc`: Der Encoder gibt sein
  ABR-Handle frei und stoppt `ldac_ABR_Proc()`, **sobald die Stufe fest statt
  auf ABR steht.** Bei gepinnter Stufe kann eine Stoerung die Stufe also gar
  nicht mehr verschieben — nur noch Verlust erzeugen.
- **Damit kippt die bisherige Lesart:** Nicht das Pinnen konfundiert. T-008 hat
  verschiedene **Stufen** gegeneinander verglichen — das war der Konfundierer.
  **Fester Pin plus dosierter externer Stoerhebel** haelt die Stufe per
  Konstruktion konstant und variiert allein den Verlust. Das sind die
  Zwischenpunkte, die M-11 bisher gefehlt haben.
- Rangfolge der Stoerhebel nach Dosierbarkeit mal Konfundierungsfreiheit:
  **kontrollierte 2,4-GHz-Belegung** (kuenstlicher WLAN-/BLE-Verkehr) ist der
  feinste ohne Root; physische Daempfung braeuchte einen kalibrierten
  Abschwaecher, den das Projekt nicht hat; Rechenlast ist wegen `SCHED_FIFO`
  kaum dosierbar (deckt sich mit dem bereits erfolgten Ausschluss); HCI-Ebene
  braucht Root plus deaktiviertes SELinux, Gegenstelle bietet keinen Testmodus.

**Die eine Pruefung, an der alles haengt — vor jeder Kalibriermessung:**
Ist der projekteigene Pin-Marker (`Priority: 1000000` aus T-008) **dasselbe
Feld** wie `quality_mode_index` / `codecSpecific1` im LDAC-Encoder, oder ein
unabhaengiges Feld (A2DP-Codec-Auswahlprioritaet)? Die numerische Aehnlichkeit
ist auffaellig, aber **nicht bewiesen**. Traegt die Gleichsetzung nicht, faellt
die ganze R-007-These, und M-11 bleibt unmessbar. Das ist ein Read-back am
Geraet, kein Schreibtischschluss — und es ist der **erste** Schritt der
Messreihe, nicht ein Nebenpunkt.

Ruhen laesst der Nutzer: **T-005 Scan** (die sechs Entscheidungen sind nirgends
aufgeschrieben; erst neu herleiten lassen, wenn der Scan ansteht).

## Die tragenden Befunde (belegt, nicht mehr zu diskutieren)

- **"990 gepinnt gleich Warteschlangenueberlauf gleich hoerbare Aussetzer" ist
  belegt** (T-008, `docs/perf/T-008-experimente.md`). A/B/A: ABR 0 Drops /
  0 Dropouts, 990 gepinnt 525/21 (13/min, durchgehend hoerbar), zurueck auf ABR
  wieder 0.
- **Underflow ist als Leitgroesse untauglich.** Blieb in ALLEN Armen 0 — auch im
  hoerbar kaputten. Im 39-min-Ruhelauf dagegen 2 auf 25 (0,591/min). Taugt weder
  fuer Ueberlast noch fuer Ruhe. Traegt seit 02.09. **kein Verdikt** mehr,
  bleibt aber sichtbar (QA-001).
- **Die zwei Hoerbarkeitspunkte sind konfundiert.** 0 Dropouts gab es nur bei
  492/660, 13/min nur bei 990. Belegt ist "990 gepinnt klingt kaputt", **nicht**
  "13/min sind hoerbar". Das ist der Anlass des Research-Blocks.
- **Die Ruherate ist null, belastbar** (M-5, T-011): 38,93 min, 1795 Samples,
  jeder der 1794 Uebergaenge geprueft. Dreierregel-Obergrenze **0,063/min**;
  `LOSS_NOTICE_RATE_PER_MIN` = 1/min liegt rund 16-fach darueber. Wert bleibt.
- **ABR probiert 990 von sich aus, n=31:** 30-mal fuer genau ein Sample, nie mit
  Verlust.
- **Ursache des Queue-Ueberlaufs bleibt offen.** Ausgeschlossen: WLAN, Doze,
  CPU-Knappheit. Gestuetzt, nicht belegt: "990 ist fuer diese Strecke zu
  schnell".
- **R-E ist dauerhaft:** keine abstufenden Woerter, kein mehrstufiges Bildzeichen
  fuer Raten echt zwischen 0 und 12/min, solange M-11 unmessbar ist. Das Wort
  "audible" kommt in der Oberflaeche nicht vor (AK-T009-43, Grep-Regel).

## "Gesetzt" heisst NICHT "gebaut"

Die T-009-Parameter stehen in `UI_SPEC.md` **festgelegt**; im Code existiert
**keine** davon (T-021, per Grep). Ebenso fehlen der `SETTLING`-Zustand und die
Maschine CLEAN/OCCASIONAL/DISTURBED/CANNOT_TELL. Die heutige `LossRow` rechnet
eine rohe Poll-zu-Poll-Differenz. **Rund 25 Akzeptanzkriterien sind dadurch
nicht ungetestet, sondern gegenstandslos.** Genau das schliesst V-1..V-7.

## T-023 geliefert und auf `master` (02.09.)

`ARCHITECTURE.md` **AD-015..AD-024** plus fuenf Eintraege "Bewusst nicht getan".
Kern: reine Logik in `:core-monitor` (`link/live/verdict/`), gefaltet im
ViewModel. **Keine Uhr** — Zeitstempel reisen in den Lesungen. **Ein** Fenster
(`LossWindow`); der Ueberblicks-`LiveTrace` wird daraus **projiziert** statt
zweimal akkumuliert. `CANNOT_TELL` ist versiegelter Zustand mit typisiertem
Grund. Eine Schwelle ohne Messung ist ein eigener Typ (`Open`/`None`), keine
Zahl. Verlust und Stufe sind **zwei getrennte Maschinen ohne gemeinsamen Typ** —
R-E ist damit strukturell erzwungen, nicht redaktionell.

**Schrittfolge V-1..V-7, kein Schritt braucht ein Geraet.** V-1 schliesst QA-012
nebenbei mit.

Zwei Nachtraege aus T-023:

- **AD-022 korrigiert eine Director-Notiz:** Im gepinnten Modus ist die
  Stufenzeile **nicht** `CANNOT_TELL` — Verlustzaehler und gemessene Stufe sind
  lesbar. Nur die **ABR-Fakten** fehlen (Rung-Index, Wechselzaehler, Anteile,
  G-4-Markierung). Nur dorthin gehoert `CANNOT_TELL`.
- **An den `ui-ux-designer`:** `UI_SPEC.md` (T-009-Tabelle) laesst
  `encoder underflows` noch `OCCASIONAL` tragen. Widerspricht dem gebauten Stand
  und QA-001. Nachzuziehen.

**Entscheidung Nutzer 02.09. (nach R-005):** `dropped` **verliert das Verdikt**
— gleiche Behandlung wie `underflow`. Es bleibt als Zahl sichtbar, traegt aber
keine Schwelle. Grund: `dropped` zaehlt Eintraege variabler Groesse, eine Rate
darauf addiert Ungleiches. **Alleinige Leitgroesse fuer Ueberlast ist
`dropouts`** (Episoden je Minute) — die einzige Groesse, die der Quelltext als
saubere Ereigniseinheit hergibt. Folge fuer AD-019: `LOSS_*_RATE_PER_MIN` fuer
`dropped` wird `None` mit Grund, nicht `Measured`. `UI_SPEC.md` ist
entsprechend nachzuziehen.

**Entscheidung Nutzer 02.09.:** App- und Mixer-Underruns bleiben **unbeurteilt**
(sichtbar als Zaehler, kein Verdikt), solange M-1 ihre Ruherate nicht kennt.
Keine rote Zeile mehr aus diesen Kanaelen. AD-019 wird so gebaut.

## Rahmen und Geraet

- Pixel 11 Pro `67011FDKX004XG`, Android 17, **per Kabel verbunden** (02.09.).
  **Kein A2DP-Link** — Kopfhoerer getrennt seit 13:27. Jede Messung braucht
  Kopfhoerer verbunden **und** laufende Musik. `A2dpOffloadEnabled: true`.
- Toolchain steht: JDK 21 unter `~/tools/jdk/jdk-21.0.12.1+1`, NDK gepinnt
  `27.3.13750724` (durch nativen Bau belegt, 16-KB-Zusage haelt), build-tools
  35.0.0 genuegt AGP 8.9.3. `gradlew test`: **2390 Tests, 0 Failures**.
- **Risiko R-2:** zwei adb-Binaries (`C:\RSL\2.1HF5\adb\adb.exe` und
  `platform-tools\adb.exe`) killen sich den Server. Waehrend einer Messung nur
  **eines** benutzen.
- **`sdkmanager` liefert bei Erfolg Exit-Code 127.** Erfolg am Dateisystem
  pruefen, nie am Exit-Code.
- Kein Emulator (kein Hypervisor). Alles ausser Geraetetests laeuft ueber
  Unit-Tests und Robolectric.
- **Methodischer Vorbehalt:** USB-3 strahlt ins 2,4-GHz-Band; alle Messungen
  liefen am Kabel. Kontrollmessung ueber drahtloses adb steht aus.
- **Verfahrensregel:** Read-back deckt das **vollstaendige** Zustandsbuch ab,
  nicht nur die geaenderte Variable (Anlass: unbemerktes WLAN machte die vierte
  T-008-Zelle wertlos).

## Laufende und offene Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-023 | architect | Entwurf Verlustmechanik | **erledigt**, AD-015..AD-024 auf `master` |
| T-024 | researcher | Semantik `dropped`/`dropouts`/`underflow`, nach R-005 | **laeuft** |
| T-025 | researcher | Hoerbarkeitsschwellen A2DP, nach R-006 | **laeuft** |
| T-026 | researcher | Hebel fuer Verlust bei konstanter Stufe (M-11), nach R-007 | **laeuft** |
| T-021 | developer | AK-Verankerung (QA-008) | **erledigt**; Retest gelaufen, Restbefunde QA-012/QA-013 |
| T-022 | performance-tuner | Fixtures Verlustfall/Stufen/Pause | geliefert |
| T-001 | performance-tuner | Vergleichslauf gegen Block 1 | offen; **vor** dem Transport-Messlauf (U-6/S-6) |
| T-006 | architect nach developer | Transport SR-001/SR-009 | Entwurf abgenommen (AD-010..014, U-0..U-6); Umsetzung offen |
| T-005 | architect | Scan-Entwurf S-1..S-7 | **ruht** (Nutzer 02.09.) |
| T-008 | performance-tuner | E-1/E-3 (Nearby-Scans, Spatializer aus) | offen, **kein Shell-Hebel** — nur von Hand |
| SR-012 | performance-tuner | `umask 077` in `docs/perf/tools/*.sh` plus Reste loeschen | zurueckgestellt bis Ende der Messreihe |
| QA-012 / QA-013 | developer | vakuum-gruene Grep-Regel, schwacher AK-T009-29-Test | offen; QA-012 faellt in V-1 |

**Offene Hoch-Sicherheitsbefunde:** SR-001 und SR-009 — weltles- und
-schreibbare Dumps bzw. Helper-Log in `/data/local/tmp`, **ueberleben die
Deinstallation**. Behebung ist T-006/U-0..U-6, braucht Geraet.

## Drei Fixture-Luecken — fuer die naechste Geraetesitzung

1. **Kein aufgenommener Dump aus dem 990er-Arm** mit `dropped`/`dropouts`
   ungleich 0. AK-T009-24 laeuft heute ueber gesetzte Zaehlerstaende — ein Test
   ueber die Arithmetik, **kein** Beleg, dass der Parser einen echten 990er-Dump
   liest. **Wer je wieder auf 990 pinnt: Dump mitnehmen.**
2. Kein Dump eines Builds **ohne** die beiden ABR-Zeilen.
3. **Nur ein Rung-Wert aufgenommen** (Index 4 / 396 kbps). Die Paare 660/1 und
   492/3 stehen nur in der Messdoku, nicht in einer Fixture.

## Offen — braucht den Nutzer oder seine Hand am Geraet

- **Kopfhoerer verbinden und Musik starten**, bevor gemessen werden kann.
- **Codec-Pin-Zustand ist widerspruechlich dokumentiert** (`Priority: 5001` im
  T-011-Read-back gegen `1000000` in der Part-3-Notiz). Vor der naechsten
  Messung frisch lesen, nicht aus den Notizen glauben.
- E-1 (Nearby-Scans aus) und E-3 (Spatializer aus) haben **keinen Shell-Hebel** —
  am Geraet vollstaendig geprueft. Nur von Hand.
- Vierte T-008-Zelle bleibt **INCONCLUSIVE** (WLAN-Konfundierer).

## Zurueckgestellt

- Kein `CHANGELOG.md`, kein gebautes Artefakt, keine Installationsanleitung —
  fuer den `power-user` gibt es deshalb noch keinen Ausgangspunkt.
- QA-005: zwei ABR-Felder ohne Konsumenten; `TxProbeSample` traegt sie nicht.
  **Kopplung fuer den UI-Zyklus:** `A2dpTxProbe.sampleBetween` kopiert nur
  `bitrateKbps` und `qualityModeLabel` — der Nahaufnahme-Kanal bekommt die neuen
  Felder nicht, und G-4/AK-T009-41 braucht den Zaehler genau dort.
- Aufnahme gegen R-001..R-004 abgleichen (der tuner hat sie nie gelesen).
- Widerspruch R-001 gegen Messung: 492 kbps ist gemessen, gilt dort aber nicht
  als Nominalstufe. Leiter fuer 96 kHz/32 bit unverstanden.
- `AudioEffectSessionReceiver` exportiert — eigenes Security-Review ausstehend.
- QA-011: historische Fixture, ehrlich markiert.
