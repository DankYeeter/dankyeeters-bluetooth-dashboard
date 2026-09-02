# T-011 / M-5 — Ruherate über ≥ 30 min ABR, Pixel 11 Pro

Ausführender Agent: `performance-tuner`. Auftrag: `docs/tasks/T-011.md`.
Werkzeuge: `docs/perf/tools/m5_run.sh` (Geräteseite, Sammelschleife),
`docs/perf/tools/m5_parse.awk` (Hostseite, Zeitreihe → CSV). Rohdumps
(`series.log`, 3,5 MB, 62 825 Zeilen) liegen wie in T-007/T-008 ausserhalb
des Repos.

**Eile-Hinweis:** Auf Anweisung des Directors wurde die Reihe wegen
bevorstehenden Abziehens des Kabels vorzeitig, aber nach Erreichen des
Gültigkeitskriteriums (> 30 min netto) gestoppt. Die Rohdaten sind
**gesichert** (Antwort auf die Rückfrage des Directors, s. u.).

---

## 1. Messszenario

| Punkt | Wert |
|---|---|
| Zielgerät | Pixel 11 Pro, `67011FDKX004XG`, Android 17, per Kabel |
| adb | `C:\RSL\2.1HF5\adb\adb.exe` |
| Zustand | warm — Musik lief bereits (TIDAL, `state=PLAYING`), kein Cold-Start |
| App unter Test | nicht installiert (`pm list packages \| grep btdash`, rc=1, vor Laufbeginn geprüft) |
| Eingriff | keiner — reine Beobachtung, nichts am Gerät verändert |
| Kadenz | ~1,4 s (Auftragsvorgabe), gemessen: Mittel **1,302 s**, sd 0,033 s, Spanne 1,066–1,465 s |
| Quelle je Sample | nur die Blöcke `A2DP State:` (Counts inkl. dropped/dropouts/underflow) und `A2DP LDAC State:` (Priority, Quality Mode, Bitrate, Queue, ABR-Index/-Adjustments) aus `dumpsys bluetooth_manager` — reduziert nur die **Schreibmenge**, nicht die Stack-Berührung (die ist pro Sample identisch zu Block 1/T-007/T-008) |

**Warum diese Kadenz und dieser Zuschnitt:** ~1,4 s ist gemäss Auftrag für
eine Ruherate über 30 min ausreichend (der 379-ms-Lauf gehört zu M-6/M-10).
Volle Dumps über ~1500+ Samples hätten mehrere hundert MB ergeben; die
beiden benötigten Blöcke reichen für Primär- und Sekundärauswertung und
halten die Reihe bei 3,5 MB.

---

## 2. Zustandsbuch vorher / nachher

Vorher aufgenommen vor Laufbeginn (Host ~08:11–08:12, Gerät ~08:12–08:14).
Nachher aufgenommen unter Zeitdruck nach Weisung des Directors — nicht alle
Felder erneut geprüft; ungeprüfte Felder sind als **nicht erhoben**
gekennzeichnet, nicht als unverändert unterstellt.

| Feld | Vorher | Nachher | Diff |
|---|---|---|---|
| `global wifi_on` | 1 | 1 | unverändert (Bookends; Zwischenstand nicht überwacht) |
| `dumpsys wifi` „Wi-Fi is …" | enabled | enabled | unverändert |
| **BLE-Scans (`Ongoing N scans`)** | **1** (`com.google.android.gms.findmydevice`, `LOW_POWER`, `ALL_MATCHES_AUTO_BATCH`, `ACTIVE`) | **2** (zusätzlich `nearby_fast_pair`, `BALANCED`, `ACTIVE`, gestartet ~08:30 Gerätezeit; `nearby_connections`, `AMBIENT_DISCOVERY`, `ACTIVE`, gestartet ~08:47 Gerätezeit) | **ABWEICHUNG — Befund, s. 2.1** |
| Bonded devices | 3 | 3 | unverändert |
| Aktives A2DP/HEADSET-Gerät | `…:37:8F` (Kopfhörer), verbunden seit 08:01:13 Gerätezeit | dasselbe Gerät, **keine** `CONNECTION_STATE_CHANGED`-Events im Fenster (Event-Log-Diff, s. 2.2) | unverändert |
| `Priority` (LDAC) | **5001** | **5001** | unverändert — **s. 2.3 zum Abgleich mit der Nutzerangabe** |
| `LDAC quality mode` | ABR | ABR | unverändert (zusätzlich: in **allen 1795 Samples** der Reihe `ABR`, keine einzige Änderung) |
| `Config` (Rate/Bits/Mode) | `96000/32/STEREO` | `96000/32/STEREO` | unverändert |
| `global bluetooth_on` | 1 | 1 (erneut geprüft 08:52:54) | unverändert |
| `global ble_scan_always_enabled` | 1 | 1 (erneut geprüft 08:52:54) | unverändert |
| `global low_power` | 0 | 0 (erneut geprüft 08:52:54) | unverändert |
| `global wifi_scan_always_enabled` | 1 | **nicht erhoben** | — |
| `secure location_mode` | 3 | **nicht erhoben** | — |
| `deviceidle` mState/mLightState | ACTIVE/ACTIVE | **nicht erhoben** | — |
| BT-Prozess Doze-Whitelist | exempted (`user,com.google.android.bluetooth,1002`) | **nicht erhoben** | — |
| Thermal Status | 0 (NONE, alle Sensoren `mStatus=0`) | **nicht erhoben** | — |
| Lautstärke (`bt_a2dp`-Stream) | 20 / 25 | **nicht erhoben** | — |
| App installiert | nein (rc=1) | **nicht erhoben** (kein Installationsweg während des Laufs plausibel, aber nicht geprüft) | — |

### 2.1 Befund: BLE-Scananzahl änderte sich unbeobachtet während des Laufs

Nicht durch mich ausgelöst — ich habe nichts am Gerät verändert. Zwei
zusätzliche GMS-Scanner (`nearby_fast_pair`, `nearby_connections`) liefen
zeitweise mit, laut `Elapsed`-Zeitstempeln ab ~08:30 bzw. ~08:47 Gerätezeit,
also **innerhalb** des Messfensters (Fenster: 08:14:22–08:53:18 Gerätezeit).
Nach der verschärften Read-back-Regel aus T-008 ist das ein **Befund, kein
Randdetail**.

**Einordnung, ohne die Regel zu unterlaufen:** Anders als beim WLAN-
Konfundierer in T-008 (7b) wirkt diese Abweichung **nicht gegen** das
Ergebnis, sondern **verstärkt** es: Trotz Wechsel von 1 auf 2 aktive
BLE-Scans blieben `dropped` und `dropouts` über die gesamte Reihe bei
exakt 0 (s. Abschnitt 4). Das Nullergebnis ist also **nicht** an eine
konstante Scan-Zahl gebunden — es hält über einen Zustandswechsel hinweg.
Für eine strikte Eine-Variable-Regel ist der Lauf trotzdem nicht als
kontrollierter Vergleich zu behandeln, nur als robuster Beobachtungsbefund.

### 2.2 Ereignis-Log-Diff: keine Verbindungs- oder Codec-Übergänge

Abgleich der `rec[]`-Einträge für das aktive Gerät zwischen Vorher- und
Nachher-Dump: der letzte `CONNECTION_STATE_CHANGED`/`CODEC_CONFIG_CHANGED`
liegt bei 08:01:13 Gerätezeit — **vor** Laufbeginn. Im Fenster selbst nur
zwei harmlose `EVENT_TYPE_BIEV`-Einträge (Akkustand-Telemetrie des
Kopfhörers, 08:36:39, Wert 90 %), keine Verbindungs-, Codec- oder
Profilwechsel. Relevant für den M-8-Beitrag, s. Abschnitt 6.

### 2.3 Diskrepanz zur gemeldeten Codec-Auswahl — protokolliert, nicht bewertet

Der Auftrag nennt den vom Nutzer gemeldeten Zustand „Codec-Auswahl LDAC
gepinnt". Mein eigener Read-back zeigt **`Priority: 5001`** — laut dem in
T-008 belegten Zusammenhang (Abschnitt 6 dort) ist das der Wert für
**„System-Auswahl verwenden"**, nicht der Pin-Marker `1000000`. Das
Ereignis-Log zeigt ausserdem, dass der Kopfhörer heute um 08:01:13 mit
`mCodecPriority:5001` neu verbunden hat — der Pin-Marker aus T-008
(`1000000`) ist demnach zwischenzeitlich zurückgesetzt worden (vermutlich
die in `docs/state.md` offene Aufgabe des App Designers).

**Für diese Messung folgenlos:** LDAC lief so oder so (System-Auswahl
wählt hier ohnehin LDAC), und Quality Mode war durchgehend `ABR` — exakt
die Bedingung, die M-5 verlangt. Ich fasse das nicht an und werte es nicht;
die Abweichung zur Nutzerangabe gehört an den Director.

---

## 3. Dauer, Kadenz, Lückenprüfung

| Grösse | Wert |
|---|---|
| Samples | 1795 |
| Fenster (Gerätezeit) | 08:14:22,214 – 08:53:18,034 |
| **Nettodauer** | **2335,82 s = 38,930 min** |
| Kadenz Mittel / sd | 1,302 s / 0,033 s |
| Kadenz Min / Max | 1,066 s / 1,465 s |
| Lücken > 4 s | **keine** (geprüft über alle 1794 Sample-Übergänge) |
| Zähler-Resets (Wert sinkt zwischen Samples) | **keine**, bei `dropped`, `dropouts` und `underflow` |

Gerätezeit lief **89 s vor** der Hostzeit beim Start (08:14:22 Gerät vs.
08:12:53 Host) — deckt sich fast exakt mit den 88 s aus T-008, ein weiterer
unabhängiger Beleg für die Stabilität dieses Versatzes.

**Gültigkeit nach Weisung des Directors:** Nettodauer 38,93 min liegt über
der 30-min-Schwelle → **M-5 gilt als vollständig**, nicht als Teilmessung.
Sie erreicht auch den im Auftrag genannten Zielkorridor von 35–40 min.

---

## 4. Primärauswertung M-5: `dropped` und `dropouts`

| Zähler | Start | Ende | Minimum | Maximum | Delta über 38,93 min |
|---|---|---|---|---|---|
| **`dropped`** | 0 | 0 | 0 | 0 | **0** |
| **`dropouts`** | 0 | 0 | 0 | 0 | **0** |

Geprüft ist das nicht nur an den Eckwerten: **jeder der 1794
Sample-zu-Sample-Übergänge** wurde auf ein Delta > 0 geprüft (Skript
`m5_parse.awk` + Auswertungsschritt, s. u.) — kein einziges Ereignis, keine
Bündel, keine Zeitpunkte, keine Abstände zu berichten, weil keine
Ereignisse aufgetreten sind.

**Nebenbefund, ausdrücklich ausserhalb der Leitgrösse:** `underflow` stieg
im selben Fenster von 2 auf 25 (Δ 23, **0,591/min**) — ungleich der
514-s-Referenz, in der `underflow` in allen fünf Armen bei 0 blieb. Nach
T-009/`UI_SPEC.md` trägt `underflow` **kein Verdikt** (R-D, AK-T009-24) und
bleibt hier folgenlos für M-5. Es bestätigt aber genau den Satz, den
AK-T002-16 dafür fordert: „ein Zähler auf null sagt nur etwas über diesen
Zähler" — über 514 s war `underflow` zufällig null, über 39 min zeigt sich,
dass er es nicht strukturell ist. Kein Handlungsbedarf aus M-5, aber ein
Datenpunkt für den `ui-ux-designer`, falls die Sechs-Wort-Begründung dort
einmal mit einer Zahl unterlegt werden soll.

---

## 5. Urteil zur Schwelle `LOSS_NOTICE_RATE_PER_MIN`

**Dreierregel-Obergrenze, neu gerechnet aus der Gesamtdauer:**

| Grundlage | Dauer | Obergrenze (95 %, Nullbefund) |
|---|---|---|
| T-007/T-008 (5-fach belegt) | 514 s | 0,350/min |
| **T-011 (dieser Lauf, allein)** | 2335,82 s | 0,077/min |
| **Kombiniert (Auftragsvorgabe: 514 s + neuer Lauf)** | **2849,82 s = 47,50 min** | **0,063/min** |

(Formel wie in T-011 selbst benannt: Obergrenze = 3 Ereignisse / Gesamtdauer,
180/Sekunden·min⁻¹; 180/2849,82 = 0,0632/min.)

**Antwort auf die einzige Frage dieses Laufs:** Die Ruherate von `dropped`
und `dropouts` bleibt über ≥ 30 min ununterbrochener ABR-Wiedergabe
**exakt bei null** — kein Anstieg, kein Ausreisser, kein einziges
Ereignis über 38,93 min bei ~1,3-s-Kadenz.

**`LOSS_NOTICE_RATE_PER_MIN` = 1/min trägt weiterhin — mit deutlich mehr
Sicherheitsabstand als zuvor.** Die neue Obergrenze (0,063/min) liegt
**~16-fach unter** dem gesetzten Wert, gegenüber ~3-fach vor diesem Lauf.
Der im Auftrag benannte Sorgenfall — „Obergrenze rutscht so nah an den
Parameter, dass er in der Praxis pillt" — tritt **nicht** ein. Eine
Sustain-Bedingung ist nach diesem Befund **nicht** erforderlich; die
bestehende einfache Schwelle bleibt tragfähig, sogar mit Reserve nach
unten (eine Absenkung wäre rechnerisch vertretbar, ist aber nicht
Gegenstand dieses Auftrags und wird nicht empfohlen, ohne dass der
`director` das anfordert).

**Einschränkung, die dazugehört:** Die gepoolte Dauer verbindet zwei nicht
identische Umgebungen — 514 s mit WLAN aus (T-007/T-008), 2335,82 s mit
WLAN an und wechselnder Scananzahl (dieser Lauf, s. 2.1). Das ist die vom
Auftrag verlangte Rechnung, aber kein sauberer Wiederholungsversuch unter
gleicher Bedingung. Da beide Teilmengen unabhängig voneinander bereits bei
null liegen (0,350/min bzw. 0,077/min Obergrenze), ändert das am Urteil
nichts — es macht die Aussage sogar robuster gegenüber genau der Variable
(WLAN/Scans), die in T-008 als Konfundierer aufgefallen war.

---

## 6. M-8-Beitrag (Sekundärauswertung, nachrangig)

Ohne Zusatzaufwand aus derselben Reihe verfügbar:

- **Echte Übergänge (Verbindung/Codec) im unangetasteten Fenster: 0.**
  Beleg: Event-Log-Diff (Abschnitt 2.2) zeigt keinen
  `CONNECTION_STATE_CHANGED`- oder `CODEC_CONFIG_CHANGED`-Eintrag zwischen
  Vorher- und Nachher-Dump für das aktive Gerät. **Einschränkung:** diese
  Methode liefert nur „null über das gesamte 38,93-min-Fenster", keine
  zeitaufgelöste Rate — für `SETTLE_MAX_SPAN_MS` bräuchte es mehrere
  unabhängige Fenster dieser Länge, nicht eines.
- **Playback-Start/-Stopp:** nicht erfasst (kein `media_session`-Sample
  pro Messpunkt) — hier ohne Zusatzaufwand nicht zu beantworten, deshalb
  ausgelassen statt geschätzt.
- **ABR-Stufenwechsel (kein M-8-Übergang im engeren Sinn, aber
  einordnungsrelevant):** 176 Wechsel / 38,93 min = **4,52/min**, vier
  Stufen berührt (396/492/660/990 kbps, Anteile 4,9 % / 36,0 % / 57,4 % /
  1,8 %). Das liegt in derselben Grössenordnung wie A0 (3,69/min) und A'
  (4,91/min) aus T-008 — **keine** Auffälligkeit, nur mehr Gelegenheiten
  über die neunfache Laufzeit.
- **Der Regler bestätigt sich selbst, jetzt mit n=31 statt n=1:** 990 kbps
  wurde 31-mal angesteuert, **30 davon exakt 1 Sample lang** (1 Lauf 2
  Samples), danach sofort wieder verlassen — im Mittel 1,03 Samples pro
  Episode. Bei **keiner** dieser 31 Annäherungen entstand ein `dropped`
  oder `dropouts`-Ereignis. Das reproduziert den T-008-Befund („Regler
  probiert 990 und verwirft es binnen 1,4 s") um den Faktor 31 und stützt
  AK-T009-27/28 (kein Zwischenwert, „less than 2×Kadenz").

---

## 7. Rohdaten — Sicherungsstatus (Antwort an den Director)

**Ja, die Rohdaten sind sicher vom Gerät herunter:** `series.log` wurde vor
dem Stoppsignal gezogen (3 494 701 Byte) und nach dem sauberen Stopp
(`donemark` vorhanden, Prozess beendet) erneut gezogen (3 506 472 Byte,
1795 Samples, Anzahl deckt sich mit der Geräte-eigenen `count`-Datei).
Beide Kopien liegen ausserhalb des Repos im Scratch-Verzeichnis dieser
Sitzung. Bei einem unvollständigen Lauf hätte nachgerechnet werden können —
war hier nicht nötig, da die Nettodauer die 30-min-Schwelle bereits
deutlich überschreitet.

---

## 8. Werkzeug-Hinweis (SR-012)

`docs/perf/tools/m5_run.sh` und `docs/perf/tools/m5_parse.awk` sind neu und
sollten laut Auflage `0600` tragen. `chmod 600` wurde ausgeführt, zeigt auf
diesem Windows/NTFS-Arbeitsplatz aber **keine Wirkung** (`ls -la` weiterhin
`-rwxr-xr-x` bzw. `-rw-r--r--`) — dieselbe Einschränkung, die vermutlich
hinter der Zurückstellung von SR-012 in `docs/state.md` steht. Melde ich
als Befund, löse ich nicht eigenmächtig (keine Werkzeug- oder
Config-Änderung ausserhalb des Auftrags).

---

## 9. Zusammenfassung für den Director

- **M-5 vollständig:** 38,93 min netto, lückenlos, `dropped`/`dropouts`
  durchgehend 0.
- **`LOSS_NOTICE_RATE_PER_MIN` = 1/min bleibt gesetzt.** Neue
  Dreierregel-Obergrenze (kombiniert 514 s + 2335,82 s): **0,063/min** —
  keine Sustain-Bedingung nötig.
- **Zwei Befunde ausserhalb der Primärfrage, beide gemeldet, keiner
  bewertet oder behoben:** BLE-Scananzahl wechselte unbeobachtet 1→2
  während des Laufs (Abschnitt 2.1, schwächt das Ergebnis nicht, schwächt
  aber die Reinheit der Bedingung); `Priority: 5001` widerspricht der
  gemeldeten „LDAC gepinnt"-Angabe (Abschnitt 2.3).
- **`underflow`-Nebenbefund** (0,591/min über 39 min) ist kein M-5-Problem,
  aber ein Datenpunkt für `ui-ux-designer`/`AK-T002-16`.
- **M-8-Beitrag:** 0 echte Übergänge im 38,93-min-Fenster (methodisch nur
  als Fenstersumme, nicht zeitaufgelöst) plus n=31-Bestätigung des
  Regler-Selbstkorrektur-Befunds aus T-008.
- Sechs Zustandsbuch-Felder im Nachher **nicht erhoben** (Abschnitt 2) —
  wegen der Eile explizit als Lücke markiert, nicht als „unverändert"
  unterstellt.
