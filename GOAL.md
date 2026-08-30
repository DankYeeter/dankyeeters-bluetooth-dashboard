# GOAL — DankYeeter's Bluetooth Dashboard

Zielbild des Director-Zyklus, angelegt 2026-08-30. Aenderungen nur durch
Entscheidung des Nutzers, mit Datum und Grund.

## Ziel

Die App macht den Bluetooth-Audiopfad des Telefons sichtbar und
korrigierbar: sie zeigt ehrlich, was der Stack gerade tut (Codec, Bitrate,
Verluste), sie kompensiert Daniels Hoerverlust ueber eine System-EQ-Kette,
und sie tut beides, **ohne den Klang zu verschlechtern, den sie misst**.
Das Messen ist ein Instrument, kein Eingriff. Eine Anzeige, die das
Encodieren stoert, ist schlechter als keine Anzeige.

## Abnahmekriterien

- **AK-1 (Nicht-Einmischung):** Bei laufender LDAC-Wiedergabe unterscheidet
  sich die gemessene Verlustrate (Encoder-Underflows, Stack-Dropouts,
  Track-Underruns pro Minute) zwischen "App laeuft mit allen Live-Ansichten"
  und "App-Prozess nicht vorhanden" nicht ausserhalb der Streuung der
  Referenzlaeufe. Belegt durch Messreihen am Pixel 11 Pro in
  `docs/perf/baselines.md`.
- **AK-2 (Kein Detailverlust):** Der Informationsgehalt der Live-Ansichten
  bleibt gegenueber Stand babe3d8 erhalten — Codec, Quality-Mode, gemessene
  Bitrate, Underflow-/Dropout-Zaehler, Nahaufnahme mit >= 2 Hz effektiver
  Aufloesung.
- **AK-3 (Ehrlichkeit bleibt):** Kein Wert wird interpoliert, geschaetzt
  oder als MEASURED ausgegeben, wenn er es nicht ist. Degradierte Pfade
  melden "cannot check", nie einen falschen Freispruch.
- **AK-4 (Hintergrund kostet nichts):** Ohne offene Monitor-Oberflaeche
  fuehrt die App keine wiederkehrende Arbeit aus, die den Audiopfad
  beruehrt. Jede periodische Arbeit im Prozess ist inventarisiert und
  begruendet.
- **AK-5 (Regressionsfrei):** Volle Testsuite gruen; A16-Bau-Regel
  eingehalten (neue Features auf der bewiesenen API-Flaeche, sonst
  versions-gegated mit ehrlicher Meldung).
- **AK-6 (Sicherheit):** Jede Erweiterung des privilegierten Helpers ist
  vom `security-reviewer` geprueft; keine Verbreiterung der Angriffsflaeche
  ohne dokumentierte Begruendung.

## Nicht-Ziele

- Kein Klang-"Verbessern" jenseits der spezifizierten Hoerkompensation.
- Keine neue Datensammlung ueber das hinaus, was der Audiopfad hergibt.
- Kein Rueckbau der Ehrlichkeitsregeln zugunsten huebscherer Graphen.
- Keine Installationen mehr auf dem Pixel 8 Pro (abgenommen 28.8.).

## Rahmen

- Zielgeraet: **Pixel 11 Pro, Android 17** (`67011FDKX004XG`, per Kabel).
  Android 16 bleibt unterstuetzt, wird aber nicht mehr bespielt.
- Kotlin/Compose, Oboe NDK, eigener privilegierter Helper (`app_process`,
  per ADB-Loopback gestartet). Kein Shizuku mehr.
- Toolchain: Temurin 21, compileSdk/targetSdk 36, AGP 8.9.3, Gradle 8.11.1.
- Emulator existiert nicht (kein Hypervisor) — alles ausser Geraetetests
  laeuft ueber Unit-Tests und Robolectric.
- Der Helper darf fuer den Datenweg erweitert werden (Entscheidung Daniel,
  2026-08-30), inklusive Parsing im Helper, Push-Kanal und neuen
  Whitelist-Kommandos, jeweils nach Sicherheitspruefung.
