# Sicherheitsbefunde

Fortgeschrieben, nie ueberschrieben. Behobene Befunde bleiben mit Status
"behoben" stehen. Angelegt 2026-08-31 nach dem Papier-Review von T-005
(Entwurfs-Review, kein Code — vollstaendige Befundtexte im damaligen
Review-Bericht, Kurzfassungen unten).

Status: offen | in Arbeit | behoben | akzeptiert (Restrisiko, mit Datum
und Entscheider) | entfallen (Grund)

| ID | Titel | Prioritaet | Status | Letzte Pruefung |
|---|---|---|---|---|
| SR-001 | Gestagte Dumps **0666** in `/data/local/tmp` — Geraetenamen und BT-Adressen fuer jede App les- UND schreibbar; **ueberlebt die Deinstallation** | hoch | offen (eigener Task T-006), am Geraet BESTAETIGT und verschaerft | 2026-09-01 |
| SR-002 | `wifiFacts`: SSID/BSSID-Zusage nur Konvention, Fehlerpfad reicht Rohtext durch | hoch | offen (Auflage A1 vor S-3) | 2026-08-31 |
| SR-003 | `wifiFacts`-Parser verarbeitet vom AP kontrollierten Text im Shell-Prozess | hoch | offen (Auflage A2 vor S-3) | 2026-08-31 |
| SR-004 | Settings-Leihe ohne belegte Wirkung — Risiko ohne gesicherten Ertrag | hoch | offen (Geraetefrage vor S-6) | 2026-08-31 |
| SR-005 | Rueckgabe der geliehenen Schalter faellt bei Deinstallation, Permission-Entzug oder ausbleibendem App-Start dauerhaft aus | hoch | offen | 2026-08-31 |
| SR-006 | Ledger in `datastore/` reist per Cloud-Sicherung und Geraeteuebertragung | mittel | offen | 2026-08-31 |
| SR-007 | Ledger: Doppelleihe macht Leihwert zum Nutzerwert; Rueckgabe ohne Vergleiche-und-Setze ueberschreibt Nutzerentscheidung | mittel | offen | 2026-08-31 |
| SR-008 | Kein Test erzwingt parameterlose fest verdrahtete Vektoren (`wifiFacts`) | mittel | offen (Auflage A3 vor S-3) | 2026-08-31 |
| SR-009 | Helper-Log **0666** (welt-les- und -schreibbar), ueberlebt die Deinstallation; Nebenausgang fuer WLAN-Daten, Log-Injection ueber SSID, zusaetzlich Integritaetsverlust | **hoch** (hochgestuft 2026-09-01, Auflage erfuellt: Modus am Geraet geprueft) | offen | 2026-09-01 |
| SR-010 | Versionssprung 5 → 6: Methode ans AIDL-Ende, KDoc fortschreiben | niedrig | offen (Auflage A4 vor S-3) | 2026-08-31 |
| SR-011 | Ungesalzener MAC-Hash als `deviceKey` im Bericht | niedrig | offen | 2026-08-31 |
| SR-012 | **`/data/local/tmp/btperf`: 117 Dateien, 13 MB Roh-Dumps mit Geraetenamen und MACs, 0666/0777, ueberlebt die Deinstallation** — erzeugt von `docs/perf/tools/*.sh`, nicht von Produktivcode | hoch | offen (Rolle: performance-tuner; Behebung `umask 077` in den Skripten) | 2026-09-01 |
| SR-013 | Bauform 3 (entlinkte Datei in `/data/local/tmp`): Entlinken wirkt **nicht rueckwirkend** — eine App, die den konstanten Namen im Oeffnungsfenster trifft, haelt den Deskriptor darueber hinaus und liest den vollen Dump | hoch | offen (Auflage A11 vor U-0) | 2026-09-01 |
| SR-014 | Pipe-Bauformen 1/2: Schreiben aus dem Binder-Thread in eine 64-KB-Pipe, waehrend der Aufrufer synchron wartet, **verklemmt den absichtlich unsterblichen Helper dauerhaft** | hoch | offen (Auflage A14 vor U-2) | 2026-09-01 |
| SR-015 | Loeschung von `ExecHandoff` entfernt die einzige Erkennung stiller Trunkierung — kurzer Dump statt Fehlermeldung, verletzt AK-3 | mittel | offen (Auflage A13 vor U-1) | 2026-09-01 |
| SR-016 | Helper **ueberlebt die Deinstallation** und pollt unbegrenzt weiter — R1 aus AD-011 ist fuer den Regelfall widerlegt; verwaister Shell-Prozess ohne Beendigungsbedingung | mittel | offen (Nachtrag an architect) | 2026-09-01 |
| SR-017 | `umask 077`: auf dem Auto-Start-Pfad strukturell, auf dem Kopier-Pfad Konvention; durch jeden expliziten `ownerOnly=false`-Aufruf aushebelbar; erreicht SR-012 nicht | mittel | offen (Auflage A15 vor U-5) | 2026-09-01 |
| SR-018 | Nutzlast-Deskriptor kann an `dumpsys`-Kindprozesse vererbt werden — kein EOF, mit SR-015 zusammen stumme Kuerzung | mittel | offen (Auflage A12, Messpunkt in U-0) | 2026-09-01 |
| SR-019 | Reihenfolgekopplung: `umask 077` **vor** dem FD-Transport erzeugt keinen Ausfall (`ExecSpill` stellt mit `setReadable(true,false)` 0644 her), sondern einen **unabgenommenen Zwischenstand** — Integritaetshaelfte von SR-001 zu, Vertraulichkeitshaelfte offen, messbar wie eine Behebung, wirksam wie die verworfene Option A. Version 6 liefert einen Zustand, nicht zwei | mittel | offen (Auflagen A9/A10 vor Freigabe v6; **Text korrigiert 2026-09-01**, Auflage unveraendert in Kraft) | 2026-09-01 |
| SR-020 | Empfangener Deskriptor wird auf den Ablehnpfaden von `exec` nicht freigegeben; `null`-Deskriptor auf privilegierter Flaeche | niedrig | offen (Auflage A14 vor U-2) | 2026-09-01 |
| SR-021 | Aufraeumpfad in `shutdown()` laeuft beim Helperwechsel nicht (Nachfolger SIGKILLt den Vorgaenger vor dem Handover); Inode-Falle bestaetigt und zu verallgemeinern | niedrig | offen (Nachtrag an architect) | 2026-09-01 |
| SR-022 | Schrittnummern S-1..S-7 gehoeren zu T-005; AD-010/011/012 haben keine eigene Schrittfolge — Auflagen nicht verankerbar (Vorschlag U-0..U-6) | niedrig | offen (Nachtrag an architect) | 2026-09-01 |

## Kurzfassungen

- **SR-001** (Bestand, nicht T-005): `ExecSpill` legt Antworten > 64 KB als
  0644-Datei mit konstantem Namen in `/data/local/tmp` ab. Jede
  permissionlose App kann `btdash_exec_current.out` zyklisch lesen und
  erhaelt bei offener Live-Ansicht sekuendlich den vollen
  `dumpsys bluetooth_manager` (Namen + MACs aller gekoppelten Geraete).
  Behebung: Uebergabe per ParcelFileDescriptor ueber Binder ODER Chunking
  unter dem Limit; Minimum: unvorhersagbarer Name + sofortiges Loeschen.
  → T-006. Solange offen: `dumpsys wifi` als Rueckfallweg **gesperrt**
  (Auflage A5).
- **SR-002**: Die `wifiFacts`-Antwort darf **kein** Freitextfeld tragen
  (kein note/raw/reason); Fehler nur als geschlossene Codemenge
  (UNPARSEABLE, NO_LINK, COMMAND_FAILED(exit)); Marker-Test ueber alle
  Zweige (Erfolg, Exit!=0, leer, verstuemmelt, Timeout), dass SSID/BSSID
  nirgends auftauchen. Bestehende Fehlerpfade (`encodeError`,
  restartBluetooth-Muster) duerfen nicht wiederverwendet werden.
- **SR-003**: SSID ist vom AP-Betreiber frei waehlbar (32 Bytes inkl.
  Leerzeichen/=/Zeilenumbruch) und erreicht den Parser im Shell-Prozess.
  Verankerte Feldmuster je Feld, SSID/BSSID benennen und verwerfen,
  bei Mehrdeutigkeit UNPARSEABLE statt raten, feindliche Fixtures im Test.
- **SR-004**: Beide Schalter regeln dokumentiert das Scannen bei
  **ausgeschaltetem** Radio; im Experiment sind beide Radios an. Vor jedem
  Bau der Leih-Experimente (S-6) am Geraet pruefen, ob das Umlegen im
  eingeschalteten Zustand die Scan-Kadenz ueberhaupt aendert. Faellt die
  Annahme, entfallen AD-006/AD-007/S-4/S-6 ersatzlos.
- **SR-005**: Rueckgabe haengt an App-Start + WRITE_SECURE_SETTINGS +
  Ledger; Deinstallation im geliehenen Zustand laesst zwei
  Ortungsschalter dauerhaft aus (betrifft Notruf-Ortung, Geraete-Finden,
  Tracker-Warnungen). Auflagen: Rueckgabe beim Verlassen des Vordergrunds,
  laufende Benachrichtigung als sichtbares zweites Ledger, Rueckgabe an
  BootReceiver/BluetoothConnectReceiver haengen, Einwilligung je Lauf mit
  Folgen-Nennung, kein Lauf bei laufendem Anruf.
- **SR-006**: Ledger nicht in `datastore/` sichern (Backup/Transfer traegt
  ihn auf fremde Geraete) — `<exclude>` in backup_rules.xml und
  data_extraction_rules.xml oder eigener Pfad; Eintrag traegt
  Boot-/Installations-Kennung, sonst verwerfen.
- **SR-007**: Ledger-Eintrag ist die Sperre (keine Doppelleihe,
  previousValue nie ueberschreiben); Rueckgabe als Vergleiche-und-Setze
  (Feld `writtenValue` ergaenzen); alte Eintraege dem Nutzer vorlegen;
  synchrones Schreiben abwarten; genau ein Aufrufer (Screen-Scope).
- **SR-008**: `wifiFacts(token)` ohne weitere Parameter, `mayStage=false`;
  reflektiver Test: mutates=false-Operationen mit Prozessstart nehmen nur
  den Token; Test vergleicht konstante Vektoren gegen erwartete Liste.
- **SR-009**: `wifiFacts` protokolliert nie Ausgabe/Netzdaten, nur
  Feldanzahl oder Fehlercode; Fremdtext vor Log-Ausgabe von Steuerzeichen
  befreien; Dateimodus von `btdash_helper.log` am Geraet pruefen (stat) —
  bei 0644 mit SR-001 zusammenfuehren und hochstufen.
- **SR-010**: Neue AIDL-Methode ans Ende (Transaktionscodes), VERSION-KDoc
  um Eintrag fuer 6 ergaenzen.
- **SR-011**: Ungesalzener Hash einer 48-Bit-MAC ist keine
  Anonymisierung; Salz je Installation oder gar keine stabile Kennung im
  Bericht.

## Geraeteverifikation 2026-09-01 (T-007, performance-tuner, read-only)

Am Pixel 11 Pro nachgemessen, **nachdem die App deinstalliert war**:

```
-rw-rw-rw-  1 shell shell 118487 2026-08-30 19:41 btdash_exec_current.out
-rw-rw-rw-  1 shell shell  11837 2026-08-30 19:41 btdash_helper.log
drwxrwxrwx 15 shell shell   3452 2026-08-30 19:43 btperf
```

Drei Verschaerfungen gegenueber dem Papier-Review:

1. **Modus ist 0666, nicht 0644.** Die Dateien sind nicht nur welt-lesbar,
   sondern welt-**schreibbar**. Damit kommt eine Dimension hinzu, die das
   Review nicht behandelt hat: jede App kann das Helper-Log **manipulieren**
   — genau die Datei, die bei einer Fehlersuche gelesen wird. Aus einem
   reinen Vertraulichkeitsbefund wird zusaetzlich ein Integritaetsbefund.
2. **Die Reste ueberleben die Deinstallation.** `/data/local/tmp` gehoert
   nicht zum App-Datenverzeichnis; der Paketmanager raeumt es nicht auf.
   Geraetenamen und BT-MACs aus alten Dumps liegen dort zeitlich
   unbegrenzt, auch wenn die App laengst weg ist.
3. **Das Verzeichnis `btperf` steht auf 0777** — schreibbar fuer jeden.

Folge fuer T-006: Die Behebung muss zusaetzlich einen **Aufraeumpfad**
enthalten (Loeschen beim Start und beim Beenden), und der Transportweg
darf gar nicht erst eine Datei in einem gemeinsam genutzten Verzeichnis
erzeugen. Der Ansatz "unvorhersagbarer Dateiname" (Option c des Reviews)
ist damit endgueltig ungenuegend — er loest weder die Schreibbarkeit noch
die Persistenz.

**Sofortmassnahme fuer das Geraet** (Entscheidung des Nutzers, nicht
automatisch ausgefuehrt): die drei Reste loeschen. Es sind Artefakte
unserer eigenen deinstallierten App, kein Systembestandteil. **Wirksam
erst nach S-4 und S-2 aus AD-010/AD-012** — davor legt der naechste Lauf
sie wieder an.

### Nachtrag Director, 2026-09-01: Verzeichnisrechte gemessen

`stat /data/local/tmp` → **`drwxrwx--x shell:shell` (0771)**. Damit ist die
offene Annahme des Architekten (AD-010, Annahme 1) **bestaetigt** und die
Symlink-Frage geschlossen: Fremd-Apps koennen dort weder auflisten noch
Eintraege anlegen oder entlinken. Sie koennen aber **traversieren** (`--x`)
und eine Datei bei **exakt bekanntem Namen** oeffnen — und der Name ist
eine Konstante in einer sideloadbaren APK. SR-001 gilt damit unveraendert;
die Angriffsflaeche ist praezise begrenzt, nicht kleiner.

### SR-012 — am Geraet gemessen, 2026-09-01

```
/data/local/tmp/btperf   117 Dateien, 13 MB
block1.log               -rw-rw-rw-   (0666)
run.sh, snapshot.sh      -rwxr-xr-x
```

Das Verzeichnis stammt aus `docs/perf/tools/run.sh` und `snapshot.sh`
(`mkdir -p` unter umask 0), nicht aus Produktivcode. Es enthaelt
**vollstaendige `dumpsys bluetooth_manager`-Aufnahmen** — dieselbe
Datenklasse wie die Spill-Datei, nur 13 MB davon und dauerhaft. Es
ueberlebt die Deinstallation und wird von keinem Aufraeumpfad der App
erfasst (AD-011 loescht bewusst nur `btdash_exec_*.out`).

**Bewertung des Directors:** Gemessen an der Datenmenge ist SR-012 der
groessere Abfluss, gemessen an der Wiederholrate SR-001 — die Live-Ansicht
erneuert die Spill-Datei im Sekundentakt, `btperf` waechst nur bei
Messlaeufen. Beide sind `hoch`. Behebung von SR-012 ist ein `umask 077` am
Anfang der beiden Skripte plus einmaliges Loeschen; sie gehoert **nicht**
in T-006, weil sie kein Anwendungscode ist.

## Freigabestand T-006 (Review 2026-09-01, Entscheidungen Director)

**Transportentwurf AD-010..AD-013: freigabefaehig mit Auflagen.** Die
Entscheidung fuer den Deskriptor statt des Pfades ist bestaetigt — die
tragende Unmoeglichkeitsbehauptung haelt gegen alle vier geprueften
Plattformmechanismen (`chown`, POSIX-ACL, `chgrp`, App-Verzeichnis).
Nicht freigabefaehig ist die **Bauformenreihenfolge**.

- **Neue Bauform 3' vom Reviewer gefunden**, dem Architekten entgangen:
  Datei im **App-privaten** Verzeichnis, von der **App** angelegt und
  sofort entlinkt, Schreib-Deskriptor an den Helper. Kein Name, kein
  geteiltes Verzeichnis, blockiert nicht. Neue Reihenfolge fuer den
  Spike: **3' → 4 → 1 → 2 → 3**.
- **A4 begrenzt aufgehoben (Director, 2026-09-01):** `exec` darf an Ort
  und Stelle um den Deskriptor-Parameter erweitert werden, statt ein
  `execStream` danebenzustellen — Mischbetrieb ist am Code ausgeschlossen
  (Versionspruefung vor Token-Rotation, in `PrivilegedProvider.call()`
  belegt). Bedingungen **A6** (Test: genau eine `exec`-Methode, keine
  Ueberladung), **A7** (Signatur und Versionssprung in einem Commit),
  **A8** (Ablehnmeldung im Wortlaut unveraendert). **Fuer neue Methoden
  gilt A4 unveraendert weiter** — `wifiFacts` geht ans Ende.
- **Zwei Versionssprünge bestaetigt** (6 Transport, 7 `wifiFacts`) — vom
  Reviewer als die sicherere Variante beurteilt. Bedingung **A9**:
  Version 6 enthaelt AD-010 **und** AD-011 **und** AD-012; die
  Freigabemeldung nennt SR-001 und SR-009 einzeln und **SR-012
  ausdruecklich als offen**.
- **A10:** `umask 077` darf **nicht** vorgezogen ausgeliefert werden —
  zusammen mit dem heutigen `ExecSpill` macht es die Spill-Datei fuer die
  App unlesbar und faellt als Totalausfall der Live-Ansichten aus.
- **A5 faellt erst mit dem Geraete-Retest U-6**, nicht mit der
  Auslieferung von Version 6. Papier behebt keinen Befund.

### Auflagenliste A6–A16 (Schrittfolge U-0..U-6 in AD-014)

| ID | Inhalt | vor Schritt |
|---|---|---|
| A6 | Test: `IPrivilegedService` deklariert genau **eine** Methode `exec` — keine Ueberladung | U-1 |
| A7 | Signaturaenderung und Versionssprung in **einem** Commit; VERSION-KDoc nennt fuer 6 die geaenderte `exec`-Signatur | U-1 |
| A8 | Ablehnmeldung des Providers bleibt im Wortlaut (einzige Nutzerhandlung unveraendert) | U-3 |
| A9 | Version 6 traegt AD-010 **und** AD-011 **und** AD-012; Freigabemeldung nennt SR-001 und SR-009 einzeln und **SR-012 ausdruecklich als offen** | Freigabe v6 |
| A10 | `umask 077` nicht vorgezogen ausliefern (Begruendung korrigiert, siehe unten) | U-5 |
| A11 | Bauform **3'** in den Spike aufnehmen; Reihenfolge **3' → 4 → 1 → 2 → 3**; Bauform 3 nur mit 0600 beim Anlegen **und** unvorhersagbarem Namen | U-0 |
| A12 | Spike misst je Arm: `FD_CLOEXEC` mit laufendem `dumpsys`-Kind, Verhalten bei ungelesener Gegenseite ueber 64 KB, Deskriptor-Freigabe auf abgelehntem Aufruf | U-0 |
| A13 | `byteCount` bleibt Bestandteil der Binder-Antwort; Client verweigert bei Abweichung das Parsen | U-1 |
| A14 | Helper schreibt Nutzlast **nie** aus dem Binder-Thread; Schreib-Timeout; garantiertes `close()` auf jedem Pfad inkl. Ablehnpfaden; `null`-Deskriptor toleriert | U-2 |
| A15 | Test/Lint gegen jeden `set*(…, ownerOnly = false)`-Aufruf im Helper-Pfad | U-5 |
| A16 | Geraete-Retest durch den `security-reviewer`: `stat /data/local/tmp` nach einer Live-Sitzung, `ls -la` auf Reste, `/proc/<helper>/fd` nach 500 Aufrufen **inkl. Ablehnpfaden`**. Erst danach faellt A5 | U-6 |

### Entscheidung des Directors zum Widerspruch bei A10 (2026-09-01)

Der `architect` widerspricht der **Begruendung** von A10 und hat recht:
`ExecSpill.stage` ruft nach dem Anlegen `setReadable(true, false)` — das ist
`chmod a+r` und stellt die Lesbarkeit wieder her. Ein vorgezogenes
`umask 077` erzeugte also **0644 statt 0666** (Schreibhaelfte von SR-001
und SR-009 geschlossen, Lesehaelfte offen), **keinen Totalausfall der
Live-Ansichten**. Das ist derselbe Mechanismus, mit dem SR-017 zu Recht
begruendet wird; er kann nicht in einer Richtung wirken und in der
anderen nicht.

**A10 bleibt in Kraft**, mit neuer Begruendung: Version 6 soll genau
**einen** Zustand herstellen, keinen dritten halben. Ein Zwischenstand
0644, den niemand abnimmt, waere ein weiterer Befundtext statt
Fortschritt — und mit A9 ist die Reihenfolge ohnehin festgelegt.
Beiden Rollen mitgeteilt.

**Der `security-reviewer` hat den Fehler bestaetigt** (2026-09-01) und
zwei Verschaerfungen nachgereicht, die wertvoller sind als die Korrektur
selbst:

1. **Die korrigierte Begruendung ist sicherheitlich die staerkere.** 0644
   ist exakt die in AD-010 verworfene Option A: Schreibhaelfte zu,
   Lesehaelfte offen, der eigentliche Befund unberuehrt. Ein `stat`, das
   0644 zeigt, **sieht in einem Bericht aus wie eine Behebung und ist
   keine**.
2. **Folge fuer A16, seinen eigenen Retest:** Der Beleg fuer SR-001 ist
   **kein Dateimodus**. Weder 0600 noch 0644 sind ein Nachweis. Der
   Nachweis ist, dass nach einer Live-Sitzung **keine Datei dieser Form
   existiert** und der Transport ohne Dateisystem auskommt. A16 ist
   entsprechend zu fahren — er waere auf demselben Weg in dieselbe Falle
   gelaufen.

A15 ist von der Korrektur **gestuetzt**, nicht beruehrt: Die Zeile
`ExecSpill.kt:81` ist der Beleg dafuer, dass ein `ownerOnly = false`-Aufruf
jede umask-Zusage schweigend umdreht.

## Freigabestand T-005 (Entscheidung Director, 2026-08-31)

- **Teil 1 `wifiFacts` (AD-005, Helper v6): freigegeben mit Auflagen
  A1 (SR-002), A2 (SR-003), A3 (SR-008), A4 (SR-010), A5 (kein
  `dumpsys wifi`-Rueckfall solange SR-001 offen).** Umsetzung erst nach
  Nutzerentscheidungen zu T-005 und mit Toolchain/Geraet.
- **Teil 2 Settings-Leihe (AD-006/AD-007): NICHT freigegeben.** Erst
  SR-004 am Geraet klaeren; traegt die Annahme, sind SR-005/006/007 vor
  S-6 zu erfuellen. Restrisiko-Entscheidung (SR-005) liegt beim App
  Designer.

## Beobachtung ausserhalb des Auftrags (ungeprueft)

`AudioEffectSessionReceiver` in `:core-system` ist exportiert fuer
OPEN_/CLOSE_AUDIO_EFFECT_CONTROL_SESSION (vermutlich keine geschuetzten
Broadcasts; Komponente per Default deaktiviert). Eigenes Review noetig,
nicht Teil von T-005.
