# T-055b — X-2: AD-041, AD-042, F-011, DR-006, Banner-Text, SR-031 (developer, 28.09.)

Stand `4ac22ab`. Suite `./gradlew clean test --no-build-cache` 23:19: 2653 Tests, 0 Fehler.

## Rot vorher

Zuerst wurden die Tests geschrieben, dann das Ledger-API (discard, Fabrik, Marker) sowie die Verdrahtung in ViewModel und Schirm gebaut, noch ohne die Fixes.
Zu diesem Zeitpunkt schlugen 12 Tests aus dem erwarteten Grund fehl: AD-041 V1 (4), V2 (2), V3, V4, V12, AK-T054-9, AK-T054-10,
AK-T054-11, SR-031. Die V11-Sperre wurde per Mutation geprüft (Wächter entfernt → rot, Datei per Kopie
zurückgestellt). Die AD-042-Tests für den Store (neue Methode) waren nur durch den Kompilierfehler rot.

## Befunde

1. **Leere Datei = leeres Ledger** (V10, zweiter Test): DataStore liest eine 0-Byte-Datei als
   leere Preferences, `entries()` liefert dann `success([])`. Das ist eine theoretische Lücke in M3,
   praktisch unwahrscheinlich, weil DataStore über eine Temp-Datei und Umbenennen schreibt. → security-reviewer, director.
2. **Root in sichtbaren Texten außerhalb von AK-T054-11:** `SystemAccessScreen.kt:128`
   („… not root.“) und `SetupWizardScreen.kt:408` („… not root.“). Beide liegen außerhalb der beiden
   geprüften Dateien und wurden nicht geändert. → ui-ux-designer, director (GOAL-Nichtziel „Root nicht nennen“?).

## Annahmen

- Eigener Wortlaut (fehlt in UI_SPEC), wenn Verwerfen nichts ändert: „Nothing was forgotten — the list
  could not be changed just now.“ als Zeile im Unreadable-Banner. → ui-ux-designer.
- „Forget this list“ erscheint nach dem Tipp auf „Read again“, solange der Banner danach
  `Unreadable` bleibt. Gelingt das Lesen, wechselt der Banner, und der Knopf verschwindet.
- `profileFor` wird ohne eigenen Fang aufgerufen. `DeviceProfileStore` wirft heute nicht. Würde es
  werfen, bricht `applyNow` ab, ohne zu schreiben, meldet aber kein `Skipped`.
