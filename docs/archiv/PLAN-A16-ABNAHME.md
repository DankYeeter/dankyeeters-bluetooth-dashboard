# Android-16-Abnahme (Pixel 8 Pro) — letzter Durchlauf vor der Weggabe

Das Pixel 8 Pro ist das einzige Android-16-Geraet. Nach der Weggabe ist
kein A16-Test mehr moeglich — dieser Plan ist die finale Abnahme, nicht
eine von vielen. Dauer realistisch: 45–60 Minuten am Stueck, Kopfhoerer
(Noble oder Bathys) plus USB-Kabel noetig.

## Vorbereitung (5 min)

1. Frisches `app-debug.apk` vom finalen Stand bauen und per Kabel
   installieren (`adb install -r`). Kein alter Stand — die Abnahme gilt
   fuer das, was danach weiterlebt.
2. `adb logcat -c`, damit Absturzspuren eindeutig dieser Sitzung gehoeren.

## Block A — Helper-Lebenszyklus (der A16-kritischste Teil, 15 min)

Der Helper nutzt @hide-Reflection und app_process; genau hier trennen
sich die Android-Versionen.

- [ ] A1: App starten ohne laufenden Helper → Activate-Screen erscheint.
- [ ] A2: Self-Activation per Wireless-Debugging-Pairing durchlaufen
      (der Kernpfad; auf A16 war zuletzt der Intent-Flow anders).
      Helper-Prozess `btdash_privileged` erscheint in `ps`.
- [ ] A3: Kabel-Fallback: "Copy command" → per `adb shell` ausfuehren →
      App bindet den Helper (Status "Running").
- [ ] A4: App schliessen, wieder oeffnen → Helper wird ohne neue
      Aktivierung wiedergefunden.
- [ ] A5: Bluetooth-Neustart-Aufruf (v4-Op) einmal ausfuehren →
      Stack kommt wieder, App verbindet neu, kein Helper-Verlust.
- [ ] A6: HD-Audio-Toggle (optional codecs) einmal hin und zurueck →
      Wert liest sich zurueck, kein Absturz (v4-Op, reflectiert).

## Block B — Audio-Kernpfad (15 min)

- [ ] B1: Musik starten, EQ oeffnen, ein Band deutlich absenken →
      hoerbar (DynamicsProcessing greift auf A16).
- [ ] B2: Loudness restoration an/aus → kein Audio-Abriss, kein Crash
      (MBC-Stage ist der juengste DSP-Code, auf A16 nie gelaufen).
- [ ] B3: A/B-Bypass-Schalter mehrfach → kein Neuaufbau-Knacken ausser
      dem dokumentierten.
- [ ] B4: Default-Lautstaerke beim Verbinden greift.

## Block C — Live-Link und Codec (10 min)

- [ ] C1: Live-Panel zeigt Eingang vs. Link mit echtem Codec-Namen
      (name-first-Fix muss auch auf dem A16-Dump-Format funktionieren —
      falls dumpsys dort anders formatiert, faellt es sichtbar auf
      Honesty-Zustaende zurueck, nicht auf falsche Werte).
- [ ] C2: Beide Graphen fuellen sich; Verlust-Zeile reagiert (kurz
      ausser Reichweite gehen reicht als Provokation).
- [ ] C3: LDAC-Pin 990→330→ABR einmal durch; Ergebnis wird
      zurueckgelesen; Kalibrier-Signaturen ueberleben App-Neustart
      (Room-Migration auf A16-SQLite).
- [ ] C4: dumpsys-Adressmaskierung: Header maskiert, Pin funktioniert
      trotzdem (raw-Adress-Aufloesung ueber A2DP-Profil).

## Block D — Hoertest + Klinik (10 min)

- [ ] D1: Fit-Check + kurzer Hoertest-Run (2–3 Frequenzen reichen,
      abbrechen erlaubt) → Toene kommen, Pause-Logik pausiert Musik.
- [ ] D2: Klinisches Audiogramm eintragen (2 Werte genuegen) →
      speichern, App-Neustart, Werte noch da.
- [ ] D3: Backup exportieren und wieder importieren → Klinik-Daten,
      Runs, Profile und Derived-Kalibrierungen unversehrt.

## Block E — Zerstoerungstest (5 min)

- [ ] E1: App-Daten NICHT loeschen (der Bestand auf dem Geraet ist egal,
      das Geraet geht weg) — stattdessen: App deinstallieren,
      neu installieren, einmal Block A2 wiederholen. Das ist der Zustand,
      in dem ein A16-Nutzer die App zum ersten Mal sieht.

## Abnahme-Kriterium

Jeder Punkt entweder gruen oder mit begruendetem "auf A16 nicht
verfuegbar, App sagt das ehrlich" dokumentiert. Crashes oder stille
Falschwerte = nicht abgenommen. Ergebnisliste kommt als Abschnitt in
HANDOVER.md; danach darf das Pixel 8 Pro weg.

## Bekannte A16/A17-Trennstellen (wo Fehler am wahrscheinlichsten sind)

- Wireless-Debugging-Pairing-Intents (A2) — der Flow wurde fuer A17
  umgebaut und soll versionsignorant sein; A2 beweist es.
- dumpsys-bluetooth_manager-Format (C1/C4) — Parser sind gegen
  A17-Fixtures gebaut; die Honesty-Faelle muessen greifen statt raten.
- Reflectierte BluetoothA2dp-Methoden (A6, C3) — Signaturen koennen sich
  zwischen 16 und 17 unterscheiden; Fehler muss als Wortmeldung kommen,
  nie als Crash.
