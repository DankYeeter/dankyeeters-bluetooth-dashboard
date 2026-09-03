# T-027 Phase 4 — echte M-11-Kalibriermessung auf 2,4 GHz

Rolle: `performance-tuner`. Auftrag: Director-Anweisung 2026-09-02 "T-027
Phase 4 — die ECHTE M-11-Kalibriermessung auf 2,4 GHz". Grundlage:
`docs/tasks/T-027.md`, `docs/perf/T-027-messung.md` Abschnitt 9
(Phase-2-Mechanik, Zellenplan).

**Diese Datei ist eigenständig.** `docs/perf/T-027-messung.md` (die
5-GHz-Rohdatenauswertung eines parallelen Laufs) wird von diesem Lauf **nicht**
angefasst. `docs/perf/baselines.md` ebenfalls nicht — der Director führt
zusammen.

**Was sich geändert hat, der Grund für diesen Lauf:** Der Nutzer hat seinen
Router auf reines 2,4 GHz umgestellt. Bluetooth und WLAN teilen sich jetzt
dasselbe Band — die PTA-Arbitrierung im Kombichip ist damit der Mechanismus,
den R-007s Hebel B unterstellt, nicht mehr "Last auf 5 GHz, Mechanismus
ungeklärt" wie im vorherigen Lauf (dortige Abschnitt-9-Beschriftungsregel).

---

## 0. Rahmen und Ausgangszustand — per Read-back verifiziert, nicht angenommen

- Gerät: Pixel 11 Pro `67011FDKX004XG`, Android 17, per Kabel.
- Werkzeug: **ausschließlich** `~/tools/android-sdk/platform-tools/adb.exe`
  (Risiko R-2). `adb kill-server`/`start-server` einmal zu Sessionbeginn,
  danach durchgehend derselbe Server. `C:\RSL\2.1HF5\adb\adb.exe` zu keinem
  Zeitpunkt aufgerufen.
- Gerätewalluhrzeit bei Sessionbeginn: **19:15:39** (`adb shell date`).

### 0.1 WLAN — 2,4 GHz bestätigt

Frischer Read-back (`dumpsys wifi`), vor jeder eigenen Änderung:

| Feld | Wert |
|---|---|
| SSID | `SSID_A` |
| BSSID | `AP_BSSID` |
| Frequency | **2462 MHz** (2,4-GHz-Band, Kanal 11) |
| Supplicant state | COMPLETED |
| IP | IP_2 |
| Security | wpa2-psk (Authentication=2, networkType=TYPE_WPA2) |
| Wi-Fi standard | 11ax |
| Link speed (Tx/Rx) | 48/114 Mbps |

Deckt sich exakt mit der Vorgabe aus dem Auftrag. Kein WLAN-Passwort
abgefragt, keine Verbindung aufgebaut — der Nutzer hat den Wechsel selbst
vorgenommen; nur gelesen.

### 0.2 A2DP und Dreifachprüfung — bestanden

Vollständiger `dumpsys bluetooth_manager`-Dump, 19:15:5x Gerätezeit (Datei
`preflight_full.txt`, nur Scratchpad):

| Größe | Wert |
|---|---|
| `ConnectionState` (A2DP) | STATE_CONNECTED |
| `mIsPlaying` | true |
| `mCodecSpecific1` | **1001** |
| `LDAC quality mode` | **MID** |
| `LDAC transmission bitrate (Kbps)` | 660 |
| ABR-Zeilen (`adaptive bit rate`) | **abwesend** (0 Treffer) |
| `Priority` | 1000000 (unverändert ggü. T-027-Vorlauf, kein Pin-Marker s. Gate-Befund) |
| `Counts (flushed/dropped/dropouts)` | 1 / 277 / 12 (kumuliert seit letztem Stack-Reset, **nicht** seit Sessionbeginn — Ausgangsstand für Zellen-Deltas) |
| `Counts (underflow)` | 2085 (kumuliert, dito) |

**Dreifachprüfung bestanden** (`mCodecSpecific1` = 1001, `LDAC quality mode` =
MID, ABR-Zeilen abwesend). Ausgangszustand entspricht der Vorgabe, gemessen
wird ab hier.

### 0.3 Methodischer Vorbehalt (Auftragspflicht, unverändert übernommen)

USB-3 strahlt ins 2,4-GHz-Band ab. Das Gerät hängt während der gesamten
Session am Kabel. Bei diesem Lauf ist 2,4 GHz **der Stör-Kanal selbst** —
das Kabel ist damit potenziell **Teil des Messgegenstands**. Benannt, nicht
aufgelöst.

### 0.4 Stimulus-Mechanik — Skripte übernommen, Sink-Server neu gestartet

- **Geräteseitig:** `/data/local/tmp/btperf/t027p2/cell.sh` (vom
  vorherigen Lauf gebaut, unverändert übernommen). Nimmt `<label> <n_streams>
  <duration_s> <host_ip> <port>`; startet parallele `dd if=/dev/zero | nc`-
  Ströme gegen den Host-Sink und sampelt `A2DP State:`/`A2DP LDAC State:`
  alle ~1 s in eine eigene `series.log` je Zelle.
  **Vorhandene Verzeichnisse `level0_control` … `level16_16stream`,
  `level0b_return` unter `/data/local/tmp/btperf/t027p2/` sind die
  vollständigen Rohdaten des parallelen 5-GHz-Laufs — nicht angefasst,
  nicht gelöscht.** Eigene Zellen laufen unter einem eigenen Namensraum
  `24ghz_level*`, damit nichts kollidiert.
- **Umask:** jede eigene Zelle wird per `adb shell "umask 077; sh cell.sh
  ..."` gestartet, damit die von `mkdir -p` neu angelegten Verzeichnisse
  `drwx------` sind (Auftragspflicht `umask 077`; die bestehenden
  `level*`-Verzeichnisse des Vorlaufs sind `drwxrwxrwx` — nicht mein
  Eingriff, nicht rückwirkend korrigiert, da fremde Rohdaten).
- **Host-Sink-Server:** alter Prozess (PID 9413) verifiziert tot
  (`Get-Process` liefert nichts). Neu gestartet aus einer Kopie des
  Skripts mit eigenem Log (`t027_sink_server_24ghz.py` →
  `t027_sink_server_24ghz.log`, beide nur Scratchpad, damit die Logzeilen
  nicht in die Datei des parallelen Laufs geschrieben werden). Host-IP
  geprüft: weiterhin **IP_1** (unverändert), Port 5501,
  `Get-NetTCPConnection` bestätigt Listen-Status. Firewall-Profil weiterhin
  `Private` (`Get-NetConnectionProfile`), kein Eingriff nötig.
  Smoke-Test (3 s, 50×64 KiB vom Gerät) zeigt `total_MB=3.3` im Sink-Log —
  Pfad Gerät→Host bestätigt funktionsfähig, bevor die erste echte Zelle
  läuft.
- **Zellendauer:** 240 s je Zelle, identisch zur 5-GHz-Methodik
  (Abschnitt 9.1 der Schwesterdatei) — kein eigener Entwurf, dieselbe
  Mechanik wiederholt.
- **Leiter:** 0 (Kontrolle) / 1 / 2 / 4 / 8 / 16 parallele Ströme, danach
  0' (Rückkehr, A/B/A'-Disziplin).

---

**Hinweis vor der ersten Zelle:** Zwischen dem Preflight-Read (19:15:5x,
`dropped`/`dropouts` = 277/12, s. Abschnitt 0.2) und dem Pre-Read-back von
Zelle 0 (19:18:46, `dropped`/`dropouts` = 622/27) liegen rund 3 min **ohne
laufende Stör-Zelle** (nur der 3-s-Smoke-Test des Sink-Pfads), in denen
`dropped` bereits um 345 und `dropouts` um 15 gestiegen sind. Das ist reine
Beobachtung, kein Bestandteil einer Zellmessung — festgehalten, weil es zeigt,
dass am 2,4-GHz-Link bereits **ohne** dosierten Stimulus mehr Hintergrundverlust
anfällt als am 5-GHz-Link im 40-min-Ruhelauf aus Phase 1 der Schwesterdatei
(dort `dropped`/`dropouts` Δ = 0/0 über ~40 min bei identisch gepinnter Stufe).
Für die eigentliche A/B-Auswertung zählt ausschließlich das Delta **innerhalb**
der jeweiligen 240-s-Zelle (Pre-Read-back unmittelbar vor Zellstart, Post-
Read-back unmittelbar danach) — nicht der Sprung davor.

## 1. Zellentabelle (wird nach jeder abgeschlossenen Zelle sofort ergänzt)

| Zelle | Stufe | Start (Gerätezeit) | Ende (Gerätezeit) | Dreifachprüfung vorher | Dreifachprüfung nachher | `dropped`/`dropouts` (roh, Δ) | je Minute | Ist-Durchsatz (Sink-Log) | Status |
|---|---|---|---|---|---|---|---|---|---|
| `24ghz_level0_control` | 0 (kein Stimulus) | 19:18:46 (Pre-Read-back) | 19:23:06 (Post-Read-back) | bestanden | bestanden | dropped 622→646 (Δ24); dropouts 27→28 (Δ1) | dropped 5,857/min; dropouts 0,244/min | 0 Mbit/s durchgehend (Sink-Log: `active_conns=0` über die gesamte Zelle) | **abgeschlossen, nicht konfundiert** |
| `24ghz_level1_1stream` | 1 Strom | **19:24:04** (Startkommando; Skript-eigener Start-Zeitstempel 19:24:04,98 Gerätezeit) | 19:28:23 (Post-Read-back) | bestanden (Pre-Read 19:23:57: 646/28) | bestanden (807/35) | dropped 646→807 (Δ161); dropouts 28→35 (Δ7) | dropped 39,30/min; dropouts 1,709/min | Ø ≈ 7,3 Mbit/s (223,9 MB über 245,8 s), `active_conns=1` durchgehend, deutlich **unter** dem ~330 Mbit/s eines einzelnen Stroms im 5-GHz-Vorlauf — plausibel, da der Kanal jetzt mit Bluetooth geteilt wird statt frei zu sein | **abgeschlossen, nicht konfundiert — erste Störzelle mit deutlichem Effekt ggü. Kontrolle (≈6,7× dropped-Rate, ≈7× dropouts-Rate)** |
| `24ghz_level2_2stream` | 2 Ströme | 19:29:53 (Pre-Read-back); Startkommando direkt danach | 19:34:05 (Post-Read-back) | bestanden (830/36) | bestanden (830/36) | dropped 830→830 (**Δ0**); dropouts 36→36 (**Δ0**); underflow 2092→2094 (Δ2) | dropped 0/min; dropouts 0/min | Ø ≈ 9,0 Mbit/s (274,5 MB über 244,7 s), `active_conns=2` durchgehend — **kaum höher** als bei 1 Strom (7,3 Mbit/s), der 2,4-GHz-Kanal ist bereits bei 1 Strom nahe seiner erreichbaren Kapazität unter BT-Koexistenz | **abgeschlossen, nicht konfundiert — Δ0 trotz Stimulus, nicht monoton ggü. Zelle 1; roh berichtet, nicht geglättet** |
| `24ghz_level4_4stream` | 4 Ströme | 19:34:55 (Pre-Read-back); Startkommando direkt danach | 19:39:06 (Post-Read-back) | bestanden (830/36) | bestanden (830/36) | dropped 830→830 (**Δ0**); dropouts 36→36 (**Δ0**); underflow 2094→2118 (Δ24) | dropped 0/min; dropouts 0/min | Ø ≈ 12,9 Mbit/s (394,2 MB über 245,0 s), `active_conns=4` durchgehend — Durchsatz steigt mit Streamzahl nur unterproportional (7,3→9,0→12,9 Mbit/s für 1/2/4), Kanal bleibt knapp | **abgeschlossen, nicht konfundiert — weiterhin Δ0 bei `dropped`/`dropouts`, trotz höherem Ist-Durchsatz als Zelle 1; `underflow` steigt (kein Verdikt, nur Beobachtung)** |
| `24ghz_level8_8stream` | 8 Ströme | 19:39:41 (Pre-Read-back); Startkommando direkt danach | 19:43:52 (Post-Read-back) | bestanden (830/36) | bestanden (830/36) | dropped 830→830 (**Δ0**); dropouts 36→36 (**Δ0**); underflow 2118→2121 (Δ3) | dropped 0/min; dropouts 0/min | Ø ≈ 13,6 Mbit/s (414,9 MB über 245,0 s), `active_conns=8` durchgehend — Zuwachs ggü. 4 Streams nur gering (12,9→13,6 Mbit/s), Kanal weiterhin knapp | **abgeschlossen, nicht konfundiert — Δ0 bei `dropped`/`dropouts` seit Zelle 2 durchgehend, trotz steigendem Ist-Durchsatz** |
| `24ghz_level16_16stream` | 16 Ströme (höchste geplante Dosierstufe) | 19:44:24 (Pre-Read-back); Startkommando direkt danach | 19:50:51 (Post-Read-back, ~2:22 nach Zellende — kein Stimulus in der Lücke, s. u.) | bestanden (830/36) | bestanden (830/36) | dropped 830→830 (**Δ0**); dropouts 36→36 (**Δ0**); underflow 2121→2257 (Δ136) | dropped 0/min; dropouts 0/min | Ø ≈ 17,1 Mbit/s (524,3 MB über 245,0 s), `active_conns=16` durchgehend, höchster Ist-Durchsatz der Leiter (Spitzen bis ~51 Mbit/s) | **abgeschlossen, nicht konfundiert — Δ0 bei `dropped`/`dropouts` auch bei der höchsten Dosierstufe; `underflow`-Δ deutlich höher als in Zelle 4/8, aber ohne Verdikt** |
| `24ghz_level0b_return` | 0 (Rückkehr, A/B/A'-Disziplin) | 19:51:38 (Pre-Read-back); Startkommando direkt danach | 19:55:49 (Post-Read-back) | bestanden (830/36) | bestanden (830/36) | dropped 830→830 (Δ0); dropouts 36→36 (Δ0); underflow 2259→2262 (Δ3) | dropped 0/min; dropouts 0/min | 0 Mbit/s durchgehend (Sink-Log: alle 41 Ticks im Fenster `active_conns=0`, keine Fremdverbindung) | **abgeschlossen, nicht konfundiert — A/B/A'-Rückkehr sauber, deckt sich mit der seit Zelle 2 stabilen Nullrate** |

**Leiter abgeschlossen 19:55:49 Gerätezeit.** Alle sieben Zellen (0/1/2/4/8/16/0')
liefen, keine als konfundiert markiert (Dreifachprüfung bestand in jeder
Zelle, vorher und nachher). `dropped`/`dropouts` waren **nur in Zelle 1
(1 Strom)** von der Kontrolle unterscheidbar — Δ0 in allen übrigen
Stör-Zellen (2/4/8/16) trotz monoton steigendem Ist-Durchsatz bis ~17 Mbit/s.
Das ist ein reales, nicht geglättetes Ergebnis, kein Messfehler: die
Dreifachprüfung bestand durchgehend, der Sink-Log bestätigt in jeder Zelle
den tatsächlich erreichten Durchsatz. Einordnung folgt in Abschnitt 3.

---

## 2. Fixtures

**Zelle `24ghz_level1_1stream` zeigte echten, zellinternen Verlust** (Δdropped
161, Δdropouts 7 über 240 s bei 1 parallelem Strom, gegen Δ24/Δ1 in der
unmittelbar vorausgehenden Kontrollzelle) — die dritte, seit Wochen offene
Fixture-Lücke aus `docs/state.md` ("kein aufgenommener Dump aus dem
Verlustfall" ausserhalb des 990-Arms).

Der volle Post-Read-back dieser Zelle (19:28:23 Gerätezeit, `dropped`/
`dropouts` = 807/35, `Last update time ago in ms (flushed/dropped): 0 / 0` —
der Verlust war im Moment der Aufnahme frisch) wurde auf das reduzierte
Zwei-Block-Format (`A2DP State:`/`A2DP LDAC State:`) getrimmt, verbatim, kein
MAC im Ausschnitt (geprüft, Redaktion nicht nötig) — dieselbe Konvention wie
die drei Gate-Fixtures aus der Schwesterdatei:

| Datei | Repo-Pfad | Deckt |
|---|---|---|
| `bt_manager_pixel11_ldac_pinned_660_24ghz_induced_loss.txt` | `core-monitor/src/test/resources/dumps/` | Echter Verlustfall (`dropped`/`dropouts` ≠ 0) bei **fest** 660 kbps/MID, **extern induziert** durch reales 2,4-GHz-WLAN auf demselben AP, ABR-Zeilen abwesend — der Fall, den R-007 unterstellt, nicht der spontane 990er-Overload aus `..._990_loss.txt` |

README (`core-monitor/src/test/resources/dumps/README.md`) im bestehenden
Tabellenformat ergänzt, mit Abgrenzung zur bestehenden `990_loss`-Fixture.

**Kein Golden-Test angelegt.** Das README-Muster für echte Geräte-Captures
verlangt eigentlich einen begleitenden Parser-Test — das ist Bau, nicht
Messung, und fällt ausserhalb dieser Rolle (`performance-tuner` schreibt
keine neuen Tests/Features). Empfehlung an den `director`: `developer`
oder `qa-engineer` beauftragen, einen Golden-Test auf diese Fixture zu
setzen, analog zu den bestehenden `pinned_660`/`pinned_330`-Fixtures.

Weitere Zellen (2/4/8/16/0') zeigten **Δ0** bei `dropped`/`dropouts`
innerhalb ihres jeweiligen 240-s-Fensters — kein zusätzlicher Verlustfall
zum Mitnehmen, s. Zellentabelle.

---

## 3. Nicht messbar / offen

- **Keine Aussage zur Hörbarkeit** — an keiner Stelle enthalten, wie vom
  Auftrag verlangt. Die Zuordnung von Uhrzeit zu Hörerinnendruck ist Aufgabe
  des `director` in `docs/perf/T-027-hoereindruck.md`.
- **Keine Schwellenempfehlung** — nicht Teil dieses Laufs.
- **Kein WLAN-Passwort verwendet, keine Verbindung aufgebaut** — nur die
  vom Nutzer bereits hergestellte 2,4-GHz-Verbindung gelesen.
- **Keine Streuung über mehrere Wiederholungen pro Stufe.** Jede Stufe lief
  genau einmal (240 s), wie in der 5-GHz-Schwestermessung. Eine echte
  Streuungsangabe (Median/p95 über mehrere Läufe je Stufe) würde
  Wiederholungen brauchen — nicht Teil dieses Auftrags, hier nur als
  methodische Lücke benannt, damit "jede Aussage mit ihrer Streuung" nicht
  stillschweigend unterlaufen wird. Was stattdessen vorliegt: die
  Zellinterne Konsistenz (Dreifachprüfung bestand an jedem Zellrand, keine
  Zelle konfundiert) und der Kontrast zur unmittelbar vorausgehenden
  Kontrollzelle je Stör-Zelle.
- **Warum `dropped`/`dropouts` ab Zelle 2 bei Δ0 verharren, obwohl der
  Ist-Durchsatz bis Zelle 16 weiter steigt, ist offen.** Denkbare, hier
  **nicht geprüfte** Ansätze (Aufzählung, keine Wertung): (a) der
  eigentliche Verlustmechanismus reagiert nicht monoton auf zusätzliche
  TCP-Ströme, sobald der Kanal einmal knapp ist — der Sprung von Kontrolle
  auf 1 Strom könnte bereits die volle PTA-Wirkung ausgelöst haben, weitere
  Ströme sättigen nur den WLAN-Durchsatz, nicht die BT-Kollisionsrate; (b)
  Reset-/Rauschverhalten der Zähler zwischen den Zellen (der Sprung 807→830
  geschah **ausserhalb** jeder Zelle, in der ~90-s-Lücke zwischen Zelle 1
  und Zelle 2 — s. Zellentabelle); (c) das Sampling-Fenster (Pre-/Post-
  Read-back statt kontinuierlicher Zählung) kann einen kurzen Ausschlag
  verpasst haben, der zwischen zwei Reads liegt. Keiner dieser Punkte ist
  hier aufgelöst — Recherche, nicht Messung.
- **Kein zweiter Durchlauf der Leiter.** Der Auftrag verlangte die Leiter
  einmal, mit A/B/A'-Rückkehr am Ende — nicht mehrfach. Ob Zelle 1s Effekt
  reproduzierbar ist oder eine einmalige Ausreisser-Episode, ist damit
  offen.

## 4. Aufgeräumt (SR-012)

- Eigene Geräteverzeichnisse `/data/local/tmp/btperf/t027p2/24ghz_level*`
  (7 Stück, alle mit `umask 077` angelegt, `drwx------`) nach dem Abzug der
  Rohreihen (`series.log`/`meta.txt`, nur Scratchpad, s. u.) vollständig
  entfernt (`rm -rf`).
- **Nicht angefasst:** `/data/local/tmp/btperf/t027p2/cell.sh` (Werkzeug,
  wiederverwendet) und `/data/local/tmp/btperf/t027p2/level*` — die
  vollständigen Rohdaten des parallelen 5-GHz-Laufs, fremder Auftrag, nicht
  meine Artefakte.
- Host-Sink-Server-Prozess gestoppt (`python.exe`, tatsächliche Windows-PID
  6652 — die von `$!` in der Bash-Shell zurückgegebene PID 1335 war die
  msys-interne Prozess-Sicht, nicht die reale Windows-PID; per
  `Get-CimInstance Win32_Process` verifiziert und korrigiert), Port 5501
  wieder frei (`Get-NetTCPConnection` liefert nichts mehr).
- Rohdaten bleiben **nur im Scratchpad**, nicht im Repo (gleiche Konvention
  wie alle vorherigen Läufe dieses Projekts):
  `t027_sink_server_24ghz.py`/`.log`, `sink_stdout.log`,
  `24ghz_level{0,1,2,4,8,16,0b}_{pre,post}.txt` (volle Dumps, je Zelle),
  `preflight_full.txt`, `24ghz_final_check.txt`,
  `24ghz_raw_series/<zelle>/{series.log,meta.txt}` (die vor dem Löschen vom
  Gerät gezogenen Rohreihen). Verfallen mit dem Scratchpad — falls der
  `director` sie über diese Session hinaus braucht, müssen sie vor Ablauf
  gesichert werden.
- `adb`-Server läuft weiter (kein `kill-server` am Ende, unkritisch — nur
  `platform-tools/adb.exe` wurde während der gesamten Session verwendet,
  `C:\RSL\2.1HF5\adb\adb.exe` zu keinem Zeitpunkt aufgerufen).
- Kein neuer Prozess auf dem Gerät verblieben, kein Force-Stop, keine App
  installiert/deinstalliert, Bluetooth-Verbindung durchgehend gehalten,
  Musik lief laut letztem Read-back weiter (`mIsPlaying: true`).
