# T-038 — QA-Retest und Bestaetigungslauf (abgelegt vom Director, L-005)

STATUS: **erledigt** · Kette geschlossen · 2026-09-03
Geprueft: `374be69`, `5218455`, `de2454b`, `5f605b1`. Suite **2488 / 0**,
vom `qa-engineer` unabhaengig ueber 235 Ergebnisdateien gezaehlt.

## Erster Retest — QA-014/015/016

**QA-014 geschlossen.** Mutation `at(FRAMES, 1)` → `at(FRAMES, 2)`: Debug **und**
Release je 396 Tests, **1 Failure**, genau der neue Test. Kein anderer reagiert.
Wiederherstellung per `git hash-object` gegen `HEAD` belegt.

**QA-015 geschlossen fuer den Vakuum-Fall.** Der `qa-engineer` hat den Pfad
**durchgespurt**, nicht nur den Test laufen lassen: Die Kopfzeile bleibt
eingerueckt, `readStateMachine` sieht sie nicht als Abschnittsende, der
Exakt-Vergleich schlaegt fehl, nur der Fallback matcht. **Die `trimIndent()`-Falle
ist genuin umgangen.**

Nebenbefund von Wert: Das Vertauschen der `FRAMES`-Indizes 0↔1 macht **vier**
Testklassen rot (`CodecModeCalibrationTest`, `CodecModeInferenceTest`,
`Ldac990LossGoldenTest`, `LiveLinkParserTest`) — dort ist die Absicherung
breiter als vermutet.

**QA-016 zunaechst NICHT geschlossen** — vierter Fundort gefunden → QA-017.
**QA-015 nicht vollstaendig** — Parameter ungebunden → QA-018.

## Zweiter Lauf — QA-017 und QA-018

**QA-017 geschlossen.** Vierter Fundort korrigiert (`de2454b`). Danach hat der
`developer` **projektweit** nach vier Formulierungsvarianten gegrept: keine
Treffer mehr. Der `qa-engineer` hat mit **eigenen** Suchmustern gegengeprueft —
vier Treffer, alle mit den korrekten Zahlen, **keine fuenfte Stelle**.

**QA-018 geschlossen** (`5f605b1`), und hier liegt der inhaltliche Kern:

Der Vorschlag aus dem QA-015-Retest — Adressen `xx:xx:xx:xx:11:CD` gegen
`22:33:44:55:11:cd` — **haette den Befund nicht behoben.** Der `developer` hat
das gefangen und mathematisch begruendet:

> Der Fallback ist `a.takeLast(5).equals(b.takeLast(5), ignoreCase = true)`.
> `takeLast(2)` ist ein **Suffix** von `takeLast(5)`, an identischer Position von
> hinten. Stimmen zwei Strings auf 5 Zeichen ueberein, stimmen sie zwangslaeufig
> auch auf 2 ueberein. Eine Verengung kann also **nur mehr** matchen, nie
> weniger, und bleibt unter **jedem positiven** Testfall gruen. Die Fensterbreite
> bindet nur ein **negativer** Fall.

Der `qa-engineer` hat die Herleitung im Bestaetigungslauf nachvollzogen,
bestaetigt und seinen eigenen frueheren Vorschlag ausdruecklich als untauglich
eingeraeumt.

Umgesetzt als **zwei** Tests:
- `an active device is recognised across different redaction levels of the same
  address` — `xx:xx:xx:xx:11:cd` gegen `99:88:77:66:11:CD`, bindet `ignoreCase`.
- `two devices that share only their last octet are not conflated as the active
  one` — `xx:xx:xx:xx:99:CD` gegen `11:22:33:44:11:CD`, bindet die Fensterbreite.

**Rot-vorher, beide Mutationen einzeln, vom `qa-engineer` unabhaengig
reproduziert:** `takeLast(2)` → nur der Fensterbreiten-Test rot;
`ignoreCase = false` → nur der Case-Test rot. Keine Kollateraltreffer.
Produktivcode nach jedem Lauf per `git checkout --` und `hash-object` als
byte-identisch belegt. **`sameAddress` selbst ist unveraendert.**

## Fehler des Directors in dieser Kette

Ich habe den untauglichen Testvorschlag aus dem QA-Bericht uebernommen und im
Auftrag an den `developer` **ausdruecklich befuerwortet**, ohne ihn zu pruefen —
obwohl die Pruefung eine Zeile Nachdenken gewesen waere. Haette der `developer`
ihn ausgefuehrt statt hinterfragt, waere ein Test entstanden, der **aussieht wie
die Behebung des Befunds und keine ist** — genau das Muster, das die ganze Kette
beheben sollte.

Beide Lehren stehen in `qa/findings.md` und gelten weiter.

## Entscheidungen des Directors

- Der `sameAddress`-Zweig **bleibt**. „Ungetestet“ war kein Grund zum Entfernen,
  sondern einer zum Testen.
- Die Aufteilung in zwei Commits (Kommentar getrennt von Testverhalten) folgt
  der Projektkonvention und **bleibt so** — kein Zusammenfassen der Historie.
- Der ausgelassene volle Suite-Lauf fuer eine reine Kommentaraenderung war
  vertretbar; dieselbe Datei war unmittelbar davor vollstaendig gruen gelaufen.
- **Die Kette ist hier abgeschlossen.** Jede Runde fand einen kleineren Rest;
  der Bestaetigungslauf fand innerhalb seines Scopes keinen mehr. Weitere
  Runden waeren Selbstbeschaeftigung.
