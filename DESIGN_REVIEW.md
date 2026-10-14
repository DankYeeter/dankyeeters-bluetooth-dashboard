# Design & UX Review

## Review vom 2026-09-29 (T-065b, Retest)

**Methode:** Code-Analyse (kein Geraet, eingefrorener Stand `abe8953`) —
`LdacTuning.kt:406-430` (`tuningSentence`), `ComparisonSection.kt:134-153`
(`PinAndCheck`), `PrivilegedClient.kt:786-790` (`EFFECT_UNKNOWN`),
`DeviceConnectionWatcher.kt:84-114` gelesen; `LdacTuningTest.kt`
(`LdacTuningSentenceTest`, Test „an unanswered pin is reported as unknown,
not as unchanged"), `ComparisonSectionTest.kt` (Test „an unanswered pin is
reported as unknown, not as not pinned" — echter Compose-Render-Test,
`composeRule.onNodeWithText` gegen den woertlichen Satz),
`DeviceConnectionWatcherLogTest.kt` gelesen (Testinhalte abgeglichen, nicht
ausgefuehrt) — Testergebnisse selbst **unverifiziert (Code-Analyse)**.
`git diff --stat abe8953 12ffcd7` zeigt fuer diesen Stand nur
Doku-/Sicherheitsdateien, keine der hier gepruften Quelldateien.

**Geprueft gegen:** DR-008 (Wortlaut-Widerspruch bei TimedOut+changes=true,
siehe unten in der T-063b-Sektion), plus „unnamed device" auf UI-Sichtbarkeit.

**Gesamturteil: DR-008 ist behoben, exakt in der im Befund vorgeschlagenen
Richtung, und an beiden Fundstellen gleich — ship-ready fuer diesen
Scope.**

### DR-008 — Retest

Beide Aufrufstellen pruefen jetzt zuerst `outcome.reason.endsWith(EFFECT_UNKNOWN)`
und lassen bei Treffer den bisherigen „not changed"/„Could not pin"-Rahmen
weg — der Satz besteht dann nur aus dem grossgeschriebenen AD-048-Fragment
selbst, ohne widersprechende Behauptung:

- `LdacTuning.tuningSentence()` (`LdacTuning.kt:421-427`): fuer
  TimedOut+changes=true liefert der Test (`LdacTuningTest.kt:263-278`)
  woertlich „The privileged helper did not answer within 15 s — whether the
  change took effect is unknown." (nicht gespeichert) bzw. mit vorangestelltem
  „Stored for this headphone and asked for again on every connect. "
  (gespeichert) — kein "not changed" mehr im selben Satz wie "unknown".
- `ComparisonSection.kt` `PinAndCheck()` (Zeile 142, nicht mehr 139 — durch
  die DR-008-Kommentarzeile um drei verschoben, keine inhaltliche Abweichung):
  derselbe Fruehausstieg, Test `ComparisonSectionTest.kt:266-280` rendert
  „The privileged helper did not answer within 15 s — whether the change
  took effect is unknown. Nothing was measured." — ebenfalls kein "Could not
  pin"-Rahmen mehr fuer diesen Grund.
- Beide bisherigen, tatsaechlich definitiven Gruende („the privileged helper
  is not running", „there is no live link to change") behalten ihren
  „not changed"-Rahmen unverandert (`LdacTuningTest.kt:239-260`) — die
  Unterscheidung trifft nur den einen Pfad, der vorher falsch lag.
- „unnamed device" (`DeviceConnectionWatcher.kt:95`) bleibt, wie im Auftrag
  vorausgesetzt, ausschliesslich in zwei `Log.i`-Aufrufen (Zeile 96, 110) —
  `logLabel` fliesst nirgends in `store.noteSeen()`, einen State oder eine
  Composable; `DeviceConnectionWatcherLogTest.kt` prueft nur `ShadowLog`.
  Nicht sichtbar, daher kein UI-Befund.

Keine neuen Befunde in diesem Scope; keine Regression gegenueber dem
2026-09-29-T-063b-Review gefunden.

### Offene Fragen an den App Designer

Keine neuen.

## Review vom 2026-09-29 (T-063b)

**Methode:** Code-Analyse (kein Geraet, Auftragsgrenze T-063b, eingefrorener
Stand `483d78b`) — `PrivilegedClient.kt`, `ARCHITECTURE.md` (AD-048),
`LdacTuning.kt` (`tuningSentence`), `SettingsRestore.kt`,
`ComparisonSection.kt`, `DeviceProfilesScreen.kt`,
`MeasureStatusSection.kt` gelesen; `LdacTuningTest.kt` (Klasse
`LdacTuningSentenceTest`), `PrivilegedTimeoutTest.kt`, `BitrateSectionTest.kt`,
`MacRedactionInvariantTest.kt` gelesen (Testinhalte — welche Zeichenketten
sie binden — abgeglichen, nicht ausgefuehrt) — Testergebnisse selbst
**unverifiziert (Code-Analyse)**. Keine Screenshots (kein Geraet in diesem
Lauf, kein Robolectric-Screenshot-Pfad im Projekt). `26f7442` als Ancestor
von `483d78b` per `git merge-base --is-ancestor` bestaetigt.

**Geprueft gegen:** die zwei vom architect (AD-048) gesetzten Nutzersaetze in
`PrivilegedClient.kt:779-787` (`Bounded.Unanswered.reason`) — behalten oder
Ersatztext woertlich, wie in `docs/tasks/T-063.md` T-063b beauftragt; dazu
DR-007-Nachpruefung.

**Gesamturteil: die zwei Saetze selbst sind ehrlich und werden behalten. Eine
bestehende Verwendungsstelle verfaelscht einen davon bei der Zusammensetzung
(neuer Befund DR-008, Wichtig) — das ist kein Fehler am AD-048-Wortlaut
selbst, sondern am Rahmensatz, in den er eingebettet wird.** DR-007 ist am
eingefrorenen Stand behoben (siehe oben, in der T-059b-Sektion vermerkt).

### Die zwei Saetze — Urteil je Satz

1. **Busy — „the privileged helper has not answered an earlier request yet —
   nothing was sent"** (`PrivilegedClient.kt:781`) — **behalten**. Gleiche
   Wortfamilie wie die uebrigen `Unavailable`-Gruende in derselben Datei
   ("the privileged helper is not running", "the helper returned nothing"),
   nennt Ursache und Konsequenz in einem Satz. Ueberall dort, wo ich die
   Verwendungsstellen gelesen habe, wird er unverfaelscht angehaengt:
   `SettingsRestore.kt:130/159` zeigt ihn als alleinstehende Berichtzeile,
   `ComparisonSection.kt:139` haengt ihn in „Could not pin to {S} kbps:
   {reason}. Nothing was measured." an. Die zwei "nothing"/"Nothing" in
   letzterem Satz sind keine Wiederholung derselben Aussage, sondern zwei
   verschiedene Nichts-Ereignisse (der Helfer hat nichts geschickt / der
   Vergleich hat nichts gemessen) — liest sich beim ersten Lesen etwas
   dicht, aber nicht falsch; kein eigener Befund.
2. **TimedOut+changes — „the privileged helper did not answer within 15 s —
   whether the change took effect is unknown"** (`PrivilegedClient.kt:783-786`)
   — **behalten als Fragment**. Als alleinstehende Berichtzeile
   (`SettingsRestore.kt:130/159`, Rueckweg-Bericht) ist er klar und ehrlich:
   er benennt Ursache und die tatsaechliche Unsicherheit, ohne ein Urteil
   vorwegzunehmen. Fuer Lesungen (`changes = false`, z. B.
   `DeviceProfilesScreen.kt:1081` „Cannot be read: …") entfaellt der
   Halbsatz ohnehin (`PrivilegedClient.kt:786`) — dort besteht das Problem
   unten gar nicht. Eine Verwendungsstelle verfaelscht ihn aber, siehe DR-008.

### Wichtig

- **DR-008 ✔ (behoben, bestaetigt T-065b 2026-09-29, `abe8953`) [`LdacTuning.kt:395-400`, `tuningSentence()`]** Der zweite
  AD-048-Satz wird hier in einen Rahmensatz eingebettet, der dem Ergebnis
  bereits ein definitives Urteil vorwegnimmt: „LDAC quality was not changed
  — ${outcome.reason}." (nicht gespeichert) bzw. „It was not changed on the
  link right now — ${outcome.reason}." (gespeichert). Trifft der Grund
  TimedOut mit `changes = true` (Pin-Anfrage, Helfer haengt 15 s), ergibt das
  am Bildschirm woertlich: **„LDAC quality was not changed — the privileged
  helper did not answer within 15 s — whether the change took effect is
  unknown."** Der Rahmen behauptet "was not changed" als Tatsache, der
  eingebettete AD-048-Satz sagt im selben Atemzug das Gegenteil — "unknown"
  statt "not changed". Genau das vermeidet die App an anderer Stelle bewusst
  (R-A, "keine Behauptung ueber unbeobachteten Zustand", `GOAL.md` AK-8): der
  Nutzer liest hier einen Widerspruch statt einer Unsicherheit, und genau der
  Zustand, den AD-048 fuer AK-11 als "pending, mit einem zweiten Tipp
  nachholbar" definiert (`ARCHITECTURE.md:3188-3196`), wird durch den Rahmen
  als abgeschlossenes "nicht geaendert" hingestellt. Betrifft nur den einen
  Pfad TimedOut+changes=true — die zwei bislang getesteten Gruende
  ("the privileged helper is not running", "there is no live link to
  change", `LdacTuningTest.kt:239-259`, Klasse `LdacTuningSentenceTest`) sind
  tatsaechlich definitive Nicht-Aenderungen, dort passt der Rahmen. Kein Test
  deckt die neue Kombination ab. **Richtung:** in `tuningSentence()` den
  Rahmensatz fuer den TimedOut-Fall nicht als "was/is not changed"
  formulieren, sondern die Unsicherheit offen lassen — am naechstliegenden
  Vorbild orientiert, das `SettingsRestore.kt:130/159` bereits so haelt (dort
  steht `outcome.reason` ohne einen "not changed"-Rahmen). Wortlautentscheidung
  beim developer/architect; der AD-048-Satz selbst bleibt unveraendert.

### Positiv / beibehalten

- Beide AD-048-Saetze halten die etablierte Wortfamilie und den ehrlichen
  Rahmen des privilegierten Kanals durch (kein "failed", kein "error", eine
  Ursache und, wo zutreffend, eine Unsicherheitsfolge statt eines Urteils) —
  Massstab fuer kuenftige `PrivilegedCallGuard`-Saetze.
- `changes`-Parameter korrekt an der Quelle entschieden (`read()` = false,
  `request()`/`apply()` = true, `PrivilegedClient.kt:591/602/691/706`) — der
  Unsicherheitshalbsatz erscheint nur dort, wo er sachlich zutrifft
  (Schreibungen), nicht bei Lesungen.

### Offene Fragen an den App Designer

Keine.

---

## Review vom 2026-09-29 (T-059b)

**Methode:** Code-Analyse (kein Geraet, kein Emulator, eingefrorener Stand
`d6d1890` laut Auftrag) — `ComparisonSection.kt`, `ConditionStatus.kt`,
`MeasureStatusSection.kt`, `EnvironmentConditions.kt`, `MeasurementService.kt`,
`MonitorScreen.kt`, `LiveLinkPanel.kt`, `BitrateSection.kt`,
`DeviceProfilesScreen.kt`/`-ViewModel.kt`, `Panel.kt`, `Explainer.kt`,
`Measures.kt` gelesen. Existierende Tests gelesen, nicht ausgefuehrt
(`SettingsJumpsTest.kt`, `ConditionStatusTest.kt`, `ComparisonSectionTest.kt`,
`MeasuresTest.kt`, `EnvironmentConditionsTest.kt`) — Testinhalte (welche
Strings/Zahlen sie binden) gegen den Code abgeglichen; Testergebnisse selbst
sind **unverifiziert (Code-Analyse)**. `ARCHITECTURE.md` (AD-044..046)
gegengelesen. Keine Screenshots moeglich (kein Geraet/Emulator in diesem
Lauf, kein Robolectric-Screenshot-Pfad im Projekt).

**Geprueft gegen:** `UI_SPEC.md` Abschnitt "Saeule 3 — Pruefstand, Umgebung,
Sprung, Band (T-057b)" (P-001/P-002/P-003/P-004, AK-T057-1..10) und die in
`docs/tasks/T-059.md` T-059b genannten, selbst gesetzten Wortlaute aus
T-055b/c/d und T-058a/b/c.

**Gesamturteil: ship-ready, mit einer Layout-Praezisierung.** Alle geprueften
Wortlaute sind ehrlich, in der etablierten Wortfamilie der App und frei von
den verbotenen Woertern; die Wiederverwendungsdisziplin (eine
`SettingsJump`-Komponente, eine `readBackSentence`-Funktion, ein
`timeFormat`, ein `Condition.displayName` fuer alle Renderorte) ist stark.
Vier Stellen in `UI_SPEC.md` waren durch AD-046 und die tatsaechliche
Implementierung ueberholt und sind in diesem Lauf korrigiert (siehe unten,
kein Verhaltensfindling). Ein neues Layout-Finding (DR-007) am neuen
P-001-Panel.

### Kritisch

Keine.

### Wichtig

- **DR-007 ✔ (behoben, bestaetigt T-063b 2026-09-29, `26f7442`, Ancestor von
  `483d78b`) [`MeasureStatusSection.kt:29-48`, `MeasureStatusPanel`, ⚠
  unverifiziert (Code-Analyse, kein Screenshot)]** Das neue "Measures"-Panel
  (P-001, AK-18) rendert alle sechs Massnahmen als direkte Kinder der einen
  `Panel`-`Column`, deren `verticalArrangement = Arrangement.spacedBy(12.dp)`
  **jeden** Zeilenabstand gleich behandelt (`Panel.kt:87-91`) — den Abstand
  zwischen `measure.displayName` und der zugehoerigen `readBackSentence`-
  Zeile genauso wie den Abstand zwischen dem Ende einer Massnahme und dem
  Namen der naechsten. Bei sechs Massnahmen mit je zwei bis drei Zeilen
  (Name, Ruecklesesatz, optional ein `SettingsJump`-Knopf) ergeben sich bis
  zu 16 Zeilen mit identischem 12dp-Abstand, ohne dass eine engere
  Innerhalb-Massnahme-Distanz gegenueber der Zwischen-Massnahmen-Distanz das
  Auge fuehrt. Das widerspricht dem eigenen Zweck des Panels ("Auf einen
  Blick sehen, ob die Bedingungen ... gegeben sind", `UI_SPEC.md` P-001) —
  ein Panel, das man auf einen Blick lesen soll, aber dessen Gruppierung nur
  durch mitzaehlende Aufmerksamkeit erkennbar ist, verfehlt genau das.
  Andere Mehrfach-Eintrag-Stellen der App umgehen das, weil sie pro Eintrag
  nur eine Zeile brauchen (`Choose()`s Radiozeilen, `ComparisonSection.kt:98-
  110`) — P-001 ist die erste Stelle mit mehrzeiligen Bloecken pro Eintrag,
  ohne dass ein bestehendes Gruppierungsmuster dafuer wiederverwendet wurde.
  **Richtung:** je Massnahme eine eigene `Column` (z. B. `spacedBy(2.dp)`
  fuer Name/Satz/Knopf) als ein Kind der Panel-`Column`, damit die
  aeussere 12dp-Distanz weiterhin die Massnahmen trennt und eine engere
  innere Distanz sie zusammenhaelt. `UI_SPEC.md` legt fuer P-001 keine
  konkrete Verschachtelung fest, das ist also ein Layout-Nachtrag, kein
  Wortlaut- oder Verhaltensfehler. **Nachtrag T-063b (2026-09-29):**
  `MeasureStatusSection.kt:41` verschachtelt am eingefrorenen Stand `483d78b`
  jede Massnahme jetzt in eine eigene `Column(spacedBy(2.dp))` als Kind der
  Panel-`Column`, exakt wie empfohlen — kein Nacharbeitsbedarf.

### Nice-to-have

- **[`EventLog.kt:184-188`, `EventRow`, ⚠ unverifiziert (Code-Analyse)]**
  Der P-004-Block (Kopfzeile "Conditions when this episode was first read:"
  plus bis zu sechs Bedingungszeilen) wird als **ein** `Text()` mit
  eingebetteten Zeilenumbruechen gerendert, in derselben `bodySmall`/
  `onSurfaceVariant`-Formatierung wie `event.detail` selbst — die Kopfzeile
  hat kein optisches Gewicht (kein `titleSmall`, kein Fettdruck) gegenueber
  den Datenzeilen darunter. Bei einer bereits aufgeklappten, dichten
  Protokollzeile (Basissatz + Kopfzeile + bis zu sechs Bedingungen +
  `payloadLine()`) fehlt ein Ankerpunkt fuer das Auge. Geringe Prioritaet,
  da `UI_SPEC.md` P-004 genau diese Formatierung ("als weitere Text-Zeilen
  in bodySmall/onSurfaceVariant") vorgibt und ich das beim Schreiben von
  T-057b nicht anders spezifiziert habe — eher eine gestalterische
  Nachbesserung als ein Fehler gegen die eigene Vorgabe.

### Spec-Korrekturen (kein Verhaltensfindling, `UI_SPEC.md` berichtigt)

Vier Stellen im Abschnitt "Saeule 3 — Pruefstand, Umgebung, Sprung, Band
(T-057b)" waren durch AD-046 (T-058c) und die tatsaechliche Implementierung
ueberholt; alle vier direkt in `UI_SPEC.md` korrigiert, mit datierter
Korrekturnotiz, kein Verhaltensunterschied zum Code:

1. **P-002 zweiter Renderort:** Spec nannte `Choose()`
   (`ComparisonSection.kt:91-122`); gebaut ist `Instruct()`
   (`ComparisonSection.kt:178`). `Choose()` hat pro Massnahme nur eine
   Radiozeile ohne Platz fuer einen Zweitknopf — `Instruct()` ist der
   Ort, an dem die Massnahme bereits feststeht und der Nutzer handeln soll.
   Wortlaut/Verhalten unveraendert, nur der Ort berichtigt (AK-T057-7 mit).
2. **AK-T057-4/P-004-Reihenfolge:** "genau fuenf Zeilen" und die feste
   Reihenfolge-Liste ohne `WIFI_BAND` waren seit AD-046 (sechster
   `Condition`-Eintrag) veraltet; auf `Condition.entries.size` und die
   vollstaendige Sechserliste umgestellt — Test
   (`ConditionStatusTest.kt:70,84`) prueft bereits dynamisch.
3. **`UI_SPEC.md`-Zeile 3311** ("does not say which band it used") ist durch
   AD-046 sachlich falsch geworden — die App liest das Band jetzt und A2
   widerspricht bei 2,4 GHz. Zeile als historischer Beleg stehen gelassen,
   mit Korrekturhinweis zur aktuell gueltigen Aufteilung.
4. **P-003/AK-T057-10 vierter Zweig** ("Berechtigung fehlt ... needs
   location access") gestrichen — AD-046 waehlte den Leseweg
   (`ConnectivityManager`, `ACCESS_NETWORK_STATE`) bewusst so, dass dieser
   Fall nie eintritt (`EnvironmentConditionsTest.kt:189-191`); ein Satz fuer
   einen unerreichbaren Fall waere selbst eine kleine Unehrlichkeit. P-003
   gilt jetzt unbedingt (AD-046: "P-003 baubar: ja"), die "(AK-21, bedingt)"-
   Klammer ist entfernt.

### Positiv / beibehalten

- **DR-006 behoben:** Der Rueckweg-Bericht-Dialog heisst jetzt "Settings put
  back" statt "Back to before" (`DeviceProfilesScreen.kt:1497`) — Ersatztext
  aus der T-051a-Empfehlung woertlich uebernommen.
- Starke Wiederverwendungsdisziplin bei allen neuen T-057b/T-058-Texten: ein
  `SettingsJump`-Composable fuer beide Renderorte, eine `readBackSentence`-
  Funktion fuer P-001 und Instruct-Ruecklese, ein geteiltes `timeFormat`
  (`EventLog.kt:32`, in `MeasureStatusSection.kt` importiert statt
  dupliziert), ein `Condition.displayName` fuer P-001 und P-004. Kein
  sechstes Wording fuer dieselbe Sache gefunden.
- Die neuen Fehl-/Ausweichsaetze ("Could not open {page} on this phone —
  nothing changed.", "Android did not let this comparison run in the
  background — keep this screen open until this arm ends.") liegen
  zeichennah in derselben Formel wie ihre jeweiligen Vorbilder
  (`PinAndCheck`, Deep-Capture-Ausweichsatz) — konsistente Wortfamilie statt
  einer zweiten Formulierung fuer denselben Fehlerfall.
- Die Benachrichtigungstexte (`MeasurementService.kt`) sind sichtbar, nennen
  Dauer und Stop-Weg, ohne Geraeteinformationen (V19) — entspricht der
  R-013-Anforderung an eine neue, zeitbegrenzte FGS-Ankuendigung (im
  Unterschied zur bewusst stillen `EqForegroundService`).
- "MEASURES" als Panel-Ueberschrift ist keine neue Grossschreibungs-
  Konvention, sondern `PanelHeader`s bestehendes `.uppercase()` auf
  `EyebrowStyle` (`Panel.kt:110-111`) — dieselbe Mechanik wie jeder andere
  Panel-Titel der App.
- Kein Icon, keine Verification-Farbe, kein Zaehler im neuen Measures-Panel
  — AK-T057-3 sichtbar eingehalten.

### Offene Fragen an den App Designer

Keine neuen. Die im Auftrag genannten, bereits im Lauf entschiedenen Punkte
(Sprungknoepfe an beiden Renderorten, P-001-Panel nicht einklappbar) sind nur
bewertet, nicht neu aufgeworfen — beide Umsetzungen entsprechen der
`UI_SPEC.md`-Empfehlung und werden hier nicht erneut infrage gestellt
(Stabilitaetsregel).

---

## Review vom 2026-09-28 (T-051a)

**Methode:** Code-Analyse (kein Geraet, kein Emulator laut Auftrag) —
`ComparisonSection.kt`, `ComparisonController.kt`, `SettingsRestore.kt`,
`LdacTuning.kt`, `EnvironmentConditions.kt`, `DeviceProfilesScreen.kt`
(`SettingsRestoreBanner`/`SettingsRestoreBannerContent`),
`DeviceProfilesViewModel.kt`, `MonitorScreen.kt` gelesen; Wortlaut gegen
`docs/tasks/T-047.md` (woertliche AD-038/UI_SPEC-Vorgabe) und `UI_SPEC.md`
T-047-Abschnitt abgeglichen (identisch). Bestehende Compose-/Unit-Tests
(`SettingsRestoreBannerTest.kt`, `SettingsRestoreTest.kt`) gelesen, nicht
ausgefuehrt (`build/` parallel bei `qa-engineer`, T-051b) — Testergebnisse
selbst sind **unverifiziert (Code-Analyse)**, ihr Inhalt (welche
Zeichenketten sie pruefen) ist gelesen und abgeglichen. Keine Screenshots
moeglich (kein Geraet in diesem Lauf, kein Robolectric-Screenshot-Pfad im
Projekt).
**Geprueft gegen:** AK-12 (Ausweichen-Kategorie), AK-14 (Messrahmen am
Ergebnis, keine Fussnote), AK-16 (Grenze des Rats), `UI_SPEC.md` T-047
(S3-5/S3-6, AK-T047-1..17) sowie die fuenf im Auftrag genannten,
selbst gesetzten Wortlaute aus S3-5/S3-6 (`docs/berichte/T-047i-developer.md`,
T-047h-Antwort — nicht als Datei erhalten, daher direkt am Code verifiziert).
**Gesamturteil: ship-ready, mit einer Praezisierung.** Alle spezifizierten
Saetze (Katalog, Workarounds, Ruecklese-, Urteils- und Messrahmensaetze,
AK-16-Grenzsatz) sind zeichengenau umgesetzt und liegen — bestaetigt durch
`AcceptanceCriteriaGrepTest.kt` — im selben Composable ohne zweite Ebene. Von
den fuenf zu pruefenden, selbst gesetzten Wortlauten sind vier unauffaellig
und uebernehmen die etablierte Wortfamilie der App; einer (der Bericht-Dialog
nach dem Rueckweg) verwendet denselben Text wie der Ausloeser-Knopf und sollte
umbenannt werden.

### Die fuenf gepruften, selbst gesetzten Wortlaute — Urteil je Wortlaut

1. **"Start Arm B"** (`ComparisonSection.kt:171`, Knopf in Phase `Instruct`
   nach `VERIFIED`/`NOT_VERIFIABLE`) — **behalten**. "Arm A"/"Arm B" sind in
   `UI_SPEC.md` selbst Nutzertext (u. a. "Arm A ran for {tA}, Arm B for
   {tB}.", "Arm B has not started..."), kein internes Kuerzel, das hier
   erstmals nach aussen dringt. Parallelform zu "Check again" (Text-Button,
   niedrigere Betonung) — sinnvolle Hierarchie: der bestaetigende Pfad ist
   der `GoldButton`.
2. **"device discovery"** (`ComparisonSection.kt:381`,
   `Condition.DISCOVERY_SEEN.displayName`, erscheint im Zustandsbuch/
   `notChecked`) — **behalten**. Deckt sich woertlich mit dem bereits
   spezifizierten Massnahmennamen "No device discovery or scanning"
   (`Measure.NO_DISCOVERY`), keine zweite Bezeichnung fuer dieselbe Sache.
3. **Pin-Satz — Fehlgruende bei `LdacTuning.pin`** (`LdacTuning.kt:354-357`:
   `BUSY` = "another LDAC quality request is still running", `NOT_RECORDED` =
   "the value before could not be recorded, so it could not be put back
   later") — **behalten**. Beide Saetze laufen durch die spezifizierte
   Vorlage `"Could not pin to {S} kbps: {reason}. Nothing was measured."`
   (`ComparisonSection.kt:132`) bzw. die bestehende Tuning-Meldung; `NOT_
   RECORDED` benennt korrekt den AD-033-Grund (kein Schreiben ohne haltbaren
   Vorwert) in derselben Wortfamilie ("put back") wie der Rueckweg-Bericht.
4. **Banner bei unlesbarem Ledger** (`DeviceProfilesScreen.kt:1429`:
   "The list of changed settings could not be read, so they cannot be put
   back from here.") — **behalten, mit einer Wortkorrektur.** Der Zustand
   selbst ist richtig gestaltet: `RestoreBanner.Unreadable` ist bewusst von
   `Hidden` unterschieden (KDoc "not the same as 'nothing held'"), es wird
   ehrlich nichts vorgetaeuscht und kein Knopf angeboten, der nichts leisten
   koennte — das entspricht der AK-15/AK-3-Haltung der App (keine Ableitung
   ohne lesbare Grundlage). Einzig "**...from here**" liest sich, als gebe es
   einen Weg anderswo. **Ersatztext (woertlich):** `"The list of changed
   settings could not be read, so nothing can be put back until it can be
   read again."`
5. **Dialog "Back to before"** (Titel des Bericht-Dialogs nach
   `restoreAll()`, `DeviceProfilesScreen.kt:1457`) — **Ersatztext, siehe
   DR-006.**

### Kritisch

Keine.

### Wichtig

- **DR-006 ✔ (behoben, bestaetigt T-059b 2026-09-29)
  [`DeviceProfilesScreen.kt:1457`, `SettingsRestoreBannerContent`]**
  Der Bericht-Dialog, der nach dem Rueckweg die Ergebniszeilen zeigt, tragt
  denselben Titel "Back to before" wie der Knopf, der ihn ausgeloest hat
  (Banner-Knopf `:1425`, Bestaetigungs-`confirmButton` `:1446`) — derselbe
  Wortlaut erscheint in dieser Reihenfolge dreimal: Knopf (Handlungsaufruf) →
  Bestaetigungsdialog-Knopf (Handlungsaufruf) → Bericht-Dialog-Titel
  (Rueckschau auf eine bereits gelaufene Handlung). Ein Nutzer, der eben auf
  "Back to before" getippt hat, sieht sofort danach wieder "Back to before"
  als Ueberschrift — das liest sich wie ein erneuter Aufruf oder ein
  Hängenbleiben, nicht wie eine Bestaetigung, dass etwas geschah. `UI_SPEC.md`
  legt fuer diesen Dialog keinen Titel fest (nur die Zeilenfamilie), das ist
  also ein eigener, unspezifizierter Zusatz des developers. **Ersatztext
  (woertlich, uebernimmt die im Bericht selbst etablierte Vokabel "put
  back"):** `"Settings put back"`. Kein Test bindet den aktuellen Titelstring
  (`SettingsRestoreBannerTest.kt` prueft Kopf/Knopf/Bericht-Inhalt, nicht den
  Dialogtitel) — Aenderung ist folgenlos fuer die Testsuite.

### Nice-to-have

- Siehe Punkt 4 oben (Ersatztext fuer den Unreadable-Banner) — Wortkorrektur,
  kein Verhaltensfehler.
- **DR-005** (siehe unten) bleibt unveraendert bestehen.

### Positiv / beibehalten

- Messrahmen, AK-16-Block, Urteils-/`NotComparable`-Satz und Prozesstod-Satz
  liegen alle im selben `Result`-Composable, keine zweite Ebene — AK-14
  wortgleich erfuellt.
- Katalog (6 waehlbar) und "Workarounds" (4, nicht waehlbar, kein
  Start-Knopf) sind strukturell getrennt; das Wort "fix"/"fixes"/"fixed" ist
  im Katalog nicht vorhanden und in "Workarounds" nur negiert — durch
  `AcceptanceCriteriaGrepTest.kt` bereits grep-gebunden (inkl. der
  Ausnahmeregel fuer "a fixed step" im F1-Pflichtsatz).
- Der Rueckweg-Bericht ist scrollbar (`Modifier.verticalScroll`) — bleibt
  lesbar, auch wenn viele Eintraege (mehrere Geraete, alle vier Kategorien)
  gleichzeitig zurueckgemeldet werden.
- Ruecklese-, Urteils- und Messrahmensaetze sind zeichengenau wie in
  `UI_SPEC.md`/`docs/tasks/T-047.md` — keine Abweichung gefunden.

### Offene Fragen an den App Designer

- Siehe `UI_SPEC.md`, Abschnitt T-047 "Offene Fragen an den App Designer",
  Punkt 3 (neu, 28.09.2026): ob `restoreButton` in
  `ArmProgress`/`Instruct`/`Result` der volle D2-Banner (Kopfzeile + Knopf)
  bleiben soll oder auf einen blossen Knopf verkuerzt wird. Empfehlung dort:
  so lassen.

---

## Review vom 2026-09-23 (T-043c)

**Methode:** Code-Analyse (kein Geraet, Auftragsgrenze) — `ObservationRun.kt`,
`ObservationRunSection.kt`, `ObservationRunController.kt`, `SetupStore.kt`,
`AppIcons.kt`, `LiveLinkGraph.kt`, `LinkLiveModels.kt` gelesen, dazu
`ObservationRunTest.kt`, `ObservationRunSectionTest.kt`,
`AcceptanceCriteriaGrepTest.kt` gelesen (nicht ausgefuehrt — `build/` durfte
laut Auftrag nicht angefasst werden, der `qa-engineer` lief parallel; die
Testergebnisse selbst sind daher **unverifiziert (Code-Analyse)**, ihr Inhalt
— welche Zeichenketten/Zustaende sie behaupten zu pruefen — ist gelesen und
gegen `UI_SPEC.md` T-039 abgeglichen). Fuer die Icon-Frage zusaetzlich drei
WebFetch-Abgleiche gegen `google/material-design-icons` (github, Originalpfade
fuer `bluetooth`, `equalizer`, `help_outline`).
**Geprueft gegen:** `UI_SPEC.md` T-039 (Beobachtungslauf), wortgleich aus
`docs/tasks/T-039.md` uebernommen — AK-T039-1..17, Zustandstabelle, Wortlaut,
Unterbrechungstabelle, Parameter.
**Gesamturteil: ship-ready.** Die Umsetzung trifft den spezifizierten Wortlaut
zeichengenau (Grundzeilen, Kennzahlsaetze, Starthinweis-Dialog, Zustandstabelle
inkl. Chip-Spalte), haelt AK-T002-13 korrekt ein und loest die vom `developer`
gemeldete scheinbare Widerspruechlichkeit der Spec korrekt auf. Ein
Wortlaut-Widerspruch in der Spec selbst ist behoben (unten), ein
Grenzfall-Verhalten ist als offene Produktfrage markiert statt entschieden.

### Antwort auf die Frage des developer (T-039b)

**Bestaetigt, mit Spec-Korrektur.** Die Umsetzung (isPlaying==false → Luecke;
isPlaying==true ohne lesbare Rate → Lauf endet ueber "Rate nicht mehr lesbar")
ist die einzig konsistente Aufloesung: `LdacState.from()`
(`LinkLiveModels.kt:421-444`) baut `measuredKbps` und `liveBitrateHonesty` aus
derselben Ableseoperation — bei laufender Wiedergabe ist `measuredKbps == null`
niemals gleichzeitig mit `liveBitrateHonesty == MEASURED` moeglich. Die alte
Spec-Zeile "Wiedergabe pausiert ... oder `measuredKbps == null`" konnte den
Fall "spielt, aber Rate weg" also nie eigenstaendig treffen; er gehoerte immer
schon in die Zeile "Rate nicht mehr lesbar". Ich habe `UI_SPEC.md` (T-039,
Unterbrechungstabelle, Zeile 1+3) entsprechend korrigiert und die Begruendung
dort dokumentiert, kein Verhalten geaendert.

**Zusaetzlich gefunden, nicht Teil der Entwicklerfrage:** `RUN_GAP_MAX_MS`
misst — wie der `developer` notierte — den Abstand zwischen zwei **Abfragen**,
nicht zwischen zwei Ratenlesungen. Das bedeutet: eine beliebig lange Pause
(`isPlaying == false`) bei fortlaufenden Abfragen beendet den Lauf **nie**,
nur ein tatsaechlicher Ausfall der Abfragen selbst (Hintergrund, dumpsys-Fehler)
tut es. Belegt durch `ObservationRunTest."a ten minute pause is a gap, not
observed time"` — ein zehnminuetiger Ausfall bleibt `assertNull(run.end)`.
Das ist kein Fehler gegen die alte Tabelle (die "Wiedergabe pausiert"-Zeile
sagte nie, dass `RUN_GAP_MAX_MS` hier greift — nur die "Hintergrund"-Zeile tut
das ausdruecklich), aber es war nicht klar genug ausgesprochen, um es beim
Lesen der Tabelle zu erkennen. Als eigene, bewusste Produktfrage in
`UI_SPEC.md` (T-039, "Offene Fragen", Punkt 5) nachgetragen — meine Empfehlung
ist "so lassen", die Entscheidung selbst faellt der App Designer.

**Korrektur (T-047c, 2026-09-23):** Der App Designer hat die oben verlinkte
Frage 5 am 23.09. mit **nein** beantwortet — die Empfehlung dieses Durchlaufs
wurde nicht uebernommen. Seit T-046 beendet eine Pause den Lauf, sobald sie
`RUN_GAP_MAX_MS` (2 min) ueberschreitet (`RunEnd.PAUSED`); "beendet den Lauf
nie" gilt nicht mehr. Der zitierte Test ist umbenannt:
`ObservationRunTest."a pause of exactly the gap maximum is a gap, not
observed time"` misst jetzt die Grenze (2 min), nicht zehn Minuten. Diese
Zeilen bleiben als historischer Befund dieses Durchlaufs stehen; der
aktuelle Wortlaut steht in `UI_SPEC.md` T-039 (dort ebenfalls mit
Korrektur-Vermerk T-047c).

### Kritisch

Keine.

### Wichtig

Keine — dieser Durchlauf fand keine Abweichung von AK-T039-1..17, die die
Nutzung beeintraechtigt. Ein verwandter Grenzfall (RUN_ENDED zeigt "Start" auch
wenn die Rate gerade nicht lesbar ist, Neustart endet dann sofort wieder) ist
bereits im Dispatch an den `qa-engineer` (T-043a, Punkt 4) zur Beurteilung
gegeben; ich dupliziere ihn hier nicht, teile aber die Beobachtung: das ist
eine Zustands-Prioritaetsfrage (RUN_ENDED vs. RUN_UNAVAILABLE bei
ueberlappenden Bedingungen), die die T-039-Tabelle nicht ausdruecklich
regelt, weil sie von einer Partition ausgeht, die die fuenf Zustaende in der
Praxis nicht ganz bilden.

### Nice-to-have

- **DR-005 [`UI_SPEC.md` T-039, jetzt korrigiert]** Siehe oben — Wortlaut
  bereinigt, keine Code-Aenderung noetig gewesen.

### Zustaende, Wortlaut, Icons — im Detail gepruft

- **Fuenf Zustaende (Tabelle):** Bedingungen und Chip-Spalte 1:1 in
  `ObservationRunSection.kt:78-86` (Headline) und `:65-71` (Chip-`when`)
  wiedergefunden. `RUN_UNAVAILABLE` zeigt korrekt **keinen** Chip (weder
  `run != null && end == null` noch `run != null || hasReadableRate` trifft
  zu), `RUN_ENDED` korrekt wieder "Start".
- **Grundzeilen (sechs Enden):** `endLine()` (`ObservationRunSection.kt:163-174`)
  zeichengenau wie in `UI_SPEC.md` Wortlaut-Abschnitt, inkl.
  `formatSpan(RUN_GAP_MAX_MS)` in der Gap-Zeile.
  `ObservationRunTest."each of the six ends is recognised and freezes the
  figures"` deckt alle sechs mit eingefrorenen Kennzahlen danach ab.
  **Korrektur (T-047c, 2026-09-23):** Seit T-046 sind es **sieben** Enden
  (`PAUSED` kam dazu) und der Test heisst jetzt `ObservationRunTest."each end
  is recognised and freezes the figures"`. Diese Zeile ist der Stand dieses
  Durchlaufs (23.09., vor T-046) und bleibt stehen; nicht erneut gepruft.
- **Starthinweis-Dialog:** Titel, Text, Button-Beschriftungen ("Continue"/"Not
  now") zeichengenau wie in `UI_SPEC.md` Entscheidung 5.
  `LEAVING_DISCARDS_THE_RUN`-Konstante wird fuer Dialog **und** Satz 1 der
  zweiten Ebene verwendet (ein String, zwei Stellen) — genau die Vorgabe
  "nicht zweimal formuliert". `SetupStore.isObservationRunNoticeAccepted()`
  kopiert `isLocalConnectionAccepted()` 1:1 (DataStore-Boolean, nur bei
  "Continue" gesetzt) — die geforderte kleinste Loesung.
- **AK-T002-13 ("kein Text laenger als zwei Saetze"):** Alle Erstebenen-Texte
  halten die Grenze (Grundzeilen und Kennzahlsaetze je ein Satz; die
  `RUN_COLLECTING`-Zeile ist zwei Saetze — noch innerhalb der Grenze). Die
  **zweite Ebene** (hinter dem Fragezeichen) hat vier Saetze — das ist **keine
  Abweichung**: `UI_SPEC.md` T-039 legt genau das im Wortlaut-Abschnitt
  ausdruecklich und begruendet fest ("vier Saetze, mehr nicht"), als bewusst
  dokumentierte Praezisierung von AK-T002-13 ("zielt auf unaufgeforderten
  Dauertext"). Ruhezustand bleibt bei einer Zeile (`ObservationRunSectionTest.
  "the resting section is one line and a chip"`), also kein Wachstum des
  unaufgeforderten Textes.
- **Icons (`AppIcons.kt`):** Drei von acht Pfaden stichprobenartig gegen die
  Originalquelle `google/material-design-icons` (GitHub, `materialicons`/
  `materialiconsoutlined`, 24px) abgeglichen: `Bluetooth`, `Equalizer`,
  `HelpOutline` — alle drei geometrisch identisch (gleiche Zahlenwerte;
  lediglich `V`/`H`-Line-Befehle sind zu `L`-Befehlen mit demselben Endpunkt
  aufgeloest, was am Rendering nichts aendert). Die restlichen fuenf
  (`ExpandLess`, `ExpandMore`, `GraphicEq`, `Headphones`, `Hearing`,
  `Insights`, `Settings`) wurden **nicht** einzeln gegengeprueft — bei
  konsistenter Kommentierung ("Path data copied unchanged from
  material-icons-extended 1.7.6") und durchgehendem Muster in den drei
  geprueften Faellen halte ich das Risiko einer verdeckten Abweichung fuer
  gering, es ist aber nicht belegt. **Optisch gleich, soweit gepruft.**

### Backlog (geparkt)

- RUN_ENDED zeigt "Start" unabhaengig von `hasReadableRate` (siehe oben,
  T-043a Punkt 4 zustaendig).
- Fuenf der acht `AppIcons`-Pfade sind ungeprueft (siehe oben).

### Positiv / beibehalten

- Die Wortlaut-Treue der gesamten Beobachtungslauf-Sektion ist aussergewoehnlich
  hoch: jede der sechs Grundzeilen, der Starthinweis-Dialog, die
  Kennzahlsaetze und die Chip-Beschriftungen stimmen zeichengenau mit
  `UI_SPEC.md` ueberein. Beibehalten als Massstab fuer kuenftige Abschnitte.
- Wiederverwendung statt Neuerfindung durchgehend: `ExplainedBlock`,
  `FilterChip`, `LdacQuality.chipLabel`, das `SetupStore`-Flag-Muster von
  `isLocalConnectionAccepted` — kein neues Token, keine neue Komponente, keine
  neue Farbe, wie von der Spec verlangt.
- Die grep-verankerten Akzeptanzkriterien (`AcceptanceCriteriaGrepTest.kt`)
  sind ein Verfahren, das ein Review wie dieses strukturell ueberfluessig
  macht, sobald es alle Kriterien abdeckt — aktuell deckt es AK-T039-5, -6,
  -10, -11 ab. Empfehlung fuer kuenftige Auftraege: neue AK mit Grep-Potenzial
  gleich dort ergaenzen, statt erneut manuell nachzuschlagen.

### Offene Fragen an den App Designer

- **UI_SPEC.md T-039, Punkt 5 (neu):** Soll eine Pause beliebig lang sein
  duerfen, ohne den Beobachtungslauf zu beenden (aktuelles, getestetes
  Verhalten), oder soll eine sehr lange Pause den Lauf ebenfalls beenden?
  Empfehlung: so lassen. Voller Text mit Begruendung in `UI_SPEC.md`.

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
