# T-040b — Befunde (developer, 22.09.2026)

## Kern der sechs gelöschten Geräte-Probes (`core-audio/src/androidTest/.../eq/`)

Alle Befunde stehen ausführlicher in `HANDOVER.md` (Abschnitte ab Zeile ~2990)
und in den Kommentaren von `OutputMixReachGate.kt` / `EqController.kt`.

- **AudibleEqDemoTest** — Hördemo, kein Test. Über Lautsprecher gemessen: 18 dB
  Absenkung ergibt 14 dB am Mikrofon. Über Bluetooth sah der EQ strukturell aktiv
  aus, klang aber gleich; daher erst Pegel (−15 dB alle Bänder), dann Klangfarbe.
- **ForeignSessionAttachProbeTest** — Tidal sendet kein
  `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` (am Gerät gemessen). Frage: wirkt ein
  EQ auf eine fremde Session-ID, die der Helfer aus `dumpsys audio` liest? Anhängen
  braucht keine Berechtigung, nur die ID.
- **LeakageSnrProbeTest** — Handy neben geschlossenem ANC-Kopfhörer maß −39 dB bei
  500 Hz und +13 dB bei 8 kHz für einen 6-dB-Schritt: Mikrofon las sein eigenes
  Rauschen. Faustregel: unter ~10 dB Abstand Ton/Stille ist so nichts messbar.
- **SessionIdProbingFeasibilityTest** — Idee: Session-IDs sind ein Zähler,
  `generateAudioSessionId()` verrät den Stand; fremde IDs knapp darunter per
  Durchprobieren finden (ohne Helfer). Ergebnis steht nicht im Code.
- **SessionIdVisibilityProbe** — Frage: liefert
  `getActivePlaybackConfigurations` einer normalen App die fremde Session-ID?
  Sonst bleibt nur der Helfer über `dumpsys audio`. Ergebnis steht nicht im Code.
- **SpatializerRoutingProbeTest** — Auf dem Pixel läuft A2DP über einen
  SPATIALIZER-Thread; ein Output-Mix-Effekt (Session 0) am normalen Mixer erreicht
  ihn nicht, ein sessiongebundener schon (via `dumpsys media.audio_flinger`).

## Nicht gehaltene oder nicht umgesetzte Prämissen

- `AncMode`: wird in `AudiogramStore`, `CompensationProfileStore` und
  `BackupMapper` per `valueOf` persistiert/gelesen → Datenformat, nicht entfernt.
- `EXTRAPOLATED_INDICES` liegt in `core-audio/src/main` (weder a noch b), nicht
  angefasst; einziger Nutzer ist `NalRCompensationCalculatorTest`.
- `activeMediaSessions` (core-system), `LARGE_MIN`, `isMeasuredBitrate`,
  `preferencePresetId` (app) liegen in T-040a-Dateien.
- `ProcessResolver`/`ConnectionTicks`: Parametertyp auf die Implementierung
  umgestellt; `MonitorGraph.kt` (gesperrt) bleibt unverändert und kompiliert.

## Außerhalb des Auftrags gefunden

- `./gradlew assembleDebugAndroidTest` schlägt **auf master** fehl:
  `app/src/androidTest/.../privileged/adb/AdbReachabilityTest.kt:35,38,58` ruft
  `AdbPortDiscovery.find`, das es nur noch als `findAll` gibt. T-040b ändert nichts
  unter `app/`. Die androidTest-Quellen von core-audio, core-hearing, core-monitor
  und core-system kompilieren.
- Klon-Volllauf nicht möglich: Klonen ins Scratchpad bricht mit „Filename too long“
  ab (Windows-Pfadlänge). Die Messung lief im Worktree (dort schreibt keine
  andere Rolle), mit `clean test --no-build-cache`.
