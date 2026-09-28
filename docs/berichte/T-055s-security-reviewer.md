# T-055s — Security-Vorlauf AD-039 / AD-041 / AD-042 (security-reviewer, 28.09.)

Vom Director aus der Antwort abgelegt (Rolle ohne Write). Urteil **CONCERNS**:
Kritisch 0 · Hoch 0 · Mittel 1 (SR-032) · Niedrig 1 (SR-033). Alle drei
Entwuerfe mit V1–V21 baubar.

## Befunde

**SR-032 [Mittel] Vorwert vor der Ledger-Sperre gelesen.** Codec: AD-041
legt `priorWish = profile.codecPreference` fest; `profile` liest
`DeviceProfileApplier.kt:81` vor allen Schritten, Sekunden vor dem Codec-Schritt
`:284-302`. HD-Audio (Bestand): `hdAudio.read` bei `:254` ausserhalb der Sperre,
festgehalten erst `:261`. Ablauf: Chip pinnt 990 → Kopfhoerer verbindet, Applier
liest Profil (990) → Nutzer bestaetigt Rueckweg (Profilwunsch `null`, Eintrag
geloescht) → Applier haelt `Ldac(priorWish = 990)` aus altem Abbild fest →
zweiter Rueckweg setzt Profil auf 990 zurueck. Verstoesst gegen
`SettingsLedger.kt:22-23`. Behebung: Vorwert im `record`-Lambda frisch lesen (V2, V3).

**SR-033 [Niedrig] JSONException-Meldungen tragen den Ledger-Inhalt ins
Logcat** (`SettingsLedger.kt:136-140`, `Log.w(..., e)`); `JSONTokener.syntaxError`
haengt den ganzen Eingabetext an (javap, android-all 15). Inhalt: deviceKey
(ungesalzener MAC-Hash, SR-011), Global-Vorwerte, Codecwuensche. Behebung V12.
K-2-Suche: `catch (e: JSONException)` mit Log in `*/src/main` nur `SettingsLedger.kt:139`.

## Bauauflagen T-055b — AD-041 (Codec-Eintrag im Applier)

**V1 — Eintrag nur im schreibenden Zweig, vor dem Schreiben, unter der Sperre.**
`recordIfAbsent(LedgerEntry.Ldac(profile.deviceKey, priorWish, priorLive = null))`
steht innerhalb von `ifRecorded` und nur im Zweig, der `codec.apply` ruft — fuer
jeden Wunsch, auch Nicht-LDAC und `SYSTEM_DEFAULT`. `false` → kein `codec.apply`,
stattdessen `Skipped("codec", PRIOR_NOT_RECORDED)`. `address == null` oder
`!codec.isAvailable()` → kein Eintrag. *Test:* Reihenfolge-Log aus AD-041; Wunsch
`SYSTEM_DEFAULT` → Eintrag vor `apply`; `address = null` → keine Ledger-Aufrufe.

**V2 — `priorWish` frisch aus dem Speicher, unter der Sperre (SR-032).**
Im `record`-Lambda gilt `priorWish = profiles.profileFor(profile.deviceKey)?.codecPreference`,
nicht der Wert aus dem Parameter `profile`. Frisches Lesen `null` → kein Eintrag,
kein Schreiben, `Skipped(PRIOR_NOT_RECORDED)`. `DeviceProfileStore` macht aus
unlesbarem Speicher `null` (vgl. `SettingsRestore.kt:138-141`); wirft
`profileFor` kuenftig, gilt derselbe Skip. *Test:* Test haelt `ledger.lock`
(echter `Mutex`), startet `applyNow(profile(LDAC 990))`, setzt im Fake-Speicher
den Wunsch auf `null`, gibt frei → `priorWish == null` (mit AD-041-Wortlaut rot:
990). `profileFor` → `null` ⇒ 0 × `recordIfAbsent`, 0 × `codec.apply`.

**V3 — HD-Vorwert frisch unter der Sperre (SR-032, Bestand).** `hdAudio.read(address)`
fuer `recordHdAudio` steht im `record`-Lambda. Die Abkuerzung `alreadyRight` darf
auf dem frueheren Lesen bleiben. *Test:* vorher `Known(true)`, Wunsch DISABLE;
Test haelt die Sperre, setzt Fake auf `Known(null)`, gibt frei → `HdAudio(prior =
SYSTEM_DEFAULT)` (heute rot: ENABLE).

**V4 — Keine Live-Lesung, keine Adresse.** `priorLive` in `:core-system` immer
`null`, nie `NONE` (M12). Eintrag traegt nur `deviceKey` (M6); rohe Adresse bleibt
Argument von `codec.apply`. *Test:* Eintrag fuer LDAC 1000 → `priorLive == null`;
gespeichertes JSON enthaelt die Test-MAC nicht.

**V5 — Genau ein Produktions-Applier, mit echtem Ledger.** In `*/src/main` genau
eine Konstruktion `DeviceProfileApplier(` (heute `SystemGraph.kt:278`), sie
uebergibt `ledger =`. *Test:* Quelltext-Scan (erfasst nur woertliche Konstruktionen).

**V6 — Format bleibt, Rundreise verlustfrei.** JSON-Typ `ldac` und Klasse `Ldac`
bleiben, nur KDoc breiter. `putBack` vergleicht mit `!=` (`SettingsRestore.kt:132`).
*Test (Robolectric):* `Ldac(key, CodecPreference("AAC", 48000, 24, 2, 0), null)`
und `SYSTEM_DEFAULT` → `entries()` liefert beide exakt gleich zurueck; altes
T-047-Fixture dekodiert unveraendert.

**V7 — Keine verschachtelte Sperre.** Weder `record`-Lambda noch `codec.apply`
nehmen `ledger.lock` (Mutex nicht reentrant). *Test:* Fake-Ledger mit echtem
`Mutex`; `applyNow` endet innerhalb des `runTest`-Timeouts.

## Bauauflagen T-055b — AD-042 (Verwerfen)

**V8 — Pruefung in derselben Transaktion, gleiche Fehlermenge wie der Lesepfad.**
`discardUnreadable` laeuft unter `ledger.lock`; in `dataStore.edit { }` ersetzt
es den Inhalt durch `"[]"` nur, wenn `decode(prefs[KEY_ENTRIES])` eine
`JSONException` wirft — genau diese, nicht `Exception` (`CancellationException`,
`IllegalStateException` loesen kein Verwerfen aus). Fehlender Schluessel =
lesbar → `false`. `IOException` ausserhalb (inkl. `CorruptionException`) →
`false`. `true` nur bei tatsaechlichem Ersetzen. *Test:* AD-042-Tests; fehlender
Schluessel → `false`, Inhalt unveraendert.

**V9 — Marker nicht als Array lesbar.** `CORRUPT_MARKER` beginnt nach `trimStart()`
nicht mit `[` (`JSONArray(JSONTokener)` ignoriert den Rest: `"[]#corrupt"` liest
sich als leer → M3 gebrochen). *Test (Robolectric):* nach dem Handler ist
`entries()` ein Fehler; Assertion auf das erste Zeichen des Markers.

**V10 — Eine Fabrik, eine Instanz, Name bleibt.** `corruptionHandler` nur am
Ledger-DataStore; Fabrik genau einmal im `lazy` von `SystemGraph` (M6); Dateiname
bleibt `settings_ledger` (M5-Test gruen); Tests bauen ueber dieselbe Fabrik.
*Test:* 16 × `0xFF` in die Datei vor dem ersten Lesen → `entries()` Fehler, nicht
leer; danach `discardUnreadable() == true` und `entries() == success([])`. Zweiter
Test dokumentiert das tatsaechliche Verhalten einer 0-Byte-Datei.

**V11 — Ausloeser nur der Nutzer.** `discardUnreadable(` hat in `src/main` genau
eine Aufrufstelle, `DeviceProfilesViewModel.discardUnreadableLedger()`, nur aus
`confirmButton`, prueft vorher Banner == `Unreadable`. Kein Weg ueber Intent,
`OpenRoute`, Receiver, Dienst, App-Start. Bei `false` sagt der Schirm, dass nichts
verworfen wurde, Banner bleibt (M3, AK-T054-9). *Test:* Grep-Test auf
Aufrufstellen; VM-Test: Banner nicht `Unreadable` → kein Aufruf.

**V12 — Kein Ledger-Inhalt im Log (SR-033).** Neue Fangstellen und der Handler
protokollieren nur Tatsache und Klassenname, ohne Exception-Objekt — auch die
Bestandsstelle `SettingsLedger.kt:136-140`. *Test (Robolectric, `ShadowLog`):*
kaputter Inhalt mit deviceKey `KEY-SENTINEL` → keine Logzeile enthaelt den
Sentinel (heute rot).

## Bauauflagen T-055c — AD-039 (MeasurementService, Stop)

**V13 — Manifest.** Genau `exported="false"`, `foregroundServiceType="connectedDevice"`,
`stopWithTask="true"`; kein `<intent-filter>`, kein `android:process`; keine neue
`uses-permission` (FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE,
POST_NOTIFICATIONS, BLUETOOTH_CONNECT vorhanden); kein `WAKE_LOCK`. *Test:*
XML-Test ueber `app/src/main/AndroidManifest.xml` auf die Attribute und fehlendes
`intent-filter`; kein `WAKE_LOCK` in irgendeinem `src/main`-Manifest.

**V14 — PendingIntents.** Stop: `Intent(ctx, MeasurementService::class.java).setAction(ACTION_STOP)`
ohne Extras, `PendingIntent.getService(..., FLAG_IMMUTABLE [or FLAG_UPDATE_CURRENT])`.
Tippen: expliziter Intent an `MainActivity`, `FLAG_IMMUTABLE`, hoechstens eine
vorhandene `OpenRoute`-Route. Nie `FLAG_MUTABLE`. *Test (Robolectric):* beide
immutable, Komponente und Aktion stimmen, keine Extras.

**V15 — `onStartCommand`.** `intent?.action == ACTION_STOP` → Stop-Pfad; alles
andere (auch `null`, unbekannt) → `held`/DEEP auswerten, beides leer → `stopSelf`.
Extras nie lesen. Rueckgabe `START_NOT_STICKY`. *Test:* `ACTION_STOP` mit fremden
Extras wirkt gleich; Stop ohne laufende Messung → `stopSelf`, kein
`startForeground`, kein Absturz; Rueckgabewert pruefen.

**V16 — Stop schreibt nichts.** Stop-Pfad genau: `ActiveComparison.end(StoppedOutside)`,
`engine.stopDeepCapture()`, `stopForeground(REMOVE)`, `stopSelf()`. Kein Aufruf
von `SettingsRestore`, `LdacTuning.pin/apply`, Ledger, Profilspeicher, Helfer.
Nur deshalb darf Stop vom Sperrbildschirm ohne Entsperren laufen
(`VISIBILITY_PUBLIC`, `setAuthenticationRequired` nicht gesetzt). Bekommt Stop je
ein Schreiben: `setAuthenticationRequired(true)` und neues Review. *Test
(Robolectric):* echter `ComparisonController` in `ARM_A`, zaehlender
Fake-Codec-Controller und Fake-Ledger → nach `ACTION_STOP` 0 Aufrufe,
`phase == CHOOSE`, `ended == StoppedOutside`.

**V17 — Start nur aus einem Tipp.** `MeasurementService.start(` in `src/main` nur
in Tipp-Handlern („Compare“, beide „Watch live“; Director-Ergaenzung: „Start Arm
B“). Nicht in Receivern, `BootReceiver`, `DeviceConnectionWatcher`, `Application`
oder dem Dienst selbst. *Test:* Grep-Test auf Aufrufstellen.

**V18 — Wenn `startForeground` scheitert.** `SecurityException` und
`IllegalStateException` (inkl. `ForegroundServiceStartNotAllowedException`,
Typ-Exceptions ab A14) fangen → `foreground = false`, `stopSelf`, Log ohne
Adresse; in `onDestroy` ebenfalls `foreground = false`. *Test:* injizierbarer
Starter wirft → `foreground.value == false`, Dienst beendet, kein Absturz.

**V19 — Inhalt der Benachrichtigung.** `RunningMeasurement` enthaelt nur Art, Arm,
Zeitmarken — keine Adresse, kein Geraetename, keine Messwerte (M18);
`VISIBILITY_PUBLIC`. *Test:* Titel und Text fuer `ARM_A`, `ARM_B` und DEEP
entsprechen der UI_SPEC-Tabelle; kein Treffer auf ein MAC-Muster.

**V20 — Eigene Kennungen.** Kanal-ID nicht `eq_running_quiet`;
Benachrichtigungs-ID verschieden von EQ-Dienst und Pairing-Benachrichtigung.
*Test:* Konstanten paarweise verschieden.

**V21 — DEEP zeitlich begrenzt, unabhaengig von der Wanduhr.** `startDeepCapture`
begrenzt `durationMs` auf `DEEP_CAPTURE_WINDOW_MS`; der Dienst beendet DEEP
spaetestens `DEEP_CAPTURE_WINDOW_MS` nach beobachtetem Start, gemessen mit
`elapsedRealtime` (Engine-Uhr ist `System.currentTimeMillis`, `MonitorEngine.kt:48`).
*Test:* `startDeepCapture(Long.MAX_VALUE)` → `deepCaptureUntilMs ≤ now + WINDOW`;
Dienst mit Fake-Uhr, die 1 h zurueckspringt → endet nach WINDOW virtueller Zeit.

## Beobachtungen und Director-Entscheide (28.09. 23:0x)

- **B1** `INSTRUCT` ohne Obergrenze — Dienst liefe unbegrenzt, gegen „zeitlich
  begrenzt“ (AK-4). **Director:** Im `INSTRUCT` haelt der Dienst keinen
  Vordergrund; er endet mit Arm A und startet erneut aus dem Tipp „Start Arm B“
  (V17 ergaenzt). Stirbt der Prozess in `INSTRUCT`, gilt der bestehende
  PROCESS_END-Weg. In T-055c umzusetzen.
- **B2** AD-039 `IMPORTANCE_LOW` gegen UI_SPEC „`IMPORTANCE_DEFAULT` oder hoeher“.
  **Director:** `IMPORTANCE_LOW` (Symbol in der Statusleiste, lautlos) plus
  `setOnlyAlertOnce(true)`; UI_SPEC im selben Zug korrigiert.
- **B3** Stop beendet Vergleich und DEEP gemeinsam. **Director:** so lassen
  (ein Dienst, ein Stop); Text der Benachrichtigung nennt, was laeuft.
- **B4** Rest-Schreibung nach Rueckweg (V2 macht den Eintrag korrekt) — keine Auflage.
- **B5** `LdacTuning.pin` liest Live-Stufe vor der Sperre (`:238` vs. `:264`),
  Klasse SR-032 — naechster Zyklus (Beschlossen, nicht beauftragt).
- **B6** Ledger-Sperre ueber Helferaufrufe ohne Timeout (`PrivilegedClient`) —
  naechster Zyklus.
- **B7** `DeviceConnectionWatcher.kt:95` loggt Namen, faellt auf rohe Adresse
  zurueck (Nachbar SR-030) — naechster Zyklus.

**Nicht geprueft:** Geraet (Sperrbildschirm-Aktion, FGS-Verweigerung A17, 0-Byte-
Datei), aktuelles gemergtes Manifest (gelesen vom 23.09.), `ActiveComparison` und
AD-040-Endpfad (entstanden erst in T-055a, `e9b9546`).
