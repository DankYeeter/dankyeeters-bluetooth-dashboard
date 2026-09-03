# T-034 — QA-Retest (abgelegt vom Director, L-005)

STATUS: **erledigt** · 2026-09-03 · Ergebnis: **Abnahme, mit einer Einschraenkung**

Geprueft wurde Commit `0dbea4e`, `Ldac990LossGoldenTest.kt`, sechs Tests.
Alle Mutationen liefen in einem `git archive`-Klon im Scratchpad, nicht im Repo.

## 1. Die vier Mutationen des `developer` — alle vier reproduziert

| Mutation | erwartet | gemessen |
|---|---|---|
| M1 Zaehler-Label verfaelscht | 2 Tests | **2 rot**, `field 1 expected:<0> but was:<null>` |
| M2 Bitrate fest 660 | 3 Tests | **3 rot**, `expected:<990> but was:<660>` |
| M3 `abrIndex` default `0` | 1 Test | **1 rot**, `expected null, but was:<0>` |
| M4 `isOffloaded` fest `true` | 2 Tests | **2 rot**, `expected:<HOST_ENCODED> but was:<OFFLOADED>` |

Meldetexte woertlich deckungsgleich mit dem Entwicklerbericht. Zuordnung
nachgerechnet: Test 1←M4, 2←M1, 3←M2, 4←M3, 5←M1/M2/M4, 6←M2.
**Kein Vakuum-Fall (QA-012) in dieser Datei.**

## 2. Vierzehn eigene Mutationen — fuenf zusaetzliche Treffer

**Von der neuen Datei gefangen, vom `developer` nicht geprueft:**

- **Q1** Zaehlertausch `dropped` gegen `dropouts` → 2 rot
- **Q2** Abschnittsgrenze `A2DP State:` schliesst nie → 1 rot. **Das ist die im
  Auftrag vermutete Luecke, und sie haelt:** der Parser liest dann die letzte
  der drei `Counts (underflow)`-Zeilen (LE-Audio-HAL, Wert 0) statt der
  A2DP-Zeile (623).
- **Q12** dieselbe Aufweichung im `A2DP LDAC State:`-Block → 5 rot
- **Q14** kein Vorzug fuer das verbundene Geraet → 5 rot. Dass die Fixture ein
  echtes Mehrgeraete-Dump ist, nagelt diese Regel fest — **der einzige gesehene
  Nachweisweg dafuer**, und der Entwicklerbericht behauptet ihn nicht einmal.
- **Q17** zweite `assertNull` (ABR-Adjustments) → 1 rot

**Golden gruen, im Modul gefangen** (Grenze der Datei, kein Loch im Baum):
Q4 Offload-Erkennung faellt aus (5 rot anderswo) · Q5 Enqueue-Deviation ·
Q6 Nullbitrate durchgereicht · Q7 `Config: Invalid` ignoriert · Q10 `flushed`
fest 0 · Q23 ABR-Label verfaelscht.

**Zwei Ueberlebende der vollen Suite** → QA-014, QA-015 in `qa/findings.md`.

## 3. Wiederherstellung belegt

`git diff -- core-monitor` leer. `git hash-object` von Parser und Testdatei
identisch mit `git rev-parse 0dbea4e:<pfad>`. `git status --short`
zeichengleich mit dem Sitzungsbeginn.

## 4. Die Kernaussage — prueft das ueberhaupt AK-T009-24?

**Nein.** Unabhaengig hergeleitet und **mechanismus-gebunden belegt** (R1):
Baut man den urspruenglichen QA-001-Fehler wieder ein
(`STACK_DROPOUTS to dropouts + underflows`), bleiben **alle sechs Tests gruen**;
rot werden nur vier andere Tests, alle mit handgesetzten Zaehlern.

Kein Test importiert `A2dpTxDelta`, `TxLossChannel` oder `LossRow`. Die
Verdikt-Maschine fehlt nachweislich (eigener Grep: zwei Treffer, beide
Kommentare; kein `enum class`).

**Was der Test stattdessen leistet, und das ist echt:** Er nagelt die
**Eingaben** fest, auf denen jedes kuenftige Verdikt beruhen wird — inklusive
der beiden Abschnittsgrenzen, an denen der Parser die Zahlen eines fremden
Funksystems lesen koennte.

## 5. Textpruefung in Test 4 — **vertretbar, kein Befund**

Die erste Assertion liest nur `dumpText` und kann strukturell durch keine
Codeaenderung rot werden. Die beiden `assertNull` sind mechanismus-gebunden und
**einzeln** belegt (M3 und Q17). Der Test benennt die Zweiteilung im eigenen
KDoc — der Fall, den QA-003/QA-004 sanktioniert haben (Kommentar behauptet
Deckung, die nicht existiert), liegt hier gerade nicht vor. Die Textpruefung ist
ausserdem ein sinnvoller Waechter gegen stille Aenderung der Fixture.

Gemessene Grenze: Q23 (ABR-Label verfaelscht, Parser kann ABR-Zeilen gar nicht
mehr lesen) laesst Test 4 gruen — er unterscheidet also nicht „meldet Abwesenheit
korrekt“ von „kann ABR nicht mehr lesen“. Hinnehmbar, weil drei andere Tests das
abdecken (nachgemessen). Ein Verweis im KDoc waere eine Verbesserung, kein Mangel.

## 6. Suite-Zahlen — eigenhaendig gemessen, beide bestaetigt

| Lauf | tests | failures | errors |
|---|---|---|---|
| `0dbea4e` unveraendert | **2482** | 0 | 0 |
| dieselbe Basis ohne die neue Datei | **2470** | 0 | 0 |

Differenz **12** = 6 Methoden x (Debug + Release). Die frueher notierten „2390“
passen zu dieser Zaehlweise nicht; die Korrektur in `docs/state.md` ist
begruendet.

## 7. Was der QA-Lauf ausdruecklich nicht getestet hat

Instrumentierte Tests/Robolectric/Geraet (existiert nicht, laut Auftrag nicht
noetig) · `:app` unter Mutation R1 (Lauf brach nach 4 roten `core-monitor`-Tests
ab — „4“ ist damit eine **Untergrenze** dafuer, wie viele Tests R-D tragen) ·
Mutationen an `A2dpTxProbe.kt` selbst (durch Q4 indirekt) · andere Fixtures
anzubinden (Scope-Grenze) · Performance (325 KB, unauffaellig, nicht vertieft).

## 8. Entscheidungen des Directors zu den beiden Rueckfragen

**Frage 1 — zaehlt die Zielaussage als erfuellt?** Fuer den Zaehler- und
Bitratenpfad ja, fuer den Verlust-Verdikt-Pfad nein. **Meine
Auftragsformulierung war zu weit**; die Einschraenkung wird uebernommen und in
`qa/findings.md` festgehalten. Der Task ist abgenommen, die Luecke bleibt
sichtbar offen.

**Frage 2 — genuegt eine Doppelaufnahme fuer AK-T009-24 (b)?** **Ja.** Die
Neufassung in `UI_SPEC.md:2361` lautet „Snapshot `underflows` = 0, `dropped` =
525, `dropouts` = 21 **in 97 s**“ — das sind **Fensterwerte, keine absoluten
Zaehlerstaende**. Der QA-Lauf hat sie als absolute gelesen, daher die Sorge.
Genau dieser Fall ist in T-022 aufgetreten und in `dumps/README.md`
festgehalten: `underflow` stand 623 → 623 still, waehrend `dropouts` stiegen.
**Bedingung an T-036:** Das Intervall muss eines sein, in dem `underflow` sich
nicht bewegt und `dropouts` zaehlen.
