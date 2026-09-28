# T-055a — X-1 AD-040 (developer), 28.09.2026

Stand: teilweise. Commit `e9b9546` auf `auto/2026-09-23-saeule3`
(Basis `7a3c341`). Teile 1–3 gebaut, Teil 4 blockiert.

## Blocker an director: Teil 4 (`SAMPLE_RATE_CHANGED`) bricht den Build einer fremden Datei

Ein neuer `RunEnd`-Wert macht das erschöpfende `when (end)` in
`ObservationRunSection.endLine` (`app/.../ui/screens/monitor/ObservationRunSection.kt:164-177`)
unkompilierbar. Die Datei gehört laut AD-043 zu X-4 (T-055d), und X-4 führt
dort auch den „Sample-Rate-Satz“. Dazu verlangt `ObservationRunSectionTest:247-248`
für jeden `RunEnd` einen eigenen Satz. UI_SPEC T-054b („Ausdrücklich nicht Teil“)
sagt, die Grundzeilen-Tabelle decke einen neuen Grund „bereits ab“. Das stimmt
nicht: Für den neuen Grund gibt es keinen Satz.

Optionen:
1. **Empfehlung:** Teil 4 geht ganz an X-4: `RunEnd.SAMPLE_RATE_CHANGED`, die
   Zeile in `endAgainst`, KDoc-Satz „it ends no run“ an `RunLink` streichen,
   `ObservationRunTest`-Fall (LDAC 990, 96 → 88,2 kHz → `SAMPLE_RATE_CHANGED`)
   und die `endLine`-Zeile in einem Lauf. X-4 berührt dann zusätzlich
   `ObservationRun.kt` (Welle 3, kein Parallellauf auf der Datei).
2. Ein kurzer Nachlauf X-1b mit Freigabe für eine Zeile in `ObservationRunSection.kt`.
   Wortlaut nach dem Tabellenmuster, vom ui-ux-designer zu bestätigen:
   `"Run ended when the sample rate changed. {T} observed."`

Bis dahin endet ein Lauf bei einem Sample-Rate-Wechsel so wie heute nicht.
Für den Vergleich gilt das schon jetzt: `RunLink` enthält `sampleRateHz`.
Ein Wechsel über eine Lücke oder vor Arm B beendet den Vergleich also bereits.

## Hinweis an X-4 / ui-ux-designer

UI_SPEC F-019 sperrt die Chips auch in `PIN_AND_CHECK`. `LdacTuning.pin` sperrt
dort nicht, weil das eigene Pinnen des Vergleichs über `pin` läuft (AD-040
Teil 1). Die Oberfläche muss `PIN_AND_CHECK` selbst sperren. `locksLdac`
reicht dafür nicht.
