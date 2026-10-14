# T-043a — QA-Bericht (qa-engineer)

**Stand:** `auto/2026-09-23` @ `afacd89`, gemessen in eigenem Worktree `C:\t043`
(detached, `local.properties` kopiert). Hauptbaum unverändert bis auf diese Datei.
**Urteil: CONCERNS** — kein Befund P1/P2, AK-5 belegt, AK-17 funktional erfüllt;
über zwei neue P3- und zwei neue P4-Befunde am Beobachtungslauf sowie die
eingegrenzte Ursache von F-006 (P3, nur Tests) entscheidet der `director`.

## Zahlen (alle nach dem letzten Stand gezählt)

| Messung | Ergebnis | Quittung |
|---|---|---|
| Volle Suite, `clean test --no-build-cache --continue` | **2493 Tests, 0 Failures, 0 Errors, 0 Skipped** | JUnit-XML aller Module gezählt, 23.09. 00:40:47–00:41:51, Klon @ `afacd89`; ein Volllauf |
| Mutationen T-039 (Rechenkern, Section, Controller) | 17 gefahren: **14 rot, 3 überlebt** (M06, M12, M13) | je Mutation `ObservationRunTest` + `ObservationRunSectionTest` + `ObservationRunControllerTest`, 23 Tests |
| Mutationen Migration 3→4 | 2 gefahren, **2 rot** (Testtask Exit 1) | `MonitorDatabaseMigrationTest` |
| Mutationen QA-012/QA-013 | 3 gefahren, **3 rot**, je genau der zuständige Test | `AcceptanceCriteriaGrepTest`, `LiveLinkPanelScreenTest` |
| JSON, alt geschrieben → neu gelesen | **0 von 6000** Fällen abweichend (3000 Profile, 3000 Kalibrierungen, Zeitstempel ≤ 2^53) | Differenztest gegen `MiniJson` aus `5f62b15`, Robolectric |
| JSON, Fremdeingaben | **13 von 26** Konstrukten lesen verschieden | ebd. |
| Icons | **10 Icons, 12 Pfade**: Pfadknoten, Füllung, Deckkräfte, Fülltyp, Kontur-Pinsel, Rahmen, autoMirror, Name gleich | Vergleich gegen `material-icons-extended` 1.7.6 aus dem Gradle-Cache, nur im Klon |

## 1. T-039 / AK-17 — Urteil je Kriterium

„Prüft“ = der Test fällt, wenn das Kriterium verletzt wird (Mutation belegt).
„Prüft teilweise“ = eine realistische Verletzung bleibt grün.

| AK | Urteil | Test prüft das Kriterium? | Beleg |
|---|---|---|---|
| T039-1 | erfüllt | prüft | M17 (`startedAfter(null)`) rot |
| T039-2 | erfüllt | **prüft teilweise**: der Filter sucht Knoten über die Beschriftung („Lowest reading“, „of the time“, „Time at each step“). Ein Minimum ohne Fenster und ohne diese Wörter besteht ihn | M01, M02 rot; M03 („Lowest 492 kbps.“) lässt den AK-2-Test grün, rot werden nur zwei andere Tests über exakte Strings |
| T039-3 | erfüllt | prüft (Kern und Oberfläche) | M04 (Lücke zählt in `{T}`) 3 rot; M05 (Lückenzeile weg) rot |
| T039-4 | erfüllt | Test vorhanden, nicht mutiert | — |
| T039-5 | erfüllt | prüft, Grep mit positivem Selbsttest | `AcceptanceCriteriaGrepTest` |
| T039-6 | erfüllt | prüft, Grep + Compose über fünf Zustände | ebd., `ObservationRunSectionTest` |
| T039-7 | **teilweise** | prüft nur den laufenden Lauf und nur die 96er-Leiter | Befund A |
| T039-8 | erfüllt | prüft (die Folgen aus der Spec) | — |
| T039-9 | **teilweise** | sechs Auslöser einzeln: M09, M10, M11, M15, M16 rot. **M12 überlebt** (gepinnt → gepinnt) | Befunde A, C, D |
| T039-10 | erfüllt | prüft; Controller bewusst ausgenommen (Flag laut Entscheidung 5) | Grep mit Selbsttest |
| T039-11 | erfüllt, durch Konstruktion | **prüft nur, dass der Code tut, was er tut**: der Compose-Test gibt einen festen Lauf hinein, eine Speisung aus der Nahaufnahme könnte ihn nicht ändern. Der Grep scannt die Verdrahtung in `MonitorViewModel.kt:188-191` nicht; es schützen die Typen (`TxProbeSample` ≠ `LinkLiveSnapshot`) | Beobachtung |
| T039-12 | erfüllt | prüft (Textknoten gezählt) | — |
| T039-13 | erfüllt | prüft (45/89/91 s, 24/72 min) | — |
| T039-14 | erfüllt | KDoc gelesen: `ObservationRun.kt:63-73` | — |
| T039-15 | erfüllt | Test vorhanden (`MonitorTraceModelTest`), nicht mutiert | — |
| T039-16 | erfüllt | prüft (Anteil exakt, Verweildauer < 5 s) | — |
| T039-17 | erfüllt im Code | **prüft teilweise** | M07 rot; **M06 überlebt zweimal**, M08 lässt den eigenen Test grün → Befund D |

## 2. Regression der Bereinigung

- **JSON:** Vom alten Schreiber erzeugte Daten liest `org.json` in 6000 Zufallsfällen gleich wie `MiniJson`: Unicode, Emoji, Steuerzeichen, einzelne Surrogate, -0.0, subnormal, ±MAX, NaN/∞ (vom Schreiber als 0.0 abgelegt), 5000 Zeichen. Einzige Abweichung im Lauf mit Zeitstempeln > 2^53: `MiniJson` las Longs als Double und rundete, `org.json` liest sie exakt, also eine Verbesserung. Fremdeingaben, die die App nie schreibt: `010` wird zu **8** (Oktal, alt 10), `0x10` zu 16 (alt: Liste verworfen), ein führendes NBSP liefert **leere Liste** (alt gelesen); Kommentare, `;`, `=`, einfache Anführungszeichen, Zeichen nach dem Array nimmt `org.json` an. Einziger Schreiber der beiden Schlüssel ist `encode` (`PreferenceProfileStore.kt:61,69`, `AudiogramStore.kt:147,155`), kein Importpfad → kein Befund, Ergänzung zu F-005.
- **Migration 3→4:** erhält den Verlauf. Der Test öffnet eine v3-Datei mit dem echten Identity-Hash (`bed0e2322a9dd041edcc8795c929ef08` = `schemas/3.json`) über den Produktions-Builder und vergleicht alle drei Verlaufstabellen zeilengenau. Er fällt sowohl bei zusätzlichem `DELETE FROM link_samples` als auch ohne das `DROP` der Kalibrierung.
- **Icons:** Die Gleichheit war nur behauptet, der Vergleichstest ist nicht eingecheckt (`4a50bc8`). Jetzt ist sie belegt, siehe Tabelle. Abweichend sind nur die Konturparameter (Breite 1→0, Join Bevel→Miter, Miter 1→4). Es gibt keinen Konturpinsel (`stroke = null` auf beiden Seiten), das sieht man also nicht. Der Vergleich schlägt an; er hat diese Abweichung gefunden.
- **`by lazy`:** Am Code gelesen, nicht gemessen. Jede Instanz entsteht weiter erst beim ersten Zugriff, mit denselben Nebenwirkungen (`connect()`, Paket-Invalidierung, Screen-Receiver). Neu ist je Feld eine eigene Sperre statt einer globalen. Einen Zyklus zwischen den Lazies gibt es nicht (`engine`→`repository`/`codecSource`→`dumpsysSource`/`screenOn`; `liveLink` ruft `repository` erst beim Capture). Frühere Arbeit sehe ich nicht, auch keine Deadlock-Gefahr (AK-4).

## 3. F-006 — Ursache (kein Fix)

**Ursache: geteilter Prozesszustand zwischen Robolectric-Tests, kein Produktfehler.**
`SystemGraph` ist ein `object`, und `settingsStore` wird per `by lazy` erzeugt
(`core-system/.../SystemGraph.kt:68`). Der DataStore-Delegate
(`EqSettingsStore.kt:20`) ist prozessweit einmalig. Beide überleben die Testgrenze
in derselben JVM. Die zwei Tests erwarten eine Neuinstallation
(`EqScreenVolumeTiltTest.kt:57-68`, `VolumeAwareTiltViewModelTest.kt:106-117`).
Vorausgehende Tests lassen `volumeAwareTilt = true` im Store liegen
(`VolumeAwareTiltViewModelTest.kt:66`, `EqLayerBypassTest.kt:152`). Geschrieben
wird asynchron und nie abgewartet (`EqViewModel.kt:888`), und kein Test räumt
seine ViewModels ab.
**Beleg:** Probe-Klasse im Klon, `@FixMethodOrder`. Test a speichert
`tilt=true`, Test b (frische Application) sieht dieselbe Store-Instanz
(`identityHashCode` gleich), liest `tilt=true` und fällt mit der Meldung von
F-006.
**Hypothese, nicht reproduziert:** Welches Timing am 23.09. um 00:30 den Wert
`true` stehen liess. Drei Läufe des Pakets `ui.screens.eq` (7 Klassen, alphabetisch)
waren grün.

## 4. Offene Punkte aus T-039b, gegen die Spec

- **„Start“ nach beendetem Lauf bei unlesbarer Rate:** reproduziert → Befund B.
- **Lückenzeile während einer laufenden Pause:** Das entspricht der Spec. Eine Lücke ist die Spanne zwischen zwei Lesungen (Entscheidung 2); solange die Pause läuft, fehlt ihr Ende. Falsch ist dabei nichts, weil `{T}` stehen bleibt. Folge: Ein Stopp während der Pause zeigt die Schlusspause nie. Ein sichtbares „zählt gerade nicht“ wäre ein neuer Wunsch und ist kein Befund.

## Befunde

### A — [P3 | Minor | Mittel] Beendeter Lauf wechselt Schwellenleiter und Anteilszeile mit der aktuellen Verbindung
**Adressat:** developer (Wortlaut der Familie: ui-ux-designer)
**Betroffen:** `ObservationRunSection.kt:49,99,116` (`snapshot.codec?.sampleRateHz` statt der Rate des Laufs)
**Reproduktion:** 150 s Lauf bei LDAC 96 kHz/660 → nächste Lesung AAC 44,1 kHz → `RUN_ENDED` mit dem AAC-Snapshot rendern.
**Erwartet:** „Grundzeile + dieselben drei Blöcke, unverändert“ (Tabelle der Zustände, AK-T039-9); Schwellen-Chips aus der Familie, über die der Lauf lief.
**Tatsächlich:** „At or above 606 kbps 100 % of the time“, Chips 909/606/303 an einem Lauf, der auf der 990/660/330-Leiter gemessen wurde.
**Auswirkung:** Die Anzeige nennt als Schwelle eine Stufe, die man für diesen Lauf nicht pinnen kann. Tritt bei jedem Codec-Wechsel mit anderer Rate auf; bei Trennung nur, wenn der Dump keinen Codec mehr nennt und der Lauf auf 44,1/88,2 kHz lief (`nominalKbps(null)` fällt auf die 48/96-Leiter).
**Vorschlag:** Die Sample-Rate beim Start im Lauf festhalten (wie `RunLink`) und die Schwelle daraus rechnen.

### B — [P3 | Minor | Mittel] Neustart bei unlesbarer Rate endet sofort mit falscher Grundzeile
**Adressat:** developer; Vorrang der Zustände `RUN_ENDED`/`RUN_UNAVAILABLE`: ui-ux-designer
**Betroffen:** `ObservationRunSection.kt:69-70`, `ObservationRun.kt:281`
**Reproduktion:** Lauf endet mit `CODEC_CHANGED` (LDAC→AAC) → Chip „Start“ → nächste Lesung AAC, Wiedergabe läuft.
**Tatsächlich:** „Run ended when the rate stopped being readable. 0 s observed.“ In diesem Lauf war die Rate nie lesbar.
**Analyse:** Beide Zustandsbedingungen gelten gleichzeitig, und die Spec legt keinen Vorrang fest. `RUN_ENDED` muss vorgehen, sonst wäre die Grundzeile `RATE_UNREADABLE` nie sichtbar. Dass dort der Chip bei unlesbarer Rate erscheint, widerspricht aber dem Sinn von Zeile 1 der Tabelle („Chip: keiner“).
**Auswirkung:** Die Anzeige behauptet etwas Falsches (AK-3). Die Zahlen des vorigen Laufs sind durch den Tap verloren.
**Vorschlag:** Den Start-Chip in `RUN_ENDED` nur bei `hasReadableRate` zeigen, oder die Zeile für einen Lauf ohne jede Lesung eigens formulieren.

### C — [P4 | Minor | Niedrig] Leerer Snapshot blendet den Laufabschnitt samt beendetem Lauf aus
**Adressat:** developer
**Betroffen:** `LiveLinkPanel.kt:112-116` gegen `:132`
**Reproduktion:** `LiveLinkPanel(snapshot = LinkLiveSnapshot(ts, warnings = ["no shell identity …"]), observationRun = beendeter Lauf)` rendern.
**Tatsächlich:** Nur „Nothing on the link could be read.“, keine Grundzeile, keine Zahlen. Sie kehren zurück, sobald wieder gelesen wird.
**Erwartet:** „`RUN_ENDED` behält seine Zahlen sichtbar, bis ein neuer Lauf gestartet oder der Bildschirm verlassen wird.“
**Vorschlag:** Den Abschnitt ausserhalb des `isEmpty`-Zweigs führen, wenn `run != null`.

### D — [P4 | Minor | Niedrig] Tests binden zwei Hälften tragender Kriterien nicht (systemisch: Testlücke)
**Adressat:** developer
**Belege:**
- **M06**: `onNoticeDismiss` setzt das Flag asynchron (`scope.launch { store.set…(true) }`). Zweimal grün: `ObservationRunControllerTest.kt:63-64` liest, bevor geschrieben ist. Die Hälfte „Not now lässt das Flag ungesetzt“ ist damit nicht gebunden.
- **M08**: Das Flag wird aus einem Prozess-`companion` gelesen, nicht aus dem Store. Der AK-17-Test bleibt grün, weil das „frische ViewModel“ im selben Prozess läuft. Rot wurde nur der andere Test, und nur wegen der Methodenreihenfolge.
- **M12**: `ldac?.mode != link.mode` entfernt. Grün, weil `ObservationRunTest.kt:115` nur ABR→gepinnt prüft. Der Wechsel HIGH→MID endet den Lauf nicht mehr, und kein Test merkt es.
**Vorschlag:** Nach „Not now“ die Store-Schreibvorgänge abwarten, etwa mit einem Store-Fake, der Schreibvorgänge zählt. Den Neustart über einen frisch gebauten DataStore auf derselben Datei prüfen. Einen Fall gepinnt→gepinnt ergänzen.

### F-006 — Statusvorschlag: Ursache eingegrenzt (siehe Abschnitt 3), Adressat developer (Testisolation)

## Beobachtungen (kein Befund)
- Die Zeitstempel sind Wanduhrzeit (`LiveLinkSource.kt:60`). Springt die Uhr um mehr als 2 min vor, endet der Lauf als `READING_GAP`; springt sie zurück, zählt er still nicht weiter.
- Die Grenze von `READING_GAP` bei genau 120 s ist nicht gebunden (M13).
- Der Icon-Vergleich existiert nur als Commit-Aussage und nicht als Test.

## Offene Fragen
- **An ui-ux-designer (läuft in T-043c):** Folgen der umgesetzten Lesart von `RUN_GAP_MAX`, gemessen. Ist der Helfer 30 min weg (Snapshots ohne Gerät), läuft der Lauf weiter, ohne zu enden, und bucht beim Wiederkehren eine Pause von 30 min. Eine Wiedergabepause von 60 min endet nie. Nach der wörtlichen Tabellenzeile („Lücke länger als `RUN_GAP_MAX_MS` → Lauf endet“) müssten beide enden.
- **An ui-ux-designer:** Ein Wechsel der Sample-Rate mitten im Lauf (LDAC ABR bleibt) beendet ihn nicht, weil `RunLink` keine Rate trägt. Die Stufen beider Leitern landen dann in einem Lauf. Ist das gewollt?

## Stichprobe bestehende Features (ohne Gerät)
Gewählt nach dem höchsten Risiko aus diesem Zyklus: 1. Sichtbarkeit, degradierter Pfad „Helfer weg“ (AK-3). 2. Kontrolle, die Zeile „LDAC quality“ (gespeichert oder in Kraft), deren Helferprotokoll T-041e umgebaut hat. 3. Das Personalisieren von Optimieren, dessen Speicher T-041b umgestellt hat. Den geführten Prozess AK-12..16 gibt es im Code noch nicht, grep „Ausweichen“ ohne Treffer.
1. Das Panel sagt ehrlich „Nothing on the link could be read.“ und „1 value could not be read“. Befund C betrifft nur den Laufabschnitt.
2. `LdacTuning.kt:305-327` trennt „stored“ und „read back“. Ein fehlender Helfer wird als nicht gesetzt gemeldet, nicht als Erfolg. Nur am Code gelesen.
3. Gespeicherte Profile und Kalibrierungen bleiben lesbar, siehe JSON-Differenztest.

## Nur mit Gerät prüfbar (für die Gerätesession 23.09. abends)
1. Lauf mit echten ABR-Lesungen über mindestens 2 min, alle drei Kennzahlen mit Fenster, Kadenzwechsel 1/5 s.
2. Pause und Weiterspielen (`mIsPlaying`); Stopp während der Pause.
3. Bluetooth aus, Kopfhörer aus, Helfer beendet: Lauf endet oder läuft weiter? Enthält der Dump dann einen Geräteblock mit `isConnected=false` oder keinen?
4. Codec-Wechsel LDAC→AAC während des Laufs, danach „Start“ (Befunde A und B).
5. Wechsel HIGH→MID bei gepinntem Lauf (M12); Wechsel der Sample-Rate mitten im Lauf.
6. Starthinweis: Erster Tap, dann „Not now“, dann App beenden (Prozess töten), neu starten, Tap; das Gleiche mit „Continue“.
7. Chipgrösse ≥ 48 dp.
8. Nach dem Update: `monitor.db` v3→v4 auf dem Gerät, Verlauf noch da. Gespeicherte Präferenzprofile lesbar.
9. Icons optisch in der Navigationsleiste und in den Hilfeknöpfen.
10. AK-4: ohne offenen Monitor keine periodische Arbeit (`dumpsys jobscheduler`/`alarm`, CPU im Hintergrund).

## Nicht getestet
Kompilierung von androidTest und Release-Build, weil nicht beauftragt. AK-11 (Rückweg) für LDAC liegt ausserhalb dieses Zyklus. Helferprotokoll und Token (T-043b). Der zweite Volllauf entfiel, weil ich keinen Fix gemacht habe.

## QA-Log (Vorschlag für `qa/findings.md`, IDs vergibt der Director)

| ID | Titel | Prio | Prüfer | Adressat | Status | Datum | Auftrag |
|---|---|---|---|---|---|---|---|
| neu-A | Beendeter Lauf wechselt Schwellenleiter/Anteil mit aktueller Sample-Rate (`ObservationRunSection.kt:49,99`) | P3 | qa-engineer | developer | offen | 2026-09-23 | T-043a |
| neu-B | Neustart bei unlesbarer Rate: sofortiges Ende, Grundzeile „stopped being readable“ falsch | P3 | qa-engineer | developer, ui-ux-designer | offen | 2026-09-23 | T-043a |
| neu-C | Leerer Snapshot blendet beendeten Lauf aus (`LiveLinkPanel.kt:112`) | P4 | qa-engineer | developer | offen | 2026-09-23 | T-043a |
| neu-D | Testlücken AK-T039-17 (M06, M08) und AK-T039-9 gepinnt→gepinnt (M12) | P4 | qa-engineer | developer | offen | 2026-09-23 | T-043a |
| F-006 | Flaky EQ-Tests: geteilter `SystemGraph`/DataStore über Testgrenzen, asynchrones `save` | P3 | qa-engineer | developer | Ursache eingegrenzt, offen | 2026-09-23 | T-043a |
| QA-012 | AK-T009-31-Guard | P3 | qa-engineer | – | **behoben, QA-bestätigt** (2 Mutationen rot) | 2026-09-23 | T-043a |
| QA-013 | AK-T009-29-Sweep | P3 | qa-engineer | – | **behoben, QA-bestätigt** (Mutation rot) | 2026-09-23 | T-043a |

Klon `C:\t043` mit den Probe-Tests (`QaT043ProbeTest`, `QaJsonDiffTest`,
`QaOld*`, `QaIconEqualityTest`, `testImplementation` für die Icon-Bibliothek)
und das Mutationsskript `scratchpad/T-043a/mut.py` bleiben zur Nachprüfung
liegen. Entfernen: `git worktree remove --force C:\t043`.
