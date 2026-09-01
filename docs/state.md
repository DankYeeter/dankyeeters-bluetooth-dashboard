# Stand — 2026-09-01 (Abend)

Kurzfassung fuer die Agenten. Historie in `HANDOVER.md`, Zielbild in
`GOAL.md`.

## Beantwortet: die offene Frage seit dem 30.08.

**„990 gepinnt → Warteschlangenueberlauf → Paketverluste → hoerbare
Aussetzer" ist BELEGT** (T-008, `docs/perf/T-008-experimente.md`).
A/B/A vollstaendig, App deinstalliert, Musik laufend:

| Arm | Zustand | Drops | Dropouts | Hoereindruck |
|---|---|---|---|---|
| A0 | ABR | 0 | 0 | keine Aussetzer |
| **B** | **990 gepinnt** | **525** | **21** (13/min) | **Aussetzer, durchgehend** |
| A' | ABR | 0 | 0 | keine Aussetzer mehr |

Nullwert **fuenffach** belegt (T-007 A/B/C, A0, A') ueber 514 s. Die
beiden 990er-Laeufe stimmen auf 0,4 % ueberein. Encoder arbeitet
fehlerfrei (`Packet counts expected/dropped: 179751/0`, Underflow 0) —
verworfen wird erst danach in der TxQueue, in Buendeln bis 26 Paketen.

**Der Regler bestaetigt uns autonom:** In A' probiert ABR bei t=11,3 s von
sich aus **990** und verlaesst die Stufe 1,4 s spaeter zwei Stufen tiefer.
Er kommt zum selben Urteil wie unsere erzwungene Messung.

**Nachwirkung der 12 min Ueberlast: keine dauerhafte.** 18,5 s
Einschwingen ueber alle vier Stufen, dann exakt das alte Muster;
eingeschwungenes Mittel 576,0 kbps trifft A0 (580,8) auf 0,8 %.

## Die zwei Kalibrierpunkte — wertvollstes Einzelergebnis

1. **~13 Dropouts/min sind deutlich hoerbar, 0/min sind unhoerbar.**
   Erster belegter Bezugspunkt fuer den Abschnitt „Die Hoerbarkeitsgrenze"
   in `UI_SPEC.md`. **Nicht** belegt: wo dazwischen die Schwelle liegt —
   zwei Punkte, keine Kurve. Eine Anzeige darf daraus nicht ableiten, ob
   z. B. 3/min hoerbar sind.
2. **`Counts (underflow)` blieb in ALLEN Armen 0 — auch im hoerbar
   kaputten.** Wer Aussetzer anzeigen will, muss
   **`Counts (dropped/dropouts)`** lesen. Eine Anzeige auf Underflow-Basis
   haette Arm B als einwandfrei gemeldet.

**Folge fuer T-002:** Die neun offenen UI_SPEC-Parameter beruhen auf der
falschen Leitgroesse. Im Normalbetrieb sind alle Verlustzaehler null und
der Verlust steckt in der **ABR-Stufe**; bei Ueberlast sind
**dropped/dropouts** die richtige Groesse. Nicht Underflow. Naechster
UI-Auftrag.

## Offen am Geraet — braucht die Hand des App Designers

- **Pin-Marker nicht zurueckgesetzt:** `Priority` steht auf 1000000 statt
  5001. Es sind zwei Entwickleroptionen; die *Wiedergabequalitaet* ist
  korrekt auf ABR zurueck, die *Codec-Auswahl* steht weiter auf „LDAC fest".
  Zurueck auf „System-Auswahl verwenden". **Ohne Wirkung auf die Messung**
  (Codec war in allen Armen LDAC), aber der Kopfhoerer laeuft bis dahin
  mit erzwungenem statt ausgehandeltem Codec.
- **E-1 (Nearby-Scans aus) und E-3 (Spatializer aus) haben keinen
  Shell-Hebel** — am Geraet vollstaendig geprueft. Nur von Hand.
- **Vierte Zelle ist die wertvollste offene Messung:** E-2 lief durchgehend
  **mit** drei aktiven ACTIVE-BLE-Scans (in allen Armen kontrolliert).
  „990 plus Scans aus" trennt „990 traegt grundsaetzlich nicht" von
  „990 traegt nicht, solange gescannt wird".

## Ausgeschlossen als Ursache (belegt)

WLAN (Radio war aus, Schaukel lief trotzdem), Energiespar-/Doze-/
Standby-Mechanismen (alles inaktiv bzw. EXEMPTED), CPU-Knappheit.
**Offen bleibt die Ursache des Queue-Ueberlaufs** — Funkstoerung,
Empfangsgrenze des Hoerers oder schlicht zu hohe Rate sind nicht getrennt.
Periodizitaet: **INCONCLUSIVE** fuer einen echten Takt (Ereignisse alle
2,97–7,68 s, Mittel 4,90; 2 von 23 Lags marginal), das Phaenomen selbst
ist bestaetigt. Die T-005-Wahrnehmung war richtig, nur die unterstellte
Regelmaessigkeit nicht.

## T-006 — Entwurf fertig, geprueft, freigegeben mit Auflagen

`ARCHITECTURE.md` AD-010..AD-014, Befunde SR-013..SR-022 und Auflagen
A6–A16 in `security/findings.md`, Schrittfolge **U-0..U-6**.

- **Transport: Deskriptor statt Pfad**, ohne Groessenschwelle. Tragender
  Grund: Jede Uebergabe ueber einen Pfad ist zwingend weltlesbar (vier
  Plattformmechanismen geprueft).
- **Bauform 3' vom Reviewer gefunden** (App-privates Verzeichnis, App legt
  an und entlinkt, fd an den Helper). Reihenfolge **3' → 4 → 1 → 2 → 3**.
- **Zwei schwere Funde am Erstentwurf:** Bauform 3 waere SR-001 mit
  kuerzerem Fenster (Entlinken wirkt nicht rueckwirkend); die
  Pipe-Bauformen koennten den unsterblichen Helper dauerhaft verklemmen.
- **SR-016:** Der Helper ueberlebt die Deinstallation und pollt unbegrenzt
  weiter → Abbruchbedingung auf **Paket-Existenz** umstellen, dann selbst
  aufraeumen. R1 war falsch.
- **Entscheidung App Designer 01.09.:** Die App bekommt eine sichtbare
  Aktion **„Helper beenden und aufraeumen"** — als Absicherung fuer den
  Restfall, nicht als Hauptweg, und sie meldet ehrlich, was sie erreicht
  hat.
- **A10-Widerspruch entschieden** (Auflage bleibt, Begruendung korrigiert);
  beide Rollen informiert. Folge: **A16 darf nicht am Dateimodus haengen**,
  sondern daran, dass keine Datei dieser Form mehr existiert.

## Laufende Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-001 | performance-tuner | Vergleichslauf gegen Block 1 | offen; **vor** dem Transport-Messlauf (U-6/S-6), sonst konfundiert |
| T-002 | ui-ux-designer | UI_SPEC | **Neuauftrag faellig** — beide Kalibrierpunkte einarbeiten |
| T-005 | architect | Scan-Entwurf | geliefert; wartet auf Nutzerentscheidungen 1–6 |
| T-006 | architect → developer | Transport SR-001/SR-009 | Entwurf **abgenommen**; Umsetzung braucht Geraet + Toolchain (U-0) |
| T-007 | researcher + tuner | Deep-Dive | geliefert |
| T-008 | performance-tuner | Eingriffsexperimente | **E-2 fertig**; E-1/E-3 + vierte Zelle offen |
| SR-012 | performance-tuner | `umask 077` in `docs/perf/tools/*.sh` + Reste loeschen | zurueckgestellt bis Ende der Messreihe |

## Rahmen

Zweitrechner: **kein JDK, kein SDK, kein Gradle** — Tests hier nicht
lauffaehig, jede Umsetzung blockiert. adb nur ueber ein Fremdprodukt
(`C:\RSL\2.1HF5\adb\adb.exe`). Geraet per Kabel erreichbar.
**Methodischer Vorbehalt:** USB-3 strahlt ins 2,4-GHz-Band; alle Messungen
liefen am Kabel. Kontrollmessung ueber drahtloses adb steht aus.

## Offen / zurueckgestellt

- Aufnahme gegen R-001..R-004 abgleichen (der tuner hat sie nie gelesen).
- Widerspruch R-001 vs. Messung: 492 kbps ist gemessen, gilt dort aber
  nicht als Nominalstufe. Leiter fuer 96 kHz/32 bit unverstanden.
- 48→96-kHz-Hochrechnung durch den Spatializer-Thread (E-3).
- `AudioEffectSessionReceiver` exportiert — eigenes Review ausstehend.
- `NUL`-Datei im Repo-Root; Emulator (Nutzer, 31.08. zurueckgestellt).
