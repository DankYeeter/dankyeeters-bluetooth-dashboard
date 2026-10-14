# T-049a — Einstellungs-Inventur P-3 (T-045)

Gerät `67011FDKX004XG` (Pixel 11 Pro, Android 17), `platform-tools/adb.exe` (R-2).
Nutzer: nichts (Änderung 23.09. 19:19). Kein `dumpsys bluetooth_manager` verwendet (R-011).

## 0. Vorlauf

19:56 WEST: App-Prozess `dev.dankyeeter.btdashboard` (PID 25360) per
`am force-stop` beendet, Helferprozess `btdash_privileged` (PID 25341, User
`shell`, aus T-048 F-012) per `kill` beendet. `ps -A | grep -i btdash` danach
leer. Am Ende der Sitzung (20:06:58) erneut leer — beide bleiben beendet, kein
Neustart für diesen Abschnitt nötig. Tidal (`com.aspiro.tidal`, PID 11069)
lief durchgehend unberührt weiter.

## 1. `wifi_on`, `wifi_scan_always_enabled`, `ble_scan_always_enabled`

| Setting | Liest die App? | Schreibt die App? | Deep-Link? | Anleitungstext? | Quelle |
|---|---|---|---|---|---|
| `wifi_on` | ja, read-only | nein | nein (nur genereller `ACTION_WIFI_SETTINGS`-Link in `ActivateScreen.kt:347`, für die Helfer-Aktivierung über WLAN, nicht für diese Messung) | ja: „Turn Wi-Fi off on this phone for this arm — the band it uses competes with Bluetooth for airtime." | `EnvironmentConditions.kt:31,98`; `ComparisonSection.kt:335-336` |
| `wifi_scan_always_enabled` | ja, read-only | nein | nein | nein (nur passive Zustandsanzeige „Wi-Fi scanning" im Read-back, keine Handlungsaufforderung) | `EnvironmentConditions.kt:32,99`; `ComparisonSection.kt:378` |
| `ble_scan_always_enabled` | **nein**, bewusst ausgeschlossen | nein | nein | nein | `EnvironmentConditions.kt:22-26` (Begründung im Code: R-010 C7/AK-12 — Bluetooth-Scan wirkt nur bei ausgeschaltetem Bluetooth, das während laufendem Prozess nie zutrifft; „refuted measure with nowhere honest to show") |

Für keine der drei Einstellungen stellt die App selbst einen Wert, und keine
hat einen Deep-Link zu sich selbst.

## 2. Lesbar aus App-UID / schreibbar mit `WRITE_SECURE_SETTINGS`?

**Werkzeug-Limit zuerst:** `adb shell run-as <pkg> settings get/put …` scheitert
mit `SecurityException … requires android.permission.INTERACT_ACROSS_USERS`,
`adb shell run-as <pkg> content query …` mit `… requires
android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY`. Beides sind
Berechtigungsprüfungen der jeweiligen Shell-Kommandos selbst (`SettingsService$MyShellCommand`,
`content`-Binary), nicht der `Settings.Global`-ContentResolver-API, die der
App-Code tatsächlich verwendet (`GlobalSettingsController.kt`,
`EnvironmentConditions.kt`). Ein App-UID-Prozess ohne diese beiden
Shell-Sonderrechte kann die CLI-Werkzeuge dafür nicht nutzen — das ist kein
Befund über die App, sondern eine Einschränkung der Prüfmethode.

**Belegt (Primärquelle `dumpsys package`, 19:57–20:07):**
- `WRITE_SECURE_SETTINGS: granted=true` für `dev.dankyeeter.btdashboard`,
  durchgehend, auch nach Beenden des Helfers (Grant ist persistent, nicht an
  den laufenden Prozess gebunden).
- Vorwerte (Shell, vor jeder Probe): `wifi_on=1`, `wifi_scan_always_enabled=1`,
  `ble_scan_always_enabled=1`.
- Schreibprobe `wifi_scan_always_enabled`: `1 → 0` (angenommen, Read-back
  bestätigt `0`) `→ 1` (Read-back bestätigt `1`).
- Schreibprobe `ble_scan_always_enabled`: `1 → 0` (Read-back bestätigt `0`)
  `→ 1` (Read-back bestätigt `1`).
- `wifi_on` **bewusst nicht** probeweise geschrieben: anders als die beiden
  Scan-Schalter ist das ein echter Funkschalter, kein reiner Anzeigewert —
  ein Fehlschlag oder ein tatsächliches Abschalten des Radios wäre ein
  Seiteneffekt über die Probe hinaus, unnötig für die reine
  Lesbarkeits-/Schreibbarkeitsfrage. Vorwert `1` ist dokumentiert, keine
  Rückstellung nötig, da nicht verändert.

**Einordnung:** Die Probe lief auf Shell-Ebene (uid 2000), die für den
globalen Settings-Namensraum dieselbe Rechteklasse wie `WRITE_SECURE_SETTINGS`
durchläuft — dieselbe Methode, mit der laut KDoc bereits die vier
`BluetoothDeveloperOptions`-Schlüssel „von Hand" verifiziert wurden
(`BluetoothSystemControls.kt:33-35`). Ein wortwörtlicher Nachweis aus dem
App-Prozess selbst (uid 10394) war mit den verfügbaren CLI-Werkzeugen nicht
herstellbar; das Lesen ist zusätzlich durch das Plattformverhalten
plausibilisiert (`Settings.Global`-Lesen verlangt für normale Apps grundsätzlich
keine Berechtigung — darauf baut der bereits ausgelieferte, getestete Code in
`EnvironmentConditions.kt`/`EnvironmentConditionsTest.kt` auf).

## 3. F-011 — wo stellt der Nutzer die drei Nur-Lesen-Werte?

Betroffen: `a2dpHardwareOffload` (`persist.bluetooth.a2dp_offload.disabled`),
`maxConnectedAudioDevices` (`persist.bluetooth.maxconnectedaudiodevices`),
`hciSnoopLog` (`persist.bluetooth.btsnooplogmode`) — alle drei System-Properties,
keine `Settings.Global`-Schlüssel (`BluetoothSystemControls.kt:97-127`).

Vollständiger Scroll durch Android-Entwickleroptionen (mehrere unabhängige
Durchläufe, verschiedene Startpositionen, `uiautomator dump` + Textsuche je
Bildschirm, 19:57–20:06). Erfasste Abschnitte: Allgemein (Arbeitsspeicher,
Bug-Report, Sicherung, Wach bleiben, Bootloader, laufende Dienste, WebView,
Updates, DSU, Demo-Modus), Netzwerk (nur WLAN-Logging/-Drosselung/-MAC,
mobile Daten, Tethering, Downloadrate), NFC, Eingabe, Fensterverwaltung,
Autovervollständigung, Darstellung, Medien, Überwachung, Hardware-Rendering,
Speicher, Standort, Eingabemethode. **Kein** sichtbarer Menüpunkt mit
„Bluetooth", „AVRCP", „snoop", „offload" oder „connected devices" im Text —
weder für die drei Nur-Lesen-Werte noch für die vier
`BluetoothDeveloperOptions`-Schlüssel, deren KDoc sie als „ordinary global
settings Android's own Developer Options screen writes" beschreibt.

**Widerspruch zu bestehender Dokumentation, nicht selbst aufgelöst:**
- `docs/berichte/T-045-architect.md:48` (P-4, laut `docs/state.md`
  „beschlossen, nicht beauftragt"): „Die drei Nur-Lesen-Zeilen verlinken in
  die Entwickleroptionen … und der Root-Satz fällt weg." — auf diesem Gerät
  keine sichtbare Andockstelle gefunden.
- `docs/state.md:61-64`: F-011 dort als „laut T-045 falsch" vermerkt (der
  Root-Satz sei nicht zutreffend). Der Live-Befund stützt eher die
  App-Aussage: kein Nutzerpfad ohne Root gefunden, und die bereits im Code
  zitierte `setprop`-Probe (`BluetoothSystemControls.kt:79-82`) schlägt am
  SELinux-Kontext der Shell fehl.

Beides bewusst nur als Diskrepanz gemeldet — Neubewertung von P-4/F-011 ist
Sache des Directors, nicht dieser Rolle.

## 4. Zustand am Ende

App und Helfer weiterhin beendet, `WRITE_SECURE_SETTINGS` weiterhin `granted=true`
(unverändert von Vorlauf), alle drei Settings auf Ausgangswert `1`, Tidal
läuft unverändert (PID 11069), keine temporären Dateien auf dem Gerät
(`/sdcard/*.xml` aus der `uiautomator`-Prüfung entfernt, belegt per `ls`-Grep-Leerergebnis).
