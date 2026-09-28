# Produkt-Backlog

Gefuehrt vom `product-strategist`. Fortgeschrieben, nie ueberschrieben. IDs
`P-001 ...` laufen durch und werden nie neu vergeben. Den Status `freigegeben`
setzt ausschliesslich der Nutzer.

**Namensraum:** `P-001 ...` hier sind Backlog-Eintraege. Die Schritte `P-1..P-9`
aus `docs/berichte/T-045-architect.md` sind etwas anderes (Bauschnitt Saeule 3,
gebaut in S3-1..S3-6).

## Warteschlange (freigegeben, noch nicht gebaut) - 0 von hoechstens 3

| ID | Titel | Freigegeben am |
|---|---|---|
| P-001 | Ist-Stand der belegten Massnahmen (GOAL AK-18) | 2026-09-28, Nutzer |
| P-004 | Umgebungszustand am Verlustereignis (GOAL AK-19) | 2026-09-28, Nutzer |
| P-002 | Sprung zur Systemeinstellung (GOAL AK-20) | 2026-09-28, Nutzer |

Nachruecker: **P-003** (GOAL AK-21), freigegeben 2026-09-28 unter Bedingung
"ohne Standortberechtigung"; rueckt nach, sobald ein Eintrag gebaut ist.
Streichvorschlag "Feld `adaptiveBitrateIndex` nicht mehr fuehren" vom Nutzer
angenommen (28.09.); T-005-Schluss und AK-7→Nicht-Ziel abgelehnt.

Quittung: vor Lauf 1 existierte `docs/product/` nicht (Glob `docs/product/**`,
28.09.2026, 0 Treffer).

## Vergleichsfeld - Stand 2026-09-28

Werkzeuge: **EO** = Android-Entwickleroptionen (Pixel, Android 17, Systembestand);
**BCC** = Bluetooth Codec Changer 2.4.1 (Play Store, Stand 18.09.2026);
**BCM** = Bluetooth Codec Monitor (Open Source, GitHub, nicht im Play Store);
**WS** = HCI-Snoop-Log + Wireshark (Power-User-Weg, offline);
**RG** = Ratgeber-Artikel/Foren zu LDAC-Stottern (handgepflegte Checklisten).

| Funktion | EO | BCC | BCM | WS | RG | wir | Topf |
|---|---|---|---|---|---|---|---|
| Aktiver Codec mit Rate/Bits | ja | ja | ja | ja | nein | ja | A (vorhanden) |
| LDAC-Stufe / Codec stellen | ja | ja | nein | nein | nein | ja (Codec-Wechsel nur als Ausweichen, AK-12) | A (vorhanden) |
| Bitrate live gemessen | nein | ja ("measured", Messweise unbekannt) | nein (geschaetzt) | ja (offline) | nein | ja | A (vorhanden) |
| Wunsch je Geraet automatisch anwenden | nein | ja | nein | nein | nein | ja (Profile) | A (vorhanden) |
| Hintergrundueberwachung / Live-Wert in Benachrichtigung | nein | ja | ja | nein | nein | nein | A - **gesperrt durch AK-4, Nicht-Ziel** |
| Verlust-/Aussetzerzaehler aus dem Stack | nein | unbekannt (nicht beworben) | nein | teilweise (A2DP-Medienpakete fehlen im Bugreport-Snoop) | nein | ja | B (vorhanden) |
| Belegte Massnahmenliste fuer die hoechste Stufe | nein | nein | nein | nein | ja (Text, unbelegt) | ja (R-010-Katalog) | B (vorhanden) |
| Gemessener Vorher/Nachher-Vergleich einer Massnahme | nein | nein | nein | nein | nein | ja (Saeule 3) | B (vorhanden) |
| Rueckweg auf den Ausgangszustand | unbekannt | unbekannt | nein | nein | nein | ja (Ledger, AK-11) | B (vorhanden), im Feld nicht bestaetigt |
| Paarungsfakten (EDR, 3 Mbps, MTU) | nein | nein | nein | ja (offline) | nein | ja | C (vorhanden) |
| **Ist-Stand je Massnahme ausserhalb eines Vergleichs** | nein | nein | nein | nein | nein | nein (nur Rueckleseworte im Vergleich, `ComparisonSection.kt:289`) | **B -> P-001** |
| **Sprung zur Einstellung je Massnahme** | ja (ist selbst der Ort) | ja (stellt selbst) | nein | nein | teilweise (Menuepfad als Text) | nein (5 Intents, keiner zum Audiopfad) | **A -> P-002** |
| **WLAN-Band neben dem BT-Audiopfad** | nein | nein | nein | nein | teilweise (Rat "WLAN aus", ohne Band) | nein (`wifi_on` sagt das Band nicht, `Measures.kt:50-56`) | **B -> P-003** |
| **Umgebungszustand am Verlustereignis** | nein | nein | nein | teilweise (von Hand korrelierbar) | nein | nein | **B -> P-004** |
| Verlauf speichern + Export (CSV/MD/PDF) | teilweise (Bugreport) | unbekannt | ja | ja (Datei) | nein | nein | C - **Nicht-Ziel, Nutzer 03.09.** |
| Stabilitaetsetikett "stabil/instabil" | nein | unbekannt | ja (Heuristik) | nein | nein | nein | C - **Nicht-Ziel (Regel R-E)** |
| Meldung bei Codec-Abstieg/Trennung | nein | unbekannt | ja | nein | nein | nein | C - gesperrt durch AK-4 |
| Profile je App | nein | ja | nein | nein | nein | nein | C |
| Latenzmessung | nein | ja | nein | nein | nein | nein | C |
| Kopfhoerer-Akkustand | nein | ja | nein | nein | nein | nein | C |
| Geraete-Datenbank der Gemeinde | nein | ja | nein | nein | nein | nein | C |
| Equalizer | nein | ja | nein | nein | nein | ja (Hoerkompensation) | C (vorhanden) |

**Quellen, abgerufen 2026-09-28:**
- BCM: https://github.com/Iam-Master/bluetooth-codec-monitor-android (README: Room-Verlauf, Export CSV/MD/PDF, Vordergrunddienst, Stabilitaetsflag, Bitrate "estimated").
- BCC: https://www.free-codecs.com/download/bluetooth_codec_changer.htm (2.4.1 vom 18.09.2026: "measured bitrate" in der Benachrichtigung, Latenztest, Akkustand, Profile je App, EQ, Auto-Reapply). Play-Seite https://play.google.com/store/apps/details?id=com.amrg.bluetooth_codec_converter nur als Suchtreffer gesehen, Abruf lieferte keinen Inhalt; appbrain 403. Was dort nicht steht, ist in der Tabelle **unbekannt**.
- WS: https://ask.wireshark.org/question/20800/no-avdtp-in-bluetooth-hci-trace-with-ldac-audio/ (AVDTP fehlt im Bugreport-Snoop).
- RG: https://drhead.ae/blog/let-s-fix-the-stuttering-of-your-bluetooth-headphones/ und https://www.isuper.co.th/en/blog/11560/why-does-ldac-keep-stuttering-how-to-fix-unstable-bluetooth-audio (Suchtreffer; Rat "fuer 990 WLAN, WLAN-Scan und BT-Scan aus" — welcher der beiden Artikel ihn woertlich enthaelt, nicht geprueft).
- EO: Bestand im Projekt (`BluetoothDeveloperOptions.kt`, T-049a-Inventur), nicht extern.
- wir: Code am Stand `c2a29f5`, Suchen 28.09.2026 (`Settings.ACTION_` in `app/src/main`: 5 Treffer, keiner zum Audiopfad; `readConditions(` nur fuer den Vergleich in `MonitorViewModel.kt:149`).

## Lauf 1 - 2026-09-28 - Modus discover

Rahmen des Auftrags (T-053): jeder Kandidat ohne Geraet und ohne Nutzerhandgriff
baubar und pruefbar (Unit/Robolectric), hoechstens ein `developer`-Lauf. Die
Geraeteabnahme faellt in den naechsten Geraetetermin.

### P-001 - Ist-Stand der belegten Massnahmen, solange der Monitor offen ist   [B weisser Fleck]
**Problem:** Der Nutzer will 990 stabil fahren. Ob die Bedingungen dafuer
**gerade jetzt** gegeben sind (WLAN aus, kein zweites Geraet verbunden, keine
Geraetesuche, Kabel ab), sieht er heute nur mitten im gefuehrten Vergleich —
nach der Wahl einer Massnahme und einem 15-min-Arm. Wer einfach Musik hoert
und Aussetzer bemerkt, muss den Zustand selbst zusammensuchen; genau das hat
in T-008 (vierte Zelle, unbemerktes WLAN) sogar das Team verfehlt.
**Vorschlag:** Bei offenem Monitor steht zu jeder der sechs belegten
Massnahmen ihr aktueller Pruefstand — bestaetigt, widerspricht, oder nicht
pruefbar mit Grund —, aus demselben Zustandsbuch und mit denselben Worten wie
die Rueckleseworte des Vergleichs. Keine Gesamtnote, keine Ampel, keine
Reihenfolge-Empfehlung.
**Bei B - warum macht es niemand:** (1) Es lohnt sich nur fuer diese
Nutzungsweise. Die anderen Werkzeuge haben weder eine belegte
Massnahmenliste noch Stack-Lesungen (zweite ACL-Verbindung, Geraetesuche);
die Ratgeber haben die Liste nur als Text, ohne Ist-Wert.
**Abnahmekriterium:** Bei offenem Monitor zeigt die App zu jeder der sechs
Massnahmen aus `Measures.kt` den Pruefstand aus dem zuletzt gelesenen
Zustandsbuch; eine unlesbare Groesse erscheint als "nicht pruefbar" mit
ihrem Grund, nie als erfuellt; ohne offenen Monitor wird kein Zustand
gelesen, und es gibt keine zusammenfassende Bewertung.
**Beleg:** GOAL.md leitende Frage; `ComparisonSection.kt:289-306` (Rueckleseworte
nur im Vergleich); `docs/perf/T-036-trennmessung.md` (WLAN als Ausloeser);
`docs/lessons.md` Zyklus 1 (T-008 vierte Zelle, unbemerktes WLAN).
**Schnittgroesse:** ein Zyklus (Zustandsbuch, `Measure.verify` und Wortlaute
bestehen; neu ist die staendige Anzeige).
**Kosten und Risiko:** Eine weitere Lesung je Monitor-Takt (Settings.Global,
Akku-Intent), zu inventarisieren nach AK-4. Risiko R-E: sechs Haken
nebeneinander laden zum Lesen als "bereit fuer 990" ein — das darf der
Wortlaut nicht sagen, weil keine Massnahme unter gepinnten 990 als
hinreichend belegt ist. Wortlaut braucht `ui-ux-designer`.
**Nachts baubar:** ja (Robolectric), Geraeteabnahme spaeter.
**Status:** vorgeschlagen

### P-002 - Sprung zur Systemeinstellung je Massnahme und je Ausweichen   [A Basis]
**Problem:** AK-9 verlangt: wo die App nicht selbst stellt, springt sie an die
Stelle und sagt, was zu tun ist. Im gefuehrten Vergleich leitet sie heute nur
mit Text an; der Nutzer sucht WLAN-, Bluetooth- oder Entwickleroptionen selbst.
Er erkennt es daran, dass er die App verlassen und im System navigieren muss,
waehrend Arm A bereits abgeschlossen im Speicher liegt.
**Vorschlag:** Jede Massnahme und jedes Ausweichen, zu dem es eine
Systemstelle gibt, bekommt den Sprung dorthin; nach der Rueckkehr wird der
Ist-Wert neu gelesen und zurueckgemeldet.
**Bei A - vollstaendig heisst hier:** (a) alle sechs Massnahmen und vier
Ausweichen sind einzeln eingeordnet: Sprungziel, oder ausdruecklich
"keine Systemeinstellung, Handgriff" (Koerper aus dem Weg, Kabel ab);
(b) ein Sprung, den das Telefon nicht aufloesen kann, meldet das statt stumm
zu scheitern; (c) die drei Nur-Lesen-Werte bekommen **keinen** Sprung, weil
T-049a dafuer keinen Menuepunkt fand — dort steht der neutrale F-011-Satz;
(d) nach der Rueckkehr wird zurueckgelesen. Fehlt einer dieser vier Teile,
ist es ein Halbnachbau.
**Abnahmekriterium:** Fuer jede der zehn Eintraege aus `Measures.kt`
(`Measure`, `Fallback`) ist im Programm festgelegt, ob ein Sprungziel
existiert; jeder festgelegte Sprung ist per Test auf seine Intent-Aktion
geprueft, ein nicht aufloesbarer Sprung erzeugt einen sichtbaren Satz, und
nach der Rueckkehr in die App wird das Zustandsbuch neu gelesen.
**Beleg:** GOAL.md AK-9 ("springt sie per Deep-Link an die Stelle");
`docs/berichte/T-045-architect.md` AK-9-Zeile (keiner fuehrt zu einer
Audiopfad-Einstellung); Suche 28.09. am Code: unveraendert. Vergleichsfeld-Zeile
"Sprung zur Einstellung".
**Schnittgroesse:** ein Zyklus.
**Kosten und Risiko:** Sprungziele sind je Android-Version verschieden; ob
jedes auf Android 17 dort landet, wo es soll, zeigt erst das Geraet. Offene
Frage: loest das Oeffnen der Bluetooth-Einstellungen eine Geraetesuche aus?
Dann widerspraeche der Sprung fuer A3 der Massnahme A1.
Beruehrt dieselben Dateien wie der F-011-Fix — nicht parallel bauen.
**Nachts baubar:** ja (Robolectric prueft die Intents), Landeort erst am Geraet.
**Status:** vorgeschlagen

### P-003 - WLAN-Band lesen, damit die staerkste Massnahme pruefbar wird   [B weisser Fleck]
**Problem:** T-036 hat 2,4-GHz-WLAN als Ausloeser der Aussetzer bei 990
gezeigt — die am besten belegte Ursache des Projekts. Die App kann sie nur
pruefen, wenn WLAN ganz aus ist; bei eingeschaltetem WLAN sagt sie "nicht
pruefbar". Wer im 2,4-GHz-Netz haengt, bekommt also keinen Widerspruch
gemeldet, obwohl genau das der gemessene Fall ist.
**Vorschlag:** Die App liest, in welchem Band das WLAN gerade verbunden ist,
und fuehrt es im Zustandsbuch. 2,4 GHz widerspricht der Massnahme. Ob 5 GHz
unschaedlich ist, ist **nicht gemessen** und bleibt "nicht pruefbar", bis es
belegt ist.
**Bei B - warum macht es niemand:** (2) technisch teuer im Sinne von Rechten
und Entscheidungen, nicht von Code: es braucht eine neue Leseberechtigung und
kehrt die Festlegung "kein `WifiManager`" aus AD-035 (Nutzer 23.09., F3) um.
Gehoert vor Freigabe an `architect` und `researcher`.
**Abnahmekriterium:** Ist das Telefon mit einem 2,4-GHz-WLAN verbunden, meldet
der Pruefstand der Massnahme A2 "widerspricht"; bei 5 GHz, bei nicht lesbarem
Band und bei fehlender Berechtigung meldet er "nicht pruefbar" mit Grund; das
Band wird nur bei offenem Monitor oder an den Armgrenzen des Vergleichs gelesen.
**Beleg:** `docs/perf/T-036-trennmessung.md` (mit 2,4-GHz-WLAN 19 dropouts,
Arm "ohne" = WLAN **aus**, Z. 96 — 5 GHz also ungemessen);
`Measures.kt:50-56`; `ComparisonSection.kt:302-303`.
**Schnittgroesse:** ein Zyklus nach geklaerter Vorfrage.
**Kosten und Risiko:** neue Berechtigung; Spannung zum Nicht-Ziel "keine neue
Datensammlung ueber das hinaus, was der Audiopfad hergibt" — das Band ist
Umgebung, nicht Audiopfad; der Nutzer muss das entscheiden. SSID/BSSID
duerfen dabei nicht gelesen werden.
**Nachts baubar:** nein, erst nach Antwort von `architect` und Nutzerentscheid
zu AD-035.
**Status:** vorgeschlagen

### P-004 - Umgebungszustand am Verlustereignis festhalten   [B weisser Fleck]
**Problem:** Wenn bei offenem Monitor ein Verlustausbruch ins Protokoll geht,
steht dort was und wann, aber nicht, unter welchen Umstaenden. Den
Zusammenhang WLAN -> Aussetzer herzustellen hat das Team eine eigene
einstuendige Trennmessung gekostet (T-036), und T-029/T-032 blieben vorher
offen. Der Nutzer sieht heute im Alltag einen Ausbruch und weiss nicht, ob
zu dem Zeitpunkt WLAN an, ein zweites Geraet verbunden oder eine
Geraetesuche sichtbar war.
**Vorschlag:** Jeder Verlustausbruch im Protokoll traegt das Zustandsbuch vom
Moment seiner ersten Lesung. Die App nennt den Zustand, sie behauptet keinen
Zusammenhang.
**Bei B - warum macht es niemand:** (1) Kein anderes Werkzeug im Feld kennt
Verlustausbrueche ueberhaupt; BCM kennt nur Trennungen und Codec-Abstiege,
WS erlaubt die Korrelation nur offline von Hand.
**Abnahmekriterium:** Jeder Protokolleintrag eines Verlustausbruchs nennt die
Werte des Zustandsbuchs zum Zeitpunkt der ersten Lesung der Episode, jeden mit
"gelesen" oder "nicht lesbar: Grund"; kein Eintrag enthaelt ein Wort, das eine
Ursache behauptet; ohne offenen Monitor entsteht kein Eintrag.
**Beleg:** `docs/perf/T-036-trennmessung.md`; `docs/lessons.md` Zyklus 1 (T-008,
WLAN-Konfundierer); Vergleichsfeld-Zeile "Umgebungszustand am Verlustereignis".
**Schnittgroesse:** ein Zyklus (Protokoll und Zustandsbuch bestehen; neu ist
die Verbindung beider).
**Kosten und Risiko:** Jeder Eintrag wird laenger. Groesstes Risiko ist
Scheinkausalitaet: "WLAN an" neben jedem Ausbruch liest sich als Ursache, auch
wenn WLAN immer an ist. Wortlaut braucht `ui-ux-designer`. Nutzt dieselbe
Lesung wie P-001; zusammen gebaut billiger.
**Nachts baubar:** ja (Unit/Robolectric mit Fixture-Episode).
**Status:** vorgeschlagen

### Streichvorschlaege 1 - mindestens drei

**Was:** GOAL.md AK-7 (reserviert seit 03.09.) in ein Nicht-Ziel umwandeln —
"Kein Lesen der BQR-Telemetrie" — und T-037 damit schliessen.
**Warum es nicht mehr traegt:** AK-7 wartet seit 25 Tagen. Die Klaerung ist
gefallen, und zwar gegen das Kriterium: das Lesen leert die Queue (R-011, in
`docs/state.md` als Tatsache gefuehrt), und T-037 ist strukturell blockiert —
der Callback-Weg braucht einen echten App-Bootstrap oder AIDL im Helfer, also
eine Verbreiterung der Angriffsflaeche (AK-6, AK-10). Die Frage, fuer die AK-7
gedacht war (was stoert die Strecke), hat T-036 ohne BQR beantwortet.
**Was der Verzicht kostet:** Pakettyp und Wiederholrate bleiben unlesbar; AK-16
sagt damit dauerhaft "strukturell nicht bestimmbar" (AD-036), und AK-15 bleibt
bei diesen zwei Hardwarefakten unerfuellbar. Findet T-037 doch einen Lesezugang
ohne Loeschen, war der Verzicht verfrueht.

**Was:** Den ruhenden Scan-Entwurf T-005 (S-1..S-7) schliessen.
**Warum es nicht mehr traegt:** Er ruht seit 02.09. auf Nutzerwunsch, und AD-037
legt fest, dass der gefuehrte Prozess gilt, wo beide dasselbe tun. Ein
Entwurf, der ruht und in Teilen abgeloest ist, steht in der Auftragstabelle
von `docs/state.md` und wird bei jedem Lauf mitgelesen.
**Was der Verzicht kostet:** Was S-1..S-7 ueber den Prozess hinaus enthielt,
geht verloren. Welche Teile das sind, habe ich nicht geprueft — das muss
`architect` vor der Schliessung benennen.

**Was:** Das Feld `adaptiveBitrateIndex` (eine Haelfte von QA-005) nicht mehr
fuehren.
**Warum es nicht mehr traegt:** Es hat seit dem ersten Zyklus keinen
Konsumenten, und `UI_SPEC.md:3005-3010` begruendet, warum es keinen bekommen
darf: ein Stufenindex ohne belegte Zuordnung zur kbps-Leiter (nur ein
Wertepaar aufgenommen, Fixture-Luecke 2). Ein Feld, das nie gezeigt werden
darf, ist Pflegelast und eine Einladung, es doch zu zeigen.
**Was der Verzicht kostet:** Werden die Paare 660/1 und 492/3 spaeter als
Fixture aufgenommen, muss das Parsen wieder hinein. `adaptiveBitrateAdjustments`
bleibt davon unberuehrt (die Kopplung G-4/AK-T009-41 in `docs/state.md` haengt
daran).

**Geprueft und nicht gestrichen** (damit es im naechsten Lauf nicht wieder
auftaucht): die AVRCP-Versionswahl — fuer 990 wirkungslos (R-010 C2), aber mit
einem echten Nutzungsfall fuer einen anderen Kopfhoerer
(`BluetoothSystemControls.kt:17-18`); der Praeferenztest — gegen Nicht-Ziel 1
nur per Auslegung angreifbar, kein Beleg fuer Schaden.

### Bewusst nicht vorgeschlagen (Topf C)

- Verlauf speichern + Export: BCM, WS (teilweise EO). Vom Nutzer am 03.09. fuer
  den Beobachtungslauf und in AD-037 fuer Vergleiche ausdruecklich abgewaehlt.
- Stabilitaetsetikett "stabil/instabil": BCM. Waere eine erfundene Schwelle (R-E).
- Meldung bei Codec-Abstieg/Trennung: BCM. Braucht Hintergrundarbeit (AK-4).
- Profile je App: nur BCC. Hat mit der Frage nach stabilen 990 nichts zu tun.
- Latenzmessung: nur BCC. Nicht im Zielbild.
- Kopfhoerer-Akkustand: nur BCC; zeigt Android selbst.
- Geraete-Datenbank der Gemeinde: nur BCC. Widerspricht AK-15 (Hardwarefakten
  nie von einem anderen Geraet uebernehmen).

Topf A, aber gesperrt und deshalb **Nicht-Ziel**: Hintergrundueberwachung und
Live-Wert in der Benachrichtigung (BCC, BCM) — AK-4.

### Empfehlung

**Platz eins: P-001**, weil es die leitende Frage des Zielbilds direkt
beantwortet, fast nur aus vorhandenen Teilen besteht und heute nachts ohne
Vorklaerung baubar ist. P-001 und P-004 teilen sich die Lesung und sind
zusammen billiger; P-002 ist unabhaengig, aber nicht parallel zum F-011-Fix.
P-003 nicht vor den offenen Fragen.

Offene Fragen vor einer Freigabe:
- `architect` (P-001, P-004): Faellt die Lesung des Zustandsbuchs je
  Monitor-Takt unter AK-4-Inventar ohne neuen `dumpsys`-Aufruf?
- `architect` (P-002): Welche Sprungziele gibt es auf Android 17 fuer A1-A7
  und die vier Ausweichen, und loest das Oeffnen der Bluetooth-Einstellungen
  eine Geraetesuche aus?
- `architect` + Nutzer (P-003): Welche Berechtigung braucht das Band ohne
  Standortzugriff, und wird AD-035 ("kein `WifiManager`") dafuer geoeffnet?
- `researcher` oder `performance-tuner` (P-003): Ist 5-GHz-WLAN auf diesem
  Telefon fuer 990 unschaedlich? T-036 hat nur 2,4 GHz gegen "WLAN aus" gemessen.
- `architect` (Streichvorschlag T-005): Was aus S-1..S-7 deckt der Prozess
  nicht ab?

Freigabe liegt beim Nutzer.

## Lauf 2 - 2026-09-29 - Modus discover

Auftrag T-061c. **Warteschlange gezaehlt: 0** freigegebene, nicht gebaute
Eintraege (P-001..P-004 gebaut, Nachtrag unten) — discover zulaessig. Rahmen
wie Lauf 1: jeder Kandidat nachts ohne Geraet baubar und pruefbar
(Unit/Robolectric), Geraeteabnahme im naechsten Geraetetermin.
Play-Store- und Weitergabe-Pflichten stehen **nicht** unter den Kandidaten,
sondern unter "Bedingt", weil der Nutzer ueber eine Veroeffentlichung noch
nicht entschieden hat (C-001, Entscheidungsvorlage offen).

### Statusnachtrag zu Lauf 1 (am Code geprueft 29.09.)

Die Statuszeilen von Lauf 1 bleiben stehen, wie sie geschrieben wurden; es gilt
dieser Nachtrag. Die Warteschlangen-Tabelle oben fuehrt P-001, P-004, P-002 noch
als Zeilen, der Kopf "0 von hoechstens 3" ist richtig.

- P-001: gebaut (2026-09-29, T-058a) — `MeasureStatusPanel`, `MonitorScreen.kt:136`.
- P-004: gebaut (2026-09-29, T-058a) — `LossEpisode`, `ConditionStatus.kt:17`.
- P-002: gebaut (2026-09-29, T-058b) — `SettingsJumps.kt`, `MeasureStatusSection.kt`.
- P-003: gebaut (2026-09-29, T-058c) — `ACCESS_NETWORK_STATE`, ohne Standort,
  `app/src/main/AndroidManifest.xml:30-37`. Die Bedingung des Nutzers ist damit
  erfuellt (siehe Streichvorschlag 2.3).

Pruefphase T-059/T-060 abgenommen laut `docs/state.md`; Geraeteabnahme aller vier offen.

### Vergleichsfeld - Fortschreibung Stand 2026-09-29

Die Tabelle oben gilt weiter. Hier nur geaenderte und neue Zeilen, Spalten wie oben.

| Funktion | EO | BCC | BCM | WS | RG | wir | Topf |
|---|---|---|---|---|---|---|---|
| Ist-Stand je Massnahme ausserhalb eines Vergleichs | nein | nein | nein | nein | nein | **ja** (P-001) | B (vorhanden) |
| Sprung zur Einstellung je Massnahme | ja | ja | nein | nein | teilweise | **ja** (P-002) | A (vorhanden) |
| WLAN-Band neben dem BT-Audiopfad | nein | nein | nein | nein | teilweise | **ja** (P-003) | B (vorhanden) |
| Umgebungszustand am Verlustereignis | nein | nein | nein | teilweise | nein | **ja** (P-004) | B (vorhanden) |
| **Umgebungszustand ueber die ganze Messdauer, nicht nur an ihren Grenzen** | nein | nein | nein | teilweise (von Hand, offline) | nein | nein — Zustandsbuch an Armbeginn und Armende, nur die Geraetesuche wird dazwischen verfolgt (`ComparisonController.kt:194,199,221`) | **B -> P-005** |
| **Liste der von der App geaenderten Einstellungen mit Vorwert** | nein | unbekannt | nein (stellt nichts) | nein | nein | teilweise — Banner nennt nur die Anzahl (`DeviceProfilesScreen.kt:1433`), Einzelzeilen erst nach dem Zurueckstellen (`SettingsRestore.kt:219`) | **B -> P-006** |
| **Eigene Last des Werkzeugs auf den Audiopfad sichtbar** | nein | unbekannt (nicht beworben) | unbekannt (nicht beworben) | nein | nein | nein — nur Inventur-Dokument T-049a; F-012 und F-014 per adb gefunden | **B -> P-007** |
| Widget / Schnellkachel fuer Codec oder Profil | unbekannt | ja | unbekannt | nein | nein | nein | C |
| Abtastrate, Bittiefe, Kanalmodus stellen | ja | ja | nein | nein | nein | nein (44,1-kHz-Familie nur als Ausweichen-Wissen, AK-12) | C - gesperrt durch AK-12 |
| Bass-Booster, Raumklang, Hall | nein | ja | nein | nein | nein | nein | C - Nicht-Ziel 1 |

Gesichtet, keine Spalte: "Bluetooth Codec Monitor" v1.0.5 bei Appteka
(Suchtreffer: Codec, Rate, Bittiefe, Kanalmodus, Hintergrundueberwachung,
Aenderungsverlauf). Ob es dieselbe App wie BCM ist, ist **unbekannt**; seine
Merkmale fallen in bestehende Zeilen (Hintergrund gesperrt, Verlauf Nicht-Ziel).

**Quellen, abgerufen 2026-09-29:**
- BCC: https://bluetooth-codec-changer.en.uptodown.com/android (2.4.1, dort mit
  23.09.2026 datiert: Codec automatisch bei Verbindung, Profile je App, EQ,
  Bass-Booster, Raumklang, Hall, Abtastrate/Bittiefe/Kanalmodus, Widgets). Die
  Schnellkachel stammt nur aus einer Suchtreffer-Zusammenfassung (Grokipedia,
  nicht abgerufen). Play-Seite https://play.google.com/store/apps/details?id=com.amrg.bluetooth_codec_converter
  lieferte erneut keinen Inhalt.
- BCM Appteka: https://appteka.store/app/ab5r318181 (nur Suchtreffer, nicht abgerufen).
- BCM GitHub: README wie Lauf 1; fuer die Zeile "eigene Last" nicht geprueft, daher unbekannt.
- EO: Abtastrate/Bittiefe/Kanalmodus sind Bestand der Entwickleroptionen (Projektbestand `BluetoothDeveloperOptions.kt`, nicht extern).
- wir: Code am Stand `68d3859`, Suchen 29.09.2026 (Zeilennachweise in der Tabelle).

**Befund zu Topf A: leer.** Was das Feld fast ueberall hat und hier fehlt, ist
durch AK-4 oder Nicht-Ziele gesperrt (Hintergrund, Benachrichtigung, Verlauf);
die einzige offene A-Zeile aus Lauf 1 (Sprung) ist gebaut. Aufholen steht in
diesem Lauf nicht an. Deshalb nur drei Kandidaten, alle Topf B.

### P-005 - Umgebungszustand ueber die ganze Armdauer, nicht nur an den Grenzen   [B weisser Fleck]
**Problem:** Ein Vergleichsarm dauert 15 min (`Comparison.kt:9`). Das
Zustandsbuch wird an seinem Beginn und seinem Ende gelesen; nur die
Geraetesuche wird dazwischen verfolgt. Faellt das WLAN mitten im Arm aus der
Verbindung und kommt zurueck, verbindet sich fuenf Minuten lang ein zweites
Geraet oder steckt kurz ein Ladekabel, dann liest der Arm an beiden Enden
denselben Zustand. Das Ergebnis nennt dann Bedingungen, unter denen der Arm
nicht durchgehend lief. Erkennbar ist das heute gar nicht — genau deshalb.
**Vorschlag:** Waehrend eines Arms wird jede lesbare Groesse des Zustandsbuchs
bei jeder Lesung mit dem Stand am Armbeginn verglichen. Wich sie ab, nennt das
Ergebnis an Ort und Stelle, welche Groesse und wie lange die Abweichung
beobachtet wurde. Die App behauptet keinen Zusammenhang mit dem Ergebnis.
**Bei B - warum macht es niemand:** (1) Es lohnt sich nur fuer diese
Nutzungsweise. Kein anderes Werkzeug im Feld hat einen gemessenen
Vorher/Nachher-Vergleich mit Zustandsbuch; das Muster "waehrend des Arms
gesehen" existiert hier schon fuer die Geraetesuche (`discoverySeen`).
**Abnahmekriterium:** Weicht waehrend eines Vergleichsarms eine lesbare Groesse
des Zustandsbuchs bei irgendeiner Lesung vom Stand am Armbeginn ab, nennt das
Vergleichsergebnis an Ort und Stelle diese Groesse und die Dauer, ueber die die
Abweichung beobachtet wurde; eine Groesse, die im Arm zeitweise nicht lesbar
war, erscheint als "nicht durchgehend lesbar" und nie als unveraendert.
**Beleg:** Code `ComparisonController.kt:194,199,221` (Lesung an den
Armgrenzen); `docs/perf/T-027-hoereindruck.md:70` (WLAN-Assoziationsverlust
19:48:49 mitten in einer Zelle); `docs/perf/baselines.md:338-340`
(T-029/T-032-Widerspruch erst nachtraeglich durch die WLAN-Assoziation
erklaert); `docs/state.md` T-008 vierte Zelle INCONCLUSIVE (unbemerktes WLAN)
und Verfahrensregel "Read-back deckt das vollstaendige Zustandsbuch ab, je
Abschnitt". Vergleichsfeld-Zeile "Umgebungszustand ueber die ganze Messdauer".
**Schnittgroesse:** ein Zyklus.
**Kosten und Risiko:** Lesen im Arm, auch bei Display aus in der
AK-4-Ausnahme — ob der Messdienst das Zustandsbuch dort schon je Lesung liest,
ist offen (Frage an `architect`); falls nicht, ist es neue Arbeit innerhalb
der Ausnahme und gehoert ins Inventar. Risiko Scheinkausalitaet wie bei P-004:
"WLAN wich 4 min ab" neben einem schlechten Arm liest sich als Ursache.
Wortlaut braucht `ui-ux-designer`.
**Nachts baubar:** ja (Robolectric mit Lesungsverlauf "Zustand kippt und kehrt zurueck").
**Status:** vorgeschlagen

### P-006 - Welche Einstellungen die App gerade veraendert haelt, mit Vorwert   [B weisser Fleck]
**Problem:** Der Rueckweg-Banner sagt "3 settings not put back yet"
(`DeviceProfilesScreen.kt:1433`). Welche es sind, was die App gesetzt hat und
was vorher galt, erfaehrt der Nutzer erst, nachdem er alles zurueckgestellt hat
(Bericht, `SettingsRestore.kt:219`). Wer 990 gepinnt lassen, aber wissen will,
was die App sonst noch an seinem Telefon veraendert haelt, muss den Rueckweg
blind ausloesen oder ihm blind nicht trauen.
**Vorschlag:** Solange Aenderungen der App offen sind, kann der Nutzer vor dem
Rueckweg jede davon sehen: Einstellung, gesetzter Wert, festgehaltener Vorwert.
Ansehen schreibt nichts. Kein teilweiser Rueckweg in diesem Schnitt.
**Bei B - warum macht es niemand:** (1) Kein anderes Werkzeug im Feld haelt
einen Ausgangszustand fest (Lauf 1, Zeile "Rueckweg": nur wir); ohne Liste gibt
es nichts anzuzeigen.
**Abnahmekriterium:** Solange Einstellungen, die die App gesetzt hat, nicht
zurueckgestellt sind, nennt die App vor dem Rueckweg jede davon mit dem
gesetzten Wert und dem vor dem ersten Setzen festgehaltenen Wert; ein
Vorwert "war nicht gesetzt" und ein Vorwert "vorher nicht lesbar" werden
unterschieden und nie als Standardwert gezeigt; die Anzeige schreibt nichts.
**Beleg:** *unbelegt* als Nutzerproblem — kein QA-, Usability- oder
Nutzerbefund. Vorhanden sind nur der Code-Befund (Banner zeigt die Anzahl)
und C-001 Befund 1 (Play-Regel "review … all significant changes", A-002);
Letzteres zaehlt nur bei Veroeffentlichung.
**Schnittgroesse:** ein Zyklus (Liste und Vorwerte bestehen, neu ist die Anzeige).
**Kosten und Risiko:** Eintraege der Codec-Praeferenz haengen an einem Geraet —
angezeigt wird der Name, nie die Adresse (Muster SR-030); braucht einen Blick
des `security-reviewer`. Ein weiterer Ort, an dem der unlesbare Zustand der
Liste (F-021/AD-042) richtig erscheinen muss.
**Nachts baubar:** ja (Robolectric).
**Status:** vorgeschlagen

### P-007 - Die App zeigt ihre eigene Last auf den Audiopfad   [B weisser Fleck]
**Problem:** Das Zielbild verspricht, dass Messen kein Eingriff ist (Ziel,
AK-1, AK-4). Zweimal hat die App das gebrochen, ohne dass es in der App
sichtbar war: F-012 (43 Helferaufrufe in 25 s nach Verlassen des Monitors) und
F-014 (`dumpsys` alle 30 s bei geschlossenem Monitor, leert laut R-011 die
BQR-Queue). Beide fand QA nur am Geraet mit adb. Der Nutzer selbst kann nicht
pruefen, ob die App still ist, wenn sie es sein soll.
**Vorschlag:** Die App zaehlt ihre eigenen Aufrufe in den Audiopfad —
Helferaufrufe und `dumpsys`-Lesungen, je Quelle — und zeigt bei offenem
Monitor, wie viele es seit dem letzten Schliessen gab. Gezaehlte Werte, keine
Bewertung, kein "leise/laut".
**Bei B - warum macht es niemand:** (1) Es lohnt sich nur fuer ein Werkzeug,
das Nicht-Einmischung verspricht. BCC und BCM fragen im Hintergrund ab, das
ist dort das Merkmal (Lauf 1, Zeile "Hintergrundueberwachung"); eine Zaehlung
wuerde ihnen nichts belegen, was sie behaupten.
**Abnahmekriterium:** Die App zaehlt jeden eigenen Helferaufruf und jede eigene
`dumpsys`-Lesung je Quelle; bei offenem Monitor zeigt sie die Zahlen fuer die
Zeit seit dem letzten Schliessen des Monitors als gezaehlte Werte ohne
Bewertung; jeder Aufrufweg zum Helfer ist per Test an die Zaehlung gebunden,
und das Zaehlen selbst loest keine Lesung aus.
**Beleg:** `qa/findings.md` F-012, F-014 (beide P2, beide nur per adb
gefunden); GOAL AK-4 ("Jede periodische Arbeit im Prozess ist inventarisiert")
— das Inventar ist heute ein Dokument (`docs/perf/T-049a-inventur.md`), keine
laufende Pruefung.
**Schnittgroesse:** ein Zyklus, falls es eine Stelle gibt, an der alle Aufrufe
vorbeikommen (Frage an `architect`); sonst mehrere.
**Kosten und Risiko:** Groesstes Risiko ist eine unvollstaendige Zaehlung:
ein Aufrufweg, der nicht mitzaehlt, ergibt eine zu kleine Zahl — ein falscher
Freispruch, gegen AK-3. Deshalb die Testbindung im Kriterium. Die Zahl sagt
nichts ueber Hoerbarkeit (Regel R-E); der Wortlaut darf das nicht nahelegen.
**Nachts baubar:** ja, wenn die Zaehlstelle feststeht.
**Status:** vorgeschlagen

### Bedingt - nur wenn der Nutzer eine Weitergabe will (C-001, offen)

Keine Kandidaten, keine P-IDs. Werden sie gebraucht, schneide ich sie in einem
eigenen Lauf zu. Kennung aus `docs/legal/AUFLAGEN.md`.
- **A-014 Lizenzseite in der App**, `THIRD_PARTY_LICENSES.md` um Conscrypt
  ergaenzt — Pflicht schon bei der ersten Weitergabe der APK, auch ueber
  GitHub (C-001 Nr. 7). Der kleinste Punkt, ein Zyklus.
- **A-016/A-018 Zweckbestimmung der Hoerkompensation**, Textpruefung,
  MDR-Klaerung — jede Weitergabe einer Fassung mit Hoerkompensation.
- **A-005 `QUERY_ALL_PACKAGES` aus dem Play-Build** — nur Play; die Frage
  unabhaengig vom Store steht in Streichvorschlag 2.1.
- **A-001 Offenlegung des Helfers im Setup mit Zustimmung, A-010
  Datenschutzerklaerung in der App** — nur Play.
- **A-012 Loeschen der Monitor-Historie in der App** (empfohlen): Hoertest-Laeufe
  lassen sich loeschen (`HearingTestScreen.kt:1398`), die Monitor-Historie mit
  Geraeteadressen nicht; sie verfaellt nach 24 h (`MonitorEngine.kt:253`).

### Streichvorschlaege 2 - mindestens drei

**2.1 Was:** Die Berechtigung `QUERY_ALL_PACKAGES` — auch in der privaten
Fassung. Die EQ-App-Erkennung stuetzt sich dann auf die vorhandene
`<queries>`-Liste (`app/src/main/AndroidManifest.xml:69-79`).
**Warum es nicht mehr traegt:** Die App liest dafuer die vollstaendige Liste
aller installierten Apps. Das ist mehr, als der Audiopfad hergibt (Nicht-Ziel
"Keine neue Datensammlung …"), Google zaehlt es zu den sensiblen Daten (C-001
Nr. 2), und fuer Play ist es ROT. Der Manifest-Kommentar selbst haelt die
`<queries>`-Liste als tragende Rueckfallebene vor. Bei einer spaeteren
Weitergabe muesste es ohnehin weg (A-005); zwei Fassungen mit
unterschiedlicher Erkennung waeren Pflegelast.
**Was der Verzicht kostet:** Ehrlich: ein EQ, den die Liste nicht kennt, wird
nicht mehr gefunden — und der Manifest-Kommentar (Z. 40-43) begruendet die
Vollabfrage genau mit diesem Fall. Die Anzeige muss dann sagen "nur bekannte
EQ-Apps" (AK-3). Bleibt die App privat, ist das Play-Argument leer und nur das
Nicht-Ziel-Argument bleibt.

**2.2 Was:** Die dauerhafte Ablage der Encoder-Starvation-Forensik in Room
(Tabelle `encoder_starvation_events`). Die Zeile im Ereignisprotokoll bleibt.
**Warum es nicht mehr traegt:** Die App schreibt die Forensik
(`LiveLinkSource.kt:287`, `MonitorGraph.kt:223`), liest sie aber nirgends:
`starvations(` hat im Hauptcode keinen Aufrufer ausser seiner eigenen
Definition (`MonitorRepository.kt:35,67`, `MonitorDatabase.kt:142`; Suche
29.09.). Sie verfaellt nach 24 h (`MonitorEngine.kt:253`,
`MonitorRepository.kt:74`), der KDoc verspricht dagegen eine Auswertung
"possibly months later" per `sqlite3` (`MonitorDatabase.kt:58-59`) — auf einem
ungerooteten Telefon ohne Debug-Build nicht erreichbar. Der eigene Kommentar
sagt: "a capture nobody can read later is worth exactly as much as no
capture" (`LiveLinkSource.kt:282-283`).
**Was der Verzicht kostet:** Eine Schema-Migration. Die Forensik (welche
Effektketten beim Aushungern liefen) ist verloren — wer sie spaeter doch
auswerten will, braucht einen Leser, und das waere ein eigener Kandidat. Ob
QA oder `performance-tuner` die Tabelle per `run-as` nutzt, habe ich nicht
geprueft; das muss vor der Streichung gefragt werden.

**2.3 Was:** Die Bedingungsklausel von AK-21 in `GOAL.md` ("— bedingt",
"Geht das nicht, bleibt P-003 vorgemerkt und AK-21 ruht").
**Warum es nicht mehr traegt:** Die Bedingung ist erfuellt — P-003 ist mit
`ACCESS_NETWORK_STATE` und ohne Standortberechtigung gebaut
(`app/src/main/AndroidManifest.xml:30-37`). Ein Kriterium, das als "bedingt"
und moeglicherweise ruhend dasteht, wird von jedem Lesenden als halb gueltig
gelesen.
**Was der Verzicht kostet:** Die Bedingung selbst ("nur ohne Standort, keine
SSID/BSSID") darf nicht mit verschwinden — sie ist die Grenze, die kuenftige
Aenderungen halten muessen. Gestrichen wird nur das "ruht"; die Grenze bleibt
als Satz im Kriterium. Uebertragen durch den `director` nach Nutzerentscheid.

**Geprueft und nicht gestrichen:** Die Deklaration `BLUETOOTH_PRIVILEGED`
(`core-monitor/src/main/AndroidManifest.xml:17`) — wird nie gewaehrt, haengt
aber am BQR-Weg und damit an AK-7/T-037; der Nutzer hat AK-7 am 28.09. nicht
aufgegeben, und T-061b entwirft den Weg gerade. Erst nach dieser AD neu bewerten.

### Bewusst nicht vorgeschlagen (Topf C)

- Widget / Schnellkachel fuer Codec oder Profil: BCC. Stellt ohne Messung und
  ohne Rueckmeldung — gegen "misst danach gegen"; die Stufe ist im Monitor
  einen Handgriff entfernt.
- Abtastrate, Bittiefe, Kanalmodus stellen: EO, BCC. Wechselt oder senkt die
  Leiter; nach AK-12 nur Ausweichen-Wissen.
- Bass-Booster, Raumklang, Hall: BCC. Nicht-Ziel 1.
- Aus Lauf 1 unveraendert: Verlauf/Export, Stabilitaetsetikett, Meldung bei
  Abstieg/Trennung, Profile je App, Latenz, Akkustand, Gemeinde-Datenbank
  (Gruende dort).

Gesehen und zurueckgestellt, kein Topf: einen Vergleich wiederholen (Arm A
nach Arm B noch einmal), weil Verluste in Clustern kommen (T-036). Das baut auf
Saeule 3, die am Geraet noch nicht abgenommen ist — erst danach.

### Empfehlung

**Platz eins: P-005**, weil es das Kernergebnis der Saeule 3 gegen genau den
Fehler absichert, der das Team selbst dreimal getroffen hat (T-008, T-027,
T-029/T-032), und das Muster dafuer im Code schon fuer die Geraetesuche
existiert. P-007 danach (belegt, aber Schnitt haengt an einer
Architekturfrage). P-006 zuletzt (unbelegt). Alle drei sind unabhaengig
voneinander; P-005 und P-006 beruehren verschiedene Bildschirme.

Offene Fragen vor einer Freigabe:
- `architect` (P-005): Liest der Messdienst im Arm bei Display aus das
  Zustandsbuch je Lesung, oder nur der offene Monitor? Wenn nicht: welche
  Lesung kommt dazu, und wie steht sie im AK-4-Inventar?
- Nutzer (P-005): Genuegt es, eine Abweichung zu nennen (Vorschlag), oder soll
  sie den Vergleich "nicht vergleichbar" machen? Und soll der Beobachtungslauf
  (AK-17) dasselbe bekommen — das vergroessert den Schnitt.
- `architect` (P-007): Gibt es eine Stelle, an der jeder Helferaufruf und jede
  `dumpsys`-Lesung vorbeikommt, einschliesslich EQ-Dienst und
  Boot-Empfaenger? Zaehlt die App oder der Helfer?
- `security-reviewer` (P-006): Anzeige der Eintraege mit Geraetebezug.
- `qa-engineer`/`performance-tuner` (Streichvorschlag 2.2): Wird
  `encoder_starvation_events` fuer Messungen per `run-as` gelesen?
- `performance-tuner` (aus Lauf 1 offen, kein Kandidat): 5-GHz-WLAN gegen
  "WLAN aus" bei gepinnten 990 ist ungemessen; ob der gebaute Vergleich A2 bei
  5-GHz-Verbindung ueberhaupt startet ("nicht pruefbar"), ist zu klaeren.

Freigabe liegt beim Nutzer.
