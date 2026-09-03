# T-032 — Geräte-Read-back: Linkfakten und BQR-Telemetrie bei gepinnten 990 kbps

Rolle: `performance-tuner`. Gerät: Pixel 11 Pro `67011FDKX004XG`, Android 17,
per USB-Kabel (adb). adb-Binary durchgehend: `C:\RSL\2.1HF5\adb\adb.exe`
(R-2 beachtet — `platform-tools\adb.exe` wurde vor Beginn beendet und nicht
mehr benutzt). Sink: `SINK_A` (der laut Auftrag verbundene Kopfhörer), im
Bericht durchgehend als `SINK_A` maskiert, keine MAC-Fragmente unten. Rohdaten
mit MAC-Adressen liegen ausschließlich unter `C:\Users\Daniel\t032-rawdata`
(außerhalb des Repos).

Datum: 2026-09-03, 09:04–09:41 CEST (Geräte-Lokalzeit, per `adb shell date`
bestätigt).

## Zustandsbuch (Phase 0, 09:04:23, und Bestätigung am Ende von Phase 2, 09:38–09:41)

| Größe | Wert bei Phase-0-Start (09:04:23) | Wert am Ende von Phase 2 (09:38–09:41) |
|---|---|---|
| Aktiver Codec | LDAC (`Current Codec: LDAC`) | unverändert |
| `mCodecSpecific1` (aktive Peer-Config) | `1000` | unverändert |
| `LDAC quality mode` | `HIGH` | unverändert |
| LDAC-Bitrate | `990` Kbps | unverändert |
| ABR-Zeilen vorhanden? | Nein — Block `A2DP LDAC State:` enthält keine `LDAC adaptive bit rate …`-Zeilen | unverändert (bestätigt [[project_ldac_990_no_abr_lines]] erneut) |
| Abtastrate / Bittiefe | 96000 Hz / 32 Bit (`Config: Rate=96000 Bits=32 Mode=STEREO`) | unverändert |
| A2DP-Link aktiv? | Ja, `Streaming: true`, `mIsPlaying: true` | Ja, unverändert |
| WLAN-Radio | **AN** (`Wifi is enabled`, `WifiState 1`, `wifi_on=1`) | unverändert |
| WLAN-Assoziation | **NICHT verbunden** (`Wifi is not connected`, `Supplicant state: DISCONNECTED`) | unverändert (Stichprobe am Ende) |
| `wifi_scan_always_enabled` | `1` (AN) | unverändert |
| Verbundene Bluetooth-Geräte | 1 — `SINK_A`, `Connected: true`, `Streaming: true` | unverändert |
| Bildschirmzustand | `mWakefulness=Awake` | `mWakefulness=Awake` |
| Akkustand | 100 %, `Capacity level: 5` (FULL) | 100 %, unverändert |
| Ladezustand | `USB powered: true`, `AC powered: false` | unverändert |
| BT-Stack-Prozess-Laufzeit | `com.google.android.bluetooth` PID 12476, seit ≈124 s nach Boot aktiv; Gerät `uptime` ≈ 2 Tage 2:41 h → Prozess läuft seit weit vor 2026-09-02 durchgehend, kein Neustart im relevanten Fenster erkennbar | — |

**Abbruchbedingung geprüft, nicht ausgelöst:** Stufe ist 990, A2DP-Link ist
aktiv und streamt — weitergemessen.

**Wichtige Abweichung vom vom Nutzer gemeldeten Zustand:** Der Nutzer meldete
„WLAN aus". Tatsächlich ist das WLAN-**Radio** eingeschaltet
(`Wifi is enabled`, `cmd wifi status` → „Wifi is enabled" / „Wifi is not
connected"), nur **nicht mit einem Access Point assoziiert**. Das ist kein
Abbruchgrund (Abbruch ist ausschließlich an Stufe/A2DP-Link geknüpft), aber
eine Umgebungsabweichung, die dokumentiert gehört: Es lag im ganzen Fenster
keine WLAN-Assoziation vor — das ist die stärkstmögliche Ausprägung von
„WLAN aus" für die Luftzeit-These, auch wenn der Radio-Zustand selbst nicht
„aus" war. `wifi_scan_always_enabled=1` bedeutet, dass periodische
Hintergrund-Scans grundsätzlich möglich sind — nicht geprüft, ob während des
Fensters tatsächlich gescannt wurde (kein Shell-Hebel dafür verfügbar, siehe
offene Punkte).

**Einschränkung der „je Abschnitt"-Prüfung:** WLAN/Bildschirm/Akku wurden nur
an den beiden Blockgrenzen (Phase-0-Start, Phase-2-Ende) gelesen, nicht
fortlaufend während der 27,78 Minuten. Grund: jeder zusätzliche
`dumpsys bluetooth_manager`-Aufruf hätte mit dem BQR-Lese-Verhalten
kollidiert (siehe Phase 1, Punkt 6) und den Sampler kontaminiert. Für
WLAN/Bildschirm/Akku allein (ohne `dumpsys bluetooth_manager`) wäre eine
fortlaufende Prüfung möglich gewesen und ist es beim nächsten Lauf nachzuholen
— siehe „Offene Punkte".

## Phase 1 — Strukturfakten des Links

| # | Frage | Rohzeile (zitiert) | Wert / Befund |
|---|---|---|---|
| 1 | `Frames per packet (ave)` | `Frames per packet (total/max/ave) : 115111 / 4 / 13` (Phase 0) — über alle 1143 Phase-2-Samples **konstant `13`**, kein einziger Ausreißer | **ave = 13.** Passt zu **keiner** der beiden R-010-Erwartungen (~2 für 2-DH5, ~3 für 3-DH5). Zusätzlich intern auffällig: `max = 4` ist kleiner als `ave = 13`, was bei „Durchschnitt ≤ Maximum" eigentlich nicht sein kann — Indiz, dass das Feld nicht das bedeutet, was R-010 unterstellt hat, oder dass „total/max/ave" nicht die drei Dinge sind, die die Beschriftung suggeriert. **Nicht aufgelöst, als Widerspruch gemeldet, nicht selbst gedeutet.** Die direktere Antwort auf die Kernfrage liefert die BQR-Zeile selbst (siehe unten). |
| 2 | Effektive MTU vs. angebotene MTU | `Effective MTU: 883` (A2DP LDAC State) gegen `MTU: 1005` (AVDTP Stream Control Block, Stream-Konfiguration für `SEP codec: LDAC`) | **883 (effektiv genutzt) gegen 1005 (verhandelt/angeboten).** Bestätigt den in `docs/state.md` bereits vermerkten Wert mit frischem Dump. |
| 3 | EDR-Fähigkeit der Gegenstelle | `EDR: true` / `Support 3Mbps: true` (A2DP Source State, Peer `SINK_A`) | **Ja, 3-Mbps-EDR wird von der Gegenstelle unterstützt.** Kein separates Remote-Features-Bitfeld im Dump gefunden — nicht geschätzt, nur diese beiden Zeilen als Beleg verwendet. |
| 4 | LDAC Host-Pfad oder Offload? | `codecConfigOffloading:` listet nur `SBC`, `AAC`, `Opus` (je `mCodecPriority:0`) — **LDAC fehlt in dieser Liste**, obwohl `mA2dpOffloadEnabled: true` gerätweit gesetzt ist. Zusätzlich: zwei `dumpsys`-Reads 3 s auseinander zeigten `Counts (enqueue/dequeue/readbuf)` von `14510/54412/68805` auf `14673/55023/69578` — die Zähler **bewegen sich**. | **Host-Encoder-Pfad**, nicht Offload — zwei unabhängige Belege stimmen überein (Codec fehlt in der Offload-Liste; `btif_a2dp_source`-Zähler laufen). Die im Auftrag genannten Logcat-Strings `software codec=` und `enabled offloading capability=` wurden **nicht gefunden** — weder im aktuellen Logcat-Ringpuffer (33 844 Zeilen, alle Buffer) noch gezielt gesucht; sie werden vermutlich nur beim initialen Verbindungsaufbau/Codec-Handshake einmalig geloggt und waren zum Zeitpunkt der Prüfung bereits aus dem Ringpuffer rotiert. Nicht geschätzt — als „nicht auffindbar" gemeldet. |
| 5 | Bietet das Gerät aptX an? | `Selectable: Rate=44100\|48000 Bits=16 Mode=STEREO` (Block `A2DP AptX State:`) | Ja, aptX (nicht aptX-HD/adaptive) ist in der Selectable-Liste vorhanden, `Config: Invalid` (nicht aktiv verwendet, da LDAC aktiv ist). |
| 6 | BQR-Abschnitt vorhanden? Monitoring-Modus? | `getprop persist.bluetooth.bqr.event_mask` → leer; `getprop persist.bluetooth.bqr.min_interval_ms` → leer | **Beide Properties sind auf diesem Gerät nicht gesetzt** (kein Wert, kein Fehler — die Property existiert schlicht nicht/ist leer). Damit lässt sich der Monitoring-Modus **nicht über Properties** bestimmen. Empirischer Ersatzbefund: Die 25 Ereignisse vom 09-02 sind unregelmäßig verteilt (Abstände von 1 s bis 6 min 24 s), keine feste Taktung erkennbar → spricht für **ereignisgetriggert**, nicht periodisch — als Lesart markiert, nicht als Beleg über Quelltext oder Property. **Wichtiger Zusatzbefund, nicht im Auftrag erwartet:** Der BQR-Abschnitt verhält sich **nicht wie ein reines Anhänge-Log**. Beim Phase-0-Read (09:04:23) enthielt er 25 Einträge vom Vortag (09-02, 22:33–22:53). Nach einigen weiteren `dumpsys bluetooth_manager`-Aufrufen (u. a. der Offload-Gegenprobe aus Punkt 4) las derselbe Abschnitt wenige Minuten später als `Event queue is empty.` — die 25 Altereignisse waren verschwunden. Das ist mit „lesen leert die Warteschlange" konsistent, aber **nicht am Quelltext verifiziert** — das wäre eine Recherche-Aufgabe, keine, die dieser Auftrag löst. Konsequenz für die Methodik: Ab diesem Punkt hat **ausschließlich** der Phase-2-Sampler `dumpsys bluetooth_manager` aufgerufen, um die Warteschlange nicht zusätzlich zu kontaminieren. |

## Phase 2 — 27,78 Minuten bei gepinnten 990 (09:10:28–09:38:15 Geräte-Lokalzeit)

**Werkzeug:** neu geschrieben, `docs/perf/tools/t032_run.sh` (die vorhandenen
Werkzeuge passten nicht: `m5_run.sh`/`snapshot.sh` erfassen keinen
BQR-Abschnitt). Kadenz: nominell 1 Hz, **gemessene** mittlere Kadenz
1,459 s/Sample (Ursache: `dumpsys bluetooth_manager` wird mit wachsendem
Log langsamer, wie bereits in T-001 dokumentiert — nicht neu untersucht).
1143 Samples, 1666,7 s Spanne. Aufruf: `adb shell "umask 077; sh
/data/local/tmp/btperf/t032_run.sh …"` (SR-012-Konvention aus T-029
übernommen). Rohdaten (Serie + Vollständigkeitsnachweis) liegen unter
`C:\Users\Daniel\t032-rawdata\t032_phase2\` (außerhalb des Repos).

Erfasst je Sample: `dropped`/`dropouts`/`underflow`/`flushed`/`max dropped`,
`Frames per packet (ave)`, `LDAC saved transmit queue length`,
Enqueue/Dequeue-Deviation-Zähler samt Zeitsummen, `LDAC quality mode`/Bitrate,
sowie ein BQR-Ereigniszähler pro Sample (vollständiger BQR-Block wird nur bei
`bqr_event_count > 0` als eigene Datei gesnapshottet — Begründung siehe
Skriptkommentar und Phase-1-Punkt 6).

**Alle 1142 Sample-zu-Sample-Übergänge geprüft** (Prüfmethode: die Felder
sind kumulative Zähler; ein Delta ungleich 0 an irgendeiner Stelle hätte
einen Wert ungleich 0 im entsprechenden Sample erzeugt — es reicht daher, auf
`>0` an jeder Stelle zu prüfen, was per `awk` über alle 1143 Zeilen geschah).

### Ergebnis, ungeschönt

| Größe | Befund über alle 1143 Samples |
|---|---|
| `dropped` | **0 in jedem einzelnen Sample** |
| `dropouts` | **0 in jedem einzelnen Sample** |
| `flushed` | 0 in jedem Sample |
| `max dropped` | 0 in jedem Sample |
| `LDAC quality mode` / Bitrate | `HIGH` / `990` in jedem Sample, kein einziger Stufenwechsel |
| `Frames per packet (ave)` | konstant `13` in jedem Sample |
| `LDAC saved transmit queue length` | 0 in 1142 von 1143 Samples, einmal `6` (Sample 1055, ≈09:36:07), danach sofort wieder 0 — kein begleitender `dropped`/`dropouts`-Zuwachs |
| `underflow` | monoton steigend 2 → 18 (7 Sprünge über 27,78 min, ≈0,58/min), keine Häufung, keine Korrelation mit dem Queue-Ausschlag |
| `bqr_event_count` | **0 in jedem einzelnen Sample** — kein einziges BQR-Ereignis im gesamten Fenster |

**Es gibt in diesem Messfenster keine Cluster.** Ein Cluster ist per
AK-T030-Kriterium definiert als ≥3 `dropouts`-Episoden in 30 s — bei
`dropouts` konstant 0 kann diese Bedingung nie erfüllt sein. Die gesamte
27,78-Minuten-Sitzung ist eine einzige, durchgehende Ruhephase.

Das steht in direktem Spannungsverhältnis zu T-029 (`docs/perf/T-029-990-korrelation.md`),
wo dieselbe Paarung bei gepinnten 990 kbps **10 Cluster in 25 min** zeigte.
**Lesart, ausdrücklich als solche markiert, nicht belegt:** T-029 hat die
WLAN-Assoziation explizit **nicht** geprüft (eigenes Zitat aus jenem Bericht:
„Die WLAN-Assoziation wurde in diesem Lauf nicht gesondert geprüft"). Dieser
Lauf hier zeigt für sein eigenes Fenster durchgehend **keine** WLAN-Assoziation.
Ob das den Unterschied erklärt, ist offen — es ist n=1 gegen n=1, keine
Wiederholung, keine kontrollierte Variable. Genauso plausibel ist reine
Uhrzeit-/Tagesabhängigkeit oder Zufall (T-029 selbst weist auf ungleiche
Cluster-Abstände zwischen 36 s und 4,4 min hin, sodass auch ein 27,78-min-Fenster
ohne jeden Cluster nicht per se unplausibel ist). **Diese Frage braucht
Wiederholungen, keine Interpretation aus einem einzelnen Gegenlauf.**

### Tabelle Cluster gegen Ruhephase

**Kann für das heutige Fenster nicht gefüllt werden — es gibt keinen Cluster
zum Vergleichen.** Ersatzweise unten die einzige verfügbare BQR-Firmware-Telemetrie
mit echten Choppy-Ereignissen: die 25 Einträge, die beim Phase-0-Read
(09:04:23) noch in der Warteschlange standen, datiert auf **09-02, 22:33–22:53**
— außerhalb des heutigen Messfensters, **keine gepaarte Ruhephase aus
derselben Sitzung verfügbar**. Nicht als Beleg für „heute" verwenden, nur als
Kontext für die Kernfrage (Pakettyp/RSSI/ReTx-NoRX-Verhältnis dieser Paarung
im Allgemeinen).

| Zeit (09-02) | Pakettyp | Sendeleistung (`PwLv`) | RSSI | `ReTx`/`TxTotal` | `ReTx`/`TxTotal` in % (abgeleitet, reine Division der beiden Rohwerte) | `NoRX` | `UnusedCh`/`UnidealCh` |
|---|---|---|---|---|---|---|---|
| 22:33:02 | 2DH5 | 14 | -51 | 1335/4186 | 31,9 % | 1191 | 38/0 |
| 22:33:20 | 2DH5 | 14 | -53 | 1416/4702 | 30,1 % | 1263 | 43/0 |
| 22:33:21 | 2DH5 | 14 | -51 | 69/216 | 31,9 % | 64 | 43/0 |
| 22:33:34 | 2DH5 | 14 | -58 | 1003/3346 | 30,0 % | 860 | 43/0 |
| 22:33:53 | 2DH5 | 14 | -54 | 1329/4830 | 27,5 % | 1257 | 59/3 |
| 22:34:35 | 2DH5 | 8 | -57 | 2611/10245 | 25,5 % | 2542 | 51/0 |
| 22:34:50 | 2DH5 | 14 | -58 | 975/3752 | 26,0 % | 941 | 50/0 |
| 22:35:19 | 2DH5 | 14 | -57 | 2112/7135 | 29,6 % | 1888 | 52/0 |
| 22:36:29 | 2DH5 | 14 | -51 | 4393/17453 | 25,2 % | 4099 | 47/0 |
| 22:37:15 | 2DH5 | 14 | -58 | 3058/11675 | 26,2 % | 2812 | 51/0 |
| 22:38:52 | 2DH5 | 14 | -49 | 5890/24142 | 24,4 % | 5611 | 53/0 |
| 22:39:09 | 2DH5 | 14 | -50 | 953/4063 | 23,5 % | 947 | 55/0 |
| 22:39:09 | 2DH5 | 14 | -49 | 51/167 | 30,5 % | 50 | 55/0 |
| 22:42:24 | 2DH5 | 16 | -46 | 11486/47315 | 24,3 % | 11330 | 59/9 |
| 22:42:25 | **2DH3** | 16 | -45 | 91/274 | 33,2 % | 87 | 59/5 |
| 22:46:39 | 2DH5 | 14 | -44 | 16060/62384 | 25,7 % | 15576 | 49/0 |
| 22:48:26 | 2DH5 | 14 | -43 | 6729/25812 | 26,1 % | 6656 | 59/0 |
| 22:48:27 | 2DH5 | 14 | -43 | 76/303 | 25,1 % | 78 | 59/0 |
| 22:49:11 | 2DH5 | 14 | -50 | 3074/10544 | 29,2 % | 3025 | 59/1 |
| 22:49:13 | 2DH5 | 14 | -52 | 154/586 | 26,3 % | 151 | 59/3 |
| 22:49:17 | 2DH5 | 14 | -47 | 286/1078 | 26,5 % | 274 | 59/4 |
| 22:49:19 | 2DH5 | 14 | -49 | 98/305 | 32,1 % | 98 | 59/0 |
| 22:49:19 | 2DH5 | 14 | -47 | 38/134 | 28,4 % | 37 | 59/0 |
| 22:49:22 | 2DH5 | 14 | -47 | 216/704 | 30,7 % | 209 | 59/4 |
| 22:53:55 | 2DH5 | 14 | -52 | 17785/66916 | 26,6 % | 17510 | 59/0 |

**Lesart, getrennt markiert:** 24 von 25 Ereignissen laufen auf `2DH5`, eines
auf `2DH3` — direkt vom Firmware-Zähler gemeldet, nicht aus „Frames per
packet" hergeleitet. `ReTx/TxTotal` liegt bei jedem Ereignis zwischen 23,5 %
und 33,2 % — bei starkem Signal (RSSI überwiegend zwischen -43 und -58 dBm).
Das stützt die im Auftrag zitierte Luftzeit-These (viel Wiederholung trotz
gutem Signal) stärker, als es die reine RSSI-Zahl allein täte — als Lesart,
nicht als neuer Beweis, da diese 25 Ereignisse nicht aus dem heutigen,
kontrollierten Fenster stammen und ihre Quell-Sitzung (WLAN-Zustand,
Stresspegel) nicht rekonstruierbar ist.

## Nachweisgrenzen (Dreierregel, wie in T-011/T-027)

Fenster: 27,78 min (1666,7 s), 1143 Samples.

| Größe | Beobachtet | Obergrenze bei Null-Beobachtung (3/27,78 min) |
|---|---|---|
| `dropouts` | 0 | **0,1080/min** |
| `dropped` | 0 | 0,1080/min (Zahl bleibt sichtbar, trägt laut Nutzerentscheidung 02.09. kein Verdikt — Nachweisgrenze trotzdem berichtet, da die Größe null blieb) |
| BQR-Ereignisse | 0 | 0,1080/min |

## Phase 3 — Fixture-Lücke: NICHT wie beauftragt geschlossen, aber Korrektur zum Sachstand

**Es konnte kein neuer Dump aus einem Cluster aufgenommen werden, weil im
gesamten 27,78-Minuten-Fenster kein Cluster auftrat** (`dropped`/`dropouts`
blieben durchgehend 0). Die Auftragsbedingung „Dump aus einem Cluster, mit
`dropped`/`dropouts` ungleich 0" ist damit aus dieser Sitzung heraus nicht
erfüllbar — eine Aufnahme zu erzwingen hätte entweder Wartezeit über die
vorgegebenen 25–30 min hinaus (nicht beauftragt) oder einen Stresshebel
gebraucht (**ausdrücklich Scope-Grenze, nicht angewendet**).

**Wichtiger Fund, der die Ausgangslage des Auftrags korrigiert:** Eine
verbatim-Aufnahme mit genau dieser Eigenschaft **existiert bereits** im Repo:
`core-monitor/src/test/resources/dumps/bt_manager_pixel11_ldac_990_loss.txt`
(2026-09-02, T-022, vollständiger Dump, `Counts (flushed/dropped/dropouts) =
0 / 1851 / 74`, `LDAC quality mode: HIGH`, 990 Kbps, dokumentiert im
`README.md` desselben Verzeichnisses). Sie ist **nicht** an einen Golden-Test
angebunden (per Grep über `core-monitor/src/test` bestätigt — nur das
`README.md` erwähnt die Datei), aber die Datei selbst erfüllt Phase 3 bereits.
**Empfehlung an den Director:** Die Notiz in `docs/state.md` („Kein
aufgenommener Dump aus dem 990er-Arm mit `dropped`/`dropouts` ungleich 0")
ist damit **veraltet** — die eigentliche Lücke ist ein fehlender Golden-Test
auf einer bereits vorhandenen Fixture, nicht ein fehlender Dump. Das ist
`developer`/`qa-engineer`-Arbeit, nicht meine, und wird hier nur gemeldet.

Kein neues Fixture-File wurde abgelegt.

## Kernfrage: In welcher Link-Klasse liegt diese Paarung?

**Direkter Befund (Firmware-Telemetrie, nicht Heuristik):** Die 25 BQR-Choppy-
Ereignisse vom 09-02 (Tabelle oben) melden **`2DH5`** in 24 von 25 Fällen und
einmal `2DH3` — das Firmware-Feld nennt den Pakettyp direkt, ohne Ableitung
über „Frames per packet". Das stützt die 2-DH5-Annahme aus R-009/R-010
direkter, als es die dortige Heuristik selbst konnte.

**Indirekter Befund (die im Auftrag vorgeschlagene Heuristik), widersprüchlich:**
`Frames per packet (ave) = 13`, konstant über alle 1176 heute gelesenen
Samples (Phase 0 + Phase 2). Das passt zu **keiner** der beiden R-010-Annahmen
(~2 für 2-DH5, ~3 für 3-DH5), und `max = 4 < ave = 13` ist intern
unstimmig. **Diese Heuristik trägt auf diesem Gerät nicht** — entweder
bedeutet das Feld etwas anderes als angenommen, oder die Ableitung „ave ≈
Pakettyp-Indikator" war von Anfang an nicht so belastbar, wie R-010 selbst
schon einräumte („plausibel, nicht belegt"). Empfehlung: Diese Heuristik
nicht weiterverwenden, den direkten BQR-Pakettyp stattdessen als primäre
Quelle behandeln.

**Zusammengenommen:** Die Paarung läuft (zumindest im vom BQR erfassten
Fenster gestern) überwiegend auf **2-DH5**, mit vereinzeltem Rückfall auf
2-DH3. Damit liegt sie **nicht** in der 2-DH3-Klasse, die laut Auftrag
„keine Maßnahme aus R-010 Teil 1" mehr hilft — die Luftzeit-Rechnung aus
R-009/R-010 bleibt anwendbar. **Diese Aussage stützt sich ausschließlich auf
den 09-02-Datensatz**, nicht auf das heutige Fenster, weil im heutigen
Fenster kein einziges BQR-Ereignis auftrat, um den Pakettyp erneut zu
bestätigen. Für „heute, ohne WLAN-Assoziation" gibt es **keinen** direkten
Pakettyp-Beleg — nur den indirekten (und als unstimmig erkannten)
`Frames per packet`-Wert.

**Zur eigentlichen Kapazitätsfrage** („kann 990 auf dieser Paarung tragen"):
Ja, im gemessenen Fenster **eindeutig** — 27,78 Minuten durchgehende Wiedergabe,
`dropped`/`dropouts` durchgehend 0, keine Stufenrückstufung, keine ABR-Aktivierung,
kein einziges BQR-Ereignis. Das ist ein reiner Messbefund für **dieses eine
Fenster**, keine Garantie für andere Zeitpunkte — T-029 zeigt für dieselbe
Stufe an einem anderen Tag das Gegenteil (10 Cluster in 25 min). Beide
Befunde sind gültig für ihr jeweiliges Fenster; keiner widerlegt den anderen,
sie beschreiben unterschiedliche Momente.

## Offene Punkte

1. **Read-Clear-Verhalten der BQR-Warteschlange** ist nur indirekt beobachtet
   (25 Ereignisse verschwanden nach mehreren `dumpsys`-Aufrufen), nicht am
   Quelltext verifiziert. Sollte recherchiert werden, bevor ein Messapparat
   fest darauf aufbaut, der mehrfach pro Sitzung liest.
2. **`Frames per packet (ave) = 13`**, konstant, unstimmig mit `max = 4` und
   mit der R-010-Heuristik — ungeklärt, was das Feld tatsächlich zählt.
   Kandidat für eine Quelltext-Recherche (`btif_a2dp_source.cc`).
3. **Kein Cluster im heutigen Fenster** — die Kernfrage „Verlust-Cluster
   gegen Ruhephase, heute, ohne WLAN" bleibt für dieses eine Fenster
   unbeantwortet, weil es keinen Cluster gab. Braucht Wiederholung, falls die
   Frage weiter verfolgt werden soll — keine Garantie, dass eine Wiederholung
   einen Cluster produziert.
4. **`software codec=` / `enabled offloading capability=`** nicht im
   Logcat-Ringpuffer gefunden — vermutlich nur beim initialen Verbindungsaufbau
   geloggt. Für einen sauberen Beleg bräuchte es einen Read gleich nach einem
   frischen Connect, nicht Minuten danach.
5. **WLAN/Bildschirm/Akku wurden nicht fortlaufend während der 27,78 Minuten
   geprüft**, nur an den Blockgrenzen — Methodik-Lücke, siehe Zustandsbuch-Abschnitt.
   Eine fortlaufende, von `dumpsys bluetooth_manager` unabhängige Nebenprüfung
   (nur `dumpsys wifi`/`dumpsys power`/`dumpsys battery`) wäre beim nächsten
   Lauf ergänzbar, ohne die BQR-Warteschlange zu stören.
6. **`persist.bluetooth.bqr.event_mask`/`min_interval_ms` sind auf diesem
   Gerät leer** — der Monitoring-Modus (periodisch/ereignisgetriggert) ist
   damit nur indirekt (Lesart) bestimmt, nicht per Property belegt.
7. **`bt_manager_pixel11_ldac_990_loss.txt` ist unverknüpft** — siehe Phase 3.
   Empfehlung an Director/`developer`/`qa-engineer`, keine eigene Handlung
   hier.

## Werkzeug

Neu angelegt: `docs/perf/tools/t032_run.sh` (Device-Sampler für Phase 2 inkl.
BQR-Watcher, Kommentare im Skript erklären die Read-Clear-Beobachtung und die
Blockgrenzen-Erkennung ohne Leerzeilen-Annahme). Host-seitiges Parsing lief
ad hoc über `awk` gegen `series.log`, nicht als eigenes Skript abgelegt (nur
eine CSV-Umwandlung, kein wiederverwendbares Werkzeug wie `parse.sh`/`compare.sh`).
