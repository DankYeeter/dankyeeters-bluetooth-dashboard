# T-070a — Vorgaben-Vorlauf AD-050..053 (security-reviewer, 29.09. 21:52)

Uebertragen vom Director aus der Antwort (Rolle ohne Write). Stand `22af2e2`.

**Urteil: FAIL fuer die Veroeffentlichung, nicht fuer die Entwuerfe.** SR-001
(hoch) ist offen, und die Play-Stufe weitet ihn auf fremde Geraete aus. AD-054
sagt "security-reviewer: nicht faellig" und uebersieht das. AD-050..053 duerfen
mit den Vorgaben unten gebaut werden.

## Befund [Hoch | Hoch | jede App ohne Berechtigung] SR-001/SR-009/SR-016 auf Kaeufergeraeten
Betroffen: `ExecSpill.kt:78-81` (fester Name, 0644), `PrivilegedProvider.kt:72,85-86`
(`/data/local/tmp`), `PrivilegedServer.kt:227-251` (Helfer endet nur, wenn die
laufende App sein Token ablehnt). Angriffspfad: fremde App kennt den festen
Dateinamen aus der APK, Ordner durchquerbar, Datei 0644; nach Deinstallation
bleibt die Datei, nur der Helfer raeumt auf (`ExecSpill.kt:66-73`); der Helfer
laeuft verwaist mit Shell-Rechten bis zum Neustart. Auswirkung: letzter Dump
`dumpsys bluetooth_manager` mit Geraetenamen und BT-Adressen lesbar (SR-001, am
Geraet bestaetigt 01.09.); Datenschutzerklaerung und "Verlauf loeschen" waeren
unwahr, wenn sie die Reste verschweigen. Behebung: T-006 (Transport ohne
Dateisystem) vor der Einreichung — sonst WAIVED durch den Director und
`privacy.txt` nennt die Reste (E5).

## Vorgaben zum woertlichen Kopieren

**AD-050 (Manifest)**
- A1. `StoreManifestTest` prueft die **vollstaendige** Menge der angeforderten Berechtigungen als Literal, nicht nur, dass zwei fehlen. Der developer misst die Menge nach dem Umbau aus dem gemergten Manifest. Randbedingung: Debug und Release haben dieselben Manifeste; keine `src/debug|release`-Manifeste (`git ls-files`, 29.09.).
- A2. `AppLabel` bleibt reine Anzeige; keine Pruefung, keine Entscheidung stuetzt sich auf Paketsichtbarkeit (`getPackagesForUid|getNameForUid`: 4 Stellen, alle Anzeige/Kandidatenabgleich; Helfer-Authentifizierung an Uid und Token, `PrivilegedServer.kt:689-705`). Kein Test noetig.

**AD-051 (Offenlegung)**
- D1. `HelperDisclosure` nennt zusaetzlich: die vier Lesebefehle aus `PrivilegedProtocol.ALLOWED`; dass der Helfer bis zum Neustart laeuft und nach dem Neustart selbst wieder startet (`BootReceiver.kt:113`); dass `WRITE_SECURE_SETTINGS` bei jeder Helfer-Ankunft automatisch vergeben wird (`BtDashboardApplication.kt:86-117`); wie der Nutzer widerruft (gekoppeltes Geraet unter "Wireless debugging" entfernen; nach Deinstallation neu starten). Test: `HelperDisclosureTest` (4): `readCommands.keys == ALLOWED`, Literal 4.
- D2. Der Waechter bindet die Schreibstellen, nicht nur die Map: Allowlist in `GlobalSettingsController.write/clear`, nur Schluessel aus `BluetoothDeveloperOptions.all` (heute dreifach bei den Aufrufern: `DeviceProfileApplier.kt:160`, `DeviceProfilesViewModel.kt:346`, `SettingsRestore.kt:112`). Test 1: unbekannter Schluessel → `false`, ohne `putString`. Test 2 (Quellscan): Dateien mit `Settings\.(Global|Secure|System)\.put` unter `*/src/main` sind genau {`WirelessDebuggingSwitch.kt`, `AbsoluteVolumeGate.kt`, `GlobalSettingsController.kt`} (29.09.: 7 Zeilen, 1 KDoc).
- D3. Zustimmung nicht nur in der Oberflaeche: `HelperAutoStart.attempt()` und `pairThenStart()` enden ohne Verbindung, wenn `isLocalConnectionAccepted` false ist. PC-Weg: Offenlegung **vor** dem Befehl; Befehl und Token erst nach dem Bestaetigen, das dasselbe Flag setzt. Tests: `ActivateViewModel` mit Flag false → `activate()` fuehrt in `Disclosure`; `SystemAccessScreenTest`: vor dem Bestaetigen kein Knoten mit dem ADB-Befehl.
- D4 (niedrig). `local_connection_accepted` (in `setup_state`) aus Cloud-Backup und Geraeteuebertragung nehmen wie das Ledger (AD-033); heute schliesst `data_extraction_rules.xml` `datastore/` ein.

**AD-052 (Assets, Loeschen)**
- E1. `AssetTextScreen` oeffnet nur Namen aus einer festen Konstantenmenge; kein Routen-Argument, keine `WebView`, kein Autolink.
- E2. Geloescht wird nur ueber den Bestaetigungsdialog, nie ueber Route oder Intent-Extra (`MainActivity` exportiert, navigiert zu jedem Routen-String, `MainActivity.kt:40,46`, `BtDashboardApp.kt:214-224`). Test: Tipp oeffnet Dialog, Loeschung nicht aufgerufen; erst "Bestaetigen" ruft sie genau einmal.
- E3. Die drei `DELETE` in **einer** Room-Transaktion; `true` nur, wenn alle drei gelangen; UI meldet "geloescht" nur bei `true`. Test: `MonitorHistoryDeleteTest`, im ViewModel `false` → Fehlertext.
- E4 (niedrig). Nach dem Komplettloeschen `wal_checkpoint(TRUNCATE)` und `VACUUM`.
- E5. `privacy.txt` sagt: der Verlauf enthaelt Adressen und Namen der Geraete (`device_address`, `device_name`); welche Speicher "Verlauf loeschen" nicht erfasst (Geraeteprofile, Hoerdaten, EQ, Setup, Ledger, ADB-Schluessel); solange SR-001/009/016 offen: dass Dateien in `/data/local/tmp` und der Helfer die Deinstallation ueberleben.

**AD-053 (exec-Frist)**
- C1. Einzelbelegung von `exec` sichert ab jetzt `execSlot`, nicht mehr `EXEC_LOCK` — nur solange es eine einzige Guard-Instanz fuer `exec` gibt (heute `BtDashboardApplication.kt:128`, `SHARED`); der KDoc nennt diese Bedingung.
- C2. Dekodieren und `spill.collect` laufen im `boundedExec`-Block. Test: AD-Test 2 mit gestagter Antwort; der dritte `run` erreicht den Fake erst, wenn das spaete `collect` fertig ist.
- C3. Nach Fristablauf enthalten Antwort und Logs keine Teilausgabe, nur "timed out after 15 s". Whitelist-Pruefung und `refuse()` bleiben vor `execute`. AIDL unveraendert.
- C4 (K-2). Vier Dateien sagen, `EXEC_LOCK` sichere die Einzelbelegung — nach AD-053 falsch. Masken `EXEC_LOCK` (4) und `mutex|serialis(es|ed) exec` (6): `ExecSpill.kt:26-29,76-77`, `PrivilegedProvider.kt:98-102`, `PrivilegedProtocol.kt:183`, `PrivilegedClient.kt:449-456`. Director hebt dafuer den Ausschluss von `ExecSpill.kt` auf (nur Kommentare).

## Beobachtungen
- Hypothese: Der verwaiste Helfer uebergibt Binder und Token an jeden Inhaber der Authority, sobald ein Prozess mit der alten Uid laeuft; Provider-Eigentuemer ungeprueft (`PrivilegedServer.kt:228-235,428-485`). Billig: beim Handover `holder.info.applicationInfo.uid == appUid` pruefen.
- Leser in `awaitOutput` laufen im Common-Pool; heute startet kein Whitelist-Befehl einen Enkel, kein Pfad.
