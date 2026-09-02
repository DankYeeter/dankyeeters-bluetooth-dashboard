# QA-Befunde

Gefuehrt vom Director aus den Berichten des `qa-engineer`. Eine Zeile je
Befund, fortgeschrieben statt neu begonnen. Der `qa-engineer` schreibt hier
nicht selbst.

| ID | Titel | Prio | Sev / Likelihood | Adressat | Status | Zuletzt geprueft | Auftrag |
|---|---|---|---|---|---|---|---|
| QA-001 | `Counts (underflow)` allein malt die Verlustanzeige rot — AK-T009-24 erste Haelfte verletzt | P1 | Major / Hoch | developer | **behoben**, mutationsbelegt mit 3 unabhaengigen Proben | 2026-09-02 | T-014/T-015/T-016 |
| QA-002 | Kanal „stack dropouts" aus AK-T009-24 von keinem Test festgenagelt | P2 | Major / Hoch | developer | **behoben**, mutationsbelegt | 2026-09-02 | T-014/T-015/T-016 |
| QA-003 | Sweep-Zusicherungen unerreichbar; Kommentar behauptete Deckung | P2 | Major / Mittel | developer | **behoben** (Kommentar zurueckgenommen, offener Fall benannt) | 2026-09-02 | T-014/T-015/T-016 |
| QA-004 | Testtitel behauptete systemweite Wirkungslosigkeit von 990 kbps | P3 | Minor / Mittel | developer | **behoben** (Titel zurueckgenommen) | 2026-09-02 | T-014/T-015/T-016 |
| QA-005 | Zwei neue ABR-Felder ohne Konsumenten; `TxProbeSample` traegt sie nicht | P3 | Minor / Niedrig | director (Scope) | zurueckgestellt auf den UI-Zyklus | 2026-09-02 | T-014 |
| QA-006 | Long-Ueberlauf nicht von „Zeile fehlt" unterscheidbar | P3 | Minor / Niedrig | developer | **behoben** in der Form „benannt statt behoben", mit Test | 2026-09-02 | T-014/T-015/T-016 |
| QA-007 | `isEmpty` verwirft ABR-Zeilen bei fehlendem Quality Mode und Rate 0 | P3 | Minor / Niedrig | developer + Messung | **fuer Pause negativ belegt** (T-022), andere Zustaende offen | 2026-09-02 | T-014/T-022 |
| QA-008 | Systemisch: Akzeptanzkriterien nirgends maschinell verankert (Beleg: **AK-T009-29 verletzt**) | P2 | Major / Hoch | director + developer | offen, eigener Zyklus | 2026-09-02 | T-014 |
| QA-009 | `LossRow` prueft den Offload-Zustand nicht selbst | P3 | Major / Niedrig | developer | **behoben**, mutationsbelegt (2-Poll-Gegenprobe) | 2026-09-02 | T-018/T-020 |
| QA-010 | Drei getrennte Implementierungen von „was zaehlt als Verlust" | P3 | Minor / Mittel | developer | **behoben**, mutationsbelegt (5/5 Fehlschlaege ueber 3 Pfade + Kompilierprobe) | 2026-09-02 | T-018/T-020 |
| QA-011 | `MonitorEventLogTest:76-77` — Fixture-Satz vom Code nicht mehr erzeugbar, aber ehrlich als historisch markiert | P4 | Trivial / Niedrig | developer | zurueckgestellt | 2026-09-02 | T-016 |

## Design-Review-Befunde (T-017, `DESIGN_REVIEW.md`)

| ID | Titel | Prio | Status | Auftrag |
|---|---|---|---|---|
| DR-001 | Underflow-Zeile liest sich wie ein Defektbefund | Wichtig | **behoben** | T-018/T-020 |
| DR-002 | Graph-Caption zaehlte Zaehlerstaende statt Marken | Kritisch | **behoben**, testbelegt | T-018/T-020 |
| DR-003 | AK-T009-29 verletzt (Einzelpaket-Fehlalarm) | Kritisch | **behoben**, 5 Messreihen nachgefahren | T-018/T-020 |
| DR-004 | Drei verbotene Zeichenketten in der Verlustzeile (AK-T002-12) | Kritisch | **behoben**, alle drei Zweige | T-019/T-020 |
| DR-005 | „no stack loss in this window" im Nahaufnahme-Graphen macht „loss" zum Subjekt statt des Zaehlers | offen | **neu**, Formulierung an `ui-ux-designer` | T-020 |

## Entscheidungen des Directors

**02.09. — „DISTURBED" in AK-T009-24 ist als ZUSTAND gemeint, nicht als
Bezeichner.** Meine frueherer Einordnung („eine Spezifikation fuer Code, den es
noch nicht gibt") ist damit **falsch und zurueckgezogen**. Begruendung des
`qa-engineer`, die traegt: Ein Kriterium, das den Namen schuetzt und nicht die
Sache, schuetzt nichts. Die Bezeichner fehlten im Code — der **Mechanismus**
existierte, er hiess `hasLoss`.

**02.09. — QA-001 sofort behoben** statt in den UI-Zyklus geschoben. Aktiver
Fehler, kein fehlendes Feature.

**02.09. — Zwei Testzahlen in `MonitorTraceModelTest` (4→2, 5→4) bestaetigt.**
Der `developer` hat sie als Abweichung gemeldet statt sie stillschweigend
anzupassen. `TracePoint.lossCount` traegt im KDoc „everything audible", und
Underflow ist nachweislich nicht hoerbar. Haette ich abgelehnt, waere der Graph
als einziger Pfad bei der alten falschen Rechnung geblieben.

**02.09. — DR-003 kommt in denselben Auftrag wie DR-001/DR-002**, nicht in
einen spaeteren Zyklus. Es ist derselbe Fehlertyp wie QA-001 (Anzeige schlaegt
im Alltag Alarm, wo nichts ist), die Spec liegt fertig vor
(`LADDER_QUEUE_PRESSURE_FRACTION` = 0,20, laut Review der bestbelegte
Schwellenwert der ganzen Vorgabe).

**02.09. — QA-010 wird zuerst umgesetzt, vor DR-002 und DR-003.** Es ist die
**strukturelle Ursache** von QA-002: Drei handgepflegte Kopien der
Verlustdefinition, jede fuer sich mutationsfest, keine mit den anderen
verbunden. Die Stellen werden fuer DR-001/DR-002 ohnehin angefasst — sie jetzt
zusammenzuziehen ist billiger als beim naechsten Kanal wieder eine zu vergessen.

**02.09. — QA-011 zurueckgestellt.** Der Fixture-Satz ist im Kommentar bereits
ehrlich als historisch markiert; kein Nutzerschaden, keine Falschbehauptung.

## Bestaetigt, kein Befund

- Der Regressionstest zu AK-T009-24 **faengt** die Regression (Mutation
  `hasLoss = underflows > 0` → Test faellt).
- Blockgrenze des LDAC-Readers, `Config: Invalid`-Abweisung, 0-vs.-Abwesenheit
  fuer beide neuen ABR-Felder, Nicht-Deutung des Index als Rate.
- Truncation: alle 22 Schnittpunkte nachgerechnet, kein Praefix erzeugt einen
  LDAC-Block ohne die ABR-Zeilen.
- **Offload-Pfad haelt im Produktionsfluss** — `LiveLinkSource.readPass` nullt
  `tx`/`txDelta` bei `OFFLOADED`, bevor der Snapshot die UI erreicht. Die
  Absicherung ist aber unbewacht (QA-009).
- Testzahlen aus eigenen QA-Laeufen mit `--rerun-tasks`: 2344/0 (T-014),
  **2361/0** (T-016). Ein blosses `test` meldet nur `UP-TO-DATE` und liefert
  keine eigene Evidenz.

## Nachtrag 02.09. — Entscheidungen nach dem Retest T-020

**AK-T002-12 ist als Zeichenkettenliste abschliessend; R-A gilt daneben als
Grundsatz weiter.** Der `qa-engineer` hat richtig gesehen, dass
„no stack loss in this window" (Nahaufnahme-Graph) keine der verbotenen
Zeichenketten enthaelt — „no loss" steht dort nicht zusammenhaengend — aber
dennoch „loss" zum Subjekt der Verneinung macht statt des Zaehlers. **Beides
stimmt.** Die Liste ist die *pruefbare* Form des Grundsatzes, nicht sein
Ersatz. Kein akuter Fehler, aber inkonsistent zu „No counter moved in the
last {W} s." zwei Zeilen weiter. Als **DR-005** an den `ui-ux-designer`,
Umsetzung im UI-Zyklus. **Nicht** in T-021 — der aendert kein Verhalten.

**Restfall aus der QA-010-Kopplung, in T-021 aufgenommen.** Die
Kopplung schuetzt nur die Richtung Enum → App-Wort. `lossByChannel` ist von
Hand geschrieben, nicht aus `TxLossChannel.entries` erzeugt: Ein Kanal, der im
Enum steht und sein App-Wort bekommt, aber in der Map vergessen wird,
**kompiliert und testet sich gruen und zeigt nie Verlust an**. Im realistischen
Ablauf tritt das nicht auf; ein Test, der die Map gegen `entries` prueft,
schliesst es endgueltig.

**Die 20-%-Schwelle bekommt eine KDoc-Ergaenzung, keine Codeaenderung.** Bei
5-s-Kadenz liegen nur ~12 Lesungen im 60-s-Fenster, jedes Sample verschiebt den
Anteil um ~8,3 Punkte — die Schwelle wird grobkoernig getroffen. **Kein
Befund:** Die Referenzmessungen liegen bei 0–1,4 % (Ruhe) und 79–81 %
(Ueberlast); der Abstand ist 14x unten und 4x oben, eine einzelne
Sample-Verschiebung kann zwischen diesen Populationen nicht fehlklassifizieren.
Ungemessen ist, ob ein Betriebszustand *dazwischen* existiert — genau das
gehoert ins KDoc, statt es zu verschweigen.

## Nachtrag 02.09. — Fixture-Aufnahme T-022, ein struktureller Fund

**Die ABR-Zeilen und echte Verluste schliessen einander auf diesem Geraet
aus.** `LDAC adaptive bit rate encode quality mode index` und `adjustments`
druckt der Stack **nur**, wenn `LDAC quality mode: ABR` ist. Verluste treten
nur im **gepinnten** Zustand auf (HIGH/990). Belegt in beide Richtungen:

- Drei 990er-Dumps ueber 4:08 min: `dropped` 800 -> 1851, `dropouts` 32 -> 74,
  `underflow` **konstant 623**. `grep -c "adaptive bit rate"` = **0** in allen
  dreien.
- Alle **1795** Samples aus T-011 (ABR): beide Zeilen vorhanden,
  `dropped`/`dropouts` durchgehend **0**.

**Der `performance-tuner` hat NICHT zusammengefuehrt**, um die Anforderung des
Directors zu erfuellen — das haette eine Geraeteausgabe erfunden, die es nicht
gibt. Richtig so.

### Entscheidungen des Directors dazu

**AK-T009-24 braucht zwei Fixtures, nicht eine.** Meine Anforderung „eine
Aufnahme mit Verlusten UND vollstaendigem ABR-Block" war sachlich unmoeglich.
Der Verlustfall wird gegen `bt_manager_pixel11_ldac_990_loss.txt` geprueft,
der Parserpfad der ABR-Zeilen gegen die ABR-Fixtures. Der `qa-engineer`-Befund
aus T-014 („Regressionstest und neuer Parsercode beruehren einander nirgends")
bleibt damit **teilweise bestehen** — nicht aus Nachlaessigkeit, sondern weil
das Geraet die Kombination nicht erzeugt. Das gehoert so dokumentiert.

**Produktrelevante Folge, an den `ui-ux-designer`:** Im gepinnten Modus gibt es
**keine ABR-Stufeninformation**. Die neue Leitgroesse aus T-009 (ABR-Stufe im
Normalbetrieb) ist dort nicht nur unguenstig, sondern **nicht vorhanden**. Die
Oberflaeche muss fuer diesen Fall `CANNOT_TELL` sagen koennen — nicht „Stufe
unbekannt" als Zahl und nicht eine geratene Stufe aus der Rate. Das ist ein
neuer Punkt fuer den UI-Zyklus, kein heutiger Fehler.

**QA-007 ist fuer die gepruefte Bedingung negativ beantwortet.** Pausieren
(verbunden, still, `mIsPlaying=false`) laesst `quality mode` und Rate
unveraendert stehen — der `isEmpty`-Pfad wird dadurch **nicht** ausgeloest.
Andere Zustaende (echter Standby, Verbindungsabbau) bleiben ungeprueft. Der
Befund wird damit von „offen" auf „fuer Pause negativ belegt, sonst offen"
gesetzt, nicht geschlossen.

## Nachtrag 02.09. — Retest der Grep-Verankerung (T-021)

| ID | Titel | Prio | Adressat | Status |
|---|---|---|---|---|
| QA-012 | **`AK-T009-31` ist vakuum-gruen**: kein einziger `TODO(`-Marker im gescannten Baum, und als einzige der sechs Regeln **ohne** Vollstaendigkeits-Guard (die anderen nutzen `assertNone`, das `files.isNotEmpty()` erzwingt) | P3 Minor / Niedrig | developer | **offen** |
| QA-013 | `AK-T009-29` zeigt auf einen T-018-Bestandstest mit zwei `assertHides`-Substrings ueber zwei Zustaende — **keine ContentDescription, kein Leer-Guard**. Ungleich robust zum AK-T002-12-Sweep, der acht Zustaende plus Semantik prueft | P3 Minor / Niedrig | developer | **offen** |

**Bestaetigt:** Sieben der acht Regeln fallen bei Verzeichnis- und
Identifier-Umbenennung laut (`IllegalArgumentException`/`AssertionError`), nie
still gruen. Der Repo-Root-Fallback ueber `compiledClassesDir()` findet die
Wurzel auch bei erzwungenem Fremd-CWD. Die Erkennungslogik von AK-T009-31
funktioniert (ein eingefuegter `TODO(T-001)` wurde sofort gefangen) — nur ihr
Selbstschutz fehlt.

**Urteil zum Widerspruch des `developer` (Grep vs. gerenderter Text):
traegt.** Der `qa-engineer` bestaetigt die Kollision als real, nicht erfunden —
`grep -n "falling behind"` findet exakt den Kommentar, der die Entfernung
dokumentiert. Der gerenderte Sweep ist fuer AK-T002-12 **strenger** als ein
Grep: ganzer Compose-Baum inkl. ContentDescription, acht Zustaende, faengt zur
Laufzeit zusammengesetzte Strings, eigener Leer-Guard. **Gemeinsame Grenze
beider Ansaetze:** Sie decken nur, was heute existiert — eine neue Oberflaeche
mit derselben Formulierung an anderer Stelle braucht in beiden Faellen jemanden,
der die Liste erweitert.

### Wofuer die vier Fixtures stehen — Festlegung des Directors

- `bt_manager_pixel11_ldac_990_loss.txt` — **der Verlustfall fuer AK-T009-24**
  (`dropped` 0→1851, `dropouts` 0→74, HIGH gepinnt, keine ABR-Zeilen).
- `..._abr_rung1_660.txt` + `..._abr_rung3_492.txt` — **die nichtmonotone
  Leiter**. Zusammen mehr wert als einzeln: Ein Test kann damit pruefen, dass
  der Parser zwei **unterschiedliche** reale Stufen auseinanderhaelt, statt nur
  „der Block ist da".
- `..._paused_990.txt` — **Beleg zu QA-007, sonst nichts.** Er zeigt, dass
  Pausieren allein `quality mode` und Rate **nicht** blankt, der `isEmpty`-Pfad
  also dadurch nicht ausgeloest wird. Die hoeheren Zaehlerstaende gegenueber
  `990_loss.txt` sind Nebenprodukt der spaeteren Aufnahme, **kein**
  Delta-Testfall. Wer daraus einen Zwei-Poll-Test baut, muss das eigens
  begruenden — die Fixture ist dafuer nicht erhoben worden.
