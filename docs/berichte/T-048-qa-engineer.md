# T-048 — Geräteprüfliste Beobachtungslauf und Update (qa-engineer)

**Stand:** Zwischenstand vor der ersten Nutzerunterbrechung (23.09.2026, 18:12).

## Umgebung

- Gerät Pixel 11 Pro (`67011FDKX004XG`), Android 17, 1080×2410 px physisch,
  420 dpi (Faktor 2,625 px/dp). Kopfhörer Noble FoKus Prestige (`xx:xx:xx:xx:…`),
  LDAC 96 kHz/32 bit, Tidal spielt.
- adb nur `android-sdk\platform-tools\adb.exe`.
- Neuer Stand: Debug-APK aus dem Hauptbaum, HEAD `bb8fbe2` (Code gleich
  `2bdc0ec`/`6e7fa44`, `git diff --stat 2bdc0ec HEAD -- ':!docs'` leer).
- Alter Stand für Punkt 8: Debug-APK aus Klon `C:\t048` auf `9266f2e`
  (letzter Commit mit `monitor.db` Version 3; `v0.2.0` trägt Version 1,
  `git grep "version = [0-9]" v0.2.0`), versionCode 3 wie der neue Stand.
- Abweichung von der Prämisse: `settings get global wifi_on` = `1` (18:05),
  nicht aus.

## Urteil je Punkt

| # | Punkt | Urteil | Beleg |
|---|---|---|---|
| 1 | Lauf ≥ 2 min, drei Kennzahlen mit Fenster, Kadenz 1/5 s | OFFEN | — |
| 2 | Pause/Weiter, Stopp in der Pause | OFFEN | — |
| 3 | BT aus, Kopfhörer aus, Helfer beendet | OFFEN | — |
| 4 | Codec LDAC→AAC im Lauf, danach Start | OFFEN | — |
| 5 | HIGH→MID gepinnt; Sample-Rate im Lauf | OFFEN | — |
| 6 | Starthinweis Not now / Continue über Prozessneustart | OFFEN | — |
| 7 | Chipgrösse ≥ 48 dp | OFFEN | — |
| 8 | Update `monitor.db` v3→v4, Verlauf, Profile | OFFEN | — |
| 9 | Icons Navigationsleiste, Hilfeknöpfe | OFFEN | — |
| 10 | AK-4 ohne Monitor keine periodische Arbeit | OFFEN | — |
| 11 | Pause > 2 min beendet Lauf, kürzer läuft weiter | OFFEN | — |

## Befunde

(noch keine)
