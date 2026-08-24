# Handover — 23./24. August 2026

Stand am Ende der Sitzung. Alles auf Branch `backup/wip-20260822`, letzter Commit
`9d1574a`; die Arbeit dieser Sitzung ist **nicht committet** (siehe unten).
`master` ist unberührt. 930 Tests grün, App läuft.

---

## Das Ergebnis der Sitzung

**Die ADB-Kopplung funktioniert.** Das war wochenlang die Wand, und die Ursache
war weder in SPAKE2 noch in der Kryptografie:

> Das SPAKE2-Passwort ist nicht der sechsstellige Code, sondern
> `Code ‖ 64 Byte TLS-Exporter-Material` (RFC 5705). `adb` bindet den Austausch
> damit an genau diesen TLS-Kanal — `pairing_connection.cpp`, Zeilen 191–199.

Deshalb war jede Schicht einzeln korrekt und der Schlüssel trotzdem
verschieden. Kein Parametertest hätte das gefunden, weil der Fehler in keinem
Parameter lag. Gegenprüfung an der Originalquelle (BoringSSL `spake25519.c`,
AOSP `pairing_auth.cpp`, `aes_128_gcm.cpp`, `tls_connection.cpp`) hat außerdem
bestätigt: Rolle, Masken, Namensterminatoren, Skalar-Konvention,
Transkript-Reihenfolge, HKDF- und AES-Parameter waren **alle schon richtig**.

Zwei Details, die leicht wieder verloren gehen:

- Das Exporter-Label ist `adb-label` mit `sizeof(...)` = **10 Byte inklusive
  NUL**, nicht 9.
- Die Plattform-Conscrypt hat `exportKeyingMaterial`, gibt es aber nicht her:
  `domain=core-platform, api=blocked`, am Gerät dreimal gemessen, dreimal
  `denied`. Deshalb bringt die App Conscrypt jetzt selbst mit und **baut den
  Kopplungs-Socket mit diesem Provider** — sonst rückt der Exporter nichts
  heraus.

Der Helfer startet danach vom Telefon aus, ohne PC. Er starb anfangs 36 ms nach
dem Start, weil adbd die Prozessgruppe der Shell abräumte, während die VM noch
hochfuhr; `setsid` plus drei Sekunden Nachlauf lösen das.

---

## Offen: nur noch der Test

Alles Gebaute ist **ungetestet am Gerät** — bewusst, auf Daniels Ansage
("bau die fehlenden funktionen. der test passiert danach").

### Der Test, der die Verbindung kappt

Der Beweis der Automatik ist ein **Neustart**. Dabei geht die adb-Verbindung
verloren, weil sie über genau das Wireless Debugging läuft, das die App dann
selbst öffnet und wieder schließt. Danach muss es von Hand wieder eingeschaltet
werden, um das Gerät zu erreichen.

Reihenfolge, die am wenigsten kostet:

1. **Erst ohne Neustart**: App starten, Helfer kommt hoch, prüfen ob
   `WRITE_SECURE_SETTINGS` danach gewährt ist
   (`dumpsys package dev.dankyeeter.btdashboard | grep 'WRITE_SECURE_SETTINGS: granted'`).
   Das geht ohne Verbindungsverlust, weil Wireless Debugging von Hand an ist und
   die Abschalt-Logik nur schließt, was die App selbst geöffnet hat.
2. **Dann prüfen, dass nur ein Helfer startet** (`ps -Ao pid,args | grep btdash_privileged`)
   und im Log genau ein `privileged helper connected` steht.
3. **Erst dann der Neustart.**

### Woran es zuletzt lag, und was daraus wurde

Die Vergabe hing am Ende von `HelperAutoStart`. Ein Helfer erreichte die App
aber auf einem anderen Weg zuerst, worauf die Aktivierung über
`if (isConnected) true` aussteigt — die Vergabe wurde jedes Mal übersprungen,
während ringsum alles Erfolg meldete.

Sie hängt jetzt an der **Ankunft eines Helfers** statt an einem Startweg: ein
Collector auf `PrivilegedConnection.service` in `BtDashboardApplication`. Damit
sind alle Wege abgedeckt — Aktivierung, ein überlebender Helfer der sich nach
einem App-Neustart wieder meldet, ein Reconnect. In `HelperAutoStart` steht der
Aufruf zusätzlich, dort aber mit anderer Aufgabe: das Abschalten von Wireless
Debugging in der nächsten Zeile braucht die Berechtigung sofort, und der
Collector ist asynchron.

---

## Diese Sitzung fertig geworden

- **Harter Gate** — ohne Helfer zeigt die App nur den Activate-Knopf, auch wenn
  er im Betrieb stirbt. Der Setup-Wizard steht bewusst *davor*: dort wird die
  Benachrichtigungs-Berechtigung erteilt, ohne die die Kopplung den Code nicht
  entgegennehmen kann.
- **Prompts raus** — „EQ not attached", „EQ is off"-Status, der lange
  Bluetooth-Erklärtext. Von der App bleibt genau eine Meldung: die
  Foreground-Service-Notification, stumm, ohne Text, vor dem Sperrbildschirm
  verborgen. Android verlangt sie, sie lässt sich nicht entfernen.
- **Veralteter Boot-Text** — schickte für einen ADB-Befehl an den PC. Stimmt
  seit dieser Sitzung nicht mehr.
- **Schrittanleitung einmal definiert** (`ActivationSteps.kt`), von beiden
  Notifications und dem Activate-Screen benutzt. Zugeklappt eine Zeile,
  aufgeklappt vier Schritte inklusive Entwickleroptionen.
- **`pm grant` als eigene, als mutierend deklarierte Operation** — nicht als
  Whitelist-Eintrag. `exec` ist als nicht-mutierend klassifiziert; ein Befehl,
  der die Rechte der App dauerhaft erweitert, hätte dort die Lese-Tür benutzt.
  Paket und Berechtigung sind im Helfer festverdrahtet, nicht Parameter.
- **Aktivierung serialisiert** — ein prozessweiter `Mutex`. Zwei gleichzeitige
  Aufrufer starteten je einen Helfer; der zweite verwarf den ersten sofort.
- **Boot-Restore läuft einmal pro Prozess.** Der Filter listet `BOOT_COMPLETED`
  *und* `LOCKED_BOOT_COMPLETED`, und ein Neustart liefert beide aus — der
  Restore lief zweimal. Beide Aktionen bleiben im Filter (welche ankommt, hängt
  an der Direct-Boot-Fähigkeit des Receivers); die zweite wird ignoriert.
- **Kein WLAN wird erkannt und benannt** (`Outcome.NoWifi`). Vorher lief die
  Aktivierung ohne WLAN in eine Portsuche, die nichts finden konnte — ein
  langsamer, unerklärter Fehlschlag statt eines Einzeilers.

### Drei Fehler, die Ursachen waren, keine Symptome

1. **Verlorener Rückgabewert durch Thread-Sichtbarkeit.** `earlyVerdict` wurde
   in den Portsuche-Callbacks auf anderen Threads gesetzt und ohne
   Synchronisation gelesen — die Zuweisung ging verloren
   (`verdict=null endpoints=2` am Gerät). Erklärte auch den Doppelstart des
   Helfers. Jetzt `AtomicReference`, und genau ein Endpunkt darf ihn starten.
2. **`Started` hieß „Kommando abgesetzt", nicht „Helfer läuft".** Die App
   meldete Erfolg an einen Nutzer mit totem EQ. Sie wartet jetzt auf den Binder
   und nennt im Fehlerfall die Logdatei.
3. **Literale NUL-Bytes in Kotlin-Quellen** brechen jetzt den Build
   (`verifyNoNulBytes` in `build.gradle.kts`, verifiziert). Sie kompilieren
   sonst klaglos und machen Dateien für git und grep binär.

Außerdem: Die „EQ is off"-Notification blieb nach erfolgreicher Aktivierung
stehen, wenn man über die App statt über die Notification aktivierte. Sie
verschwindet jetzt, sobald der Helfer sich meldet.

---

## Backlog

- **Play Store** — Data-Safety-Formular, `QUERY_ALL_PACKAGES` entkoppeln,
  FGS-Typen deklarieren, INTERNET begründen.
  *Risiko bleibt bestehen:* Shizuku, das technisch fast identische Vorbild, wird
  nicht über Google Play vertrieben. Entscheidung ist gefallen: wir versuchen
  es, im Zweifel GitHub.
- ~~ABI-Split~~ — **nicht nötig.** Ein App Bundle liefert die native Bibliothek
  pro Gerät aus, und für die GitHub-Variante ist ein universelles APK gewollt.
- **Commit-Entscheidung** — die ganze Sitzung liegt uncommittet auf
  `backup/wip-20260822`.

---

## Gerätezustand

- Pixel 8 Pro, verbunden über `192.168.178.22:44803`.
- `adb` liegt unter `ClaudeCode/android-sdk/platform-tools`, **nicht** im PATH.
- Wireless Debugging ist **von Hand an** — deshalb greift die Abschalt-Logik
  derzeit nicht (sie schließt nur, was die App selbst geöffnet hat).
- `WRITE_SECURE_SETTINGS` ist **entzogen** (absichtlich, für den Test).
- Helfer-Ausgabe: `/data/local/tmp/btdash_helper.log`.
- USB-Kabel muss abgesteckt bleiben: es schaltet Wireless Debugging binnen
  Millisekunden ab.
- Bei langen Wartezeiten sperrt sich der Bildschirm — Tipp-Befehle laufen dann
  ins Leere, ohne zu scheitern.
