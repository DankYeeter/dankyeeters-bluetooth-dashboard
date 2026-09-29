# T-071 — Ponytail-Bereinigung (developer)

Branch `t071`, Basis `eba2636`, Stand `bbcffb3` (13 Commits).
Suite: 2704 Tests, 0 Fehler, 0 uebersprungen (`gradlew test --parallel`, 22:00:38–22:02:13;
core-audio/-hearing/-system UP-TO-DATE seit dem Lauf 21:56, ohne Eingangsaenderung danach).
Differenz zu 2750: 23 geloeschte Testmethoden x 2 Varianten (debug/release) = 46.
`git diff --shortstat 22af2e2..HEAD`: 43 Dateien, +195 / -1454 (darin +85 Auftragsdatei);
Code gegen `eba2636`: 42 Dateien, +110 / -1454, net -1344.

## Ausgelassen, weil der Bestand widerspricht

1. **`LdacStackState.adaptiveBitrateAdjustments`** (Punkt 2) — erst geloescht, dann in
   `bbcffb3` wiederhergestellt. Geplanter Leser: `UI_SPEC.md:1609` AK-T009-41
   ("enthaelt ungesehene Wechsel"); `docs/product/BACKLOG.md:234-236` haelt fest, dass das
   Feld vom Streichen des Index unberuehrt bleibt. AK-T009-41 hat im Code keinen Leser —
   offen, ob umgesetzt oder aufgegeben (Entscheidung App Designer/director).
2. **`ComparisonResult.Compared.verification`** (Punkt 12) — nicht angefasst.
   `ARCHITECTURE.md:2163-2166` (AD-035, Nachtrag F-017) und `UI_SPEC.md:3679,3689`
   nennen `result.verification` als Quelle der Rueckleseergebnis-Zeile. Die UI liest aber
   `readBackSentence(result.measure, result.after.start)` (`ComparisonSection.kt:237`),
   also das Buch neu — Abweichung von der Spezifikation, gleicher Wert (an qa/director).

## Veraltete Stellen in fremden Dateien (nicht geaendert)

- `ARCHITECTURE.md:60` `SystemPropertyReader`; `:1503` `MonitorGraph.liveLinkEvents`;
  `:1733` AD-028 "`CodecModeSignature`/`LdacModeSignatures` ... bleiben".
- `UI_SPEC.md:71,420` `framesPerEnqueue`; `:135` `enqueueOverdue`/`dequeueOverdue`.
- `qa/findings.md:204` "`framesPerPacketMax` wird dort persistiert" — Feld existiert nicht
  mehr; QA-014 (max/ave vertauschbar) ist damit gegenstandslos.
- Suche: `grep -rnE` ueber `*.md,*.kt,*.kts,*.toml,*.xml,*.pro` mit allen entfernten Namen,
  ohne `docs/archiv|berichte|tasks`; Treffer ausser den obigen nur Compose-Importe
  `androidx.compose.ui.graphics.*` (Paket, nicht die Abhaengigkeit) und die lokale
  Variable `rawModelId` im Parser.

## Neue Funde (nicht umgesetzt)

- `LiveCodecSnapshot.rawCodecName` hat nach Punkt 1 in `main` keinen Leser mehr, nur den
  Setzer `A2dpLinkDumpParser.kt:244` (`grep -rn rawCodecName */src/main`, 22:03, `bbcffb3`:
  nur Setzer und Deklaration).

## Geloeschte Tests (23)

- `CodecModeInferenceTest` (ganze Klasse, 15).
- `LiveLinkParserTest`: `max and ave frames per packet are not interchangeable`.
- `AppleProximityBeaconParserTest`: `model id is read big-endian across the byte boundary`,
  `in-ear flags are reported per side`, `both buds in ear are reported together`,
  `in-case beats a stale in-ear bit`, `lid closed bit is honoured`,
  `lid open counter is the low three bits`, `colour byte maps to the housing colour`.
- Die ABR-Tests (Parser 4, Probe 1, Golden 1) stehen wieder, nur ohne Index; drei Titel
  entsprechend angepasst.
