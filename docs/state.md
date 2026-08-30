# Stand — 2026-08-30

Kurzfassung fuer die Agenten. Die lange Historie steht in `HANDOVER.md`,
das Zielbild in `GOAL.md`.

## Wo wir stehen

- Branch `backup/wip-20260822`, HEAD **babe3d8**, Arbeitsbaum sauber,
  synchron mit `origin` (sync-in 2026-08-30). `master` liegt 87 Commits
  zurueck und ist irrelevant.
- Auf dem Pixel 11 Pro installiert ist **versionName 0.3.0, Stand
  28.08. 18:25** — also ein **aelterer** Build als HEAD. Insbesondere
  fehlen 7085ef7/672ec9a (Binder-Transport per Datei-Spill), die genau im
  beanstandeten Bereich liegen. Jede Messung braucht vorher einen frischen
  Build von HEAD.
- Android-16-Abnahme bestanden (28.8.), Pixel 8 Pro abgenommen.

## Aktuelles Thema: Messen darf den Klang nicht kosten

Daniel meldet 2026-08-30: das Abtasten der Bluetooth-Verbindung drosselt
die Leistung und beeinflusst die Klangqualitaet spuerbar — **auch wenn kein
Live-Panel offen ist**, die Eingrenzung ist ihm unklar.

Aktenkundige Kosten aus den Doc-Kommentaren im Code:

| Pfad | Kadenz | Kosten pro Pass | Duty-Cycle |
|---|---|---|---|
| `LiveLinkSource.updates` | 2 s | 3 dumpsys, ~550 ms | ~25 % |
| `A2dpTxProbe.samples` | 500 ms | 1 dumpsys, ~233 ms | ~47 % |
| `MonitorEngine` (ACTIVE) | 30 s | 1 Sample-Lauf | gering |

Arbeitshypothese des Directors, **noch unbewiesen**: nicht die CPU-Last
schadet, sondern die Lock-Haltezeit im `btif_a2dp_source`-Dump, der
denselben Stack anhaelt, der gerade LDAC encodiert.

## Entscheidungen des Nutzers, 2026-08-30

- **Detail bleibt erhalten**, dafuer darf der Datenweg grundlegend umgebaut
  werden (Push statt Poll, BQR, Parsing im Helper). Mehrere Zyklen erlaubt.
- **Der Helper darf voll erweitert werden**, inkl. neuer Kommandos —
  `security-reviewer` prueft.
- **Erst messen, dann umbauen.**

## Befund 30.08. abends — am Geraet belegt

**990 kbps traegt auf dieser Strecke nicht.** Bei gepinnt 990 stockt die
Wiedergabe periodisch (~3 s Takt). Der Takt bleibt **auch bei
force-gestoppter App** — kein Poller, kein dumpsys, keine EQ-Kette.
Read-back mit gestoppter App: `LDAC quality mode: HIGH`,
`transmission bitrate: 990 Kbps` — der Pin hat den Force-Stop ueberlebt,
die Beobachtung gilt also wirklich fuer 990.

Konsequenzen:
- Die Director-Hypothese (unser Polling stoert den Encoder) ist **fuer
  diesen Fall widerlegt**. Sie ist damit NICHT allgemein erledigt: die
  urspruengliche Meldung — Abtasten kostet generell Leistung, auch im
  Hintergrund — ist ein anderes Phaenomen und weiter offen (T-001).
- Die Periodizitaet spricht fuer einen Grenzzyklus: geforderter Durchsatz
  ueber dem, was die Strecke traegt, Sendeschlange laeuft im festen
  Rhythmus leer. Erklaert auch das ABR-Pendeln bei ~420 kbps am Hbf.
- **Der Optimize-Lauf (T-003) ist damit belegter Bedarf**, nicht nur
  Wunsch: es existiert nachweislich eine Stufe, die nicht traegt, und sie
  ist nur durch minutenlanges Hinhoeren erkennbar.
- Korrigierte Erfahrungswerte des App Designers: 660 traegt meistens,
  **990 ist die kritische Schwelle**, die selten klappt.

## Entscheidungen des App Designers, 30.08. spaet

- Kein "Aussetzer gehoert"-Knopf in der App (zu viel Alltagsaufwand).
  Schwellen muessen allein aus Messwerten ableitbar sein.
- Kopfhoerer-Modding: gewuenscht als Idee, aber **zurueckgestellt**
  ("muss nicht jetzt sein") — siehe docs/tasks/T-004.md.

## Laufende Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-001 | performance-tuner | Inventar + Messreihe + blinder Hoertest am Pixel 11 | in Arbeit |
| T-002 | ui-ux-designer | Spec Verlustanzeige (UI_SPEC.md) | Nacharbeit laeuft |
| T-003 | architect | Optimize-Entwurf + Codec-Machbarkeit | in Arbeit |
| T-004 | — | Kopfhoerer-Modding | zurueckgestellt |

## Offen / zurueckgestellt

- Frischer Hoertest (Daniels Ohren) fuer die echte Kalibrier-Ableitung.
- Mini-Entscheidung: BackupCodec verwirft aktive Preset-Auswahl beim
  Import (Kurve reist mit).
- Kosmetik: eine Datei `NUL` liegt im Repo-Wurzelverzeichnis
  (Windows-Artefakt).

## PAUSE 30.08. abends — Stand bei Wiederaufnahme

**Nichts ist geloest.** Das Flackern im Audio besteht unveraendert fort.
Genau zwei Dinge sind gesichert:

1. **990 kbps traegt auf dieser Strecke nicht** — periodisches Stocken
   (~3 s), auch bei force-gestoppter App, Pin per Read-back bestaetigt.
2. **Die Director-Hypothese ist fuer diesen Fall widerlegt**: unser
   Polling erzeugt das 990-Stocken nicht. Fuer das urspruengliche,
   allgemeine Phaenomen (Abtasten kostet Leistung, auch im Hintergrund)
   ist sie **weiterhin offen und ungeprueft**.

### Was fertig ist

- `GOAL.md` (Zielbild, AK-1 bis AK-6), `docs/state.md`, T-001 bis T-005.
- **`UI_SPEC.md` fertig** (641 Zeilen, 23 Akzeptanzkriterien). Kernstueck:
  Abschnitt "Die Hoerbarkeitsgrenze" — kein Zustandstext darf eine
  Aussage ueber den Klang machen, nur ueber die Zaehler; `CLEAN` heisst
  jetzt "No counter moved in the last {W}" statt "Nothing lost".
  Neun von zehn Parametern warten auf Messwerte aus T-001.

### Was abgebrochen wurde (beide Agenten gestoppt, Kontext erhalten)

- **T-001** (`performance-tuner`): Inventar und Teile der Messreihe
  vorhanden, M-1 lief fuer die Mixer-Kanaele, die Per-Track-Spalte war
  noch fehlerhaft. Der blinde Hoertest wurde nie gefahren.
  Wiederaufnahme per Nachricht an denselben Agenten, nicht neu starten.
- **T-003** (`architect`): war beim Schreiben des Entwurfs, als gestoppt
  wurde. Ergebnis liegt nicht vor.

### Was als Erstes ansteht

1. **T-005 statt T-003.** Der App Designer hat den Treppen-Optimierer
   zurueckgestellt und will stattdessen den **Umgebungs- und
   Einstellungs-Scan** (docs/tasks/T-005.md). Der `architect` ist darauf
   noch nicht umgeleitet worden — das ist der erste Auftrag.
2. **Erste Spur fuer den Scan, unbewiesen:** WLAN faehrt Wi-Fi 7 mit MLO,
   affiliierte Links auf 2,4 GHz (Kanal 6) **und** 5 GHz (Kanal 100).
   Um 18:57 war das Geraet auf 2437 MHz, um ~19:50 auf 5500 MHz. Welches
   Band beim Stocken um 19:41 aktiv war, ist **nicht** belegt. Ausserdem
   `wifi_scan_always_enabled=1` und `ble_scan_always_enabled=1`
   (periodische 2,4-GHz-Suchlaeufe) und 3 gekoppelte Geraete, Anzahl
   gleichzeitig verbundener nicht ausgelesen. **Die Periodizitaet des
   Stockens passt zu einem periodischen Stoerer — das ist die
   aussichtsreichste offene Spur.**
3. T-001 fortsetzen, danach die neun offenen UI_SPEC-Parameter setzen.
