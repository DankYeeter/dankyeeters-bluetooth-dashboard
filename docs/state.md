# Stand — 2026-08-31

Kurzfassung fuer die Agenten. Historie in `HANDOVER.md`, Zielbild in
`GOAL.md`.

## Wo wir stehen

- **Git:** Arbeitsbranch ist wieder `master`, HEAD enthaelt den frueheren
  `backup/wip-20260822` vollstaendig (dessen Remote-Ast ist damit
  obsolet). Arbeitsbaum sauber, synchron mit origin.
- **Session 31.08. laeuft auf dem Zweitrechner: kein JDK, kein
  Android-SDK, kein adb, kein Geraet.** Nur Analyse-/Entwurfs-/Doku-Arbeit
  moeglich. Emulator waere hier technisch moeglich (AMD SVM aktiv, AEHD),
  Entscheidung des Nutzers: zurueckgestellt, bis Mehrwert erkennbar.
  Die GOAL.md-Aussage "kein Hypervisor" gilt so pauschal nicht mehr.
- Auf dem Pixel 11 Pro installiert: versionName 0.3.0 vom 28.08. —
  aelter als HEAD. Vor jeder Messung frisch von HEAD bauen.
- Audio-Flackern weiterhin ungeloest. Gesichert: 990 kbps traegt nicht
  (~3-s-Stocken, auch bei force-gestoppter App); unser Polling als
  Ursache dafuer widerlegt.

## T-005 geliefert (31.08.): Umgebungs- und Einstellungs-Scan, Entwurf

- Entwurf: `docs/scan/T-005-ENTWURF.md`; Entscheidungen AD-002..AD-009 in
  `ARCHITECTURE.md` (neu angelegt).
- Kernpunkte: Periodizitaet beweist keinen Stoerer — **E-0** (A/B/A
  990 vs. 660, Zeitreihe Schlangenlaenge/Verlustzaehler) trennt
  Ueberlastung von Stoerer, ohne neue Zugriffe. Zwei Phasen
  (Bestandsaufnahme ~2 s / Belege einzeln freigegeben). Evidenz als Typ
  (AD-004). **Optimizer verschmilzt in den Scan**, kein eigener Knopf.
  48 kHz entlastet den Funk NICHT (gleiche Bitratenleiter).
- Eine Helper-Erweiterung noetig: `wifiFacts` (lesend, v6, SSID/BSSID
  verlassen den Helper nie).

## Sicherheitsreview T-005 (31.08.) — `security/findings.md` angelegt

- **`wifiFacts`: freigegeben mit Auflagen A1–A5** (kein Freitextfeld,
  verankerte Parser-Muster, parameterlos, AIDL-Ende, kein
  `dumpsys wifi`-Rueckfall).
- **Settings-Leihe (AD-006/AD-007): NICHT freigegeben** — erst SR-004 am
  Geraet klaeren (wirken die Schalter bei eingeschaltetem Radio
  ueberhaupt?). Traegt die Annahme, vorher SR-005/006/007 erfuellen.
- **SR-001 (Bestand, hoch): Spill-Datei 0644 in /data/local/tmp** — jede
  App liest Geraetenamen/BT-MACs mit. Eigener Task **T-006**.

## Entscheidungen des App Designers — OFFEN, vor Umsetzungsbeginn

1. Settings-Leihe grundsaetzlich erlauben (inkl. Restrisiko SR-005:
   Deinstallation im geliehenen Zustand laesst 2 Ortungsschalter aus)?
   Vorgeschaltet: SR-004-Vorpruefung am Geraet.
2. Dauer eines Belegteils: ~10–12 min Musik fuer 3 Experimente ok, oder
   Deckel (mehr INCONCLUSIVE)?
3. Testweise 48 kHz akzeptabel (nur Qualitaets-, kein Stabilitaetshebel)?
4. Darf der Scan empfehlen ("660 pinnen") oder nur Zahlen zeigen?
5. Bestaetigung: kein eigener Optimize-Knopf.
6. Berichte persistieren (Room-Migration, schwer umkehrbar) oder
   fluechtig?

## Laufende Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-001 | performance-tuner | Messreihe/Hoertest Pixel 11 | unterbrochen; Block 1 in `baselines.md` ist FERTIG und signifikant (dumpsys kostet keine Verlustmetrik, auch bei 0,5 s) — bei Wiederaufnahme NICHT bei Null anfangen |
| T-002 | ui-ux-designer | UI_SPEC Verlustanzeige | fertig; 9 Parameter warten auf T-001-Messwerte |
| T-004 | — | Kopfhoerer-Modding | zurueckgestellt |
| T-005 | architect | Scan-Entwurf | **geliefert**; Umsetzung wartet auf Entscheidungen 1–6 + Toolchain/Geraet |
| T-006 | architect→developer | SR-001 Spill-Datei weltlesbar | offen, hoch; braucht Entwicklungsrechner |

## Offen / zurueckgestellt

- SR-004-Vorpruefung und SR-009-stat am Geraet (performance-tuner, wenn
  Pixel wieder verfuegbar).
- `AudioEffectSessionReceiver` exportiert — eigenes Review ausstehend.
- Frischer Hoertest (Daniels Ohren) fuer Kalibrier-Ableitung.
- BackupCodec verwirft aktive Preset-Auswahl beim Import.
- `NUL`-Datei im Repo-Root (Windows-Artefakt) entfernen.
- Emulator-Umgebung auf dem Zweitrechner: zurueckgestellt (Nutzer,
  31.08.).
