# T-051b — QA Erstpruefung Saeule 3 + T-046/T-050 am Quellstand

Stand `c2a29f5` (`auto/2026-09-23-saeule3`), eigener Worktree, kein Geraet.
Pruefmassstab: AK-11..16 (`docs/tasks/T-051.md`), AD-030..038, `UI_SPEC.md` S3-5/S3-6.
Proben (Unit/Robolectric, nur im Klon, danach entfernt): `QaT051bControllerTest`,
`QaT051bSectionTest`, `QaT051bRestoreTest`; Ausgaben im Scratchpad `T-051b/probe-output.txt`.

## Suite

`gradlew clean test --no-build-cache` am eingefrorenen Stand, 28.09.2026 22:15:18–22:17:28:
**2610 Tests, 0 Fehler, 0 uebersprungen** (JUnit-XML debug+release summiert), BUILD SUCCESSFUL.
Bestaetigt die developer-Angabe nach T-050 (2610/0).

## Befunde

### [P2 | Major | Hoch] F-015 Ein Vergleichsarm zaehlt nur bei sichtbarem Monitor; Displaysperre > 2 min startet ihn neu, und das sagt die App nirgends

**Adressat:** director (ob die Arme den Bildschirm wach halten duerfen), ui-ux-designer (Hinweis)
**Betroffen:** `MonitorViewModel.kt:191,204` (`WhileSubscribed`, F-012-Fix), `MonitorScreen.kt:57` (`collectAsStateWithLifecycle`), `ComparisonController.kt:159-164` (Neustart), `ComparisonSection.kt:82-85` (Einstieg), `:393-396` (`PROCESS_END`); Suche `keepScreenOn|KEEP_SCREEN_ON` in `app/src/main`: 0 Treffer.
**Umgebung:** Akku (Pflicht bei A5 „USB cable unplugged“), normale Displayzeit (< 15 min).

**Reproduktion:**
1. Vergleich starten, Arm A laeuft.
2. Display geht aus oder Tab wechselt, > 2 min keine Beruehrung.
3. Zurueck zum Monitor.

**Erwartet:** Der Nutzer weiss vor dem Start, dass der Monitor 2×15 min sichtbar bleiben muss (wie beim Beobachtungslauf, Dialog „This screen must stay open“, T-048 P6) — oder der Arm laeuft weiter.
**Tatsaechlich:** Nach dem Wiederkommen `READING_GAP`, der Arm beginnt bei 0 („Arm A ended before 15 minutes … Starting Arm A again.“; Bestandstest `ComparisonControllerTest` „arm B ending on a reading gap …“). Kein Satz im Einstieg oder Fortschritt nennt die Bedingung; der einzige Satz dazu (`PROCESS_END`, erst im Ergebnis) sagt „Leaving this screen or the app keeps it“ — gilt nur fuer den fertigen Arm A.

**Analyse:** Seit F-012/T-050 stoppt der Poll mit ON_STOP des Monitors (am Geraet belegt, T-050r). Displaysperre ist ON_STOP; das Geraeteverhalten bei Sperre ist aus diesem Mechanismus abgeleitet, am Geraet nicht nachgemessen. In der Geraetesession 23.09. verdeckte `stay_on_while_plugged_in=7` (USB) das; bei A5 faellt genau das weg.
**Auswirkung:** Auf Akku endet ein Arm nur, wenn der Nutzer das Display alle ≤ Displayzeit + 2 min weckt. Arm B von A5 ist ohne das praktisch nicht erreichbar.
**Vorschlag:** Entscheidung director: Bildschirm waehrend laufender Arme wach halten (Poll bleibt an den offenen Monitor gebunden, AK-4 unberuehrt) und/oder Starthinweis wie T-039; `PROCESS_END` auf „laufender Arm“ vs. „fertiger Arm A“ trennen.

---

### [P3 | Major | Niedrig] F-016 Ergebnis nennt Paarungsfakten des gerade verbundenen Kopfhoerers als „read on this pairing“

**Adressat:** developer
**Betroffen:** `ComparisonSection.kt:194` (`boundaryLines(snapshot, armA)`), `:236-251`; AK-15.

**Reproduktion (Robolectric, `QaT051bSectionTest` S3):**
1. Ergebnis fuer Kopfhoerer …37:8F (Arm-Link 990, MTU 883) steht auf dem Schirm.
2. Live-Snapshot zeigt einen anderen Kopfhoerer (…11:22, MTU 351, kein 3 Mbps).

**Erwartet:** Fakten der gemessenen Paarung oder „not readable“.
**Tatsaechlich:** „3 Mbps EDR: no — read on this pairing.“ und „Effective MTU: 351 — read on this pairing.“ unter dem Ergebnis von …37:8F.

**Analyse:** `boundaryLines` liest `snapshot` (aktueller Poll), nicht die Arme. Das Ergebnis bleibt im ViewModel, bis „New comparison“ getippt wird.
**Auswirkung:** AK-15 („nie von einem anderen Geraet uebernommen“) verletzt, sobald zwischen Messung und Ablesen das Geraet wechselt.
**Vorschlag:** Fakten am Armende festhalten (wie `ConditionBook`) oder nur zeigen, wenn Snapshot-Adresse = `armA.run.link.address`, sonst „not readable“.

---

### [P3 | Major | Mittel] F-017 Ergebnis sagt nicht, ob die Massnahme rueckgelesen wurde

**Adressat:** developer; Wortlaut ui-ux-designer; Spec-Konflikt → director
**Betroffen:** `Comparison.kt:46,83` (`verification` wird berechnet), `ComparisonSection.kt:179-199` (wird nicht gezeigt; Grep `\.verification` in `app/src/main`: 0 Treffer). `ARCHITECTURE.md:2118` (AD-035): „der Rahmen sagt, welches von beiden“; `UI_SPEC.md:3352-3372` (Messrahmen-Liste) enthaelt keine solche Zeile.

**Reproduktion (`QaT051bSectionTest` S4, Controller-Probe P6):** Massnahme „Clear line of sight“ (A4), Urteil `FEWER_DETECTED`, `verification = NOT_VERIFIABLE` → keine Zeile im Ergebnis nennt, dass die Umsetzung nicht pruefbar war.

**Erwartet:** Nach AD-035 steht „bestaetigt“ bzw. „nicht pruefbar“ am Ergebnis.
**Tatsaechlich:** Die Rueckleseworte erscheinen nur in `INSTRUCT`/`ARM_B`, im Ergebnis nicht.
**Auswirkung:** Bei A1, A4, A7 (immer `NOT_VERIFIABLE`) und A2 mit WLAN „on“ steht „Fewer … after than before“ ohne Hinweis, dass die Umsetzung nicht belegt ist (AK-13/AK-14).
**Vorschlag:** Eine Zeile im Messrahmen aus `result.verification` plus UI_SPEC nachziehen.

---

### [P3 | Minor | Mittel] F-018 Messrahmen „Both pinned to {Stufe}“ liest nur Arm A und sagt „pinned“ auch bei ABR

**Adressat:** developer
**Betroffen:** `ComparisonSection.kt:202-210`.

**Reproduktion (`QaT051bSectionTest` S1/S2):**
1. Arm A 990, Arm B nach Neustart ABR → `LinkDiffers` und daneben „Both pinned to 990 kbps, LDAC.“
2. Beide Arme ABR (Arm A ungepinnt neu gestartet) → „Both pinned to ABR, LDAC.“

**Erwartet:** Stufe je Arm; „pinned“ nur bei gepinnter Stufe.
**Tatsaechlich:** siehe oben.
**Analyse:** `frameLines` nimmt `armA.run.link` fuer beide Arme. Tritt auch ohne die bekannte Luecke auf, wenn die Arme in verschiedenen Sample-Rate-Familien liefen (Probe P3: `LinkDiffers`, Rahmen nennt 990).
**Auswirkung:** Der Rahmen widerspricht dem `LinkDiffers`-Satz direkt darueber (AK-14).
**Vorschlag:** Stufe/Codec je Arm aus dem eigenen `RunLink`; ADAPTIVE/NOT_PINNED als „not pinned (ABR)“.

## Bewertung der bekannten Luecke „ungepinnter Neustart eines Arms“ (nur Auswirkung)

Reproduziert mit `QaT051bControllerTest`:
- **Rueckweg oder Chip waehrend Arm A (P1):** Arm A startet bei ABR neu, Arm B ebenso → `Compared` (`NO_BASELINE_EVENTS`), Rahmen „Both pinned to ABR“. Ein Urteil entsteht unter einer Bedingung, die AD-031 ausschliesst.
- **Rueckweg waehrend Arm B (P2):** 15 min Arm B bei ABR, dann `LinkDiffers`.
- **Codec wechselt weg von LDAC (P5):** 20 Neustarts in 20 Lesungen (`RATE_UNREADABLE`), Schleife bis „Cancel“.
- **Ausloeser liegen auf demselben Schirm:** Der „Back to before“-Knopf steht laut Spec in jeder Armphase (`UI_SPEC.md:3395-3397`), die LDAC-Chips stehen darueber. Ein Reconnect heilt sich nur, wenn Autoapply das Profil wieder pinnt; nach einem Rueckweg ist Autoapply ausgesetzt.
- **Einstufung:** wie P2 (falsches Urteil moeglich, Ausloeser im Hauptpfad). Empfehlung an director: in den Fixauftrag aufnehmen; mindestens Vergleich beim Rueckweg beenden und Neustart nur bei unveraendertem `RunLink`.

## Fundstellen der Nutzerentscheide 28.09.

1. **F-014:** `SamplingPolicy.kt:59-64` (BURST, ACTIVE `:33`, BACKGROUND ohne `uiVisible`); Eingaben `MonitorEngine.kt:113-114`. Signal fuer „Monitor offen“: `MonitorScreen.kt:63-75` (ON_START/ON_STOP). DEEP (`:57`, 5-min-Fenster) laeuft nach Verlassen weiter — mitentscheiden. Tests: `SamplingPolicyTest.kt`, `SamplerParkingTest.kt`.
2. **Sample-Rate-Wechsel beendet den Lauf:** `ObservationRun.kt:317-321` (KDoc „it ends no run“), `:341-350` (`endAgainst` ohne Rate), `RunEnd` `:21-45` (neuer Grund); Satz `ObservationRunSection.kt:164-177`, mitbenutzt von `ComparisonSection.kt:309-311`. Ist-Zustand (Probe P3b): ein Lauf fuehrt 909 und 990 nebeneinander in `stepDwell`, ohne Ende.
3. **F-011:** `DeviceProfilesScreen.kt:316-318` und `BluetoothSystemControls.kt:105-106`. Projektweite Suche `rooted phone`/`root` (Code, Tests, Doku ausser Berichte/Archiv): keine weiteren Stellen dieses Satzes. `README.md:119` („unrooted phone“) ist ein anderer Zusammenhang.

## Offene Fragen (an director)

- **Profil-Codecwunsch ohne Rueckweg.** Im Profileditor gesetzter Codec/LDAC-Stufe wird vom Applier ohne Ledger-Eintrag geschrieben (`DeviceProfileApplier.kt:284-312`). Ist das der einzige Tuning-Zustand, ist das Banner verborgen und `restoreAll` tut nichts, Autoapply bleibt an (`SettingsRestore.kt:59`; Probe R1: `banner=Hidden`, `autoApply=true`, Wunsch 990 bleibt). Lesart A: AD-033 schliesst Codec bewusst aus. Lesart B: AK-11 „aus jedem Tuning-Zustand“. Wer entscheidet?
- **Unlesbares Ledger ist endgueltig.** Alle Schreibungen werden verweigert (`SettingsLedger.kt:127-141`), das Banner sagt „cannot be put back from here“ (`DeviceProfilesScreen.kt:1429`), einen Ausweg gibt es nur ueber das Loeschen der App-Daten. Gewollt (M3)?

## Beobachtungen

- Ein Wechsel der Update-Rate im Arm faellt erst nach beiden Armen als `CadenceDiffers` auf (Probe P4).
- `wifi_on` „2“/„3“ (Flugmodus) wird `Unreadable` statt on/off (`EnvironmentConditions.kt:62`); A2 ist dann nicht pruefbar.
- Nach dem Rueckweg bleibt ein von `LdacTuning.store` angelegtes Stub-Profil (ohne Wunsch, Autoapply aus) bestehen.

## Explorationsprotokoll

Gehalten: `recordIfAbsent` verweigert bei JSON-/IO-Fehler; Pin/Global/HD-Audio schreiben nicht ohne Vorwert; Lock nicht reentrant, aber Rueckweg ruft `LdacTuning.apply` statt `pin` (kein Deadlock); Rueckweg setzt Autoapply zuerst aus und loescht nur nach Read-back; `CONTRADICTED` startet Arm B nicht, `MeasureNotInEffect` greift auch nach einem Neustart von Arm B; Binomialtest n = 0 → `NO_BASELINE_EVENTS`, p = 1; Doppeltipp „Compare“ gesperrt; Cancel waehrend Pin verwirft die Antwort; T-046 Pause > 120 s → `PAUSED`, bei 120 s noch nicht; nicht lesbare Bedingungen landen in `notChecked`, MTU/3 Mbps zeigen „not readable“.

## Nicht getestet

Geraet (Auftrag): Displaysperre-Verhalten (F-015), Rueckweg HD-Audio→LDAC in Folge (Neuverhandlung, moeglich `NotObserved`), T-050 nur ueber T-050r und Bestandstests. Wortlaute → T-051a.

## Zusammenfassung (an director)

P1: 0 · P2: 1 (F-015) · P3: 3 (F-016, F-017, F-018) · P4: 0; dazu die bekannte Luecke mit Auswirkung wie P2.
**Gesamturteil: FAIL** — F-015 (P2) offen. Mindestens: F-015 entschieden und behoben, bekannte Luecke im Fixauftrag.

## QA-Log (Zeilen zur Uebernahme in `qa/findings.md`)

| ID | Titel | Prio | Sev / Likelihood | Adressat | Status | Zuletzt geprueft | Auftrag |
|---|---|---|---|---|---|---|---|
| F-015 | Vergleichsarm nur bei sichtbarem Monitor, Neustart nach > 2 min, nicht angesagt | P2 | Major / Hoch | director, ui-ux-designer | offen | 2026-09-28 | T-051b |
| F-016 | Ergebnis zeigt Paarungsfakten des aktuell verbundenen Geraets | P3 | Major / Niedrig | developer | offen | 2026-09-28 | T-051b |
| F-017 | Ergebnis nennt Rueckleseergebnis (verification) nicht | P3 | Major / Mittel | developer, ui-ux-designer | offen | 2026-09-28 | T-051b |
| F-018 | Messrahmen-Stufe nur aus Arm A, „pinned“ auch bei ABR | P3 | Minor / Mittel | developer | offen | 2026-09-28 | T-051b |
