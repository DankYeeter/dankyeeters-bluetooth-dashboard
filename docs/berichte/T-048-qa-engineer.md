# T-048 — Geräteprüfliste Beobachtungslauf und Update (qa-engineer)

**Urteil: FAIL** wegen Befund 1 (P2, AK-4). Sieben Punkte bestanden, einer
mit Befund (3b). Nicht geprüft: 4 und 5b. Ohne Nutzer nicht prüfbar: 3c.
Punkt 10 ist durch Befund 1 widerlegt. Prüfzeit 23.09.2026 19:19–19:53 (Hostuhr).

## Umgebung

- Pixel 11 Pro (`67011FDKX004XG`), Android 17, 1080×2410 px physisch, 420 dpi
  (2,625 px/dp). Kopfhörer Noble FoKus (`xx:xx:xx:xx:37:8f`), LDAC 96 kHz/32 bit,
  Tidal spielt.
- Neuer Stand: Debug-APK aus `bb8fbe2`, eingefroren im Scratchpad
  (`new-bb8fbe2.apk`); Code gleich `2bdc0ec`/`6e7fa44`
  (`git diff --stat 2bdc0ec bb8fbe2 -- ':!docs'` leer). Nicht neu gebaut nach `3696f94`.
- Alter Stand für Punkt 8: Debug-APK aus Klon `C:\t048` auf `9266f2e`, dem
  letzten Commit mit `monitor.db` Version 3 (Tag `v0.2.0` trägt Version 1,
  `git grep "version = [0-9]" v0.2.0`). versionCode 3 in beiden.
- WLAN war an (`settings get global wifi_on` = `1`, 18:05), entgegen der Prämisse;
  laut Director für T-048 ohne Belang.
- **Helfer-Start abweichend vom App-Weg:** Die App startet den Helfer über
  Wireless Debugging, das bei gestecktem Kabel aus ist; ohne Helfer sperrt die
  App alles hinter „Activate“. Ich habe ein UUID als `pending_token` in
  `shared_prefs/privileged.xml` gelegt (per `run-as`) und den Befehl aus
  `PrivilegedBootstrap.shellCommand` über USB-adb ausgeführt
  (`scratchpad/T-048/helper.sh`). Log: „serving as uid 2000 … version 5“.
- Berechtigungen `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `POST_NOTIFICATIONS`
  per `pm grant` erteilt (Einrichtungsschritte 1 und 3).

## Urteil je Punkt

| # | Punkt | Urteil | Beleg |
|---|---|---|---|
| 1 | Lauf ≥ 2 min, drei Kennzahlen mit Fenster, Kadenz 1/5 s | PASS | Kadenz 2 s → 1 s → 5 s nach 56 s. Bei 2 min: „Lowest reading 396 kbps, 2 min observed.“, „At or above 660 kbps 43 % of the time, 2 min observed.“, „Time at each step, 2 min observed:“ mit 660 kbps 58 s, 492 kbps 51 s, moving 25 s (`p1-run.txt`, `p1-run.png`). Echte ABR-Werte 330/396/492/660. |
| 2 | Pause/Weiter; Stopp in der Pause | PASS | 26 s Pause (`MEDIA_PAUSE`, Sitzung `PAUSED(2)`): Lauf läuft weiter, danach „Not observed for 28 s in 2 breaks; that time is in no figure here.“ Stopp nach 6 s Pause: „Run stopped. 3 min observed.“, Kennzahlen bleiben nach Weiterspielen stehen (`p2-stop.txt`). |
| 3a | Helfer beendet | PASS (Absicht) | `kill` auf `btdash_privileged` während des Laufs: die App wechselt nach 15 s auf „Activate“, der Monitor ist verlassen, der Lauf verworfen (`p3-helper.png`). Entspricht dem Gate in `BtDashboardApp.kt:129-133` („covers the helper dying mid-session“). |
| 3b | Bluetooth aus | PASS mit Befund 2 | `cmd bluetooth_manager disable` im Lauf: Live-Panel samt Laufabschnitt verschwindet, solange BT aus ist (`p3-bt-top.png`). Nach `enable` erscheint „Run ended when the rate stopped being readable. 6 s observed.“, nicht „… headphone disconnected.“ `dumpsys bluetooth_manager` bei BT aus: `State: BLE_ON`, `ConnectionState: STATE_DISCONNECTED`. Ob der Dump dann einen Geräteblock mit `isConnected=false` trägt, habe ich nicht erfasst. |
| 3c | Kopfhörer aus | nicht prüfbar ohne Nutzer | Director 19:2x: keine Handgriffe mehr. |
| 4 | Codec LDAC→AAC im Lauf, danach Start | nicht geprüft | Ein Codec-Wechsel geht nur über eine andere Ansicht (App-Tab oder Entwickleroptionen); beides verlässt den Monitor. Aus Zeitgründen nicht mehr versucht. |
| 5a | HIGH→MID bei gepinntem Lauf (M12) | PASS | 990 gepinnt, Start, „Collecting — 6 readings, 10 s“, Tipp 660: „Run ended when the LDAC quality changed. 16 s observed.“ Ebenso MID→HIGH (24 s) und ABR→990 (22 s). |
| 5b | Sample-Rate im Lauf | nicht geprüft | Zeit. |
| 6 | Starthinweis über Prozessneustart | PASS | Erster Tap: Dialog „This screen must stay open“ (`p6-dialog.png`). „Not now“: kein Lauf. `am force-stop`, Neustart, Tap: Dialog erneut. „Continue“: Lauf startet. Zweiter Start in derselben Sitzung ohne Dialog. `force-stop`, Neustart, Tap: Lauf startet direkt („Collecting — 4 readings, 6 s“), Zähler der Dialogtexte im Dump 0. |
| 7 | Chipgrösse ≥ 48 dp | PASS | Klickbare Knoten im `uiautomator`-Dump: Start `[95,1178][276,1304]` = 126 px = 48,0 dp hoch, 69 dp breit; Kadenzchips 126 × 145 px = 48 × 55 dp; Hilfeknopf 126 × 126 px = 48 × 48 dp. Android, 420 dpi, physische Pixel. |
| 8 | Update `monitor.db` v3→v4, Verlauf, Profile | PASS | Vorher `user_version 3`, `codec_mode_signatures` vorhanden, 1 `link_samples`, 2 `monitor_events`. Nach `install -r`: `user_version 4`, Tabelle weg, das alte Sample (`timestamp_ms 1790184208411`) und beide Ereignisse noch da (`db-old3`, `db-new1`). Alle sechs DataStore-Dateien byte-gleich (`cmp`), darunter `compensation_profiles` mit Profil „Test“. EQ-Tab zeigt nach dem Update weiter „Based on two runs — one more makes it steady.“ Das Profil „Test“ habe ich in der Oberfläche nicht aufgesucht. |
| 9 | Icons Navigationsleiste, Hilfeknöpfe | PASS (Sicht) | `p3-bt-top.png`: fünf Tab-Icons und „?“-Hilfeknöpfe gerendert, keine Platzhalter. |
| 10 | AK-4 ohne offenen Monitor | FAIL (Befund 1) | App im Hintergrund (Launcher oben). `dumpsys alarm`: 0 Treffer für das Paket. `jobscheduler`: nur Statuszeilen, kein Job. In den ersten 60 s nach dem Verlassen 34 Helferaufrufe (`dumpsys bluetooth_manager`/`audio`/`media.audio_flinger`) und 2,82 s App-CPU. Danach 2 Aufrufe in 46 s, 0,54 s CPU. `EqForegroundService` läuft (EQ auf Tidal). Welche Aufrufe dem EQ-Dienst gehören, habe ich nicht zugeordnet, siehe Offene Fragen. |
| 11 | Pause > 2 min beendet Lauf; kürzer läuft weiter | PASS | Start, 21 s gelesen, `MEDIA_PAUSE` 19:31:42, nach 100 s noch „Collecting — 22 readings, 21 s observed.“, nach 143 s „Run ended after 2 min of paused playback. 21 s observed.“ (`p11.txt`). Kürzere Pause: Punkt 2. |

## Befunde

### 1 — [P2 | Major | Hoch] Live-Abfrage läuft nach Verlassen des Monitors weiter; ein laufender Lauf zählt im Hintergrund ohne Lücke weiter
**Adressat:** developer; Spec-Frage an director (siehe unten)
**Betroffen:** `MonitorViewModel.kt:186,199` (`WhileSubscribed(LIVE_STOP_TIMEOUT_MS)`, `LIVE_STOP_TIMEOUT_MS = 3_000L` in Z. 420), `ObservationRunController`-Einspeisung Z. 178
**Umgebung:** wie oben, EQ-Vordergrunddienst aktiv, Tidal spielt, Kadenz 2 s.
**Reproduktion:** 1. Monitor öffnen, Lauf starten. 2. HOME (Launcher als `topResumedActivity` bestätigt). 3. `grep -c exec /data/local/tmp/btdash_helper.log` vor und nach 25 s vergleichen. 4. App wieder öffnen.
**Erwartet:** `UI_SPEC.md` T-039, Zustandstabelle: „App im Hintergrund, anderer Tab | Poller stoppt (`WhileSubscribed`) | **Lücke** …“. Der Hinweistext sagt: „A run counts only while this screen is open; leaving it discards the run“. GOAL AK-4 verlangt ohne offenen Monitor keine wiederkehrende Arbeit am Audiopfad.
**Tatsächlich:**
- 19:52:22–19:52:47 im Hintergrund: 43 Helferaufrufe (1276→1319), jeweils `dumpsys bluetooth_manager`, `audio` und `media.audio_flinger`. Der Lauf zählte weiter von 48 auf 56 Lesungen, ohne Lückenzeile.
- ~19:51: 10 s HOME, Lesungen 15 → 24, „28 s“ → „46 s observed“, keine Lücke.
- 4 s auf dem EQ-Tab: der Lauf lief weiter, 3 → 9 Lesungen.
- Ohne laufenden Lauf, 19:46:07–19:47:07 nach Bluetooth-Tab und HOME: 34 Helferaufrufe und 2,82 s App-CPU. Danach nur noch ~2 Aufrufe je 46 s.
- Ein beendeter Lauf blieb nach Tabwechsel, HOME und Rückkehr sichtbar.
**Analyse (Hypothese):** Irgendetwas hält die geteilte Live-Abfrage länger als 3 s abonniert, etwa ein zweiter Sammler oder die Lebenszyklusbindung. Wer es ist, habe ich nicht ermittelt.
**Auswirkung:** Im Hintergrund laufen Abfragen am Audiopfad (AK-4). Der Lauf zählt Zeit, in der der Monitor nicht offen war, entgegen dem Hinweistext und der Spec-Tabelle.
**Vorschlag:** Den Abonnenten finden, der die Abfrage hält, und die Stoppzeit nach HOME am Gerät messen. Einen Test ergänzen, der nach Wegfall des letzten Sammlers das Ende der Abfrage nach `LIVE_STOP_TIMEOUT_MS` prüft.
**Spec-Frage an director:** Gilt ein Tabwechsel als „Verlassen des Bildschirms“? Laut Tabelle ergibt er nur eine Lücke, laut Hinweistext verwirft er den Lauf. Die gemessene Abweichung besteht unabhängig davon.

### 2 — [P3 | Minor | Mittel] Bluetooth aus beendet den Lauf mit „rate stopped being readable“ statt „headphone disconnected“
**Adressat:** developer
**Betroffen:** `core-monitor/.../link/live/ObservationRun.kt:341-349` (`endAgainst`)
**Umgebung:** Gerät wie oben, LDAC gepinnt 660, Tidal spielt, Helfer läuft.
**Reproduktion:** 1. Lauf starten. 2. `adb shell cmd bluetooth_manager disable`. 3. Nach ~30 s `enable`, Monitor ansehen.
**Erwartet:** Grundzeile „Run ended when the headphone disconnected. {T} observed.“ (`RunEnd.DISCONNECTED`, AK-T039-9: je Grund eine eigene Zeile).
**Tatsächlich:** „Run ended when the rate stopped being readable. 6 s observed.“ Einmal reproduziert.
**Analyse (Hypothese):** Beim Abschalten kommt zuerst ein Snapshot, in dem das Gerät noch verbunden ist und spielt, die Rate aber nicht mehr gemessen wird. `RATE_UNREADABLE` greift, bevor ein Snapshot mit `device == null`/`!isConnected` eintrifft.
**Auswirkung:** Der Nutzer liest einen Build-/Stack-Grund, wo die Verbindung weg war.
**Vorschlag:** `RATE_UNREADABLE` erst nach Bestätigung durch einen weiteren Snapshot vergeben, oder beim Übergang zu „nicht verbunden“ den Grund nachträglich auf `DISCONNECTED` setzen. Einen Test mit der Snapshotfolge „verbunden, Rate weg“ → „getrennt“ ergänzen.

### Bestätigung neu-C (T-043a) am Gerät
Solange Bluetooth aus ist, verschwindet der beendete Lauf ganz, keine Grundzeile, keine Zahlen (`p3-bt-top.png`). Mit BT an kehrt er zurück. Das deckt sich mit neu-C („Leerer Snapshot blendet beendeten Lauf aus“). Kein neuer Befund.

## Beobachtungen (kein Befund)
- Nach `am force-stop` und Neustart verband sich die App einmal mit dem laufenden Helfer (19:2x) und zweimal nicht (19:34, zweimal hintereinander): „Activate“, obwohl `btdash_privileged` lief. Das kann an meinem abweichenden Helferstart mit eingeschleustem Token liegen, deshalb ist es kein Befund.

## Offene Fragen
- **An director/developer (AK-4):** Gehören die ~2 Helferaufrufe je 46 s im Ruhezustand zum inventarisierten EQ-Dienst? Nicht zugeordnet.

## Nicht getestet
Punkt 4 und 5b aus Zeitgründen. 3c braucht den Nutzer. Den Geräteblock im Dump bei BT aus habe ich nicht erfasst. Befunde A/B aus T-043a sind am Gerät nicht nachgeprüft, weil sie an Punkt 4 hängen.

## Zustand für den performance-tuner
App `bb8fbe2` installiert. Helfer läuft (PID 25341, über USB gestartet, siehe oben). Monitor geschlossen, Launcher im Vordergrund. Tidal spielt, LDAC wieder ABR („Adaptive — 492 kbps right now (measured)“). Bluetooth an. Keine eigenen Skripte laufen. Hinweis zu Befund 1: Nach dem Schliessen des Monitors dauert es bis zu ~60 s, bis Ruhe herrscht.

## QA-Log (Vorschlag, IDs vergibt der Director)

| ID | Titel | Prio | Adressat | Status | Datum | Auftrag |
|---|---|---|---|---|---|---|
| neu | Live-Abfrage läuft im Hintergrund weiter, Lauf zählt ohne Lücke (AK-4, Spec-Tabelle T-039) | P2 | developer, director (Spec-Frage) | offen | 2026-09-23 | T-048 |
| neu | BT aus → Grundzeile „rate stopped being readable“ statt „headphone disconnected“ (`ObservationRun.kt:344-347`) | P3 | developer | offen | 2026-09-23 | T-048 |
| neu-C (T-043a) | Leerer Snapshot blendet beendeten Lauf aus | P4 | developer | offen, am Gerät bestätigt | 2026-09-23 | T-048 |
