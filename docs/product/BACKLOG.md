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
