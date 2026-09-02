# Design & UX Review

## Review vom 2026-09-02 (T-017)

**Methode:** Code-Analyse + vorhandene Robolectric/Compose-Testbelege.
**Kein Geraet verfuegbar** — alle drei Befunde sind **unverifiziert
(Code-Analyse)**: ich habe den tatsaechlichen Bildschirm nicht gesehen,
nur `LiveLinkPanel.kt`, `LiveLinkGraph.kt`, `LiveTraceModel.kt` und die
zugehoerigen Tests unter `app/src/test/.../monitor/`. Wo ein Test die
Behauptung stuetzt, ist er benannt; wo keiner existiert, steht das dabei.
**Gepruefte Bereiche:** ausschliesslich die drei im Auftrag `T-017`
benannten Punkte in `LiveLinkPanel` und `LiveLinkGraph` — kein
Gesamt-Review dieses Zyklus.
**Gesamturteil:** Punkt 1 ist ein loesbares Formulierungsproblem, Punkt 2
ist eine bereits spezifizierte, aber nicht gebaute Korrektur, Punkt 3 war
bei Ersterfassung der schwerste Befund — inzwischen durch DR-004
ueberholt: ein Vorbefund des `developer`, dass die Grundformulierungen der
Verlustzeile selbst (nicht nur die Underflow-Zeile oder die Warteschlange)
seit T-002 gegen die eigene Wortverbotsliste verstossen.

**Nachtrag 2026-09-02 (Rueckfragen des Coordinators vor dem Retest):** drei
Rueckfragen entschieden, siehe `UI_SPEC.md` Nachtrag T-017 fuer die vollen
Begruendungen:
1. „audibly" in der urspruenglichen Erklaerung der Underflow-Zeile verletzt
   AK-T009-43 im Geist (Wortfamilie, nicht Zeichenkette) — korrigiert auf
   „where stack dropouts ran throughout".
2. Der 8-dp-Versatz durch `ExplainedRow` wird hingenommen, keine
   Sonderbehandlung der geteilten Komponente.
3. Neuer Befund **DR-004** (unten): „Audio lost", „No loss this window."
   und der Graph-Default „no loss in this window" verletzen `AK-T002-12`
   wortgleich, seit T-002 (30.08.), kein Regress von T-017.
4. Zusatzfrage zu „audible" auf dem EQ-Screen: `AK-T009-43` gilt nur fuer
   Monitor-/Link-Oberflaechen, nicht app-weit — die EQ-Formulierungen sind
   eine andere Aussage mit anderer Beleglage und bleiben unangetastet.

**Nachtrag 2026-09-02, zweite Runde:** DR-002 ist gebaut und getestet —
✔ unten markiert, nicht geloescht. Bei DR-004 sind zwei der drei Zweige
gebaut; fuer den dritten (`"Audio lost: …"`) lag keine ratenlose Vorgabe
vor, weil die volle T-009-Zustandsmaschine fehlt (Ratenparameter offen,
kein Ereignis-Alter). Entscheidung: Option (a), reduzierte Sofortformel
ohne Rate — Wortlaut und Begruendung in `UI_SPEC.md` Nachtrag T-017. Details
unten unter DR-004.

### Kritisch

- **✔ DR-002 [`LiveLinkGraph.kt`, `LiveTraceModel.kt`] — behoben,
  2026-09-02.** War: Graph-Caption beschriftete `lossTotal`
  (Ereigniszahlen `dropped + dropouts`) als „N loss marks", obwohl
  `LinkTraceGraph` nur eine Marke je Punkt mit `hasLoss == true` zeichnet —
  525 `dropped` in einem Fenster ergaben „525 loss marks" bei einer
  gezeichneten Marke (`GOAL.md` AK-3, „irrefuehrend"). Entscheidung war
  „Marken zaehlen" (`AK-T002-11`, seit T-002 bindend, nicht neu erfunden).
  **Jetzt:** `caption()` liefert „{k} of {n} windows lost something"
  (KDoc in `LiveLinkGraph.kt` nennt DR-002 ausdruecklich als Anlass);
  `LiveLinkPanelScreenTest.kt` prueft `"1 of 20 windows lost something"`
  und `"1 of 2 windows lost something"` — der Test, der die alte
  Fehlbeschriftung festschrieb, ist mitkorrigiert. Keine Nacharbeit offen.

- **DR-003 [`LiveLinkPanel.kt:398-406`, `TxRows`]** „Bluetooth is falling
  behind: {N} packets queued." steht unveraendert in `bodyMedium` +
  `colorScheme.error`, in der ersten Ebene, ausgeloest bei jedem
  `savedTxQueueLength > 0` — kein Fenster, kein Anteil, keine Schwelle.
  Das verletzt `AK-T009-29` wortgleich. Belegt durch die eigenen Messungen
  in `UI_SPEC.md` (T-009, „Warteschlangendruck statt
  Einzelpaket-Alarm"): `savedTxQueueLength > 0` tritt im **gesunden**
  Normalbetrieb in 0–1,4 % der Lesungen auf (A0: 1/70, A': 0/70, T-007:
  2/262) und ist dort ausdruecklich „der Normalbetrieb des Reglers, kein
  Zurueckfallen" — unter Ueberlast dagegen in 79–81 % (55/70, 129/160). Die
  heutige Zeile kann diese beiden Faelle nicht unterscheiden: sie feuert
  bei **jedem einzelnen** Nicht-Null-Sample, also unvermeidlich auch in
  gesunden Sitzungen, in Fehlerfarbe, ohne Bezugsrahmen. Kein Test deckt
  `TxRows` in diesem Zweig ab — ich habe keine Faelle mit
  `savedTxQueueLength > 0` in `LiveLinkPanelScreenTest.kt` gefunden.
  **Nur bewertet, nicht geloest** (Auftragsgrenze) — Einordnung siehe
  „Prioritaet fuer den naechsten Zyklus" unten.

- **DR-004 [`LiveLinkPanel.kt:350/365`, `LiveLinkGraph.kt:207`]** Die
  Grundformulierungen der Verlustzeile verletzen `AK-T002-12` woertlich,
  seit T-002 (30.08., kein Regress von T-017 — Vorbefund des `developer`,
  hier quittiert): „Audio lost: …" (Zeile 350) macht das Audio zum Subjekt
  und ist wortgleich verboten; „No loss this window." (Zeile 365) enthaelt
  die verbotene Zeichenkette „no loss"; der Graph-Default `quietText = "no
  loss in this window"` (`LiveLinkGraph.kt:207`) greift ungenutzt-override
  auf der 60-s-Uebersicht und enthaelt sie ebenfalls. Das ist der am
  breitesten wirksame Befund dieses Durchlaufs: er tritt bei **jedem**
  Render der Zeile auf, nicht nur in einem Randfall wie DR-003 (0–1,4 %) —
  „No loss this window." ist im Normalbetrieb die am haeufigsten gezeigte
  Zeile der gesamten App. Er widerspricht direkt R-A, dem Grundsatz, aus
  dem AK-T002-12 ueberhaupt folgt.

  **Stand 2026-09-02, 2 von 3 Zweigen behoben:** `"No loss this window."`
  und der Graph-Default sind auf `"No counter moved in the last {W} s."`
  umgestellt (T-002-Formulierungstabelle, unveraendert bindend — kein
  neuer Wortlaut noetig gewesen). Fuer den dritten Zweig (`"Audio lost:
  …"`, `LiveLinkPanel.kt:350`) gab es keine anwendbare Zeile in der
  Tabelle: `OCCASIONAL`/`DISTURBED` brauchen eine Rate ueber
  `LOSS_WINDOW_MS` (offen — T-001) oder ein Ereignis-Alter (D-8), beides
  fehlt, weil die volle T-009-Zustandsmaschine nicht gebaut ist (siehe
  Backlog-Eintrag unten). **Entscheidung: reduzierte Sofortformel ohne
  Rate**, `"${parts.joinToString(", ")} in the last ${W} s."` — der
  bestehende String minus dem Praefix „Audio lost: ", kein neuer Text.
  Subjekt sind jetzt die Zaehler-Fragmente (R-A erfuellt); ohne Rate- oder
  Dauer-Suffix ist der Satz strukturell nicht mit `OCCASIONAL`/`DISTURBED`
  verwechselbar und behauptet keine Haeufigkeit, die nicht gemessen wird.
  Vollstaendige Begruendung: `UI_SPEC.md` Nachtrag T-017. **Damit ist die
  Wortlaut-Entscheidung fuer alle drei Zweige abgeschlossen** — offen ist
  nur noch die Umsetzung des dritten.

### Wichtig

- **DR-001 [`LiveLinkPanel.kt:354-361`, `LossRow`]** Die neue Zeile „{N}
  encoder underflow(s) in the last {W} s." steht direkt unter „No loss
  this window." in identischer, korrekt ruhiger Formatierung
  (`bodySmall`/`onSurfaceVariant` — keine neue Betonungsstufe, R-E
  eingehalten, keine verbotenen Woerter). Das verbleibende Problem ist
  reine Lesbarkeit im Kontext: „underflow" liest sich fuer eine Person, die
  die T-009-Kanalrangfolge nicht kennt, wie ein Defektwort — direkt unter
  einer Entwarnungszeile. Getestet in
  `LiveLinkPanelScreenTest.kt::an underflow-only window stays quiet and
  still shows the count` (Text vorhanden, Kontrastfrage nicht gepruft, weil
  keine Zusicherung zur Nachbarschaft/Erklaerung existiert). Entscheidung
  und Wortlaut-Empfehlung siehe unten und `UI_SPEC.md` Nachtrag T-017:
  Satzform beibehalten, Zeile auf `ExplainedRow` umstellen und die
  R-D-Aussage als Erklaerung hinter dem Fragezeichen mitgeben, statt sie in
  der ersten Ebene auszuformulieren (Textwachstumsverbot AK-T002-13).

### Nice-to-have

Keine in diesem engen Scope.

### Backlog (geparkt)

- Die vollstaendige T-009-Zustandsmaschine (Coverage, funf Zustaende,
  Rate-oder-Alter, `LOSS_WINDOW_MS`-Aggregation) scheint in
  `LiveLinkPanel.kt` noch nicht implementiert zu sein — `LossRow` liest
  weiterhin rohe Poll-zu-Poll-Deltas statt eines 60-s-Fensters. Ausserhalb
  des Auftragsscopes, nicht geprueft; nur als Hinweis fuer den naechsten
  Zyklus notiert.

### Positiv / beibehalten

- Die Entscheidung, `Counts (underflow)` sichtbar zu halten statt zu
  streichen, ist richtig und AK-2-konform — der `developer` hat das
  korrekt selbst so begruendet.
- Die Farbwahl der neuen Zeile (ruhige Stufe, keine neue Betonungsebene,
  kein Pill) ist exakt richtig kalibriert und sollte bei der Umsetzung der
  Wortlaut-Korrektur (DR-001) nicht angetastet werden.
- `LADDER_PINNED` unangetastet zu lassen (T-009) bleibt die richtige
  Entscheidung — ausserhalb des heutigen Scopes, aber als Referenz notiert,
  falls ein spaeterer Durchlauf sie in Frage stellt.

### Prioritaet fuer den naechsten Zyklus (Einschaetzung, keine Loesung)

Reihenfolge nach Schwere, Stand 2026-09-02 (DR-002 erledigt und aus der
Liste genommen, DR-004 jetzt zu zwei Dritteln erledigt):

1. **DR-004, dritter Zweig (`"Audio lost: …"`)** — hoechste verbleibende
   Prioritaet. Einziger noch offener Rest des Befunds mit der groessten
   Reichweite (jedes Rendern der lossy-Verlustzeile) und dem sichtbarsten
   R-A-Verstoss der Oberflaeche. Wortlaut liegt vor (siehe oben und
   `UI_SPEC.md` Nachtrag T-017), reine Umsetzung — kleiner Eingriff
   (Praefix-Entfernung an einer Stelle).
2. **DR-003 (AK-T009-29)** — zweite Prioritaet. Einziger verbleibender
   Befund, der **aktiv** waehrend gesunder Nutzung einen echten Fehlalarm
   in Fehlerfarbe erzeugt (0–1,4 % Nicht-Null-Lesungen im gesunden
   Betrieb), statt nur eine falsche Beschriftung zu tragen — und er ist
   ungetestet. Fix bereits vollstaendig spezifiziert, bestbelegter
   Schwellenwert der gesamten Vorgabe (`LADDER_QUEUE_PRESSURE_FRACTION =
   0,20`, 14-facher Abstand zum hoechsten Ruhewert, 4-facher Abstand zum
   niedrigsten Ueberlastwert — UI_SPEC.md, T-009).
3. **DR-001 (Underflow-Wortlaut)** — niedrigste Prioritaet. Kein
   Fehlalarm, keine Fehlerfarbe, keine verbotene Formulierung — nur ein
   Lesbarkeits-/Vertrauensrisiko in der Zeilennachbarschaft. Entscheidung
   und Wortlaut liegen vor (`UI_SPEC.md` Nachtrag T-017, inkl. Korrektur
   der „audibly"-Erklaerung), die Umsetzung ist klein (Text-zu-
   `ExplainedRow`, kein neues Token, 8-dp-Versatz hingenommen).

### Offene Fragen an den App Designer

Keine. Alle drei Punkte waren nach Auftragslage und vorhandener
Vorgabe/Messlage entscheidbar; die Entscheidungen stehen oben und in
`UI_SPEC.md` Nachtrag T-017.
