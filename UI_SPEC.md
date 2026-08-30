# UI_SPEC — DankYeeter's Bluetooth Dashboard

Verbindliche UI/UX-Vorgaben, gegen die der `developer` baut und gegen die der
`ui-ux-designer` spaeter prueft. Ein Abschnitt je Auftrag, mit Datum.
Bestehende Abschnitte werden nie ueberschrieben, nur mit Datum korrigiert.

Sprachregel des Projekts gilt fuer alle Strings hier: **Englisch**, Erklaertexte
maximal zwei Saetze, keine Rueckkehr zu langen Absaetzen.

---

## Verlust- und Stoerungsanzeige (T-002) — 2026-08-30

### Zweck & Nutzerziel

Ein Blick auf das Live-Panel beantwortet: **"Stimmt mit meinem Ton gerade etwas
nicht — und wenn ja, wo?"** Nicht: "wie viele Zaehlerereignisse hat dieses
Telefon seit dem letzten Bluetooth-Neustart gesammelt".

Ausloeser (App Designer, 2026-08-30): der Verlustwert liest sich als eine immer
weiter steigende Nummer, sieht bei tadelloser Verbindung nach Schaden aus und
bietet keinen Mehrwert. Das ist ein Verstoss gegen `GOAL.md` AK-3: ein
Fehlalarm ist genauso unehrlich wie ein falscher Freispruch.

### Was der Code heute wirklich tut (nachgeprueft, nicht geglaubt)

Geprueft am Quelltext auf HEAD `babe3d8`; der installierte Build (28.08. 18:25)
liegt hinter `672ec9a` (28.08. 17:56) und damit **funktional auf demselben
Stand** wie das hier Beschriebene. Kein Geraetezugriff (Geraet durch T-001
belegt), alle Befunde daher **unverifiziert (Code-Analyse)**.

1. **Die Datenschicht rechnet bereits Deltas.** `A2dpTxDelta`
   (`LinkLiveModels.kt:528`) traegt `windowMs`, `dropped`, `dropouts`,
   `underflows`, `flushed`; gebaut in `LiveLinkSource.txDelta()` (Zeile 366) und
   `A2dpTxProbe` (Zeile ~180). Eine Rate existiert dort schon:
   `underflowsPerSecond`. Die kumulative Zahl ist also tatsaechlich eine
   **Darstellungsentscheidung**. Bestaetigt.
2. **Das Live-Panel zeigt heute schon Fensterwerte, nicht die Rohsummen.**
   `LossRow` (`LiveLinkPanel.kt:304`) liest ausschliesslich Deltas. Die
   "immer weiter steigende Nummer", die der Nutzer sieht, entsteht an drei
   anderen Stellen:
   - **Graph-Caption**: `LiveTrace.lossTotal` (`LiveTraceModel.kt:123`) summiert
     `lossCount` ueber das Fenster und wird als `"$lossTotal loss marks"`
     ausgegeben (`LiveLinkGraph.kt`, `caption()`). Der Text sagt *Marken*, die
     Zahl zaehlt *Ereignisse* — bei 5 Underruns in 3 Fenstern steht dort "5 loss
     marks" bei 3 Strichen. Zusaetzlich waechst der Wert waehrend der ersten
     60 s nach dem Oeffnen monoton, weil sich das Fenster erst fuellt.
   - **Ereignisliste und Timeline**: `LiveLinkSource.lossEvent()` (Zeile 500)
     feuert bei **jedem** Poll mit irgendeinem Delta > 0 — ohne Schwelle, ohne
     Sustain, ohne Cooldown. Jedes davon wird als `MonitorEventType.DROPOUT`
     (`loud = true`) persistiert. Bei 2-s-Kadenz sind das bis zu 30 Zeilen
     "Audio dropout" pro Minute in einer Liste, die zwei Stunden zurueckreicht.
     Das ist die eigentliche wachsende Zahl.
   - **Summierung ungleicher Dinge**: `lossCount` (`LiveTraceModel.kt:190/212`)
     addiert App-Underruns + Mixer-Underruns + dropped packets + stack dropouts
     + encoder underflows zu **einer** Zahl. Fuenf verschiedene Fehler mit
     fuenf verschiedenen Verdaechtigen werden zu einem Betrag, der nur gross
     aussehen, aber nichts erklaeren kann.
3. **Ereignisse, die keine Stoerung sind, zaehlen mit.** `hasLossThisWindow`
   (`LinkLiveModels.kt:684`) filtert nicht nach `device.isPlaying`, nicht nach
   Codec-Wechsel im selben Fenster, nicht nach dem ersten Fenster nach dem
   Verbindungsaufbau. Besonders auffaellig: das Pinnen einer LDAC-Qualitaet
   verhandelt den Codec neu — der Panel-Erklaertext sagt selbst "the audio cuts
   out for a moment" — und erzeugt damit im selben Fenster eine rote Zeile
   "Audio lost" plus einen persistierten DROPOUT. **Die App meldet ihre eigene
   Aktion als Stoerung.** (`flushed` ist bereits korrekt aus `hasLoss`
   ausgenommen; das ist der richtige Praezedenzfall.)
4. **Es gibt keinen ehrlichen Nenner fuer eine Prozentanzeige.**
   `A2dpTxStats.enqueueCount` ist am Geraet als 20-ms-Timer-Tick nachgewiesen,
   nicht als Funkpaket (Doku in `LinkLiveModels.kt`, Falsifikation von
   `framesPerEnqueue`). "X % der Pakete verloren" waere ein erfundener Nenner
   und damit ein AK-3-Verstoss. **Prozent ist ausgeschlossen.**
5. **Ehrlichkeitsluecke im heutigen Aufbau.** Bei ausgelagerter Codierung setzt
   `LiveLinkSource` (Zeile 180) `tx = null`. `LossRow` faellt dann — solange
   irgendeine App einen lesbaren `underrunDelta` hat — in den Zweig
   `"No loss this window."`. Der Hinweis "loss counters do not apply" steht in
   `TxRows` (Zeile 362) und wird **darunter** gerendert, also nach dem
   Freispruch. Ein Nutzer liest die Entlastung zuerst. Das ist genau der
   falsche Freispruch, den AK-3 verbietet.
6. **Was ueberhaupt zaehlt, ist wahrscheinlich nicht die tx-Seite.**
   `docs/perf/baselines.md` misst auf genau diesem Geraet und Kopfhoerer
   (Pixel 11 Pro, Noble Encore, LDAC/ABR) ueber acht 180-s-Laeufe:
   `uflow/min` = 0 in sieben von acht Laeufen, Dropouts durchgehend 0. Die
   Eingangsseite (per-App-Track-Underruns, Mixer-Underruns) ist dort **nie
   gemessen worden**. Wenn Daniel bei subjektiv perfekter Verbindung Verlust
   sieht, bewegt sich mit hoher Wahrscheinlichkeit ein Eingangs-Zaehler, nicht
   der Bluetooth-Stack. Siehe Messanforderung M-1 unten.

---

### Leitgroesse

**Ein Zustand, getragen von einer Rate pro Minute in einem gleitenden Fenster,
je Kanal getrennt.**

Begruendung der Form:

- **Zustand statt Zahl in der ersten Ebene.** Die Frage des Nutzers ist binaer
  ("stimmt was nicht?"). Eine Zahl beantwortet sie nur, wenn er weiss, welche
  Zahl normal ist — das weiss er nicht und soll es nicht muessen.
- **Rate statt Summe.** Eine Summe waechst per Konstruktion und ist damit
  nie ein Urteil ueber *jetzt*. Eine Rate ist ueber Poll-Intervalle hinweg
  vergleichbar; das Projekt argumentiert das bereits selbst bei
  `A2dpTxDelta.underflowsPerSecond` und stuetzt darauf den
  `EncoderStarvationTripwire`.
- **Pro Minute, nicht pro Sekunde.** Die betrachteten Ereignisse sind selten;
  pro Sekunde stehen dort "0,03" — unlesbar und faelschlich beruhigend.
  `docs/perf/baselines.md` rechnet bereits in `/min`; dieselbe Einheit in
  Messung und Anzeige verhindert Uebersetzungsfehler bei der Schwellensetzung.
- **Pro Kanal getrennt, nie summiert.** App-Underrun, Mixer-Underrun,
  dropped packet, stack dropout, encoder underflow sind fuenf verschiedene
  Fehler mit fuenf verschiedenen Verdaechtigen. Eine Summe verdeckt genau die
  Information, wegen der die Anzeige existiert. `LinkEvent.lossParts()` macht
  das bereits richtig und ist das zu uebernehmende Muster.
- **Kein Prozentsatz**, Begruendung siehe Befund 4.
- **Alter statt Rate bei sehr wenigen Ereignissen** — eine Rate aus drei
  Ereignissen taeuscht Praezision vor, die die Stichprobe nicht hergibt. Siehe
  "Rate oder Alter".
- **Nenner ist die tatsaechlich gemessene Zeit, nicht die Wanduhr.** Faellt der
  Poller aus, darf die Rate nicht steigen, weil weniger gemessen wurde.

### Kanaele (die Verlustfamilie, abschliessend)

| Kanal | Quelle | Wortwahl-Stufe |
|---|---|---|
| App underruns | `InputStreamSnapshot.underrunDelta` | "ran dry" |
| Mixer underruns | `fastMixerUnderrunDelta` + `normalMixerEmptyDelta` | "ran dry" |
| Dropped packets | `A2dpTxDelta.dropped` | "dropped" |
| Stack dropouts | `A2dpTxDelta.dropouts` | "dropped" |
| Encoder underflows | `A2dpTxDelta.underflows` | "ran dry" |

**Nicht in der Verlustfamilie**, ausdruecklich:

- `flushed` — bereits heute ausgenommen, bleibt ausgenommen.
- `enqueueOverdue` / `dequeueOverdue` — auf einer kerngesunden Verbindung
  ~2500/min bzw. ~1880/min (`docs/perf/baselines.md`). Das sind
  Scheduling-Abweichungen, kein Verlust; in der Verlustfamilie wuerden sie
  alles andere zudecken.

### Die Hoerbarkeitsgrenze (bindend, verschaerft am 2026-08-30)

Der App Designer hat den "ich habe das gerade gehoert"-Marker abgelehnt
(Entscheidung 2). Damit gibt es in dieser App **keine Bruecke zwischen
Gezaehltem und Gehoertem** — nicht heute und nicht spaeter. Die Konsequenz ist
nicht, die Frage zu streichen, sondern die Anzeige so zu bauen, dass sie die
Behauptung gar nicht erst aufstellen kann.

**Der aktuelle Anlass macht das dringend:** der App Designer hoert bei gepinnt
660+ anhaltende Stoerungen, waehrend die tx-Zaehler laut
`docs/perf/baselines.md` fast durchgehend null zeigen. Wenn die Anzeige daraus
"clean" macht, ist das exakt der falsche Freispruch aus `GOAL.md` AK-3 — nur
diesmal gegen ein Ohr, das recht hat.

Drei Regeln verhindern das:

- **R-A Subjekt jedes Satzes ist der Zaehler, nie das Audio.** "Nothing lost"
  und "audio lost" sind Aussagen ueber den Klang und damit unbelegbar. Erlaubt
  ist nur die Aussage darueber, was sich gezaehlt hat: "No counter moved",
  "{N} encoder underflows". Ein "dropped"-Kanal darf "dropped" heissen, weil
  der Stack das Audio nachweislich selbst weggeworfen hat; ein "ran dry"-Kanal
  darf nie so heissen, weil der Puffer des Kopfhoerers ihn absorbiert haben
  kann und das auf dem Telefon nicht lesbar ist.
- **R-B Der ruhige Zustand nennt seine eigene Decke.** `CLEAN` heisst nie "die
  Verbindung ist in Ordnung", sondern "keiner der fuenf Zaehler hat sich
  bewegt". Die zweite Ebene fuehrt dauerhaft auf, **was die fuenf Zaehler nicht
  sehen koennen**: Funkstoerungen und Retransmissions (kein BQR ohne
  privilegierten Zugriff), alles jenseits der Antenne — Decoder, Puffer und
  Wandler des Kopfhoerers — sowie jede Verzerrung, die keinen Puffer leerlaufen
  laesst. Damit ist "ruhig" nie als Gesamturteil lesbar.
- **R-C Kein Zustand darf ein Ohr widerlegen.** Es gibt weder Text noch
  Bildzeichen, das dem Nutzer sagt oder nahelegt, dass er nichts gehoert haben
  kann. Verbotene Formen ausdruecklich: gruene Haken, "all good", "healthy",
  "no problems", "everything fine", Daumen-hoch, sowie jede Formulierung im
  Perfekt ueber den Klang.

Die heutigen Formulierungen "Audio lost: …" (`LiveLinkPanel.kt`, `LossRow`) und
"No loss this window." fallen damit beide weg — die zweite, weil sie den Klang
zum Subjekt macht.

---

### Zustaende (genau fuenf, immer genau einer sichtbar)
Zwei orthogonale Groessen, aus denen der Zustand folgt:

- **Coverage** ∈ `ALL_FIVE` | `PARTIAL(blinde Kanaele)` | `NONE`
- **Verdikt** ∈ `CLEAN` | `OCCASIONAL` | `DISTURBED`, nur gebildet aus den
  Kanaelen, die tatsaechlich gelesen wurden.

`ALL_FIVE` heisst ausdruecklich **"alle fuenf Zaehler gelesen"**, nicht "der
ganze Pfad beobachtet". Der hoechste erreichbare Zustand dieser Anzeige ist
"kein Zaehler hat sich bewegt", und das ist weniger als "der Ton war gut"
(Regel R-B). Der Name des Zustands traegt diese Decke, damit sie im Code nicht
verlorengeht.

Regel: `Coverage = NONE` ⟹ Zustand ist immer `CANNOT_TELL`. Ein Verdikt wird
**niemals** ueber einen Kanal ausgesprochen, der nicht gelesen wurde.

| # | Zustand | Bedingung | Erste Ebene |
|---|---|---|---|
| 1 | `SETTLING` | Fenster liegt in einer Umschaltspanne (siehe "Umschaltketten") | Pill `Settling` (NEUTRAL) + "The link just changed — counters are not comparable yet." |
| 2 | `MEASURING` | weniger als zwei vergleichbare Lesungen | kein Pill + "Loss needs two readings." |
| 3 | `CANNOT_TELL` | Coverage `NONE` (offloaded / kein Helper / kein Link / Zaehler zurueckgesetzt / Umschaltspanne ueber `SETTLE_MAX_SPAN_MS`) | Pill `Can't tell` (NEUTRAL) + der Grund, wortgleich aus der Datenschicht |
| 4 | `CLEAN` / `PARTIAL` | alle gelesenen Kanaele unter `LOSS_NOTICE_RATE_PER_MIN` | **kein Pill** bei `ALL_FIVE`; Pill `Partial view` (NEUTRAL) bei `PARTIAL` + Nennung des blinden Kanals |
| 5 | `OCCASIONAL` / `DISTURBED` | mindestens ein Kanal ueber Schwelle | Pill `Occasional` bzw. `Disturbed` (WARN) + Satz mit Kanal, Anzahl im Fenster und Rate oder Alter |

**Die Kernunterscheidung "nichts los" vs. "kann es nicht sagen" ist
strukturell, nicht farblich gefuehrt**: `CLEAN` traegt gar keinen Pill (die
Panel-Doku begruendet das bereits: ein gruener Haken trainiert das Auge, die
Zeile zu ueberspringen), `CANNOT_TELL` traegt einen. Presence-vs-absence ist
farbfehlsichten-sicher und ueberlebt einen Screenshot in Graustufen. Kein
neues Design-Token noetig — `Pill`/`PillTone` (`Panel.kt:154`) reichen.

`OCCASIONAL` und `DISTURBED` teilen sich `PillTone.WARN`; unterschieden werden
sie durch das Wort und dadurch, dass nur `DISTURBED` den Fliesstext in
`colorScheme.error` setzt. Auch das ist ohne Farbe lesbar.

**Die Zeile bleibt in jedem Zustand sichtbar** (Entscheidung 1). Eine Zeile,
die im ruhigen Zustand verschwindet, ist von "die App misst gerade nicht" nicht
zu unterscheiden — und genau diese Verwechslung ist der Kern von AK-3.
Anwesenheit ohne Alarm ist die ehrlichere Aussage.

### Rate oder Alter — wann welche Zahl (Entscheidung 3)

Eine Rate aus sehr wenigen Ereignissen taeuscht Praezision vor, die die
Stichprobe nicht hergibt: bei `k` gezaehlten Ereignissen liegt der reine
Zaehlfehler bei `1/sqrt(k)`, also bei k = 3 schon ueber 57 %. Deshalb:

- `k >= RATE_MIN_EVENTS_IN_WINDOW` ⟹ **Rate**, "about {R}/min",
  **ohne Nachkommastelle**. Eine Nachkommastelle behauptet eine zweite
  signifikante Ziffer, die selbst bei k = 10 noch Rauschen ist (32 %).
- `1 <= k < RATE_MIN_EVENTS_IN_WINDOW` ⟹ **Alter statt Rate**:
  "last counted {D} ago". Zahl und Bezug sind damit beide belastbar.
- `k = 0` ⟹ der ruhige Zustand, siehe Formulierungen.

**Das Alter gilt nur ueber gemessene Zeit.** War die Spanne seit dem letzten
Ereignis nicht luecklos gemessen — Panel war zu, Poller stand, Zaehler wurde
zurueckgesetzt — darf "{D} ago" nicht behauptet werden. Dann gilt:
"nothing counted in the {M} this panel has measured". `{M}` ist die Summe der
gemessenen Fenster, nicht die Wanduhrzeit.

### Formulierungen erste Ebene (verbindlich, Englisch)

Subjekt ist immer der Zaehler, nie das Audio (Regel R-A).

- `SETTLING` — "The link just changed — counters are not comparable yet."
- `SETTLING`, Kette — "The link changed {N} times just now — counters are not
  comparable yet."
- `MEASURING` — "Loss needs two readings." (bestehender String, bleibt)
- `CANNOT_TELL`, offloaded — "{Codec} is encoded by the controller — nothing on
  this phone counts its loss."
- `CANNOT_TELL`, kein Helper — "The helper is not running, so the loss counters
  cannot be read."
- `CANNOT_TELL`, kein Link — "No link — nothing to count."
- `CANNOT_TELL`, Dauerumschaltung — "The link keeps changing — nothing here is
  comparable."
- `CLEAN` (`ALL_FIVE`) — "No counter moved in the last {W}."
- `CLEAN` ohne jedes Ereignis seit Sitzungsbeginn — "No counter has moved in
  the {M} this panel has measured."
- `PARTIAL` — "No app or mixer counter moved in the last {W}; the Bluetooth
  stack cannot be read on this link."
- `OCCASIONAL`, viele Ereignisse — "{N} {kanal} in the last {W} — about
  {R}/min."
- `OCCASIONAL`, wenige Ereignisse — "{N} {kanal} in the last {W} — last counted
  {D} ago."
- `DISTURBED` — "{Kanal} for {D} now — about {R}/min."
- `DISTURBED`, Zusatzzeile (genau hier und nirgends sonst) — "Watch closely
  reads the stack twice a second while it is on." Ein **Hinweis**, kein
  Selbstschalter: die Nahaufnahme bleibt eine Nutzeraktion (Entscheidung 4).

`{W}` Fenster, `{R}` Rate pro Minute ohne Nachkommastelle, `{D}` Dauer bzw.
Alter, `{M}` gemessene Zeit, `{N}` Anzahl.

Verboten in allen Zustaenden (Regel R-C): "audio lost", "no loss", "nothing
lost", "audible", "you heard", "all good", "healthy", "everything fine",
gruene Haken, Daumen-Symbole.

### Zweite Ebene (hinter dem Fragezeichen / aufklappbar)

Traegt das Detail, das nach `GOAL.md` AK-2 nicht verschwinden darf — als
Zeilen, nicht als Prosa, damit kein Text zurueckwaechst:

1. **Je Kanal eine Zeile**: Name, Anzahl im Fenster, Rate/min oder Alter, und ob
   der Kanal in diesem Fenster ueberhaupt lesbar war ("not readable" statt "0").
2. **Sitzungswert mit Bezugsrahmen**: "Since this panel started watching {T}:
   {N}" — je Kanal. Der Zaehler startet bei der ersten erfolgreichen Lesung des
   Panels, **nicht** beim Bluetooth-Stack.
3. **Die Rohsummen des Stacks, mit ihrer echten Epoche**: "Bluetooth stack
   totals since the stack started, which the app cannot reset: dropped {x},
   dropouts {y}, underflows {z}." Damit zieht die kumulative Zahl um statt zu
   verschwinden, und sie traegt endlich ihren Bezugsrahmen.
4. **Die Bitratenstufe des Fensters**, siehe "Bitratenbezug" unten.
5. **Die Decke, dauerhaft und in jedem Zustand** (Regel R-B): "These five
   counters do not see the radio itself, and nothing past the antenna — the
   headphone's own decoder and buffer are not readable from here."

Erklaertext des Blocks, maximal zwei Saetze:
"Loss is counted per channel and stated as a rate, because a running total
grows forever and says nothing about now. Where a channel could not be read the
row says so rather than showing a zero."

---

### Parameter (Form begruendet, Werte bis auf einen offen)

Alle Schwellen sind **pro Kanal** definiert, nicht global: die natuerlichen
Ruheraten der fuenf Kanaele sind nachweislich verschieden (tx-Seite 0/min laut
`docs/perf/baselines.md`, Eingangsseite unbekannt). Eine gemeinsame Schwelle
waere zwangslaeufig fuer den lautesten Kanal kalibriert.

| Parameter | Form und Begruendung | Wert |
|---|---|---|
| `LOSS_WINDOW_MS` | Gleitendes Fenster der Rate. Muss lang genug sein, dass ein Einzelereignis die Rate nicht dominiert, und kurz genug, dass sie noch "jetzt" beschreibt. Sollte mit `LiveTrace.OVERVIEW_WINDOW_MS` (60 s) uebereinstimmen, damit Zeile und Graph dasselbe Fenster meinen. | **offen — T-001** (Vorschlag 60 s aus Deckungsgleichheit mit dem Graphen, keine Messung) |
| `LOSS_NOTICE_RATE_PER_MIN[kanal]` | Ab hier wird ueberhaupt etwas gesagt. Muss ueber der gemessenen Ruherate gesunder LDAC-Wiedergabe liegen, mit Abstand, nicht knapp darueber. | **offen — T-001** |
| `LOSS_ALERT_RATE_PER_MIN[kanal]` | Ab hier `DISTURBED`. Muss strikt ueber `NOTICE` liegen und in der Luecke zwischen "gesund" und "kaputt" sitzen, nicht am Rand — dieselbe Begruendung, die `EncoderStarvationTripwire.TRIP_RATE_PER_SECOND` traegt. | **offen — T-001** |
| `LOSS_ALERT_SUSTAINED_WINDOWS` | Anzahl aufeinanderfolgender Fenster ueber `ALERT`, bevor alarmiert wird. Muss >= 2 sein, sonst wird jeder Trackwechsel zum Alarm. Vorbild: `SUSTAINED_PASSES = 3`. | **offen — T-001** |
| `LOSS_CLEAR_RATE_PER_MIN[kanal]` | Hysterese. Muss **strikt unter** `NOTICE` liegen, sonst flattert die Anzeige an der Schwelle. | **offen — T-001** |
| `LOSS_CLEAR_HOLD_MS` | Mindestdauer, die ein Zustand gehalten wird, bevor er zurueckfallen darf. Gegen Flattern; muss > `LOSS_WINDOW_MS / 2` sein, damit ein einzelnes ruhiges Fenster den Zustand nicht kippt. | **offen — T-001** |
| `SETTLE_AFTER_TRANSITION_MS` | Karenz nach einem Uebergang. Muss mindestens die Dauer einer Codec-Neuverhandlung abdecken. Wird bei jedem neuen Uebergang **neu gestartet**, nicht aneinandergereiht — siehe "Umschaltketten". | **offen — T-001** (bitte die Neuverhandlungsdauer mitmessen, M-3) |
| `SETTLE_MAX_SPAN_MS` | Obergrenze einer zusammenhaengenden Umschaltspanne. Ohne sie koennte ein haengendes `busy`-Flag oder ein Dauer-Umschalter die Anzeige unbegrenzt blind halten und "ruhig" aussehen lassen. Muss ein Vielfaches von `SETTLE_AFTER_TRANSITION_MS` sein. Nach Ablauf faellt der Zustand auf `CANNOT_TELL`, **nie** auf `CLEAN`. | **offen — T-001** |
| `LOSS_EVENT_COOLDOWN_MS` | Mindestabstand zwischen zwei Log-Eintraegen derselben laufenden Episode. Vorbild und Begruendung: `CAPTURE_COOLDOWN_MS = 10 min` — eine lange Episode darf nicht neunzig Zeilen schreiben. | **offen — T-001** |
| `RATE_MIN_EVENTS_IN_WINDOW` | Ab wie vielen gezaehlten Ereignissen im Fenster eine Rate statt eines Alters gezeigt wird (Entscheidung 3). Der Zaehlfehler einer Ereigniszahl liegt bei `1/sqrt(k)`; bei k = 3 sind das 58 %, bei k = 10 noch 32 %. Unterhalb der Grenze ist selbst die erste Ziffer der Rate nicht belastbar, also wird gar keine gezeigt. | **10, festgelegt** — folgt aus der Zaehlstatistik, nicht aus einer Geraetemessung, und ist deshalb nicht offen |

**Regel fuer den `developer`:** Die Parameter werden als benannte Konstanten mit
KDoc angelegt, das die Begruendung ihrer *Form* traegt, und mit einem
`TODO(T-001)`-Vermerk, solange der Wert nicht gesetzt ist. Kein Wert wird
geraten und keiner wird stillschweigend gesetzt.

---

### Graph (60 s) und Nahaufnahme (10 s)

**Dieselbe Leitgroesse fuer den Zustand, eine andere fuer die Linie.** Die
Linie bleibt die gemessene Bitrate (bzw. der Liveness-Fallback) — sie
beantwortet eine andere Frage (Durchsatz) und ist bereits richtig geloest.
Verlust bleibt Annotation auf derselben Achse.

Aenderungen:

1. **Caption**: `"$lossTotal loss marks"` wird zu **"{k} of {n} windows lost
   something"** — `k` = Anzahl Marken, `n` = Anzahl **gemessener** Fenster.
   Beschraenkt, selbstnormierend, und die heutige Verwechslung von
   Ereignisanzahl und Markenanzahl ist damit weg.
2. **Nicht gemessene Fenster werden genannt**: "{m} not measured", wenn `m > 0`.
   Fehlende Fenster duerfen nicht stillschweigend als "war ruhig" durchgehen.
3. **`SETTLING`-Fenster bekommen eine eigene Marke**: gleiche Geometrie wie die
   Verlustmarke, aber in `colorScheme.outline` statt `error`. Ein
   Codec-Wechsel wird damit sichtbar, ohne als Verlust gezaehlt zu werden, und
   verschwindet nicht unsichtbar.
4. **Nahaufnahme**: Coverage ist konstruktionsbedingt immer `PARTIAL` (nur
   Stack). Der ruhige Text bleibt kanal-qualifiziert und zaehler-subjektig:
   "no stack counter moved in this window".
5. **Die Nahaufnahme schaltet sich nie selbst ein** (Entscheidung 4). Sie
   kostet einen 233-ms-Dump alle 500 ms und wuerde damit ausgerechnet dann
   teurer, wenn die Verbindung ohnehin leidet — das verstoesst gegen
   `GOAL.md` AK-1 (Nicht-Einmischung) und AK-4 (Hintergrund kostet nichts) und
   koennte die Stoerung verstaerken, die es zeigen soll. Erlaubt ist genau
   **ein Hinweis** im Zustand `DISTURBED`, der die Kosten nennt und den
   bestehenden "Watch closely"-Chip meint; er erscheint in keinem anderen
   Zustand und schaltet nichts.

### Umschaltketten (Nachtrag 7, akut wegen T-003)

Ein "Optimize"-Lauf pinnt mehrfach hintereinander. Jeder Pin verhandelt den
Codec neu und bewegt dabei genau die Zaehler, die diese Anzeige liest. Ohne die
folgenden Regeln erzeugt ein einziger Optimize-Lauf eine Kette roter Zustaende
und eine Kette persistierter `DROPOUT`-Eintraege — die App wuerde ihre eigene
Arbeit als Stoerung protokollieren.

- **U-1 Der Uebergangs-Marker ist ein neu startender Latch, keine
  Warteschlange.** Jeder neue Uebergang setzt den Ablauf von
  `SETTLE_AFTER_TRANSITION_MS` **neu** an. N Umschaltungen kurz hintereinander
  ergeben damit **eine** zusammenhaengende `SETTLING`-Spanne, die
  `SETTLE_AFTER_TRANSITION_MS` nach der **letzten** endet — nicht N Spannen.
- **U-2 Ein angemeldeter Lauf ist von Anfang bis Ende eine Spanne.** Solange
  ein Umschaltvorgang der App laeuft (`LdacTuning.state.busy` oder ein
  angemeldeter Optimize-Lauf, siehe D-7), ist die gesamte Zeit von der ersten
  bis `SETTLE_AFTER_TRANSITION_MS` nach der letzten Umschaltung `SETTLING`,
  auch wenn dazwischen ruhige Fenster liegen.
- **U-3 Keine Episode ueber eine Umschaltspanne hinweg.** Eine laufende
  Verlust-Episode wird beim Eintritt in `SETTLING` **geschlossen** und endet
  dort mit ihrem gemessenen Teil. Sie wird nicht ueber die Spanne fortgesetzt
  und nicht mit einer spaeteren verschmolzen; sonst truege sie eine Dauer, in
  der nichts vergleichbar war.
- **U-4 Kein einziger `DROPOUT` aus einer Umschaltspanne.** Weder waehrend noch
  fuer sie. Was bleibt, ist genau **ein** Eintrag der Detailebene je Spanne:
  "Link retuned {N} times in {D}" (`EventLayer.DETAIL`, also nicht in der
  Hauptliste, aber exportierbar). Damit geht die Tatsache nach `GOAL.md` AK-2
  nicht verloren und die Liste bleibt trotzdem leer.
- **U-5 Die Obergrenze faellt nach `CANNOT_TELL`, nicht nach `CLEAN`.** Reisst
  eine Spanne `SETTLE_MAX_SPAN_MS` — haengendes `busy`-Flag, Dauer-Umschalter —
  wird der Zustand `CANNOT_TELL` mit "The link keeps changing — nothing here is
  comparable.". Ein stillschweigender Ruecksprung auf `CLEAN` waere ein
  falscher Freispruch aus einem Zustand heraus, in dem gar nichts gemessen war.
- **U-6 Eine ABR-Stufe ist kein Uebergang.** `MeasuredBitrateChanged` unter ABR
  bewegt sich staendig; wuerde das `SETTLING` ausloesen, waere ein adaptiver
  Link nie messbar. Uebergaenge sind **nur**: Verbindungsaufbau/-abbau,
  Codec-Wechsel, Playback-Start/-Stopp und eine Aenderung des **gepinnten
  Modus** (`LdacQualityMode`). Die Unterscheidung existiert im Code bereits als
  `LdacModeChanged` gegen `MeasuredBitrateChanged`.

### Bitratenbezug (Nachtrag 6 — beantwortet)

**Frage:** Gehoert die aktuell gefahrene LDAC-Stufe in den Rahmen der Anzeige,
weil dieselbe Verlustrate bei 990 und bei 330 nicht dasselbe bedeutet?

**Antwort: ja als Kontext, nein als Normierung.**

- **Ja, als Kontext.** Jedes Fenster, jede Episode und jeder Log-Eintrag traegt
  die zu diesem Zeitpunkt gemessene Stufe und ob sie gepinnt oder adaptiv war —
  "at 660 kbps, pinned" bzw. "at 492 kbps, ABR". Erst damit kann ein spaeterer
  Leser Gleiches mit Gleichem vergleichen, und genau das ist der aktuelle Fall:
  die Beobachtung "bei gepinnt 660+ hoere ich Stoerungen" ist ohne die Stufe im
  Datensatz nicht nachvollziehbar. Ort: zweite Ebene (Punkt 4) und
  Episoden-Detail.
- **Nein, als Normierung.** Es wird **keine** Groesse "Verlust je Mbit" oder
  "Verlust relativ zur Stufe" gebildet und keine Schwelle durch die Bitrate
  geteilt. Dass Verlust mit der Bitrate skaliert, ist eine Behauptung ueber
  einen Zusammenhang und braeuchte denselben Beleg wie jede andere hier — das
  Projekt hat sich an genau dieser Stelle schon einmal geirrt
  (`framesPerEnqueue` als vermeintlicher Raten-Proxy, am Geraet falsifiziert).
  Ohne Messung je Stufe waere eine Normierung erfunden und damit ein
  AK-3-Verstoss.
- **Was daraus folgt, aber jetzt nicht gebaut wird:** Schwellen **je Stufe**
  waeren die richtige Verfeinerung, sobald es je Stufe eine Ruherate gibt. Das
  ist eine Messung, keine Designfrage; sie steht als M-4 unten und ist
  ausdruecklich nicht Teil dieser Vorgabe.

### Ereignisprotokoll und Timeline

Aus Fenster-Ereignissen werden **Episoden-Ereignisse**.

- Eine Episode oeffnet, wenn der Zustand nach `OCCASIONAL` oder `DISTURBED`
  wechselt; sie schliesst, wenn alle Kanaele fuer `LOSS_CLEAR_HOLD_MS` unter
  `LOSS_CLEAR_RATE_PER_MIN` liegen.
- **Ein** `DROPOUT`-Ereignis je Episode, mit Dauer, Spitzenrate und dominantem
  Kanal. `LOSS_EVENT_COOLDOWN_MS` begrenzt sehr lange Episoden auf einen
  Eintrag je Cooldown.
- Fenster im Zustand `SETTLING` erzeugen **nie** ein `DROPOUT`-Ereignis; eine
  Kette von Umschaltungen erzeugt genau einen Detail-Eintrag statt einer Kette
  von Eintraegen (Regeln U-3 und U-4).
- Jedes Episoden-Ereignis traegt die gefahrene Bitratenstufe und ob sie gepinnt
  oder adaptiv war (siehe "Bitratenbezug").
- `MonitorEventSummary` Zeile 115: `"Audio dropout"` wird zu
  **"{Kanal} loss — {Dauer}"**, z. B. "Encoder underflows — 40 s". Die
  Detailebene traegt weiterhin alle Kanaele einzeln (`lossParts()` bleibt).
  Detailebene traegt weiterhin alle Kanaele einzeln (`lossParts()` bleibt).

---

### Anforderungen an den Datenweg

Kein neuer Zugriff auf das System, keine neue Datenquelle. Was fehlt, ist
Aggregation und Weiterreichen:

- **D-1 Raten je Kanal.** `A2dpTxDelta` hat nur `underflowsPerSecond`. Noetig
  sind Raten fuer `dropped` und `dropouts` sowie fuer die Eingangs-Kanaele —
  reine Arithmetik auf vorhandenen Feldern.
- **D-2 Fenster-Aggregator.** Ein Ring, der die letzten `LOSS_WINDOW_MS`
  aufbewahrt: je Fenster `windowMs`, die fuenf Kanalzaehler getrennt, und ob das
  Fenster ueberhaupt messbar war. `LiveTrace` wirft heute alles ausser der
  Summe weg. **Der Nenner der Rate ist die Summe der `windowMs` der gemessenen
  Fenster, nicht die Wanduhrzeit.**
- **D-3 Coverage explizit.** `LinkObservability` erreicht den Snapshot bereits.
  Die Eingangsseite braucht ein ausdrueckliches "lesbar / nicht lesbar" statt
  der heutigen Ableitung `inputs.none { it.underrunDelta != null }`.
- **D-4 Uebergangs-Marker je Fenster.** Ein Flag am Snapshot: Verbindungsaufbau,
  Codec-Wechsel, Playback-Start/-Stopp, von der App ausgeloester LDAC-Pin
  (`LdacTuning.state.busy`). Alle Signale existieren, keines ist an den
  Verlustpfad verdrahtet.
- **D-5 Sitzungs-Akkumulator.** Zaehler je Kanal mit eigenem Startzeitpunkt fuer
  die zweite Ebene, zurueckgesetzt beim Neustart des Panels und bei jedem
  Zaehler-Reset des Stacks.
- **D-6 Reihenfolge im Panel.** Die Coverage-Qualifikation gehoert **in** die
  erste Ebene der Verlustzeile. Der heutige Hinweis aus `TxRows`
  (`LiveLinkPanel.kt:362`), der unter dem Freispruch steht, wird dort entfernt.
- **D-7 Ein Umschaltlauf meldet sich an.** Der Optimize-Lauf aus T-003 braucht
  einen Weg, dem Monitor "ich schalte jetzt N-mal um" zu sagen und das Ende zu
  melden — sonst kann Regel U-2 die Spanne nicht schliessen und muesste raten.
  Ein Zaehler "offene Umschaltvorgaenge" plus ein Zeitstempel der letzten
  Umschaltung reicht; `LdacTuning` haelt bereits den Ort dafuer.
- **D-8 Alter nur ueber gemessene Zeit.** Fuer "last counted {D} ago" braucht
  der Aggregator den Zeitstempel des letzten gezaehlten Ereignisses **und** die
  Information, ob die Spanne seitdem luecklos gemessen wurde. Ohne das zweite
  darf die Zahl nicht gezeigt werden.
- **D-9 Bitratenstufe am Fenster.** `ldac.measuredKbps` und
  `LdacState.isAdaptive` sind bereits im Snapshot; sie muessen mit ins Fenster
  und in den Episoden-Datensatz, damit spaeter Gleiches mit Gleichem
  verglichen werden kann.

### Messanforderung an T-001

- **M-1 (wichtig):** Welche der fuenf Kanaele bewegen sich bei subjektiv
  perfekter LDAC-Wiedergabe ueberhaupt — **getrennt gemessen, in Ereignissen
  pro Minute**? `docs/perf/baselines.md` deckt heute nur die tx-Seite ab und
  zeigt dort 0/min. Ohne diese Zahl fuer App- und Mixer-Underruns ist
  `LOSS_NOTICE_RATE_PER_MIN` fuer zwei von fuenf Kanaelen nicht setzbar.
- **M-2:** Streuung dieser Raten ueber mehrere Laeufe, damit `NOTICE` mit
  Abstand statt knapp ueber dem Rauschen gesetzt werden kann.
- **M-3:** Dauer einer LDAC-Codec-Neuverhandlung, als Untergrenze fuer
  `SETTLE_AFTER_TRANSITION_MS`, und die Zeit, die ein Optimize-Lauf mit
  mehreren Umschaltungen insgesamt braucht, als Untergrenze fuer
  `SETTLE_MAX_SPAN_MS`.
- **M-4 (neu, aus Nachtrag 6):** Ruherate je Kanal **je Bitratenstufe**,
  mindestens fuer gepinnt 330 / 660 / 990 und fuer ABR. Erst damit waeren
  Schwellen je Stufe moeglich; ohne sie bleibt die Stufe reiner Kontext.
  **Dringlich**, weil die aktuelle Beobachtung des App Designers genau eine
  Stufe betrifft (gepinnt 660+): wenn dort ein Kanal laeuft, der bei ABR ruht,
  faellt es nur in dieser Messung auf.

---

### Akzeptanzkriterien

Pruefbar, gegen diese Saetze wird im Review-Modus gemessen.

- **AK-T002-1** Auf keiner Oberflaeche der App steht ein Verlustwert ohne
  Bezugsrahmen. Jede genannte Zahl traegt entweder ein Fenster ("in the last
  60 s"), eine Rate ("/min") oder eine ausgesprochene Epoche ("since this panel
  started watching", "since the Bluetooth stack started").
- **AK-T002-2** Bei gesunder Wiedergabe (alle Kanaele unter `NOTICE`) traegt die
  Verlustzeile **keinen** Pill, keine Fehlerfarbe und keine Zahl ausser dem
  Fenster.
- **AK-T002-3** `CLEAN` und `CANNOT_TELL` sind ohne Farbwahrnehmung
  unterscheidbar: `CANNOT_TELL` traegt einen Pill, `CLEAN` nicht, und die
  Saetze teilen kein Wort ausser Funktionswoertern. In einem Graustufen-
  Screenshot beider Zustaende ist die Unterscheidung erhalten.
- **AK-T002-4** Bei `LinkObservability.OFFLOADED` erscheint an **keiner** Stelle
  der Oberflaeche das Wort "no loss", "nothing lost" oder eine 0 fuer einen
  Stack-Kanal — auch nicht unterhalb oder nach einem korrekten Hinweis.
  Automatisiert pruefbar als Compose-Test gegen einen offloaded Snapshot.
- **AK-T002-5** Fenster innerhalb `SETTLE_AFTER_TRANSITION_MS` nach
  Verbindungsaufbau, Codec-Wechsel, Playback-Start/-Stopp oder LDAC-Pin gehen
  weder in eine Rate noch in ein `DROPOUT`-Ereignis ein und werden auf dem
  Graphen als `SETTLING` markiert. Pruefbar als Unit-Test des Aggregators.
- **AK-T002-6** Ein von der App selbst ausgeloester LDAC-Pin erzeugt keinen
  Verlust-Zustand und keinen Log-Eintrag. Regressionstest gegen den Befund,
  dass die App heute ihre eigene Aktion als Stoerung meldet.
- **AK-T002-7** Eine 30-minuetige Sitzung mit **einer** durchgehenden Stoerung
  erzeugt genau **ein** `DROPOUT`-Ereignis je `LOSS_EVENT_COOLDOWN_MS`, nicht
  eines je Poll. Pruefbar als Unit-Test des Episoden-Trackers.
- **AK-T002-8** Die fuenf Kanaele werden nirgends zu einer Zahl addiert. Weder
  in der Zeile, noch in der Caption, noch im Ereignistext. Pruefbar per Grep auf
  das Verschwinden von `lossCount()` als Summe.
- **AK-T002-9** Die Rate wird gegen die Summe der `windowMs` der **gemessenen**
  Fenster gebildet. Ein Aggregator, dem die Haelfte der Fenster fehlt, meldet
  dieselbe Rate wie einer mit allen — nicht die doppelte. Unit-Test.
- **AK-T002-10** Kein Schwellenwert steht als Literal im Code. Jeder ist eine
  benannte Konstante mit KDoc zur Form; ungesetzte Werte tragen
  `TODO(T-001)`.
- **AK-T002-11** Die Graph-Caption nennt Marken als Marken: "{k} of {n} windows
  lost something", plus "{m} not measured" wenn `m > 0`. Der Wert waechst
  innerhalb eines vollen Fensters nicht monoton.
- **AK-T002-12** Kein Text der Verlustanzeige behauptet oder verneint
  Hoerbarkeit, und keiner macht den Klang zum Subjekt (Regeln R-A und R-C).
  Verboten als Zeichenkette: "audio lost", "no loss", "nothing lost", "audible",
  "you heard", "all good", "healthy", "everything fine". Verboten als Bild:
  gruener Haken, Daumen. Erlaubt: "dropped" fuer die zwei Stack-Kanaele, "ran
  dry" fuer die drei Underflow-/Underrun-Kanaele. Pruefbar per Grep und als
  Compose-Test ueber alle fuenf Zustaende.
- **AK-T002-13** Kein Erklaertext dieser Vorgabe ist laenger als zwei Saetze.
  Der Textumfang des Live-Panels waechst gegenueber `babe3d8` nicht.
- **AK-T002-14** Kein Detailverlust (`GOAL.md` AK-2): Underflow- und
  Dropout-Zaehler bleiben erreichbar. Sie stehen in der zweiten Ebene, je Kanal
  getrennt, mit Sitzungs- **und** Stack-Epoche.
- **AK-T002-15** Die Verlustzeile ist in **jedem** der fuenf Zustaende
  vorhanden. Es gibt keinen Zustand, in dem sie fehlt (Entscheidung 1).
  Compose-Test ueber alle fuenf Zustaende.
- **AK-T002-16** Die zweite Ebene nennt in jedem Zustand die Decke der Messung —
  Funk und alles jenseits der Antenne sind nicht gezaehlt (Regel R-B). Der Satz
  ist auch im ruhigen Zustand da, nicht nur im gestoerten.
- **AK-T002-17** Eine Rate erscheint nur bei `k >= RATE_MIN_EVENTS_IN_WINDOW`
  und dann **ohne** Nachkommastelle; darunter erscheint ein Alter. Unit-Test
  ueber k = 0, 1, 9, 10, 50.
- **AK-T002-18** "last counted {D} ago" erscheint nur, wenn die Spanne seit dem
  Ereignis luecklos gemessen wurde; sonst die `{M}`-Formulierung. Unit-Test mit
  einer kuenstlichen Messluecke.
- **AK-T002-19** Die Nahaufnahme wird von keinem Zustandswechsel eingeschaltet.
  Kein Codepfad ruft `setCloseUpEnabled(true)` ausser der Nutzeraktion am Chip
  (Entscheidung 4). Pruefbar per Grep auf Aufrufstellen.
- **AK-T002-20** Eine Kette von `N` Umschaltungen innerhalb einer Spanne erzeugt
  **null** `DROPOUT`-Ereignisse und **genau einen** Detail-Eintrag "Link retuned
  {N} times in {D}" (Regeln U-1 bis U-4). Unit-Test mit N = 1, 2 und 6
  simulierten Pins — das ist der Regressionstest gegen T-003.
- **AK-T002-21** Reisst eine Umschaltspanne `SETTLE_MAX_SPAN_MS`, ist der
  Folgezustand `CANNOT_TELL`, nie `CLEAN` (Regel U-5). Unit-Test.
- **AK-T002-22** Eine ABR-Bitratenstufe loest kein `SETTLING` aus (Regel U-6).
  Ein ueber zehn Minuten frei wandernder ABR-Link bleibt durchgehend messbar.
  Unit-Test mit einer Folge von `MeasuredBitrateChanged` ohne Modewechsel.
- **AK-T002-23** Jede Episode und jeder Log-Eintrag traegt die gefahrene
  Bitratenstufe und ob sie gepinnt oder adaptiv war. Es existiert **keine**
  Groesse, die Verlust durch die Bitrate teilt oder mit ihr normiert
  (Nachtrag 6). Pruefbar per Grep auf Divisionen durch `measuredKbps`.

### Ausdruecklich nicht Teil dieser Vorgabe

- Der Rest der Monitor-Oberflaeche: Timeline-Lanes, Data-Source-Panel,
  Device-Test, Bitraten-Zeile, LDAC-Chips.
- Die Bitraten-Linie selbst und der Liveness-Fallback — richtig geloest, bleibt.
- Die Mechanik des "Watch closely"-Chips. Festgelegt sind nur seine
  Beschriftung im ruhigen Zustand und dass **nichts** ihn automatisch schaltet.
- Ein "I heard a stutter"-Marker — vom App Designer abgelehnt, siehe
  Entscheidung 2. Die Konsequenz daraus ist nicht offen, sondern als
  Hoerbarkeitsgrenze R-A/R-B/R-C bindend spezifiziert.
- Schwellen **je Bitratenstufe**. Richtige Verfeinerung, braucht aber M-4;
  bis dahin ist die Stufe Kontext, keine Normierung.
- BQR als zusaetzliche Verlustquelle. Aendert nichts an dieser Darstellung,
  waere ein weiterer Kanal in derselben Tabelle.
- Die Umsetzung von T-003 selbst. Diese Vorgabe legt nur fest, wie der Monitor
  sich gegenueber einem Umschaltlauf verhaelt, und was er von ihm braucht (D-7).

### Entscheidungen des App Designers (2026-08-30, ueber den Director)

Alle fuenf urspruenglich offenen Fragen sind beantwortet und oben eingearbeitet.
Sie stehen hier als Protokoll, damit ein spaeterer Durchlauf sie nicht erneut
stellt (Stabilitaetsregel).

1. **Verlustzeile im Zustand `CLEAN`: bleibt sichtbar, ohne Pill.** Eine Zeile,
   die verschwindet, ist von "die App misst gerade nicht" nicht zu
   unterscheiden. Eingearbeitet in "Zustaende" und AK-T002-15.
2. **"Ich habe das gehoert"-Marker: nein.** Zu viel Aufwand im Alltag. Damit
   gibt es dauerhaft keine Bruecke zwischen Gezaehltem und Gehoertem.
   Eingearbeitet als Abschnitt "Die Hoerbarkeitsgrenze" (R-A/R-B/R-C),
   AK-T002-12 und AK-T002-16.
3. **Einheit bei seltenen Ereignissen: Alter statt Rate.** Eingearbeitet als
   Abschnitt "Rate oder Alter", Parameter `RATE_MIN_EVENTS_IN_WINDOW` = 10 und
   AK-T002-17/18.
4. **Nahaufnahme schaltet sich nicht selbst ein: nein.** Verstoss gegen
   `GOAL.md` AK-1 und AK-4. Ein Hinweis ist erlaubt. Eingearbeitet in "Graph und
   Nahaufnahme" Punkt 5 und AK-T002-19.
5. **Pill-Wortwahl: "Can't tell".** Sagt etwas ueber das Wissen der App statt
   ueber die Beschaffenheit der Daten. Bereits so festgelegt, bleibt.

### Offene Fragen an den App Designer

Keine. Die Vorgabe ist ohne Rueckfrage umsetzbar.

Was noch fehlt, sind **Messwerte, keine Entscheidungen**: neun der zehn
Parameter haben keinen Wert, bis T-001 die Messungen M-1 bis M-4 liefert.
`RATE_MIN_EVENTS_IN_WINDOW` (= 10) und die Form aller uebrigen Parameter stehen
fest. Der `developer` kann Struktur, Zustaende, Texte, Episoden-Logik und Tests
vollstaendig bauen; er setzt die Zahlen zuletzt ein.
