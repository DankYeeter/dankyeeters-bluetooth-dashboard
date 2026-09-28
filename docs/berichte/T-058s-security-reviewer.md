# T-058s — Security-Vorlauf AD-044 / AD-045 / AD-046 (security-reviewer, 28.09., Stand `9a55573`)

Vom Director aus der Antwort abgelegt (Rolle ohne Write). **Urteil PASS** unter
Bedingung W1–W7. Kritisch 0 · Hoch 0 · Mittel 0 · Niedrig 5 (SR-034..SR-038).

## Bauauflagen Y-1 (AD-044)

**W1 — Zustandsbuch nur in `monitor_events.detail`.** Buch und `conditionLines`
nirgends sonst: nicht DataStore, SharedPreferences, Datei, `Log.*`. Randbedingung:
Backup-Regeln nehmen nur `datastore/` auf; `monitor.db` unter `databases/` ist
weder in Cloud-Sicherung noch Geraeteuebertragung; aendert jemand die
Backup-Regeln, neu pruefen. *Test:* Grep-Test in `AcceptanceCriteriaGrepTest`:
`conditionLines(` nur in `ConditionStatus.kt`, `MonitorViewModel.kt`, ggf.
`EventLog.kt`; in `SourceTree.appAndLinkSources` 0 Treffer fuer
`Log\.\w\(.*(book|conditionBook|conditionLines)`.

**W2 — Jede Bedingungszeile einzeilig.** In `conditionLines` laeuft jeder
`Unreadable.reason` zuerst durch `redactAddresses`, dann wird jedes Steuerzeichen
(`Char.isISOControl()`) durch ein Leerzeichen ersetzt, erst dann
`joinToString("\n")`. *Test (ConditionStatusTest, JVM):* `Unreadable("x\nWi-Fi
power: on, read.")` → genau eine Zeile je `Condition`, Gesamtzahl =
`Condition.entries.size`; `Unreadable("AA:BB:CC:DD:EE:FF gone")` → Zeile mit
`XX:XX:XX:XX:EE:FF`.

## Bauauflagen Y-2 (AD-045)

**W3 — `openSettings` startet nur die zwei festen Aktionen.** `openSettings(context,
action)` gibt `false` zurueck und ruft `startActivity` nicht, wenn `action` nicht
in {`Settings.ACTION_WIFI_SETTINGS`, `Settings.ACTION_BLUETOOTH_SETTINGS`}. Intent
ohne Extras, `data`, `ClipData`, `FLAG_GRANT_*`; kein `startActivityForResult`,
kein `PendingIntent`; Aktion nur aus `settingsAction(...)`, nie aus gespeichertem
oder von aussen geliefertem Wert (`MainActivity` exportiert, `OpenRoute.EXTRA`,
`MainActivity.kt:40,46`). *Test (Robolectric, `checkActivities(true)`):*
`openSettings(ctx, "android.intent.action.VIEW")` → `false`, `nextStartedActivity
== null`; fuer beide erlaubten Aktionen: `extras` null/leer, `data == null`,
`clipData == null`, keine `FLAG_GRANT_*`-Bits.

**W4 — Geraeteprobe A3 prueft auch Sichtbarkeit.** Bei offener Bluetooth-Seite
zusaetzlich einmal `dumpsys bluetooth_manager | grep -iE
"ScanMode|Discoverable|Discovering"`; Scan-Modus `…DISCOVERABLE` → A3-Sprung auf
`null` wie bei `Discovering: true`. R-011: nicht waehrend einer Messung. Nur am Geraet.

## Bauauflagen Y-3 (AD-046)

**W5 — Genau ein Leseweg, aus `WifiInfo` nur die Frequenz.** Einziger
Netz-Lesezugriff im Projekt: `getNetworkCapabilities(activeNetwork)?.transportInfo
as? WifiInfo` mit `.frequency`, als ein Ausdruck in `EnvironmentConditions.kt`.
`NetworkCapabilities`, `TransportInfo`, `WifiInfo` an keinen Wert gebunden, der
die Funktion verlaesst; nie in einen String, nie geloggt. Verboten:
`getLinkProperties`, `registerNetworkCallback`, `registerDefaultNetworkCallback`,
`requestNetwork`, `FLAG_INCLUDE_LOCATION_INFO`, `WifiManager`. Option D
(NetworkCallback) braucht neuen Vorlauf. *Test (Grep, ersetzt den AD-046-Vorschlag):*
alle Kotlin-Quellen unter `app/src/main` und `core-*/src/main`, case-sensitiv, 0
Treffer auf
`\.(ssid|bssid|ipAddress|macAddress|networkId|rssi|linkSpeed|informationElements|passpointFqdn|apMldMacAddress)\b|\b(getSSID|getBSSID|getIpAddress|getMacAddress|getRssi)\b|WifiManager|getLinkProperties|register(Default)?NetworkCallback|requestNetwork|FLAG_INCLUDE_LOCATION_INFO`;
`getNetworkCapabilities` und `WifiInfo` nur in `EnvironmentConditions.kt`. KDoc
`EnvironmentConditions.kt:17` nennt `WifiManager` → umformulieren.

**W6 — Berechtigungen am gemergten Manifest pruefen.** Neue Zeile
`<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` in
`app/src/main/AndroidManifest.xml` mit Zweckkommentar (nur Band ueber Frequenz,
keine SSID/BSSID, kein Standort). *Test (Robolectric):*
`packageManager.getPackageInfo(packageName, GET_PERMISSIONS).requestedPermissions`
enthaelt `BLUETOOTH_SCAN` (Kontrolle: gemergtes Manifest) und
`ACCESS_NETWORK_STATE`, enthaelt keine von `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `NEARBY_WIFI_DEVICES`,
`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `LOCAL_MAC_ADDRESS`.

**W7 — Kein Weg zu einer Berechtigungsanfrage.** Kein Band-Zweig und kein
Pruefstand-Satz loest eine Berechtigungsanfrage aus oder oeffnet App-Info- oder
Standort-Einstellungen. *Test (Grep):* in `SourceTree.monitorScreen`,
`ui/tuning/EnvironmentConditions.kt`, `ui/tuning/SettingsJumps.kt` 0 Treffer fuer
`RequestPermission|requestPermissions|ACTION_APPLICATION_DETAILS_SETTINGS|ACTION_LOCATION_SOURCE_SETTINGS`.

## Befunde

- **SR-034** Grep-Vorschlag AD-046 uebersieht Kotlin-Property-Zugriff (`.ssid`/`.ipAddress`), prueft nur `app/src/main/java`; `ssid` case-insensitiv trifft `rssiDbm` (`LinkTimeline.kt:97`) → W5.
- **SR-035** Standortpruefung nur an Quellmanifesten, Manifest-Merge ungeprueft → W6.
- **SR-036** `openSettings(action: String)` nimmt jede Aktion (Confused Deputy, nur bei kuenftiger Verdrahtung) → W3.
- **SR-037** Sichtbarkeit beim Sprung zu `BLUETOOTH_SETTINGS` am Pixel nicht belegt (Hypothese) → W4.
- **SR-038** Zeilenumbruch in einem Grund faelscht Bedingungszeilen in `detail` (`EnvironmentConditions.kt:62`, braucht Shell) → W2.

## Beobachtungen und Director-Entscheide (23:3x)

- Datenhaltung AD-044 ohne Befund (`monitor.db` privat, nicht im Backup, 24 h,
  `detail` nur in `EventLog.kt:185` mit `redactAddresses`). Rohfrequenz im Grund
  gespeichert — hinnehmbar.
- **UI_SPEC-Satz „needs location access this app does not request“ hat keinen
  Ausloeser und behauptet eine Ursache. Director: gestrichen**; unlesbares Band
  zeigt den allgemeinen Satz „not readable — {reason}“ (AD gilt).
- **AD-044 vs UI_SPEC Umgebungsblock** (jede DROPOUT-Zeile vs. nur erste Zeile
  der Episode). **Director: AD-044 gilt** — AK-19 sagt „Jeder Protokolleintrag
  eines Verlustausbruchs nennt …“; jede Verlustzeile traegt das Buch der ersten
  Lesung ihrer Episode.
- **Gefangene Exceptions:** Director: jede `Exception` ausser
  `CancellationException` fangen (UI_SPEC), sicherheitlich unschaedlich.
- Auswahldialog bei Dritt-Apps mit Filter auf die Settings-Aktionen: Bestand,
  kein Datenabfluss; `FLAG_ACTIVITY_REQUIRE_DEFAULT` nicht jetzt.
- Play: A-008 bekommt eine Zeile fuer `ACCESS_NETWORK_STATE`; A-010 / C-001 Nr. 10
  muss das WLAN-Band nennen → beim Nutzer (Play-Vorlage).
