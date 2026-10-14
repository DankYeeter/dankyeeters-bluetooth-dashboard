# T-055c — X-3 AD-039, F-014 (developer), 28.09.2026

Stand: erledigt, mit Befunden. Branch `t055c-measurement`, Basis `14fa3fc`.
Commits `eba88a8` (F-014), `937f320` (DEEP-Grenze V21), `758e19b` (AD-039).
Suite `./gradlew clean test --no-build-cache` 23:41: 2646 Tests, 0 Fehler.

## Befunde an director

1. **AK-T054-2 gegen AD-039/V16 (Vergleich).** UI_SPEC verlangt, dass „Stop“
   in der Benachrichtigung dieselbe Funktion ruft wie „Cancel“ in `ArmProgress`
   (`onReset`, keine Endzeile). AD-039 und V16 verlangen
   `ActiveComparison.end(StoppedOutside)`, also `ended = StoppedOutside` mit
   Endzeile. Gebaut nach AD-039/V16. Für DEEP ist die Identität gegeben
   (`engine.stopDeepCapture`). Entscheidung nötig: Cancel auch über
   `end(StoppedOutside)` (X-4), oder AK-T054-2 für den Vergleich lockern.
2. **`ActiveComparison.set`-KDoc** („Only ComparisonController“) widerspricht
   AD-039 Punkt 2 (`null` in `MonitorViewModel.onCleared`). Ich rufe `set(null)`
   aus `onCleared`; `ActiveComparison.kt` blieb unverändert (Auftrag). Der KDoc
   gehört angepasst.
3. **Für X-4:** `MeasurementService.foreground` ist in `INSTRUCT` `false`
   (Dienst endet mit Arm A, B1). Der Ersatzsatz für „Dienst verweigert“ darf
   deshalb nur in `ARM_A`/`ARM_B` gelten. Zwischen Tipp und `onStartCommand`
   ist `foreground` für Millisekunden `false`; ein Flackern des Ersatzsatzes
   ist möglich (ungeprüft, kein Gerät).
4. **Vorhandene Debt (Tests):** `IdleWakeupTest`, `SamplerParkingTest` und
   `MonitorEngineTest` rufen `engine.stop()` erst nach den Assertions. Scheitert
   eine, dreht die Engine-Schleife auf dem geteilten Test-Scheduler weiter und
   der Lauf hängt (hier 16 min bis zum Abbruch). Risiko: ein roter Test sieht
   aus wie ein Hänger. Aufwand klein (`backgroundScope` oder `try/finally`).
5. **Vorhandene Debt (Engine):** `deepCaptureUntilMs`/`burstUntilMs` in
   `MonitorEngine` sind nicht `@Volatile`, werden aber vom Main-Thread (Tipp,
   jetzt auch der Dienst) und der Schleife auf `Dispatchers.Default` benutzt.
   Risiko niedrig (eine verspätete Sicht um eine Runde), Aufwand klein.

## An ui-ux-designer: Abweichungen und eigene Texte (ANNAHMEN)

- **Benachrichtigungstext ohne `{T}`:** AD-039 verbietet ein Neuposten außer bei
  Phasenwechsel; die Zeit zeichnet der System-Chronometer neben dem Titel
  (Arm: vorwärts ab Phasenbeginn, DEEP: rückwärts bis Ende). Text daher:
  `"Arm A: counting 15 min. Ends on its own, or tap Stop."`,
  `"Up to 5 min. Ends on its own, or tap Stop."`; laufen beide (B3):
  `"Arm A: counting 15 min, and watching live. Ends on its own, or tap Stop to end both."`
  `PIN_AND_CHECK` zeigt bereits „Arm A“.
- **Kanal:** Name `"Measurement running"`, Beschreibung „Shown while a
  comparison arm or a live capture you started keeps measuring off the screen.
  It ends with the measurement.“ — AD-039 kündigt die Texte von T-054b an,
  UI_SPEC enthält sie nicht.
- **DEEP-Ersatzsatz bei verweigertem Dienst** (AD-039 Punkt 5), ebenfalls nicht
  spezifiziert: `"Android did not let this capture run in the background — keep this screen open until it ends."`
- `"{T} of 5 min captured."` zählt vom Start bis zur neuesten Messprobe, nicht
  nach Wanduhr; die Zeile bewegt sich also im 10-s-Takt der Proben.

## Nicht geprüft

Gerät (AD-039 Geräteprobe: Arm A 15 min, Display aus, Akku; Stop vom
Sperrbildschirm; Verweigerung des Diensttyps ab A17); gemergtes Manifest.
