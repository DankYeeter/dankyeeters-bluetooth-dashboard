# T-008 — Drei Eingriffsexperimente

Fortsetzung von T-007, gleicher Agent, gleiche Skripte, gleiche Kadenz.

**Stand:** **E-2 ist vollstaendig** — A0 → B → A' gefahren, mit Hoereindruck
in allen drei Armen. E-1 und E-3 bleiben mangels Hebel offen.

Schwaerzung wie in T-007: MACs gekuerzt, Geraetenamen nur als Rolle.

---

## 0. Das wichtigste Einzelergebnis: der Kalibrierpunkt der Hoerbarkeitsgrenze

`UI_SPEC.md` fordert im Abschnitt „Die Hoerbarkeitsgrenze" einen Bezugspunkt
zwischen gemessenen Zaehlern und tatsaechlicher Wahrnehmung. Der liegt jetzt
zum ersten Mal vor — aus drei Armen mit Hoereindruck des App Designers:

| Arm | Zustand | **Dropouts (gemessen)** | **Hoereindruck** |
|---|---|---|---|
| A0 | ABR, 492/660 | **0** / 97,6 s | keine Aussetzer |
| **B** | **990 gepinnt** | **21** / 97,0 s = **12,99/min** | **„hoere nach wie vor Aussetzer"** — durchgehend |
| A' | ABR zurueck | **0** / 97,8 s | **„keine Aussetzer mehr"** |

**Zaehler und Ohr stimmen ueberein, in beide Richtungen.** Der Uebergang ist
in beiden Richtungen sauber und wurde in derselben Sitzung, am selben Geraet,
mit demselben Hoerer und demselben Musikmaterial belegt.

Was daraus **belegt** ist:

- **~13 Dropouts/min (≈ alle 4,6 s) sind deutlich hoerbar.**
- **0 Dropouts/min sind unhoerbar.**

Was daraus **nicht** folgt: wo genau zwischen 0 und 13/min die Schwelle
liegt. Wir haben zwei Punkte, keine Kurve. Eine Anzeige darf daraus **nicht**
ableiten, dass z. B. 3/min hoerbar oder unhoerbar sind — das ist ungemessen.

**Zweiter, davon unabhaengiger Kalibrierpunkt:** Die Verlustzaehler des
Stacks sind fuer die Hoerbarkeit **die richtige Groesse**. Die
`Counts (underflow)` blieben in **allen** Armen bei 0 — auch im hoerbar
gestoerten Arm B. Wer Aussetzer anzeigen will, muss
**`Counts (dropped/dropouts)`** lesen, nicht `underflow`. Eine Anzeige, die
nur Underflows zeigt, haette den hoerbar kaputten Arm B als einwandfrei
gemeldet.

---

## 1. Phasenprotokoll

Geraetezeit laeuft **88 s vor** der Hostzeit. Der App Designer hoert am
Geraet mit, deshalb ist die **Geraetezeit** massgeblich.

| Phase | Host | **Geraet** | Zustand | Hoereindruck |
|---|---|---|---|---|
| Zustandsbuch | 21:52 | **21:53** | ABR | — |
| **Arm A0 — Referenz** | 21:52:36–21:54:15 | **21:54:04–21:55:44** | ABR, 492/660 | keine Aussetzer |
| E-1 Hebelversuch (`appops`) | ~21:56 | **~21:57** | ohne Wirkung | — |
| Zustandsabgleich | 21:59 | **22:01** | identisch mit Buch | — |
| *App Designer pinnt 990 von Hand* | — | **zw. 22:01 und 22:07** | ABR → HIGH | — |
| Read-back Pin | 22:05:38 | **22:07:06** | **990 bestaetigt** | — |
| **Arm B — Standard** | 22:06:00–22:07:39 | **22:07:28–22:09:07** | **990 gepinnt** | **Aussetzer** |
| **Arm B — schnell** | 22:07:50–22:08:51 | **22:09:19–22:10:20** | **990 gepinnt** | **Aussetzer** |
| *Pin blieb stehen (Warten auf Hoereindruck)* | — | **bis ~22:20** | 990 | **Aussetzer** |
| Stichprobe Director | — | **22:19:30** | 990 | — |
| *App Designer stellt auf adaptiv zurueck* | — | **zw. 22:19:30 und 22:21:42** | HIGH → ABR | — |
| Read-back Rueckflip | 22:20:14 | **22:21:42** | **ABR bestaetigt** (mit Vorbehalt, s. 6) | — |
| **Arm A' — Rueckkehr** | 22:20:42–22:22:21 | **22:22:10–22:23:50** | ABR | **keine Aussetzer** |
| Endzustand E-2 | 22:23:22 | **22:24:50** | ABR, 492 | — |
| *App Designer: 990 erneut gepinnt, BT-Suche + Quick Share aus* | — | **zw. 22:24:50 und 22:39:27** | ABR → HIGH, 3 → 2 Scans | — |
| **WLAN geht an und assoziiert (unbemerkt)** | — | **22:34:53** | **Konfundierer, s. 7b** | — |
| Read-back vierte Zelle | 22:37:59 | **22:39:27** | **990 + 2 Scans bestaetigt**; WLAN **nicht** geprueft | — |
| **Arm E2_B_reduced — Standard** | 22:38:46–22:40:17 | **22:40:14–22:41:46** | **990, 2 Scans** | — |
| **Arm E2_B_reduced — schnell** | 22:40:30–22:41:29 | **22:41:58–22:42:57** | **990, 2 Scans** | — |

---

## 2. Read-backs

**Pin gesetzt (22:07:06)** — vier unabhaengige Belege:

| Feld | A0 | gepinnt |
|---|---|---|
| `LDAC quality mode` | `ABR` | **`HIGH`** |
| `LDAC transmission bitrate` | 492/660 wechselnd | **990 konstant** |
| `LDAC adaptive bit rate …` | vorhanden | **Felder verschwunden** — Regler aus |
| `Priority` (LDAC) | 5001 | **1000000** — Pin-Marker |

Der Pin hielt ueber beide B-Arme: in **allen 230 Samples** stand 990.

**Pin geloest (22:21:42)** — teilweise:

| Feld | Soll (A0) | nach Rueckflip | |
|---|---|---|---|
| `LDAC quality mode` | `ABR` | **`ABR`** | OK |
| `LDAC adaptive bit rate` Felder | vorhanden | **vorhanden** (idx 1, adj 11) | OK |
| Bitrate | wechselnd | **wechselnd** | OK |
| **`Priority` (LDAC)** | **5001** | **1000000** | **ABWEICHUNG — s. 6** |

Der funktional relevante Schalter (Quality Mode) ist zurueck; ABR regelt
wieder. Arm A' ist damit gueltig.

---

## 3. Ergebnis E-2: A0 → B → A'

| Groesse | **A0 (ABR)** | **B (990 gepinnt)** | **B schnell (990)** | **A' (ABR zurueck)** |
|---|---|---|---|---|
| Kadenz | 1415 ± 33 ms | 1407 ± 32 ms | 379 ± 20 ms | 1417 ± 20 ms |
| Dauer | 97,6 s | 97,0 s | 60,3 s | 97,8 s |
| Stufe | 492/660 | **990 konstant** | **990 konstant** | 396/492/660/990 → 492/660 |
| Stufenwechsel | 6 (3,69/min) | **0** | **0** | 8 (4,91/min) |
| **Dropped packets** | **0** | **525** (324,7/min) | **325** (323,4/min) | **0** |
| **Dropouts** | **0** | **21** (12,99/min) | **13** (12,94/min) | **0** |
| Underflows | 0 | **0** | **0** | 0 |
| Flushes | 0 | 0 | 0 | 0 |
| `SavedTxQueue` ≠ 0 | 1/70 (Wert 2) | **55/70 (79 %)** | **129/160 (81 %)** | **0/70 (0 %)** |
| `SavedTxQueue` Mittel / Max | ~0 | **7,69 / 24** | **7,69 / 23** | **0 / 0** |
| Enqueue-Rate | 2999,0/min | 3001,5/min | 3000,0/min | 3001,2/min |
| **Hoereindruck** | keine Aussetzer | **Aussetzer** | **Aussetzer** | **keine Aussetzer** |

**Das A/B/A schliesst sauber.** Die Verluste gehen von 0 auf 525 und wieder
auf **exakt 0** zurueck. Die Sendeschlange ist in A' sogar **in allen 70
Samples leer** — sauberer als in A0, das ein Sample mit Wert 2 hatte.

Die **Enqueue-Rate ist ueber alle vier Arme konstant** (2999,0 – 3001,5/min,
Spanne 0,08 %). Der Vergleich ist damit nicht durch eine veraenderte
Grundrate verzerrt: der Encoder liefert in jedem Arm gleich viel, nur die
Strecke traegt es unterschiedlich.

### 3.1 Mechanismus: Warteschlangenueberlauf, nicht Quellenmangel

- **`Counts (underflow)` bleibt in allen Armen 0.** Der Encoder bekommt immer
  genug PCM. Es ist **kein** Zuliefer-Problem.
- **`Counts (dropped)` steigt im 990er-Arm auf 324/min.** Die
  Sendewarteschlange laeuft ueber, weil der Funk nicht schnell genug abholt.
- `Counts (max dropped): 26` — die Verluste kommen in **Buendeln bis 26
  Paketen**, nicht als gleichmaessiges Rieseln. Das passt zum Hoereindruck
  „Aussetzer" statt „Rauschen".
- `Dequeue overdue scheduling time … max` steigt von **26 ms** (T-007) auf
  **62 ms**.
- LDAC selbst meldet `Packet counts (expected/dropped): 78260 / 0` — **der
  Encoder arbeitet fehlerfrei**. Verworfen wird erst danach, in der TxQueue.

Das deckt sich exakt mit dem R-001-Mechanismus: ABR regelt anhand der
Sendeschlangentiefe. Nimmt man ihm die Regelung weg, laeuft genau die
Groesse voll, auf die er sonst reagiert haette — und zwar in 4 von 5
Samples.

### 3.2 A': keine Nachwirkung, aber ein Einschwingvorgang

Die Frage des Directors, ob zwoelf Minuten Dauerueberlast etwas
hinterlassen, ist beantwortet: **nein, nichts Dauerhaftes.** Aber der
Weg dorthin ist aufschlussreich.

| Abschnitt | Dauer | Stufen | Wechsel/min | ABR-Adj/min | gew. Mittel |
|---|---|---|---|---|---|
| A' Samples 0–13 (**Einschwingen**) | 18,5 s | 396, 492, 660, **990** | **13,00** | **16,24** | 549,9 kbps |
| A' Samples 14–69 (**eingeschwungen**) | 77,9 s | 492/660, 28/28 | **3,08** | 6,16 | **576,0 kbps** |
| A0 (Referenz) | 97,6 s | 492/660, 33/37 | 3,69 | 3,69 | **580,8 kbps** |

Der Regler tastet in den ersten 18,5 s **alle vier Stufen** ab und faellt
danach in exakt das alte Muster zurueck: 492/660 im Wechsel, Verweildauern
6, 14, 12, 14, 10 Samples — die aus T-007 und A0 vertrauten 11–14. Das
gewichtete Mittel im eingeschwungenen Teil (576,0 kbps) trifft A0
(580,8 kbps) auf 0,8 % genau.

**Die erhoehte Anpassungsrate des Gesamtarms (7,98/min gegen 3,69/min in A0)
geht vollstaendig auf diese Einschwingphase zurueck.** Es gibt keine
Nachwirkung der Ueberlast.

### 3.3 Der Regler bestaetigt unseren Befund selbst

Der wertvollste Einzelmoment aus A': bei **t = 11,32 s** probiert ABR von
sich aus **990 kbps** (Index 0) — die Stufe, die er in T-007 und A0 nie
angesteuert hatte. **Er verlaesst sie beim naechsten Sample 1,4 s spaeter
wieder und faellt gleich zwei Stufen auf 492** (`adjustments` springt um 2).
Danach ruehrt er 990 nicht mehr an.

Das ist eine **unabhaengige, autonome Bestaetigung**: Der Regelalgorithmus
kommt an derselben Strecke zum selben Urteil wie wir — 990 traegt hier
nicht. Wir haben es ihm aufgezwungen und Verluste gemessen; er testet es
selbst und verwirft es binnen anderthalb Sekunden.

---

## 4. Periodizitaet — Phaenomen bestaetigt, Takt nicht

Das Urteil aus dem B-Arm bleibt: **INCONCLUSIVE fuer eine echte
~3-s-Periodizitaet, bestaetigt fuer das Phaenomen.**

Ereignisabstaende im schnellen Lauf (379 ms Aufloesung, der belastbare):

```
7,68  6,37  7,57  5,26  3,82  5,38  4,48  3,05  2,97  4,23  4,90  3,03   [s]
Median 4,69 s | Mittel 4,90 s | sd 1,58 s | Spanne 2,97–7,68 s
```

Autokorrelation der Drop-Inkremente (Band ±2/√160 = ±0,158): bei 3,03 s
r = +0,171, bei 7,58 s r = +0,172. **Von 23 gepruefen Lags ueberschreiten 2
das Band; rein zufaellig erwartet man bei α ≈ 5 % genau 1,2.** Das haelt
einer Mehrfachvergleichskorrektur nicht stand. Die Autokorrelation der
Queue-Tiefe zeigt im 3-s-Bereich nichts (−0,10 / −0,02 / −0,13).

**Aufloesung des scheinbaren Widerspruchs zu T-005:** Die Ereignisse treten
mit ~13/min auf, im Mittel alle 4,6–4,9 s, kuerzeste Abstaende 2,97–3,05 s.
Wer unregelmaessig alle 3 bis 7 Sekunden ein Stocken hoert, beschreibt das
plausibel als „stockt etwa alle 3 Sekunden". **Die T-005-Wahrnehmung war
richtig, nur die unterstellte Regelmaessigkeit nicht.** Das Stocken ist real
und haeufig — aber es tickt nicht.

**Methodischer Vorbehalt, der eine Fehldeutung verhindert:** Im Standardlauf
sehen die Abstaende taeuschend diskret aus (2,81 / 4,22 / 5,63 / 7,05 s).
Das sind exakt 2×, 3×, 4×, 5× seine Kadenz von 1407 ms — ein
**Quantisierungsartefakt**. Der Standardlauf kann die Abstandsverteilung
grundsaetzlich nicht aufloesen und liefert nur die **Rate** belastbar. Fuer
die Zeitstruktur zaehlt allein der schnelle Lauf. Waere nur der Standardlauf
ausgewertet worden, haette man eine Periodizitaet berichtet, die es nicht
gibt.

---

## 5. Urteil zu E-2

**„990 gepinnt → Sendeschlangenueberlauf → Paketverluste → hoerbare
Aussetzer" ist BELEGT.** Evidenzniveau nach GOAL.md AK-3:

| Glied der Kette | Evidenz | Grundlage |
|---|---|---|
| Pin wirkt tatsaechlich | **belegt** | 4 Read-back-Felder, 230/230 Samples auf 990 |
| 990 → Queue laeuft voll | **belegt** | Queue ≠ 0 in 79–81 % der Samples (A0/A': 1 % / 0 %) |
| Queue voll → Paketverluste | **belegt** | 525 bzw. 325 Drops; `max dropped 26`; Encoder selbst fehlerfrei |
| Verluste → hoerbar | **belegt** | Hoereindruck in allen drei Armen, in beide Richtungen |
| Reversibel | **belegt** | A' zurueck auf exakt 0, Queue in 70/70 Samples leer |
| Nicht durch Drift erklaerbar | **belegt** | Nullwert 5-fach belegt (T-007 A/B/C, A0, A'), 514 s ohne einen Drop; Enqueue-Rate ueber alle Arme konstant |
| **Ursache des Ueberlaufs** | **offen** | Funkstoerung, Empfangsgrenze des Hoerers oder schlicht zu hohe Rate — nicht getrennt |
| **Echter ~3-s-Takt** | **INCONCLUSIVE** | s. Abschnitt 4 |

Die beiden 990er-Laeufe sind unabhaengig (andere Kadenz, andere Dauer) und
stimmen auf **0,4 %** ueberein: 324,7 vs. 323,4 Drops/min, 12,99 vs.
12,94 Dropouts/min.

### 5.1 Unkontrollierter Langzeitarm — **kein Messwert**

Der Pin stand nach meinen Armen noch rund zwoelf Minuten. Stichprobe des
Directors um **22:19:30 Geraetezeit**: `HIGH`, 990 kbps, **4805 verworfene
Pakete, 192 Dropouts** kumuliert, letzter Verlust 2,6 s her, Underflows
unveraendert 536. Mein eigener Zaehlerstand um 22:21:42 lag bei **5280 Drops
/ 211 Dropouts / 11 Flushes**.

**Ausdruecklich als Groessenordnung gekennzeichnet, nicht als Messwert:**
Ueber die lange Strecke ergibt das grob ~400 Drops/min gegenueber
325/min im kontrollierten Fenster. Kein definiertes Zeitfenster, kein
Startsnapshot, waehrenddessen wurde bedient und der Hoereindruck erhoben.
**Verwertbar ist daraus nur eines:** die Verluste hoerten ueber zwoelf
Minuten **nicht** von selbst auf — es ist kein Einschwingeffekt, sondern ein
Dauerzustand. Die Zahl 400/min selbst traegt kein Urteil.

---

## 6. Sofortbefund: der Pin-Marker ist nicht zurueckgestellt

Der Director hat ausdruecklich die vollstaendige Rueckkehr in den
Ausgangszustand verlangt. Sie ist **nicht** erreicht:

```
A2DP LDAC State:
  Priority: 1000000        <-- Soll laut Zustandsbuch/T-007: 5001
  LDAC quality mode : ABR  <-- korrekt zurueckgestellt
```

**Was das bedeutet:** Es sind **zwei** Entwickleroptionen im Spiel. Die
*Wiedergabequalitaet* (ABR ↔ HIGH) ist korrekt auf ABR zurueck. Die
*Codec-Auswahl* steht aber weiterhin auf „LDAC fest gewaehlt"
(`Priority: 1000000`) statt auf der System-Automatik (`5001`).

**Auswirkung auf die Messung: keine.** Der Codec war in allen Armen LDAC,
und ABR regelt normal. Arm A' ist gueltig.

**Auswirkung auf den Geraetezustand: der Kopfhoerer laeuft bis auf
Weiteres mit fest erzwungenem LDAC**, statt dass das System bei jeder
Verbindung neu aushandelt. Das faellt nicht auf, bis ein Geraet
verbunden wird, fuer das die Automatik etwas anderes gewaehlt haette.

**Zu tun (App Designer):** In den Entwickleroptionen unter „Bluetooth
Audio Codec" von „LDAC" zurueck auf **„System-Auswahl verwenden"**
(Standard). Ich fasse es auftragsgemaess nicht an. Erst danach ist der
Ausgangszustand vollstaendig.

---

## 7. E-1 und E-3 — weiterhin ohne Hebel

**E-1:** `cmd appops set com.google.android.gms BLUETOOTH_SCAN ignore` lief
ohne Fehler durch und **blieb wirkungslos** — Read-back weiter `allow`, alle
drei Scans mit durchlaufenden `Elapsed`-Zaehlern (1 306 850 → 1 315 595 ms).
Belegt: `appops` greift gegen GMS nicht. Weitere Varianten auf Werkzeugebene
abgelehnt bzw. vom Director nicht freigegeben.

**E-3:** Kein Shell-Hebel. `cmd audio` kennt kein Spatializer-Kommando,
`spatial_audio_enabled` ist in allen drei Namespaces `null`. Einziger
Ansatzpunkt bleibt `secure.audio_device_inventory` — bewusst nicht
freigegeben.

**Kontrolle:** Die drei BLE-Scans liefen waehrend **aller** Arme unveraendert
(`Ongoing 3 scans`). E-2 wurde also durchgehend **mit** aktiven Scans
gemessen.

---

## 7b. Vierte Zelle — **ungueltig als Scan-Experiment (konfundiert)**

**Arm-Name: `E2_B_reduced`.** Der Arm wurde gefahren, ist sauber gemessen —
und ist als Antwort auf die Scan-Frage **nicht verwertbar**, weil waehrend
seiner Vorbereitung eine **zweite Variable** veraendert wurde.

### 7b.1 Der Konfundierer: WLAN wurde eingeschaltet

Nach den Laeufen ergab der Abgleich gegen das Zustandsbuch:

```
global.wifi_on = 1   (soll 0)   ABWEICHUNG
```

Das Wi-Fi-Ereignisprotokoll datiert es exakt:

| Geraetezeit | Ereignis |
|---|---|
| 22:34:23 – 22:34:44 | mehrfaches `WIFI_ENABLED` / `WIFI_DISABLED` |
| **22:34:53.031** | **`WIFI_ENABLED`** (endgueltig) |
| 22:34:53.498 | `CMD_START_CONNECT` |
| 22:34:54.103 | `NETWORK_CONNECTION_EVENT` |
| 22:34:54.812 | `NETWORK_AGENT_VALID_NETWORK` — verbunden |

Gegenueberstellung mit meinen Armen:

| Arm | Geraetezeit | WLAN |
|---|---|---|
| A0 | 21:54:04–21:55:44 | **aus** (Zustandsbuch 21:53: `wifi_on=0`) |
| B / B schnell | 22:07:28–22:10:20 | **aus** |
| A' | 22:22:10–22:23:50 | **aus** (Endzustand 22:24:50: `wifi_on=0`) |
| *WLAN geht an* | **22:34:53** | — |
| **E2_B_reduced** | **22:40:14–22:41:46** | **AN, verbunden** |
| **E2_B_reduced schnell** | **22:41:58–22:42:57** | **AN, verbunden** |

**WLAN war fuer die gesamte vierte Zelle an**, rund 5,3 Minuten vor dem
ersten Sample. Alle Vergleichsarme liefen ohne WLAN.

**Und es ist ausgerechnet der Funkzustand, den T-007 als Stoerhypothese
benannt hatte:** Wi-Fi 7 (`11be`), verbunden, RSSI −59, aktiver Link auf
**5 GHz Kanal 44** — mit einem **affiliierten MLO-Link auf 2,4 GHz Kanal 6**
(derzeit `MLO_LINK_STATE_UNASSOCIATED`, RSSI −51). Genau die Konstellation
aus der frueheren Beobachtung. Ob der 2,4-GHz-Link waehrend der Arme Verkehr
getragen hat, ist rueckwirkend **nicht** feststellbar (*cannot check*).

### 7b.2 Was das bedeutet

Die Methodikregel aus T-008 — **eine Variable je Experiment** — ist verletzt.
Zwischen `E2_B` und `E2_B_reduced` wurden **zwei** Dinge geaendert:

1. ein Scanner und ein Advertiser abgeschaltet (beabsichtigt),
2. **WLAN eingeschaltet und assoziiert** (unbeabsichtigt, unbemerkt).

Die beiden wirken auf dieselbe Zielgroesse und **in entgegengesetzte
Richtung**. Ein Nullbefund ist damit nicht interpretierbar: er kann
bedeuten, dass die Scans nichts bringen — oder dass ihr Wegfall durch den
neuen 2,4-GHz-Konkurrenten aufgewogen wurde.

**Mein Verfahrensfehler, ausdruecklich:** Mein Read-back vor der vierten
Zelle prueft Pin und Scan-Lage, aber **nicht das uebrige Zustandsbuch**.
Haette ich `wifi_on` mitgelesen, waere der Konfundierer vor dem Lauf
aufgefallen. Fuer kuenftige Arme gilt: **Read-back deckt das vollstaendige
Zustandsbuch ab, nicht nur die manipulierte Variable.**

### 7b.3 Die Messwerte — vollstaendig, aber ohne Urteil

Die Arme selbst sind sauber: Pin hielt (990 in allen 230 Samples,
`quality mode HIGH`, ABR-Felder abwesend), und die Scan-Lage war vor, zwischen
und nach den Laeufen unveraendert (`Ongoing 2 scans`, `advertising: 1`) — es
gab keine Kontamination *innerhalb* der Messung.

Es liefen weiter: `nearby_fast_pair` (BALANCED, **ACTIVE**) und
`nearby_connections` (AMBIENT_DISCOVERY, **ACTIVE**). Abgeschaltet wurde
`nearby_sharing` — der Scanner, der **0 Ergebnisse** meldete, waehrend die
beiden verbliebenen den gesamten Ergebnisverkehr tragen (100 und 39).

| Arm | Scanner | WLAN | Kadenz | Dauer | **Drops/min** | **Dropouts/min** | Queue ≠ 0 |
|---|---|---|---|---|---|---|---|
| `E2_B` | 3 | **aus** | 1407 ms | 97,0 s | **324,7** | **12,99** | 79 % |
| `E2_B_fast` | 3 | **aus** | 379 ms | 60,3 s | **323,4** | **12,94** | 81 % |
| `E2_B_reduced` | 2 | **an** | 1306 ms | 90,1 s | **283,7** | **11,32** | 84 % |
| `E2_B_reduced_fast` | 2 | **an** | 366 ms | 58,2 s | **337,1** | **13,40** | 78 % |

**Urteil: `INCONCLUSIVE` — und zwar aus zwei unabhaengigen Gruenden.**

*Erstens, der Konfundierer* (7b.1) macht jedes Urteil ueber die Scans
unzulaessig.

*Zweitens* traegt die Statistik ohnehin kein Urteil, selbst wenn man den
Konfundierer ignorierte:

1. **Die beiden Paarvergleiche zeigen in entgegengesetzte Richtungen.**
   Standardkadenz: 324,7 → 283,7 = **−12,6 %**. Schnelle Kadenz:
   323,4 → 337,1 = **+4,2 %**. Ein realer Effekt kehrt seine Richtung nicht
   um, wenn man nur die Abtastrate wechselt.
2. **Die Streuung innerhalb der 2-Scanner-Bedingung uebersteigt den
   Unterschied zwischen den Bedingungen.** Innerhalb: 283,7 vs. 337,1 =
   **17,2 %**. Zwischen den Mitteln: 324,1 vs. 310,4 = **−4,2 %**.
3. **Zum Vergleich die Praezision der 3-Scanner-Bedingung: 0,4 %** (324,7 vs.
   323,4). Dass dieselbe Methodik jetzt 17 % streut, ist selbst ein Hinweis —
   **passend dazu, dass in dieser Bedingung ein neuer Funkkonkurrent
   dazugekommen ist.**
4. **Die Sendeschlange bleibt gleich belastet:** mittlere Tiefe 7,64 gegen
   7,69. Der Anteil gefuellter Samples widerspricht sich (84 % vs. 78 %).
5. **Die Ereignisabstaende werden kuerzer, nicht laenger:** Mittel 3,93 s
   gegen 4,90 s, Median 4,13 gegen 4,69 s — tendenziell *schlechter*.

### 7b.4 Was trotzdem verwertbar ist

Ein Befund ueberlebt den Konfundierer, weil er nicht auf dem Vergleich
beruht:

**Bei gepinnten 990 kbps liegt die Verlustrate in allen vier Armen in
derselben Groessenordnung — ~284 bis ~337 Drops/min und ~11 bis ~13
Dropouts/min — quer ueber zwei Scanner-Konfigurationen und beide
WLAN-Zustaende.** Weder das Abschalten eines Scanners noch das Zuschalten
von WLAN hat die Groessenordnung verschoben.

Das staerkt *relativ* die Hypothese **„990 kbps ist fuer diese Strecke
schlicht zu schnell"** gegenueber „eine bestimmte Funkstoerung ist schuld".
*Evidenzniveau: plausibel*, nicht belegt — vier Arme, zwei davon
konfundiert, und die stoerungsfreie Vergleichsbedingung (990 ohne Scans,
ohne WLAN) fehlt weiterhin vollstaendig.

### 7b.5 Wie der Arm sauber nachzuholen ist

1. **WLAN wieder aus** (`wifi_on = 0`) — Ausgangszustand aller frueheren Arme.
2. Alle drei Nearby-Scanner abschalten. Die fehlenden zwei Schalter liegen
   nicht in den Bluetooth-Einstellungen: **Fast Pair** unter *Google →
   Geraete & Freigabe → Geraete in der Naehe*, **Mein Geraet finden** unter
   *Google → Alle Dienste → Mein Geraet finden*.
3. Read-back gegen das **vollstaendige** Zustandsbuch, nicht nur gegen Pin
   und Scan-Lage.
4. Dann zwei Laeufe wie gehabt. Die Skripte stehen bereit; ein Nachholen
   kostet rund vier Minuten.

## 8. Was offen bleibt

| Frage | Warum offen |
|---|---|
| **Warum laeuft die Queue ueber?** | Mechanismus belegt, **Ursache nicht**: Funkstoerung durch die BLE-Scans, Empfangsgrenze des Kopfhoerers oder schlicht zu hohe Rate fuer diese Strecke sind nicht getrennt. Braucht E-1 und einen RSSI-Zugang, den das Geraet nicht hergibt. |
| **Traegt 990 ohne die BLE-Scans?** | **Weiterhin offen.** Die vierte Zelle (7b) ist **konfundiert** — WLAN ging um 22:34:53 unbemerkt an und lief waehrend beider Arme, waehrend alle Vergleichsarme ohne WLAN liefen. Zusaetzlich war die Reduktion nur teilweise. Ergebnis `INCONCLUSIVE`, Nachholen noetig (7b.5). |
| **Trug der 2,4-GHz-MLO-Link waehrend der vierten Zelle Verkehr?** | **cannot check** — rueckwirkend nicht feststellbar. Beim Nachmessen stand der 2,4-GHz-Link auf `UNASSOCIATED`, der aktive Link auf 5 GHz. |
| **Wo sitzen Fast Pair und „Mein Geraet finden"?** | Nicht ermittelt. Ohne diese beiden Schalter ist E-1 nicht sauber fahrbar. |
| **Wo genau liegt die Hoerbarkeitsschwelle?** | Zwei Punkte (0 und 13/min), keine Kurve. Der Bereich dazwischen ist ungemessen. |
| **Echter ~3-s-Takt** | INCONCLUSIVE, s. Abschnitt 4. |
| **Langzeitrate 400/min** | Unkontrolliert, s. 5.1. Kein Urteil. |
| **7 Underflows zwischen den Armen** | 529 → 536; beide B-Arme zeigen dUF = 0. Fielen in die Handumschaltung. Nicht zuzuordnen. |
| **Nachwirkung der Ueberlast** | Beantwortet: keine dauerhafte. Der Einschwingvorgang dauerte 18,5 s. |

---

## 9. Empfehlungen

1. **Pin-Marker zuruecksetzen** (Abschnitt 6) — App Designer, offener Punkt.
2. **`UI_SPEC.md` fortschreiben:** Der Kalibrierpunkt aus Abschnitt 0 gehoert
   in „Die Hoerbarkeitsgrenze". Und die Anzeige muss
   **`Counts (dropped/dropouts)`** lesen, **nicht** `underflow` — der hoerbar
   gestoerte Arm hatte 0 Underflows. Das betrifft T-002 direkt.
3. **Vierte Zelle nachholen** — sie ist konfundiert (7b) und traegt kein
   Urteil. Vorgehen in 7b.5: **WLAN wieder aus**, alle drei Scanner ab,
   Read-back gegen das vollstaendige Zustandsbuch. Kostet rund vier Minuten.
4. **Read-back-Regel verschaerfen:** kuenftig gegen das **komplette**
   Zustandsbuch pruefen, nicht nur gegen die manipulierte Variable. Der
   Konfundierer waere sonst wieder unbemerkt geblieben.
4. **`baselines.md`:** Der 990er-Arm ist ein neues Szenario mit eigenem
   Abschnitt — nicht in die ABR-Tabelle mischen. Noch nicht eingetragen,
   weil E-2 erst mit der vierten Zelle abgeschlossen waere; auf Signal
   ergaenze ich ihn.

---

## 10. Zustandsfeststellung

**Von mir wurde am Geraet nichts veraendert.** Der einzige eigene
Schreibversuch (`appops set`) blieb nachweislich wirkungslos.

Endzustand um 22:24:50 Geraetezeit, alle Werte per Read-back geprueft:

| Wert | Soll | Ist | |
|---|---|---|---|
| `LDAC quality mode` | ABR | **ABR** | OK |
| ABR-Regelung aktiv | ja | idx 3, adj 37, 492 kbps | OK |
| **`Priority` (LDAC)** | **5001** | **1000000** | **ABWEICHUNG, s. 6** |
| `Config` | `Rate=96000 Bits=32 Mode=STEREO` | identisch | OK |
| `appops … BLUETOOTH_SCAN` | allow | allow | OK |
| `global ble_scan_always_enabled` | 1 | 1 | OK |
| `global wifi_scan_always_enabled` | 1 | 1 | OK |
| `global wifi_on` | 0 | 0 | OK |
| `global low_power` | 0 | 0 | OK |
| `secure location_mode` | 3 | 3 | OK |
| `global bluetooth_on` | 1 | 1 | OK |
| `secure audio_device_inventory` | 17 Eintraege | **zeichengleich** | OK |
| BLE-Scans | 3 laufend | 3 laufend | OK |

**Achtung — der Zustand hat sich nach Abschnitt 10 erneut geaendert:** Fuer
die vierte Zelle (7b) hat der App Designer **990 erneut gepinnt** und
**Bluetooth-Suche sowie Quick Share abgeschaltet**. Beim letzten Read-back
(22:45:11 Geraetezeit) standen daher: `LDAC quality mode: HIGH`,
990 kbps, `Priority: 1000000`, `Ongoing 2 scans`, `advertising: 1` — und
**`wifi_on = 1`**, waehrend das Zustandsbuch `0` fordert.
**Diese vier Punkte — Quality Mode, Codec-Auswahl-Marker, die zwei
Scan-Schalter und WLAN — liegen beim App Designer und sind noch
zurueckzustellen.** Alle uebrigen Werte des Zustandsbuchs sind unveraendert
(`audio_device_inventory` zeichengleich, appops `allow`,
`ble_scan_always_enabled` 1, `wifi_scan_always_enabled` 1, `low_power` 0,
`location_mode` 3, `bluetooth_on` 1).
Von mir wurde weiterhin nichts veraendert.

Der Stand unten beschreibt das Ende von E-2 (Abschnitt 3), nicht das Ende
der vierten Zelle.

**Zum Zeitpunkt von Abschnitt 10 war das Geraet bis auf den
Codec-Auswahl-Marker im Ausgangszustand.** Die
eine Abweichung ist in Abschnitt 6 als Sofortbefund dokumentiert und liegt
beim App Designer. Die App ist weiterhin deinstalliert, der AK-1-Referenzarm
unversehrt.

---

## 11. Rohdaten

Nur im Scratchpad, nicht im Repo:
`C:\Users\Daniel\AppData\Local\Temp\claude\C--Users-Daniel-Desktop-ClaudeCode\4712ed34-df5d-47e2-9f5b-48a5579d4f68\scratchpad\`

| Datei | Inhalt |
|---|---|
| `T008_zustandsbuch.txt` | Ist-Zustand vor der ersten Aenderung |
| `T008_endzustand.txt` | Endzustand nach A' mit Soll-Ist-Abgleich |
| `T008_phasen.log` | Start-/Endzeiten aller Arme (Host und Geraet) |
| `ts_A0.txt` | Arm A0, ABR-Referenz, 70 Samples |
| `ts_E2_B.txt` | Arm B, 990 gepinnt, Standardkadenz, 70 Samples |
| `ts_E2_B_fast.txt` | Arm B, 990 gepinnt, 379 ms, 160 Samples |
| `ts_A_prime.txt` | Arm A', ABR zurueck, 70 Samples |
| `raw_bt_E2_B.txt` | voller `bluetooth_manager`-Dump im gepinnten Zustand |
| `ts_E2_B_reduced.txt` | vierte Zelle, 990 + 2 Scans, Standardkadenz, 70 Samples |
| `ts_E2_B_reduced_fast.txt` | vierte Zelle, 990 + 2 Scans, 366 ms, 160 Samples |
| `raw_bt_cell4_pre.txt` | voller Dump vor der vierten Zelle (Scan-Lage) |
| `raw_wifi_cell4.txt` | `dumpsys wifi` mit dem `WIFI_ENABLED`-Zeitstempel |
| `T008_endzustand_zelle4.txt` | Endzustand nach der vierten Zelle |
| `run_arm.sh` | Messlaeufer, feste Kadenz |
| `analyze_arm.py` | Auswerter (Stufen, Wechsel, Verluste, Queue) |
| `periodicity.py` | Ereignisabstaende und Autokorrelation |
