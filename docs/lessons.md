# Lessons Learned

## Zyklus 1 — 2026-09-01

**Ziel des Zyklus:** Die seit dem 30.08. offene Frage "traegt 990 kbps auf
dieser Strecke?" belegt beantworten (T-007/T-008), den Transportentwurf fuer
SR-001 pruefen und abnehmen (T-006, AD-010..AD-014), und `UI_SPEC.md` auf die
durch die Messung belegte Leitgroesse umstellen (T-009). Erster Zyklus mit
Retrospektive — es existiert keine Vorgaenger-`docs/lessons.md`, daher keine
Wirkungskontrolle frueherer Massnahmen moeglich (siehe unten).

**Gut gelaufen — ausdruecklich benannt, damit es nicht verloren geht:**

- **Fehler wurden rollenuebergreifend gefunden und ohne Verteidigung
  angenommen, oft mit Mehrwert statt blosser Korrektur.** Der
  `security-reviewer` fand die vom `architect` uebersehene Bauform 3'
  (`ARCHITECTURE.md` AD-010, Nachtrag) — der `architect` uebernahm sie
  sofort in die geltende Reihenfolge. Der `architect` fand den
  Selbstwiderspruch des `security-reviewer` zwischen A10 und SR-017
  (`security/findings.md`, "Entscheidung des Directors zum Widerspruch bei
  A10") — der Reviewer bestaetigte den eigenen Fehler **und** leitete daraus
  von sich aus eine Verschaerfung seines spaeteren Retests (A16) ab, statt
  nur die Korrektur hinzunehmen. Die `ui-ux-designer` dokumentierte in T-009
  selbst, dass ihre eigene T-002-Vorgabe den einzigen belegten hoerbar
  kaputten Fall als einwandfrei gemeldet haette (`UI_SPEC.md`, "Was aus
  T-002 dadurch falsch war"), ausdruecklich, "damit ein spaeterer Durchlauf
  ihn nicht wegkuerzt". Der `performance-tuner` benannte den eigenen
  Konfundierungsfehler bei der vierten Zelle explizit als "mein
  Verfahrensfehler" (`docs/perf/T-008-experimente.md` 7b.2) und verschaerfte
  daraufhin selbst die Read-back-Regel. Das ist eine wiederkehrende,
  wertvolle Eigenschaft dieses Team-Vorgehens und sollte durch nichts an den
  Konventionen gedaempft werden.
- **Der Kalibrierpunkt der Hoerbarkeitsgrenze war kein Zufallsfund, sondern
  Auftragsdesign.** T-008 verlangte ausdruecklich ein "Phasenprotokoll mit
  Uhrzeiten", **weil** der App Designer waehrend der Laeufe mithoert
  (`docs/tasks/T-008.md`, Abschnitt "Testumgebung"). Dass Hoereindruck und
  Zaehler in allen drei Armen A0/B/A' uebereinstimmten — auch dort, wo der
  App Designer sich weigerte, einen Unterschied zu behaupten (E-1,
  wirkungslos) — ist die Folge davon, dass das Protokoll von vornherein auf
  diesen Abgleich angelegt war. Diese Praxis (Menschliches Urteil mit
  Zeitstempel neben die Messreihe legen) hat sich bewaehrt und sollte fuer
  jeden Messauftrag mit hoerbarer Wirkung Standard bleiben.

### L-001 — Eine an einem engen Fall geprüfte Aussage über einen Mechanismus wird ungeprüft auf einen weiteren Fall ausgedehnt

**Belege:**
- `ARCHITECTURE.md` AD-010, Nachtrag (Zeilen ~425–435): Der `architect`
  verallgemeinerte "ein Verzeichnis, das nur die App betreten darf, kann der
  Helper nicht beschreiben" von *Oeffnen ueber einen Pfad* auf *jeden
  Zugriff* und uebersah dadurch Bauform 3' (App-privates Verzeichnis +
  Deskriptor-Handoff). Vom `security-reviewer` gefunden.
- `security/findings.md`, Abschnitt "Entscheidung des Directors zum
  Widerspruch bei A10" (Zeilen 206–241): Der `security-reviewer` begruendete
  Auflage A10 zunaechst mit einer universellen Wirkung von `umask 077`
  (fuehrt angeblich zu Totalausfall der Live-Ansichten), obwohl er in einem
  eigenen, fruehen Befund (SR-017) korrekt beschrieben hatte, dass `umask
  077` nur *teilweise* wirkt ("auf dem Kopier-Pfad Konvention... durch jeden
  expliziten `ownerOnly=false`-Aufruf aushebelbar"). Zwei Befunde, ein
  Mechanismus, gegenlaeufige Reichweite. Vom `architect` gefunden, vom
  Director entschieden, vom Reviewer bestaetigt.
- `docs/research/R-003.md`, Abschnitt "A2DP Hardware Offload": Der
  `researcher` uebernahm die als "plausibel" markierte, aber mit hohem
  Gewicht versehene ("zentral fuer alle obigen Punkte") Sekundaerquellen-
  Annahme "Offload ist auf Pixel-Geraeten standardmaessig aktiv" — eine
  Aussage ueber Android-Geraete mit unterstuetzender HAL im Allgemeinen,
  ungeprueft auf *dieses* Geraet angewendet. `docs/perf/T-007-aufnahme.md`
  Abschnitt 2.2 widerlegte sie am Geraet eindeutig: Host-Encoding, kein
  Offload.

**Ursache:** Aussagen ueber einen Mechanismus tragen an keiner der drei
Stellen eine explizite Randbedingung, unter der sie geprueft bzw. gueltig
sind. Ohne diese Markierung ist die Grenze der Aussage fuer den Autor selbst
unsichtbar — sichtbar wird sie erst durch eine zweite Rolle oder eine
Messung, nie durch Selbstpruefung des Autors an der eigenen Formulierung.

**Massnahme (Vorschlag — betrifft Konventionen/Auftrags-Template, siehe
Grenzen; keine Aenderung durch mich):** Ergaenzung in den Konventionen fuer
`architect`, `security-reviewer` und `researcher` (bzw. zentral in einem
gemeinsamen Abschnitt, falls es einen gibt): *"Jede Aussage, die eine
Entscheidung, eine Sicherheitsauflage oder eine zentrale Schlussfolgerung
traegt, nennt in einem Satz die Randbedingung, unter der sie geprueft wurde
('gilt fuer X, weil Y' statt 'gilt'). Wird eine solche Aussage auf einen
neuen Fall angewendet — die eigene fruehere, eine andere Rolle, oder eine
Sekundaerquelle — wird zuerst geprueft, ob die genannte Randbedingung im
neuen Fall noch zutrifft, bevor die Aussage weiterverwendet wird."*

**Erfolgskriterium:** In den naechsten zwei Zyklen taucht keine Korrektur
mehr auf, bei der eine Rolle eine eigene oder uebernommene Aussage nachtraeglich
auf einen zu breiten Fall zurueckfuehren muss, ohne dass die urspruengliche
Randbedingung im Text bereits benannt war. Taucht sie doch auf, ist die
Randbedingung wenigstens explizit genannt gewesen und die Korrektur faellt
entsprechend kleiner aus (weniger Nacharbeit, weil die Grenze schon sichtbar
war).

**Status:** vorgeschlagen (2026-09-01) — Nutzerentscheidung noetig, da die
Massnahme Konventionen/Agentendefinitionen betrifft, die ich nicht selbst
aendern darf.

**Kostenabwaegung:** Ein zusaetzlicher Satz je tragender Aussage in Befunden,
Architekturentscheidungen und Recherchen. Angesichts von drei unabhaengigen
Vorkommen an einem einzigen Tag, jeweils mit realer Nacharbeit (Bauformen-
Neubewertung, Auflagen-Neuentscheidung, verworfene Kausalkette), uebersteigt
der erwartete Nutzen die Kosten.

### Wirkungskontrolle früherer Massnahmen

| ID | Massnahme | Übernommen am | Wirkung | Konsequenz |
|---|---|---|---|---|
| — | — | — | Kein vorheriger Zyklus vorhanden; `docs/lessons.md` existierte vor diesem Zyklus nicht. | Keine Wirkungskontrolle moeglich. Ab Zyklus 2 wird L-001 (und ggf. hochgestufte Beobachtungen) hier gefuehrt. |

### Beobachtungen (noch kein Muster)

**Verdacht (2 Vorkommen) — unvollständige Zustandsprüfung vor einer
Abwesenheits- oder Unverändert-Behauptung:**
- 2026-09-01: `performance-tuner` las vor der vierten Zelle in T-008 nur Pin
  und Scan-Lage zurueck, nicht das vollstaendige Zustandsbuch — WLAN war
  unbemerkt eingeschaltet und lief waehrend beider Arme, was den Arm
  konfundierte (`docs/perf/T-008-experimente.md`, Abschnitt 7b.2, "Mein
  Verfahrensfehler, ausdruecklich"). Rollenintern bereits selbst
  verschaerft: "Read-back deckt kuenftig das vollstaendige Zustandsbuch ab,
  nicht nur die geaenderte Variable" (`docs/state.md`, Abschnitt "T-008
  vierte Zelle").
- 2026-09-01: `archivist` meldete am Sessionstart, es gebe keine `GOAL.md`
  im Repo-Root, obwohl sie dort lag; der Director pruefte selbst nach und
  fand sie (aus der Auftragsbeschreibung dieses Zyklus, keine separate
  Belegdatei im Repo verfuegbar).

  Beide Faelle sind derselbe Fehler in unterschiedlichen Rollen: eine
  Behauptung ueber Abwesenheit bzw. Unveraendertheit wird anhand des Teils
  des Bestands getroffen, den die Rolle ohnehin im Blick hat (die
  manipulierte Variable; das erwartete Verzeichnis), nicht anhand einer
  vollstaendigen, definierten Pruefroutine. Sollte ein drittes Vorkommen
  auftreten — in gleich welcher Rolle — gehoert die Massnahme an **eine**
  gemeinsame Stelle (z. B. `CLAUDE.md` oder Uebergabe-Kontrakt), nicht in
  einzelne Agentendefinitionen: *"Eine Abwesenheits- oder
  Unveraendert-Behauptung wird nur getroffen, wenn die Pruefung den
  vollstaendigen dafuer definierten Bestand abgedeckt hat — und die
  Pruefmethode wird im selben Satz genannt."* Bis dahin: beobachten, nicht
  verordnen.

**Verdacht (2 Vorkommen) — Selbstkorrektur, die nur durch zusätzlichen,
nicht angeordneten Prüfaufwand möglich wurde:**
- 2026-09-01: `performance-tuner` haette im B-Arm von T-008 fast eine
  Periodizitaet berichtet, die es nicht gibt — die Ereignisabstaende im
  Standardlauf (1407 ms Kadenz) sahen diskret aus, waren aber exakte
  Vielfache der eigenen Abtastrate. Aufgefangen nur, weil zusaetzlich ein
  Lauf mit 379 ms gefahren wurde (`docs/perf/T-008-experimente.md`,
  Abschnitt 4: "Waere nur der Standardlauf ausgewertet worden, haette man
  eine Periodizitaet berichtet, die es nicht gibt.").
- 2026-09-01: `ui-ux-designer` erkannte den blinden Fleck der eigenen
  T-002-Vorgabe nur, weil T-009 einen expliziten Abgleich der neuen
  Spezifikation gegen den real gemessenen Grenzfall verlangte
  (`UI_SPEC.md`, AK-T009-24 als Regressionstest "gegen den Befund selbst").

  Beide male trug nicht die Konsistenzpruefung der eigenen Arbeit den
  Fehler zutage, sondern der Abgleich gegen einen zweiten, unabhaengigen
  Bezugspunkt (eine zweite Abtastaufloesung; ein bereits belegter
  Extremfall). Dieser zweite Bezugspunkt war in keinem der beiden Faelle
  ausdruecklich im Auftrag verlangt — bei T-008 war es Eigeninitiative des
  `performance-tuner` (fortgesetzt aus der T-007-Methodik mit Laeufen A/B/C
  unterschiedlicher Kadenz), bei T-009 eine Vorgabe der `ui-ux-designer`
  selbst. Falls sich das ein drittes Mal bestaetigt, ist die Massnahme
  bereits formulierbar, aufgeteilt nach Bereich: fuer Periodizitaets-/
  Taktfragen (Messauftraege) *"Ein Urteil ueber Regelmaessigkeit wird nur aus
  einem Lauf abgeleitet, dessen Abtastkadenz mindestens dreimal feiner ist
  als die behauptete Periode; sonst ist nur 'Phaenomen bestaetigt, Takt
  nicht beurteilbar' zulaessig."* Fuer neue Leitgroessen/Schwellenwerte
  (UI_SPEC-Auftraege) *"Jede neue oder geaenderte Leitgroesse wird vor
  Abnahme gegen mindestens einen bereits belegten Extremfall geprueft; das
  Ergebnis wird als eigenes Akzeptanzkriterium festgehalten."* Bis zum
  dritten Vorkommen: beobachten. Die Kosten waeren in beiden Faellen gering,
  weil die Praxis bereits freiwillig angewendet wird — es wuerde nur die
  Pflicht ergaenzt.

**Einzelfälle (je 1 Vorkommen, betreffen die Rolle `director`):**
- 2026-09-01: Der `director` liess das Telefon rund zwoelf Minuten in einem
  Zustand stehen, der hoerbare Aussetzer erzeugt (990 kbps gepinnt), weil er
  auf einen Hoereindruck wartete, ohne das im Protokoll zu vermerken —
  sichtbar erst als unkontrollierter Langzeitarm ohne Messwert
  (`docs/perf/T-008-experimente.md`, Abschnitt 5.1).
- 2026-09-01: Der `director` uebertrug Auflage A16 zunaechst nicht
  vollstaendig in `security/findings.md`; der `architect` bemerkte es (laut
  Auftragsbeschreibung dieses Zyklus — der aktuelle Stand von
  `security/findings.md` enthaelt A16 bereits vollstaendig, die Korrektur ist
  demnach bereits nachgetragen).
