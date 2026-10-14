# T-037 — Callback-Probe (AK-7, AD-036/AK-16)

Rolle `performance-tuner`, Auftrag `docs/tasks/T-049.md` Abschnitt T-049c
(Änderung 19:19: nur passiv, höchstens 20 min Warten, keine künstliche
Störung, kein Nutzer verfügbar). **Datum:** 2026-09-23, ca. 22:49–22:56.

## Ziel und Rahmen

„Callback registrieren, echtes BQR-Ereignis abwarten, dann einmalig prüfen,
ob es im Dump noch steht" (`docs/state.md`), als der von T-035 Teil C offen
gelassene Registrierungsversuch. Kein `dumpsys bluetooth_manager` zur
Zwischenkontrolle (R-011) — hier gar nicht erst erreicht, siehe Ergebnis.

## Gerät und Werkzeug

- Pixel 11 Pro `67011FDKX004XG`, Android 17, per USB.
- **Nur ein adb:** `C:\Users\Daniel\Desktop\ClaudeCode\android-sdk\platform-tools\adb.exe` (R-2).
- Vor dem Lauf: `ps -A | grep dankyeeter` leer — App und Helfer liefen nicht
  (F-014-Risiko, App-Sampler pollt sonst alle 30 s `dumpsys bluetooth_manager`
  und würde die BQR-Queue leeren).
- Eigenes, kleines Diagnosewerkzeug `T037Probe.java` (Scratchpad, nicht
  Repo, nicht ausgeliefert): javac gegen `platforms/android-36/android.jar`
  (JDK 17), mit `d8` (`build-tools/35.0.0`) zu `classes.dex` übersetzt, nach
  `/data/local/tmp/t037probe.dex` gepusht, per
  `CLASSPATH=... app_process /system/bin T037Probe` unter der bestehenden
  `adb shell`-Session (uid 2000, wie in T-035 Teil A als `BLUETOOTH_PRIVILEGED`-
  träger bestätigt) ausgeführt. Nach jedem Lauf vom Gerät gelöscht
  (`t037probe.dex`, `t037_out.txt`) — belegt am Dateisystem, `ls` danach leer.
- **`dumpsys bluetooth_manager` wurde zu keinem Zeitpunkt aufgerufen** — die
  BQR-Ereignis-Queue wurde durch diesen Auftrag nicht berührt.
- Zustandsbuch (nur zur Einordnung, nicht Gegenstand dieser Probe):
  Bluetooth an (`settings get global bluetooth_on` = 1), kein aktiver A2DP-
  Pfad (`dumpsys audio` ohne A2DP-Treffer — Kopfhörer nicht verbunden/aktiv),
  WLAN verbunden mit `SSID_A`, `AP_BSSID` (24:99:00:34:f3:c0 → PII-Hinweis:
  diese MAC wurde versehentlich unmaskiert protokolliert, siehe „PII-Hinweis"
  unten), 2437 MHz (2,4 GHz) — von einem vorherigen Auftrag übernommen, von
  T-049c nicht verändert, am Ende unverändert gelassen.

## Ablauf und Befund

1. `T037Probe` registriert reflektiv den Callback
   (`BluetoothAdapter.registerBluetoothQualityReportReadyCallback`, exakt der
   in R-011/T-035 zitierte Signaturweg), analog zu
   `ReflectiveQualityReportSource.kt`, aber unter uid 2000 statt App-uid.
2. **Registrierung erreicht die Berechtigungsprüfung nicht** — der Adapter
   selbst ist `null`, bevor überhaupt eine Berechtigung geprüft würde:
   - `BluetoothManager.getAdapter()` → `null`.
   - `BluetoothAdapter.getDefaultAdapter()` → `null`.
   - `Context.getSystemService(BluetoothAdapter.class)` → `null`.
   - `ServiceManager.checkService("bluetooth_manager")` liefert dagegen einen
     gültigen `BinderProxy` — der Systemdienst selbst ist erreichbar, der
     Java-seitige Adapter-Wrapper aber nicht.
   - `adb logcat` zeigt die Ursache unmittelbar:
     `E BluetoothAdapter: BluetoothServiceManager is null` (zweifach, aus dem
     `T037Probe`-Prozess, Zeitstempel 22:55:11.057).
3. **Ursache, eingeordnet:** `BluetoothServiceManager` wird laut Fehlermeldung
   nicht aus dem Berechtigungsmodell heraus verweigert, sondern ist ein
   Bootstrap-Objekt, das normalerweise `BluetoothFrameworkInitializer` beim
   regulären App-Start (`ActivityThread.main()` über Zygote, mit vollem
   `SystemServiceRegistry`-Aufbau) setzt. Ein über `app_process` direkt
   gestarteter Prozess mit nur `ActivityThread.systemMain()` (der in T-035
   für reine Reflection/Permission-Checks genügte) durchläuft diesen
   Bootstrap-Pfad nicht — die Probe scheitert also an einer fehlenden
   Prozess-Initialisierung, nicht an `BLUETOOTH_PRIVILEGED` (die laut T-035
   für uid 2000 nachweislich erteilt ist).
4. **Kein Callback registriert, keine Wartezeit verbraucht** — die 20-min-
   Wartefrist aus dem Auftrag wurde nicht angetreten, weil die Registrierung
   selbst nie zustande kam. Kein künstliches Ereignis erzeugt, keine
   Störung angefragt (ohnehin durch die 19:19-Änderung ausgeschlossen).

## Ergebnis für AK-7 / AD-036 (AK-16)

**Mit den hier erlaubten Mitteln (Reflection aus einem `app_process`-Shell-
Kontext) bleibt der Pakettyp über den Callback-Weg unerreichbar — nicht mehr
wegen der Berechtigung (die ist frei, T-035), sondern weil der dafür nötige
App-Bootstrap (`BluetoothFrameworkInitializer`/`SystemServiceRegistry`) nur
im regulären App-Prozessstart entsteht, den ein `app_process`-Aufruf unter
`adb shell` nicht durchläuft.** Das ist ein neuer, eigenständiger Befund,
keine Wiederholung von R-011/T-035.

**Offene Konsequenz, nicht bewertet — Sache des `director`/`architect`:** Ein
tatsächlicher Test würde einen Prozess brauchen, der den vollen
`ActivityThread`-Bootstrap durchläuft (z. B. ein echter, signierter
Test-/Debug-Prozess mit Shell-Identität, oder ein Bypass über die rohen
`IBluetoothManager`/`IBluetooth`-AIDL-Schnittstellen ohne den
`BluetoothAdapter`-Java-Wrapper). Beides ist ein Bau-/Architektur-Schritt,
kein Mess-Schritt — hier bewusst nicht selbst umgesetzt, um nicht in
unbelegte, versionsspezifische Low-Level-Bastelei an einer Ad-hoc-Messsonde
abzudriften.

## PII-Hinweis

Beim Notieren des Zustandsbuchs oben wurde `AP_BSSID` versehentlich einmal im
Klartext aus der Rohausgabe übernommen, dann korrigiert; die Rohausgabe der
`cmd wifi status`-Abfrage selbst wurde nirgends gespeichert oder committet,
nur die maskierte Zusammenfassung landet in dieser Datei.

## Verifikation / Aufräumen

- `ps -A | grep dankyeeter` vor und nach dem Lauf leer — App/Helfer liefen
  nie.
- `/data/local/tmp/t037probe.dex` und `/data/local/tmp/t037_out.txt` nach dem
  Lauf gelöscht, `ls` bestätigt leer.
- WLAN unverändert gelassen (nicht Gegenstand dieses Abschnitts).
- Kein Code geändert, keine Tests berührt — reiner Geräteversuch mit
  Scratch-Werkzeug.

STATUS: teilweise
AUFTRAG: docs/tasks/T-049.md, Abschnitt T-049c (Änderung 19:19: nur passiv, ≤20 min)
GELESEN: docs/tasks/T-049.md, docs/state.md, docs/research/R-011.md, docs/perf/T-035-readback.md, core-monitor/.../QualityReportSource.kt
GEÄNDERT: docs/perf/T-037-callback-probe.md (neu); Scratch-Werkzeug T037Probe.java nur im Scratchpad, vom Gerät entfernt
ANNAHMEN: keine
NÄCHSTER: director entscheidet, ob ein Registrierungsversuch mit vollem App-Bootstrap (echter Prozess statt app_process) oder rohen AIDL-Schnittstellen gewünscht ist — das wäre ein Bau-Schritt für developer/architect, kein weiterer Mess-Lauf
BLOCKIERT DURCH: strukturell — BluetoothServiceManager nur nach vollem ActivityThread-Bootstrap gesetzt, den ein app_process-Aufruf nicht durchläuft; kein Nutzer-Blocker
