# T-047b — Security-Vorlauf AD-033/AD-034 (security-reviewer, 23.09., Stand `f8a00f4`)

Vom Director aus der Antwort übertragen (Rolle ohne Write). Urteil **CONCERNS**:
baubar mit den Auflagen unten; vier Entwurfsbefunde mittel, zwei niedrig
(SR-025..SR-030 in `security/findings.md`), nichts hoch/kritisch.

## Befunde

1. **SR-025 [Mittel]** Globals: `GlobalSettingsController.kt:34-36` macht aus
   einer Ausnahme `null`. Beim Festhalten hält `recordGlobal` „nicht gesetzt"
   fest, der Rückweg löscht dann einen Wert, den der Nutzer hatte; bei der
   Bestätigung prüft `clear()` über `read()` (`:72-73`), ein Lesefehler gilt als
   „gelöscht".
2. **SR-026 [Mittel]** `LdacQuality.codeOf` (`LdacTuning.kt:98-104`) liefert nie
   `null`: `UNKNOWN` und fehlender Wert werden `NONE`; der Rückweg fragt dann
   ADAPTIVE an, obwohl der Vorwert unbekannt war.
3. **SR-027 [Mittel]** Ledger im Format von `DeviceProfileStore`: nicht lesbare
   Datei wird still „leer" (`DeviceProfileStore.kt:40-41`); die nächste
   Schreibung hielte den geänderten Wert als Ausgangszustand fest.
4. **SR-028 [Mittel]** Wettlauf: `DeviceConnectionWatcher.kt:93-103` wendet
   Profile in eigener Coroutine an. Anwenden sieht alten Eintrag → schreibt
   nichts ins Ledger; Rückweg stellt zurück, löscht Eintrag; Anwenden schreibt
   Profilwert → Einstellung geändert, Ledger leer.
5. **SR-029 [Niedrig]** Rückweg schreibt einen gespeicherten Schlüssel direkt in
   `Settings.Global.putString`; heute sind Schlüssel über `byKey` erlaubt
   (`DeviceProfileApplier.kt:142`) oder fest im Code.
6. **SR-030 [Niedrig]** `HelperBluetooth.kt:564` baut `"unknown device $address"`
   mit echter Adresse; Gründe sollen wörtlich angezeigt werden.

## Vorgaben

**S3-3 (Ledger-Kern, Applier, Backup)** — Muss:
- **M1:** Lesezugriff mit drei Ausgängen für Ledger und Rückweg: Wert, nicht
  gesetzt, nicht lesbar. `recordGlobal` gibt bei „nicht lesbar" `false` zurück.
- **M2:** `recordIfAbsent` prüfen, ergänzen und schreiben in einem einzigen
  `edit {}`-Schritt; die Applier-Schreibung beginnt erst danach.
- **M3:** Ist die Ledger-Datei nicht lesbar, antwortet `recordIfAbsent` mit
  `false`, und `entries()` meldet einen Fehlerzustand, keine leere Liste. Test:
  kaputtes JSON → keine Schreibung.
- **M4:** Eine gemeinsame Sperre im Ledger; der Applier hält sie über
  „festhalten und schreiben" je Einstellung.
- **M5:** Drei Ausschlüsse nach AD-033, dazu ein Test, der den Pfad als festen
  Text in allen drei Abschnitten sucht und den Store-Namen `"settings_ledger"`
  als festen Text prüft (gilt, solange DataStore unter
  `files/datastore/<name>.preferences_pb` ablegt — dokumentiert, nicht gemessen).
- **M6:** Ledger speichert nur `deviceKey`, nie rohe Adresse oder Gerätenamen.
  Genau eine DataStore-Instanz, gebaut in `SystemGraph`.
- **M7:** Test: nicht lesbarer Vorwert → `Skipped`, kein Schreibaufruf; je
  einmal HD-Audio und Globals (Zustand „nicht lesbar" aus M1).

Darf nicht: Lesefehler in `null` umdeuten · Ledger beim Parsen still verwerfen
· Eintrag ohne bestätigte Rücklesung löschen.

**S3-5 (Rückweg)** — Muss:
- **M8:** Globals vor jedem Schreiben gegen feste Liste prüfen: die vier
  Schlüssel aus `BluetoothDeveloperOptions.byKey` plus
  `bluetooth_disable_absolute_volume`. Fremde Schlüssel → „nicht zurück" mit
  Grund. Test mit `adb_enabled` im Ledger: wird nicht geschrieben.
- **M9:** Bestätigung über den Zugriff aus M1; nur „nicht gesetzt" bestätigt
  ein `clear`. Absolute Volume über `globals.write/clear`, nicht über
  `AbsoluteVolumeGate.setEnabled` (liest nicht zurück, `AbsoluteVolumeGate.kt:69-71`).
- **M10:** Zuerst Autoapply aussetzen, gespeichert und abgewartet; schlägt das
  fehl, schreibt der Rückweg nichts und meldet den Grund.
- **M11:** Rückweg hält dieselbe Sperre wie M4, je Eintrag über Schreiben,
  Rücklesen, Löschen.
- **M12:** `LedgerEntry.Ldac.priorLive` ist `null` bei `UNKNOWN` oder fehlendem
  Wert; `NONE` nur für `NOT_PINNED`. Verbunden und `priorLive == null` →
  Bericht „Stufe bleibt bis zum nächsten Verbinden" (analog D4), nicht als
  zurückgestellt.
- **M13:** Jeder Grundtext vor der Anzeige durch `redactAddresses`.
- **M14:** Hinweis auf offene Einträge nach jedem App-Start auf dem ersten
  Bildschirm (D2), auch ohne laufenden Helfer.
- **M15:** `setGlobalNow`/`setAbsoluteVolumeNow`: festhalten, dann schreiben,
  nacheinander in derselben Coroutine.

Darf nicht: Rückweg automatisch starten (Start, Neustart, Prozesstod) — nur
Knopfdruck mit Bestätigung · Rückweg von aussen erreichbar (Intent, Receiver,
Provider) · Grundtexte speichern · Medienlautstärke oder EQ anfassen (D3).

**S3-6 (990 pinnen)** — Muss:
- **M16:** Pinnen ausschliesslich über `LdacTuning.pin` mit Ledger-Eintrag;
  `pin` gibt ein Ergebnis zurück (heute `Unit`, kehrt bei `busy` still zurück,
  `LdacTuning.kt:211-212`). „Nicht versucht" gilt nie als `Applied`.
- **M17:** `wifi_on`, `wifi_scan_always_enabled`, `ble_scan_always_enabled` nur
  lesen, drei Ausgänge; Lesefehler = „nicht lesbar". Zugriff in S3-4.
- **M18:** Messdaten und Paarungsfakten ohne rohe Adresse (Schirm, Zustand, Log).

Darf nicht: neue Schreibungen, Helfer-Kommandos, Berechtigungen, exportierte
Komponenten · gespeicherter Hinweis-Schalter · Ergebnisse speichern/exportieren.

## Beobachtungen

- AD-033 nennt `MonitorViewModel.kt:405`; der `pin`-Aufruf steht heute in :380.
- Deinstallation bei offenen Einträgen lässt Globals/HD-Audio gesetzt — wie
  heute ohne Ledger; alles bleibt in den Android-Einstellungen änderbar.
- Nicht geprüft: ob `Settings.Global.getString` für die vier Entwickleroptionen
  am Gerät wirft; DataStore-Dateiname am Gerät; `adb backup` bei Debug-Builds;
  Zulassungsliste in `PrivilegedServer`.
