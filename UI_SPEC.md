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

> **Nachtrag 2026-09-01 (T-009):** Die Messwerte sind da und sagen, dass die
> hier gewaehlte **Leitgroesse falsch** war. Der Abschnitt bleibt als Protokoll
> stehen; was gilt, steht im Abschnitt „Leitgroesse neu" weiter unten. Konkret
> ueberholt sind: Befund 6 (Rangfolge der Kanaele), die Gleichrangigkeit der
> fuenf Kanaele im Verdikt, die Rolle von `Counts (underflow)` als Indikator
> und die Begruendung, die `EncoderStarvationTripwire` als Vorbild nimmt. Die
> **Form** der Leitgroesse (Zustand statt Zahl, Rate statt Summe, pro Kanal
> getrennt, kein Prozentsatz, gemessene Zeit als Nenner) haelt und bleibt
> unveraendert.

---

## Leitgroesse neu: was die Strecke traegt (T-009) — 2026-09-01

Fortschreibung von T-002, nicht Ersatz. Alles aus T-002, was hier nicht
ausdruecklich geaendert wird, gilt unveraendert weiter — insbesondere die
Zustandsmechanik, die Umschaltketten U-1..U-6, „Rate oder Alter",
`RATE_MIN_EVENTS_IN_WINDOW = 10` und der Datenweg D-1..D-9.

Quellen dieser Fortschreibung: `docs/perf/T-008-experimente.md` (E-2
vollstaendig, A0 → B → A'), `docs/perf/T-007-aufnahme.md` (Laeufe A/B/C),
`docs/state.md` vom 01.09., `ARCHITECTURE.md` AD-011. **Alle Messungen wurden
mit deinstallierter App und laufender Musik am Pixel 11 Pro gefahren; die
Zahlen sind belegt, nicht abgeleitet.** Diese Fortschreibung selbst ist
**unverifiziert (Spezifikation ohne Geraet und ohne Toolchain)** — auf diesem
Rechner laeuft nichts, was man ansehen koennte.

### Warum die Leitgroesse wechselt — drei Befunde, jeder fuer sich hinreichend

1. **Der ruhige Zustand ist nicht ruhig, er gibt nach.** Ueber 514 s in fuenf
   unabhaengigen Laeufen standen **alle** Verlustzaehler auf exakt null,
   waehrend der ABR-Regler zwischen 492 und 660 kbps pendelte, im Mittel alle
   11 s ein Wechsel, in Lauf B 35 Samples auf 660 gegen 35 auf 492. Der Encoder
   verliert nichts, **weil er vorher nachgibt**. Eine Anzeige, die nur Zaehler
   liest, meldet die Haelfte der Zeit bei 75 % der oberen Stufe als
   ereignislos — was zaehlerisch stimmt und die Frage des Nutzers nicht
   beruehrt. **Der Verlust dieser Strecke steht in der Bitrate, nicht in den
   Zaehlern.**
2. **Der hoerbar kaputte Zustand bewegt einen anderen Zaehler als erwartet.**
   Bei 990 gepinnt: 525 `dropped` (324,7/min) und 21 `dropouts` (12,99/min) in
   97,0 s, vom App Designer durchgehend als Aussetzer gehoert — und
   **`Counts (underflow)` blieb auch dort null**. Eine Anzeige auf
   Underflow-Basis haette diesen Zustand als einwandfrei gemeldet. Das ist
   genau der falsche Freispruch aus `GOAL.md` AK-3, und er waere durch die
   T-002-Fassung hindurchgegangen.
3. **Der Regler selbst ist eine Informationsquelle, die T-002 als Rauschen
   behandelt hat.** In A' steuerte er von sich aus 990 an und verliess die
   Stufe nach einer Lesung (~1,4 s) zwei Stufen tiefer — dieselbe Aussage, die
   unsere erzwungene Messung ergab, nur autonom. Und nach einer Stoerung tastet
   er ~18,5 s lang alle vier Stufen ab (13,00 Wechsel/min), bevor er auf 3,08
   Wechsel/min zurueckfaellt. Wer diese beiden Ereignisse nicht kennt, meldet
   jeden Kopfhoererwechsel als Problem und uebersieht die einzige Stelle, an
   der der Stack selbst ein Urteil ueber eine Stufe faellt.

### Die neue Leitgroesse

**Zwei Regime, zwei Groessen, zwei Zeilen — und keine Zusammenfassung ueber
beide.**

| Regime | Leitgroesse | Traegt die Aussage |
|---|---|---|
| **Normalbetrieb** (adaptiv, Zaehler ruhig) | **Stufe, Verweildauer, Anteile im Fenster** — was die Strecke gerade traegt | Stufenzeile (ersetzt `rateLine()` an Ort und Stelle) |
| **Ueberlast** (Pakete gehen wirklich verloren) | **`dropped` und `dropouts` je Minute** ueber gemessene Zeit | Verlustzeile (T-002, unveraendert in der Form) |

Begruendung der Aufteilung:

- **Zwei Regime, weil die Messung zwei zeigt, die sich gegenseitig ausschalten.**
  Wo der Regler regelt, gibt es keine Verluste; wo er nicht regeln darf, gibt
  es fast nur Verluste (Queue in 79–81 % der Lesungen nicht leer gegen 0–1,4 %
  im Normalbetrieb). Eine einzige Groesse ueber beide Regime waere in jedem
  Regime die falsche.
- **Keine Zusammenfassung ueber beide.** Es gibt keine Zahl, die „492 statt
  660" und „13 Aussetzer/min" vergleichbar macht, und es gibt keine Messung,
  die eine Umrechnung stuetzen wuerde. Eine gemeinsame Ampel waere erfunden
  (AK-3). Die zwei Zeilen stehen nebeneinander und sagen jede fuer sich, was
  sie wissen.
- **Underflow verliert die Hauptrolle, behaelt die Zeile.** `Counts (underflow)`
  bleibt einer der fuenf Kanaele der zweiten Ebene (AK-T002-14, `GOAL.md`
  AK-2), aber es traegt **kein Verdikt mehr** und seine Null entlastet nichts.
  Belegt: null im hoerbar kaputten Arm.
- **Rangfolge innerhalb der Verlustfamilie, neu.** T-002 hat die fuenf Kanaele
  gleichrangig behandelt. Das war falsch: nur `dropped` und `dropouts` haben
  einen Kalibrierpunkt in **beide** Richtungen (0 unhoerbar, ~13/min hoerbar).
  Sie und nur sie tragen `DISTURBED`.

### Was aus T-002 dadurch falsch war — ausdruecklich benannt

- **Befund 6 („was ueberhaupt zaehlt, ist wahrscheinlich nicht die tx-Seite")
  ist widerlegt.** Unter Ueberlast ist genau die tx-Seite die richtige Groesse.
  M-1 (Eingangsseite) bleibt offen und richtig, aber nicht mehr als
  wichtigste Messung.
- **Die Gleichrangigkeit der fuenf Kanaele im Verdikt war falsch.** Siehe
  Rangfolge oben.
- **`EncoderStarvationTripwire` taugt nicht als Vorbild fuer die Schwelle
  dieser Anzeige.** Seine *Form* (Schwelle in die leere Mitte zwischen zwei
  gemessene Haufen legen) haelt und wird hier weiterverwendet. Seine *Groesse*
  — Underflows pro Sekunde — war im einzigen gemessenen hoerbar kaputten
  Zustand blind. Der Tripwire bleibt, was er ist: ein Ausloeser fuer den
  ~49/s-Vorfall, den er einmal gefangen hat. Er darf an **keiner** Stelle der
  Oberflaeche als Verlust- oder Stoerungsindikator auftreten oder als solcher
  benannt werden.
- **Die T-002-Fassung haette den Fall des App Designers nicht gefangen.** Das
  ist der schwerwiegendste Einzelbefund gegen die eigene Vorgabe, und er steht
  hier, damit ein spaeterer Durchlauf ihn nicht wegkuerzt.

---

### Die Hoerbarkeitsgrenze — Nachtrag mit den ersten Messpunkten

Der Abschnitt aus T-002 bleibt vollstaendig gueltig. R-A (Subjekt ist der
Zaehler), R-B (der ruhige Zustand nennt seine Decke) und R-C (kein Zustand
widerlegt ein Ohr) bleiben unveraendert. Er bekommt jetzt Zahlen — und zwei
weitere Regeln, die aus genau diesen Zahlen folgen.

**Belegt (T-008, drei Arme, Hoereindruck des App Designers in allen dreien):**

| Punkt | Dropouts | Hoereindruck |
|---|---|---|
| A0 / A' (ABR) | **0/min**, fuenffach ueber 514 s | **unhoerbar** |
| B (990 gepinnt) | **12,94 und 12,99/min**, zwei unabhaengige Laeufe | **deutlich hoerbar, durchgehend** |

**Nicht belegt und nicht ableitbar:** alles zwischen 0 und ~13/min. **Zwei
Punkte, keine Kurve.** Ob 3/min hoerbar sind, weiss niemand.

- **R-D Kein Zaehler spricht einen anderen frei.** Ein Kanal auf null ist eine
  Aussage ueber genau diesen Kanal. Belegt: `underflow` = 0 in einem hoerbar
  kaputten Zustand. Kein Text, kein Zustand und keine Sortierung der
  Oberflaeche darf so gebaut sein, dass die Null eines Kanals die Anzeige eines
  anderen dampft, ersetzt oder ueberschreibt.
- **R-E Zwischen den zwei Punkten wird nur gezaehlt, nie geurteilt.** Fuer
  jede `dropouts`-Rate echt zwischen 0 und `LOSS_ALERT_RATE_PER_MIN[dropouts]`
  ist ausschliesslich die zaehlende Formulierung erlaubt („{N} stack dropouts
  in the last {W} — about {R}/min"). Verboten sind dort zusaetzlich zu den
  Verboten aus R-C auch: „minor", „slight", „barely", „probably inaudible",
  „should not be noticeable", „may be audible", jede Abstufung wie „mild /
  moderate / severe" und jedes mehrstufige Bildzeichen, das eine Skala
  suggeriert. **Die Anzeige hat dort keine Meinung.**
- **Die Decke des ruhigen Zustands bekommt einen zweiten Satz** (Erweiterung
  von R-B, siehe „Zweite Ebene" unten): dass kein Zaehler sich bewegt hat,
  heisst auch dann nicht „nichts verloren", wenn alle fuenf gelesen wurden —
  belegt, weil der Regler in genau diesem Zustand die Haelfte der Zeit eine
  Stufe unter dem Maximum faehrt, ohne dass ein Zaehler das bemerkt.

---

### Die Stufenzeile (neu, ersetzt `rateLine()` an Ort und Stelle)

**Kein neuer Platz im Panel.** Die Zeile ist die vorhandene LDAC-Ratenzeile aus
`LiveLinkPanel.LdacSection` (`ExplainedRow(label = ldac.rateLine(), …)`,
`LiveLinkPanel.kt:240`, Formatierer bei Zeile 523). Sie bekommt die
Verweildauer dazu und wird laenger um zwei Woerter, nicht um eine Zeile.
Damit bleibt AK-T002-13 (kein Textwachstum gegenueber `babe3d8`) gewahrt; die
Anteile, das Einschwingen und der Queue-Druck gehen in die zweite Ebene.

**Zustaende der Stufenzeile — genau vier, immer genau einer sichtbar:**

| # | Zustand | Bedingung | Erste Ebene (Englisch, verbindlich) |
|---|---|---|---|
| 1 | `LADDER_CANNOT_TELL` | kein `A2DP LDAC State`-Block, kein LDAC, offloaded | vorhandene Strings, unveraendert: "Adaptive — rate not observable" / "LDAC quality not readable" |
| 2 | `LADDER_MEASURING` | weniger als zwei vergleichbare Lesungen | "{X} kbps right now (measured)" — Verweildauer erst ab zwei Lesungen |
| 3 | `LADDER_PINNED` | Modus nicht adaptiv (`LdacState.isAdaptive == false`) | vorhandener String, unveraendert: "{nominal} kbps (pinned) · {measured} kbps measured" |
| 4 | `LADDER_STEADY` | adaptiv, Wechselrate unter `LADDER_SETTLING_RATE_PER_MIN` | "Adaptive — {X} kbps for {D} (measured)" |
| 5 | `LADDER_SETTLING` | adaptiv, Wechselrate ab `LADDER_SETTLING_RATE_PER_MIN` | "Adaptive — moving between steps, {R}/min over the last {LW}." |

Fuenf Zeilen in einer Tabelle mit vier angekuendigten Zustaenden waere
schlampig, deshalb ausdruecklich: `LADDER_MEASURING` ist **kein** eigener
Zustand, sondern die erste Lesung von `LADDER_STEADY`/`LADDER_PINNED`, in der
die Verweildauer noch nicht existiert. Der bestehende String bleibt fuer diesen
Fall unveraendert — es wird kein „Loss needs two readings"-Aequivalent fuer die
Stufe eingefuehrt, weil die Stufe selbst schon nach **einer** Lesung MEASURED
ist. Nur die Verweildauer braucht zwei.

**Warum Verweildauer und nicht Wechselrate in der ersten Ebene:** Bei
gemessenen 3,08–5,30 Wechseln/min enthaelt ein 60-s-Fenster 3–5 Ereignisse.
Nach der eigenen Regel „Rate oder Alter" (k >= 10) duerfte dort gar keine Rate
stehen. Die Verweildauer ist dagegen eine direkt gemessene Zeit und braucht
keine Zaehlstatistik. Die Wechselrate erscheint nur dort, wo sie gross genug
ist, um belastbar zu sein: im Einschwingen (13,00/min ueber 180 s = k ≈ 39).

**`LADDER_PINNED` bleibt unangetastet**, weil die vorhandene Loesung bereits
richtig ist: sie zeigt Sollstufe **und** Messwert nebeneinander, und genau
dadurch waere der Fall des App Designers — 990 gepinnt, 990 gemessen, trotzdem
kaputt — an der Verlustzeile sichtbar geworden statt an dieser. Das ist gut und
bleibt (siehe „Positiv/beibehalten" wird hier zu: nicht anfassen).

### Ereignis „Stufe angesteuert und verworfen"

Ein eigenes Ereignis, kein Rauschen. Belegt: A' bei t = 11,32 s, 990 kbps in
genau **einer** Lesung, danach zwei Stufen tiefer und nie wieder angesteuert.

- **Definition:** Eine Stufe, die in hoechstens `LADDER_REJECTED_MAX_READINGS`
  aufeinanderfolgenden Lesungen stand und **nach unten** verlassen wurde.
- **Ehrlichkeitsauflage, zwingend:** Die wahre Verweildauer ist bei einer
  einzigen Lesung **nicht aufgeloest** — sie liegt irgendwo zwischen einem und
  zwei Poll-Intervallen. Der Text sagt deshalb nie „held for 1.4 s", sondern
  **"held for less than {2 × Kadenz}"**. Wer die 1,4 s als Messwert schreibt,
  behauptet eine Aufloesung, die die Abtastung nicht hat.
- **Ort:** zweite Ebene der Stufenzeile und **ein** Log-Eintrag der Detailebene
  (`EventLayer.DETAIL`), nie ein `DROPOUT`, nie die Hauptliste.
- **Wortlaut (Englisch):** "ABR tried {X} kbps and left it within {2 × Kadenz},
  {t} ago."
- **Warum ueberhaupt:** Es ist die einzige Stelle, an der der Stack selbst ein
  Urteil ueber eine Stufe faellt. Eine Stufe, die **angesteuert und verworfen**
  wird, sagt etwas anderes aus als eine, die nie erscheint — und beides sagt
  etwas anderes als eine, die gehalten wird. Die Anzeige darf daraus **keine**
  Empfehlung machen („pin nicht auf 990"): ob ein Befund eine
  Handlungsempfehlung wird, haengt an T-005 und offenen Nutzerentscheidungen
  und ist nicht Teil dieser Vorgabe.

### Einschwingen gegen Dauerzustand

Belegt: 18,5 s mit 13,00 Wechseln/min ueber alle vier Stufen, danach 77,9 s mit
3,08 Wechseln/min — und das eingeschwungene gewichtete Mittel (576,0 kbps)
trifft die Referenz A0 (580,8 kbps) auf 0,8 %.

- Die Unterscheidung laeuft ueber `LADDER_SETTLING_RATE_PER_MIN`, nicht ueber
  einen Timer nach einem Ereignis. Grund: Das Einschwingen ist am
  Reglerverhalten **sichtbar**, ein Timer waere eine Annahme darueber, wie
  lange es dauert.
- `LADDER_SETTLING` ist **kein** `SETTLING` im Sinne von T-002 und loest keines
  aus. U-6 bleibt unveraendert gueltig: eine ABR-Stufe ist kein Uebergang, und
  ein ueber zehn Minuten frei wandernder Link bleibt durchgehend messbar. Die
  beiden duerfen im Code nicht denselben Namen tragen — der eine beschreibt die
  Vergleichbarkeit der **Zaehler** nach einem Umschalten, der andere den
  Zustand der **Stufenleiter**.
- Waehrend `LADDER_SETTLING` wird **keine** Aussage ueber Anteile im Fenster
  gemacht („660 for 48 %" waere ueber eine Abtastphase gemittelt und damit
  bedeutungslos). Die zweite Ebene sagt dort stattdessen: "Steps are still
  moving — the share of each step is not comparable yet."

### Warteschlangendruck statt Einzelpaket-Alarm

Die heutige Zeile `TxRows` (`LiveLinkPanel.kt:377`) — "Bluetooth is falling
behind: {N} packets queued", sobald `savedTxQueueLength > 0` — ist ein
Fehlalarm-Erzeuger und faellt weg. Belegt: In A0 stand die Queue in **1 von 70**
Lesungen auf 2, in A' in **0 von 70**, und in T-007 lagen die einzigen zwei
Nicht-Null-Samples aus 262 genau auf Stufenabstiegen. Ein einzelnes gestautes
Paket beim Herunterschalten **ist der Normalbetrieb des Reglers**, kein
Zurueckfallen.

Ersatz, mit einem Messwert statt eines Einzelsamples:

- Gebildet wird der **Anteil der Lesungen im Fenster mit `savedTxQueueLength`
  > 0**. Belegt: 0–1,4 % im Normalbetrieb (A0/A'), **79–81 %** unter Ueberlast
  (55/70 und 129/160).
- Die Zeile erscheint erst ab `LADDER_QUEUE_PRESSURE_FRACTION` und dann in der
  zweiten Ebene der Stufenzeile, nicht in der ersten. Grund: Sie ist ein
  **Fruehindikator**, kein Ereignis — sie sagt „die Strecke ist am Anschlag",
  nicht „etwas ist verloren gegangen". Was verloren ging, sagt die Verlustzeile.
- **Wortlaut (Englisch):** "The send queue was not empty in {f} % of the
  readings in the last {LW}." Subjekt ist die Warteschlange, also ein Zaehler
  (R-A). Kein „falling behind", kein „struggling", keine Fehlerfarbe unterhalb
  der Schwelle.

---

### Verlustzeile — was sich gegenueber T-002 aendert

Die Mechanik (fuenf Zustaende, Coverage, Episoden, U-1..U-6) bleibt
**unveraendert**. Geaendert wird nur, welcher Kanal welches Verdikt tragen darf:

| Kanal | `OCCASIONAL` | `DISTURBED` | Begruendung |
|---|---|---|---|
| Dropped packets | ja | nein (siehe unten) | belegt hoerbar, aber dasselbe Ereignis wie `dropouts`, feiner gezaehlt |
| Stack dropouts | ja | **ja — der einzige** | einziger Kanal mit Kalibrierpunkt in beide Richtungen |
| Encoder underflows | ja | **nein** | im einzigen gemessenen hoerbar kaputten Zustand null; es gibt keinen gemessenen gestoerten Wert |
| App underruns | ja | offen (M-1) | nie gemessen |
| Mixer underruns | ja | offen (M-1) | nie gemessen |

**Warum `dropped` keine eigene `DISTURBED`-Schwelle bekommt:** Beide Zaehler
stammen aus demselben Ereignis — 525 verworfene Pakete in Buendeln bis 26
ergaben 21 Aussetzer. Zwei Schwellen aus einem Ereignis waeren zwei
Behauptungen aus einem Beleg. `dropped` erscheint mit seiner Zahl und seiner
Rate in beiden Ebenen; es traegt nur kein zweites Verdikt.

Formulierungen der ersten Ebene bleiben wortgleich wie in T-002. Ergaenzt wird
genau eine, fuer den Fall, der die ganze Fortschreibung ausgeloest hat:

- `CLEAN` (`ALL_FIVE`) bei gleichzeitig adaptiver, wandernder Stufe — die
  Verlustzeile bleibt **wortgleich** ("No counter moved in the last {W}."), und
  die Stufenzeile darueber sagt in derselben Sekunde, welche Stufe gerade
  getragen wird. **Es wird kein kombinierter Satz gebaut.** Zwei Zeilen, zwei
  Aussagen, keine Verrechnung — jede andere Loesung waere die Zusammenfassung,
  die es nach der Messlage nicht geben darf.

### Zweite Ebene — Ergaenzungen

Punkte 1–5 aus T-002 bleiben. Punkt 4 („Die Bitratenstufe des Fensters") wird
erweitert, Punkt 5 (die Decke) bekommt zwei Saetze dazu, und es kommt ein
Punkt 6 hinzu:

4. **Die Stufenleiter im Fenster** — je Zeile, nie als Prosa:
   - "Steps in the last {LW}: {X} kbps {p} %, {Y} kbps {q} % of measured time."
     Anteile an der **gemessenen** Zeit, nicht an der Wanduhr (D-2 gilt
     unveraendert).
   - "{n} step changes in the last {LW}." — als Zahl, nicht als Rate, solange
     k < `RATE_MIN_EVENTS_IN_WINDOW`.
   - "Highest step seen in the last {LW}: {X} kbps."
   - Das Ereignis „angesteuert und verworfen", wenn es eines gab.
   - Der Warteschlangendruck, wenn er ueber der Schwelle liegt.
   - In `LADDER_SETTLING`: nur der Satz aus „Einschwingen gegen Dauerzustand",
     keine Anteile.
5. **Die Decke, dauerhaft und in jedem Zustand**, jetzt dreiteilig:
   - der vorhandene Satz zu Funk und allem jenseits der Antenne (unveraendert),
   - **neu:** "A counter at zero is a statement about that counter only —
     the encoder underflow counter stayed at zero on a link that was dropping
     packets." Belegt, und der Satz nennt den Beleg mit.
   - **neu:** "These counters also stay at zero while the adaptive encoder
     lowers its bitrate instead of losing data." Das ist der Satz, der „kein
     Zaehler hat sich bewegt" davon abhaelt, als „nichts verloren" gelesen zu
     werden.
6. ~~**Die Kalibrierpunkte, wortgleich und nicht interpretiert.**~~
   **ENTFAELLT ersatzlos** (App Designer, Entscheidung 4, siehe Nachtrag). Die
   Kalibrierpunkte erscheinen nirgends in der App; das Wort „audible" kommt in
   der Oberflaeche nicht mehr vor. Grund: die zwei Punkte sind konfundiert —
   sie unterscheiden sich zugleich in der Dropout-Rate und in der Bitratenstufe.

---

### Parameter — was jetzt gesetzt ist und was warum offen bleibt

Neun Parameter waren offen. **Sechs sind gesetzt, einer ist als Formel
gesetzt und in einem Randfall offen, zwei sind je Kanal geteilt: fuer die
Stack-Kanaele gesetzt, fuer die nie gemessenen Eingangskanaele offen.**

| Parameter | Wert | Woraus |
|---|---|---|
| `LOSS_WINDOW_MS` | **60 000** | Bei der einzigen belegt hoerbaren Rate (12,94/12,99 Dropouts/min) enthaelt ein 60-s-Fenster k ≈ 13 ≥ `RATE_MIN_EVENTS_IN_WINDOW` (10) — der belegte Fall ist damit der erste, der ueberhaupt eine Rate statt eines Alters bekommt. Bei 30 s waere k ≈ 6,5 und der belegte Fall wuerde als Alter gemeldet, also untertrieben. Zusaetzlich deckungsgleich mit `LiveTrace.OVERVIEW_WINDOW_MS` (60 s), und der laengste gemessene Ereignisabstand innerhalb einer laufenden Stoerung (7,68 s) passt achtfach hinein. |
| `LOSS_NOTICE_RATE_PER_MIN[dropped]`, `[dropouts]`, `[underflows]` | **1/min** (= ein Ereignis im Fenster) | Die Ruherate dieser drei Kanaele ist ueber 514 s in fuenf unabhaengigen Laeufen **exakt 0**. Obergrenze nach der Dreierregel: 3/8,57 min ≈ **0,35/min**. Der kleinste in einem 60-s-Fenster ueberhaupt aufloesbare Wert ist 1/min und liegt damit ~3× ueber dieser Obergrenze. Ein Abstand ist hier nicht waehlbar, sondern durch die Aufloesung erzwungen — das ist als Restrisiko benannt, siehe M-5. |
| `LOSS_NOTICE_RATE_PER_MIN[app underruns]`, `[mixer underruns]` | **offen** | **M-1 unveraendert offen.** Diese zwei Kanaele sind nie gemessen worden, weder ruhend noch gestoert. T-007/T-008 haben ausschliesslich die tx-Seite erfasst. |
| `LOSS_ALERT_RATE_PER_MIN[dropouts]` | **12/min** | Der einzige belegte hoerbare Punkt liegt bei 12,94 und 12,99/min (zwei unabhaengige Laeufe, Uebereinstimmung 0,4 %). **12 statt 13**, weil die Rate ohne Nachkommastelle gefuehrt wird und ein Fenster bei 12,94/min ebenso gut 12 wie 13 zaehlt — bei Schwelle 13 wuerde der belegte Fall in etwa der Haelfte der Fenster durchrutschen. **12 ist ausdruecklich keine Aussage darueber, dass 12/min hoerbar sind** (R-E); es ist die ganzzahlige Untergrenze des einen gemessenen Punktes. |
| `LOSS_ALERT_RATE_PER_MIN[alle anderen Kanaele]` | **entfaellt bzw. offen** | `dropped`: bewusst keine (dasselbe Ereignis, siehe Rangfolge). `underflows`: **bewusst keine** — es existiert kein gemessener gestoerter Wert, der Kanal war im kaputten Zustand null. `app/mixer underruns`: offen, M-1. |
| `LOSS_ALERT_SUSTAINED_WINDOWS` | **2**, mit Mindestabstand `LOSS_WINDOW_MS / 4` = 15 s zwischen zwei gezaehlten Auswertungen | Ohne Mindestabstand waere „zwei aufeinanderfolgende Auswertungen" bei gleitendem Fenster und 2-s-Poll fast dieselbe Lesung zweimal. Mit 15 s muss die Rate eine Viertelstunde eines Fensters durchhalten. Belegt gegen: die einzige gemessene Stoerung hielt 97 s im kontrollierten Arm und ueber zwoelf Minuten unkontrolliert durch; im gesamten Normalbetrieb (514 s) gab es kein einziges Ereignis. Zwischen 15 s und 97 s liegt kein gemessener Fall — der Wert sitzt in einer leeren Spanne, nicht an einem Rand. |
| `LOSS_CLEAR_RATE_PER_MIN[dropped]`, `[dropouts]`, `[underflows]` | **0** | `NOTICE` ist 1/min, und unter 1/min gibt es in einem 60-s-Fenster nur die Null. Die Hysterese kann auf diesen Kanaelen konstruktionsbedingt nicht ueber die Rate laufen — sie laeuft ueber `LOSS_CLEAR_HOLD_MS`. Fuer die zwei Eingangskanaele **offen**, weil `NOTICE` dort offen ist. |
| `LOSS_CLEAR_HOLD_MS` | **35 000** | Zwei bindende Untergrenzen: `> LOSS_WINDOW_MS / 2` (= 30 s, Regel aus T-002) und ≥ 4× der laengste **innerhalb** einer laufenden Stoerung gemessene Ereignisabstand (7,68 s, aus dem schnellen 379-ms-Lauf, dem einzigen, der Abstaende ueberhaupt aufloest → 30,7 s). 35 s liegt ueber beiden. **Der Faktor 4 ist ein benannter Sicherheitszuschlag, keine Messung** — die Stichprobe hat 12 Abstaende, ihr Ausreisser-Ende ist unbeobachtet. Schliessen wuerde ihn M-6. |
| `SETTLE_AFTER_TRANSITION_MS` | **20 000** | Der eine gemessene Einschwingvorgang nach einem Moduswechsel (Pin geloest, HIGH → ABR) dauerte **18,5 s**, bis die Wechselrate von 13,00/min auf 3,08/min fiel. 20 s ist der naechste runde Wert darueber. **n = 1, und nur fuer einen Uebergangstyp** — Verbindungsaufbau, Codec-Wechsel und Playback-Start sind nicht gemessen. Schliessen wuerde es M-7. |
| `SETTLE_MAX_SPAN_MS` | **Formel gesetzt, Randfall offen** | Fuer einen **angemeldeten** Lauf (D-7 meldet „ich schalte N-mal um") ist der Deckel `SETTLE_AFTER_TRANSITION_MS × (N + 1)` — das braucht keine Messung, weil N bekannt ist. Fuer eine **nicht angemeldete** Kette (haengendes `busy`, Dauer-Umschalter von aussen) bleibt der absolute Deckel **offen**: er muss ueber dem laengsten legitimen Uebergangs-Cluster liegen, und wie oft ein unangetasteter Link echte Uebergaenge produziert, ist nicht gemessen (M-8). Bis dahin `TODO(M-8)`. Die Regel U-5 (Ablauf faellt nach `CANNOT_TELL`, nie nach `CLEAN`) gilt unveraendert. |
| `LOSS_EVENT_COOLDOWN_MS` | **600 000** (10 min) | Uebernahme des im Projekt bereits begruendeten `EncoderStarvation.CAPTURE_COOLDOWN_MS` — kein zweiter Wert fuer dasselbe Problem. Gegen die Messlage geprueft: die eine gemessene Episode hielt ueber zwoelf Minuten durch und ergibt damit **zwei** Eintraege, nicht neunzig. Das ist genau die Groessenordnung, fuer die die Konstante gebaut wurde. |
| `RATE_MIN_EVENTS_IN_WINDOW` | **10, unveraendert** | Zaehlstatistik, keine Geraetemessung. Steht seit T-002 fest. |

**Neue Parameter dieser Fortschreibung:**

| Parameter | Wert | Woraus |
|---|---|---|
| `LADDER_WINDOW_MS` | ~~**180 000**~~ → **60 000**, siehe Nachtrag, Selbstkorrektur eins | Bei den gemessenen 3,08–5,30 Wechseln/min enthaelt ein 3-min-Fenster 9–16 Wechsel und damit mehrere volle Zyklen (gemessene Verweildauern: 492 dreimal exakt 11 Samples ≈ 15,8 s; 660 zwischen 2,9 und 28,8 s). Ein 60-s-Fenster enthaelt haeufig **eine** Verweildauer und wuerde „100 % at 492" melden — richtig fuer das Fenster, irrefuehrend ueber die Strecke. Anteile sind ein Zeitverhaeltnis, keine Ereignisrate, deshalb greift `RATE_MIN_EVENTS_IN_WINDOW` hier nicht. |
| `LADDER_SETTLING_RATE_PER_MIN` | ~~**8**~~ — **zurueckgezogen**, ersetzt durch `LADDER_SETTLING_MIN_DISTINCT_STEPS` = 3 im 20-s-Teilfenster, siehe Nachtrag, Selbstkorrektur zwei | Gemessene Ruhewerte: 3,69 (A0), 3,08 (A' eingeschwungen), 5,30 (T-007 Lauf B). Gemessener Einschwingwert: 13,00 (A' Samples 0–13). 8 liegt 1,5× ueber dem hoechsten Ruhewert und 1,6× unter dem Einschwingwert — mit Abstand zu beiden, an keinem Rand. **Schmale Basis: drei Ruhewerte, ein Einschwingwert.** Schliessen wuerde es M-9. Dies ist eine Klassengrenze zwischen zwei gemessenen Haufen, **keine** Interpolation einer Wahrnehmungsschwelle — der Unterschied zu R-E ist, dass hier beide Seiten gemessen sind und keine Aussage ueber den Klang getroffen wird. |
| `LADDER_REJECTED_MAX_READINGS` | **1** | Der belegte Fall stand in genau einer Lesung. Der Wert ist an die Poll-Kadenz gebunden, nicht an eine Zeit: bei 1,417 s Kadenz liegt die wahre Verweildauer zwischen 1 und 2 Intervallen. Deshalb die Ehrlichkeitsauflage „less than {2 × Kadenz}". Aufloesen wuerde das M-10. |
| `LADDER_QUEUE_PRESSURE_FRACTION` | **0,20** (20 % der Lesungen im Fenster mit Queue > 0) | Gemessene Ruhewerte: 1/70 = 1,43 % (A0), 0/70 = 0 % (A'), 2/262 = 0,76 % (T-007). Gemessene Ueberlastwerte: 55/70 = 79 % und 129/160 = 81 %. 20 % liegt **14×** ueber dem hoechsten Ruhewert und **4×** unter dem niedrigsten Ueberlastwert. Das ist der am besten belegte Schwellenwert dieser gesamten Vorgabe. |

**Regel fuer den `developer`, praezisiert:** Jeder hier gesetzte Wert bekommt
KDoc, das **die Messung nennt, auf der er ruht**, mit Datei und Arm (z. B.
„T-008 Arm B, 21 Dropouts / 97,0 s, zwei unabhaengige Laeufe"). Jeder offene
Wert traegt `TODO(M-x)` mit **der** Messung, die ihn schliesst — nicht mehr
`TODO(T-001)` pauschal. Ein Wert ohne Herkunft im KDoc ist ein Fehler, auch
wenn die Zahl stimmt.

---

### Aktion „Helper beenden und aufraeumen" (AD-011)

Der App Designer hat die Aktion beschlossen; `ARCHITECTURE.md` AD-011 legt
fest, was sie technisch leisten darf, und ueberlaesst mir ausdruecklich Ort und
Darstellung.

**Zweck & Nutzerziel:** „Ich will, dass von dieser App nichts mehr laeuft und
nichts mehr herumliegt — und ich will wissen, was davon wirklich passiert ist."

**Ort: `SettingsScreen` → Panel „System access"**, direkt unter der
vorhandenen `StatusRow("App helper", …)` (`SettingsScreen.kt:166`). Begruendung:

- Das Panel traegt bereits den Zustand des Helpers und den einzigen anderen
  Satz ueber seinen Lebenszyklus („The equalizer keeps running after you close
  the app."). Die Aktion gehoert neben ihren Gegenstand.
- **Nicht** auf dem Monitor-Bildschirm: sie beendet genau das, was der Monitor
  braucht. Ein Knopf, der die Anzeige ueber sich selbst abschaltet, ist eine
  Falle.
- **Nicht** im Setup-Assistenten: der baut auf, diese Aktion baut ab.
- **Nirgends automatisch.** AD-011 nennt die Aktion ausdruecklich Absicherung
  und nicht Hauptweg; ein Mitlaufen beim App-Start waere der schlechtere
  Hauptweg.

**Bedienelemente, alle aus dem Bestand** (`GoldOutlinedButton`, `StatusRow`,
`Pill`/`PillTone`, `PanelDivider`, `ExplainedRow`, `AlertDialog` wie in
`PreferenceTestScreen`). **Kein neues Token, keine neue Komponente.**

~~**Ein Knopf, zwei Beschriftungen**~~ — **ueberholt durch Entscheidung 3, siehe
Nachtrag.** Es gilt: **ein Knopf, eine Beschriftung, "Stop the helper"**, und er
erscheint nur bei verbundenem Helper. Ohne verbundenen Helper gibt es keine
Aktion, sondern die Standzeile. Die folgende Tabelle steht als Protokoll:

| Lage | ~~Beschriftung~~ | ~~Was passiert~~ |
|---|---|---|
| Helper verbunden | ~~"Stop the helper and clean up"~~ → **"Stop the helper"** | Bestaetigung → beenden (Helper raeumt dabei auf) → nachmessen |
| kein Helper verbunden | ~~"Check for leftovers"~~ → **kein Knopf** | Standzeile statt Aktion |

**Bestaetigungsdialog** (nur im ersten Fall, weil die Folge nicht in der App
rueckgaengig zu machen ist — der Helper kommt nur ueber den ADB-Befehl
zurueck):

- Titel: "Stop the helper?"
- Text, zwei Saetze: "The helper stops now, and everything that needs it — the
  system EQ and the live monitor — stops with it until you run the ADB command
  again. It removes the files it can name by itself first."
- Knoepfe: "Stop and clean up" / "Cancel".

**Der Aussagerahmen — genau drei Stufen, immer alle drei sichtbar, nie eine
Zusammenfassung darueber.** Eine Zeile je Stufe, jede mit eigenem `Pill`:

| # | Zeile | Pill | Satz (Englisch, verbindlich) |
|---|---|---|---|
| 1 | "Helper stopped" | "Observed" / "Not observed" | Observed: "The connection to the helper died, which this app saw for itself." — Not observed: "The helper did not go away within {T}, so nothing was removed." |
| 2 | je bekanntem Dateinamen eine Zeile | "Gone" / "Still there" / "Cannot check" | Gone: "Checked by name after the helper stopped: it is not there." — Still there: "Checked by name: it is still there." — Cannot check: "This app could not check the name, so it says nothing about the file." |
| 3 | "Files from older builds" | **"Cannot check"**, dauerhaft und unaenderbar | "Older builds wrote files under names this app cannot know, and it cannot list the folder. This line says nothing about whether any are there." |

**Regeln, die diesen Rahmen tragen:**

- **Nur nachgemessen, nie gemeldet.** Keine Zeile gibt wieder, was der Helper
  getan zu haben behauptet. Stufe 1 ist der beobachtete Binder-Tod, Stufe 2 ist
  ein Oeffnungsversuch der App selbst. Das ist dieselbe Read-back-Regel, die im
  Projekt fuer jede Aenderung gilt.
- **Stufe 3 ist nicht wegklickbar, nicht einklappbar und nicht abhaengig vom
  Ergebnis.** Sie steht auch dann da, wenn 1 und 2 beide „Observed"/„Gone"
  sagen. Genau dort waere die Versuchung, sie wegzulassen, und genau dort waere
  es der falsche Freispruch.
- **Keine Summenzeile, kein Gesamt-Pill, keine Fortschrittshaken.** Es gibt
  keinen Satz, der die drei Stufen zu einem Urteil verrechnet.
- **Verboten als Zeichenkette:** "clean", "all clean", "cleaned up", "clean-up
  complete", "nothing left", "no leftovers", "the folder is empty", "fully
  removed", "all files removed", "done" als alleinstehende Zusammenfassung.
  **Verboten als Bild:** gruener Haken ueber der Gruppe, Daumen, jedes Symbol,
  das die drei Zeilen zusammenfasst.
- **Erlaubt** ist `PillTone.ACCENT` auf den Einzelzeilen 1 und 2 im Erfolgsfall
  — das sind direkte Beobachtungen ueber je einen Gegenstand, keine Urteile
  ueber das Verzeichnis. Zeile 3 traegt immer `PillTone.NEUTRAL`; sie ist keine
  Warnung und kein Erfolg, sie ist eine Wissensgrenze.
- **Kein totes Ende.** Bleibt eine Datei liegen oder war kein Helper da, nennt
  die zweite Ebene den vollstaendigen ADB-Befehl, mit dem der Nutzer sie selbst
  entfernt — dieselbe Darstellung, die der vorhandene Bootstrap-Befehl schon
  benutzt. Ebenso fuehrt der Weg zurueck: nach dem Beenden zeigt die
  vorhandene `StatusRow` "Not running", und der vorhandene Knopf "Open system
  access" ist der Weg zum Neustart.
- **Der Monitor darf danach nicht „ruhig" sagen.** Nach dem Beenden faellt die
  Coverage der Verlustanzeige auf `NONE` und damit auf `CANNOT_TELL` mit dem
  Grund „The helper is not running, so the loss counters cannot be read." — ein
  Ruecksprung auf `CLEAN` waere ein falscher Freispruch, ausgeloest durch eine
  Nutzeraktion.
- **Ergebnis bleibt stehen**, bis der Bildschirm verlassen wird, mit
  Zeitstempel ("Last cleanup — {Uhrzeit}"). Ein Ergebnis, das nach zwei
  Sekunden verschwindet, ist bei drei Zeilen nicht lesbar.

**Welche Namen in Stufe 2 auftauchen, legt diese Vorgabe nicht fest** — das ist
AD-011s Sache und aendert sich mit dem Transport aus T-006. Die Vorgabe
verlangt nur: **eine Zeile je Name, den die App tatsaechlich pruefen kann**, und
keine Zeile fuer einen Namen, den sie nur vermutet.

---

### Anforderungen an den Datenweg (Ergaenzung zu D-1..D-9)

- **D-10 Stufenverlauf im Fenster.** Der Aggregator braucht je Lesung
  `ldac.measuredKbps`, `ldac.isAdaptive` und `ldac.stack.savedTxQueueLength`
  (alle drei sind bereits im Snapshot) mit Zeitstempel, ueber
  `LADDER_WINDOW_MS`. Daraus: aktuelle Stufe, Verweildauer, Anteile an der
  gemessenen Zeit, Anzahl Wechsel, hoechste gesehene Stufe, Anteil der
  Lesungen mit Queue > 0. Reine Arithmetik auf vorhandenen Feldern.
- **D-11 Der ABR-Zaehler wird heute nicht gelesen.** `A2dpLinkDumpParser`
  kennt `LDAC quality mode`, `LDAC transmission bitrate (Kbps)`,
  `Effective MTU` und `LDAC saved transmit queue length`
  (`A2dpLinkDumpParser.kt:401-404`) — **nicht** die `LDAC adaptive bit rate`-
  Zeilen (Index und `adjustments`), die T-007 und T-008 durchgehend
  ausgewertet haben. Sie sind der direkteste Beleg fuer einen Stufenwechsel und
  unabhaengig von der Abtastung: der Zaehler zaehlt auch Wechsel, die zwischen
  zwei Lesungen liegen. Ohne ihn unterzaehlt die Wechselrate systematisch. Der
  Parser muss beide Zeilen aufnehmen; die exakte Schreibweise ist dem Dump zu
  entnehmen, nicht zu raten, und eine fehlende Zeile ergibt `null`, nie 0.
- **D-12 Verweildauer nur ueber luecklos gemessene Zeit.** Dieselbe Regel wie
  D-8 fuer das Alter: war das Panel zwischendurch zu, ist die Verweildauer
  nicht „seit 40 s", sondern nicht bestimmbar. Dann steht die Stufe ohne
  Dauer da.
- **D-13 Poll-Kadenz erreicht die Anzeige.** Fuer „less than {2 × Kadenz}"
  braucht die Stufenzeile die **gemessene** Kadenz (`A2dpTxDelta.windowMs` ist
  bereits die ehrliche Zahl), nicht das eingestellte Intervall.
- **D-14 Ergebnis der Aufraeum-Aktion als Datentyp, nicht als Text.** Drei
  Stufen mit je eigenem Evidenzzustand (`OBSERVED` / `NOT_OBSERVED` /
  `CANNOT_CHECK`), damit die Oberflaeche keine Zeichenketten parsen muss und
  ein Test ueber alle Kombinationen laufen kann. Stufe 3 ist ein Konstant-Wert
  `CANNOT_CHECK` **ohne** Konstruktor-Parameter — was nicht gesetzt werden
  kann, kann auch nicht versehentlich auf „ok" gesetzt werden.

### Messanforderungen

Unveraendert offen aus T-002: **M-1** (Ruheraten der Eingangskanaele, getrennt,
in Ereignissen pro Minute — Rang gesenkt, aber nicht erledigt), **M-2**
(Streuung dieser Raten), **M-3** (Dauer einer Codec-Neuverhandlung; **die
Untergrenze fuer `SETTLE_AFTER_TRANSITION_MS` ist jetzt gesetzt**, offen bleibt
die Gesamtdauer eines Optimize-Laufs).

**Teilweise beantwortet: M-4** (Ruherate je Bitratenstufe). Fuer **990 gepinnt**
liegt sie jetzt vor: 324,7/323,4 `dropped`/min und 12,99/12,94 `dropouts`/min in
zwei unabhaengigen Laeufen. Fuer **330 und 660 gepinnt** weiterhin offen — und
das ist die Messung, die entscheidet, ob Schwellen je Stufe ueberhaupt noetig
sind.

Neu, jede mit dem Parameter, den sie schliesst:

- **M-5 → `LOSS_NOTICE_RATE_PER_MIN[Stack-Kanaele]`.** Ruherate von `dropped`
  und `dropouts` ueber **≥ 30 min** ununterbrochener ABR-Wiedergabe. Heute ist
  die Null ueber 514 s belegt; die Dreierregel gibt daraus nur eine Obergrenze
  von 0,35/min. Liegt die Stundenrate darueber, erzeugt die Schwelle „ein
  Ereignis" gelegentliche Pills, und dann braucht sie eine Sustain-Bedingung
  statt einer hoeheren Zahl. **Diese Messung ist die einzige, die einen
  gesetzten Wert wieder umwerfen kann.**
- **M-6 → `LOSS_CLEAR_HOLD_MS`.** Verteilung der Ereignisabstaende im
  gestoerten Arm ueber ≥ 10 min bei ≤ 400 ms Kadenz. Heute: 12 Abstaende,
  Median 4,69 s, Maximum 7,68 s. Gesucht ist das obere Ende, damit der
  Sicherheitsfaktor 4 durch einen Messwert ersetzt werden kann.
- **M-7 → `SETTLE_AFTER_TRANSITION_MS`.** Drei bis fuenf wiederholte
  Moduswechsel, je gemessen bis die Wechselrate auf Ruheniveau faellt. Heute:
  ein einziger Vorgang, 18,5 s.
- **M-8 → `SETTLE_MAX_SPAN_MS` (nicht angemeldeter Randfall).** Wie oft
  produziert ein unangetasteter Link pro Stunde echte Uebergaenge im Sinne von
  U-6 (Verbindung, Codec, Playback-Start/-Stopp, Moduswechsel)? Der Deckel muss
  ueber dem laengsten legitimen Cluster liegen.
- **M-9 → `LADDER_SETTLING_RATE_PER_MIN`.** Einschwingverhalten nach **anderen**
  Stoerungen als einem Moduswechsel: Kopfhoererwechsel, Verbindungsaufbau,
  Playback-Start, Entfernung vom Telefon. Heute: ein Einschwingwert gegen drei
  Ruhewerte.
- **M-10 → `LADDER_REJECTED_MAX_READINGS`.** Ein schneller Lauf (≤ 400 ms)
  **waehrend** einer Einschwingphase. Erst damit ist eine Verweildauer unter
  zwei Poll-Intervallen ueberhaupt aufloesbar; heute ist „1,4 s" in Wahrheit
  „zwischen 1,4 und 2,8 s".
- **M-11 → die Luecke der Hoerbarkeitsgrenze.** Drei bis vier Zwischenpunkte
  zwischen 0 und 13 Dropouts/min mit Hoereindruck. **Ehrlicher Vorbehalt:** Es
  ist unklar, ob ein Hebel existiert, der eine Dropout-Rate gezielt einstellt —
  gepinnt 990 gab genau einen Punkt, und 660/330 liefern voraussichtlich wieder
  null. Ohne einen solchen Hebel bleibt die Luecke dauerhaft offen, und dann
  bleibt R-E dauerhaft bindend. Das ist kein Mangel der Anzeige, sondern ihre
  Wahrheit.

---

### Aenderungen an bestehenden Akzeptanzkriterien

Angefasst wurden vier, alle uebrigen 19 gelten wortgleich weiter.

- **AK-T002-2 — praezisiert.** Der Satz gilt weiterhin fuer die **Verlustzeile**.
  Ergaenzt: Die **Stufenzeile** ist keine Verlustaussage; sie traegt in jedem
  Zustand eine Stufe und ab der zweiten Lesung eine Verweildauer, und das ist
  kein Verstoss gegen „keine Zahl ausser dem Fenster". Grund fuer die
  Praezisierung: ohne sie waere die neue Leitgroesse durch das alte Kriterium
  verboten.
- **AK-T002-12 — erweitert.** Zusaetzlich zu den bisherigen Verboten gilt R-E:
  fuer `dropouts`-Raten echt zwischen 0 und 12/min sind auch abstufende Woerter
  („minor", „slight", „barely", „probably inaudible", „should not be
  noticeable", „may be audible", „mild/moderate/severe") und jedes mehrstufige
  Bildzeichen verboten. Grund: die zwei Kalibrierpunkte decken den Bereich
  dazwischen nicht, und eine Abstufung ist eine Kurve durch zwei Punkte.
- **AK-T002-16 — erweitert.** Die Decke der zweiten Ebene muss jetzt **drei**
  Saetze tragen: Funk/jenseits der Antenne (bisher), „ein Zaehler auf null sagt
  nur etwas ueber diesen Zaehler" (neu, R-D) und „die Zaehler bleiben auch dann
  null, wenn der Regler stattdessen die Bitrate senkt" (neu). Grund: der zweite
  Satz ist am Geraet belegt, der dritte ist der Kern des Auftrags.
- **AK-T002-10 — praezisiert.** `TODO(T-001)` als pauschaler Marker entfaellt.
  Gesetzte Werte tragen ihre Messung im KDoc, offene tragen `TODO(M-x)` mit der
  konkreten Messung. Grund: „T-001" identifiziert nach dieser Fortschreibung
  nicht mehr, welche Messung fehlt.

**Ausdruecklich nicht geaendert** und weiterhin bindend: AK-T002-1, -3, -4, -5,
-6, -7, -8, -9, -11, -13, -14, -15, -17, -18, -19, -20, -21, -22, -23. Kein
Kurswechsel bei Geschmacksfragen: `CLEAN` ohne Pill, "Can't tell" als Wortwahl,
kein Selbstschalten der Nahaufnahme, kein „ich habe das gehoert"-Marker.

### Neue Akzeptanzkriterien

- **AK-T009-24** `Counts (underflow)` erzeugt an keiner Stelle der Oberflaeche
  allein einen `DISTURBED`-Zustand, und sein Wert 0 verhindert nirgends die
  Anzeige eines anderen Kanals (R-D). Pruefbar als Unit-Test mit einem
  Snapshot: `underflows = 0`, `dropouts = 21/97 s` → Zustand `DISTURBED`,
  Kanal „stack dropouts". **Das ist der Regressionstest gegen den Befund, dass
  die T-002-Fassung Arm B als einwandfrei gemeldet haette.**
- **AK-T009-25** Bei ruhenden Zaehlern und wandernder ABR-Stufe zeigt die
  Oberflaeche gleichzeitig „No counter moved in the last 60 s" **und** die
  gefahrene Stufe mit Verweildauer. Es existiert kein Satz, der beide zu einem
  Urteil verrechnet. Compose-Test gegen einen A0-aehnlichen Snapshot
  (492/660 wechselnd, alle Zaehler 0).
- **AK-T009-26** Die Stufenzeile nennt eine Verweildauer erst ab zwei luecklos
  gemessenen Lesungen und nie ueber eine Messluecke hinweg (D-12). Unit-Test
  mit einer kuenstlichen Luecke.
- **AK-T009-27** Das Ereignis „Stufe angesteuert und verworfen" wird nie mit
  einer exakten Verweildauer beziffert, sondern immer als "less than
  {2 × gemessene Kadenz}". Pruefbar per Grep gegen die Formatierung und als
  Unit-Test mit einer einzelnen Lesung auf 990.
- **AK-T009-28** Ein Stufenwechsel loest weder `SETTLING` (T-002) noch ein
  `DROPOUT`-Ereignis aus; ein „angesteuert und verworfen"-Ereignis erzeugt
  genau **einen** `EventLayer.DETAIL`-Eintrag. Unit-Test mit der Stufenfolge
  aus A' (396, 492, 660, 990, 492, …).
- **AK-T009-29** Die Zeile „Bluetooth is falling behind: {N} packets queued"
  existiert nicht mehr. Der Warteschlangendruck erscheint ausschliesslich als
  Anteil ueber ein Fenster, erst ab `LADDER_QUEUE_PRESSURE_FRACTION`, in der
  zweiten Ebene, ohne Fehlerfarbe unterhalb der Schwelle. Pruefbar per Grep und
  als Unit-Test mit 1/70 Lesungen ≠ 0 (keine Zeile) gegen 55/70 (Zeile).
- **AK-T009-30** Waehrend `LADDER_SETTLING` erscheint kein Stufenanteil in
  Prozent. Compose-Test gegen einen Snapshot mit 13 Wechseln/min.
- **AK-T009-31** Jeder gesetzte Parameter traegt im KDoc die Messung, auf der er
  ruht (Datei und Arm); jeder offene traegt `TODO(M-x)` mit genau einer
  benannten Messung. Pruefbar per Grep auf `TODO(T-001)` (muss verschwinden)
  und als Sichtpruefung im Review.
- **AK-T009-32** `EncoderStarvationTripwire` wird an keiner Stelle der
  Oberflaeche als Verlust- oder Stoerungsindikator gezeigt oder benannt.
  Pruefbar per Grep auf Verwendungen im `ui`-Baum.
- **AK-T009-33** Die Aktion „Helper beenden und aufraeumen" laeuft ausschliesslich
  auf eine Nutzeraktion. Kein Codepfad ruft sie aus `onCreate`, einem
  `LaunchedEffect` ohne Nutzerausloeser, einem Service, einem Timer oder einem
  Lifecycle-Ereignis. Pruefbar per Grep auf die Aufrufstellen — dasselbe Muster
  wie AK-T002-19.
- **AK-T009-34** Das Ergebnis der Aktion besteht aus genau drei Stufen, alle
  drei immer sichtbar, ohne Summenzeile und ohne Gesamt-Pill. Stufe 3 ist
  dauerhaft „Cannot check" und in keinem Zweig auf einen anderen Wert setzbar.
  Compose-Test ueber alle Ergebniskombinationen inklusive „alles beobachtet,
  alles fort".
- **AK-T009-35** Im Ergebnis der Aktion erscheint keine der verbotenen
  Zeichenketten ("clean", "all clean", "cleaned up", "clean-up complete",
  "nothing left", "no leftovers", "the folder is empty", "fully removed", "all
  files removed") und kein zusammenfassendes Bildzeichen. Pruefbar per Grep und
  Compose-Test.
- **AK-T009-36** Keine Ergebniszeile gibt einen Rueckgabewert oder eine
  Behauptung des Helpers wieder. Stufe 1 haengt am beobachteten Binder-Tod,
  Stufe 2 an einem Oeffnungsversuch der App. Pruefbar als Unit-Test: ein
  Helper, der „erledigt" meldet, aber weiterlebt, ergibt „Not observed".
- **AK-T009-37** Nach dem Beenden des Helpers steht die Verlustanzeige des
  Monitors auf `CANNOT_TELL` mit dem Helper-Grund, nie auf `CLEAN`. Unit-Test
  ueber den Zustandsuebergang.

### Ausdruecklich nicht Teil dieser Fortschreibung

- **Ob aus einem Befund eine Handlungsempfehlung wird.** Weder „pin nicht auf
  990" noch „schalte die Nearby-Scans ab" noch irgendein Vorschlag zur
  Verbesserung. Das haengt an T-005 und an offenen Nutzerentscheidungen.
- **Schwellen je Bitratenstufe.** M-4 ist erst fuer 990 beantwortet.
- **Die Ursache des Warteschlangenueberlaufs.** Funkstoerung, Empfangsgrenze
  des Hoerers oder schlicht zu hohe Rate sind nicht getrennt (T-008 Abschnitt
  8). Die Anzeige nennt den Zustand, nie die Ursache.
- **Ein Takt oder eine Periodizitaet in der Darstellung.** INCONCLUSIVE (2 von
  23 Lags marginal, was einer Mehrfachvergleichskorrektur nicht standhaelt).
  Es gibt keine Anzeige, die „alle 3 s" oder ein Intervall behauptet.
- **Die Technik des Aufraeumens**, die Namensform der Dateien und die
  Inode-Pruefung — AD-011, nicht diese Vorgabe.
- **Der Pin-Marker `Priority: 1000000`** aus T-008 Abschnitt 6. Das ist ein
  Handgriff des App Designers in den Entwickleroptionen, kein UI-Gegenstand.

### Offene Fragen an den App Designer

> **Alle vier sind beantwortet (App Designer, 2026-09-01, ueber den Director).**
> Die Antworten stehen im Nachtrag am Ende dieses Abschnitts und haben Vorrang;
> die Fragen bleiben als Protokoll stehen, damit ein spaeterer Durchlauf sie
> nicht erneut stellt (Stabilitaetsregel).

1. **Darf die Stufenzeile im Normalbetrieb ihre Verweildauer dauernd
   mitzaehlen?** Sie steht dann als laufende Sekundenzahl im Panel und
   aktualisiert sich mit jedem Poll. Das ist ehrlich und es ist Bewegung im
   Blickfeld. Alternative: die Verweildauer erscheint erst ab einer Schwelle
   („for over 30 s") und die Zeile ist sonst ruhig. **Reine Geschmacksfrage,
   beide Varianten sind gleich ehrlich** — ich habe die laufende Zahl gewaehlt,
   weil sie die neue Leitgroesse sichtbar macht, aber das ist kein Argument,
   das eine Entscheidung ersetzt.
2. **Soll die Aktion „Helper beenden und aufraeumen" auch dann sichtbar sein,
   wenn nie ein Helper lief?** Sie waere dort „Check for leftovers" und wuerde
   in aller Regel „Gone" und „Cannot check" melden. Dafuer spricht, dass Reste
   aus einer frueheren Installation genau dann liegen, wenn gerade nichts
   laeuft. Dagegen spricht ein Knopf, der bei den meisten Nutzern nie etwas
   tut. **Produktentscheidung.**
3. **Ist „Stop the helper and clean up" die richtige Beschriftung, oder soll
   das Beenden im Vordergrund stehen** („Stop the helper", Aufraeumen als
   Nebeneffekt im Dialogtext)? Der Unterschied ist, was der Nutzer sucht, wenn
   er das Panel oeffnet — Aufraeumen oder Abschalten. Ich habe beides in die
   Beschriftung genommen, weil beides passiert; wer die Aktion im Kopf nur als
   „aufraeumen" fuehrt, wird vom gestoppten Helper ueberrascht.
4. **Duerfen die zwei Kalibrierpunkte (0 und ~13 Dropouts/min) ueberhaupt in
   der App stehen?** Ich habe sie als Punkt 6 der zweiten Ebene vorgesehen —
   als Bericht ueber eine Messung an genau diesem Telefon mit genau diesem
   Kopfhoerer, nicht als Skala. Es ist der einzige Ort, an dem das Wort
   „audible" in der App vorkaeme. Wer das zu nah an einer Klangaussage findet,
   streicht den Punkt und verliert nichts an Funktion.

Was **nicht** offen ist: alle Parameterwerte oben ruhen auf Messungen oder auf
benannten, offenen Luecken. Es gibt keine Zahl in dieser Fortschreibung, die
auf eine Entscheidung des App Designers wartet.

---

## Nachtrag T-009: die vier Entscheidungen des App Designers (2026-09-01)

Alle vier offenen Fragen sind beantwortet. Zwei fallen anders aus als die
Vorlage — beide loesen ein Problem, das die Vorlage nur verwaltet hatte. Was
hier steht, hat **Vorrang** vor dem Abschnitt darueber, wo beides sich
widerspricht; der urspruengliche Text bleibt als Protokoll stehen.

### Entscheidung 1 — Die Bitratenstufe kommt auf den Graphen (Frage 1 aufgeloest)

Wortlaut: *„kbps als Bitratenlinie auf dem Graph waere spannend. Um so die
Spruenge zu sehen. Loest die zeitliche Achse."*

Das ist die bessere Antwort, weil sie die Frage aufloest statt sie zu
entscheiden: **Verweildauer, Wechselhaeufigkeit und die Unterscheidung
Einschwingen/Dauerzustand werden auf einer Zeitachse zur Form und muessen nicht
mehr als Zahl gelesen werden.** Lange Plateaus gegen nervoeses Springen sieht
man; „for 14 s" muss man mitrechnen.

**Befund vorab, damit niemand etwas Falsches baut: die Linie existiert bereits.**
`TracePoint.bitrateKbps` ist MEASURED und wird ueber `plotValue` schon heute vom
Ueberblicksgraphen gezeichnet (`LiveTraceModel.kt:30/45/202`), mit
Einheitenwechsel auf `packets/s` als Liveness-Fallback und einer bereits
richtigen Luecken-Regel (`breakBefore`, Zeile 137: Bruch bei fehlendem Wert oder
mehr als zwei Intervallen Abstand). **Es kommt also kein Graph dazu.** Die
vorhandene Linie wird richtig gezeichnet — und war bisher falsch gezeichnet,
weil sie ein Kontinuum behauptet, wo es eine Leiter gibt.

Damit ist auch die Frage „ergaenzt oder ersetzt" beantwortet: **weder noch.**
Der Graph bleibt einer, die Stufenzeile bleibt eine; die Zeile verliert nur die
laufende Verweildauer an den Graphen (siehe unten).

**G-1 Treppe, nicht Kurve.** Zwischen zwei Lesungen wird **waagerecht** gehalten
und dann **senkrecht** gesprungen. Keine schraege Verbindung, keine Bezier, kein
Spline. Begruendung: die Stufen sind diskret (gemessen 396, 492, 660, 990). Eine
schraege Linie von 492 nach 660 zeichnet Zwischenwerte, die es nicht gibt — das
ist derselbe AK-3-Verstoss wie eine interpolierte Hoerbarkeitskurve, nur mit
Pixeln statt mit Woertern.

**G-2 Keine Glaettung, keine Mittelung, keine Verdichtung.** Wenn mehr Lesungen
als Pixelspalten vorliegen, werden je Spalte **Minimum und Maximum** gezeichnet,
niemals ein Mittelwert. Begruendung: der aussagekraeftigste Einzelpunkt, den
dieses Projekt kennt, ist **eine einzige Lesung** — 990 kbps in Arm A' bei
t = 11,32 s, danach zwei Stufen tiefer und nie wieder. Ein Mittelwert ueber
diese Spalte loescht ihn. Eine Anzeige, die den einen Punkt wegglaettet, wegen
dem man sie gebaut hat, ist schlimmer als keine.

**G-3 Die Y-Achse ist eine Leiter, kein Kontinuum.** Achsenmarken stehen genau
auf: den **in diesem Fenster tatsaechlich beobachteten** Werten **plus** den
Nominalstufen der Abtastraten-Familie (990/660/330 bei 48 und 96 kHz,
909/606/303 bei 44,1 und 88,2 kHz — die Unterscheidung existiert bereits in
`LdacQuality.chipLabel`). **Ein beobachteter Wert, der auf keiner bekannten
Stufe liegt, wird gezeichnet und beschriftet wie er ist und niemals auf die
naechste Nominalstufe gerundet.** Begruendung: 396 und 492 sind gemessen und
stehen auf keiner der bekannten Leitern; `docs/state.md` fuehrt „Leiter fuer
96 kHz/32 bit unverstanden" als offenen Punkt. Eine Achse, die nur zeigt, was
sie erwartet hat, haette genau diesen offenen Punkt nie sichtbar gemacht.

**G-4 Die Luecke zwischen zwei Lesungen wird nicht als Wissen gezeichnet.** Bei
~2 s Kadenz ist ein Wechsel zwischen zwei Lesungen unsichtbar. Zwei Massnahmen,
beide notwendig:

1. **Jede Lesung traegt eine sichtbare Marke** auf der Treppe. Damit ist am Bild
   ablesbar, wo gemessen wurde und wo gehalten wurde — die Waagerechte zwischen
   zwei Marken ist erkennbar eine Annahme, keine Messung.
2. **Der ABR-Zaehler deckt auf, was die Abtastung verpasst hat.** `LDAC adaptive
   bit rate adjustments` zaehlt **jeden** Wechsel, auch die zwischen zwei
   Lesungen (D-11). Steigt der Zaehler um mehr, als an Stufenwechseln sichtbar
   ist, wird das betroffene Segment **als „enthaelt ungesehene Wechsel"
   markiert** (eigene Strichform, keine eigene Farbe — Graustufen-Regel aus
   AK-T002-3), und die Caption nennt die Zahl. **Das ist der Grund, warum D-11
   nicht optional ist:** ohne den Zaehler kann der Graph nur behaupten, luecklos
   zu sein, und untertreibt jede Wechselhaeufigkeit systematisch.

**G-5 Nicht gemessene Spannen bleiben leer.** Die vorhandene `breakBefore`-Regel
bleibt unveraendert und ist der Praezedenzfall: kein Wert oder mehr als zwei
erwartete Intervalle Abstand ⟹ die Linie bricht. Eine Treppe, die ueber eine
Messluecke durchhaelt, macht aus „niemand hat hingesehen" ein „es war stabil" —
und das ist die Luege, die das Auge sofort glaubt.

**G-6 Das Ereignis „angesteuert und verworfen" bekommt eine eigene Marke.**
Gleiche Geometrie wie die `SETTLING`-Marke aus T-002 (`colorScheme.outline`),
damit eine Spitze von einer Lesung Breite auch bei einem Pixel Spaltenbreite
sichtbar bleibt. Sie ist keine Verlustmarke und wird nie als solche gezaehlt.

**G-7 Die Nahaufnahme folgt denselben Regeln.** Treppe, Marken, keine
Glaettung. Bei 500 ms Kadenz ist sie das einzige vorhandene Mittel, eine
Verweildauer unter zwei Ueberblicks-Intervallen ueberhaupt zu sehen — sie
**ersetzt M-10 aber nicht**, weil sie eine Nutzeraktion ist und beim Ereignis
schon laufen muesste.

**G-8 Caption, ergaenzt.** Zusaetzlich zu „{k} of {n} windows lost something"
und „{m} not measured" (T-002, unveraendert): „{c} step changes" und, wenn der
ABR-Zaehler mehr meldet, „{u} more the readings did not show". Plus einmal die
Aufloesung: „one reading every ~{Kadenz}". Alles Zahlen ueber gemessene Dinge,
kein Urteil.

**Folge fuer die Stufenzeile: die laufende Verweildauer entfaellt.** Die Zeile
fuer `LADDER_STEADY` lautet wieder wie der vorhandene String — „Adaptive —
{X} kbps right now (measured)" — und der Graph darunter sagt, wie lange das
schon so ist. Damit ist AK-T009-26 (Verweildauer nur ueber luecklos gemessene
Zeit) gegenstandslos in der Zeile und gilt stattdessen fuer die Treppe: **G-5
ist seine neue Form.**

**Selbstkorrektur eins: `LADDER_WINDOW_MS` 180 000 → 60 000.** Meine Begruendung
fuer 3 Minuten war, ein 60-s-Fenster koenne „100 % at 492" melden. **Das war
falsch gerechnet.** Die gemessenen Verweildauern sind 15,8 s (492, dreimal
exakt) und 2,9–28,8 s (660); bei 3,08–5,30 Wechseln/min enthaelt ein
60-s-Fenster 4–6 Verweildauern, also mehrere volle Zyklen. Ein Ein-Stufen-Fenster
ist im gemessenen Regime praktisch ausgeschlossen. **60 s stellt die
T-002-Regel wieder her, dass Zeile, Graph und Verlustfenster dasselbe Fenster
meinen** — was jetzt, wo Zeile und Graph nebeneinander stehen, keine Kosmetik
mehr ist, sondern Voraussetzung dafuer, dass man sie vergleichen darf.

**Selbstkorrektur zwei: `LADDER_SETTLING_RATE_PER_MIN` = 8 wird
zurueckgezogen.** Neuer Grund, nicht Geschmack: Bei einem **gleitenden**
60-s-Fenster bleiben die 13 Wechsel einer 18,5 s langen Einschwingphase eine
volle Minute im Fenster. Die Rate steht dann noch ~40 s nach dem Ende des
Einschwingens ueber der Schwelle — die Anzeige wuerde ein 18,5-s-Ereignis rund
dreimal so lang melden, wie es dauert. Ersatz, direkt aus den Messwerten:

| Ersatz | Wert | Woraus |
|---|---|---|
| `LADDER_SETTLING_MIN_DISTINCT_STEPS` | **3** | Einschwingen beruehrte **vier** verschiedene Stufen (396, 492, 660, 990) in 18,5 s. Jeder gemessene Dauerzustand beruehrte **genau zwei** (492/660): A0 ueber 97,6 s, A' ueber 77,9 s, T-007 Lauf B ueber 101,0 s. Drei liegt zwischen zwei gemessenen Werten mit Abstand nach beiden Seiten und braucht keine Ratenschaetzung aus kleinem k. |
| `LADDER_SETTLING_SUBWINDOW_MS` | **20 000** | Gleich `SETTLE_AFTER_TRANSITION_MS`, aus demselben einen gemessenen Vorgang (18,5 s). Ein Teilfenster statt des vollen Fensters, damit das Ereignis mit sich selbst endet statt mit dem Fenster. |

**Was diese Regel nicht faengt, ausdruecklich:** ein schnelles Pendeln zwischen
**zwei** benachbarten Stufen. Das ist nie gemessen worden — es gibt keinen
Beleg, dass es vorkommt, und keinen, dass es nicht vorkommt. **M-9 wird
entsprechend erweitert:** die Einschwingvorgaenge sind auch daraufhin
auszuwerten, ob sie immer mehr als zwei Stufen beruehren. Bis dahin ist diese
Erkennungsluecke benannt und nicht geschlossen.

### Entscheidung 2 — Die Aufraeum-Moeglichkeit erscheint nur, wenn schon einmal ein Helper lief

Umgesetzt als Sichtbarkeitsbedingung mit **drei** Zweigen, weil ein einzelnes
gemerktes Flag genau im wichtigsten Fall blind waere:

1. ein Helper ist **jetzt** verbunden, **oder**
2. in **dieser Installation** lief schon einmal einer (gemerkter Zustand), **oder**
3. ein **bekannter** Restdateiname laesst sich oeffnen — dieselbe Faehigkeit,
   die SR-001 ueberhaupt erst zum Befund macht, hier einmal nuetzlich.

Zweig 3 ist nicht Bequemlichkeit, sondern schliesst eine Luecke: Nach einer
**Neuinstallation** ist der gemerkte Zustand aus Zweig 2 fort, waehrend die
Reste im `/data/local/tmp` liegenbleiben — genau die Lage, in der die Aktion
gebraucht wird. Zweig 3 stellt sie wieder her, und zwar durch eine Beobachtung
statt durch ein Gedaechtnis.

**Restfall, den auch das nicht deckt, und der bleibt:** Ein Nutzer, der neu
installiert hat und **ausschliesslich** Reste unter Namen aelterer Versionen
besitzt, sieht nichts — kein Helper verbunden, kein Gedaechtnis, kein bekannter
Name. Das ist derselbe Restfall, den Stufe 3 des Aussagerahmens dauerhaft
benennt, nur dass hier auch die Zeile fehlt, die ihn benennen wuerde. Die
Alternative waere „immer sichtbar" gewesen, und die ist entschieden. **Der
Restfall wird nicht wegdefiniert, er wird hier festgehalten** und gehoert als
bekannte Grenze in die Uebergabe an den `architect` (AD-011, R1-Familie).

### Entscheidung 3 — Der Knopf heisst „Stop the helper", das Aufraeumen ist integriert

Wortlaut: *„aufraeumen soll generell integriert sein."* Die eigenstaendige
Nutzeraktion „Helper beenden und aufraeumen" entfaellt als **Aktion**; das
Aufraeumen laeuft, wo AD-011 es ohnehin vorsieht (Helper-Start, geordnetes
Ende, Paket-fort-Pfad). Der Nutzer muss nichts finden.

Was sich dadurch aendert:

- **Beschriftung: "Stop the helper".** Kein zweiter Satz im Knopf. Der
  Bestaetigungsdialog bleibt und nennt beides, weil beides passiert: Titel
  "Stop the helper?", Text zwei Saetze — "The helper stops now, and everything
  that needs it — the system EQ and the live monitor — stops with it until you
  run the ADB command again. It removes the files it can name on its way out."
  Knoepfe "Stop the helper" / "Cancel".
- **Die zweite Beschriftung „Check for leftovers" entfaellt** ersatzlos. Ohne
  verbundenen Helper gibt es keine Aktion mehr, sondern eine **Standzeile**
  (siehe naechster Punkt).
- **Der dreistufige Aussagerahmen bleibt vollstaendig gueltig** — er beschreibt
  jetzt, was die App **nach dem Beenden berichtet**, nicht das Ergebnis einer
  eigens angestossenen Aufraeumaktion. Stufe 1 (beobachteter Binder-Tod), Stufe 2
  (je bekanntem Namen, am eigenen Oeffnungsversuch), Stufe 3 (dauerhaft „Cannot
  check"). Alle Ehrlichkeitsregeln unveraendert: keine Summenzeile, kein
  Gesamt-Pill, verbotene Zeichenketten unveraendert, Monitor danach auf
  `CANNOT_TELL`.
- **Neu, und der eigentliche Gehalt von „generell integriert": Stufe 3 wird zur
  Standzeile.** Sie haengt nicht mehr an einer Aktion, sondern steht im Panel,
  solange die Sichtbarkeitsbedingung aus Entscheidung 2 erfuellt ist — mit
  ihrem `PillTone.NEUTRAL` und ihrem Satz, auch wenn der Nutzer nie etwas
  drueckt. Begruendung: Eine Wissensgrenze, die man erst durch eine Aktion zu
  sehen bekommt, ist als Wissensgrenze nichts wert.
- **Eine Standzeile ueber das Verfahren, ein Satz**, in der zweiten Ebene des
  Panels: "The helper removes the files it can name whenever it starts and when
  it stops." Sie ersetzt jede Aufforderung, etwas anzustossen.
- **Kein Ergebnisbericht bei Helper-Start.** Das Aufraeumen dort laeuft
  wortlos. Ein Bericht ueber eine Arbeit, die der Nutzer nicht ausgeloest hat,
  waere Laerm — und beim Start hat die App den Binder-Tod aus Stufe 1 gar nicht
  zu beobachten.

### Entscheidung 4 — Die Kalibrierpunkte kommen nicht in die App, und sie sind konfundiert

Wortlaut: *„Ich hatte nur bei weniger Bitrate null. Ich weiss nicht, ob das
hilft."*

**Der Einwand ist ein methodischer Befund, und er ist richtig.** Unsere beiden
Punkte unterscheiden sich nicht nur in der Dropout-Rate, sondern **gleichzeitig
in der Bitratenstufe**: 0 Dropouts gab es ausschliesslich bei 492/660,
~13 Dropouts ausschliesslich bei 990. Die Punkte sind **konfundiert**. Belegt
ist damit: **„990 gepinnt klingt auf dieser Strecke kaputt, adaptiv nicht."**
Nicht belegt ist: „13 Dropouts pro Minute sind hoerbar, unabhaengig von der
Stufe."

Folgen, alle eingearbeitet:

- **Punkt 6 der zweiten Ebene entfaellt ersatzlos.** Die Kalibrierpunkte
  erscheinen nirgends in der App. Damit kommt das Wort „audible" in der
  gesamten Oberflaeche nicht mehr vor — was die Durchsetzung von R-C und R-E
  von einer Textpruefung zu einer Grep-Regel macht.
- **R-E wird staerker begruendet, nicht schwaecher.** Bisher: zwei Punkte, keine
  Kurve. Jetzt zusaetzlich: **die zwei Punkte tragen eine zweite Variable mit
  sich.** Selbst eine Gerade durch beide waere nicht nur unbelegt, sie waere
  ueber zwei Groessen gleichzeitig gezogen. R-E gilt unveraendert und ist damit
  besser begruendet als bei seiner Einfuehrung.
- **`LOSS_ALERT_RATE_PER_MIN[dropouts]` = 12/min bleibt** — es ist die
  bestbelegte Alarmschwelle, die dieses Projekt hat, und sie irrt allenfalls in
  die vorsichtige Richtung (sie schlaegt eher zu spaet als zu frueh an, und sie
  behauptet nichts ueber Klang). **In ihre Begruendung im KDoc gehoert der
  Vorbehalt:** der Wert stammt aus einem Punktepaar, in dem Rate und
  Bitratenstufe zusammen variiert haben.
- **M-11 wird wichtiger und praeziser.** Zwischenpunkte muessen bei **gleicher
  Stufe** erhoben werden, sonst messen sie wieder zwei Dinge auf einmal. Das
  verschaerft den bereits benannten Vorbehalt: der einzige bekannte Hebel, der
  ueberhaupt Dropouts erzeugt, ist das Pinnen auf 990 — und der veraendert die
  Stufe per Konstruktion. **Es ist derzeit kein Verfahren bekannt, mit dem M-11
  sauber messbar waere.** Wer eines findet, schliesst die Luecke; bis dahin ist
  R-E nicht vorlaeufig, sondern dauerhaft.

### Aenderungen an den Akzeptanzkriterien dieses Abschnitts

**Zurueckgezogen:**

- **AK-T009-26** (Verweildauer in der Stufenzeile) — gegenstandslos, die Zeile
  traegt keine Verweildauer mehr. Die Sache selbst ist nicht verloren: sie
  gilt jetzt als **AK-T009-40** fuer die Treppe.

**Geaendert:**

- **AK-T009-27** — „less than {2 × Kadenz}" gilt weiterhin fuer den
  Log-Eintrag und die zweite Ebene. Im Graphen tritt an seine Stelle die Marke
  aus G-6; eine Zahl steht dort nicht.
- **AK-T009-30** — `LADDER_SETTLING` wird nicht mehr ueber eine Wechselrate
  geprueft, sondern ueber `LADDER_SETTLING_MIN_DISTINCT_STEPS` im
  Teilfenster. Der Testfall wird: vier verschiedene Stufen in 20 s ⟹ kein
  Stufenanteil in Prozent; zwei Stufen ueber 90 s ⟹ Anteile erscheinen.
- **AK-T009-33** — die Aktion heisst „Stop the helper" und laeuft weiterhin
  ausschliesslich auf Nutzeraktion. Zusaetzlich pruefbar: das Aufraeumen selbst
  haengt **nicht** an dieser Aktion (Entscheidung 3), also darf kein Codepfad
  das Aufraeumen nur hier ausloesen.
- **AK-T009-34** — Stufe 3 ist jetzt eine **Standzeile**: sie ist auch dann
  sichtbar, wenn nie eine Aktion ausgeloest wurde, solange das Panel die
  Sichtbarkeitsbedingung erfuellt. Compose-Test entsprechend erweitert.

**Neu:**

- **AK-T009-38** Die Bitratenspur wird als Treppe gezeichnet: zwischen zwei
  Lesungen ausschliesslich waagerecht, am Wechsel ausschliesslich senkrecht. Es
  existiert kein schraeges Segment und keine Glaettungsfunktion auf der
  Stufenspur. Pruefbar per Grep auf Interpolations-/Spline-Aufrufe im
  Graph-Pfad und als Pixel-/Pfadtest ueber die Wertefolge 492 → 660.
- **AK-T009-39** Eine Stufe, die in genau **einer** Lesung stand, ist im
  Graphen sichtbar — auch dann, wenn mehr Lesungen als Pixelspalten vorliegen.
  Je Spalte werden Minimum und Maximum gezeichnet, nie ein Mittelwert. Unit-Test
  mit der Wertefolge aus Arm A' (…, 660, **990**, 492, …) bei kuenstlich
  verdichteter Spaltenzahl. **Das ist der Regressionstest gegen das
  Wegglaetten des aussagekraeftigsten gemessenen Punktes.**
- **AK-T009-40** Der Graph zeichnet keine Treppe ueber eine Messluecke: die
  vorhandene `breakBefore`-Regel bleibt wirksam, und jede Lesung traegt eine
  eigene Marke. Unit-Test mit einer kuenstlichen Luecke von mehr als zwei
  erwarteten Intervallen.
- **AK-T009-41** Meldet der ABR-Zaehler mehr Wechsel, als an Stufenwechseln
  sichtbar sind, wird das betroffene Segment als „enthaelt ungesehene Wechsel"
  markiert und die Caption nennt die Zahl. Die Markierung ist ohne
  Farbwahrnehmung erkennbar (Strichform, nicht Farbe). Unit-Test:
  `adjustments` +3 bei einem sichtbaren Wechsel ⟹ „2 more the readings did not
  show".
- **AK-T009-42** Ein gemessener Bitratenwert, der auf keiner bekannten
  Nominalstufe liegt, wird gezeichnet und beschriftet wie gemessen und niemals
  gerundet oder eingerastet. Unit-Test mit 396 und 492 gegen die
  48/96-kHz-Leiter.
- **AK-T009-43** Die Wortfamilie „audible/audibly/audibility" und die
  Zahlen der beiden Kalibrierpunkte kommen auf den Bluetooth-Link- und
  Verlust-Oberflaechen der App nicht vor (Entscheidung 4). Pruefbar per
  Grep gegen `ui/screens/monitor` und die dort verwendeten
  Stringressourcen.

  **Praezisiert 2026-09-02, nach Rueckfrage des Coordinators (zwei
  Aenderungen, beide begruendet):**
  1. **Wortfamilie statt Zeichenkette.** Der urspruengliche Wortlaut
     „die Zeichenkette 'audible'" liess „audibly" durchgreppen — belegt am
     Fund des `developer` in T-017. Verboten ist die *Aussage*, dass eine
     gezaehlte Rate hoerbar sei; das gilt fuer jede Beugung des Worts.
     Grund unveraendert: die zwei Kalibrierpunkte sind konfundiert (0
     Dropouts nur bei 492/660 kbps, 12,94–12,99/min nur bei 990 kbps
     gepinnt) — belegt ist „990 gepinnt klingt kaputt", nicht „diese Rate
     ist hoerbar".
  2. **Scope auf Monitor/Link-Oberflaechen eingegrenzt, nicht app-weit.**
     Der urspruengliche Wortlaut „in keiner Oberflaeche der App" war zu
     weit gefasst. Der EQ-Screen (`ui/screens/eq/CompensationSection.kt`)
     verwendet „audible" an drei Stellen fuer die Hoerkompensation
     (Loudness-Restoration, per Text selbst: „real listeners tested
     against the prescription"). Das ist eine andere Aussage mit einer
     anderen Beleglage — sie behauptet nichts ueber einen BT-Verlust-
     Zaehler und traegt nicht den Konfundierer, den Entscheidung 4 aus
     T-009 verhindern soll. Diese Vorgabe (T-002/T-009) behandelt die
     Verlust- und Link-Anzeige; sie legt der Hoerkompensation keine Regel
     auf. Ob die EQ-Formulierungen selbst belegt sind, ist eine eigene
     Frage und nicht Teil dieser Pruefung.
- **AK-T009-44** Das Panel mit Helper-Aktion und Standzeile erscheint nur, wenn
  einer der drei Zweige aus Entscheidung 2 zutrifft, und verschwindet sonst
  vollstaendig. Unit-Test ueber alle acht Kombinationen der drei Zweige.

### Offene Fragen an den App Designer

Keine. Alle vier Fragen sind beantwortet und eingearbeitet.

**Was offen bleibt, sind wieder Messungen, keine Entscheidungen** — und zwei
davon sind durch diesen Nachtrag schwerer geworden statt leichter:

- **M-11** (Zwischenpunkte der Hoerbarkeit) braucht Punkte bei **gleicher**
  Stufe, und dafuer ist derzeit kein Verfahren bekannt. Solange keines
  gefunden ist, ist R-E dauerhaft und nicht vorlaeufig.
- **M-9** (Einschwingen) traegt jetzt zusaetzlich die Frage, ob ein
  Einschwingvorgang immer mehr als zwei Stufen beruehrt — sonst hat
  `LADDER_SETTLING_MIN_DISTINCT_STEPS` eine benannte Erkennungsluecke.
- **D-11** (die `LDAC adaptive bit rate`-Zeilen im Parser) ist durch G-4 von
  „waere genauer" zu **notwendig** geworden: ohne den Zaehler kann der Graph
  nicht sagen, was er nicht gesehen hat.

---

## Nachtrag T-017: Wortlaut der Underflow-Zeile (Review, 2026-09-02)

Der `developer` hat beim Fix von QA-001 eine neue Zeile unter `LossRow`
eingefuehrt, ohne dass dafuer eine Vorgabe existierte (`Counts (underflow)`
kam bisher ausschliesslich in der roten Verlustzeile vor; sie dort zu
streichen haette den Zaehler ganz von der Oberflaeche genommen und
`GOAL.md` AK-2 verletzt). Diese Entscheidung schliesst die Luecke, im
`ui-ux-designer`-Review-Modus (`T-017`), am Quelltext gepruft
(unverifiziert — kein Geraet).

**Befund:** `LossRow` (`LiveLinkPanel.kt:354-361`) zeigt im typischen
Ruhefall zwei Zeilen uebereinander:

    No loss this window.
    3 encoder underflows in the last 2 s.

Beide in `bodySmall`/`onSurfaceVariant` — das ist die richtige Stufe, es
entsteht **keine** neue Betonungsstufe zwischen „normal" und „Fehler", und
R-E ist eingehalten (kein abstufendes Wort im aktuellen String). Das
verbleibende Problem ist die **Lesbarkeit im Kontext**: das Wort
„underflow" klingt fuer eine Leserin, die die Kanalrangfolge aus T-009
nicht kennt, nach Defekt — direkt unter einer Zeile, die Entwarnung gibt.
Genau dieses Muster (eine Zahl, die bei tadelloser Verbindung nach Schaden
aussieht) war der Ausloeser von T-002 selbst.

**Entscheidung:**

1. **Satzform bleibt, keine Kurzschreibweise.** Der Alternativvorschlag des
   `developer` („Encoder underflows: 3 (2 s)") wird **abgelehnt**: kein
   anderer Wert in diesem Panel wird als Doppelpunkt-Klammer-Paar notiert,
   jede Zeile ist ein vollstaendiger Satz. Eine einzelne Ausnahme waere ein
   sechster Stil in einem Panel, das bereits drei Ebenen (MEASURED,
   NOMINAL, UNAVAILABLE) konsequent in Prosa haelt.
2. **Die Zeile wird auf `ExplainedRow` umgestellt** (Bestand, bereits in
   derselben Datei fuer die LDAC-Zeile verwendet — kein neues Token, keine
   neue Komponente). Erste Ebene unveraendert: **„{N} encoder underflow(s)
   in the last {W} s."** Neu ist die Erklaerung hinter dem Fragezeichen:
   **„This count carries no verdict, in either direction: the same counter
   stayed at zero on a link where stack dropouts ran throughout, and it
   climbed here through 39 minutes of playback with nothing else wrong."**
   — eine Umformulierung des bereits beschlossenen R-D-Satzes aus der
   zweiten Ebene der Verlustzeile, nicht neu erfunden.

   **Korrektur 2026-09-02, nach Rueckfrage des Coordinators:** Der erste
   Entwurf sagte „on a link that was audibly breaking up". Der `developer`
   hat zu Recht gemeldet, dass „audibly" den Grep von AK-T009-43 (verbotene
   Zeichenkette „audible") nicht trifft. Der Coordinator hat entschieden
   und ich teile die Entscheidung: **die Regel meint die Wortfamilie, nicht
   die Zeichenkette** — Grund ist die konfundierte Beleglage der zwei
   Kalibrierpunkte (0 Dropouts nur bei 492/660 kbps, 12,94–12,99/min nur
   bei 990 kbps gepinnt), belegt ist „990 gepinnt klingt kaputt", nicht
   „diese Rate ist hoerbar" — ein Satz mit „audibly" behauptet exakt den
   Teil, fuer den der Beleg fehlt. Der obige Wortlaut ersetzt „audibly
   breaking up" durch **„where stack dropouts ran throughout"**: benennt
   dasselbe Phaenomen (Arm B, T-008) ueber den Kanal, der es tatsaechlich
   traegt, mit dem Zaehler als Subjekt (R-A), ohne eine Aussage ueber das
   Hoeren zu treffen. Damit ist AK-T009-43 auch im Geist, nicht nur im Grep,
   eingehalten.
3. **Warum ein Tap statt eines laengeren Erstzeilen-Satzes:** AK-T002-13
   verbietet Textwachstum gegenueber `babe3d8` in der ersten Ebene. Ein
   Disclaimer, der nur bei Bedarf gelesen wird, loest den
   Kontrastwiderspruch, ohne die Zeile schwerer zu machen als die anderen.
4. **Keine Umsortierung.** Die Zeile bleibt direkt unter der Verlustzeile
   (T-009: „Underflow verliert die Hauptrolle, behaelt die Zeile").

   **Korrektur 2026-09-02, nach Rueckfrage des Coordinators, zur
   Einrueckung:** „keine Einrueckung" war zu weit gefasst und wird
   zurueckgenommen. `ExplainedRow` rueckt sein Label bauartbedingt 8 dp ein
   — Bestandsverhalten in 17 Aufrufern app-weit, u. a. der `LdacSection`-
   Zeile direkt ueber `LossRow` in diesem selben Panel. Diesen Achtungsdrang
   fuer eine Zeile zu unterbinden, muesste entweder die geteilte Komponente
   fuer 17 Aufrufer aendern oder eine Sonderpolsterung einfuehren — beides
   ist ein Eingriff in geteilten Code fuer einen rein optischen 8-dp-Versatz
   zwischen zwei ruhigen, kleinen Zeilen, den kein Kontrast-, Zielgroessen-
   oder Lesefehler begleitet. Der Versatz ist zudem kein neues Muster: die
   `LdacSection`-Zeile ist im selben Panel bereits genauso gegen die
   flachen `LinkHeader`/`FormatLine`-Zeilen ueber ihr eingerueckt. **Wird
   hingenommen, keine Sonderbehandlung.** Gemeint war und bleibt: keine
   zusaetzliche, ueber `ExplainedRow` hinausgehende Einrueckung.

**Neue Akzeptanzkriterien:**

- **AK-T017-1** Die Underflow-Zeile ist ein `ExplainedRow` mit einer
  Erklaerung, die R-D wortgleich fuer den nicht-null-Fall ausspricht. Ohne
  Erklaerungstext ist das Kriterium nicht erfuellt.
- **AK-T017-2** Kein Doppelpunkt-Klammer-Format in diesem Panel. Pruefbar
  per Grep gegen `Regex(""":\s*\d+\s*\(""")` im `LiveLinkPanel.kt`-Quelltext.

**Wortlaut der Graph-Caption:** keine neue Entscheidung noetig — AK-T002-11
("{k} of {n} windows lost something") ist seit T-002 bindend und
unveraendert; sie ist nur nicht gebaut (siehe `DESIGN_REVIEW.md` DR-002).
„Verluste nennen" (Ereigniszahlen summieren) wird hiermit ausdruecklich noch
einmal verworfen: es ist exakt die unbegrenzt wachsende, bei tadelloser
Verbindung schadensverdaechtige Zahl, gegen die T-002 ueberhaupt geschrieben
wurde — nur diesmal in der Graph-Caption statt in der Verlustzeile.

**Vorbefund des `developer` (2026-09-02), hier quittiert:** „Audio lost: …"
(`LiveLinkPanel.kt:350`), „No loss this window." (`LiveLinkPanel.kt:365`)
und der Default `quietText = "no loss in this window"`
(`LiveLinkGraph.kt:207`, greift auf der 60-s-Uebersicht, da dort kein
Override gesetzt wird) stehen alle drei woertlich auf der Verbotsliste aus
AK-T002-12 und in `LiveLinkPanel.kt`/`LiveLinkGraph.kt` seit T-002
(30.08.) unveraendert. Kein Regress von T-017 — die T-002-Verlustzeile
selbst wurde nie auf R-A umgebaut, obwohl R-A und AK-T002-12 seit T-002
bindend sind. Auch hier keine neue Wortlaut-Entscheidung noetig: Abschnitt
„Formulierungen erste Ebene" (oben, T-002) schreibt fuer genau diesen Fall
bereits `"No counter moved in the last {W}."` vor (`CLEAN`/`ALL_FIVE`) —
der Code zeigt stattdessen weiterhin den Vor-Spec-Satz „No loss this
window.". Siehe `DESIGN_REVIEW.md` DR-004 fuer die Einordnung (Kritisch,
hoechste Prioritaet der bisher in diesem Zyklus gefundenen Punkte).

**Nachtrag 2026-09-02 (zweite Rueckfrage, dritter Zweig von DR-004):**
Zwei der drei Zeichenketten sind gebaut — `"No counter moved in the last
{W} s."` ersetzt sowohl `"No loss this window."` als auch den
Graph-Default. Fuer den dritten, nicht-leeren Zweig
(`LiveLinkPanel.kt:350`, „Audio lost: …") bietet die Formulierungstabelle
nur `OCCASIONAL`/`DISTURBED`-Saetze, und beide brauchen entweder eine Rate
ueber `LOSS_WINDOW_MS` (Parameter selbst noch offen — T-001) oder ein
gemessenes Ereignis-Alter (D-8). Beides existiert nicht: `LossRow` liefert
heute eine rohe Poll-zu-Poll-Differenz, keine 60-s-Aggregation, keine
Coverage-Klassifikation.

**Entscheidung: Option (a).** Die Zeile bekommt eine reduzierte, ratenlose
Sofortformulierung — **keine Aenderung an der Bedeutung**, nur an der
Grammatik:

    "${parts.joinToString(", ")} in the last ${trimZero(windowSeconds)} s."

Das ist der bestehende String **minus** dem Praefix „Audio lost: ". Kein
neuer Text wird erfunden: `parts` liefert bereits genau die
Kanal-Fragmente, die die `OCCASIONAL`-Zeile fuer einen einzelnen Kanal
vorschreibt (`"{N} {kanal} in the last {W}"`), nur fuer mehrere gleichzeitig
betroffene Kanaele aneinandergereiht, wie es der Code schon vor T-017 tat.

- **R-A eingehalten:** Das Subjekt ist jetzt die Liste der Zaehler
  („3 app underruns, 1 stack dropout …"), nicht mehr „Audio". Der einzige
  R-A-Verstoss war das Praefix; er ist weg.
- **R-E eingehalten:** keine Rate, also keine abstufende Aussage moeglich —
  R-E betrifft Raten zwischen 0 und 12/min und greift hier nicht.
- **Taeuscht keine Zustandsmaschine vor:** Der reduzierte Satz traegt
  keinen der beiden Marker, an denen `OCCASIONAL`/`DISTURBED` erkennbar
  sind (kein „— about {R}/min", kein „for {D} now") — er ist damit
  strukturell, nicht nur inhaltlich, von einem Verdikt unterscheidbar und
  kann nicht als Rate oder Dauer gelesen werden, die niemand gemessen hat.
  Das Fenster bleibt das tatsaechlich gemessene Poll-Intervall (`tx.windowMs`
  bzw. `intervalMs`), nie `LOSS_WINDOW_MS` — AK-T002-1 ist damit erfuellt,
  ohne einen Wert vorzutaeuschen, den es noch nicht gibt.
- **Warum nicht (b):** „Audio lost" ist der auffaelligste R-A-Verstoss der
  gesamten Oberflaeche — ihn stehenzulassen, bis die volle
  Zustandsmaschine kommt, hiesse, den sichtbarsten Fehler am laengsten zu
  behalten. Die Kuerzung kostet nichts an Aussage, die heute ohnehin nicht
  belegt waere.

**Damit ist DR-004 vollstaendig entschieden** (3 von 3 Zweigen), Umsetzung
liegt beim `developer`. Die Rate-/Alters-Verfeinerung (volle
`OCCASIONAL`/`DISTURBED`-Formulierung) bleibt fuer den Zyklus stehen, der
die T-009-Zustandsmaschine baut — kein neuer Auftrag hier.

---

## Buendelungskriterium statt Rate je Minute (T-030) — 2026-09-02

Fortschreibung von T-002 und T-009, **nicht** Ersatz. Alles, was hier nicht
ausdruecklich geaendert wird, gilt unveraendert weiter — insbesondere die
Zustandsmechanik (fuenf Zustaende, Coverage, U-1..U-6), die Regeln R-A bis R-D,
der Datenweg D-1..D-12 und die gesamte Stufenzeile aus T-009.

Quellen: `docs/perf/T-029-korrelation-hoereindruck.md` (Zuordnung Hoereindruck,
Director), `docs/perf/T-029-990-korrelation.md` (Messseite,
`performance-tuner`), `docs/research/R-006.md` (Literaturlage), `docs/state.md`
(Entscheidungen des Nutzers vom 02.09.), `ARCHITECTURE.md` AD-019/AD-020/AD-021.

**Diese Fortschreibung ist unverifiziert (Spezifikation ohne Geraet und ohne
laufende App).** Es gab nichts anzusehen; sie ruht ausschliesslich auf den
Messberichten. Alle Zahlen stammen aus **einem** Lauf, mit **einem** Hoerer, an
**einem** Geraet, nicht wiederholt — jede Aussage unten traegt genau so weit.

### Der Anlass, in einem Satz

Cluster 1 des T-029-Laufs trug **11 Episoden in 21,2 s** und wurde gemeldet; auf
die Minute gerechnet sind das 11/min und damit **unter** der bisher gesetzten
Alarmschwelle `LOSS_ALERT_RATE_PER_MIN[dropouts]` = 12/min. Eine Rate je Minute
haette den einzigen kurz-und-dicht gemessenen Fall verschwiegen.

### Warum die Leitgroesse der Ueberlast wechselt — drei Belege

1. **Die Struktur ist zweigipflig, gemessen.** Ereignisabstaende *innerhalb* der
   zehn Cluster: n = 117, Minimum 1,10 s, Median 2,70 s, Maximum **23,3 s**.
   Ruhephasen *zwischen* den Clustern: n = 11, Minimum **35,6 s**, Median
   84,1 s, Maximum 261,2 s. **Zwischen 23,3 s und 35,6 s liegt kein einziger
   gemessener Abstand.** Das ist keine Folge der 30-s-Clustergrenze des
   `performance-tuner`: ein realer Abstand von 28 s waere als "innerhalb"
   gezaehlt worden und haette das Maximum gehoben, einer von 32 s als
   "zwischen" und haette das Minimum gesenkt. Beides ist nicht eingetreten,
   also ist die Luecke beobachtet, nicht definiert.
2. **Die Zuordnung trennt entlang der Buendel, nicht entlang einer Rate.** Alle
   fuenf Cluster mit mehr als einer Episode wurden gemeldet (Cluster 1, 3, 4, 5,
   7); alle fuenf isolierten Einzelereignisse (Cluster 2, 6, 8, 9, 10) wurden
   nicht gemeldet. Elf von vierzehn Meldungen treffen die Struktur, die drei
   Fehltreffer betreffen Rueckblick und Dauer, keiner die Frage, ob ueberhaupt
   etwas war.
3. **Die Literatur kennt die Rate je Zeit nicht.** R-006 ist ein ausdruecklicher
   Negativbefund ueber den gesamten Rechercheumfang: Anteile, Lueckenlaengen und
   Burst-Muster tragen die Aussage, **eine normierte Ereignisrate je Zeit taucht
   in keiner gefundenen Quelle auf**.

**Was diese drei Belege NICHT hergeben, ausdruecklich:** Sie sagen nichts
darueber, ob gleichmaessig verteilte Episoden harmlos sind. Der urspruengliche
Schluss "Einzelereignisse wurden nicht bemerkt" ist vom App Designer
zurueckgenommen worden — **er hat sie gehoert und nur nicht jedes Mal
gemeldet**. Der belegte Gegensatz ist **"bemerkbar gegen stoerend"**, nicht
"hoerbar gegen unhoerbar". Die untere Grenze der Hoerbarkeit ist damit **nicht
bestimmt**, und keine Formulierung dieser Vorgabe darf so tun, als waere sie es.

---

### Die Groesse: Episoden in einem kurzen Fenster

**Ein Ausbruch ist erkannt, wenn im zurueckliegenden `LOSS_BURST_WINDOW_MS`
mindestens `LOSS_BURST_MIN_EPISODES` `dropouts`-Episoden gezaehlt wurden.**
Gezaehlt wird ausschliesslich auf `dropouts`; das ist die einzige Groesse, die
der Quelltext als saubere Ereigniseinheit hergibt (R-005, Entscheidung Nutzer
02.09.).

**Warum nicht der Abstand aufeinanderfolgender Ereignisse (Kandidat 2):**

- Der gemessene Median des Abstands *innerhalb* eines Buendels ist **2,70 s**,
  41 von 117 Abstaenden liegen unter 1,5 s. Die Poll-Kadenz der App liegt bei
  ~2 s, die T-029-Abtastung bei 1 Hz. Ein Kriterium auf Einzelabstaenden wuerde
  im dichten Teil der Verteilung **den Poller messen, nicht die Strecke**.
- Sein einziger Parameter — die Obergrenze des Abstands, der noch "dasselbe
  Buendel" ist — ist genau die unbestimmte Groesse: nach unten belegt durch
  Cluster 5 (**6,6 s** Abstand, trotzdem als Buendel gemeldet), nach oben durch
  35,6 s (kuerzeste Ruhephase, die der Hoerer als ruhig gemeldet hat), und
  dazwischen widersprochen durch **zwei** Meldungen, in denen eine Binnenluecke
  eines laufenden Clusters als dessen Ende gehoert wurde. Der Wert waere zu
  raten. Das verbietet AD-019.
- Ein Kettenkriterium haette ausserdem kein natuerliches Gedaechtnis: der
  Zustand haengt an der gesamten Vorgeschichte statt an einem Fenster.

**Warum nicht der Anteil gestoerter Zeit (Kandidat 3):** Das waere die
literaturnaechste Groesse (R-006: Prozent bzw. verlorener Zeitanteil) und ist
auf diesem Geraet **nicht bildbar**. Ein Anteil braucht eine Dauer je Episode;
die Zaehler geben nur Episodenzahl und verworfene Warteschlangeneintraege her
(~25 `dropped` je `dropout`, T-029 Abschnitt 10). Wieviele Millisekunden Ton
eine Episode entfernt, ist **nicht gemessen und mit den vorhandenen Zaehlern
nicht messbar**. Eine Umrechnung waere ein erfundener Nenner — derselbe
AK-3-Verstoss, der schon die Prozentanzeige aus T-002 ausgeschlossen hat.
Offen gefuehrt als M-14.

**Aufloesungsvorbehalt, bindend:** Bei 1-Hz- bzw. 2-s-Abtastung fallen mehrere
Episoden derselben Sekunde in **eine** Lesung (belegt: 26 von 127 Lesungen des
Laufs trugen 2 bis 5 `dropouts`). Zaehlungen sind dadurch nicht betroffen —
`dropouts` ist ein Delta —, **Spannenangaben schon**. Faellt ein ganzer Ausbruch
in eine einzige Lesung, ist seine Spanne nicht aufgeloest; der Text sagt dann
**nie** "0 s" und nie eine Sub-Kadenz-Zahl, sondern uebernimmt das bereits
beschlossene Muster aus T-009 ("held for less than {2 x Kadenz}").

**Was die Wahl NICHT behauptet:** Sie spricht gleichmaessig verteilte Episoden
nicht frei. Elf Episoden im Abstand von 5,5 s erfuellen das Kriterium ebenfalls
— und das ist richtig so, denn die gemessenen, gemeldeten Cluster 3 und 4 lagen
mit 3,2–3,3 s mittlerem Abstand in derselben Groessenordnung. Der Unterschied
zur Rate je Minute ist nicht, dass Verteiltes entlastet wird, sondern dass
**der kurze dichte Ausbruch ueberhaupt erkannt wird** (11/min haette 12/min nie
erreicht) und dass die Anzeige auf der Zeitskala des Ausbruchs reagiert
(Sekunden) statt auf der einer Minutenmittelung.

---

### Zwei Stufen, verschieden begruendet

| Stufe | Bedingung | Begruendung, je eigen |
|---|---|---|
| `OCCASIONAL` | mindestens **eine** `dropouts`-Episode im `LOSS_WINDOW_MS` | **Zwei unabhaengige Gruende.** Erstens: die Ruherate dieses Kanals ist ueber 514 s in fuenf Laeufen exakt 0 (T-008) — eine Episode ist bereits ausserhalb des gemessenen Ruhezustands. Zweitens, neu aus T-029: **Einzelereignisse werden gehoert**, nach eigener Aussage des Hoerers. Die untere Stufe darf deshalb auf das Einzelereignis reagieren. |
| `DISTURBED` | `LOSS_BURST_MIN_EPISODES` Episoden innerhalb `LOSS_BURST_WINDOW_MS` | **Braucht die Buendelung.** Gemeldet wurden ausschliesslich Buendel; das kleinste gemeldete trug **3 Episoden in 6,6 s** (Cluster 5). Fuenf isolierte Einzelereignisse wurden gehoert, aber nicht gemeldet. Die obere Stufe bildet "stoerend" ab, nicht "hoerbar". |

Damit traegt die zweistufige Anlage aus T-002 zum ersten Mal zwei **getrennte**
Begruendungen statt zweier Punkte auf derselben Rate-Achse. Das ist der Kern
dieser Fortschreibung.

---

### Verlustzeile — Kanaele und Verdikte, Stand 2026-09-02

Ersetzt die Tabelle "Verlustzeile — was sich gegenueber T-002 aendert" aus
T-009 vollstaendig. Zwei Zeilen sind Nachzuege bereits gefallener
Entscheidungen, drei sind neu bzw. korrigiert.

| Kanal (Feld) | UI-Wort | `OCCASIONAL` | `DISTURBED` | Schwellentyp (AD-019) | Woraus |
|---|---|---|---|---|---|
| `A2dpTxDelta.dropouts` | **"dropped audio", in "incidents"** | **ja** | **ja — der einzige** | `Measured` | einzige saubere Ereigniseinheit (R-005); einziger Kanal mit einer Zuordnung zum Hoereindruck (T-029) |
| `A2dpTxDelta.dropped` | "send queue entries cleared" — **nie "packets"** (R-G) | **nein** (geaendert) | nein | `None` | zaehlt Warteschlangeneintraege variabler Groesse; eine Rate darauf addiert Ungleiches. **Entscheidung Nutzer 02.09. nach R-005**, hier nachgezogen |
| `A2dpTxDelta.underflows` | "encoder ran dry", in "times" | **nein** (geaendert) | nein | `None` | im einzigen gemessenen hoerbar kaputten Arm null. **Nachzug**: die T-009-Tabelle liess hier faelschlich `OCCASIONAL` stehen; AD-019 und der gebaute Stand (QA-001) sagen seit dem 02.09. anderes |
| App underruns | "app audio ran dry", in "times" | **nein** (geaendert) | nein | `Open`, `TODO(M-1)` | nie gemessen, weder ruhend noch gestoert. **Entscheidung Nutzer 02.09.**: sichtbar als Zahl, kein Verdikt, keine rote Zeile |
| Mixer underruns | "mixer ran dry", in "times" | **nein** (geaendert) | nein | `Open`, `TODO(M-1)` | dito |

**Folge, ausdruecklich benannt: `dropouts` ist der einzige beurteilende Kanal
der gesamten Anzeige.** Ist er nicht lesbar (offloaded, kein Helper, kein Link),
gibt es keinen beurteilbaren Kanal, und der Zustand ist `CANNOT_TELL` mit Grund
`NO_JUDGEABLE_CHANNEL` — nie `CLEAN` (AD-019/AD-020). Alle uebrigen vier Kanaele
bleiben in der zweiten Ebene **sichtbar und unbeurteilt** (`GOAL.md` AK-2).

---

### Die Woerter: zwei Zahlen, zwei Familien (Entscheidung App Designer, 02.09.)

**Entschieden vom App Designer, hier eingearbeitet:** Die beurteilende Groesse
heisst in der Oberflaeche **"dropped audio"** und wird in **"incidents"**
gezaehlt. Daneben steht eine **zweite, getrennte Zahl** fuer die Quellseite.

**Der Messbefund, der die bisherige Projektsprache korrigiert** (vom Director
quittiert; **von mir nicht selbst am Dump geprueft**): Ein echter Dump zeigt
`Packet counts (expected/dropped): 1279910 / 0` bei gleichzeitig
`Counts (flushed/dropped/dropouts): 1 / 807 / 35`. **Auf Paketebene geht nichts
verloren.** Was das Projekt bisher "Paketverlust" nannte, ist eine **Raeumung
der Sendewarteschlange**: Der Stack wirft Audio weg, das er nicht rechtzeitig
losgeworden ist. Die Funkstrecke wiederholt selbst — das Problem ist
**Verzoegerung, nicht Verlust auf der Strecke**.

**Zwei Familien, zwei Defekte, ausdruecklich nicht zwei Stufen desselben:**

| Familie | Zaehler | Was passiert | UI-Wort | Verdikt |
|---|---|---|---|---|
| **Sendeseite** | `dropouts` (Ereigniseinheit), `dropped` (geraeumte Eintraege) | Der Stack **verwirft** fertiges Audio aus der Sendewarteschlange. Es fehlt. | **"dropped audio"**, gezaehlt in **"incidents"** | traegt das Verdikt (`OCCASIONAL`/`DISTURBED`) |
| **Quellseite** | `underflows`, `Bytes (underflow)`, `PCM read counts (expected/actual)` | Dem Encoder **fehlte PCM-Material**; die Luecke wird **mit Stille aufgefuellt**, nicht uebersprungen. | **"ran dry"**, gezaehlt in **"times"** | **kein Verdikt, in keiner Richtung** (QA-001, AD-019) |

- **Verschiedene Einheitenwoerter sind Absicht.** "incidents" und "times"
  duerfen nicht dasselbe Wort sein, sonst laedt die Oberflaeche zum Vergleichen
  und Addieren ein — und die zwei Zahlen sind nicht vergleichbar (AK-T002-8
  verbietet die Summe bereits).
- **Die zweite Zahl ist ein anderer Defekt, kein kleinerer.** Sie wird nicht
  leiser gesetzt als die erste: gleiche Typografie, gleiche Textstufe, kein
  "just", kein "only", keine Verkleinerungsform. Sie wird **nur nie rot**, weil
  fuer sie keine Schwelle gemessen ist — nicht, weil sie harmlos waere. Genau
  das sagt ihre Erklaerung hinter dem Fragezeichen.
- **Warum "dropped audio" R-A nicht verletzt:** R-A macht den Zaehler zum
  Subjekt und verbietet Aussagen ueber den Klang. T-002 hat fuer genau diesen
  Kanal die Ausnahme schon begruendet: *"Ein 'dropped'-Kanal darf 'dropped'
  heissen, weil der Stack das Audio nachweislich selbst weggeworfen hat."* Das
  ist hier der Fall und jetzt zusaetzlich am Dump belegt. Verboten bleibt
  unveraendert **"audio lost"** — das behauptet eine Folge beim Hoerer,
  "dropped audio" benennt eine Handlung des Stacks.

**R-G — Kein Wort der Verlustanzeige nennt diese Groesse einen Paketverlust
(neu).** "packet", "packets", "packet loss" und jede Beugung stehen in der
Verlust- und Link-Anzeige **nicht** fuer die Raeumungsfamilie. Belegt: auf
Paketebene ist der Zaehler null, waehrend geraeumt wird — die Bezeichnung waere
**sachlich falsch**, nicht bloss ungenau. Durchsetzung in derselben Form wie
AK-T009-43 (Wortfamilie, Grep, nicht Zeichenkette).

**Ausdrueckliche Ausnahme, damit die Regel nicht zu weit greift:** Der
Liveness-Fallback der Bitratenlinie darf weiterhin `packets/s` sagen — er
benennt **echte** transportierte Pakete (die es gibt: 1 279 910 erwartet), nicht
die Raeumung. R-G gilt der Verlustgroesse, nicht dem Wort an sich.

**Betroffene Stellen in dieser Datei, die dadurch ueberholt sind:**

- `UI_SPEC.md:128` — Kanaltabelle T-002, Zeile "Dropped packets". Neuer UI-Name:
  **"send queue entries cleared"** (zweite Ebene, ohne Verdikt). Der Feldname
  `A2dpTxDelta.dropped` im Code bleibt, was er ist.
- `UI_SPEC.md:949` — der R-D-Satz der zweiten Ebene lautete "...the encoder
  underflow counter stayed at zero on a link that was dropping packets."
  **Neuer Wortlaut:** "...stayed at zero on a link that was dropping audio
  throughout." Derselbe Beleg, ohne die falsche Ebene.
- `UI_SPEC.md:906` — die dortige Kanaltabelle ist bereits durch die Tabelle
  oben ersetzt.
- `UI_SPEC.md:876`/`1239` — "Bluetooth is falling behind: {N} packets queued"
  ist bereits gestrichen (AK-T009-29) und bleibt gestrichen; die Nennung dort
  ist ein Verbot, kein Vorkommen.

**Selbstkorrektur zu T-017, benannt:** T-017 hat die Form
"Label: Wert" abgelehnt und volle Saetze verlangt. Der App Designer hat jetzt
"Dropped audio: 3 incidents" gewaehlt; seine Entscheidung geht vor, und die
Begruendung von damals traegt hier ohnehin nur halb — abgelehnt war das
**Doppelpunkt-Klammer-Paar** ("Encoder underflows: 3 (2 s)"), das das Fenster in
eine Klammer schiebt. Das bleibt verboten (AK-T017-2 unveraendert): das Fenster
behaelt seine eigenen Woerter ("in the last 60 s"). Erlaubt ist ab jetzt der
Doppelpunkt zwischen Groessenname und Zahl, wenn der Rest ein Satz bleibt.

---

### Formulierungen erste Ebene (Aenderung gegenueber T-002/T-009)

Subjekt bleibt der Zaehler (R-A). Neu sind: **die Rate je Minute verschwindet**,
und **die zwei Familien bekommen zwei getrennte Zeilen** mit zwei getrennten
Einheitenwoertern.

| Zustand / Zeile | Wortlaut (Englisch, verbindlich) |
|---|---|
| `CLEAN` (`ALL_FIVE`), **alle** gelesenen Zaehler auf null | "No counter moved in the last {W}." — **unveraendert** |
| Sendeseite ruhig, Quellseite hat gezaehlt | "Dropped audio: no incidents in the last {W}." — die Quellseite steht in ihrer eigenen Zeile darunter |
| `OCCASIONAL` | "Dropped audio: {N} incident(s) in the last {W} — last counted {D} ago." |
| `OCCASIONAL`, Spanne nicht luecklos gemessen | "Dropped audio: {N} incident(s) in the {M} this panel has measured." |
| `DISTURBED`, Spanne ueber mindestens zwei Lesungen | "Dropped audio: {k} incidents within {s} s." |
| `DISTURBED`, alle Episoden in einer Lesung | "Dropped audio: {k} incidents in under {c} s." — `{c}` = 2 x gemessene Kadenz |
| `DISTURBED`, Zusatzzeile | "Watch closely reads the stack twice a second while it is on." — **unveraendert** |
| **Quellseite, eigene Zeile, immer sichtbar wenn lesbar** | "Encoder ran dry: {N} times in the last {W}." |
| **Quellseite, Erklaerung hinter dem Fragezeichen** (`ExplainedRow`, T-017) | "This count carries no verdict, in either direction: the same counter stayed at zero on a link that was dropping audio throughout, and it climbed through 39 minutes of playback with nothing else wrong. It names a different fault, not a smaller one — the encoder had no PCM to take, and that gap is filled with silence rather than skipped." |

**Was wegfaellt und warum:** "— about {R}/min" (T-002) und "{Kanal} for {D} now
— about {R}/min" (T-009). Zwei Gruende, jeder fuer sich hinreichend:

1. **Die Zahl ist bei gesetztem `LOSS_WINDOW_MS` = 60 000 redundant.** Ueber ein
   volles, luecklos gemessenes 60-s-Fenster ist `{R}` identisch mit `{N}`. Sie
   unterscheidet sich nur dann, wenn **weniger als eine Minute gemessen wurde** —
   also genau dort, wo das Hochrechnen auf eine Minute am wenigsten belegt ist.
2. **Sie ist die Groesse, die T-029 widerlegt hat**, und sie legt eine Achse
   nahe, auf der man Zustaende ordnen koennte. Genau das verbietet R-E.

**Eine Folge, die benannt gehoert:** Wenn die Quellseite im Normalbetrieb
mitlaeuft — und das tut sie belegt (39 min Wiedergabe ohne wahrgenommene
Stoerung, Zaehler stieg trotzdem) —, dann ist **"No counter moved in the last
{W}." fast nie wahr**. Der Satz bleibt richtig und bleibt stehen, er wird nur
selten. Die Regel dahinter ist R-D: ein Zaehler auf null sagt nur etwas ueber
diesen Zaehler, also sagt jede Familie ihren eigenen Satz. Wer stattdessen die
Quellseite aus dem `CLEAN`-Urteil herausrechnen wuerde, um den ruhigen Satz
haeufiger zu sehen, baut genau den falschen Freispruch aus `GOAL.md` AK-3.

**Strukturelle Unterscheidbarkeit** (Muster aus T-017): `OCCASIONAL` nennt ein
**gesetztes Fenster** ("in the last {W}"), `DISTURBED` eine **gemessene Spanne**
("within {s} s"). Die zwei Saetze sind ohne Farbe, ohne Pill und ohne
Betonungsstufe voneinander zu unterscheiden.

**Zahlenraum, gegen Textwachstum geprueft (AK-T002-13):** `{k}` ist durch das
Fenster begrenzt (im gesamten T-029-Lauf maximal 61 Episoden in 15 s), `{s}` ist
durch `LOSS_BURST_WINDOW_MS` auf zwei Stellen begrenzt. Die Zeile kann nicht
laenger werden als der bisherige Ratensatz.

---

### Die zweite Ebene — was ueber die Buendelung gesagt wird

Punkte 1–5 aus T-002/T-009 bleiben. Punkt 6 bleibt entfallen (Entscheidung 4).
**Neu ist Punkt 7**, als Zeilen, nie als Prosa:

7. **Die Buendelstruktur im Fenster** (Sendeseite):
   - "Dropped audio in the last {W}: {N} incidents." — Zahl, **keine Rate**
   - "Largest run in the last {W}: {k} incidents within {s} s." — nur wenn
     `k >= 2`
   - "Longest stretch without one in the last {W}: {q} s."
   - "Send queue entries cleared in the last {W}: {N}." — die feinere Zaehlung
     hinter denselben Vorfaellen (`A2dpTxDelta.dropped`), ohne Verdikt und
     **ohne das Wort "packet"** (R-G)
   - Erklaertext des Blocks, ein Satz: "Dropped audio is counted in runs, not as
     a rate per minute — the same number of incidents packed into a few seconds
     or spread across a minute are not the same thing, and a per-minute number
     cannot tell them apart."

Die Zeilen sind genau die Groessen, die R-006 als literaturnah ausweist, soweit
dieses Geraet sie hergibt: Anzahl, Buendelgroesse, Lueckenlaenge. Der Anteil
gestoerter Zeit fehlt und bleibt begruendet fort (M-14).

8. **Die Quellseite, als eigener Block mit eigener Ueberschrift** — nie in
   denselben Absatz wie Punkt 7 gemischt, nie mit ihm summiert (AK-T002-8):
   - "Encoder ran dry in the last {W}: {N} times."
   - "Bytes the encoder could not take in the last {W}: {N}."
     (`Bytes (underflow)`)
   - "PCM reads in the last {W}: {actual} of {expected}."
     (`PCM read counts (expected/actual)`)
   - Erklaertext des Blocks, zwei Saetze: "These counters are about the audio
     the encoder was handed, not about what the Bluetooth stack sent. A gap here
     is filled with silence rather than skipped, and none of these three carries
     a verdict — no measurement says what a normal value is."

   **Datenwegforderung D-13 (neu):** `Bytes (underflow)` und
   `PCM read counts (expected/actual)` sind heute nicht im Lesungssatz. Solange
   sie nicht geparst sind, sagen ihre Zeilen **"not readable"** — nie eine 0
   (Regel aus T-002, AK-T002-4). Der Block erscheint dann mit zwei lesbaren und
   einer nicht lesbaren Zeile, nicht gar nicht.

**Die Decke (R-B) bleibt dreiteilig; ein Satz wird im Wortlaut korrigiert.** Der
R-D-Satz lautet ab jetzt "...stayed at zero on a link that was dropping audio
throughout." statt "...dropping packets." (R-G). Kein vierter Satz.

---

### Das Protokoll — ein Eintrag je Ausbruch

Ersetzt die bisherige Cooldown-gesteuerte Episodenbildung fuer diesen Pfad.

- **Ein `EventLayer.DETAIL`-Eintrag je beendetem Ausbruch**, geschrieben, wenn
  der Ausbruch endet. Er traegt `{k}`, `{s}`, die gefahrene Bitratenstufe und ob
  gepinnt oder adaptiv (AK-T002-23 unveraendert).
- **Laeuft ein Ausbruch laenger als `LOSS_EVENT_COOLDOWN_MS`** (10 min), wird je
  angefangener Cooldown-Spanne ein Zwischeneintrag geschrieben. Damit behaelt
  die Konstante ihren Zweck (eine lange Stoerung schreibt keine neunzig Zeilen)
  und verliert die Rolle, die sie nicht mehr erfuellen kann: **getrennte
  Ausbrueche zusammenzufassen.** Im T-029-Lauf lagen fuenf Ausbrueche in 25 min,
  paarweise 36–261 s auseinander — ein 10-Minuten-Cooldown haette sie zu zwei
  Eintraegen verschmolzen und genau die Struktur zerstoert, die den ganzen
  Befund traegt.
- **Einzelne Episoden erzeugen keinen Protokolleintrag.** Sie erscheinen als
  Marke im Graphen und als Zahl in der zweiten Ebene. Begruendung: Sie sind
  bemerkbar, aber nicht als Vorfall meldenswert — und eine Liste, die zwei
  Stunden zurueckreicht, war der Ausloeser von T-002.

---

### Parameter

**Gesetzt (`Measured` im Sinne von AD-019):**

| Parameter | Wert | Woraus — und was den Wert noch nicht stuetzt |
|---|---|---|
| `LOSS_BURST_WINDOW_MS` | **30 000** | Liegt in der **gemessenen leeren Spanne** zwischen dem groessten Abstand innerhalb eines Buendels (23,3 s) und der kuerzesten Ruhephase zwischen Buendeln (35,6 s). Dieselbe Form wie `LADDER_QUEUE_PRESSURE_FRACTION`: Grenze in die leere Mitte zweier gemessener Haufen. **Was ihn nicht stuetzt:** die Abstaende zu beiden Seiten sind nur 1,29x bzw. 1,19x entfernt — deutlich enger als bei der Queue-Schwelle (14x / 4x). Ein Lauf, n = 117 + 11 Abstaende. **Zwei Meldungen widersprechen der oberen Haelfte:** zweimal wurde eine Binnenluecke als Ende gehoert; der wahrnehmungsseitige Wert liegt vermutlich darunter. Der Wert kann durch M-12 **nur sinken**, nie steigen — und fuer jedes Fenster zwischen ~15 s und 30 s faellt die Entscheidung fuer alle fuenf gemeldeten Buendel gleich aus. |
| `LOSS_BURST_MIN_EPISODES` | **3** | Die **kleinste tatsaechlich gemeldete Buendelgroesse**: Cluster 5, 3 Episoden in 6,6 s. Dagegen fuenf isolierte Einzelereignisse, keines gemeldet. **Was ihn nicht stuetzt:** ein Buendel aus **genau 2** Episoden ist im Lauf **nie vorgekommen** — ueber 2 sagt die Messung nichts, und die Anzeige sagt dort nichts (R-E). 3 irrt allenfalls in die vorsichtige Richtung: es schlaegt spaeter an, nie frueher als der Beleg. Verfeinert durch M-13. |
| `LOSS_BURST_CLEAR_HOLD_MS` | **5 000** | Gegen Blinken an der Fensterkante. **Die Decke ist gemessen:** die Summe aus Fenster und Haltezeit ist die maximale Nachlaufzeit nach der letzten Episode und muss unter der kuerzesten Ruhephase bleiben, die der Hoerer im Lauf als ruhig gemeldet hat (**35,6 s**) — sonst behauptet die Anzeige eine Stoerung in einer Spanne, die belegt als ruhig gemeldet wurde. 30 000 + 5 000 = 35 000 < 35 600. **Was ihn nicht stuetzt:** die Untergrenze. Jeder Wert in (0; 5 600] erfuellt die Messlage; die Wahl des groessten runden Werts darin ist Ruhe im Bild, keine Messung. |
| `LOSS_NOTICE_MIN_EPISODES[dropouts]` | **1** Episode im `LOSS_WINDOW_MS` | Numerisch identisch mit dem bisherigen `LOSS_NOTICE_RATE_PER_MIN[dropouts]` = 1/min, aber als **Zahl statt Rate** gefuehrt — dieselbe Groesse wie die obere Stufe, damit die zwei Stufen nicht auf zwei Achsen sitzen. Belegt wie bisher (Ruherate exakt 0 ueber 514 s, Obergrenze nach Dreierregel 0,35/min) und **neu zusaetzlich** dadurch, dass Einzelereignisse gehoert werden. |
| `LOSS_WINDOW_MS` | **60 000, unveraendert — Begruendung ersetzt** | Die T-009-Begruendung ("bei 12,94/min enthaelt das Fenster k = 13 >= `RATE_MIN_EVENTS_IN_WINDOW`") ist **hinfaellig**, weil keine Rate mehr gebildet wird. Der Wert bleibt aus zwei anderen Gruenden: Deckungsgleichheit mit `LiveTrace.OVERVIEW_WINDOW_MS` und `LADDER_WINDOW_MS` (Zeile und Graph meinen dasselbe Fenster), und die gemessenen Ruhephasen (Median 84,1 s) sind mehrheitlich laenger als 60 s — die ruhige Aussage bleibt in Ruhephasen also erreichbar. |

**Offen (`Open` im Sinne von AD-019, mit genau einer Messung):**

| Parameter | Warum offen | Schliesst |
|---|---|---|
| `LOSS_NOTICE/ALERT/CLEAR[app underruns, mixer underruns]` | nie gemessen, weder ruhend noch gestoert | `TODO(M-1)` — unveraendert |
| `SETTLE_MAX_SPAN_MS`, nicht angemeldeter Fall | unveraendert offen | `TODO(M-8)` |
| **obere Kante von `LOSS_BURST_WINDOW_MS`** — der Wert ist gesetzt, seine wahrnehmungsseitige Obergrenze nicht | zweimal wurde eine Binnenluecke als Ende gehoert; wie lang diese Luecken waren, steht in der aufgezeichneten Rohreihe und ist noch nicht ausgewertet | `TODO(M-12)` |
| **untere Kante von `LOSS_BURST_MIN_EPISODES`** — ob 2 Episoden bereits ein Buendel sind | im Lauf nie vorgekommen | `TODO(M-13)` |
| **Anteil gestoerter Zeit** als Groesse ueberhaupt | Dauer je Episode ist unbekannt und mit den vorhandenen Zaehlern nicht bestimmbar | `TODO(M-14)`, derzeit **ohne bekanntes Verfahren** |

**Zurueckgezogen — mit Grund, damit kein spaeterer Durchlauf sie wiederbelebt:**

| Parameter | Grund |
|---|---|
| `LOSS_ALERT_RATE_PER_MIN[dropouts]` = 12/min | **Konstruktiv ungeeignet, nicht bloss unbelegt.** Der einzige kurz-und-dicht gemessene, gemeldete Fall (11 Episoden in 21,2 s) liegt auf die Minute gerechnet **unter** dem Wert. Die Groesse wird ersetzt, nicht neu beziffert. |
| `LOSS_ALERT_SUSTAINED_WINDOWS` = 2 (Mindestabstand 15 s) | **Die Begruendung ist von T-029 falsifiziert.** Sie lautete: "zwischen 15 s und 97 s liegt kein gemessener Fall". T-029 misst dort drei: 6,6 s, 21,2 s und 38,0 s — und **alle drei wurden gemeldet**. Die Sustain-Bedingung haette den kleinsten gemeldeten Ausbruch (6,6 s) vollstaendig verschluckt. Das Buendelkriterium traegt seine eigene Fehlalarmsperre: es braucht drei Episoden, nicht eine. |
| `LOSS_CLEAR_HOLD_MS` = 35 000 | Die Begruendung (4x der laengste Binnenabstand von 7,68 s aus 12 Abstaenden) ist durch 117 gemessene Abstaende mit Maximum 23,3 s ueberholt; **unter ihrer eigenen Regel** ergaebe sie jetzt >= 93 s. Eine Nachlaufzeit von 93 s widerspricht der gemessenen Wahrnehmung (kuerzeste als ruhig gemeldete Phase: 35,6 s). Ersetzt durch das gleitende Fenster selbst plus `LOSS_BURST_CLEAR_HOLD_MS`. |
| `LOSS_CLEAR_RATE_PER_MIN[*]` | gegenstandslos: es gibt keine Rate mehr, und unter der Ganzzahlschwelle 1 liegt nur die Null — das ist das leere Fenster, kein eigener Parameter. |
| `LOSS_NOTICE/ALERT_RATE_PER_MIN[dropped]`, `[underflows]` | `None` statt `Open`: diese Kanaele tragen kein Verdikt, in keiner Richtung (siehe Kanaltabelle). |

**Warum das gleitende Fenster die Hysterese ersetzen kann:** Die Flatterfurcht
aus T-002 galt einer **stetigen Rate**, die um eine Schwelle pendelt. Eine
**Ganzzahl im gleitenden Fenster** kann ohne neue gezaehlte Ereignisse nicht
hin- und herspringen: `OCCASIONAL` endet exakt `LOSS_WINDOW_MS` nach der letzten
Episode, `DISTURBED` fruehestens `LOSS_BURST_WINDOW_MS` danach. Ein Wechsel
setzt immer ein neues, echtes Ereignis voraus.

`RATE_MIN_EVENTS_IN_WINDOW` = 10 bleibt unveraendert bestehen — es gilt weiterhin
fuer die Stufenzeile; **fuer die Verlustzeile ist es gegenstandslos**, weil dort
keine Rate mehr gebildet wird.

---

### Regeln

**R-E — Geltungsbereich praezisiert (keine neue Regel, keine Lockerung).** Der
bisherige Wortlaut band die Verbote an "jede `dropouts`-Rate echt zwischen 0 und
`LOSS_ALERT_RATE_PER_MIN`". Diese Schwelle existiert nicht mehr; ohne
Nachfuehrung haette die Regel keinen Anwendungsbereich. Neuer Bezug, sonst
wortgleich: **Die Verbote gelten in jedem gezaehlten Zustand, der nicht
`DISTURBED` ist — und ebenso innerhalb von `DISTURBED`.** Verboten bleiben
"minor", "slight", "barely", "probably inaudible", "should not be noticeable",
"may be audible", jede Abstufung wie "mild/moderate/severe" und jedes
mehrstufige Bildzeichen. Es gibt keine dritte Stufe und keinen Schweregrad
innerhalb einer Stufe.

**R-F — Keine Zahl der Verlustanzeige wird auf eine Zeit normiert, die nicht so
gemessen wurde (neu).** In der Verlustzeile, ihrer zweiten Ebene, der
Graph-Caption und den Protokolleintraegen erscheint **keine** Groesse je Minute
oder je Sekunde. Erlaubt sind: Anzahl mit Fenster, gemessene Spanne, gemessenes
Alter, gemessene Lueckenlaenge. Begruendung: T-029 (die Mittelung ueber eine
Minute loescht genau die Struktur, die den Unterschied macht) und R-006 (die
Groesse kommt in keiner gefundenen Quelle vor). **Geltungsbereich ausdruecklich
nur die Verlustanzeige** — die Stufenzeile und ihre Wechselzahlen sind davon
nicht beruehrt, dort ist die Zeitachse nicht die strittige Groesse.

**R-G** ist oben im Abschnitt "Die Woerter" ausformuliert und gilt gleichrangig:
kein Wort der Verlustanzeige nennt die Raeumungsfamilie einen Paketverlust.

R-A, R-B, R-C, R-D gelten unveraendert. AK-T009-43 (die Wortfamilie
"audible/audibly/audibility" kommt auf den Monitor- und Link-Oberflaechen nicht
vor) gilt unveraendert und wird von keiner Formulierung dieser Fortschreibung
beruehrt.

---

### Zustaende vollstaendig — was die Zeile in jedem Fall zeigt

| Fall | Erste Ebene |
|---|---|
| leer (nichts gezaehlt, alles gelesen) | "No counter moved in the last {W}." — kein Pill |
| laedt (< 2 vergleichbare Lesungen) | "Loss needs two readings." — unveraendert |
| Fehler / nicht lesbar | `CANNOT_TELL` mit typisiertem Grund; **neu haeufiger**, weil `dropouts` der einzige beurteilende Kanal ist (`NO_JUDGEABLE_CHANNEL`) |
| ein Vorfall | "Dropped audio: 1 incident in the last {W} — last counted {D} ago." |
| sehr viele Vorfaelle (Dauerausbruch) | "Dropped audio: {k} incidents within {s} s." — Zeilenlaenge konstant, `{s}` <= 30 |
| alle Vorfaelle in einer Lesung | "Dropped audio: {k} incidents in under {c} s." — nie "0 s" |
| Messluecke in der Spanne | `{M}`-Formulierung aus T-002, kein Alter |
| Umschaltspanne | `SETTLING`, unveraendert |
| Quellseite laeuft, Sendeseite ruhig | "No counter moved in the last {W}." **entfaellt** — es hat sich einer bewegt. Sendeseite: "Dropped audio: no incidents in the last {W}."; Quellseite: ihre Zahl, eine Zeile darunter. **Kein Satz verrechnet die zwei** (AD-021) |
| Quellseite nicht geparst (D-13) | "not readable", nie eine 0 |

---

### Aufbau, Token, Plattform

- **Kein neuer Platz, keine neue Komponente, kein neues Token.** Die
  Verlustzeile bleibt an ihrem Ort; die zweite Ebene bleibt hinter dem
  vorhandenen `ExplainedRow`-Fragezeichen. Verwendet werden ausschliesslich
  `Pill`/`PillTone` (`Panel.kt:154`), `bodySmall`/`onSurfaceVariant` und
  `colorScheme.error` fuer den Fliesstext von `DISTURBED` — alles Bestand.
- **Keine neue Interaktion.** Es kommt kein Bedienelement hinzu; die einzige
  Beruehrflaeche bleibt das bestehende `ExplainedRow`. Material-3-Zielgroesse
  >= 48 dp gilt dort unveraendert (nicht neu geprueft — kein Geraet).
- **Ohne Farbe lesbar:** `CLEAN` ohne Pill, `CANNOT_TELL` mit Pill,
  `OCCASIONAL`/`DISTURBED` durch **Wortwahl und Satzbau** getrennt (gesetztes
  Fenster gegen gemessene Spanne). In einem Graustufen-Screenshot bleibt jede
  Unterscheidung erhalten.
- **Graph unveraendert.** Die Verlustmarken auf der Zeitachse **sind** bereits
  die ehrliche Darstellung der Buendelung — sie zeigen sie ohne jede Schwelle.
  Es kommt ausdruecklich **keine** Hervorhebung von Ausbruechen hinzu: das waere
  ein mehrstufiges Bildzeichen (R-E). Die Caption bleibt AK-T002-11.

---

### Akzeptanzkriterien

- **AK-T030-1** In der Verlustzeile, ihrer zweiten Ebene, der Graph-Caption und
  den Protokolleintraegen erscheint keine Groesse je Minute oder je Sekunde
  (R-F). Pruefbar per Grep auf `/min`, `per minute`, `perMinute` im Verlustpfad
  und als Compose-Test ueber alle Zustaende.
- **AK-T030-2** `DISTURBED` entsteht ausschliesslich aus
  `LOSS_BURST_MIN_EPISODES` `dropouts`-Episoden innerhalb
  `LOSS_BURST_WINDOW_MS`. Unit-Test mit vier Faellen aus T-029: **11 Episoden in
  21,2 s ergeben `DISTURBED`** (der Fall, den 12/min verfehlt haette),
  **3 Episoden in 6,6 s ergeben `DISTURBED`** (kleinstes gemeldetes Buendel),
  **1 Episode ergibt `OCCASIONAL`**, **2 Episoden in 30 s ergeben
  `OCCASIONAL`**.
- **AK-T030-3** Faellt ein ganzer Ausbruch in eine Lesung, lautet der Text
  "in under {2 x Kadenz}"; eine 0 s oder eine Sub-Kadenz-Zahl erscheint nirgends.
  Unit-Test mit einer Lesung, die 3 `dropouts` traegt.
- **AK-T030-4** Kein Kanal ausser `dropouts` erzeugt ein Verdikt. Unit-Test:
  `dropped` = 5000 und `underflows` = 40 im Fenster bei `dropouts` = 0 ergeben
  `CLEAN` bzw. `CANNOT_TELL`, **nie** `OCCASIONAL`; beide Zahlen bleiben in der
  zweiten Ebene sichtbar (`GOAL.md` AK-2).
- **AK-T030-5** Ein beendeter Ausbruch erzeugt genau **einen**
  `EventLayer.DETAIL`-Eintrag; ein Ausbruch, der laenger als
  `LOSS_EVENT_COOLDOWN_MS` laeuft, je angefangener Spanne einen weiteren; eine
  einzelne Episode **keinen**. Unit-Test mit der Clusterfolge aus T-029
  (10 Cluster in 25 min ergeben **5** Eintraege).
- **AK-T030-6** Nach der letzten gezaehlten Episode faellt `DISTURBED`
  spaetestens nach `LOSS_BURST_WINDOW_MS` + `LOSS_BURST_CLEAR_HOLD_MS` = 35 s.
  Unit-Test. **Das ist der Regressionstest gegen eine Nachlaufzeit, die laenger
  waere als die kuerzeste Ruhephase, die der Hoerer als ruhig gemeldet hat
  (35,6 s).**
- **AK-T030-7** `LOSS_ALERT_RATE_PER_MIN`, `LOSS_ALERT_SUSTAINED_WINDOWS`,
  `LOSS_CLEAR_HOLD_MS` und `LOSS_CLEAR_RATE_PER_MIN` existieren im Verlustpfad
  nicht mehr — weder als Konstante noch als Feld. Pruefbar per Grep.
- **AK-T030-8** Die Zeile "Largest run in the last {W}: {k} within {s} s."
  erscheint nur bei `k >= 2`. Unit-Test mit k = 1 und k = 2.
- **AK-T030-9** Jeder Parameter dieser Fortschreibung traegt im KDoc die
  Messung, auf der er ruht, **mit Cluster oder Kennzahl** (z. B. "T-029
  Cluster 5: 3 Episoden in 6,6 s, gemeldet"), und jeder offene `TODO(M-x)` mit
  genau einer Messung. `LOSS_BURST_WINDOW_MS` traegt zusaetzlich den Vorbehalt
  der schmalen Abstaende (1,29x / 1,19x) und den Hinweis, dass der Wert durch
  M-12 nur sinken kann.
- **AK-T030-10** Es existiert keine Funktion, die aus Episodenzahl und Spanne
  eine Rate, einen Anteil, einen Prozentsatz oder einen Schweregrad bildet
  (AD-021, Sperre 3). Pruefbar per Grep auf Divisionen durch eine Fenster- oder
  Spannenlaenge im Verlustpfad.
- **AK-T030-11** Die Wortfamilie "packet/packets/packet loss" steht auf den
  Monitor- und Link-Oberflaechen an **keiner** Stelle fuer die Raeumungsfamilie
  (`dropped`, `dropouts`) — R-G. Pruefbar per Grep gegen `ui/screens/monitor`
  und die dort verwendeten Stringressourcen, in derselben Form wie AK-T009-43.
  **Erlaubte Ausnahme, im Test mitgefuehrt:** der Liveness-Fallback der
  Bitratenlinie (`packets/s`), der echte transportierte Pakete benennt.
- **AK-T030-12** Sende- und Quellseite erscheinen als **zwei** Zeilen, mit
  **zwei verschiedenen Einheitenwoertern** ("incidents" gegen "times"). Es
  existiert kein Satz, keine Summe und keine Sortierung, die beide zu einer Zahl
  oder zu einer Rangfolge verbindet (AK-T002-8, AD-021). Die Quellseiten-Zeile
  traegt **keinen Pill, keine Fehlerfarbe und kein verkleinerndes Wort**
  ("just", "only", "minor"), aber dieselbe Textstufe wie die Sendeseite.
  Compose-Test ueber die Kombination "Sendeseite null, Quellseite ungleich
  null".
- **AK-T030-13** Ist ein Quellseiten-Zaehler nicht geparst oder nicht lesbar,
  steht in seiner Zeile "not readable" — nie eine 0 (D-13, AK-T002-4).
  Unit-Test mit einem Lesungssatz ohne `Bytes (underflow)`.
- **AK-T030-14** Der R-D-Satz der zweiten Ebene endet auf "dropping audio
  throughout", nicht auf "dropping packets". Pruefbar per Grep — das ist der
  einzige gebaute bzw. spezifizierte UI-Satz, den R-G umwirft.

**Geaendert an bestehenden Kriterien:**

- **AK-T002-1 praezisiert:** Neben Fenster, Rate und Epoche gilt die **gemessene
  Spanne** ("within {s} s") als Bezugsrahmen. Die Nennung "eine Rate (`/min`)"
  entfaellt fuer die Verlustanzeige (R-F).
- **AK-T002-17 praezisiert:** In der Verlustanzeige erscheint **gar keine**
  Rate; das Kriterium gilt unveraendert fuer die Stufenzeile.
- **AK-T002-7 ersetzt** durch AK-T030-5 (Episodenbildung laeuft ueber den
  Ausbruch, nicht ueber den Cooldown).
- **AK-T002-12 erweitert** um R-F: `/min` ist im Verlustpfad eine verbotene
  Zeichenkette.
- **AK-T009-24 neu formuliert, Zweck unveraendert:** Snapshot `underflows` = 0,
  `dropped` = 525, `dropouts` = 21 in 97 s ergibt `DISTURBED`, Kanal "stack
  dropouts" — jetzt ueber das Buendelkriterium erreicht (21 Episoden in 97 s
  ergeben in jedem 30-s-Fenster mindestens 3), nicht ueber eine Rate.
- **AK-T009-25 unveraendert gueltig** (zwei Zeilen, keine Verrechnung).
- **AK-T017-1 erweitert:** Die Erklaerung der Quellseiten-Zeile nennt jetzt
  zusaetzlich, dass die Luecke **mit Stille aufgefuellt** wird und dass es sich
  um einen **anderen**, nicht um einen kleineren Fehler handelt. Ohne diesen
  Zusatz ist das Kriterium nicht erfuellt.
- **AK-T017-2 praezisiert:** Verboten bleibt das Doppelpunkt-**Klammer**-Paar
  (`Regex(""":\s*\d+\s*\(""")`). Der Doppelpunkt zwischen Groessenname und Zahl
  ist ab jetzt erlaubt ("Dropped audio: 3 incidents in the last 60 s."),
  Entscheidung des App Designers vom 02.09.

---

### Messanforderungen

- **M-12 → obere Kante von `LOSS_BURST_WINDOW_MS`. Keine neue Geraetesitzung
  noetig.** Frage: **Wie lang waren die zwei Binnenluecken, die der Hoerer als
  Ende gehoert hat** (Meldungen 21:23:08 in Cluster 3 und 21:33:18 in
  Cluster 7)? Die Antwort steht bereits in der aufgezeichneten Rohreihe des
  T-029-Laufs und ist eine Nachrechnung, keine Messung. Ergebnis: die
  wahrnehmungsseitige Obergrenze des Buendelfensters. Liegt sie unter 30 s, wird
  der Parameter gesenkt. **Das ist der billigste und zugleich entscheidendste
  offene Punkt dieser Vorgabe.** Mitzurechnen waere dabei, ab welchem Fenster
  eines der fuenf gemeldeten Buendel nicht mehr ausloest — damit auch die
  Untergrenze belegt statt geschaetzt ist.
- **M-13 → `LOSS_BURST_MIN_EPISODES` und Bestaetigung des Fensters.** Zweite
  Hoersitzung an einem anderen Tag bei fest derselben Stufe. Fragen: Wird ein
  Buendel aus **genau zwei** Episoden gemeldet? Bleibt die Abstandsverteilung
  zweigipflig, und bleibt die Luecke zwischen ~23 s und ~36 s leer? Ohne diese
  Wiederholung ruht das gesamte Kriterium auf einem Lauf, einer Person, einem
  Geraet. **Moeglicher Hebel, unabhaengig vom Pinnen auf 990:**
  `docs/perf/T-027-messung-24ghz.md` hat bei fest **660** kbps ueber
  WLAN-Konkurrenz im 2,4-GHz-Band einen Verlustfall erzeugt (eine Zelle, nicht
  wiederholt, ohne Hoerdaten). Traegt er, waeren erstmals Buendel bei **gleicher
  und niedrigerer** Stufe herstellbar — das beruehrt auch M-11.
- **M-14 → der Anteil gestoerter Zeit als Groesse.** Frage: **Wieviele
  Millisekunden Ton entfernt eine `dropouts`-Episode?** Erst damit waere die von
  R-006 als literaturnah ausgewiesene Groesse ueberhaupt bildbar. **Derzeit ist
  kein Verfahren bekannt**, das sie mit den vorhandenen Zaehlern liefert; sie
  braeuchte eine Messung im Audiobereich, nicht im Zaehlerbereich. Wie M-11 wird
  sie benannt, nicht versprochen.

**Unveraendert offen:** M-1 (Eingangskanaele), M-8 (`SETTLE_MAX_SPAN_MS`),
M-11 (Zwischenpunkte der Hoerbarkeit — durch T-029 **nicht** geschlossen; die
untere Grenze bleibt unbestimmt, R-E bleibt bindend).

**Ausdruecklich nicht geschlossen durch T-029: M-5.** Der Lauf fuhr **fest
990/HIGH**, nicht adaptiv. Die Ruherate von `dropouts` unter ABR ueber
mindestens 30 min ist damit weiterhin unbekannt, und
`LOSS_NOTICE_MIN_EPISODES` = 1 ruht weiterhin auf den 514 s aus T-008.

---

### Ausdruecklich nicht Teil dieser Fortschreibung

- **Eine Aussage darueber, ob gleichmaessig verteilte Episoden stoeren.** Das
  Kriterium spricht sie nicht frei und verurteilt sie nicht; es beschreibt, was
  gezaehlt wurde.
- **Die untere Hoerbarkeitsgrenze.** Unbestimmt, siehe M-11.
- **Die Ursache der Buendelung.** T-029 hat sie bewusst nicht untersucht; die
  Anzeige nennt den Zustand, nie die Ursache.
- **Schwellen je Bitratenstufe.** M-4 ist weiterhin nur fuer 990 beantwortet.
- **Die Stufenzeile, der Graph und die Helper-Aktion.** Unberuehrt.
- **Eine Zusammenfassung ueber Verlust und Stufe.** AD-021 unveraendert.

---

### Offene Fragen an den App Designer

1. **Soll die untere Stufe ueberhaupt einen Pill tragen?** `OCCASIONAL` loest
   bei einer einzigen Episode im 60-s-Fenster aus. Im gemessenen 990er-Lauf
   waere der Pill dadurch in grob der Haelfte aller Fenster sichtbar gewesen
   (50–61 % der Fenster waren exakt null). Das ist ehrlich, aber ein
   Merkzeichen, das die halbe Zeit steht, wird ueberlesen. Alternative:
   `OCCASIONAL` behaelt seinen Satz und verliert den Pill; nur `DISTURBED`
   traegt eines. **Beide Varianten sind gleich ehrlich** — die Frage ist, ob das
   Auge auf "es wurde etwas gezaehlt" oder erst auf "es kam gebuendelt"
   anspringen soll. Ich habe den Pill vorlaeufig gelassen (Stabilitaetsregel:
   T-002 hat ihn so entschieden), halte die Gegenvariante aber fuer die ruhigere
   Anzeige.
2. **Soll die Anzeige dem Ohr oder der Struktur folgen, wenn ein Ausbruch eine
   Pause macht?** Der Hoerer hat zweimal eine Binnenluecke eines laufenden
   Clusters als dessen Ende gemeldet. Ein kurzes Buendelfenster folgt dem Ohr,
   springt dafuer waehrend eines langen Clusters mehrfach an und aus. Ein
   laengeres Fenster bleibt ruhig stehen und behauptet dabei eine Stoerung in
   einer Spanne, die sich fuer den Hoerer vorbei anfuehlte. **M-12 sagt, wo die
   Kante liegt — nicht, welche der beiden Eigenschaften wichtiger ist.** Das ist
   eine Produktentscheidung.
