# T-055d — X-4 Vergleichsoberfläche (developer), 29.09.2026

Stand: erledigt, mit Befunden. Branch `auto/2026-09-23-saeule3`, Basis `b7d4a5f`.
Commits `6943f05` (Sample-Rate-Ende), `fbeabd7` (Chip-Sperre), `d3caa96`
(Vergleichsoberfläche, Cancel = Stop), `94b15fe` (Engine `@Volatile`, Engine-Tests).
Suite `./gradlew clean test --no-build-cache` 00:01:48–00:03:18 auf `94b15fe`:
2690 Tests, 0 Fehler.

## An ui-ux-designer: Abweichungen und eigene Texte (ANNAHMEN)

- **`QUALITY_CHANGED` nennt nicht „codec left LDAC“.** UI_SPEC F-019 Punkt 3 legt
  `CODEC_CHANGED` und `QUALITY_CHANGED` auf die Zeile „…because the codec left LDAC…“.
  Bei `QUALITY_CHANGED` bleibt der Codec LDAC (etwa ABR), der Satz wäre also falsch
  (AK-3). Gebaut: „…because the LDAC quality changed…“. Dieselbe Formel gilt auch für
  Endgründe ohne Vorgabe: „…because the headphone disconnected / the sample rate
  changed / the rate stopped being readable / the link changed…“ (`endedSentence`).
- **„Cancel“/„Stop“ (`Cancelled`) ohne Endzeile.** Das bleibt wie bisher bei „Cancel“.
  Der Nutzer hat selbst beendet und braucht keinen Grund.
- **Satz bei verweigertem Dienst im Arm:** „Android did not let this comparison run in
  the background — keep this screen open until this arm ends.“ (nach dem DEEP-Satz aus T-055c).
- **Bluetooth-Tab-Sperrsatz:** „Locked while a comparison is running on the Monitoring
  tab — …“. „below“ aus F-019 stimmt dort nicht.
- **Sample-Rate-Endzeile:** Der Director-Satz ist wörtlich übernommen, dahinter steht
  wie bei allen `endLine`-Sätzen „{T} observed.“.

## An director

- `ARCHITECTURE.md:2495,2548,2590` nennt noch `StoppedOutside`. Der Code heißt jetzt
  `Cancelled` (Option A). Der Text gehört dem architect.
- Der Sperrzustand und `keptOffScreen` kommen als Default-Parameter aus
  `ActiveComparison.held` bzw. `MeasurementService.foreground`. So bleiben
  `MonitorScreen.kt` (T-058a) und `DeviceProfilesScreen.kt` unberührt. Nach dem Merge
  von T-058a kann `MonitorScreen` den schon gesammelten `measurementKeptOffScreen`
  explizit übergeben.
- `MonitorLiveStopTest.kt:130` (eine Zeile, Umbenennung) berührt eventuell die
  T-058a-Testdatei.
- `MonitorEngineTest` nutzte schon `backgroundScope` und bleibt unverändert. Den Hänger
  gab es nur in `IdleWakeupTest`/`SamplerParkingTest`. Beleg: Mit einem eingebauten
  Fehl-Assert hing die alte Fassung, bis `timeout 240` sie abbrach (Exit 124). Die
  neue scheitert in 3 s (Exit 1).
- `@Volatile` hat keinen Rot-vorher-Test (Speichersichtbarkeit): Lücke, kein Beleg.
- Ungeprüft (kein Gerät): Flackern des Ersatzsatzes zwischen Tipp „Start Arm B“ und
  `onStartCommand` (T-055c Befund 3).
