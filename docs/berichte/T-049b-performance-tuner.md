# T-049b (T-036) — Trennmessung 990 gepinnt, WLAN 2,4 GHz mit/ohne — Bericht

Werkzeug-Budget dieses Laufs erschöpft (151/150 Aufrufe) während der
abschliessenden Aufräum-Verifikation nach Abschluss der eigentlichen
Messung. Die Messung selbst ist **fertig, ausgewertet, dokumentiert und
committet** — nur der letzte Geräte-Sanity-Check (ps/wifi/tidal ganz am
Ende) konnte nicht mehr ausgeführt werden.

## STATUS: teilweise

Grund für „teilweise", nicht „erledigt": Arm „ohne" (30,25 min, sauber)
und die Kernfrage (T-029/T-032-Widerspruch) sind vollständig gelöst; Arm
„mit" erreichte die geforderten durchgehenden 30 min bei 990 gepinnt
**nicht** (zwei echte A2DP-Reconnects beendeten je einen Versuch), und die
AK-T009-24-Doppelaufnahme konnte aus den Daten nicht hergestellt werden.
Beides ist im Bericht als offen benannt, nicht verschwiegen.

## Was fertig ist

- **`docs/perf/T-036-trennmessung.md`** (neu, committet) — vollständiger
  Bericht: Zustandsbuch je Abschnitt, Pin-Verfahren (App-UI + privilegierter
  Helfer, kein Root), zwei Arm-„mit"-Läufe mit BQR-Belegen für je einen
  Reconnect (87 %/94 % Wiederholrate vor Abbruch), ein vollständig sauberer
  30,25-min-Arm „ohne" (0 Verluste, 0 BQR-Ereignisse, 0 Reconnects), Auflösung
  T-029 vs. T-032, Fund zum Director (Pin übersteht Reconnect nicht, wenn App
  laut F-012 beendet ist), Nachweisgrenzen.
- **`docs/perf/baselines.md`** (erweitert, committet) — neuer Abschnitt mit
  Budget-Zeile (Arm ohne, 0/0/0 über 30,25 min) und den beiden „mit"-Zeilen
  ohne Budget-Markierung.
- **`docs/perf/tools/t032_run.sh`** — CRLF→LF normalisiert (Werkzeug lief auf
  dem Gerät sonst gar nicht, Pfad-Korruption per `sh -x` belegt). Inhalt
  entspricht bereits dem in `HEAD` gespeicherten Blob (git speichert schon
  LF), daher **kein Diff zu committen** — die Korrektur betrifft nur den
  Checkout.
- Commit `6dc0064` (`docs(perf): T-036 Trennmessung 990 gepinnt, WLAN
  2,4 GHz mit/ohne`), Pfade `docs/perf/T-036-trennmessung.md` und
  `docs/perf/baselines.md` — beide gemäss Auftragsvorgabe „nur eigene
  Dateien unter docs/perf/".
- Gerät am Ende der Messung (22:04:06 CEST, letzter belegter Stand vor dem
  Budget-Stopp): WLAN wieder „enabled … connected to SSID_A" (Vorwert
  wiederhergestellt), `ps` für `btdash`/`btdash_privileged` leer, Tidal PID
  11069 unverändert seit T-048 durchgehend.

## Was offen/unverifiziert ist

- **Letzter Sanity-Check nach 22:04:06** (`ps`/`wifi status`/Tidal ganz zum
  Schluss) lief nicht mehr — der Stand direkt davor ist belegt (siehe oben),
  seither wurde am Gerät nichts mehr ausgelöst, ein Restrisiko besteht nur,
  wenn zwischen 22:04 und jetzt ein weiteres spontanes BT-Reconnect/Launcher-
  Ereignis auftrat (wie es zweimal während der Messung beobachtet wurde).
  Ein kurzer `ps -A | grep btdash` und `cmd wifi status` vor dem nächsten
  Geräte-Zugriff genügt zur Bestätigung.
- **`.gitattributes`** (`docs/perf/tools/*.sh text eol=lf`) liegt im
  Arbeitsbaum, **uncommittet** — bewusst, da ausserhalb `docs/perf/` und
  damit ausserhalb der Commit-Erlaubnis dieses Auftrags. Ohne sie zerschiesst
  ein künftiger Checkout auf Windows `t032_run.sh` erneut (Fund im Bericich
  dokumentiert). Braucht eine eigene Freigabe/Commit durch Director oder
  `archivist`.
- **T-049c (Callback-Probe T-037)** aus `docs/tasks/T-049.md` ist **nicht
  begonnen** — eigener, folgender Abschnitt laut Auftrag.
- `docs/state.md` (Zeile „T-036 … naechster Schritt, braucht Geraet") ist
  **nicht aktualisiert** — Auftrag beschränkt Commits auf `docs/perf/`,
  Aktualisierung ist Sache des Directors beim Weitergeben.
- Kein Testlauf (`gradlew test`) in dieser Rolle nötig oder ausgeführt —
  reine Geräte-Messung, kein Code geändert.

## Nächster Schritt

1. Director: kurzen Geräte-Check (`ps`, `wifi status`) nachholen, dann
   `.gitattributes` separat freigeben/committen.
2. Entscheiden, ob ein dritter Versuch für einen durchgehenden 30-min-
   Arm „mit" lohnt (Reconnect-Ursache liegt am Funklink, nicht am Verfahren —
   keine Erfolgsgarantie) oder ob die vorhandenen 26,4 min (zwei Teilfenster)
   als ausreichend gelten.
3. Danach T-049c (Callback-Probe T-037) als eigener, neuer Lauf beauftragen.

Pfade: `docs/perf/T-036-trennmessung.md`,
`docs/perf/baselines.md`, `docs/perf/tools/t032_run.sh`,
`C:\Users\Daniel\Desktop\ClaudeCode\dankyeeters-bluetooth-dashboard\.gitattributes`
(uncommittet). Rohdaten (Serien-Logs, BQR-Snapshots, `logcat`, UI-Dumps):
`C:\Users\Daniel\AppData\Local\Temp\claude\C--Users-Daniel-Desktop-ClaudeCode-dankyeeters-bluetooth-dashboard\bf6dde3c-5d1c-49a9-870a-e89791c11d57\scratchpad\T-049b\`
(session-gebundener Scratchpad, nicht dauerhaft — vor Aufräumen sichern
lassen, wer sie braucht).
