# T-066c — Geraeteabnahme, Fortsetzung (qa-engineer), Zwischenstand 29.09. 21:20

**STATUS: teilweise** — wartet auf Handgriffe des Nutzers (siehe NAECHSTER). Kein Code, keine Projektdatei ausser diesem Bericht geaendert. PII: `SSID_A`, `xx:xx:xx:xx:ab:cd`. Scratchpad: `C:\Users\Daniel\AppData\Local\Temp\claude\C--Users-Daniel-Desktop-ClaudeCode-dankyeeters-bluetooth-dashboard\fe4a169d-1153-479f-b46a-877a01e4d9df\scratchpad\T-066\` (nun `qa_01`..`qa_26`, Auftrag 9: `qa_20`–`qa_26` zeigen Sperrbildschirm und Benachrichtigung).

## Urteil je Kriterium (Stand)

| AK | Urteil | Beleg |
|---|---|---|
| AK-21 | 2,4 GHz PASS (T-066a), **"nicht lesbar" PASS**, 5 GHz offen (Handgriff) | s. Punkt 1 |
| AK-4 Leerlauf | **PASS** (Helfer-Aufrufe, Alarme, Jobs) | s. Punkt 2 |
| AK-4 Stop vom Sperrbildschirm | **PASS mit Vorbehalt** (Frage F-1) | s. Punkt 3 |
| AK-4 Display aus auf Akku, 15 min / AD-039 | NICHT GEPRUEFT (Kabel ab noetig) | offen |
| AK-13, AK-14 | NICHT GEPRUEFT (Ergebnis fehlt) | offen |
| FGS-Verweigerung | offen | folgt nach Entsperren |

## Punkt 1 — AK-21 "nicht lesbar"
`dumpsys wifi` 21:09:42: `Supplicant state: DISCONNECTED`, `Frequency: -1MHz`, `mStaFrequency: 0` (WLAN an, nicht verbunden; Handy im Mobilfunk). App, Monitoring, Abschnitt MEASURES, "Read at 21:10:46." und "Read at 21:14:07.": Zeile "Wi-Fi off" -> **"Not checkable: Wi-Fi is not the active network."**, darunter Sprungknopf "Open Wi-Fi settings". Kein "Contradicts", kein Band geraten, keine Standort-Berechtigung (T-066a). Entspricht "nicht lesbares Band -> nicht pruefbar mit Grund". Der Grund nennt "nicht das aktive Netz", nicht das Band; fuer den Nutzer verstaendlich.
Noch offen: 5 GHz.

## Punkt 2 — AK-4 Leerlaufprobe
Befehl `adb shell "grep -c exec /data/local/tmp/btdash_helper.log"`. Bedingungen: App ueber HOME im Hintergrund (Monitor nicht offen), keine Messung, Kopfhoerer verbunden, TIDAL spielt, `EqForegroundService` laeuft.
- Probe 1: 21:11:33 = 2902, 21:13:13 = 2902 (100 s, Display an, Launcher im Vordergrund).
- Probe 2: nach Ende der Messung 21:16:28: 21:17:04 = 3406, 21:18:54 = 3406 (110 s, **Display aus, gesperrt**).
- `dumpsys alarm`: 0 Eintraege der App; `dumpsys jobscheduler`: kein Job der App (nur Standard-Zaehler "TopAppTimer NOT active"); `dumpsys activity services`: ein Dienst (`EqForegroundService`).
- Gegenprobe (Zaehler lebt): waehrend der zweiminuetigen Messung 21:14–21:16 stieg der Zaehler von 2902 auf 3406.
**PASS** fuer "ohne Monitor und ohne Messung keine dumpsys-Aufrufe" ueber je 100 s; laengere Ruhe nicht gemessen.

## Punkt 3 — Stop vom Sperrbildschirm (Vergleich, abgebrochener Lauf)
Vergleich 21:14 im App-Schirm gestartet (Kabel dran, WLAN getrennt). Benachrichtigung Id 1004: `importance=2`, `vis=PUBLIC`, Aktion "Stop" (`PendingIntent startService`). `input keyevent KEYCODE_SLEEP`: `isKeyguardShowing=true`, `mWakefulness=Dozing`.
- **Beobachtung:** Auf dem Sperrbildschirm und im Schnellbereich davor (Screenshots `qa_20`, `qa_22`) erscheint die Benachrichtigung **nicht**, obwohl `dumpsys notification` sie fuehrt. Ursache am Geraet: `settings get secure lock_screen_show_silent_notifications` = **0** (Einstellung "nur benachrichtigende" auf dem Sperrbildschirm), Kanal ist `IMPORTANCE_LOW` (Absicht laut `ARCHITECTURE.md:2491-2493`: "sichtbar, lautlos ... `VISIBILITY_PUBLIC`"; `MeasurementService.kt:200-207`). Nicht bekannt, ob 0 der Werkszustand oder vom Nutzer gesetzt ist.
- Mit `settings put secure lock_screen_show_silent_notifications 1` (danach auf 0 zurueckgesetzt, geprueft): Symbol im Streifen, Antippen oeffnet die Leiste, Abschnitt "Silent", Eintrag "BT Dashboard — comparing / Arm A: counting 15 min. Ends on its own, or tap Stop." (eingeklappt; Pfeil oeffnet ihn; `qa_24`, `qa_25`). Tippen auf **Stop** 21:16:25: nach 3 s `MeasurementService` weg, Benachrichtigung 1004 weg (Zaehlung 0), Sperrbildschirm blieb gesperrt (`isKeyguardShowing=true`, keine Entsperr-Aufforderung). Stop-Aktion vom Sperrbildschirm funktioniert also (T-055s V16 belegt).
- Ob die App danach im Zustand "abgebrochen" steht und den Pin zuruecknahm: erst nach Entsperren lesbar (naechster Lauf).

## Fragen an den director / ui-ux-designer
- **F-1:** Bei `lock_screen_show_silent_notifications=0` ist die laufende Messung auf dem Sperrbildschirm nicht sichtbar und nicht stoppbar; Stop geht erst nach Entsperren. AK-4 (Ausnahme): "sichtbar angekuendigt und jederzeit abbrechbar". Lesart A: erfuellt, die Ankuendigung steht in der App und die Benachrichtigung ist nach Entsperren stoppbar; das Ausblenden ist Einstellung des Nutzers. Lesart B: "jederzeit" schliesst den gesperrten Zustand ein (AD-039 nennt "Stop vom Sperrbildschirm" als Pruefpunkt); dann verlangt der Kanal `IMPORTANCE_LOW` eine andere Wahl oder der Text im App-Schirm ("Stop it here or from the notification") einen Hinweis. Entscheidung liegt beim director.

## Beobachtungen (ohne Nummer)
- Die Benachrichtigung steht auf dem Sperrbildschirm zuerst eingeklappt; "Stop" erscheint erst nach dem Aufklappen.
- Nach dem Abbruch stehen im Panel weiter "Settings changed by the process are still active. 1 setting not put back yet." und 990 kbps als gepinnter LDAC-Wert (Stand 21:09 und 21:14, vor dem Start dieses Laufs); ob der Abbruch den Pin zuruecknimmt, ist zu klaeren.

## Zustand des Geraets (21:20)
Pixel per USB, 78 %, gesperrt (Fingerabdruck), Bluetooth an, Noble FoKus verbunden, TIDAL spielt, Helfer laeuft, WLAN getrennt, `lock_screen_show_silent_notifications` wieder 0. Testartefakte entfernt: `/sdcard/qa_*.png`, `/sdcard/ui.xml`, `files/qa_ledger.bak`.

## NAECHSTER (Handgriffe)
1. Handy mit Fingerabdruck entsperren, Bildschirm anlassen.
2. WLAN mit einem **5-GHz-Netz** verbinden. Kabel bleibt dran, Kopfhoerer verbunden, Musik laeuft.
Dann: AK-21 bei 5 GHz lesen, Pin-Zustand nach Abbruch pruefen, FGS-Verweigerung (Berechtigung entziehen und wieder geben), danach der lange Vergleich auf Akku (weitere Handgriffe: Kabel ab, Vergleich selbst starten, Display aus, 15 min, WLAN aus, Arm B, Kabel an).
