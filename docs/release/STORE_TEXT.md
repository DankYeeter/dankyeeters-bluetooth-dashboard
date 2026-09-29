# Store text — Google Play listing (draft, T-074a)

Draft for the Play Console listing, English and German. Written for the
Vollfassung mit Helfer, EU/EEA only, one-time purchase in the store (Nutzerentscheide
29.09.2026). Nothing here has been entered in the Console; the price is set
there and is not named in the text.

Binds: AK-23 (helper and written settings, C-001 Auflagen 1, 3, 8), AK-24
(purpose, Auflagen 16, 17), C-002 Befund 4 (what the listing must say before a
purchase) and Befund 8 (purpose sentence). Companion texts: `HELPER_DISCLOSURE.md`
(the in-app version of the helper section), `app/src/main/assets/privacy.txt`,
`app/src/main/assets/imprint.txt`.

**Placeholders, to be replaced before submission, never invented:**
`<PRIVACY_URL>` (public page, name of the repository still open), `<E-MAIL>`.
Name and address appear through the Console's developer details and in the
in-app legal notice (`imprint.txt`), not in the listing text.

**Wordlist (C-002 Befund 8).** The purpose sentence (first paragraph) and the
disclaimer (last paragraph) contain wordlist entries on purpose: they belong in
the store text or in an asset, never in a `.kt` literal. Everything else in
both descriptions was checked against the list, with the patterns fixed by the
Director on 29.09.2026 (`\bcure\b`, `diagnos(e|es|ed|ing|is)\b`, all other
entries as substrings, case-insensitive; see `HELPER_DISCLOSURE.md` section
8). Result: the matches are in those two paragraphs only. As a plain substring
`cure` would also match `WRITE_SECURE_SETTINGS` / "secure settings".

## English

**Title** (32 characters; the Console's title limit is, from the form and not re-verified today, 30 — see open point 1):

```text
DankYeeter's Bluetooth Dashboard
```

**Short description** (76 characters, limit 80 per `docs/research/R-012.md`):

```text
Equalizer and link monitor for Bluetooth headphones. Needs a one-time setup.
```

**Full description** (3865 characters with the placeholders as written; limit 4000 — Play's documented limit, not re-verified today; the real URL and e-mail add characters, see open point 2):

```text
DankYeeter's Bluetooth Dashboard is an equalizer for Bluetooth headphones that shapes the sound to your personal hearing profile, plus a monitor for the Bluetooth connection. The hearing profile comes from a self-test in the app or from an audiogram you type in. The app is not a hearing aid and not a medical device. It does not diagnose or treat anything. If you are concerned about your hearing, see a professional.

WHAT IT DOES
• Hearing test: a guided test, ear by ear, on your own headphones. It is not calibrated: its numbers mean something only compared with each other, on the same headphones.
• Equalizer: applies a sound curve per ear, derived from your hearing profile, to the audio your phone plays.
• Bluetooth monitor: codec, bitrate, signal strength and interruptions over time, and a guided comparison of two settings. History is kept for 24 hours.

BEFORE YOU BUY
• Android 12 or later, and Bluetooth headphones.
• Most functions need a helper (see below). Setup needs Developer options and Wireless debugging switched on, a Wi-Fi connection, and a one-time pairing with a code that Android shows.
• Without the helper the app still runs, with less: the equalizer reaches only players that announce their audio session, the codec shows as unknown, and the check for other equalizers cannot run.
• That check recognizes only known apps: apps that declare an equalizer panel and 12 named headphone companion apps.
• Tested only on Google Pixel phones.
• A future Android version may block the on-phone connection that starts the helper. The helper functions would then stop working.

THE HELPER
A small program inside the app. It runs with the rights of Android's debugging shell (adb shell), not root, and downloads no code. The app starts it over Wireless debugging on the phone itself and, through it, gives itself the permission WRITE_SECURE_SETTINGS.
It runs four read commands (status reports of Bluetooth and audio, the list of running processes) and a few changes: renegotiating the Bluetooth codec, turning HD audio on or off for a headphone, restarting Bluetooth, granting that permission, and stopping itself.
The app writes six Android settings: three Bluetooth profile versions, showing devices without names, Bluetooth absolute volume, and Wireless debugging. It records each previous value and can put it back.
After a restart of the phone, if the equalizer is on, the app turns Wireless debugging on, starts the helper and turns it off again.
The full list is shown in the app before the first connection.

PERMISSIONS
• Bluetooth connect: see connected headphones, read codec and link data, apply profiles.
• Bluetooth scan (never for location): read earbud beacons for battery and wear state.
• Microphone: measure room noise before a hearing test. Analyzed in memory, never recorded.
• Notifications: equalizer status, stop button for a measurement, pairing code entry.
• Foreground service, connected device: keeps the equalizer and a measurement running with the screen off.
• Start at boot: restores the equalizer and restarts the helper.
• Change audio settings: attaches the equalizer effect.
• Network state: reads whether Wi-Fi is 2.4 or 5 GHz, nothing else.
• Internet: only to reach the debugging service on this same phone (127.0.0.1). Nothing is sent to the internet.
• Write secure settings: writes the six Android settings above. Granted through the helper, not by a dialog.

PRIVACY
No account, no ads, no analytics. Nothing leaves the phone. Privacy policy: <PRIVACY_URL>

SUPPORT
I provide security and compatibility updates for at least two years after the last sale. If I stop maintaining the app, I take it off sale first. Contact: <E-MAIL>

DISCLAIMER
This app is not a medical device and does not diagnose, treat, cure, or prevent any medical condition. For medical advice, consult a healthcare professional.
```

## Deutsch

**Titel:** wie oben (Eigenname).

**Kurzbeschreibung** (75 Zeichen):

```text
Equalizer und Monitor für Bluetooth-Kopfhörer. Einmalige Einrichtung nötig.
```

**Beschreibung** (3871 Zeichen mit den Platzhaltern wie geschrieben; Grenze 4000, siehe oben). Inhaltlich deckungsgleich mit der englischen Fassung; einzelne Sätze sind zur Einhaltung der Grenze kürzer gefasst, ohne Aussage zu streichen:

```text
DankYeeter's Bluetooth Dashboard ist ein Equalizer für Bluetooth-Kopfhörer, der den Klang an Ihr persönliches Hörprofil anpasst, dazu ein Monitor für die Bluetooth-Verbindung. Das Hörprofil stammt aus einem Selbsttest in der App oder aus einem Audiogramm, das Sie eingeben. Die App ist kein Hörgerät und kein Medizinprodukt. Sie stellt nichts fest und behandelt nichts. Bei Sorgen um Ihr Gehör: Fachpraxis aufsuchen.

WAS SIE KANN
• Hörtest: geführt, Ohr für Ohr. Nicht kalibriert: Die Zahlen gelten nur im Vergleich untereinander, mit denselben Kopfhörern.
• Equalizer: Klangkurve pro Ohr aus Ihrem Hörprofil, angewendet auf alles, was das Telefon abspielt.
• Bluetooth-Monitor: Codec, Bitrate, Signalstärke und Unterbrechungen im Zeitverlauf, geführter Vergleich zweier Einstellungen; 24 Stunden Verlauf.

VOR DEM KAUF
• Android 12 oder neuer und Bluetooth-Kopfhörer.
• Die meisten Funktionen brauchen einen Helfer (siehe unten). Einrichtung: Entwickleroptionen und Kabelloses Debugging einschalten, WLAN, einmaliges Koppeln mit dem Code, den Android anzeigt.
• Ohne Helfer läuft die App eingeschränkt: Der Equalizer erreicht nur Player, die ihre Audiositzung melden; der Codec bleibt unbekannt; die Prüfung auf andere Equalizer entfällt.
• Diese Prüfung erkennt nur bekannte Apps: solche mit Equalizer-Bedienfeld und 12 namentlich bekannte Kopfhörer-Begleit-Apps.
• Nur auf Google-Pixel-Telefonen getestet.
• Eine künftige Android-Version kann die Verbindung sperren, die den Helfer startet; die Helfer-Funktionen fielen dann weg.

DER HELFER
Ein kleines Programm in der App. Es läuft mit den Rechten der Android-Debugging-Shell (adb shell), nicht als Root, und lädt keinen Code nach. Die App startet es über Kabelloses Debugging auf dem Telefon selbst und gibt sich darüber die Berechtigung WRITE_SECURE_SETTINGS.
Er führt vier Lesebefehle aus (Statusberichte zu Bluetooth und Audio, die Liste laufender Prozesse) und wenige Änderungen: den Bluetooth-Codec neu aushandeln, HD-Audio für einen Kopfhörer ein- oder ausschalten, Bluetooth neu starten, diese Berechtigung erteilen und sich selbst beenden.
Die App schreibt sechs Android-Einstellungen: drei Bluetooth-Profilversionen, Anzeige namenloser Geräte, absolute Bluetooth-Lautstärke, Kabelloses Debugging. Frühere Werte merkt sie sich und setzt sie auf Wunsch zurück.
Nach einem Neustart des Telefons schaltet die App, wenn der Equalizer eingeschaltet ist, Kabelloses Debugging ein, startet den Helfer und schaltet es wieder aus.
Die volle Liste zeigt die App vor der ersten Verbindung.

BERECHTIGUNGEN
• Bluetooth-Verbindung: verbundene Kopfhörer sehen, Codec- und Verbindungsdaten lesen.
• Bluetooth-Suche (nie für den Standort): Ohrhörer-Signale für Akku- und Trägerstatus.
• Mikrofon: Raumgeräusch vor dem Hörtest; nur im Speicher, nie aufgezeichnet.
• Benachrichtigungen: Equalizer-Status, Stopp-Taste, Eingabe des Kopplungscodes.
• Vordergrunddienst (verbundenes Gerät): hält Equalizer und Messung bei ausgeschaltetem Display am Laufen.
• Start beim Hochfahren: Equalizer wiederherstellen, Helfer starten.
• Audioeinstellungen ändern: Equalizer-Effekt anbinden.
• Netzwerkstatus: nur, ob das WLAN 2,4 oder 5 GHz nutzt.
• Internet: nur für den Debugging-Dienst auf diesem Telefon (127.0.0.1); nichts geht ins Internet.
• Sichere Einstellungen schreiben: die sechs Android-Einstellungen oben; per Helfer erteilt, nicht per Dialog.

DATENSCHUTZ
Kein Konto, keine Werbung, keine Analyse. Nichts verlässt das Telefon. Datenschutzerklärung: <PRIVACY_URL>

PFLEGE
Ich liefere Sicherheits- und Kompatibilitätsupdates für mindestens zwei Jahre nach dem letzten Verkauf. Gebe ich die App auf, nehme ich sie zuerst aus dem Verkauf. Kontakt: <E-MAIL>

HINWEIS
Diese App ist kein Medizinprodukt und stellt keine Diagnose, behandelt, heilt und verhindert keine Krankheit. Bei medizinischen Fragen: Ärztin oder Arzt fragen.
```

## Permission map (C-001 Auflage 8)

The 12 entries of `StoreManifestTest` (`the merged manifest requests exactly
the store permission set`, 29.09.2026), and where each is explained above:

| # | Entry | Listing line (EN) |
|---|---|---|
| 1 | `ACCESS_NETWORK_STATE` | Network state |
| 2 | `BLUETOOTH_CONNECT` | Bluetooth connect |
| 3 | `BLUETOOTH_SCAN` (`neverForLocation`) | Bluetooth scan (never for location) |
| 4 | `FOREGROUND_SERVICE` | Foreground service, connected device (one line for 4 and 5) |
| 5 | `FOREGROUND_SERVICE_CONNECTED_DEVICE` | same line |
| 6 | `INTERNET` | Internet: only to reach the debugging service on this same phone |
| 7 | `MODIFY_AUDIO_SETTINGS` | Change audio settings |
| 8 | `POST_NOTIFICATIONS` | Notifications |
| 9 | `RECEIVE_BOOT_COMPLETED` | Start at boot |
| 10 | `RECORD_AUDIO` | Microphone |
| 11 | `WRITE_SECURE_SETTINGS` | Write secure settings; also named in "The helper" |
| 12 | `dev.dankyeeter.btdashboard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | not listed: a signature-level permission of this app's own package, added to the manifest by `androidx.core:core:1.15.0` (manifest merger report, debug variant, 29.09.2026); it grants nothing on the phone |

Not a permission but part of the same disclosure (AK-22): the equalizer check
sees only apps that declare an equalizer panel and the 12 named companion apps
(`<queries>` in the app manifest); there is no `QUERY_ALL_PACKAGES`. The
listing says so under "Before you buy".

## Block to add only while SR-001 / SR-009 / SR-016 are open

T-006 is the precondition of the submission (`docs/state.md`), so this block
should never ship. If a submission happens before T-006 is done, add to "Before
you buy" (English):

```text
• Known issue: while the helper runs, it writes files with device names and Bluetooth addresses to /data/local/tmp that other apps can read. These files and a running helper stay after you uninstall the app; restart the phone to stop the helper.
```

Deutsch:

```text
• Bekanntes Problem: Solange der Helfer läuft, schreibt er Dateien mit Gerätenamen und Bluetooth-Adressen nach /data/local/tmp, die andere Apps lesen können. Diese Dateien und ein laufender Helfer bleiben nach dem Deinstallieren; das Telefon neu starten beendet den Helfer.
```

## Open points

1. **Title length.** "DankYeeter's Bluetooth Dashboard" has 32 characters. If the Console limit is 30, the listing name has to be shorter than the app's `app_name`; the release-manager decides. No text here depends on the name.
2. **Length with real values.** Both full descriptions are measured with `<PRIVACY_URL>` (13 characters) and `<E-MAIL>` (8). The real values will be longer; after replacing, count again. Current margin: English 135, German 129 characters.
3. **Statements taken from other documents, not verified on a device today:** "Tested only on Google Pixel phones" (`docs/state.md`: Pixel 11 Pro on Android 17; Pixel 8 Pro accepted earlier); "history is kept for 24 hours" (`MonitorEngine.RETENTION_MS`, applied when the monitor starts); "after a restart, if the equalizer is on, the app turns Wireless debugging on, starts the helper and turns it off again" (`BootReceiver`, `HelperAutoStart`, `BtDashboardApplication`; read from code, not run). The German menu name "Kabelloses Debugging" is Android's usual German label, not checked on a device.
4. **Brand names.** The listing names none (no codec brand, no headphone brand, no player). C-001 lists trademark use as not checked; keep it that way in later edits or ask the compliance-agent.
5. **Sentences that must change with the product:** the support promise (C-002 Befund 5, two years after the last sale, entered by the release-manager in the release process), "Tested only on Google Pixel phones", and the whole "helper" section whenever `PrivilegedProtocol.ALLOWED`, a mutating `PrivilegedOperation` or a written setting changes (`HelperDisclosureTest` binds the in-app text, nothing binds this file).
6. **Price and refund terms** are not mentioned; Google states them in the store.
