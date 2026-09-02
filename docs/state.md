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
2. Kalibriermessung T-027 — **teilweise erledigt**, siehe unten. Offen bleibt
   die **Hoersitzung**, und die muss vorwaerts gefuehrt werden.
3. V-1..V-7 Verlustmechanik bauen

## T-027 — was gemessen wurde (02.09. Abend)

Berichte: `docs/perf/T-027-messung.md` (Gate, Phase 1, 5-GHz-Leiter),
`docs/perf/T-027-messung-24ghz.md` (2,4-GHz-Leiter),
`docs/perf/T-027-hoereindruck.md` (Hoerprotokoll, **fuehrt nur der Director**).
Rohdaten gesichert unter `C:\Users\Daniel\t027-rawdata` — **ausserhalb des
Repos**, weil sie Geraetenamen und MACs enthalten (SR-012-Familie).

**Gate: `Priority` ist NICHT die LDAC-Stufe.** Drei Stufenwechsel, `Priority`
bewegt sich kein einziges Mal; `mCodecSpecific1` bewegt sich jedes Mal und
kehrt exakt zurueck (1001 = MID/660, 1002 = LOW/330, 1003 = ABR). Die alte
Notiz „Pin-Marker 1000000" ist damit widerlegt, der Widerspruch 5001 gegen
1000000 war eine Scheinfrage. **Der Stufen-Read-back laeuft ab jetzt ueber
drei Groessen gemeinsam:** `mCodecSpecific1`, `LDAC quality mode`, Abwesenheit
der ABR-Zeilen.

**Phase 1 — Ruherate bei gepinnten 660 (neu, gab es nicht):** 39,78 min,
1861 Samples, **alle 1860 Uebergaenge geprueft**, `dropped`/`dropouts` = 0/0.
Dreierregel-Obergrenze **0,0754/min**. `LOSS_NOTICE_RATE_PER_MIN` = 1/min liegt
rund 13-fach darueber — der Wert haelt auch fuer den gepinnten Fall.

**5-GHz-Leiter: sauberes Nein.** Sieben Zellen, bis 16 Stroeme, real bis
~365 Mbit/s: **0/0 in jeder Zelle**, kein dosisabhaengiges Muster, Queue in
allen 1264 Samples leer. Rueckkehrzelle identisch zur Kontrolle (A/B/A' sauber).
Nachweisgrenze 0,105/min — lockerer als Phase 1, weil kuerzer; das gehoert zur
Aussage. **Last auf dem 5-GHz-Link erreicht den Bluetooth-Pfad nicht.**

**2,4-GHz-Leiter (nach Router-Umstellung durch den Nutzer, verifiziert:
2462 MHz): zwei echte Funde und ein Haken.**

| Zelle | Δ`dropped`/min | Δ`dropouts`/min |
|---|---|---|
| Kontrolle 0 | **5,86** | 0,24 |
| 1 Strom (19:24:04–19:28:23) | **39,3** | **1,71** |
| 2 / 4 / 8 / 16 Stroeme | 0 | 0 |
| Rueckkehr 0' | 0 | 0 |

- **Fund 1: Die Ruherate ist umgebungsabhaengig, nicht geraetefest.** Allein die
  2,4-GHz-Assoziation hebt die Kontrollzelle von 0 auf 5,86 `dropped`/min — bei
  identischer Stufe, wo Phase 1 ueber 40 min exakt null hatte. **Das ist fuer
  die Anzeige bedeutsam:** eine feste Schwelle unterstellt eine feste Ruherate.
- **Fund 2: dritte Fixture-Luecke geschlossen** —
  `bt_manager_pixel11_ldac_pinned_660_24ghz_induced_loss.txt`, echter Verlust
  bei fester Stufe, extern induziert. **Offen: Golden-Test dazu fehlt**, gehoert
  an `developer`/`qa-engineer`.
- **NACHTRAEGLICHER KONFUNDIERER, gefunden beim T-028-Vorabtest:** Das Geraet
  hat die WLAN-Assoziation um **19:48:49** verloren und sie nicht
  wiederhergestellt. Das faellt **mitten in die 16-Strom-Zelle** (19:44:24–
  19:50:51), und die **Rueckkehrzelle 0' (19:51:38–19:55:49) lief vollstaendig
  ohne WLAN**. Sie ist damit **keine gueltige Gegenprobe** fuer „2,4 GHz
  assoziiert, ohne Last" — sie misst „kein WLAN". Der A/B/A'-Beleg aus dem
  Phase-4-Bericht traegt in dieser Form nicht und ist beim naechsten Lauf zu
  wiederholen.
  **Hypothese, ungeprueft, aber jetzt naheliegend:** Wenn die Verbindung unter
  hoher Stromzahl bereits schwaechelte, war die tatsaechliche 2,4-GHz-Belegung
  bei 8 und 16 Stroemen moeglicherweise **geringer** als bei 1 Strom — was das
  nicht-monotone Muster erklaeren wuerde, ohne einen Zufall bemuehen zu muessen.
  Der naechste Lauf muss die Assoziation deshalb **je Abschnitt** pruefen, nicht
  nur am Anfang.
- **Der Haken: die Leiter ist NICHT monoton.** Ausschlag bei einem Strom, ab
  zwei Stroemen nichts mehr, obwohl der Durchsatz weiter stieg. Das ist **keine
  Dosis-Wirkungs-Beziehung**. Der Ausschlag bei Stufe 1 ist ein **einzelnes,
  unwiederholtes Ereignis** — jede Stufe lief genau einmal, es gibt keine
  Streuung. **Daraus laesst sich keine Schwelle ableiten.** Reproduzierbarkeit
  ist offen und muss der naechste Lauf klaeren.

**Hoersitzung ergebnislos — und das ist ein Verfahrensbefund.** Rueckblickend
abgefragt konnte der Nutzer fuer beide Zeitfenster nur „weiss ich nicht mehr
sicher" sagen. Er hoerte nebenbei, ohne zu wissen, wann ein Reiz anlag. Die
naechste Sitzung muss **vorwaerts, aktiv und blind** gefuehrt werden, mit
kurzen, mehrfach wiederholten Abschnitten — Anforderungen stehen in
`docs/perf/T-027-hoereindruck.md`. **Ohne diese Sitzung gibt es keinen
belegten Hoerbarkeitspunkt**, und `LOSS_ALERT_RATE_PER_MIN` bleibt unbelegt.

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

## NEUE RICHTUNG — Entscheidungen des App Designers 02.09. spaet

Nach T-029 hat der Nutzer das Vorhaben erweitert. Drei Bausteine, in dieser
Reihenfolge:

1. **Bessere Verlustdarstellung.** Zwei getrennte Zahlen statt einer:
   **hoerbare Verluste** (Sendeseite: `dropped`/`dropouts`, Warteschlangen-
   raeumung, Audio das nie rausging) gegen **regulaere** (Quellseite:
   `underflow`, mit Stille aufgefuellt). Belegt: heute korrelierte der
   Hoereindruck mit der Sendeseite, waehrend underflow in einem 39-min-Lauf
   ohne jede Wahrnehmung hochlief.
2. **Testsuite:** exakt messen, wie viele Pakete bei LDAC 990 wie verloren
   gehen; Einstellungen durchtunen und jeweils gegenpruefen.
3. **Bluetooth-Tuning im Dashboard**, als **gefuehrter Prozess** — nicht als
   Liste vorgeschlagener Regeln, weil moeglicherweise jedes Geraet anders ist.

### Entscheidungen des Nutzers dazu

- **Die App aendert keine Einstellungen selbst, sie leitet nur an.** Kein
  Ausbau des privilegierten Helfers, keine neue Angriffsflaeche.
- **Stress = 990 gepinnt PLUS externe Funklast.** Achtung: Die externe Last
  hat sich in T-028 als Hebel NICHT reproduzieren lassen; sie ist vor dem Bau
  der Suite erst zu belegen.
- **Zielbild wird neu geschrieben**, zwei Varianten zur Wahl: urteilende App
  gegen zeigende App, jeweils mit Tuning als drittem Standbein.
- **Sitzungslaenge, aus den Messdaten abgeleitet:** Laengste verlustfreie Phase
  bei 990 war 4,4 min, typisch 40 s bis 2 min, 10 Cluster in 25 min.
  Abwesenheit zu belegen braucht daher >5 min. **Vergleichen** braucht das
  nicht: Schnelldurchlauf 3-5 min je Einstellung unter Stress, danach EIN
  Bestaetigungslauf 20-30 min ohne Stress fuer die gewonnene Einstellung.
  **Der Schnelldurchlauf misst relative Verbesserung unter Stress, nicht
  Alltagsqualitaet — das gehoert sichtbar in die Anzeige, nicht in eine
  Fussnote.**

### Zaehler-Inventar aus einem echten Dump — mehr als bisher genutzt

Sendeseite: `Counts (flushed/dropped/dropouts)`, `Counts (max dropped)`,
`LDAC saved transmit queue length`.
Quellseite: `Counts (underflow)`, `Bytes (underflow)`,
`PCM read counts (expected/actual)`.
Scheduling: `Enqueue/Dequeue deviation counts (overdue/premature)` samt
Zeitsummen — **direkt messbarer Jitter, fuer Tuning die interessanteste
Familie und bisher voellig ungenutzt.**

**Der Fund, der die Sprache aendert:** `Packet counts (expected/dropped)` stand
bei **1279910 / 0**, waehrend gleichzeitig 807 Warteschlangeneintraege verworfen
wurden. **Auf Paketebene geht nichts verloren.** Was das Projekt "Paketverlust"
nennt, ist ein Warteschlangenueberlauf: Die Funkstrecke wiederholt selbst, das
Problem ist Verzoegerung, nicht Verlust. Das erklaert auch 990 kbps — die
Strecke wird die Datenmenge nicht rechtzeitig los.

**BELEGSTAND, nicht ueberdehnen:** Am Quelltext belegt sind nur `dropped`,
`dropouts`, `underflow` (R-005). Die Bedeutung von `Packet counts`,
`max dropped` und den Deviation-Zaehlern ist **Director-Lesart, unbelegt** —
gehoert recherchiert, bevor ein Messapparat darauf gebaut wird.

### ZIELPRAEZISIERUNG des App Designers, 02.09. spaet — WICHTIG

**990 kbps ist kein Nachteil, den es zu vermeiden gilt. Es ist das Ziel.**
Der Nutzer will ausdruecklich die hoechste Qualitaet und Bitrate fahren. Die
leitende Frage des Vorhabens lautet damit nicht mehr "wie erkennen wir, dass
990 kippt", sondern **"was muss gegeben sein, damit 990 stabil laeuft".**

Folgen, die sofort gelten:

- **Stufe senken ist keine Loesung mehr, sondern das zu Vermeidende.** Auch der
  Wechsel auf AAC, aptX oder SBC gilt als Aufgabe der Qualitaet, nicht als
  Behebung. Ratschlaege, die in Wahrheit nur die Bitrate senken, sind als
  solche zu entlarven.
- **Hauptkategorie wird alles, was Sendezeit, Latenz und Scheduling im
  Sendepfad verbessert.** Messlage dazu: bei fest 660 blieben ueber 2,3 Mio
  Pakete verlustfrei, bei fest 990 entstehen Verluste ohne jeden externen Reiz.
  Die Grenze liegt vermutlich an verfuegbarer Luftzeit und Rechtzeitigkeit,
  nicht an "Funkqualitaet" im naiven Sinn — **ungeprueft, aber die tragende
  Arbeitshypothese**.
- Ein Faktor, der bei 660 folgenlos bleibt und bei 990 den Ausschlag gibt, ist
  fuer dieses Projekt der wichtigste ueberhaupt.
- R-009 und R-010 wurden waehrend des Laufs entsprechend nachgesteuert.

**Das beruehrt `GOAL.md` an der Wurzel** und ist beim Neuschreiben des
Zielbilds zu beruecksichtigen: Das Ziel ist nicht mehr nur "ehrlich anzeigen",
sondern "die hoechste Stufe nutzbar machen". Zwei Varianten stehen zur Wahl
(urteilende gegen zeigende App); beide muessen dieses dritte Standbein tragen.

### OFFEN: PII in drei Messberichten — blockiert deren Commit

Der `archivist` hat den Commit von `docs/perf/T-027-messung.md`,
`T-027-messung-24ghz.md` und `T-028-hoersitzung-reizplan.md` **verweigert**:
sie enthalten die reale SSID, die BSSID des Access Points und LAN-IPs des
Nutzers. Die Dateien liegen unveraendert im Working Tree. **Vor dem naechsten
sync-out zu bereinigen** — Platzhalter statt Klartext, Fundstellen stehen im
Archivist-Bericht. Die vier neuen Fixtures wurden gegengeprueft und sind sauber.

### T-030 geliefert — Buendelungskriterium steht in UI_SPEC.md

Neuer Abschnitt ab Zeile 1830, 14 neue Kriterien AK-T030-1..14, sieben
bestehende geaendert. **Noch nicht committet** (lief waehrend des sync-out).

**Die Groesse:** Ein Ausbruch liegt vor, wenn im zurueckliegenden
`LOSS_BURST_WINDOW_MS` (30 s) mindestens `LOSS_BURST_MIN_EPISODES` (3)
`dropouts`-Episoden gezaehlt wurden. Verworfen wurden: **Einzelabstand** (der
gemessene Median im Buendel, 2,70 s, liegt unter der Poll-Kadenz der App — ein
solches Kriterium misst den Poller, nicht die Strecke) und **Anteil gestoerter
Zeit** (Dauer je Episode ist mit den vorhandenen Zaehlern nicht messbar, der
Nenner waere erfunden).

**Zwei Zeilen, zwei Einheiten:** „Dropped audio: {N} incidents" traegt das
Verdikt; „Encoder ran dry: {N} times" steht gleichrangig daneben, nie rot, ohne
Verdikt und ohne verkleinerndes Wort. Neue Regel R-G: das Wort „packet" steht
nicht mehr fuer die Raeumungsfamilie. Neue Regel R-F: keine Groesse je Minute
oder Sekunde in der Verlustanzeige.

**Ehrlichkeit des Entwurfs, ausdruecklich festgehalten:** Die zweite Haelfte
meiner eigenen Auftragsbegruendung traegt NICHT. „Dieselben 11 Episoden
gleichmaessig verteilt waeren ein anderer Hoereindruck" ruhte auf dem
zurueckgenommenen Schluss, Einzelereignisse seien nicht bemerkt worden. Das
Kriterium spricht gleichmaessig Verteiltes deshalb **nicht** frei. Sein Gewinn
gegenueber der Rate ist, dass der **kurze dichte Ausbruch ueberhaupt erkannt**
wird — 11 Episoden in 21 s haette die alte 12/min-Schwelle verfehlt.

**Zwei bisher als `Measured` gefuehrte Werte sind falsifiziert** und
zurueckgezogen: `LOSS_ALERT_SUSTAINED_WINDOWS` = 2 (haette den kleinsten
gemeldeten Ausbruch verschluckt) und `LOSS_CLEAR_HOLD_MS` = 35 000 (Basis
ueberholt). Sieben Nachzuege an `ARCHITECTURE.md` sind gemeldet, darunter: der
Typ `LossThreshold.Measured(ratePerMin)` traegt die neuen Werte nicht — „3 in
30 s" ist keine Rate.

Neu als `Open`: obere Kante des Burst-Fensters `TODO(M-12)`, untere Kante der
Episodenzahl `TODO(M-13)` (ein Buendel aus genau 2 kam im Lauf nie vor),
Anteil gestoerter Zeit `TODO(M-14)` (kein Verfahren bekannt).

**RICHTIGSTELLUNG DES DIRECTORS zu einem Hinweis aus T-030:** Der Entwurf
nennt den Verlustfall aus `T-027-messung-24ghz.md` (660 kbps, WLAN-Konkurrenz
im 2,4-GHz-Band) als moeglichen ersten Hebel fuer Verlust bei gleicher, tieferer
Stufe. **Das traegt nicht.** Genau dieser Einzelfall wurde in T-028 ueber acht
gueltige Abschnitte — vier mit Reiz, vier ohne — **nicht reproduziert**, alle
0/0. Er gilt als nicht belastbar. Wer daran anknuepfen will, muesste zuerst die
Reproduzierbarkeit herstellen. Nicht als Hebel weiterverwenden.

### R-008 geliefert — EIN Stresshebel aus Bordmitteln gefunden

Volltext `docs/research/R-008.md`. **Noch nicht committet.**

**Der Hebel: die geoeffnete Seite "Neues Geraet koppeln".** Sie startet laut
AOSP eine klassische Inquiry (10 x 1,28 s) plus LE-Scan und **startet sie
automatisch neu, solange die Seite offen ist**. Entscheidend: Sie umgeht dabei
die Schutzlogik, mit der Android Discovery bei laufendem A2DP sonst
unterdrueckt — SettingsLib traegt dort den Kommentar "If we are playing music,
dont scan unless forced", und die Kopplungsseite nutzt diesen Pfad nicht. Die
Entwicklerdoku nennt Discovery als Vorgang, der die verfuegbare Bandbreite
bestehender Verbindungen erheblich reduziert.

**Erreichbar ueber die Oberflaeche, ohne Root, ohne Shell, ohne Fremdgeraet** —
genau was der Nutzer wollte. **Nur zeitlich dosierbar** (Seite offen gegen zu),
nicht in der Intensitaet. **Groessenordnung auf diesem Geraet unbelegt, muss
gemessen werden.**

Zweitbester: LE-Scan-Tastverhaeltnis ueber sechs `Settings.Global`-Schluessel,
10 bis 100 Prozent, stufenlos, Shell ohne Root — Wirkung aber unbelegt, und die
bisherigen Projektmessungen bei 10 bis 25 Prozent zeigten keinen Effekt.

**Fazit:** Ein im strengen Sinn dosierbarer Hebel aus reinen Einstellungen
existiert **nicht**; das zeitlich getaktete Inquiry-Verfahren ist der Ersatz.

**Zwei Nebenbefunde von Gewicht:**

- **Der Encoder-Thread laeuft SCHED_FIFO Prioritaet 1.** Damit ist die offene
  Frage aus R-007 geschlossen: gewoehnliche Rechenlast kann ihn nicht
  verdraengen, CPU-Last scheidet als Hebel endgueltig aus.
- **`MAX_PCM_FRAME_NUM_PER_TICK`** wuerde die konstante Raeumungsgroesse 25 aus
  T-029 erklaeren, war aber in den eingesehenen Headern nicht enthalten.
  **Kandidat fuer einen eigenen Quelltext-Auftrag** — der Beleg dafuer, warum
  eine Episode immer denselben Schwung mitnimmt.

**Naechster Messschritt, vom Lauf vorgeschlagen:** Kopplungsseite zu, dann zu
25, 50 und 100 Prozent der Zeit offen, je 3-5 min, fest 660, Zustandsbuch
komplett. Davor per Read-back pruefen, ob Discovery bei offener Seite wirklich
in Schleife laeuft.

### R-009 und R-010 SIND geliefert — Korrektur des Directors

Beide fable-Laeufe meldeten einen Abbruch am Nutzungslimit, und ich hatte
daraus geschlossen, sie haetten nicht mehr geschrieben. **Das war falsch.**
Der `archivist` hat es beim Sync bemerkt: `docs/research/R-009.md` (40 KB,
22:28) und `R-010.md` (44 KB, 22:31) existieren, sind vollstaendig und in sich
stimmig — beide Agenten hatten ihre Datei **vor** dem Abbruch gesichert. Der
Abbruch traf nur den Abschlussbericht an mich.

**Beide sind NICHT neu zu beauftragen.** Sie sind zu lesen und auszuwerten;
das steht als erstes auf der Liste der naechsten Sitzung.

**Der Nachtrag am Ende von `docs/tasks/T-031.md` behauptet ebenfalls faelschlich,
sie seien nicht geliefert.** Die dort festgehaltene Zielrichtung (990 ist das
Ziel) bleibt gueltig und wichtig — nur der Anlass stimmt nicht. Die Steuerung
wurde den Laeufen waehrend der Arbeit per Nachricht mitgegeben; ob sie sie noch
eingearbeitet haben, ist beim Lesen zu pruefen.

**Lehre, fuer die Retrospektive:** Ein gemeldeter Agentenabbruch heisst nicht,
dass nichts geschrieben wurde. Vor jeder Aussage "nicht geliefert" gehoert ein
Blick ins Dateisystem — genau das hat der `archivist` getan und ich nicht.
