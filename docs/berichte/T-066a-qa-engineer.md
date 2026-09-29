# T-066a — Geraeteabnahme (qa-engineer), Uebergabe 29.09. 21:06

**STATUS: teilweise** — Zugschwelle (150 Werkzeugaufrufe) erreicht, Lauf beendet. Der director kann mich nicht fortsetzen; dieser Bericht ist die Uebergabe. Kein Code, keine Projektdatei ausser diesem Bericht geaendert. PII: `SSID_A`, `xx:xx:xx:xx:ab:cd`. Scratchpad: `C:\Users\Daniel\AppData\Local\Temp\claude\C--Users-Daniel-Desktop-ClaudeCode-dankyeeters-bluetooth-dashboard\fe4a169d-1153-479f-b46a-877a01e4d9df\scratchpad\T-066\`.

## Urteil je Kriterium (Stand)

| AK | Urteil | Beleg |
|---|---|---|
| AK-4 | **NICHT GEPRUEFT** (Teilbelege s. u.) | Kern (Arm A 15 min Display aus auf Akku, Stop vom Sperrbildschirm, FGS-Verweigerung) nicht begonnen |
| AK-13 | NICHT GEPRUEFT | Kein Vergleich bis zum Ergebnis gelaufen |
| AK-14 | NICHT GEPRUEFT | s. AK-13 |
| AK-20 | **PASS** (Landeort, Neulesen) | Punkt 3 |
| AK-21 | **TEILWEISE**: 2,4 GHz PASS, 5 GHz / nicht lesbar / ohne Berechtigung offen | Punkt 4 |

## Was belegt ist (Quittungen)

**Vorbereitung.** `git log 9ae5bae..HEAD --stat`: zwei Commits, nur `GOAL.md`, `docs/state.md`, `security/findings.md`. `gradlew installDebug` 19:23 am HEAD `2807f43`, versionName 0.3.0. Nach Freigabe des Nutzers (20:16) Helfer per USB gestartet 20:19 (Prozessname `btdash_privileged`, Log "serving as uid 2000 … version 5").

**Punkt 4 (AD-046, AK-21), 2,4 GHz.** `dumpsys wifi` 19:23: Frequenz 2462 MHz. App, Monitoring, MEASURES, "Read at 20:22:14.": "Wi-Fi off — Contradicts what this run needs: Wi-Fi here is on a 2.4 GHz network." (Screenshot `01_measures_24ghz.png`, dort `qa_01_measures_24ghz.png`). `dumpsys package`: keine Standort-Berechtigung angefragt oder gewaehrt (Suche LOCATION: 0 Treffer); `ACCESS_NETWORK_STATE` (install), `ACCESS_LOCAL_NETWORK` (runtime, granted), `INTERNET`. **Offen:** 5 GHz ("nicht pruefbar" mit Grund), "Band nicht lesbar" (WLAN getrennt: `dumpsys wifi` Frequenz -1, Zustand DISCONNECTED ab ca. 20:58 bis zur letzten Kontrolle 21:02, Ursache unbekannt, nicht durch adb; die MEASURES-Zeile in diesem Zustand wurde nicht gelesen), fehlende Berechtigung (Fall nicht herstellbar ohne App-Aenderung).

**Punkt 2 (W4/SR-037).** Sprung "Open Bluetooth settings" 20:26. 12 s nach dem Sprung, keine Messung laufend: `Discovering: false`, `DiscoveryEndMs: 0`, `ScanMode: SCAN_MODE_CONNECTABLE`. Die sechs `Discoverable : true`-Zeilen sind GATT-Advertising von `com.google.android.gms` (Tag `nearby_presence`, Stopp 19:08:00). Keine Suche durch den Sprung.

**Punkt 3 (AD-045, AK-20).** BT-Sprung landet in `com.android.settings/.Settings$ConnectedDeviceDashboardActivity` (Seite "Connected devices"), Rueckkehr per Back: "Read at" 20:22:14 -> 20:28:50. WLAN-Sprung landet in `Settings$WifiSettingsActivity` (Seite "Internet"), Rueckkehr 20:29:40 "Read at 20:29:40". Nicht am Geraet pruefbar: Satz bei nicht aufloesbarem Sprung, Intent-Aktion je Eintrag (Testsache). Screenshots `02`, `03`, `04` (echte SSID, nur fuer den Nutzer), `05`.

**Punkt 7 (Log ohne MAC).** Verbinden des Kopfhoerers 20:48:37: `I/DeviceConnectionWatcher: ACL connect: Noble FoKus Prestige Encor` und `auto-apply for Noble FoKus Prestige Encor: Applied`. Im gesamten App-Log (`logcat -d --pid=<app>`, 33 Zeilen ab 20:33) 0 Treffer fuer ein MAC-Muster `([0-9a-f]{2}:){5}[0-9a-f]{2}`. **PASS** fuer Verbinden. Getrennt-Zeile: die Klasse loggt kein Trennen (`DeviceConnectionWatcher.kt:75,96,110`), also nichts zu pruefen.

**Punkt 5 (F-013), Teilergebnis.** Observation run gestartet ca. 20:32:50, Wiedergabe lief, `cmd bluetooth_manager disable` 20:33:09 ("Success"), Timeline "Playback stopped 20:33:10". Bei getrenntem Kopfhoerer fehlt der Abschnitt "Observation run" und "Compare" auf dem Monitoring-Schirm ganz. Nach dem Wiederverbinden 20:48 steht dort: **"Run ended when the headphone disconnected. 30 s observed."** (Screenshot `07`). Also **nicht** "rate stopped being readable" — bei diesem einen Versuch. Einschraenkung: Ob der Lauf beim Bluetooth-Abschalten (20:33) oder beim Ausschalten des Kopfhoerers durch den Nutzer (nach 20:37) endete, ist nicht unterscheidbar (30 s beobachtet passt zu 20:33). Der geforderte Dump (Lesungsverlauf waehrend `disable`) wurde **nicht** aufgenommen; dazu ist ein weiterer Zyklus noetig (verbundener Kopfhoerer, Sampler `dumpsys bluetooth_manager` im Sekundentakt waehrend `disable`, ausserhalb einer Messung).

**Punkt 6 (SR-040, 0-Byte-Ledger, echter DataStore).** Datei `files/datastore/settings_ledger.preferences_pb`. Nach dem Verbinden 20:48 schrieb Autoapply einen Eintrag (186 Byte): `[{"type":"ldac","priorWish":{…,"ldacQuality":1000}}]`, Banner auf dem Bluetooth-Schirm "Settings changed by the process are still active. 1 setting not put back yet. [Back to before]". Datei per `run-as` auf 0 Byte gekuerzt (App beendet), App neu gestartet: **kein Banner, keine "unlesbar"-Meldung**, Datei bleibt 0 Byte (Screenshot `10`). Danach LDAC-Chip 660 gesetzt (Datei 200 Byte, `priorLive:0`), erneut auf 0 Byte gekuerzt, App neu gestartet, Chip 330 gesetzt: neue Datei (203 Byte) traegt `priorWish ldacQuality 1001, priorLive 1001` — also den vom Prozess selbst gesetzten Wert als "Vorwert" statt 1000. Damit am Geraet belegt, was SR-040 beschreibt: ein 0-Byte-Ledger liest als "nie geschrieben", der Rueckweg geht verloren und die naechste Aufzeichnung nimmt den eigenen Wert als Ausgangswert. Behebung ist beauftragt, nicht Teil hiervon. Aufgeraeumt: Original-Ledger (Sicherung `files/qa_ledger.bak`) zurueckgespielt, "Back to before" ausgefuehrt: "Codec preference for Noble FoKus Prestige Encor put back to LDAC, High quality." (bestaetigt Ausgangswert 1000), Ledger danach 22 Byte.

**Punkt 1 (AD-039), Teilbelege bei USB und Display an.** AD-039-Vorgabe (`ARCHITECTURE.md:2552-2554`): "Arm A auf Akku, Display aus, Musik, 15 min -> TARGET_REACHED ohne READING_GAP; ohne Monitor und ohne Messung keine dumpsys-Aufrufe (Retest F-014)"; Rueckweg falls Luecken: Teil-Wakelock fuer die Armdauer mit Timeout (AD-039 Punkt 6). Am Geraet 20:57 Vergleich (Wi-Fi off) gestartet, TIDAL spielt:
- Ankuendigung im Schirm: "N s of 15 min observed. This keeps counting if you leave this screen, switch apps, or the screen turns off — only stopping the app's process ends it early. Stop it here or from the notification."
- `dumpsys activity services`: `MeasurementService` `isForeground=true`, `types=0x00000010` (connectedDevice), `stopIfKilled=true`, Kanal `measurement_running`, Id 1004; die FGS-Start wurde unter A17 **nicht** verweigert (BLUETOOTH_CONNECT gewaehrt).
- Benachrichtigung: Titel "BT Dashboard — comparing", Text "Arm A: counting 15 min. Ends on its own, or tap Stop.", Flags ONGOING/NO_CLEAR/FOREGROUND_SERVICE, `importance=2` (Screenshots `12`–`15`).
- "Cancel" im Schirm: Dienst weg (nur noch `EqForegroundService`), Benachrichtigung Id 1004 weg (Zaehlung 0), Panel zurueck auf Auswahl.
- Aus dem Panel: mit gepinnten 990 kbps meldet die Anzeige waehrend Arm A "16 of 30 windows lost something" (Screenshot `16`), erwartete Zwischenfaelle laut Text.
**Nicht geprueft:** Display aus auf Akku (Luecken), Stop vom Sperrbildschirm, Stop-Aktion der Benachrichtigung (nicht angetippt), FGS-Verweigerung (z. B. `pm revoke BLUETOOTH_CONNECT`), AK-4-Leerlaufprobe (`grep -c exec /data/local/tmp/btdash_helper.log` vor/nach Ruhe ohne Monitor).

## Befunde (Kandidaten; IDs vergibt der director)

### [P3 | Major | Mittel] Bluetooth-Schirm zeigt "Unknown" und "InvocationTargetException", nachdem Bluetooth aus- und wieder eingeschaltet wurde
**Adressat:** developer (Rohtext an den Nutzer: ui-ux-designer)
**Betroffen:** Karte "BLUETOOTH AUDIO" im Bluetooth-Tab (Codec-Zeile und Detailzeile); Ursache ungeklaert, Hypothese: veralteter A2DP-Proxy oder Reflexionsaufruf nach Neustart des Bluetooth-Stacks (Logzeile 20:36:25 "BT profile A2DP connected to proxy …" entstand bei getrenntem Kopfhoerer; Prozess blieb am Leben).
**Umgebung:** Pixel 11 Pro, Android 17, Helfer laeuft, Noble FoKus (LDAC), App-Prozess ununterbrochen offen seit vor dem Test.
**Reproduktion (1 x beobachtet, 20:48–20:52):**
1. App offen (Bluetooth-Tab zeigt "LDAC 96 kHz · 32 bit"), Kopfhoerer verbunden.
2. `adb shell cmd bluetooth_manager disable`, dann `enable`; Kopfhoerer aus/an, bis er verbunden ist.
3. Bluetooth-Tab: Codec "Unknown", darunter der Text `InvocationTargetException` (Screenshot `09_bt_tab_banner_and_unknown.png`). Monitoring-Tab im selben Zustand zeigte LDAC.
4. App beenden und neu starten: Karte zeigt wieder "LDAC 96 kHz · 32 bit".
**Erwartet:** Nach Bluetooth aus/an liest die Karte wieder den Codec, und Nutzer sehen nie einen Klassennamen einer Ausnahme.
**Tatsaechlich:** Codec "Unknown" und roher Ausnahmename, bis die App neu gestartet wird (mindestens 2 min lang beobachtet).
**Auswirkung:** Falsche Anzeige nach einem alltaeglichen Ablauf; Workaround App-Neustart. Der Ausnahmename verraet Interna (Fehlerpfad-Regel der Checkliste).
**Vorschlag:** Codec-Lesung nach Stack-Neustart neu aufbauen; Fehlertext nicht als Klassenname zeigen. Zweite Reproduktion steht aus (naechster Bluetooth-Zyklus).

## Beobachtungen (ohne Nummer)
- Bei getrenntem Kopfhoerer fehlen "Observation run" und "Compare" auf dem Monitoring-Schirm ganz; der Endgrund eines Laufs ist erst nach dem Wiederverbinden lesbar.
- Waehrend die Benachrichtigungsleiste offen ist, zaehlt die Anzeige im Schirm nicht weiter ("4 s of 15 min observed." blieb ca. 60 s stehen) und springt danach auf den richtigen Wert (3 min); die Zaehlung selbst lief richtig.
- Die Position des Knopfes "Cancel" verschiebt sich beim Wachsen der Zeile "N of M windows lost something" um bis zu 70 px; Antippen ohne frischen UI-Dump traf zweimal daneben.
- Die Kette Autoapply -> Ledger -> Banner "1 setting not put back yet" erscheint bei jedem Verbinden, ohne dass der Nutzer etwas gestellt hat (Prinzip D2).
- WLAN trennte sich ca. 20:58 waehrend Arm A ohne mein Zutun (Ursache unbekannt); der Vergleich lief weiter. Waere ein Fall fuer die Umgebungsbedingungen (AK-14), nicht geprueft.

## Zustand des Geraets bei Abgabe (21:05)
- Pixel per USB, 77 %, Bluetooth an, Noble FoKus verbunden, TIDAL spielt (`PLAYING`), App im Vordergrund auf Monitoring, Helfer laeuft (`btdash_privileged`, gestartet 20:55, Token in `privileged.xml`).
- Vergleich per "Cancel" beendet. Ob der Pin auf 990 kbps zurueckgenommen ist, wurde nicht geprueft (Banner "not put back yet" stand noch).
- **Autoapply fuer den Noble FoKus ist pausiert** (Folge meines "Back to before" im Test, Text "Autoapply paused for Noble FoKus Prestige Encor"); der Nutzer muss es in der App wieder anschalten oder der Director entscheidet.
- LDAC-Stufe war nach dem Rueckweg auf 330 stehen geblieben (Text "the level before this app touched it could not be read", Folge meines manipulierten Ledgers).
- WLAN getrennt seit ca. 20:58 (WLAN-Kachel "Networks available").
- Auf dem Geraet liegen `/sdcard/qa_01..qa_16*.png` (nicht alle gezogen: `qa_08`, `qa_10`, `qa_11` fehlen im Scratchpad) und im App-Verzeichnis `files/qa_ledger.bak` (Testartefakt, 186 Byte). Alles loeschbar.

## Naechster Schritt (Reihenfolge, Handgriffe buendeln)
1. **Handgriff WLAN:** Nutzer verbindet auf 5 GHz; danach Punkt 4: MEASURES lesen (Erwartung "nicht pruefbar" mit Grund), `dumpsys wifi` Frequenz notieren. Dann WLAN-Zustand "nicht lesbar" (getrennt) pruefen, solange es getrennt ist.
2. **F-013 und Zwei-Fach-Repro "Unknown":** Kopfhoerer verbunden, Lauf starten, im Sekundentakt `dumpsys bluetooth_manager | grep -E "A2DP|LDAC|Codec"` waehrend `cmd bluetooth_manager disable` in einem `adb shell`-Aufruf; danach `enable` und Handgriff Kopfhoerer aus/an; Bluetooth-Tab lesen.
3. **Vergleich A2 auf Akku (Punkte 8 und 1):** Wiedergabe an, Vergleich "Wi-Fi off" starten, Ladekabel ab, Display aus (Sperrbildschirm), 15 min Arm A, Stop vom Sperrbildschirm testen oder zu Ende laufen lassen; nach Arm A WLAN wechseln (Handgriff), Arm B, Kabel an, Ergebnis lesen und mit AK-13/AK-14 abgleichen ("Bedingungen", "relative Aussage", "keine Aussage ueber Alltagsqualitaet" am Ergebnis selbst, nicht in einer Fussnote). Drahtloses Debuggen ist aus (`adb_wifi_enabled` 0), unterwegs ist kein adb; Auswertung danach ueber die App-Anzeige und `logcat -d`.
4. Punkt 9: Screenshots fuer INSTRUCT (Sprungknopf, Rueckweg-Banner), P-001-Panel (MEASURES: `01`, `03`, `05`), fehlen noch: INSTRUCT.
5. Aufraeumen: Autoapply wieder an, LDAC-Stufe auf Adaptive, `files/qa_ledger.bak` entfernen.

**Arbeitsweise fuer den naechsten Lauf (Auto-Mode).** Jeder Befehl beginnt mit dem SDK-adb (`C:/Users/Daniel/Desktop/ClaudeCode/android-sdk/platform-tools/adb.exe`), nie Python-Huelle, kein `cd`, keine Kette mit anderen Programmen. Screenshot: `adb shell "screencap -p /sdcard/x.png"` und `adb pull "//sdcard/x.png" <ziel>` (doppelter Schraegstrich gegen MSYS-Pfadumschreibung); UI-Text: `adb shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; grep -o -E '(text|content-desc)=\"[^\"]+\"[^>]*bounds=\"[^\"]+\"' /sdcard/ui.xml"`. Helfer neu starten: `am force-stop`, `pending_token` per `run-as` in `shared_prefs/privileged.xml`, `app_process /system/bin --nice-name=btdash_privileged dev.dankyeeter.btdashboard.privileged.PrivilegedServer <uuid>`; nach mehrfachem `force-stop` zeigt die App "Activate", obwohl der Helfer lebt (zweimal gesehen), dann Helfer neu starten. 150 Werkzeugaufrufe reichten fuer diese Pruefliste nicht; die Liste in zwei Auftraege teilen (Punkte 4/5/Repro, dann Vergleich auf Akku).

## Befund-Log (Vorschlag fuer `qa/findings.md`, IDs vom director)
| Titel | Prio | Adressat | Status | Geprueft |
|---|---|---|---|---|
| Bluetooth-Schirm zeigt "Unknown" und "InvocationTargetException" nach Bluetooth aus/an | P3 | developer | offen (1x reproduziert) | 2026-09-29 |
| F-013 (Endgrund "headphone disconnected" bei einem Versuch korrekt, Dump und Wiederholung offen) | P3 | developer | offen | 2026-09-29 |
| SR-040 am Geraet bestaetigt (0-Byte-Ledger, falscher Vorwert 1001 statt 1000) | — | developer | offen (Behebung beauftragt) | 2026-09-29 |
