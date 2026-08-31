# Sicherheitsbefunde

Fortgeschrieben, nie ueberschrieben. Behobene Befunde bleiben mit Status
"behoben" stehen. Angelegt 2026-08-31 nach dem Papier-Review von T-005
(Entwurfs-Review, kein Code — vollstaendige Befundtexte im damaligen
Review-Bericht, Kurzfassungen unten).

Status: offen | in Arbeit | behoben | akzeptiert (Restrisiko, mit Datum
und Entscheider) | entfallen (Grund)

| ID | Titel | Prioritaet | Status | Letzte Pruefung |
|---|---|---|---|---|
| SR-001 | Gestagte Dumps 0644 in `/data/local/tmp` — Geraetenamen und BT-Adressen fuer jede App lesbar | hoch | offen (eigener Task T-006) | 2026-08-31 |
| SR-002 | `wifiFacts`: SSID/BSSID-Zusage nur Konvention, Fehlerpfad reicht Rohtext durch | hoch | offen (Auflage A1 vor S-3) | 2026-08-31 |
| SR-003 | `wifiFacts`-Parser verarbeitet vom AP kontrollierten Text im Shell-Prozess | hoch | offen (Auflage A2 vor S-3) | 2026-08-31 |
| SR-004 | Settings-Leihe ohne belegte Wirkung — Risiko ohne gesicherten Ertrag | hoch | offen (Geraetefrage vor S-6) | 2026-08-31 |
| SR-005 | Rueckgabe der geliehenen Schalter faellt bei Deinstallation, Permission-Entzug oder ausbleibendem App-Start dauerhaft aus | hoch | offen | 2026-08-31 |
| SR-006 | Ledger in `datastore/` reist per Cloud-Sicherung und Geraeteuebertragung | mittel | offen | 2026-08-31 |
| SR-007 | Ledger: Doppelleihe macht Leihwert zum Nutzerwert; Rueckgabe ohne Vergleiche-und-Setze ueberschreibt Nutzerentscheidung | mittel | offen | 2026-08-31 |
| SR-008 | Kein Test erzwingt parameterlose fest verdrahtete Vektoren (`wifiFacts`) | mittel | offen (Auflage A3 vor S-3) | 2026-08-31 |
| SR-009 | Helper-Log als Nebenausgang fuer WLAN-Daten; Log-Injection ueber SSID | niedrig | offen (Dateimodus am Geraet zu pruefen) | 2026-08-31 |
| SR-010 | Versionssprung 5 → 6: Methode ans AIDL-Ende, KDoc fortschreiben | niedrig | offen (Auflage A4 vor S-3) | 2026-08-31 |
| SR-011 | Ungesalzener MAC-Hash als `deviceKey` im Bericht | niedrig | offen | 2026-08-31 |

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
