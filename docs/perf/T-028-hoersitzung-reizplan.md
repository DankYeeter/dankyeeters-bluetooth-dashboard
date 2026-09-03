# T-028 — Blinde Hörsitzung, Reizplan und Protokoll

Rolle: `performance-tuner`. Auftrag T-028: Reproduzierbarkeit von T-027 Phase 4
Zelle 1 (1 paralleler Strom) prüfen, parallel dazu Grundlage für die
Hörbarkeitsfrage legen (die der Director in einer eigenen Datei führt, hier
steht dazu nichts). Grundlage: `docs/perf/T-027-messung-24ghz.md` (Mechanik,
`cell.sh`, Sink-Server), `docs/perf/T-027-hoereindruck.md` (Anforderungen an
die Sitzung: aktiv, blind, kurze wiederholte Abschnitte).

**Diese Sitzung ist NICHT gelaufen.** Vor Abschnitt 1 wurde ein
Vorbedingungs-Bruch festgestellt, der die gesamte Methodik ungültig gemacht
hätte, wäre er nicht gefunden worden. Abgebrochen, gemeldet, kein einziger
Abschnitt gestartet.

---

## 0. Setup, wie durchgeführt

- Gerät: Pixel 11 Pro `67011FDKX004XG`, per Kabel, ausschließlich
  `~/tools/android-sdk/platform-tools/adb.exe` (R-2). `adb kill-server` /
  `start-server` einmal zu Sessionbeginn (20:06 Host-Zeit), danach
  durchgehend derselbe Server. `C:\RSL\2.1HF5\adb\adb.exe` zu keinem
  Zeitpunkt aufgerufen.
- Uhren-Kalibrierung (Host `date` unmittelbar vor/nach `adb shell date`):
  Host 20:07:01,06 ↔ Gerät 20:08:32 → **Versatz ≈ +91 s, Gerät vor Host**
  (deckt sich mit der im Auftrag genannten Größenordnung von ~89 s).
- Vollständiger `dumpsys bluetooth_manager`-Read-back (20:07 Host- /
  ~20:08 Gerätezeit, Datei nur Scratchpad, `t028_preflight_full.txt`):
  **Dreifachprüfung bestanden** — `mCodecSpecific1` = 1001, `LDAC quality
  mode` = MID, `LDAC transmission bitrate` = 660 Kbps, ABR-Zeilen abwesend
  (0 Treffer für „adaptive"). `ConnectionState` (A2DP) STATE_CONNECTED,
  `mIsPlaying` true. Ausgangsstand der Zähler für spätere Deltas:
  **`Counts (flushed/dropped/dropouts)` = 1 / 876 / 38**, `underflow` 2264.
- `cell.sh` unter `/data/local/tmp/btperf/t027p2/cell.sh` unverändert
  wiederverwendet (aus T-027 Phase 4, geprüft per `cat`, Inhalt identisch zur
  in der Schwesterdatei dokumentierten Fassung).
- Host-Sink-Server neu geschrieben (Vorlauf-Kopie aus T-027 lag im
  Scratchpad der damaligen Session, die dieser Session nicht zugänglich
  ist) — eigenständige, funktional äquivalente Fassung
  (`t028_sink_server.py`, nur Scratchpad), Port 5502 (neu gewählt, um mit
  keinem toten Rest von Port 5501 zu kollidieren). Host-IP frisch geprüft:
  **IP_1**, Firewall-Profil **Private**. Lokaler Smoke-Test
  (`127.0.0.1:5502`) erfolgreich — Server-Prozess selbst funktionsfähig.
- Zufällige Abschnittsreihenfolge **bereits gewürfelt** (Python
  `random.shuffle`, Bedingung: 5×STIM, 5×KONTROLLE, keine Bedingung mehr als
  zweimal hintereinander — bei der Erzeugung geprüft, nicht nur erhofft):

  | # | Bedingung |
  |---|---|
  | 1 | KONTROLLE |
  | 2 | STIM |
  | 3 | KONTROLLE |
  | 4 | KONTROLLE |
  | 5 | STIM |
  | 6 | KONTROLLE |
  | 7 | STIM |
  | 8 | KONTROLLE |
  | 9 | STIM |
  | 10 | STIM |

  Diese Reihenfolge ist **nicht verworfen** — sie steht für den nächsten
  Anlauf bereit, sobald die Vorbedingung unten wiederhergestellt ist. Kein
  neuer Wurf nötig.

## 1. Smoke-Test vor Abschnitt 1 — Vorbedingung gebrochen

Vor dem ersten Abschnitt wurde, wie in T-027 Phase 4 üblich, der Stimulus-Pfad
mit einer kurzen Testzelle geprüft (`t028_smoke`, 1 Strom, 3 s, gegen den
neuen Sink-Server):

```
adb shell "umask 077; sh /data/local/tmp/btperf/t027p2/cell.sh t028_smoke 1 3 IP_1 5502"
→ Terminated / CELL t028_smoke COMPLETE
```

Der Sink-Server verzeichnete **`active_conns=0` durchgehend**, keine einzige
Verbindung kam an. Diagnose, Schritt für Schritt:

1. Lokaler Verbindungstest (`127.0.0.1:5502` vom Host selbst) — **erfolgreich**.
   Der Sink-Server-Prozess ist funktionsfähig, das Problem liegt nicht dort.
2. `ping` vom Gerät auf `IP_1` — **100 % Paketverlust, 0 von 3
   Antworten.**
3. Live-`dumpsys wifi`-Read-back (nicht nur historisches Eventlog): **„Wi-Fi
   is enabled"**, aber `dumpsys connectivity` zeigt **keinen WIFI-Transport
   unter den aktiven Netzwerken** — nur zwei `MOBILE[LTE]`-Netzwerkagenten
   (IMS- und Web-APN). Das historische Eventlog bestätigt den Zeitpunkt:

   ```
   rec[94]: time=09-02 19:48:49.107 ... SUPPLICANT_STATE_CHANGE_EVENT
            ssid: "SSID_A" bssid: 00:00:00:00:00:00 ... state: DISCONNECTED
   ```

   Danach kein einziger Reconnect-Versuch im Log. `SupplicantStateTracker`
   aktuell: `Supplicant state: DISCONNECTED`, `RSSI: -127`, `IP: null`.

**Befund: Das Gerät hat sich um 19:48:49 Gerätezeit von der SSID „SSID_A"
getrennt und ist bis zum Zeitpunkt dieser Prüfung (~20:08 Gerätezeit, ~20
Minuten später) nicht wieder verbunden — WLAN-Radio an, aber keine aktive
Assoziation, IP-Adresse `null`. Die im Auftrag als verifiziert genannte
Ausgangslage („WLAN auf 2462 MHz, IP IP_2") gilt zum Zeitpunkt
dieser Sitzung nicht mehr.**

## 2. Warum das ein Abbruchgrund ist, keine Randnotiz

- **STIM ist ohne WLAN-Assoziation kein Stimulus mehr.** Der ganze Mechanismus
  aus T-027 Phase 4 beruht darauf, dass Bluetooth und WLAN sich denselben
  2,4-GHz-Kanal teilen (PTA-Arbitrierung). Ohne Assoziation gibt es keinen
  gemeinsamen Kanal zu teilen — die `dd | nc`-Ströme des Geräts erreichen den
  Host gar nicht (bestätigt: `active_conns=0`, `ping` ohne Antwort). Ein
  „STIM"-Abschnitt liefe technisch identisch zu einem „KONTROLLE"-Abschnitt
  ab — beide ohne jede Dosis.
- **Das würde Frage 1 nicht offen lassen, sondern falsch beantworten.** Ein
  Lauf, der unter dieser Bedingung „STIM erzeugt keinen Verlust" ergäbe,
  wäre kein negatives Ergebnis über die Reproduzierbarkeit — er wäre ein
  Artefakt eines nicht angewandten Stimulus. Das Ergebnis sähe identisch aus
  wie ein echtes „nicht reproduzierbar", ohne es zu sein. Genau die Art von
  stillschweigend falscher Zahl, die dieses Projekt (s. R-006/R-007,
  `docs/state.md`) ausdrücklich vermeiden will.
- **Es würde auch Frage 2 zerstören, ohne dass ich das sehen würde** — die
  Hörbarkeitsseite führt ausschließlich der Director. Fünf der zehn
  Abschnitte wären als „STIM" etikettiert, obwohl real keiner anlag; jede
  Zuordnung „Nutzer hat bei STIM etwas gehört" wäre auf Zufall oder
  Erwartungshaltung zurückzuführen, nicht auf echten Reiz. Da mir die
  Hörbarkeitsseite nicht zugänglich ist, könnte ich diesen Fehler im
  Nachhinein nicht einmal erkennen — er müsste jetzt verhindert werden.

**Deshalb: kein Abschnitt gestartet.** Der Nutzer saß bereits konzentriert
vor dem Kopfhörer — genau deshalb wollte ich keine Zeit auf eine Sitzung
verwenden, deren halbe Bedingungen wirkungslos gewesen wären.

## 3. Was NICHT versucht wurde, und warum

Kein Versuch, die WLAN-Verbindung selbst wiederherzustellen (`cmd wifi
connect-network`, WLAN aus/an, o. ä.). Gründe:

- Ausserhalb des Messauftrags — T-027 Phase 4 hält ausdrücklich fest, dass
  dieser Agent WLAN-Zustand **liest**, keine Verbindungen **aufbaut**
  („Kein WLAN-Passwort abgefragt, keine Verbindung aufgebaut — der Nutzer hat
  den Wechsel selbst vorgenommen; nur gelesen.").
  Ein eigenmächtiger Reconnect wäre ein Eingriff in Geräte-Netzwerkzustand
  ausserhalb dessen, was der Auftrag als meine Rolle vorsieht.
- Das Eventlog zeigt vor der letzten erfolgreichen Verbindung einen
  `SSID_TEMP_DISABLED`-Eintrag mit `reason=WRONG_KEY` (18:48:06, vor der
  eigentlichen Messreihe) — ob ein erneuter Verbindungsversuch sauber
  durchläuft oder erneut an einem Credential-Problem hängt, ist von hier aus
  nicht einschätzbar. Das ist ein Risiko, das der Nutzer selbst am Gerät
  auflösen sollte, nicht ein Agent per Shell-Befehl.

## 4. Aufgeräumt

- Device-Verzeichnis `/data/local/tmp/btperf/t027p2/t028_smoke` entfernt
  (`rm -rf`), nach `ls`-Bestätigung leer.
- Host-Sink-Server-Prozess beendet (`python.exe`, reale Windows-PID **18840**
  laut `Get-CimInstance Win32_Process`, nicht die msys-`$!`-PID), Port 5502
  wieder frei (`Get-NetTCPConnection` liefert nichts mehr).
- `cell.sh` und die fremden `level*`-Verzeichnisse des 5-GHz-Laufs nicht
  angefasst.
- Rohdaten/Skripte nur im Scratchpad: `t028_sink_server.py`,
  `t028_sink_server.log`, `sink_stdout.log`, `t028_preflight_full.txt`.
  Verfallen mit dem Scratchpad dieser Session.
- `adb`-Server läuft weiter (unkritisch, nur `platform-tools/adb.exe`
  während der gesamten Session verwendet).
- Bluetooth/A2DP-Verbindung nicht angefasst — laut letztem Read-back weiter
  `STATE_CONNECTED`, `mIsPlaying: true`. Nur WLAN ist betroffen.

## 5. Reizplan — bereit für den nächsten Anlauf

Die gewürfelte Reihenfolge aus Abschnitt 0 bleibt gültig und wird **nicht neu
gewürfelt**, sobald die Sitzung fortgesetzt wird. Vor dem ersten Abschnitt
des nächsten Anlaufs ist erneut zu prüfen (nicht aus dieser Datei zu
übernehmen, frisch zu lesen):

1. `dumpsys wifi` live: Supplicant-State `COMPLETED`, SSID `SSID_A`,
   `Frequency` 2462 MHz, IP vergeben (nicht `null`).
2. `dumpsys connectivity`: ein `NetworkAgentInfo` mit `Transports: WIFI`
   vorhanden.
3. Smoke-Test (kurze 1-Strom-Zelle) zeigt `active_conns=1` im Sink-Log,
   bevor der erste echte Abschnitt beginnt.

Erst wenn alle drei stehen, beginnt Abschnitt 1 der oben gewürfelten Folge.

### 7.3 Ausfallprotokoll — jeder Assoziationsverlust, mit Dauer

Auf Anweisung des Directors: eigene Messgröße, nicht nur Gültig/Ungültig.
Zeiten aus dem geräteseitigen `dumpsys wifi`-Eventlog
(`SUPPLICANT_STATE_CHANGE_EVENT`/`NETWORK_DISCONNECTION_EVENT`), Host-Zeit
umgerechnet über den kalibrierten Versatz (+91,2 s, Gerät vor Host,
s. 7.1) — nicht selbst neu gemessen, da das Ereignis rückblickend aus dem
Log rekonstruiert wurde.

| # | Trennung (Gerätezeit) | Trennung (Host-Zeit, umgerechnet) | Ursache laut Log | Wiederverbindung stabil (Gerätezeit) | Wiederverbindung (Host-Zeit, umgerechnet) | Dauer | Fiel in Abschnitt # | Bedingung des Abschnitts | Freq. vor Ausfall | Freq. nach Wiederverbindung | Zwischenepisode |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 20:34:17,906 | ≈20:32:46,7 | `NETWORK_DISCONNECTION_EVENT reasonCode: 0, locallyGenerated: false` (nicht selbst ausgelöst) | 20:35:38,220 | ≈20:34:07,0 | **≈80,3 s** | 4 | KONTROLLE (0 Ströme) | 2462 MHz | 2437 MHz (Kanalwechsel des AP) | Ein erster Reconnect-Versuch (20:35:22,068 `CMD_START_CONNECT` → 20:35:22,757 `ASSOCIATED` bei 2437 MHz) erreichte `COMPLETED` nicht und brach bei 20:35:32,982 erneut ab (`reasonCode: 3, locallyGenerated: true`); erst der zweite Versuch (20:35:34,785 → 20:35:38,220) stand. |

**Zur Prüfung der Director-Hypothese („der Stoerreiz zerstoert sich selbst"):**
Dieser eine bisher protokollierte Ausfall fiel in einen **KONTROLLE**-Abschnitt
(0 Ströme, kein Stimulus aktiv) — nicht in einen STIM-Abschnitt. Das ist ein
Datenpunkt **gegen** eine einfache "Stimulus verursacht direkt den Abbruch"-
Lesart für diesen konkreten Fall, widerlegt die Hypothese als Ganzes aber
nicht: der ursprüngliche Ausfall aus T-027 Teil 0 (19:48:49) fiel in eine
16-Strom-Zelle. Mit n=1 Ereignis in STIM- und n=1 in KONTROLLE-Kontext ist
das eine Beobachtung, keine Auszählung — weitere Ausfälle werden hier mit
demselben Format ergänzt, falls sie auftreten. Keine Bewertung darüber
hinaus; Einordnung ist Sache des Directors.

---

## 6. Antwort auf Frage 1 (Reproduzierbarkeit) — Stand vor der Fortsetzung

Zwischenstand vor Wiederaufnahme, s. Abschnitt 7 für die tatsächliche
Sitzung.

---

## 7. Fortsetzung nach WLAN-Wiederherstellung — Director-Freigabe

Director meldet 2026-09-02: Nutzer hat die WLAN-Verbindung wiederhergestellt,
vom Director selbst nachgeprüft (SSID `SSID_A`, BSSID `AP_BSSID`,
Frequency 2462 MHz, Supplicant state COMPLETED, IP IP_2, A2DP
STATE_CONNECTED). Eigene Live-Prüfung (nicht übernommen) bestätigt dasselbe
Bild um 20:15:44 Host-Zeit (s. u.).

**Neue Pflicht ab dieser Fortsetzung, vom Director angeordnet:** WLAN-
Assoziation wird in **jedem** Abschnitt vorher UND nachher geprüft
(Supplicant state COMPLETED, Frequency 2462 MHz, vergebene IP), als eigene
Tabellenspalte. Fällt sie während eines Abschnitts weg, wird der Abschnitt
als **ungültig** markiert, die Sitzung läuft trotzdem bis zum Ende des
gewürfelten Plans weiter (kein Abbruch mehr, anders als beim ersten
Anlauf). Hintergrund laut Director: der WLAN-Abbruch am 19:48:49 aus Teil 0
fiel mitten in die 16-Strom-Zelle von T-027 Phase 4 — die nicht-monotone
Kennlinie dort könnte durch schwächelnde/wegfallende Assoziation bei hoher
Stromzahl (mit)erklärt sein. Die Assoziationsprüfung ist damit eine eigene
Messgröße, nicht nur Qualitätssicherung.

### 7.0 Re-Setup

- `adb`-Server unverändert (kein erneuter kill/start nötig, Session lief
  durch).
- Live-WiFi-Read-back, eigenständig (nicht vom Director übernommen), 20:15:44
  Host-Zeit: `mWifiInfo SSID: "SSID_A" ... Supplicant state: COMPLETED ...
  Frequency: 2462MHz`, IP `/IP_2`. Deckungsgleich mit der
  Director-Meldung.
- Host-Sink-Server neu gestartet (derselbe Scratchpad-Server wie in Teil 0,
  Port 5502; die reale Windows-PID hat sich durch den Neustart geändert,
  jetzt **11056**, per `Get-CimInstance Win32_Process` verifiziert, nicht aus
  `$!` übernommen).
- Erneuter Smoke-Test (`t028_smoke2`, 1 Strom, 3 s): **3,64 MB** beim Sink
  angekommen, `active_conns` während der Übertragung >0 — Pfad Gerät→Host
  bestätigt funktionsfähig, jetzt mit wiederhergestelltem WLAN. Smoke-
  Verzeichnis danach vom Gerät entfernt.

### 7.1 Zeitkalibrierung (erneuert, für diese Fortsetzung massgeblich)

Host `date` unmittelbar vor/nach `adb shell date`, 20:15:23–20:15:24 Host-Zeit
↔ Gerät 20:16:55 → **Versatz ≈ +91,2 s, Gerät vor Host.** Deckt sich mit der
Kalibrierung aus Teil 0 (+91 s) und der Auftragsvorgabe (~89 s) — als
gemessene Grösse für die gesamte Fortsetzung übernommen, nicht neu
angenommen.

**Korrektur zu einer eigenen Falschmeldung:** Ich hatte dem Director als
Startzeit von Abschnitt 1 zunächst 20:17:14,9 Host-Zeit gemeldet — das war
der Zeitpunkt eines schnellen `date`-Aufrufs *vor* der Meldung, nicht der
Zeitpunkt, zu dem der eigentliche Stimulusbefehl das Gerät erreichte. Zwischen
Meldung und tatsächlichem Befehlsversand lag unbeabsichtigt eine Lücke von
rund 76 s (Antwortgenerierung). **Die tatsächliche, aus dem Befehlsaufruf
selbst gestemmte Startzeit von Abschnitt 1 ist 20:18:30,914 Host-Zeit**, s.
Tabelle unten — ab hier wird die Startzeit ausschliesslich aus dem
`date`-Aufruf entnommen, der unmittelbar im selben Werkzeugaufruf wie der
`adb shell`-Start steht, nicht aus einer separaten Vorab-Notiz.

### 7.1a Korrektur des WLAN-Kriteriums — Director, während Abschnitt 4/5

Der Access Point hat beim Wiederverbinden (s. Ausfallprotokoll unten) den
Kanal gewechselt: Gerät hängt seither auf **2437 MHz (Kanal 6)** statt
2462 MHz (Kanal 11). Auf Anweisung des Directors gilt ab sofort **nicht mehr
die feste Zahl 2462 MHz**, sondern: **irgendeine Frequenz im 2,4-GHz-Band
(2400–2500 MHz) plus Supplicant state COMPLETED plus vergebene IP.** Verlässt
die Verbindung das Band (5 GHz) oder fehlt die Assoziation, bleibt der
Abschnitt ungültig. Die tatsächliche Frequenz wird ab sofort als eigene
Spalte geführt, nicht mehr nur als Ja/Nein-Kriterium — ein Kanalwechsel
mitten in der Sitzung ist selbst eine Bedingungsänderung und wird sichtbar
gehalten, nicht weggebügelt.

**Zweite Anweisung des Directors:** jeder Assoziationsverlust wird mit
Zeitpunkt (Geräte- und Host-Zeit) und Dauer protokolliert — nicht nur als
gültig/ungültig, sondern als eigene Messgröße. Hintergrund: der Ausfall um
19:48:49 aus Teil 0 fiel in die 16-Strom-Zelle von T-027 Phase 4; ein
zweiter Ausfall in dieser Sitzung (s. u.) könnte das nicht-monotone Muster
der Kalibrierleiter miterklären. Eigenes Ausfallprotokoll: Abschnitt 7.3.

### 7.2 Abschnittstabelle

Methodik je Abschnitt: PRE-Check (WLAN-Assoziation + Dreifachprüfung +
Zählerstand) unmittelbar vor dem Startbefehl; Startbefehl (`cell.sh`, `umask
077`, `n_streams`=1 für STIM/0 für KONTROLLE, 150 s, Host IP_1:5502)
blockierend bis Abschluss; POST-Check unmittelbar nach Rückkehr. Host-Zeiten
aus dem `date`-Aufruf im selben Werkzeugaufruf wie der `adb`-Befehl.
Geräte-Start/-Ende aus dem skripteigenen `meta.txt`-Zeitstempel (`date
+%s%N`, Gerätewalluhrzeit) — genauer als eine Offset-Umrechnung, da direkt am
Gerät genommen. Ist-Durchsatz aus dem Host-Sink-Log (`active_conns`,
`mbit_s`) für das jeweilige Zeitfenster.

| # | Bedingung | Host-Start | Host-Ende | Geräte-Start | Geräte-Ende | WLAN vorher | Freq. vorher | WLAN nachher | Freq. nachher | Dreifachprüfung vorher | Dreifachprüfung nachher | `dropped` Δ (roh/min) | `dropouts` Δ (roh/min) | Ist-Durchsatz (Sink-Log) | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | KONTROLLE | 20:18:30,914 | 20:21:06,375 | 20:20:02,972 | 20:22:38,158 | COMPLETED/IP vergeben | 2462 MHz | COMPLETED/IP vergeben | 2462 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | 0 Mbit/s durchgehend (251/251 Samples `active_conns=0`) | **gültig, nicht konfundiert** |
| 2 | STIM | 20:23:49,757 | 20:26:24,912 | 20:25:21,662 | 20:27:56,680 | COMPLETED/IP vergeben | 2462 MHz | COMPLETED/IP vergeben | 2462 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | Ø ≈ 3,6 Mbit/s (74,6 MB über 156 s), `active_conns=1` in 156/157 Samples (1 Sample Anlaufzeit `active_conns=0`) — reale Dosis bestätigt | **gültig, nicht konfundiert — erster STIM-Wiederholung zeigt Δ0, anders als das einmalige Ereignis aus T-027 Zelle 1** |
| 3 | KONTROLLE | 20:27:33,752 | 20:30:09,166 | 20:29:05,787 | 20:31:40,922 | COMPLETED/IP vergeben | 2462 MHz | COMPLETED/IP vergeben | 2462 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | 0 Mbit/s durchgehend (157/157 Samples `active_conns=0`) | **gültig, nicht konfundiert** |
| 4 | KONTROLLE | 20:31:02,158 | 20:33:37,379 | 20:32:34,135 | 20:35:09,157 | COMPLETED/IP vergeben | 2462 MHz | **DISCONNECTED bei Geräte-Ende, erst 20:35:38,220 wieder COMPLETED** | 2437 MHz (nach Wiederverbindung) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38 — Zähler unverändert trotz Ausfall) | Δ0 (0/min) | Δ0 (0/min) | 0 Mbit/s (Sink-Log durchgehend `active_conns=0` — erwartungsgemäß, KONTROLLE) | **ungültig — WLAN-Ausfall während des Abschnitts, s. Ausfallprotokoll 7.3, Eintrag 1** |
| 5 | STIM | 20:37:40,788 | 20:40:16,171 | 20:39:12,793 | 20:41:47,925 | COMPLETED/IP vergeben | 2437 MHz | COMPLETED/IP vergeben | 2437 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | **0 Mbit/s — keine einzige Verbindung** (`nc: connect: Connection timed out` im Geräte-Output; Sink-Log zeigt letzten Eintrag um 20:37:38 Host-Zeit, danach keine Zeilen mehr bis zum manuellen Neustart) | **ungültig — kein Stimulus geliefert. Ursache NICHT das WLAN (Assoziation stand die ganze Zeit, s. Eventlog: keine Einträge im Fenster), sondern der Host-Sink-Server ist ca. 2 s vor Abschnittsbeginn ohne Traceback abgestürzt/beendet worden (vermutlich Prozess-Lebensdauer an die startende Bash-Shell gekoppelt statt echt losgelöst). Server neu gestartet (PowerShell `Start-Process`, PID 22680, diesmal ohne Bash-Elternprozess), Smoke-Test danach erfolgreich** |
| 6 | KONTROLLE | 20:44:24,060 | 20:46:59,445 | 20:45:56,016 | 20:48:31,156 | COMPLETED/IP vergeben | 2437 MHz | COMPLETED/IP vergeben | 2437 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | 0 Mbit/s durchgehend (156/156 Samples `active_conns=0`) | **gültig, nicht konfundiert** |
| 7 | STIM | 20:48:48,251 | 20:51:24,650 | 20:50:20,252 | 20:52:56,345 | COMPLETED/IP vergeben | 2437 MHz | COMPLETED/IP vergeben | 2437 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | Ø ≈ 3,7 Mbit/s (73,2 MB), `active_conns=1` in 155/158 Samples (Auslauf am Ende) — reale Dosis bestätigt | **gültig, nicht konfundiert — zweiter valider STIM-Abschnitt mit Δ0** |
| 8 | KONTROLLE | 20:52:30,895 | 20:55:05,492 | 20:54:02,886 | 20:56:37,207 | COMPLETED/IP vergeben | 2437 MHz | COMPLETED/IP vergeben | 2437 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | 0 Mbit/s durchgehend (157/157 Samples `active_conns=0`) | **gültig, nicht konfundiert** |
| 9 | STIM | 20:56:12,920 | 20:58:47,957 | 20:57:44,894 | 21:00:19,662 | COMPLETED/IP vergeben | 2437 MHz | COMPLETED/IP vergeben | 2437 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | Ø ≈ 4,3 Mbit/s, `active_conns=1` in 156/157 Samples — reale Dosis bestätigt | **gültig, nicht konfundiert — dritter valider STIM-Abschnitt mit Δ0** |
| 10 | STIM | 21:00:34,293 | 21:03:09,429 | 21:02:06,277 | 21:04:41,117 | COMPLETED/IP vergeben | 2437 MHz | COMPLETED/IP vergeben | 2437 MHz | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | bestanden (1001/MID/660, ABR abwesend; Zähler 876/38) | Δ0 (0/min) | Δ0 (0/min) | Ø ≈ 7,1 Mbit/s, `active_conns=1` in 151/157 Samples — reale Dosis bestätigt, höchster Durchsatz der Sitzung | **gültig, nicht konfundiert — vierter valider STIM-Abschnitt mit Δ0. Letzter Abschnitt der Sitzung.** |

### 7.4 Zusammenfassung und Antwort auf Frage 1 (Reproduzierbarkeit)

**Alle zehn Abschnitte des gewürfelten Plans sind gelaufen, keiner wurde
abgebrochen.** Zwei sind als konfundiert/ungültig markiert (Abschnitt 4:
WLAN-Ausfall während des Fensters; Abschnitt 5: Host-Sink-Server ohne
Traceback beendet, kein Stimulus geliefert), beide aus Gründen, die mit dem
gewürfelten Plan selbst nichts zu tun haben, sondern mit Infrastruktur
(WLAN bzw. Host-Prozess). Acht Abschnitte sind gültig:

- **STIM, gültig, real dosiert (bestätigt über `active_conns=1` und
  Ist-Durchsatz im Sink-Log):** Abschnitte 2 (Ø 3,6 Mbit/s), 7 (Ø 3,7 Mbit/s),
  9 (Ø 4,3 Mbit/s), 10 (Ø 7,1 Mbit/s) — **4 von geplanten 5**.
- **KONTROLLE, gültig:** Abschnitte 1, 3, 6, 8 — **4 von geplanten 5**.

**`dropped`/`dropouts`-Delta in JEDEM der acht gültigen Abschnitte: Δ0/Δ0,
sowohl in STIM- als auch in KONTROLLE-Abschnitten.** Streuung über die vier
gültigen STIM-Abschnitte: Mittelwert 0 `dropped`/min, 0 `dropouts`/min,
Standardabweichung 0 (alle vier Werte identisch null). Streuung über die
vier gültigen KONTROLLE-Abschnitte: ebenfalls Mittelwert 0, Standardabweichung
0. **Kein Unterschied zwischen den Gruppen — bei keiner einzigen Wiederholung,
auf zwei verschiedenen WLAN-Kanälen (2462 MHz in Abschnitt 2, 2437 MHz in
Abschnitt 7/9/10) und bei Ist-Durchsätzen von 3,6 bis 7,1 Mbit/s.**

**Antwort auf Frage 1: Nein, der Ausschlag aus T-027 Phase 4 Zelle 1 (39,3
`dropped`/min, 1,71 `dropouts`/min bei 1 parallelem Strom) ist unter den
heutigen Bedingungen NICHT reproduziert worden.** Vier unabhängige
Wiederholungen derselben Dosierstufe (1 Strom, real bestätigt) ergaben
durchgehend Δ0, bei vier Kontrollwiederholungen ebenfalls Δ0 — kein
Unterschied, keine Streuung, kein Trend. Das stützt die Lesart aus
`docs/perf/T-027-messung-24ghz.md` Abschnitt 3, Punkt (a)/(c): der Ausschlag
von Zelle 1 war wahrscheinlicher ein einzelnes, nicht wiederkehrendes
Ereignis (Sampling-Zufall, kurzes Störfenster ausserhalb dieser
150-s-Fenster, oder eine Bedingung, die an diesem Abend nicht mehr vorlag)
als ein stabiler Dosis-Wirkungs-Effekt bei 1 Strom. **Kein Beleg für eine
Schwelle bei dieser Dosierstufe** — weder für noch gegen einen Effekt bei
höherer Dosierung, da nur die 1-Strom-Stufe getestet wurde (wie im Auftrag
vorgegeben, Dosis nicht erhöht).

**Nebenbefund, nicht Teil von Frage 1, aber vom Director angefordert:** Der
einzige protokollierte WLAN-Ausfall dieser Sitzung (Ausfallprotokoll 7.3,
Eintrag 1) fiel in einen **KONTROLLE**-Abschnitt (0 Ströme). Das ist ein
Datenpunkt gegen eine einfache "Stimulus destabilisiert die Verbindung
direkt"-Kausalität für diesen konkreten Fall — bei nur einem Ereignis in
dieser Sitzung (und dem einen aus T-027 Teil 0, das in eine 16-Strom-Zelle
fiel) ist das eine Beobachtung, keine belastbare Auszählung. Einordnung ist
Sache des Directors.

### 7.5 Aufgeräumt (Fortsetzung)

- Alle zehn Device-Verzeichnisse `/data/local/tmp/btperf/t027p2/t028_seg{01..10}_*`
  entfernt (`rm -rf`), `ls`-Bestätigung danach leer.
- Host-Sink-Server-Prozess beendet (reale Windows-PID **22680**, per
  `Get-CimInstance Win32_Process` verifiziert), Port 5502 wieder frei
  (`Get-NetTCPConnection` liefert nichts mehr). Die zwischenzeitlich
  gestorbene erste Instanz dieser Fortsetzung (PID 11056) war zum
  Zeitpunkt der Bereinigung bereits von selbst verschwunden (s. 7.2,
  Abschnitt 5 — Ursache des Absturzes nicht abschliessend geklärt, vermutete
  Kopplung an die startende Bash-Shell statt echter Loslösung; für einen
  eventuellen nächsten Lauf: Prozess besser über `Start-Process`/echten
  Job-Objekt-losgelösten Start beginnen, wie ab PID 22680 gehandhabt).
- `cell.sh` und die fremden `level*`-Verzeichnisse des 5-GHz-Laufs weiterhin
  nicht angefasst.
- Rohdaten/Skripte nur im Scratchpad: `t028_sink_server.py`,
  `t028_sink_server.log`, `sink_stdout.log`, `sink_stdout2.log`,
  `sink_stderr2.log`, `t028_check.sh`, `t028_preflight_full.txt`,
  `seg2.txt`…`seg10.txt` (Sink-Log-Ausschnitte je Abschnitt). Verfallen mit
  dem Scratchpad dieser Session.
- `adb`-Server läuft weiter (unkritisch, nur `platform-tools/adb.exe`
  während der gesamten Session verwendet, `C:\RSL\2.1HF5\adb\adb.exe` zu
  keinem Zeitpunkt aufgerufen).
- Bluetooth/A2DP-Verbindung durchgehend nicht angefasst — letzter Read-back
  (Abschnitt 10, POST) weiterhin `mCodecSpecific1:1001`, `LDAC quality mode
  MID`, `660 Kbps`, `mIsPlaying` nicht explizit erneut gegengelesen, aber
  Musikwiedergabe war Voraussetzung jedes Dreifachprüfungs-Reads und in
  keinem davon vermerkt als unterbrochen.
- Stufe die ganze Sitzung über fest auf 660 belassen, kein einziger
  Stufenwechsel — keine Dosis heimlich erhöht, wie vom Director gefordert.
- `umask 077` bei jedem `cell.sh`-Aufruf gesetzt (Teil der Startzeile).
