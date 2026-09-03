# Stand — 2026-09-03, Part 5

## UEBERGABE an die naechste Session — hier geht es weiter

**T-032 ist gelaufen und ausgewertet.** Volltext `docs/perf/T-032-readback.md`.
Rohdaten `C:\Users\Daniel\t032-rawdata` (ausserhalb Repo, MACs).
Neues Werkzeug `docs/perf/tools/t032_run.sh` (Sampler mit BQR-Watcher).

**DER BEFUND: 27,78 min bei gepinnten 990, ohne WLAN-Assoziation — 0 Verluste.**
1143 Samples, alle 1142 Uebergaenge geprueft. `dropped`/`dropouts`/`flushed`/
`max dropped` in **jedem** Sample 0. Kein Stufenwechsel, kein BQR-Ereignis.
Queue einmal auf 6, sofort zurueck. `underflow` 2->18 (0,58/min, keine Haeufung).
Nachweisgrenze (Dreierregel) **0,1080/min**.

**Das steht gegen T-029** (10 Cluster in 25 min, gleiche Paarung, gleiche Stufe).
Der Unterschied: T-029 hat die WLAN-Assoziation nie geprueft, heute war
durchgehend keine da. **Das ist eine Hypothese, keine Erklaerung** — n=1 gegen
n=1, keine kontrollierte Variable. T-029 nennt selbst Ruhephasen bis 4,4 min.
**Die naechste Messung muss genau das trennen:** dieselben 30 min mit
2,4-GHz-Assoziation gegen ohne. Das ist der erste echte Kandidat fuer einen
belegten Hebel bei 990 — und zugleich der Test von Massnahme 1 aus R-010.

**Zustandsbuch-Abweichung, dokumentationspflichtig:** Der Nutzer meldete
„WLAN aus“. Das Radio war **an** (`Wifi is enabled`), nur **nicht assoziiert**
(`Supplicant state: DISCONNECTED`), `wifi_scan_always_enabled` = 1. Funktional
die staerkste Auspraegung von „kein WLAN im Spiel“, aber nicht „aus“.

### Linkfakten (Phase 1) — die Kernfrage ist beantwortet

| Groesse | Befund |
|---|---|
| **Pakettyp (BQR, direkt)** | **2DH5** in 24/25 Ereignissen, 1x 2DH3 |
| Effektive MTU | **883** gegen verhandelte 1005 (frisch bestaetigt) |
| EDR Gegenstelle | `EDR: true`, `Support 3Mbps: true` |
| Encoder-Ort | **Host-Pfad belegt** — LDAC fehlt in `codecConfigOffloading` trotz `mA2dpOffloadEnabled: true`; Zaehler laufen |
| aptX | **wird angeboten** (`Selectable`, 44,1/48 kHz, 16 bit), inaktiv |
| ReTx/TxTotal (BQR, 09-02) | **23,5-33,2 %** bei RSSI -43 bis -58 dBm |

**Damit ist die Kernfrage beantwortet: Die Paarung liegt in der 2-DH5-Klasse**,
nicht in 2-DH3. Massnahmen aus R-010 Teil 1 koennen also greifen. Vorbehalt:
Die BQR-Belege stammen vom 09-02; heute gab es kein einziges BQR-Ereignis.

**Wiederholrate 23-33 % bei starkem Signal** ist der bisher staerkste Beleg
fuer die Luftzeit-These: Nicht schlechter Empfang, sondern Wiederholungen, die
Sendezeit fressen. RSSI allein haette hier „alles gut“ gesagt.

### Drei Korrekturen, die Bestand haben

1. **`Frames per packet (ave)` taugt NICHT als Pakettyp-Indikator** — der
   Schluss haelt, **meine Begruendung dafuer war ueberdehnt** (aufgedeckt vom
   `developer` in T-034, 03.09.). Richtig ist:
   - T-032 las `115111 / 4 / 13` — dort ist `max=4` kleiner als `ave=13`, was
     bei „Durchschnitt <= Maximum“ nicht sein kann. **Diese Auffaelligkeit
     gilt fuer diese Aufnahme**, nicht als Eigenschaft des Feldes.
   - Die 990er-Verlust-Fixture vom 02.09. liest `2763962 / 12 / 12` —
     **intern voellig stimmig**, `max` gleich `ave`.
   - **Der Wert ist auch nicht „konstant 13“**, wie ich geschrieben hatte. Er
     war konstant 13 **innerhalb** des T-032-Laufs; ueber Aufnahmen hinweg
     steht 12 gegen 13.
   **Was traegt:** Weder 12 noch 13 passt zu den R-010-Erwartungen (~2 fuer
   2-DH5, ~3 fuer 3-DH5). Die Heuristik faellt aus — wegen der
   Groessenordnung, nicht wegen eines internen Widerspruchs. **Neue offene
   Frage:** Warum liest dasselbe Feld am selben Geraet einmal 4/13 und einmal
   12/12? Gehoert an `researcher` oder `performance-tuner`, nicht gedeutet.
   Verwendet wird der direkte BQR-Pakettyp.
2. **Die Fixture-Luecke 1 war falsch beschrieben — und ist jetzt geschlossen.**
   Ein 990er-Dump mit echtem Verlust existierte bereits
   (`bt_manager_pixel11_ldac_990_loss.txt`, T-022, 0/1851/74, HIGH, 990). Die
   echte Luecke war der fehlende Golden-Test. **T-034 hat ihn gebaut** —
   Commit `0dbea4e`, sechs Tests, Rot-vorher-Beleg ueber vier Mutationen,
   Bericht `docs/tasks/T-034-bericht.md`. **QA-Retest erledigt und
   abgenommen** (`docs/tasks/T-034-retest-bericht.md`): alle vier Mutationen
   woertlich reproduziert, **14 eigene** gefahren, fuenf weitere Regressionen
   gefangen. Drei neue Befunde QA-014..QA-016, keiner blockiert. **QA-014 vor
   dem naechsten Anfassen von `MonitorDatabase` erledigen.**
   **Einschraenkung, die zur Abnahme gehoert:** Die Datei prueft **AK-T009-24
   nicht** — mechanismus-gebunden belegt, der wieder eingebaute QA-001-Fehler
   laesst alle sechs Tests gruen. Die Zielaussage gilt fuer den Zaehler- und
   Bitratenpfad, **nicht** fuer den Verlust-Verdikt-Pfad; meine
   Auftragsformulierung war dort zu weit.
   Praezisierung des `developer`: „von keinem Testcode geladen“ stimmte nicht
   ganz — `FixtureSweepTest` zaehlt das Verzeichnis auf und schickt die Datei
   durch jeden Parser, aber nur gegen Invarianten, die auch gelten, wenn jeder
   Zaehler `null` gelesen wird. **Die Datei war geladen und unbehauptet.**
   Das ist die genauere Fassung.
3. **Der BQR-Abschnitt leert sich beim Lesen.** 25 Altereignisse waren beim
   ersten Read da, nach weiteren `dumpsys`-Aufrufen `Event queue is empty.`
   **Lesart, nicht am Quelltext belegt** — aber folgenschwer: Eine App, die
   periodisch pollt, **loescht die Telemetrie, die sie anzeigen will**, und
   nimmt sie zugleich jedem anderen Leser weg. Das beruehrt AK-7 des
   Zielbild-Entwurfs unmittelbar. **Vor jedem Bau am BQR-Kanal per
   `researcher` am Quelltext klaeren.**

**Fehlalarm im Bericht:** Der tuner meldet, „eine andere Session“ habe waehrend
seiner Laufzeit `docs/state.md` und `T-029` veraendert und den GOAL-Entwurf
angelegt. Das war **der Director in derselben Sitzung**, nicht eine parallele
Session. Kein Konflikt, keine Klaerung noetig.

**`GOAL.md` ist neu gefasst und in Kraft — abgenommen vom Nutzer 03.09.**
Der Entwurf `docs/GOAL-entwurf-part5.md` wurde durchgesprochen, mit zwei
Aenderungen angenommen und **geloescht**; sein Inhalt ist jetzt `GOAL.md`.
Drei Saeulen: Anzeigen → Stellen → Optimieren. 990 ist das Ziel, nicht der
Fehler. AK-1..AK-6 unveraendert, **AK-8..AK-16 neu in Kraft** (neun Stueck).

Die zwei Aenderungen des Nutzers:
- **AK-7 zurueckgestellt, Nummer reserviert.** Erst nach der Quelltext-
  Klaerung des BQR-Read-Clear. **Bis dahin wird an diesem Kanal nichts
  gebaut.** Laeuft als T-033.
- **AK-14 umgeformt auf „ohne kuenstliche Stoerung“.** Grund: kein belegter
  Stresshebel. **Damit ist die Testsuite nicht mehr blockiert** — sie
  vergleicht Vorher/Nachher ohne kuenstlichen Reiz.

## AK-7 — der Stand nach R-011 und T-035 (03.09.)

**R-011: Read-Clear ist belegt, und zwar in der schlechtesten Variante.**
`bqr::DebugDump()` in `btif_bqr.cc` dequeued und loescht die Eintraege in einer
Schleife; bei leerer Queue druckt sie woertlich `Event queue is empty.` — genau
der Text aus T-032. Auf `main` und `android17-release` identisch, dreifach
gegengeprueft. **Es gibt keinen Leser-Cursor:** eine einzige prozessweite
Instanz, wer liest nimmt die Ereignisse allen weg. Zweiter, unabhaengiger
Verlustweg: die Queue fasst **25** Eintraege (`kBqrEventQueueSize`) und
verdraengt beim Ueberlauf den aeltesten — das erklaert die exakt 25 Ereignisse
in T-032, die Queue war voll.

**Folge, hart:** Eine pollende Anzeige ist nicht baubar, ohne AK-1
(Nicht-Einmischung) zu verletzen. Sie wuerde fremde Diagnose zerstoeren.
**`dumpsys bluetooth_manager` ist ab sofort ein Eingriff, kein Read-back** —
in jedem kuenftigen Auftrag mitdenken, der die BQR-Daten braucht.

**T-035: Der Ausweg ist offen.** Der nicht-destruktive Kanal
`registerBluetoothQualityReportReadyCallback` verlangt `BLUETOOTH_PRIVILEGED`.
**uid 2000 haelt sie** — deklariert, gewaehrt und **zur Laufzeit durchsetzbar**,
belegt mit drei unabhaengigen Verfahren, darunter ein echter
`checkPermission`-Aufruf aus einem `app_process`-Kontext unter uid 2000. Die
Methode existiert auf dem Geraet, ist `public`, kein Hidden-API-Fehler.
Volltext `docs/perf/T-035-readback.md`. Die BQR-Queue blieb unberuehrt.

**Die eine Frage, an der AK-7 jetzt noch haengt — ausdruecklich ungeklaert,
nicht geraten:** Speist der Callback aus **derselben** Queue, die `DebugDump()`
leert, oder aus einem eigenen Zustellweg? R-011 legt einen eigenen nahe, hat
ihn aber nur einfach belegt, nicht gegengeprueft. **Speist er aus derselben,
ist nichts gewonnen.**

**Entscheidung des Nutzers 03.09.:** Diese Frage wird **mit der Trennmessung
verbunden, aber in getrennten Laeufen** — die Trennmessung darf nicht durch
den noetigen `dumpsys`-Aufruf verfaelscht werden. Reihenfolge:
1. **T-036 Trennmessung** — 30 min bei 990 **mit** 2,4-GHz-Assoziation gegen
   30 min **ohne**. Kein `dumpsys bluetooth_manager` zur Zwischenkontrolle.
   Klaert den Widerspruch T-029 gegen T-032 und testet Massnahme 1 aus R-010.
2. **T-037 Callback-Probe** — direkt danach, eigener kurzer Lauf: Callback
   registrieren, echtes BQR-Ereignis abwarten, dann **einmalig** pruefen, ob es
   im Dump noch steht. Braucht Stoerung, deshalb nach der 2,4-GHz-Zelle.
   **Vor jedem Helfer-Kommando daraus: `security-reviewer`, AK-10.**
   Ein Wegwerf-Diagnosewerkzeug ausserhalb des Repos ist kein Helfer-Kommando
   und faellt nicht darunter (Director-Entscheidung 03.09., Anlass T-035).

Beide brauchen das Geraet: Kopfhoerer verbunden, Musik, 990 gepinnt, und fuer
die erste Zelle WLAN auf 2,4 GHz assoziiert.

**Sobald R-011 da ist, sind das die naechsten Schritte:**
1. AK-7 entscheiden — festschreiben, umformulieren oder streichen. Nur der
   Nutzer.
2. **Die Trennmessung:** 30 min bei 990 **mit** 2,4-GHz-Assoziation gegen 30
   min **ohne**. Das ist der Kandidat, der aus dem Widerspruch T-029 gegen
   T-032 folgt, und zugleich der Test von Massnahme 1 aus R-010. Rolle
   `performance-tuner`; Geraetezustand vorher beim Nutzer bestaetigen.
3. **Golden-Test: gebaut (T-034), QA-Retest offen.** Siehe Korrektur 2.
   **Neue Anforderung an T-036, aus T-034 hervorgegangen:** R-D ist weiterhin
   nur gegen handgesetzte Zaehler belegt, weil `A2dpTxDelta.lossByChannel` auf
   einem **Delta zwischen zwei Lesungen** lebt und nur **eine** Aufnahme im
   Repo liegt. Die aeltere T-022-Lesung existiert nicht mehr (geprueft: C:\Users\Daniel\
   haelt nur t027-, t029- und t032-rawdata). **T-036 muss deshalb zwei
   Aufnahmen im Abstand von ~4 min aus derselben Verlustphase mitnehmen** —
   damit wird AK-T009-24 am Geraetedump statt an erfundenen Zahlen belegbar.
   **Bedingung an das Intervall (Director-Entscheidung 03.09.):** Es muss eines
   sein, in dem `underflow` sich **nicht bewegt**, waehrend `dropouts` zaehlen.
   Grund: `UI_SPEC.md:2361` formuliert das Kriterium als **Snapshot ueber ein
   Fenster** („`underflows` = 0, `dropouts` = 21 **in 97 s**“) — Fensterwerte,
   nicht absolute Zaehlerstaende. Genau dieser Fall trat in T-022 auf
   (`underflow` 623 → 623 bei steigenden `dropouts`). Damit traegt die
   Doppelaufnahme **beide Haelften** des Kriteriums.

**Erledigt in dieser Sitzung (03.09.):**

- Research-Block T-031 vollstaendig gelesen und ausgewertet (R-008/R-009/R-010),
  Ergebnis unten unter "Auswertung des Research-Blocks".
- `docs/state.md` Part 5 committet und gepusht (e08a692).
- **PII bereinigt.** `T-027-messung.md`, `T-027-messung-24ghz.md`,
  `T-028-hoersitzung-reizplan.md` (22 Fundstellen) und nachtraeglich
  `T-029-990-korrelation.md` (1 Fundstelle). Platzhalter konsistent:
  `SSID_A`, `AP_BSSID`, `IP_1` (Host), `IP_2` (Telefon). Keine Messaussage
  veraendert, keine Umformulierung noetig. **Alle vier noch nicht committet** —
  das ist der erste Auftrag an den `archivist` (sync-out) der naechsten Sitzung,
  sobald T-032 nicht mehr laeuft.
- Uebrige Dateien in `docs/perf/` und `docs/perf/tools/` geprueft: sauber.

**Entscheidungen des App Designers 03.09. — bindend:**

1. **Zielbild: alle drei Saeulen.** Basis ist Anzeigen **und** Kontrolle ueber
   die Einstellungen; Erweiterung ist Optimieren auf Basis der Messungen.
   "Durch das Auslesen soll die App einen auch gleichzeitig anweisen."
2. **UMKEHR gegenueber 02.09.: Die App stellt Einstellungen selbst**, wo das
   ohne Root geht. Der privilegierte Helfer darf dafuer wachsen. Gegengewichte
   im Entwurf: AK-10 (jedes Kommando einzeln vom `security-reviewer`
   abgenommen) und AK-11 (Rueckweg garantiert, Ausgangszustand vorher
   festgehalten). **Folge: Jeder Zyklus, der ein Helfer-Kommando anfasst, zieht
   den `security-reviewer` — ohne Ausnahme.**
3. **Tuning-Prozess: die sechs belegten Massnahmen plus eine ausdruecklich als
   "Ausweichen" benannte Kategorie.** Widerlegte Ratschlaege erscheinen gar
   nicht.
4. **PII: redigieren statt ausschliessen** — Begruendung des Nutzers: Die
   Berichte haben Wert fuer andere, die PII darin nicht.

**Offen, dem Nutzer vorgelegt, noch nicht entschieden:**

- **Git-Historie:** `T-029-990-korrelation.md` enthaelt die LAN-IP im Klartext
  in bereits committeten Staenden. Empfehlung des Directors: **nichts tun**,
  solange das Repo privat bleibt (private RFC1918-Adresse, Aufwand eines
  History-Rewrite unverhaeltnismaessig). Vor einer Veroeffentlichung neu zu
  bewerten.
- **Vorbehalt des Directors zur Generalisierung:** Der Nutzer plant, von
  diesem Geraet auf andere current-gen Geraete zu schliessen. R-009 stuetzt das
  nur zur Haelfte — Stack-Konstanten ja, Hardwarefakten (Pakettyp, effektive
  MTU 883, EDR-Klasse, Wiederholrate, Encoder-Ort) nein, die sind Eigenschaft
  **der Paarung**, nicht des Telefons. Im Entwurf als AK-15 aufgenommen.
- **Folge fuer die Testsuite, im Entwurf gezogen:** Solange kein Stresshebel
  belegt ist (T-028 hat ihn ueber acht gueltige Abschnitte nicht reproduziert),
  kann es keinen "Schnelldurchlauf unter Stress" geben. Die Suite bleibt bis
  dahin auf Vorher/Nachher ohne kuenstliche Stoerung beschraenkt. Das weicht
  vom Plan des Nutzers vom 02.09. ab und braucht seine Bestaetigung.

## Auswertung des Research-Blocks (03.09.) — was jetzt belegt ist

**Die tragende Erklaerung heisst Luftzeit, nicht Signalqualitaet.** 990 kbps
belegt auf einem 2-DH5-Link rund 70 Prozent der Kapazitaet, 660 rund 47-59.
Funkfehler werden per ARQ in Wiederholungen und damit ebenfalls in Luftzeit
umgesetzt — "Latenz gegen Signalqualitaet" ist deshalb **keine** Trennung.
Die Sendewarteschlange fasst 28 Pakete, bei 990 nur rund **150 ms** Audio.
Das erklaert, warum dieselbe Stoerung bei 660 folgenlos bleibt und 990 kippt.
**Belegstand:** Konstanten am Quelltext belegt, die Prozentrechnung ist
eigene Arithmetik auf sekundaer belegten Paketgroessen — Rahmen, keine Messung.

**Eine Director-Lesart ist widerlegt (R-010, Teil 0):** `Packet counts
(expected/dropped)` = 1279910/0 sagt **nichts** ueber die Funkstrecke. Der
Zaehler zaehlt Encoder-Aufrufe und `ldacBT_encode()`-Fehler. Der Satz "auf
Paketebene geht nichts verloren" bleibt als Protokolleigenschaft richtig,
ist aber **durch diesen Zaehler nicht belegt**. Wer damit argumentiert,
argumentiert falsch.

**Der Ueberlauf entsteht auf der Abflussseite**, nicht am Host-Timer:
`tx_audio_queue` wird nur geleert, solange `l2c_bufs` unter der Schwelle
liegt; nimmt der Controller nichts ab, staut es sich bis zur Raeumung.
Belegt am Quelltext.

### Die Stellschrauben — was die App empfehlen kann (R-010, Teil 1)

Alle ohne Root, alle nur **anleitbar**, keine von der App stellbar. Keine
davon ist unter 990 gepinnt gemessen — die Rangfolge ist Belegstaerke, nicht
Wirkungsgroesse.

| Rang | Massnahme | Beleg |
|---|---|---|
| 1 | 2,4-GHz-WLAN weg (aus, oder Router-SSID auf 5 GHz) | eigene Messung n=1 bei 660 + PTA-Mechanismus |
| 2 | Keine Discovery/Scans: Koppel-Bildschirm zu, Fast-Pair-Suche aus, scannende Apps finden | AOSP-Javadoc woertlich |
| 3 | Kein zweites BT-Geraet, Multipoint am Sink aus | Sony schaltet LDAC bei Multipoint ganz ab |
| 4 | Koerper aus der Funkstrecke, Abstand klein, Sichtlinie | Fachliteratur 10-21 dB Daempfung |
| 5 | USB-3-Kabel waehrend der Wiedergabe ab | Sekundaerquelle; **im Projekt nie ohne Kabel gemessen** |
| 6 | Sink-Modus, der LDAC zulaesst | Herstellerdoku, nur Sony belegt |

**Ausweichen statt Hilfe** (gehoert sichtbar so beschriftet): Stufe senken,
ABR, Codec-Wechsel auf AAC/SBC/aptX, 44,1-kHz-Familie (909 statt 990).
**Zwei Nebenbefunde:** 990 existiert nur in der 48/96-kHz-Familie; ein
Wechsel 96 auf 48 kHz spart **keine** Luftzeit, nur Resampling.

**Widerlegt oder ohne Mechanismus** — gehoeren nicht in den Prozess, aber in
die Begruendung, warum sie fehlen: Absolute Lautstaerke, AVRCP-Version,
Bittiefe, 48 gegen 96 kHz, Akku-/App-Optimierung, BT-Cache loeschen,
Neukoppeln, Flugmodus-Zyklus, Schalter "Bluetooth-Scannen", "maximale
Audiogeraete" bei einem Sink, Neustart — und auf **diesem** Geraet der
A2DP-Offload-Schalter, weil LDAC hier im Host-Encoder-Pfad laeuft.

**Nur mit Systemrechten, also unbrauchbar:** `setBufferLengthMillis`
(groesserer Sendepuffer gegen Latenz), Interop-Listen, HCI/Flush-Timeout.

### Der Fund mit dem groessten Hebel fuer die App (R-009)

`dumpsys bluetooth_manager` enthaelt auf diesem Geraet einen
**BT-Quality-Report-Abschnitt** mit Pakettyp, RSSI, Sendeleistungsstufe,
Wiederholungen (`ReTx/TxTotal`), Nicht-Empfang (`NoRX`) und
AFH-Kanalauslass — **echte Firmware-Telemetrie, ohne Root lesbar**, mit
Zeitstempel je Ereignis. Das ist die Groesse, die das Projekt bisher aus der
Warteschlange zurueckzuschliessen versucht hat. **Nicht stellbar, nur
lesbar.** Vorbehalt: nur bei Ereignissen oder im Monitoring-Modus gefuellt,
Felder sind herstellerdefiniert (BQR v5-v7).

Der Dump zeigte Choppy-Ereignisse bei **RSSI -47 dBm** — starkes Signal.
Das stuetzt die Luftzeit-These und schwaecht "schlechter Empfang".

### Der Geraete-Read-back, der vor allem anderen kommt

Ein einziger Lauf beantwortet, ob 990 auf dieser Paarung ueberhaupt tragen
kann, und liefert die Ist-Werte fuer die Luftzeitrechnung:

- `Frames per packet (ave)` — bei 990 heisst ca. 2 ein 2-DH5-Link, ca. 3 ein
  3-DH5-Link. Liegt der Link in der 2-DH3-Klasse, hilft **keine** Massnahme.
- BQR: Pakettyp, `ReTx/TxTotal`, `NoRX` **waehrend** eines 990er-Clusters
  gegen eine Ruhephase.
- Laeuft LDAC wirklich im Host-Pfad? Logcat "software codec=".
- Bietet das Geraet aptX an? `Selectable`-Zeile.
- Remote-Features der Gegenstelle: 3-Mbps-EDR ja/nein, effektive MTU (Dump
  sagte 883 gegen angebotene 1005).

Rolle: `performance-tuner`. Braucht Kopfhoerer verbunden und Musik.

**Offen geblieben und nicht zu klaeren war:** Chipsatz des Pixel 11 Pro,
warum die Firmware bei -47 dBm 2-DH5 faehrt, Herkunft der MTU 883,
Zahlenwert von `MAX_PCM_FRAME_NUM_PER_TICK`, Semantik von
`Frames per packet` (offen seit R-001).


Kurzfassung fuer die Agenten. Zielbild in `GOAL.md`, Historie in `HANDOVER.md`,
Entwurf in `ARCHITECTURE.md`. Details stehen dort, nicht hier.

## Laufender Lauf: Rahmen und Ende

Auftrag des App Designers 02.09.: **erst Fundament, dann Bau.** Vor V-1 muessen
die Verlustwerte belastbar sein — die heutigen Schwellen wirken erfunden.
Obergrenze: bis der Schwerpunkt Verlustmechanik abgeschlossen und von QA gruen
abgenommen ist. Der Lauf endet dort, oder frueher bei kritischem
Sicherheitsbefund, Datenverlustverdacht oder zwei Zyklen ohne messbaren
Fortschritt.

Warteschlange (vom Nutzer freigegeben 02.09.):
1. Research-Block T-024/T-025/T-026 — **erledigt**, R-005/R-006/R-007
2. Kalibriermessung T-027 — **teilweise erledigt**, siehe unten. Offen bleibt
   die **Hoersitzung**, und die muss vorwaerts gefuehrt werden.
3. V-1..V-7 Verlustmechanik bauen

## T-027 — was gemessen wurde (02.09. Abend)

Berichte: `docs/perf/T-027-messung.md` (Gate, Phase 1, 5-GHz-Leiter),
`docs/perf/T-027-messung-24ghz.md` (2,4-GHz-Leiter),
`docs/perf/T-027-hoereindruck.md` (Hoerprotokoll, **fuehrt nur der Director**).
Rohdaten gesichert unter `C:\Users\Daniel\t027-rawdata` — **ausserhalb des
Repos**, weil sie Geraetenamen und MACs enthalten (SR-012-Familie).

**Gate: `Priority` ist NICHT die LDAC-Stufe.** Drei Stufenwechsel, `Priority`
bewegt sich kein einziges Mal; `mCodecSpecific1` bewegt sich jedes Mal und
kehrt exakt zurueck (1001 = MID/660, 1002 = LOW/330, 1003 = ABR). Die alte
Notiz „Pin-Marker 1000000" ist damit widerlegt, der Widerspruch 5001 gegen
1000000 war eine Scheinfrage. **Der Stufen-Read-back laeuft ab jetzt ueber
drei Groessen gemeinsam:** `mCodecSpecific1`, `LDAC quality mode`, Abwesenheit
der ABR-Zeilen.

**Phase 1 — Ruherate bei gepinnten 660 (neu, gab es nicht):** 39,78 min,
1861 Samples, **alle 1860 Uebergaenge geprueft**, `dropped`/`dropouts` = 0/0.
Dreierregel-Obergrenze **0,0754/min**. `LOSS_NOTICE_RATE_PER_MIN` = 1/min liegt
rund 13-fach darueber — der Wert haelt auch fuer den gepinnten Fall.

**5-GHz-Leiter: sauberes Nein.** Sieben Zellen, bis 16 Stroeme, real bis
~365 Mbit/s: **0/0 in jeder Zelle**, kein dosisabhaengiges Muster, Queue in
allen 1264 Samples leer. Rueckkehrzelle identisch zur Kontrolle (A/B/A' sauber).
Nachweisgrenze 0,105/min — lockerer als Phase 1, weil kuerzer; das gehoert zur
Aussage. **Last auf dem 5-GHz-Link erreicht den Bluetooth-Pfad nicht.**

**2,4-GHz-Leiter (nach Router-Umstellung durch den Nutzer, verifiziert:
2462 MHz): zwei echte Funde und ein Haken.**

| Zelle | Δ`dropped`/min | Δ`dropouts`/min |
|---|---|---|
| Kontrolle 0 | **5,86** | 0,24 |
| 1 Strom (19:24:04–19:28:23) | **39,3** | **1,71** |
| 2 / 4 / 8 / 16 Stroeme | 0 | 0 |
| Rueckkehr 0' | 0 | 0 |

- **Fund 1: Die Ruherate ist umgebungsabhaengig, nicht geraetefest.** Allein die
  2,4-GHz-Assoziation hebt die Kontrollzelle von 0 auf 5,86 `dropped`/min — bei
  identischer Stufe, wo Phase 1 ueber 40 min exakt null hatte. **Das ist fuer
  die Anzeige bedeutsam:** eine feste Schwelle unterstellt eine feste Ruherate.
- **Fund 2: dritte Fixture-Luecke geschlossen** —
  `bt_manager_pixel11_ldac_pinned_660_24ghz_induced_loss.txt`, echter Verlust
  bei fester Stufe, extern induziert. **Offen: Golden-Test dazu fehlt**, gehoert
  an `developer`/`qa-engineer`.
- **NACHTRAEGLICHER KONFUNDIERER, gefunden beim T-028-Vorabtest:** Das Geraet
  hat die WLAN-Assoziation um **19:48:49** verloren und sie nicht
  wiederhergestellt. Das faellt **mitten in die 16-Strom-Zelle** (19:44:24–
  19:50:51), und die **Rueckkehrzelle 0' (19:51:38–19:55:49) lief vollstaendig
  ohne WLAN**. Sie ist damit **keine gueltige Gegenprobe** fuer „2,4 GHz
  assoziiert, ohne Last" — sie misst „kein WLAN". Der A/B/A'-Beleg aus dem
  Phase-4-Bericht traegt in dieser Form nicht und ist beim naechsten Lauf zu
  wiederholen.
  **Hypothese, ungeprueft, aber jetzt naheliegend:** Wenn die Verbindung unter
  hoher Stromzahl bereits schwaechelte, war die tatsaechliche 2,4-GHz-Belegung
  bei 8 und 16 Stroemen moeglicherweise **geringer** als bei 1 Strom — was das
  nicht-monotone Muster erklaeren wuerde, ohne einen Zufall bemuehen zu muessen.
  Der naechste Lauf muss die Assoziation deshalb **je Abschnitt** pruefen, nicht
  nur am Anfang.
- **Der Haken: die Leiter ist NICHT monoton.** Ausschlag bei einem Strom, ab
  zwei Stroemen nichts mehr, obwohl der Durchsatz weiter stieg. Das ist **keine
  Dosis-Wirkungs-Beziehung**. Der Ausschlag bei Stufe 1 ist ein **einzelnes,
  unwiederholtes Ereignis** — jede Stufe lief genau einmal, es gibt keine
  Streuung. **Daraus laesst sich keine Schwelle ableiten.** Reproduzierbarkeit
  ist offen und muss der naechste Lauf klaeren.

**Hoersitzung ergebnislos — und das ist ein Verfahrensbefund.** Rueckblickend
abgefragt konnte der Nutzer fuer beide Zeitfenster nur „weiss ich nicht mehr
sicher" sagen. Er hoerte nebenbei, ohne zu wissen, wann ein Reiz anlag. Die
naechste Sitzung muss **vorwaerts, aktiv und blind** gefuehrt werden, mit
kurzen, mehrfach wiederholten Abschnitten — Anforderungen stehen in
`docs/perf/T-027-hoereindruck.md`. **Ohne diese Sitzung gibt es keinen
belegten Hoerbarkeitspunkt**, und `LOSS_ALERT_RATE_PER_MIN` bleibt unbelegt.

## Der Research-Block — was er geaendert hat (02.09.)

Drei Antworten, die zusammen die Grundlage der Verlustmechanik verschieben.
Volltext in `docs/research/R-005.md`, `R-006.md`, `R-007.md`.

**R-005 — die Zaehler bedeuten nicht, was die Achse unterstellt.** Belegt an
AOSP `btif_a2dp_source.cc` (`main` und `android17-release` gegengeprueft):

- `dropped` = verworfene **Warteschlangen-Eintraege**, A2DP-Medienpakete mit
  **variabler** Anzahl gebuendelter Codec-Frames. **Kein festes Stueck
  Audiozeit.** Eine Rate „je Minute" addiert darauf ungleich grosse Dinge.
- `dropouts` = **Ueberlauf-Episoden** (Warteschlange am Stueck geleert). Saubere
  Ereignisgroesse; „je Minute" passt hier. Die Messung 525/21 = 25,0 Eintraege
  je Raeumung ist genau die Struktur, die der Quelltext erwarten laesst.
- `underflow` = Encoder-Lesetakte mit PCM-Fehlbetrag, bei LDAC fest 20 ms, aber
  **mit Stille aufgefuellt statt uebersprungen**. Keine „verlorenen
  Millisekunden". Erklaert strukturell, warum das Feld im Ruhelauf ungleich 0
  war und im hoerbar kaputten Arm 0 blieb.
- **Nebenbefund, nicht bestellt:** Die sichtbaren `accumulated_stats` werden bei
  Pause, Reconnect und Codec-Wechsel **nicht** genullt — nur ein vollstaendiger
  Neustart des A2DP-Source-Moduls ohne vorher aktiven Peer setzt zurueck. Das
  stuetzt die Poll-zu-Poll-Differenz staerker ab als vom Entwurf angenommen.
  **Folge fuer AD-020:** `COUNTERS_RESET` bleibt noetig, ist aber seltener als
  gedacht; der Ausloeser ist Stack-Neustart, nicht Pause.

**R-006 — es gibt keine Literaturschwelle, und die Achse ist die falsche.**

- Fuer **A2DP-Musik** existiert **keine belegte Hoerbarkeitsschwelle** auf
  irgendeiner Groesse. `LOSS_ALERT_RATE_PER_MIN` = 12/min hat ausserhalb dieses
  Projekts keinen Rueckhalt.
- Wo Literatur existiert (VoIP, PHY-Konformitaet, Streaming-QoE), ist die
  tragende Groesse **Verlust in Prozent bzw. verlorener Zeitanteil**, ergaenzt
  um **Lueckenlaenge und Burst-Muster**. „Ereignisse je Minute" taucht als
  normierte Achse in **keiner** gefundenen Quelle auf.
- Android kennt `QUALITY_REPORT_ID_A2DP_AUDIO_CHOPPY`, der ausloesende Wert
  steckt aber in der Chip-Firmware und ist nicht oeffentlich.
- **LDAC-Concealment ist oeffentlich undokumentiert.** Existiert eines, laufen
  Zaehlerstand und Hoereindruck systematisch auseinander — dann ist jede
  zaehlerbasierte Schwelle ein Stellvertreter und muss so beschriftet werden.

**R-007 — M-11 ist moeglicherweise messbar, und die Konfundierung war ein
Fehler der Versuchsanordnung, nicht des Geraets.**

- Belegt an AOSP `a2dp_vendor_ldac_encoder.cc`: Der Encoder gibt sein
  ABR-Handle frei und stoppt `ldac_ABR_Proc()`, **sobald die Stufe fest statt
  auf ABR steht.** Bei gepinnter Stufe kann eine Stoerung die Stufe also gar
  nicht mehr verschieben — nur noch Verlust erzeugen.
- **Damit kippt die bisherige Lesart:** Nicht das Pinnen konfundiert. T-008 hat
  verschiedene **Stufen** gegeneinander verglichen — das war der Konfundierer.
  **Fester Pin plus dosierter externer Stoerhebel** haelt die Stufe per
  Konstruktion konstant und variiert allein den Verlust. Das sind die
  Zwischenpunkte, die M-11 bisher gefehlt haben.
- Rangfolge der Stoerhebel nach Dosierbarkeit mal Konfundierungsfreiheit:
  **kontrollierte 2,4-GHz-Belegung** (kuenstlicher WLAN-/BLE-Verkehr) ist der
  feinste ohne Root; physische Daempfung braeuchte einen kalibrierten
  Abschwaecher, den das Projekt nicht hat; Rechenlast ist wegen `SCHED_FIFO`
  kaum dosierbar (deckt sich mit dem bereits erfolgten Ausschluss); HCI-Ebene
  braucht Root plus deaktiviertes SELinux, Gegenstelle bietet keinen Testmodus.

**Die eine Pruefung, an der alles haengt — vor jeder Kalibriermessung:**
Ist der projekteigene Pin-Marker (`Priority: 1000000` aus T-008) **dasselbe
Feld** wie `quality_mode_index` / `codecSpecific1` im LDAC-Encoder, oder ein
unabhaengiges Feld (A2DP-Codec-Auswahlprioritaet)? Die numerische Aehnlichkeit
ist auffaellig, aber **nicht bewiesen**. Traegt die Gleichsetzung nicht, faellt
die ganze R-007-These, und M-11 bleibt unmessbar. Das ist ein Read-back am
Geraet, kein Schreibtischschluss — und es ist der **erste** Schritt der
Messreihe, nicht ein Nebenpunkt.

Ruhen laesst der Nutzer: **T-005 Scan** (die sechs Entscheidungen sind nirgends
aufgeschrieben; erst neu herleiten lassen, wenn der Scan ansteht).

## Die tragenden Befunde (belegt, nicht mehr zu diskutieren)

- **"990 gepinnt gleich Warteschlangenueberlauf gleich hoerbare Aussetzer" ist
  belegt** (T-008, `docs/perf/T-008-experimente.md`). A/B/A: ABR 0 Drops /
  0 Dropouts, 990 gepinnt 525/21 (13/min, durchgehend hoerbar), zurueck auf ABR
  wieder 0.
- **Underflow ist als Leitgroesse untauglich.** Blieb in ALLEN Armen 0 — auch im
  hoerbar kaputten. Im 39-min-Ruhelauf dagegen 2 auf 25 (0,591/min). Taugt weder
  fuer Ueberlast noch fuer Ruhe. Traegt seit 02.09. **kein Verdikt** mehr,
  bleibt aber sichtbar (QA-001).
- **Die zwei Hoerbarkeitspunkte sind konfundiert.** 0 Dropouts gab es nur bei
  492/660, 13/min nur bei 990. Belegt ist "990 gepinnt klingt kaputt", **nicht**
  "13/min sind hoerbar". Das ist der Anlass des Research-Blocks.
- **Die Ruherate ist null, belastbar** (M-5, T-011): 38,93 min, 1795 Samples,
  jeder der 1794 Uebergaenge geprueft. Dreierregel-Obergrenze **0,063/min**;
  `LOSS_NOTICE_RATE_PER_MIN` = 1/min liegt rund 16-fach darueber. Wert bleibt.
- **ABR probiert 990 von sich aus, n=31:** 30-mal fuer genau ein Sample, nie mit
  Verlust.
- **Ursache des Queue-Ueberlaufs bleibt offen.** Ausgeschlossen: WLAN, Doze,
  CPU-Knappheit. Gestuetzt, nicht belegt: "990 ist fuer diese Strecke zu
  schnell".
- **R-E ist dauerhaft:** keine abstufenden Woerter, kein mehrstufiges Bildzeichen
  fuer Raten echt zwischen 0 und 12/min, solange M-11 unmessbar ist. Das Wort
  "audible" kommt in der Oberflaeche nicht vor (AK-T009-43, Grep-Regel).

## "Gesetzt" heisst NICHT "gebaut"

Die T-009-Parameter stehen in `UI_SPEC.md` **festgelegt**; im Code existiert
**keine** davon (T-021, per Grep). Ebenso fehlen der `SETTLING`-Zustand und die
Maschine CLEAN/OCCASIONAL/DISTURBED/CANNOT_TELL. Die heutige `LossRow` rechnet
eine rohe Poll-zu-Poll-Differenz. **Rund 25 Akzeptanzkriterien sind dadurch
nicht ungetestet, sondern gegenstandslos.** Genau das schliesst V-1..V-7.

## T-023 geliefert und auf `master` (02.09.)

`ARCHITECTURE.md` **AD-015..AD-024** plus fuenf Eintraege "Bewusst nicht getan".
Kern: reine Logik in `:core-monitor` (`link/live/verdict/`), gefaltet im
ViewModel. **Keine Uhr** — Zeitstempel reisen in den Lesungen. **Ein** Fenster
(`LossWindow`); der Ueberblicks-`LiveTrace` wird daraus **projiziert** statt
zweimal akkumuliert. `CANNOT_TELL` ist versiegelter Zustand mit typisiertem
Grund. Eine Schwelle ohne Messung ist ein eigener Typ (`Open`/`None`), keine
Zahl. Verlust und Stufe sind **zwei getrennte Maschinen ohne gemeinsamen Typ** —
R-E ist damit strukturell erzwungen, nicht redaktionell.

**Schrittfolge V-1..V-7, kein Schritt braucht ein Geraet.** V-1 schliesst QA-012
nebenbei mit.

Zwei Nachtraege aus T-023:

- **AD-022 korrigiert eine Director-Notiz:** Im gepinnten Modus ist die
  Stufenzeile **nicht** `CANNOT_TELL` — Verlustzaehler und gemessene Stufe sind
  lesbar. Nur die **ABR-Fakten** fehlen (Rung-Index, Wechselzaehler, Anteile,
  G-4-Markierung). Nur dorthin gehoert `CANNOT_TELL`.
- **An den `ui-ux-designer`:** `UI_SPEC.md` (T-009-Tabelle) laesst
  `encoder underflows` noch `OCCASIONAL` tragen. Widerspricht dem gebauten Stand
  und QA-001. Nachzuziehen.

**Entscheidung Nutzer 02.09. (nach R-005):** `dropped` **verliert das Verdikt**
— gleiche Behandlung wie `underflow`. Es bleibt als Zahl sichtbar, traegt aber
keine Schwelle. Grund: `dropped` zaehlt Eintraege variabler Groesse, eine Rate
darauf addiert Ungleiches. **Alleinige Leitgroesse fuer Ueberlast ist
`dropouts`** (Episoden je Minute) — die einzige Groesse, die der Quelltext als
saubere Ereigniseinheit hergibt. Folge fuer AD-019: `LOSS_*_RATE_PER_MIN` fuer
`dropped` wird `None` mit Grund, nicht `Measured`. `UI_SPEC.md` ist
entsprechend nachzuziehen.

**Entscheidung Nutzer 02.09.:** App- und Mixer-Underruns bleiben **unbeurteilt**
(sichtbar als Zaehler, kein Verdikt), solange M-1 ihre Ruherate nicht kennt.
Keine rote Zeile mehr aus diesen Kanaelen. AD-019 wird so gebaut.

## Rahmen und Geraet

- Pixel 11 Pro `67011FDKX004XG`, Android 17, **per Kabel verbunden** (02.09.).
  **Kein A2DP-Link** — Kopfhoerer getrennt seit 13:27. Jede Messung braucht
  Kopfhoerer verbunden **und** laufende Musik. `A2dpOffloadEnabled: true`.
- Toolchain steht: JDK 21 unter `~/tools/jdk/jdk-21.0.12.1+1`, NDK gepinnt
  `27.3.13750724` (durch nativen Bau belegt, 16-KB-Zusage haelt), build-tools
  35.0.0 genuegt AGP 8.9.3. `gradlew test`: **2482 Tests, 0 Failures** (Stand 03.09. nach T-034;
  Basislinie davor 2470, beide eigenhaendig gemessen. Die frueher notierten
  2390 passen zu dieser Zaehlweise nicht).
- **Risiko R-2:** zwei adb-Binaries (`C:\RSL\2.1HF5\adb\adb.exe` und
  `platform-tools\adb.exe`) killen sich den Server. Waehrend einer Messung nur
  **eines** benutzen.
- **`sdkmanager` liefert bei Erfolg Exit-Code 127.** Erfolg am Dateisystem
  pruefen, nie am Exit-Code.
- Kein Emulator (kein Hypervisor). Alles ausser Geraetetests laeuft ueber
  Unit-Tests und Robolectric.
- **Methodischer Vorbehalt:** USB-3 strahlt ins 2,4-GHz-Band; alle Messungen
  liefen am Kabel. Kontrollmessung ueber drahtloses adb steht aus.
- **Verfahrensregel:** Read-back deckt das **vollstaendige** Zustandsbuch ab,
  nicht nur die geaenderte Variable (Anlass: unbemerktes WLAN machte die vierte
  T-008-Zelle wertlos).

## Laufende und offene Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-023 | architect | Entwurf Verlustmechanik | **erledigt**, AD-015..AD-024 auf `master` |
| T-024 | researcher | Semantik `dropped`/`dropouts`/`underflow` | **erledigt**, R-005 |
| T-025 | researcher | Hoerbarkeitsschwellen A2DP | **erledigt**, R-006 |
| T-026 | researcher | Hebel fuer Verlust bei konstanter Stufe (M-11) | **erledigt**, R-007 |
| T-029 | performance-tuner | Buendelstruktur der 990er-Verluste | **erledigt** |
| T-030 | ui-ux-designer | Buendelungskriterium in `UI_SPEC.md` | **erledigt**, AK-T030-1..14, **nicht committet** |
| T-031 | researcher x3 | Stresshebel / Schadfaktoren / Massnahmen | **erledigt**, R-008/R-009/R-010, **ausgewertet 03.09.** |
| T-021 | developer | AK-Verankerung (QA-008) | **erledigt**; Retest gelaufen, Restbefunde QA-012/QA-013 |
| T-022 | performance-tuner | Fixtures Verlustfall/Stufen/Pause | geliefert |
| T-001 | performance-tuner | Vergleichslauf gegen Block 1 | offen; **vor** dem Transport-Messlauf (U-6/S-6) |
| T-006 | architect nach developer | Transport SR-001/SR-009 | Entwurf abgenommen (AD-010..014, U-0..U-6); Umsetzung offen |
| T-005 | architect | Scan-Entwurf S-1..S-7 | **ruht** (Nutzer 02.09.) |
| T-008 | performance-tuner | E-1/E-3 (Nearby-Scans, Spatializer aus) | offen, **kein Shell-Hebel** — nur von Hand |
| SR-012 | performance-tuner | `umask 077` in `docs/perf/tools/*.sh` plus Reste loeschen | zurueckgestellt bis Ende der Messreihe |
| QA-012 / QA-013 | developer | vakuum-gruene Grep-Regel, schwacher AK-T009-29-Test | offen; QA-012 faellt in V-1 |

**Offene Hoch-Sicherheitsbefunde:** SR-001 und SR-009 — weltles- und
-schreibbare Dumps bzw. Helper-Log in `/data/local/tmp`, **ueberleben die
Deinstallation**. Behebung ist T-006/U-0..U-6, braucht Geraet.

## Drei Fixture-Luecken — fuer die naechste Geraetesitzung

1. **Kein aufgenommener Dump aus dem 990er-Arm** mit `dropped`/`dropouts`
   ungleich 0. AK-T009-24 laeuft heute ueber gesetzte Zaehlerstaende — ein Test
   ueber die Arithmetik, **kein** Beleg, dass der Parser einen echten 990er-Dump
   liest. **Wer je wieder auf 990 pinnt: Dump mitnehmen.**
2. Kein Dump eines Builds **ohne** die beiden ABR-Zeilen.
3. **Nur ein Rung-Wert aufgenommen** (Index 4 / 396 kbps). Die Paare 660/1 und
   492/3 stehen nur in der Messdoku, nicht in einer Fixture.

## Offen — braucht den Nutzer oder seine Hand am Geraet

- **Kopfhoerer verbinden und Musik starten**, bevor gemessen werden kann.
- **Codec-Pin-Zustand ist widerspruechlich dokumentiert** (`Priority: 5001` im
  T-011-Read-back gegen `1000000` in der Part-3-Notiz). Vor der naechsten
  Messung frisch lesen, nicht aus den Notizen glauben.
- E-1 (Nearby-Scans aus) und E-3 (Spatializer aus) haben **keinen Shell-Hebel** —
  am Geraet vollstaendig geprueft. Nur von Hand.
- Vierte T-008-Zelle bleibt **INCONCLUSIVE** (WLAN-Konfundierer).

## Zurueckgestellt

- Kein `CHANGELOG.md`, kein gebautes Artefakt, keine Installationsanleitung —
  fuer den `power-user` gibt es deshalb noch keinen Ausgangspunkt.
- QA-005: zwei ABR-Felder ohne Konsumenten; `TxProbeSample` traegt sie nicht.
  **Kopplung fuer den UI-Zyklus:** `A2dpTxProbe.sampleBetween` kopiert nur
  `bitrateKbps` und `qualityModeLabel` — der Nahaufnahme-Kanal bekommt die neuen
  Felder nicht, und G-4/AK-T009-41 braucht den Zaehler genau dort.
- Aufnahme gegen R-001..R-004 abgleichen (der tuner hat sie nie gelesen).
- Widerspruch R-001 gegen Messung: 492 kbps ist gemessen, gilt dort aber nicht
  als Nominalstufe. Leiter fuer 96 kHz/32 bit unverstanden.
- `AudioEffectSessionReceiver` exportiert — eigenes Security-Review ausstehend.
- QA-011: historische Fixture, ehrlich markiert.

## NEUE RICHTUNG — Entscheidungen des App Designers 02.09. spaet

Nach T-029 hat der Nutzer das Vorhaben erweitert. Drei Bausteine, in dieser
Reihenfolge:

1. **Bessere Verlustdarstellung.** Zwei getrennte Zahlen statt einer:
   **hoerbare Verluste** (Sendeseite: `dropped`/`dropouts`, Warteschlangen-
   raeumung, Audio das nie rausging) gegen **regulaere** (Quellseite:
   `underflow`, mit Stille aufgefuellt). Belegt: heute korrelierte der
   Hoereindruck mit der Sendeseite, waehrend underflow in einem 39-min-Lauf
   ohne jede Wahrnehmung hochlief.
2. **Testsuite:** exakt messen, wie viele Pakete bei LDAC 990 wie verloren
   gehen; Einstellungen durchtunen und jeweils gegenpruefen.
3. **Bluetooth-Tuning im Dashboard**, als **gefuehrter Prozess** — nicht als
   Liste vorgeschlagener Regeln, weil moeglicherweise jedes Geraet anders ist.

### Entscheidungen des Nutzers dazu

- **Die App aendert keine Einstellungen selbst, sie leitet nur an.** Kein
  Ausbau des privilegierten Helfers, keine neue Angriffsflaeche.
- **Stress = 990 gepinnt PLUS externe Funklast.** Achtung: Die externe Last
  hat sich in T-028 als Hebel NICHT reproduzieren lassen; sie ist vor dem Bau
  der Suite erst zu belegen.
- **Zielbild wird neu geschrieben**, zwei Varianten zur Wahl: urteilende App
  gegen zeigende App, jeweils mit Tuning als drittem Standbein.
- **Sitzungslaenge, aus den Messdaten abgeleitet:** Laengste verlustfreie Phase
  bei 990 war 4,4 min, typisch 40 s bis 2 min, 10 Cluster in 25 min.
  Abwesenheit zu belegen braucht daher >5 min. **Vergleichen** braucht das
  nicht: Schnelldurchlauf 3-5 min je Einstellung unter Stress, danach EIN
  Bestaetigungslauf 20-30 min ohne Stress fuer die gewonnene Einstellung.
  **Der Schnelldurchlauf misst relative Verbesserung unter Stress, nicht
  Alltagsqualitaet — das gehoert sichtbar in die Anzeige, nicht in eine
  Fussnote.**

### Zaehler-Inventar aus einem echten Dump — mehr als bisher genutzt

Sendeseite: `Counts (flushed/dropped/dropouts)`, `Counts (max dropped)`,
`LDAC saved transmit queue length`.
Quellseite: `Counts (underflow)`, `Bytes (underflow)`,
`PCM read counts (expected/actual)`.
Scheduling: `Enqueue/Dequeue deviation counts (overdue/premature)` samt
Zeitsummen — **direkt messbarer Jitter, fuer Tuning die interessanteste
Familie und bisher voellig ungenutzt.**

**Der Fund, der die Sprache aendert:** `Packet counts (expected/dropped)` stand
bei **1279910 / 0**, waehrend gleichzeitig 807 Warteschlangeneintraege verworfen
wurden. **Auf Paketebene geht nichts verloren.** Was das Projekt "Paketverlust"
nennt, ist ein Warteschlangenueberlauf: Die Funkstrecke wiederholt selbst, das
Problem ist Verzoegerung, nicht Verlust. Das erklaert auch 990 kbps — die
Strecke wird die Datenmenge nicht rechtzeitig los.

**BELEGSTAND, nicht ueberdehnen:** Am Quelltext belegt sind nur `dropped`,
`dropouts`, `underflow` (R-005). Die Bedeutung von `Packet counts`,
`max dropped` und den Deviation-Zaehlern ist **Director-Lesart, unbelegt** —
gehoert recherchiert, bevor ein Messapparat darauf gebaut wird.

### ZIELPRAEZISIERUNG des App Designers, 02.09. spaet — WICHTIG

**990 kbps ist kein Nachteil, den es zu vermeiden gilt. Es ist das Ziel.**
Der Nutzer will ausdruecklich die hoechste Qualitaet und Bitrate fahren. Die
leitende Frage des Vorhabens lautet damit nicht mehr "wie erkennen wir, dass
990 kippt", sondern **"was muss gegeben sein, damit 990 stabil laeuft".**

Folgen, die sofort gelten:

- **Stufe senken ist keine Loesung mehr, sondern das zu Vermeidende.** Auch der
  Wechsel auf AAC, aptX oder SBC gilt als Aufgabe der Qualitaet, nicht als
  Behebung. Ratschlaege, die in Wahrheit nur die Bitrate senken, sind als
  solche zu entlarven.
- **Hauptkategorie wird alles, was Sendezeit, Latenz und Scheduling im
  Sendepfad verbessert.** Messlage dazu: bei fest 660 blieben ueber 2,3 Mio
  Pakete verlustfrei, bei fest 990 entstehen Verluste ohne jeden externen Reiz.
  Die Grenze liegt vermutlich an verfuegbarer Luftzeit und Rechtzeitigkeit,
  nicht an "Funkqualitaet" im naiven Sinn — **ungeprueft, aber die tragende
  Arbeitshypothese**.
- Ein Faktor, der bei 660 folgenlos bleibt und bei 990 den Ausschlag gibt, ist
  fuer dieses Projekt der wichtigste ueberhaupt.
- R-009 und R-010 wurden waehrend des Laufs entsprechend nachgesteuert.

**Das beruehrt `GOAL.md` an der Wurzel** und ist beim Neuschreiben des
Zielbilds zu beruecksichtigen: Das Ziel ist nicht mehr nur "ehrlich anzeigen",
sondern "die hoechste Stufe nutzbar machen". Zwei Varianten stehen zur Wahl
(urteilende gegen zeigende App); beide muessen dieses dritte Standbein tragen.

### OFFEN: PII in drei Messberichten — blockiert deren Commit

Der `archivist` hat den Commit von `docs/perf/T-027-messung.md`,
`T-027-messung-24ghz.md` und `T-028-hoersitzung-reizplan.md` **verweigert**:
sie enthalten die reale SSID, die BSSID des Access Points und LAN-IPs des
Nutzers. Die Dateien liegen unveraendert im Working Tree. **Vor dem naechsten
sync-out zu bereinigen** — Platzhalter statt Klartext, Fundstellen stehen im
Archivist-Bericht. Die vier neuen Fixtures wurden gegengeprueft und sind sauber.

### T-030 geliefert — Buendelungskriterium steht in UI_SPEC.md

Neuer Abschnitt ab Zeile 1830, 14 neue Kriterien AK-T030-1..14, sieben
bestehende geaendert. **Noch nicht committet** (lief waehrend des sync-out).

**Die Groesse:** Ein Ausbruch liegt vor, wenn im zurueckliegenden
`LOSS_BURST_WINDOW_MS` (30 s) mindestens `LOSS_BURST_MIN_EPISODES` (3)
`dropouts`-Episoden gezaehlt wurden. Verworfen wurden: **Einzelabstand** (der
gemessene Median im Buendel, 2,70 s, liegt unter der Poll-Kadenz der App — ein
solches Kriterium misst den Poller, nicht die Strecke) und **Anteil gestoerter
Zeit** (Dauer je Episode ist mit den vorhandenen Zaehlern nicht messbar, der
Nenner waere erfunden).

**Zwei Zeilen, zwei Einheiten:** „Dropped audio: {N} incidents" traegt das
Verdikt; „Encoder ran dry: {N} times" steht gleichrangig daneben, nie rot, ohne
Verdikt und ohne verkleinerndes Wort. Neue Regel R-G: das Wort „packet" steht
nicht mehr fuer die Raeumungsfamilie. Neue Regel R-F: keine Groesse je Minute
oder Sekunde in der Verlustanzeige.

**Ehrlichkeit des Entwurfs, ausdruecklich festgehalten:** Die zweite Haelfte
meiner eigenen Auftragsbegruendung traegt NICHT. „Dieselben 11 Episoden
gleichmaessig verteilt waeren ein anderer Hoereindruck" ruhte auf dem
zurueckgenommenen Schluss, Einzelereignisse seien nicht bemerkt worden. Das
Kriterium spricht gleichmaessig Verteiltes deshalb **nicht** frei. Sein Gewinn
gegenueber der Rate ist, dass der **kurze dichte Ausbruch ueberhaupt erkannt**
wird — 11 Episoden in 21 s haette die alte 12/min-Schwelle verfehlt.

**Zwei bisher als `Measured` gefuehrte Werte sind falsifiziert** und
zurueckgezogen: `LOSS_ALERT_SUSTAINED_WINDOWS` = 2 (haette den kleinsten
gemeldeten Ausbruch verschluckt) und `LOSS_CLEAR_HOLD_MS` = 35 000 (Basis
ueberholt). Sieben Nachzuege an `ARCHITECTURE.md` sind gemeldet, darunter: der
Typ `LossThreshold.Measured(ratePerMin)` traegt die neuen Werte nicht — „3 in
30 s" ist keine Rate.

Neu als `Open`: obere Kante des Burst-Fensters `TODO(M-12)`, untere Kante der
Episodenzahl `TODO(M-13)` (ein Buendel aus genau 2 kam im Lauf nie vor),
Anteil gestoerter Zeit `TODO(M-14)` (kein Verfahren bekannt).

**RICHTIGSTELLUNG DES DIRECTORS zu einem Hinweis aus T-030:** Der Entwurf
nennt den Verlustfall aus `T-027-messung-24ghz.md` (660 kbps, WLAN-Konkurrenz
im 2,4-GHz-Band) als moeglichen ersten Hebel fuer Verlust bei gleicher, tieferer
Stufe. **Das traegt nicht.** Genau dieser Einzelfall wurde in T-028 ueber acht
gueltige Abschnitte — vier mit Reiz, vier ohne — **nicht reproduziert**, alle
0/0. Er gilt als nicht belastbar. Wer daran anknuepfen will, muesste zuerst die
Reproduzierbarkeit herstellen. Nicht als Hebel weiterverwenden.

### R-008 geliefert — EIN Stresshebel aus Bordmitteln gefunden

Volltext `docs/research/R-008.md`. **Noch nicht committet.**

**Der Hebel: die geoeffnete Seite "Neues Geraet koppeln".** Sie startet laut
AOSP eine klassische Inquiry (10 x 1,28 s) plus LE-Scan und **startet sie
automatisch neu, solange die Seite offen ist**. Entscheidend: Sie umgeht dabei
die Schutzlogik, mit der Android Discovery bei laufendem A2DP sonst
unterdrueckt — SettingsLib traegt dort den Kommentar "If we are playing music,
dont scan unless forced", und die Kopplungsseite nutzt diesen Pfad nicht. Die
Entwicklerdoku nennt Discovery als Vorgang, der die verfuegbare Bandbreite
bestehender Verbindungen erheblich reduziert.

**Erreichbar ueber die Oberflaeche, ohne Root, ohne Shell, ohne Fremdgeraet** —
genau was der Nutzer wollte. **Nur zeitlich dosierbar** (Seite offen gegen zu),
nicht in der Intensitaet. **Groessenordnung auf diesem Geraet unbelegt, muss
gemessen werden.**

Zweitbester: LE-Scan-Tastverhaeltnis ueber sechs `Settings.Global`-Schluessel,
10 bis 100 Prozent, stufenlos, Shell ohne Root — Wirkung aber unbelegt, und die
bisherigen Projektmessungen bei 10 bis 25 Prozent zeigten keinen Effekt.

**Fazit:** Ein im strengen Sinn dosierbarer Hebel aus reinen Einstellungen
existiert **nicht**; das zeitlich getaktete Inquiry-Verfahren ist der Ersatz.

**Zwei Nebenbefunde von Gewicht:**

- **Der Encoder-Thread laeuft SCHED_FIFO Prioritaet 1.** Damit ist die offene
  Frage aus R-007 geschlossen: gewoehnliche Rechenlast kann ihn nicht
  verdraengen, CPU-Last scheidet als Hebel endgueltig aus.
- **`MAX_PCM_FRAME_NUM_PER_TICK`** wuerde die konstante Raeumungsgroesse 25 aus
  T-029 erklaeren, war aber in den eingesehenen Headern nicht enthalten.
  **Kandidat fuer einen eigenen Quelltext-Auftrag** — der Beleg dafuer, warum
  eine Episode immer denselben Schwung mitnimmt.

**Naechster Messschritt, vom Lauf vorgeschlagen:** Kopplungsseite zu, dann zu
25, 50 und 100 Prozent der Zeit offen, je 3-5 min, fest 660, Zustandsbuch
komplett. Davor per Read-back pruefen, ob Discovery bei offener Seite wirklich
in Schleife laeuft.

### R-009 und R-010 SIND geliefert — Korrektur des Directors

Beide fable-Laeufe meldeten einen Abbruch am Nutzungslimit, und ich hatte
daraus geschlossen, sie haetten nicht mehr geschrieben. **Das war falsch.**
Der `archivist` hat es beim Sync bemerkt: `docs/research/R-009.md` (40 KB,
22:28) und `R-010.md` (44 KB, 22:31) existieren, sind vollstaendig und in sich
stimmig — beide Agenten hatten ihre Datei **vor** dem Abbruch gesichert. Der
Abbruch traf nur den Abschlussbericht an mich.

**Beide sind NICHT neu zu beauftragen.** Sie sind zu lesen und auszuwerten;
das steht als erstes auf der Liste der naechsten Sitzung.

**Der Nachtrag am Ende von `docs/tasks/T-031.md` behauptet ebenfalls faelschlich,
sie seien nicht geliefert.** Die dort festgehaltene Zielrichtung (990 ist das
Ziel) bleibt gueltig und wichtig — nur der Anlass stimmt nicht. Die Steuerung
wurde den Laeufen waehrend der Arbeit per Nachricht mitgegeben; ob sie sie noch
eingearbeitet haben, ist beim Lesen zu pruefen.

**Lehre, fuer die Retrospektive:** Ein gemeldeter Agentenabbruch heisst nicht,
dass nichts geschrieben wurde. Vor jeder Aussage "nicht geliefert" gehoert ein
Blick ins Dateisystem — genau das hat der `archivist` getan und ich nicht.
