# T-038 — Bericht des `developer` (abgelegt vom Director, L-005)

STATUS: **erledigt** · Commits `374be69` und `5218455` auf `master` · 2026-09-03

Drei Befunde aus dem T-034-Retest. Kein Geraet, kein Produktivverhalten geaendert.

## QA-014 — `frames per packet (max/ave)` vertauschbar

Neuer Test `max and ave frames per packet are not interchangeable` in
`LiveLinkParserTest.kt`, gegen die dort bereits geladene Fixture
`bt_manager_pixel11_ldac_state_abr.txt`. Keine neue Datei, keine neue Fixture.

**Selbst nachgelesen:** `Frames per packet (total/max/ave) : 18492 / 10 / 8`
(Z. 1501) — max ungleich ave, deshalb koennen die beiden Assertions einander
widersprechen. Deckt sich mit der Auftragsangabe.

**Rot-vorher:** `framesPerPacketMax = at(FRAMES, 1)` → `at(FRAMES, 2)`.
Lauf: 35 Tests, **1 rot** — genau der neue, sonst nichts:
`Frames per packet (total/max/ave), field 2 (max) expected:<10> but was:<8>`.
Nach Rueckbau wieder 35/35 gruen; `git diff` am Parser zeigt danach
ausschliesslich die QA-016-Kommentaraenderung.

## QA-015 — redaktionstoleranter Zweig von `sameAddress`

Neuer Test `an active device is recognised across different redaction levels of
the same address`, gegen einen **von Hand gebauten Dreizeiler** statt einer
Fixture: `active_a2dp_devices` traegt die voll redigierte Adresse, der
`A2dpStateMachine`-Kopf die unredigierte; sie stimmen nur im letzten Oktett
ueberein — genau der Fall, fuer den der Zweig laut KDoc existiert.

**Fallstrick, den der `developer` vermieden und gemeldet hat:** Ein
`"""..."""`.`trimIndent()`-Block haette die Kopfzeile auf Spalte 0 gezogen. Der
Parser wertet jede nicht eingerueckte Zeile als Blockende — der Header waere
sofort verworfen worden und **der Test waere gruen geworden, ohne den geprueften
Zweig je zu erreichen.** Deshalb explizite Konkatenation mit fest gesetzten
Leerzeichen. Das ist genau der Vakuum-Fall, den dieser Task beheben sollte.

**Rot-vorher:** `takeLast(5)`-Teil aus `sameAddress` gestrichen. Lauf: 35 Tests,
**1 rot** — genau der neue. Nach Rueckbau gruen.

**Der Zweig wurde NICHT entfernt** (Vorgabe des Directors). Der Test belegt
jetzt, dass er einen erreichbaren, korrekten Fall abdeckt. **Entscheidung des
Directors: der Zweig bleibt.**

## QA-016 — Kommentarzahl an drei Stellen

**Selbst nachgezaehlt** (zweimal, unabhaengig) an
`bt_manager_pixel11_ldac_990_loss.txt`: sieben `A2DP <Codec> State:`-Bloecke
(LDAC, LHDCv5, AptX-HD, AptX, AAC, Opus, SBC). **Fuenf** drucken eine
`Effective MTU:`-Zeile (LDAC=883, AptX-HD=0, AptX=0, AAC=0, SBC=0); **LHDCv5 und
Opus drucken sie gar nicht**. Von den fuenf sind **vier** = 0. Deckt sich mit
`qa/findings.md` („7 / 5 / 4“).

Korrigiert in `A2dpLinkDumpParser.kt` (Klassen-KDoc), `Ldac990LossGoldenTest.kt`
(KDoc der MTU-Assertion) und — nach Freigabe des Directors —
`LiveLinkParserTest.kt` (KDoc ueber `the MTU comes from the LDAC block …`).

**Der dritte Fundort war ein Zufallsfund**, vom `developer` gemeldet statt
nebenbei geaendert, und von mir gesondert freigegeben. Weder mein Auftrag noch
`qa/findings.md` hatten ihn genannt. **Das ist der richtige Umgang mit einem
Fund ausserhalb des Scopes.**

## Suite

Basislinie vor den Aenderungen eigenhaendig gemessen: **2482 / 0**.
Nach allen Aenderungen: **2486 / 0** — Differenz +4 = zwei neue Methoden x
(Debug + Release). Fuer den reinen Kommentar-Nachtrag `5218455` nur
`:core-monitor:testDebugUnitTest --tests LiveLinkParserTest`: 35 / 0.

**Vom Director akzeptiert**, dass fuer eine reine Kommentaraenderung kein voller
Suite-Lauf noetig war — dieselbe Datei war unmittelbar davor vollstaendig gruen
durch die volle Suite gelaufen.

## Transparent gemeldeter Fehltritt

Beim Einfuegen eines Gedankenstrichs per Skript wurde `"\xe2\x80\x94"` als
Byte-Escape missverstanden — drei falsche Codepoints statt des Zeichens.
**Vor jedem Commit** per `git diff` bemerkt und mit `—` korrigiert. Der
committete Stand ist geprueft korrekt.

## Bestaetigungen des Directors

- Die Annahme „bestehender Parser-Test“ = neue `@Test`-Methode in bestehender
  Klasse, keine neue Datei — **korrekt gelesen**.
- Der `sameAddress`-Zweig **bleibt**.
- Kein Debt-, kein Sicherheitsfund.
