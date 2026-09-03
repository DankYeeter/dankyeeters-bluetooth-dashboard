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

## Zyklus 2 — 2026-09-03

**Ziel des Zyklus:** Research-Block R-008/009/010 auswerten, Zielbild neu
fassen und vom Nutzer abnehmen lassen (`GOAL.md`), zwei Geraete-Read-backs
(T-032, T-035) und eine Recherche (R-011) zur BQR-Queue, einen Golden-Test auf
den echten 990er-Verlustdump binden (T-034) und dessen QA-Retest fahren, sowie
eine Befundkette QA-014..QA-018 ueber vier Runden abschliessen (T-038 plus
zwei Nachtraege plus Bestaetigungslauf).

**Gut gelaufen — ausdruecklich benannt, damit es nicht verloren geht:**

- **Der Rot-vorher-Beleg hat wiederholt echte, sonst unsichtbare Luecken
  offengelegt**, nicht nur formal abgehakt. Der QA-Retest zu T-034 fuhr 14
  eigene Mutationen zusaetzlich zu den vier des `developer` und fand zwei
  Ueberlebende (QA-014, QA-015) — beide durch Rot-vorher am Meldetext
  verifiziert, nicht nur "Test existiert". In der QA-018-Kette bewahrte
  dieselbe Disziplin das Projekt vor einem Schein-Fix: die zwei neuen Tests
  wurden **einzeln** mutiert und jede Mutation traf **genau** den dafuer
  vorgesehenen Test (`T-038-retest-bericht.md`, Abschnitt "Zweiter Lauf").
  Diese Praxis braucht keine Aenderung, nur Fortsetzung.
- **Rollenuebergreifende Fehlerannahme ohne Verteidigung, mit Mehrwert statt
  blosser Korrektur** — derselbe Zug wie in Zyklus 1, diesmal an anderer
  Stelle bestaetigt: Der `developer` erkannte und begruendete mathematisch,
  dass der vom `qa-engineer` (und vom Director befuerwortete) Testvorschlag
  fuer QA-018 den Befund strukturell nicht haette binden koennen, und baute
  einen echten negativen Fall statt den Vorschlag auszufuehren
  (`T-038-retest-bericht.md`, "QA-018 geschlossen"). Der `qa-engineer` nahm
  die Herleitung an und raeumte den eigenen fruesheren Vorschlag **aktiv**
  als untauglich ein, statt ihn stillschweigend fallen zu lassen.
- **Funde ausserhalb des eigenen Scopes wurden gemeldet, nicht nebenbei
  mitgeaendert.** Der `developer` fand beim QA-016-Fix eine dritte,
  nicht beauftragte Fundstelle und legte sie dem Director zur Freigabe vor,
  statt sie einfach mitzupatchen (`T-038-bericht.md`, "Der dritte Fundort war
  ein Zufallsfund"). Ebenso meldete er transparent den eigenen
  Encoding-Fehltritt beim Gedankenstrich, bevor er committete.
- **`docs/state.md` wurde von 834 auf ~270 Zeilen verdichtet** (`4f84e9e`),
  was den unten beschriebenen Fehler erst sichtbar machte — die Verdichtung
  selbst war der richtige Schritt, nur zu spaet angesetzt (siehe L-002).

### L-002 — Eine Abwesenheits-, Unveraendert- oder Nichtlieferungs-Behauptung stuetzt sich auf eine gealterte oder abgeleitete Quelle statt auf eine direkte Pruefung des aktuellen Bestands

**Belege:**
- 2026-09-01: `archivist` meldete, `GOAL.md` fehle im Repo-Root — die Datei
  lag dort tatsaechlich (`docs/lessons.md`, Zyklus 1, Beobachtungen).
- 2026-09-02: Zwei `researcher`-Laeufe brachen am Nutzungslimit ab; daraus
  wurde geschlossen, sie haetten nichts geliefert. Der `archivist` fand beide
  Recherchedateien **vollstaendig** auf der Platte. Ein gemeldeter
  Agentenabbruch heisst nicht, dass nichts geschrieben wurde — diese Lehre
  ging beim Verdichten von `docs/state.md` verloren und wird hiermit
  nachgetragen (Direktoranweisung fuer diesen Zyklus).
- 2026-09-03: `docs/state.md` trug bei 834 Zeilen gleichzeitig „PII
  bereinigt" (Zeile 188) und „OFFEN: PII in drei Messberichten — blockiert
  deren Commit" (Zeile 719) — eine laengst erledigte, nie entfernte Notiz.
  Wer nur den hinteren Abschnitt las, mutmasste einen offenen Blocker, der
  keiner mehr war (belegt per `git show bcf0aa4:docs/state.md`).

**Ursache:** Keine Konvention verlangt, dass eine Aussage ueber Abwesenheit,
Unveraendertheit oder Nichtlieferung an der **primaeren** Quelle (Dateisystem,
Git, aktueller Code-/Repo-Zustand) geprueft wird, bevor sie getroffen wird —
eine **sekundaere** Quelle (eine Notiz, ein Statuswort wie „abgebrochen", der
zuerst gelesene Teil eines langen Dokuments) genuegt bislang. Zusaetzlich
verletzte `docs/state.md` wiederholt seine eigene, am Kopf der Datei stehende
Regel „Erledigtes fliegt raus" — die Datei wuchs vier Sitzungen lang, bevor
verdichtet wurde, was genug Zeit liess, dass eine ueberholte Zeile stehen
blieb und falsch gelesen wurde. Dies ist die durch ein drittes Vorkommen
bestaetigte Fortsetzung der Zyklus-1-Beobachtung „unvollstaendige
Zustandspruefung vor einer Abwesenheits- oder Unveraendert-Behauptung".

**Massnahme (Vorschlag — betrifft den Uebergabe-Kontrakt bzw. `CLAUDE.md`,
siehe Grenzen; keine Aenderung durch mich):**

1. Im Uebergabe-Kontrakt, Abschnitt zu `GELESEN`/Berichtsdisziplin, ergaenzen:
   *„Eine Aussage der Form 'nicht vorhanden', 'nicht geliefert' oder
   'unveraendert' wird nur getroffen, nachdem der aktuelle Bestand direkt
   geprueft wurde (Dateisystem, `git status`/`git show`, Grep) — nicht aus
   einer abgeleiteten Quelle wie einer Notiz oder einem Statuswort
   uebernommen. Ein gemeldeter Abbruch eines Agentenlaufs ist keine Aussage
   ueber das Ergebnis; das Dateisystem entscheidet."*
2. Am Kopf von `docs/state.md` (Vorschlag an den `archivist`, der die Datei
   pflegt) ergaenzen: *„Wird eine Zeile durch einen neuen Eintrag ersetzt
   oder widerlegt, wird die alte Zeile im selben Bearbeitungsschritt entfernt
   — nicht gesammelt bis zur naechsten Verdichtung."*

**Erfolgskriterium:** In den naechsten zwei Zyklen taucht keine weitere
Abwesenheits-/Nichtlieferungs-Behauptung mehr auf, die sich als falsch
herausstellt, weil eine sekundaere statt der primaeren Quelle geprueft wurde.
Gleichzeitig bleibt `docs/state.md` bei jeder Pruefung unter ~350 Zeilen,
ohne dass eine grosse Verdichtung noetig wird.

**Status:** vorgeschlagen (2026-09-03) — Nutzerentscheidung noetig, betrifft
Uebergabe-Kontrakt/Konvention.

**Kostenabwaegung:** Ein zusaetzlicher Pruefschritt vor genau der Art
Aussage, die dreimal in zwei Zyklen zu einer falschen Behauptung fuehrte,
zuletzt mit einem konkreten Fehlbefund (PII-Blocker). Der zweite Teil der
Massnahme ist Selbstdisziplin ohne Mehraufwand — er verschiebt Arbeit nicht,
er verteilt sie nur anders (sofort statt gesammelt). Nutzen uebersteigt Kosten.

### L-003 — Ein Kommentar- oder Textbefund gilt als erledigt, sobald die im Befund genannten Stellen behoben sind, statt wenn projektweit gesucht wurde

**Belege:** QA-016 (falsche `Effective MTU`-Zahl im Kommentar) wurde in drei
aufeinanderfolgenden Commits an Stellen behoben, die der `developer` zufaellig
gesehen hatte (`374be69`, `5218455`, `de2454b`). Erst ein vom Director
verlangter projektweiter Grep als Abschlussprobe deckte einen vierten
Fundort auf (QA-017); erst der vom `qa-engineer` **unabhaengig mit eigenen
Suchmustern** gefahrene Gegen-Grep belegte, dass es keinen fuenften mehr gibt
(`T-038-retest-bericht.md`, "Zweiter Lauf"). Vier Runden fuer einen reinen
Kommentarfehler ohne Verhaltensaenderung.

**Ursache:** Kein Schritt im Ablauf eines Kommentar-/Textbefunds verlangt
projektweite Suche als Abschlusskriterium; „die genannten Stellen sind
korrigiert" wird mit „der Befund ist erledigt" verwechselt, obwohl der Befund
selbst nie eine vollstaendige Fundstellenliste behauptet hatte.

**Massnahme (Vorschlag — betrifft `qa-engineer`- und `developer`-Konventionen
bzw. `CLAUDE.md`, siehe Grenzen; keine Aenderung durch mich; Formulierung ist
bereits im Team entstanden, `qa/findings.md`, "Die Lehre aus dieser Kette",
und wird hier nur uebernommen statt neu erfunden):** *„Ein Kommentar- oder
Textbefund gilt erst als behoben, wenn projektweit gesucht wurde (Grep ueber
den vollstaendigen Baum mit mindestens einer vom urspruenglichen Fund
unabhaengigen Suchmaske) und diese Suche im Bericht mit Trefferzahl
dokumentiert ist — nicht, wenn die im Befund genannten Stellen korrigiert
sind."*

**Erfolgskriterium:** In den naechsten zwei Zyklen braucht ein
Kommentar-/Textbefund hoechstens eine Korrekturrunde plus eine
Bestaetigungsrunde (zwei statt vier), weil die erste Runde bereits
projektweit sucht.

**Status:** vorgeschlagen (2026-09-03) — Nutzerentscheidung noetig.

**Kostenabwaegung:** Ein Grep-Lauf und ein Satz Dokumentation je
Textbefund — Sekunden, gegen drei zusaetzliche Runden Berichte, Commits und
Retests, die diese Sitzung tatsaechlich gekostet hat. Nutzen uebersteigt
Kosten deutlich.

### Wirkungskontrolle frueherer Massnahmen

| ID | Massnahme | Uebernommen am | Wirkung | Konsequenz |
|---|---|---|---|---|
| L-001 | Randbedingung bei jeder tragenden Aussage nennen | nicht foermlich uebernommen (Status weiterhin „vorgeschlagen") | Einmal informell bereits angewendet, **vor** diesem Zyklus: `ARCHITECTURE.md` Zeile 1413 formuliert eine Aussage explizit mit „Randbedingung dieser Aussage, damit sie nicht ueberdehnt wird" (Commit `569a2d0`, 02.09.). In den Berichten dieses Zyklus (T-034, T-038) keine Verletzung des L-001-Musters gefunden. | Keine Zwei-Zyklen-Bewertung moeglich, solange der Nutzer nicht entscheidet. Weiter vorschlagen, nicht wiederholen — ein zweiter identischer Vorschlag waere Regelinflation. |
| — (02.09., ad hoc) | „Absenz-Behauptungen brauchen Durchsicht" (Read-back deckt das vollstaendige Zustandsbuch ab) | 02.09., nicht als L-ID gefuehrt | **Teilweise gewirkt.** Fuer Geraete-Read-backs (T-032, T-035) keine Verletzung in den Material dieses Zyklus. Die Regel deckte aber nur Geraetezustand ab — der strukturell gleiche Fehler trat am 03.09. in anderer Form auf (veraltete `docs/state.md`-Zeile, siehe L-002). | In L-002 verallgemeinert: nicht mehr auf „Zustandsbuch" beschraenkt, sondern auf jede Abwesenheits-/Nichtlieferungs-Behauptung. |
| — (02.09., ad hoc) | „Eine Regel aus wenigen Beispielen ist eine Hypothese" | 02.09., nicht als L-ID gefuehrt | **Griff hier nicht** — der Director befuerwortete den QA-018-Testvorschlag des `qa-engineer` ausdruecklich, ohne ihn zu pruefen (`T-038-retest-bericht.md`, „Fehler des Directors in dieser Kette"). Analyse: andere Mechanik als die Regel abdeckt. Die Regel warnt vor **eigener** Verallgemeinerung geprueften Wissens; hier wurde eine **fremde, ungeprüfte** Aussage weitergereicht, nicht generalisiert. Die Regel hatte diesen Fall nicht im Blick. | **Kein Muster** (ein Vorkommen) — bleibt Beobachtung unten, keine neue Massnahme. Aufgefangen durch die bestehende Rot-vorher-Pflicht beim `developer`, ohne dass Schaden entstand — das Sicherheitsnetz hat gehalten. |
| Rot-vorher-Beleg (Nutzerregel, projektuebergreifend) | „Ein gruener Test zaehlt erst, wenn er beim Wiedereinbau des Fehlers rot wird" | vor Zyklus 1 in Kraft | **Wirkt weiter, mehrfach belegt.** T-034 (4+14 Mutationen), T-038/T-038-Retest (Mutationen einzeln, treffsicher). Keine Aenderung vorgeschlagen. | Beibehalten, keine Massnahme noetig. |

### Beobachtungen (noch kein Muster)

**Neu (1 Vorkommen) — Der Director befuerwortet einen fremden Vorschlag aus
einem Pruefbericht in einem Auftrag, ohne ihn selbst zu pruefen:**
- 2026-09-03: Der `qa-engineer` schlug fuer QA-018 Testadressen vor
  (`xx:xx:xx:xx:11:CD` / `22:33:44:55:11:cd`), die die geprüfte
  `takeLast(5)`-Fensterbreite strukturell nicht binden konnten — beide Tails
  stimmen auch bei `takeLast(2)` ueberein. Der Director uebernahm den
  Vorschlag „ausdruecklich befuerwortend" in den Auftrag an den `developer`,
  ohne die eine Zeile Nachrechnung selbst zu leisten. Der `developer` fing es
  (siehe „Gut gelaufen" oben). Kein Schaden entstanden, aber der Mechanismus
  ist real: Weiterreichen einer fremden Bewertung als eigene Freigabe, ohne
  dass „befuerworten" eine tatsaechliche Pruefung bedeutete. Sollte sich das
  wiederholen — insbesondere bei einem Vorschlag, den keine nachgeschaltete
  Rot-vorher-Pflicht mehr auffangen kann — waere die Massnahme: *„Der
  Director prueft einen aus einem Bericht uebernommenen und im eigenen
  Auftrag befuerworteten Vorschlag mit derselben Sorgfalt wie eine eigene
  Aussage; 'befuerwortet' heisst geprueft, nicht weitergereicht."* Bis dahin:
  beobachten.

**Geprueft, kein Befund — Entwickler-Commits nur lokal bis `sync-out`:**
- 2026-09-03: Vier `developer`-Commits (`374be69` bis `5f605b1`) lagen lokal,
  bis der `sync-out` sie mitnahm. Das entspricht der vorgesehenen
  Rollentrennung (Commit durch `developer`, Push nach Pruefung durch
  `sync-out`) und ist durch `CLAUDE.md` (Remote-Kategorie-Pruefung vor Push)
  begruendet. Kein Datenverlust, keine Verzoegerung mit erkennbarer Wirkung
  in diesem Zyklus. Keine Massnahme — als gepruefter Nicht-Befund vermerkt,
  damit ein spaeterer Zyklus das nicht neu untersuchen muss.

**Fortbestehend aus Zyklus 1 (weiterhin 2 Vorkommen, kein drittes in diesem
Zyklus gefunden):**
- „Unvollstaendige Zustandspruefung vor einer Abwesenheits- oder
  Unveraendert-Behauptung" — **hiermit nach L-002 verschoben** (drittes
  Vorkommen bestaetigt, siehe oben). Aus der Beobachtungsliste entfernt.
- „Selbstkorrektur, die nur durch zusaetzlichen, nicht angeordneten
  Pruefaufwand moeglich wurde" — kein neues Vorkommen in diesem Zyklus. Die
  in T-034/T-038 gesehene zusaetzliche Pruefung durch den `qa-engineer` war
  **angeordnete** Rollenaufgabe (Mutationstests sind Konvention), nicht
  Eigeninitiative wie in den beiden Zyklus-1-Faellen — zaehlt daher nicht als
  drittes Vorkommen. Weiter beobachten.
