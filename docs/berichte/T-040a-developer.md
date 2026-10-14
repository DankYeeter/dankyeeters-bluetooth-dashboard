# T-040a — Befunde (developer, 22.09.2026)

Stand: Worktree-Branch, HEAD nach dem letzten Code-Commit `a40c1c0`.

## 1. Prämissen, die nicht gehalten haben

- **Doppelte AirPods→Preset-Logik (`DashboardViewModel` init):** nicht doppelt.
  `BluetoothDashboardViewModel:97` schlägt über den *Gerätenamen* vor
  (`PresetMatching.presetIdFor(active.name)`), `DashboardViewModel` über das
  *Modell im BLE-Beacon* (`beacon.model.calibrationPresetId`). Umbenannte
  AirPods trifft nur der Beacon-Weg; die KDoc von `DetectedDeviceRepository`
  nennt ihn ausdrücklich als Quelle. Entfernen wäre Verhaltensänderung →
  stehen gelassen. Entscheidung Director/App Designer, falls gewünscht.
- **„~26 handgebaute Memoisierungen in SystemGraph“:** dort waren es 12 (alle
  umgestellt). Die übrigen Stellen desselben Musters (`get() = synchronized(lock)`)
  liegen in `HearingGraph.kt` (2, T-040b-Dateien) und `MonitorGraph.kt` (10,
  aus T-040b ausgenommen). Suchmuster: `grep -rn "get() = synchronized(lock)" --include=*.kt`.

## 2. androidTest von `:app` kompiliert schon auf master nicht

`./gradlew assembleDebugAndroidTest` scheitert in
`app/src/androidTest/.../privileged/adb/AdbReachabilityTest.kt:35,38,58`:
`AdbPortDiscovery.find(...)` gibt es nicht mehr (nur `findAll`). Beleg:
`git show master:app/src/main/.../adb/AdbPortDiscovery.kt | grep "fun find"` →
nur `findAll`; `git diff master --stat -- app/src/androidTest <AdbPortDiscovery.kt>`
→ leer, T-040a hat beide Dateien nicht berührt. Alle anderen Module bauen ihr
androidTest-APK (`--continue`-Lauf: einzige Fehlermeldung ist diese Datei).
Nicht behoben (Nebenfund). Auftrag nötig: developer, `AdbReachabilityTest`
auf `findAll(...)` umstellen.

## 3. Verweise auf die archivierten Dokumente außerhalb von T-040a

Nach dem Umzug nach `docs/archiv/` zeigen noch 33 Zeilen in 17 Dateien auf den
alten Ort. Alle liegen in T-040b-Dateien (Suche:
`grep -rnIE "PLAN\.md|PLAN's|PLAN/|COMPENSATION\.md|REPORT-2026-08-26|HANDOVER" core-hearing core-monitor --exclude-dir=build`):

- core-hearing main: `Audiogram.kt`, `ClinicalAudiogram.kt`, `Compensation.kt`,
  `fit/FitCheck.kt`, `HearingDrift.kt`, `MedianAudiogramAggregator.kt`, `NalR.kt`,
  `NalRCompensationCalculator.kt`
- core-hearing test: `HearingDriftTest.kt`, `NalRCompensationCalculatorTest.kt`, `NalRTest.kt`
- core-monitor main: `diagnostic/DeviceDiagnostic.kt`, `link/BluetoothBroadcastSource.kt`,
  `link/MonitorEvent.kt`, `link/QualityReportSource.kt`,
  `sampling/LinkSampleCollector.kt`, `sampling/SamplingPolicy.kt`

Dazu `ARCHITECTURE.md:60,70` (T-040c). Die Verweise der acht Archivdokumente
untereinander sind unverändert; sie liegen weiter nebeneinander und stimmen.

## 4. Klon-Volllauf nicht möglich

Der Worktree-Isolationshook verweigert `git clone` in den Scratchpad. Der
Volllauf lief deshalb im Worktree selbst (sauberer Baum, HEAD `a40c1c0`),
Ausgabe im Scratchpad unter `T-040a/klon.txt`.

## 5. Nebenfund (nicht behoben)

`SystemGraph.kt` trägt eine verwaiste KDoc („Tells the harvester the helper is
available now …“) direkt vor der KDoc von `activateHelper`; sie gehört zu
`onPrivilegedHelperConnected()`. Kosmetisch, kein Risiko.
