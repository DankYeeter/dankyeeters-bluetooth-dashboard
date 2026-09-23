# T-036 — Trennmessung: 990 gepinnt, WLAN 2,4 GHz mit/ohne

Rolle: `performance-tuner`. Auftrag `docs/tasks/T-049.md` Abschnitt T-049b
(T-036), Änderung 23.09. 19:19 (kein Nutzer verfügbar). Gerät `67011FDKX004XG`
(Pixel 11 Pro, Android 17), `platform-tools/adb.exe` (R-2). Sink: Noble FoKus
Prestige Encore (`XX:XX:XX:XX:37:8F`). Kein `dumpsys bluetooth_manager` zur
Zwischenkontrolle (R-011) — einziger Leser während jedes Messfensters ist der
Sampler selbst (`docs/perf/tools/t032_run.sh`, unverändert wiederverwendet).
WLAN selbst per `adb shell svc wifi enable|disable` geschaltet (Nutzer-Vorgabe
19:19). Musik: Tidal (PID 11069, durchgehend unverändert seit T-048, nie
angefasst). Telefon/Kopfhörer nicht bewegt.

**Reihenfolge:** mit → ohne. Begründung: WLAN war beim Start bereits mit
2,4 GHz assoziiert (Vorwert der Sitzung), das spart einen Umschaltvorgang;
danach ein `svc wifi disable` für „ohne“, am Ende ein `svc wifi enable` zur
Rückstellung auf den Vorwert — pro Reihenfolge exakt ein Toggle in jede
Richtung, unabhängig von der Wahl.

## Vorlauf — Pin-Werkzeug

Die App stellt „990 gepinnt“ nur über die eigene UI (`BitrateSection.kt`,
Chip „990 kbps“), die den privilegierten Helfer braucht. Der App-eigene Weg
dahin (`ActivateScreen`) verlangt Wireless Debugging, das laut
`WirelessDebuggingSwitch.kt`-KDoc bei gestecktem USB-Kabel nicht anbleibt —
mit dem einzigen zugelassenen `adb` (R-2, USB) also strukturell blockiert.
Stattdessen der bereits in T-048 dokumentierte Weg (`docs/berichte/T-048-qa-engineer.md:20-25`):
Helfer direkt per `adb shell` mit dem in `shared_prefs/privileged.xml`
bereits hinterlegten aktiven Token gestartet (`PrivilegedClient.shellCommand`,
Token unverändert übernommen, kein neuer `pending_token` nötig, kein Root).
Damit App-UI (Chip „990 kbps“ antippen, per `uiautomator dump`/`input tap`
lokalisiert) plus unabhängiger Einzel-Read-back (`dumpsys bluetooth_manager`,
kein Zwischenaufruf während eines Messfensters) je Pin-Vorgang. App und
Helfer danach **immer** per `am force-stop`/`kill` beendet und **vor jedem
Messfenster per `ps` belegt leer** (T-049a-Vorgabe, F-012).

**Werkzeug-Fund (behoben):** `docs/perf/tools/t032_run.sh` lag mit CRLF im
Repo (kein `.gitattributes`, `core.autocrlf=true`) und scheiterte auf dem
Gerät an `mkdir`/Pfad-Korruption (`sh -x`-Beleg: Pfade mit eingebettetem
`\r`). Datei auf LF normalisiert, `.gitattributes` mit `docs/perf/tools/*.sh
text eol=lf` ergänzt, damit ein künftiger Checkout auf Windows sie nicht
erneut zerschießt. Beide Änderungen sind Teil dieses Commits.

## Arm „mit“ — 2,4 GHz WLAN assoziiert, 990 gepinnt

**Zustandsbuch Start (Run 2, massgeblich):** 20:58:xx, WLAN „SSID_A“
(`SSID_A`), 2437 MHz, `wifi_scan_always_enabled=1`, `ble_scan_always_enabled=1`,
Akku 76–79 %, USB powered, `mWakefulness=Awake`, Tidal PID 11069 spielt,
`ps` für `btdash`/`btdash_privileged` leer, LDAC `HIGH`/990 unabhängig
verifiziert (`pin_verify2_2058.txt`, 20:58:05).

**Zwei Läufe nötig — Run 1 kontaminiert, Run 2 grösstenteils gültig:**

| Lauf | Fenster (Geräte-Lokalzeit) | Gültig gepinnt (990) | Ereignis |
|---|---|---|---|
| Run 1 | 20:22:41–20:53:08 (1298 Samples) | Sample 1–175 = **4,07 min** | Sample 176 (≈20:26:47): `Counts (flushed/dropped/dropouts)` springt auf `0/0/0` — A2DP-Neuverbindung. Codec fällt auf ABR/330 kbps, weil kein App-Prozess lief, der den Pin beim Reconnect neu stellt (`BitrateSection.kt`: „asked for again on every connect“ — nur wirksam, wenn die App läuft). Danach 20:28:32 zusätzlich Launcher-Neustart der App (`realCallingUid=10273 com.google.android.apps.nexuslauncher`, `logcat`), **nicht durch mich ausgelöst** — erst 1 min 45 s nach dem Reconnect, separates Ereignis. |
| Run 2 | 20:58:56–21:29:12 (1294 Samples) | Sample 1–954 = **22,34 min** | Sample 955 (≈21:21:16): identisches Muster, `Counts` erneut `0/0/0`. Kein App-Neustart diesmal (`ps` bis Laufende leer). |

**Direkter Beleg für beide Reconnects (BQR, nicht Heuristik):**
`bqr_snapshot_169.txt` (Run 1) — „Approach LSTO" 20:26:36, `ReTx: 119952 /
TxTotal: 137578` (≈87 %); `bqr_snapshot_948.txt` (Run 2) — „Approach LSTO"
21:21:07, `ReTx: 21448 / TxTotal: 22819` (≈94 %). Beide auf Handle `0x000b`,
Paket-typ `2DH5`/`DM1`. Extreme Wiederholraten kurz vor dem jeweiligen
Verbindungsabbruch — die Ursache liegt am Funklink, nicht an meinem Vorgehen.

**Zahlen im gültigen Fenster (Delta ab Fensterstart, Baseline `0/0/0` beide
Läufe):**

| Grösse | Run 1 (4,07 min) | Run 2 (22,34 min) |
|---|---|---|
| `dropped` | +44125 (≈10839/min) | +475 (≈21,3/min) |
| `dropouts` | +1765 (≈434/min) | +19 (≈0,85/min) |
| `underflow` | n. a. (Baseline ungleich 0, Vorlauflast) | 258→1148 (+890) |

Run 1 traf offensichtlich mitten in eine bereits laufende Verlustphase (der
Reconnect 20:26:47 ist ihr Ende, nicht ihr Anfang) — die Rate ist ~500× höher
als in Run 2 und nicht als „Normalzustand“ zu lesen, sondern als Momentaufnahme
der Verschlechterung, die zum LSTO-Ereignis führte.

**In Run 2 konzentrierten sich fast alle Verluste auf 3 Samples** (919–921,
t≈1291,8–1294,6 s, ≈2,8 s): `dropped` 0→125→400→475, `dropouts` 0→5→16→19,
`underflow` im selben Fenster 258→298→368→436 (bewegt sich **mit**, nicht
statisch) — danach bis zum Reconnect (Sample 954) `dropped`/`dropouts`
unverändert bei 475/19, während `underflow` weiter bis 1148 steigt. **Die
AK-T009-24-Doppelaufnahme (Fenster mit `underflow` ruhig, `dropouts`
zählend) ist aus diesen Daten NICHT herstellbar** — die Verlustepisode war zu
kurz (Sekunden, nicht Minuten) und `underflow` bewegte sich während ihr
mit, nicht ruhig. Wie schon in T-032 bleibt die vorhandene Fixture
(`bt_manager_pixel11_ldac_990_loss.txt`, dort unverändert, unverknüpft) der
einzige Treffer für dieses Muster — keine neue Aufnahme abgelegt.

**Zustandsbuch Ende Run 2 (21:29:xx):** WLAN weiter „SSID_A“/2437 MHz
unverändert, Scans unverändert `1`/`1`, Akku 78 %, USB powered, Awake, Tidal
unverändert PID 11069, `ps` leer, letzter Sample-Codec ABR/660 (nach dem
zweiten Reconnect, erwartet).

## Arm „ohne“ — WLAN aus, 990 gepinnt

Vor dem Fenster erneut gepinnt (dritter Pin-Zyklus, unabhängig verifiziert
`pin_verify3_ohne_start.txt`, 21:31:30, `HIGH`/990, `Priority: 1000000`), App
und Helfer beendet, `ps` leer, dann `svc wifi disable` (21:31:xx), Read-back
„Wifi is disabled“ vor Fensterstart.

**Zustandsbuch Start:** 21:31:54, WLAN **disabled** (keine Assoziation),
`wifi_scan_always_enabled=1`, `ble_scan_always_enabled=1`, Akku 79 %, USB
powered, Awake, Tidal PID 11069, `ps` leer.

**Fenster:** 21:31:54–22:02:09 (1285 Samples, **30,25 min, ununterbrochen**).
**Kein einziger Reconnect** (`Counts` nie zurückgesetzt, ein einziger,
zusammenhängender Wert über das gesamte Fenster), **kein** BQR-Ereignis,
Codec durchgehend `HIGH`/990 (letzter Sample: `Priority: 1000000`, `LDAC
quality mode: HIGH`, `990 Kbps`).

| Grösse | Wert über alle 1285 Samples |
|---|---|
| `dropped` | **0 in jedem Sample** |
| `dropouts` | **0 in jedem Sample** |
| `flushed` | 0 in jedem Sample |
| `underflow` | **0 in jedem Sample** (durchgehend, kein einziger Anstieg) |
| BQR-Ereignisse | 0 |
| Reconnects | 0 |

**Zustandsbuch Ende:** 22:02:09, WLAN weiter disabled, Scans unverändert,
Akku 81 %, `status: 4` (not charging, USB weiter powered — Nebenbefund, ohne
Bezug zur Messung), Awake, Tidal unverändert, `ps` leer.

**Rückstellung:** `svc wifi enable` (22:04:0x), Read-back bestätigt „Wifi is
enabled … connected to SSID_A" — identisch zum Vorwert der Sitzung.

## Kernfrage — Widerspruch T-029 gegen T-032

**Aufgelöst, mit Repro in derselben Sitzung:** Bei 2,4-GHz-WLAN-Assoziation
traten in **beiden** Armen reale Verluste auf (475/19 bzw. 44125/1765 je
Fenster) **und** zwei unabhängige, BQR-belegte Fast-Abbrüche (87 %/94 %
Wiederholrate) — das deckt sich mit T-029 (10 Cluster in 25 min). Ohne
WLAN-Assoziation: **0 Verluste, 0 BQR-Ereignisse, 0 Reconnects** über volle
30,25 min — deckt sich exakt mit T-032 (0 Verluste in 27,78 min). Beide
früheren Befunde sind damit **kein Widerspruch mehr**, sondern zwei
Stichproben derselben Abhängigkeit: **WLAN-Assoziation korreliert in dieser
Sitzung mit Verlusten und mit Verbindungsabbrüchen auf dieser Paarung.**
n=2 je Zustand (heute) plus je 1 historische Messung — kein Beweis über
Kausalität (kein kontrolliertes Interferenzexperiment, keine RSSI-Kontrolle),
aber die stärkste bisherige Übereinstimmung. **Massnahme 1 aus R-010** (WLAN
während Wiedergabe meiden) ist damit durch eigene Messung gestützt, nicht nur
plausibilisiert.

## Fund für den Director (kein Fix hier)

**Der 990-Pin übersteht einen A2DP-Reconnect nicht, wenn App und Helfer
beendet sind** — beide heutigen Reconnects fielen auf ABR zurück, weil das in
`BitrateSection.kt` beschriebene „asked for again on every connect" einen
laufenden App-Prozess braucht. Das steht in **strukturellem Spannungsverhältnis
zur F-012-Vorgabe** (App vor jedem Messfenster beenden, damit sie nicht im
Hintergrund `dumpsys bluetooth_manager` abfragt): Für lange unbeaufsichtigte
990er-Messfenster gibt es damit keine Konfiguration, die gleichzeitig (a) den
Pin über einen echten Reconnect rettet und (b) F-012 vermeidet. Zusätzlich:
ein Reconnect kann die App unabhängig von mir wieder in den Vordergrund holen
(Launcher-Neustart `nexuslauncher`, Run 1, 20:28:32) — „App vor jedem Arm
beendet" ist damit keine für die volle Fensterdauer garantierte Grösse,
sondern nur ein am Anfang belegter Zustand. Empfehlung: Architektur-Frage an
`architect`/`developer` (Reconnect-Listener, der unabhängig vom
UI-Prozess läuft?), keine eigene Umsetzung hier.

## Rohdaten

Serien-Logs, BQR-Snapshots, Read-backs, `logcat`-Auszüge und UI-Dumps liegen
ausschliesslich unter dem session-gebundenen Scratchpad dieses Laufs
(`…\scratchpad\T-049b\`, Pfad in der Rückgabe an den Director), **nicht** im
Repo — analog zur T-032-Konvention. Der Scratchpad ist temporär; wer die
Rohdaten über diese Sitzung hinaus braucht, sollte sie vor Aufräumen
sichern lassen (Hinweis an Director/Archivist).

## Nachweisgrenzen (Dreierregel)

| Fenster | Dauer | Nullbeobachtung-Obergrenze (3/Dauer) |
|---|---|---|
| Arm ohne (gesamt) | 30,25 min | 0,099/min |
| Arm mit, Run 2 (gültig) | 22,34 min | 0,134/min |
| Arm mit, Run 1 (gültig) | 4,07 min | 0,737/min |
