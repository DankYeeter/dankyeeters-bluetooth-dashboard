# T-046 — Bericht developer (Befunde)

Stand 23.09.2026, Branch `auto/2026-09-23-saeule3`, Commits `21d138d`,
`5286083`, `723603d`, `fbd860d`. Volllauf im Worktree: 2480/0.

## B-1 Prämisse zu kurz: mehr Nutzer des Geräte-Tests als angenommen

Die Prämisse nannte drei Dateien (`grep -rln DeviceDiagnostic`). Die Typen des
Pakets `monitor.diagnostic` wurden auch hier genutzt, und alles davon diente nur
dem Geräte-Test. Deshalb ist es mitentfernt:
- `MacRedactionInvariantTest`: der Test zum Bericht in der Zwischenablage;
- `MonitorScreenAuditTest`: der Test zur Karte;
- `CodecController.selectCodec` samt `NoOpCodecController`,
  `PrivilegedCodecController` und `FakeCodecController`: das war der einzige
  Aufrufer;
- Kommentare, die den Test als Nutzer nannten: `BtDashboardApplication`,
  `PrivilegedClient`, `DeviceProfilesScreen`, `CodecStatusSource`,
  `MonitorRepository`, `SamplingPolicy`;
- die Zeile in der README.

Suche nach der Entfernung (`diagnostic|device test|selectCodec|FakeCodecController`
im Code, ohne die Ereignis-Diagnosen des Event-Logs): leer.

## B-2 ARCHITECTURE.md nennt den entfernten Runner (an architect)

`ARCHITECTURE.md:35` (`diagnostic/`), `:106`, `:142` und `:733`
(`DeviceDiagnosticRunner` als Vorbild oder Bauform). Das Paket gibt es nicht mehr.
`docs/scan/T-005-ENTWURF.md:220` nennt ihn ebenfalls, ist aber ein Entwurf.

## B-3 Spec und Design-Review verweisen auf umbenannte Tests (an ui-ux-designer)

Die Pausenregel machte zwei Zusagen falsch, deshalb sind die Tests umbenannt:
- `ObservationRunTest."a ten minute pause is a gap, not observed time"` heißt
  jetzt `"a pause of exactly the gap maximum is a gap, not observed time"`;
- `"each of the six ends is recognised and freezes the figures"` heißt jetzt
  `"each end is recognised and freezes the figures"`;
- `ObservationRunSectionTest."a ten minute pause shows as a break…"` heißt
  jetzt `"a two minute pause…"`.

Die alten Namen oder Aussagen stehen noch in:
- `UI_SPEC.md:2778` und `:2784`: der Absatz T-043c sagt, eine
  zehnminütige Pause beende den Lauf nicht. Das ist seit T-046 falsch.
- `UI_SPEC.md` AK-T039-3: „10-min-Lücke". Eine Pause dieser Länge beendet den
  Lauf jetzt. Die Tests nutzen eine Pause von 2 min innerhalb der Grenze.
- `UI_SPEC.md` AK-T039-9: „sechs Abbruchgründe". Es sind jetzt sieben.
- `DESIGN_REVIEW.md:45` und `:85`: die alten Testnamen.

## B-4 Klon-Volllauf nicht möglich

Zwei Versuche, beide in `<Scratchpad>/T-046/klon.txt` dokumentiert:
1. `git clone` ins Scratchpad scheitert an „Filename too long". Mit
   `core.longpaths` klappt das Klonen, dann scheitert aber
   `:app:compileDebugAidl`, weil `aidl.exe` die langen Pfade nicht verträgt.
2. Über `subst T:` scheitert derselbe Schritt, weil Gradle auf den langen Pfad
   auflöst.

Danach habe ich abgebrochen. Laufwerk und Klon sind entfernt. `subst` ist
leer, das ist geprüft. Wie bei T-040b lief der Volllauf deshalb im Worktree:
16:55–16:56, HEAD `fbd860d`, 2480/0, Ausgabe `<Scratchpad>/T-046/worktree.txt`.
Uncommittet lagen dort nur `ARCHITECTURE.md` und `docs/state.md` anderer
Rollen, beide ohne Einfluss auf Tests. Ein Klon-Lauf braucht einen kurzen
Pfad außerhalb des Scratchpads, zum Beispiel `C:\k\`. Das muss der director
freigeben.
