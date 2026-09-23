# T-043b — security-reviewer, Stand `afacd89` (23.09.2026, vom Director abgelegt)

**Urteil: PASS.** Zyklus-Diff `5f62b15..afacd89` ohne Befund. W-1 gegen
V-1..V-11 erfuellt (alle elf einzeln belegt). Migration 3→4: genau ein
`DROP TABLE IF EXISTS codec_mode_signatures` (`MonitorDatabase.kt:265`), kein
`fallbackToDestructiveMigration`; `monitor.db` nicht in der Sicherung.
Starthinweis-Flag harmlos (`SetupStore.kt:87`); Beobachtungslauf speichert und
loggt nichts. F-005 ohne Angriffspfad (org.json liest nur app-eigenen
DataStore; Import laeuft ueber kotlinx + Neukodierung). Abhaengigkeiten: nur
Entfernungen, Robolectric nur Test in `:core-hearing`.

**Review `AudioEffectSessionReceiver` (offen seit T-005):** Export noetig
(Player senden ohne gemeinsame Berechtigung), bleibt. Zwei Befunde, beide
niedrig, ausserhalb des Zyklus-Diffs:

- **SR-023** `AudioEffectSessionReceiver.kt:27-29`, `SessionAttachmentStrategy.kt:217-220`:
  gefaelschtes CLOSE mit geratenen Session-IDs loest die Hoerkorrektur auch von
  Harvester-Sessions; kein Wiederanlegen, bis sich die Player-Menge aendert
  (`PlaybackSessionHarvester.kt:167`). Richtung: Herkunft je Session merken,
  Broadcast-CLOSE nur fuer Broadcast-OPEN.
- **SR-024** `AudioEffectSessionReceiver.kt:23-25`, `SessionAttachmentStrategy.kt:131-167`:
  gefaelschtes OPEN legt unbegrenzt Effekte an (`ConcurrentHashMap` ohne Grenze,
  `:60`), Binder-Aufruf unter Sperre auf dem Hauptthread. Auf erfundenen IDs
  Hypothese, am Geraet pruefen. Richtung: Obergrenze, Anlegen vom Hauptthread
  nehmen, OPEN nur fuer vom Harvester bekannte Sessions, wo verfuegbar.

**Nicht geprueft:** `BluetoothConnectReceiver`, `BootReceiver` (geschuetzte
Broadcasts?), SR-001..SR-022 nicht neu bewertet. Beobachtung: der eingeschaltete
Receiver ueberlebt Prozessende, ein gefaelschter Broadcast startet den Prozess
(AK-4-Thema, nicht Sicherheit).
