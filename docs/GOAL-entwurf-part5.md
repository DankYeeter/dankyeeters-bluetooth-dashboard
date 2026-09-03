# ENTWURF eines neuen `GOAL.md` — vorgelegt 2026-09-03

**Das ist ein Vorschlag, kein Beschluss.** `GOAL.md` aendert nur der App
Designer. Dieser Entwurf setzt die Entscheidungen vom 02.09. spaet und vom
03.09. um; er ersetzt das bisherige `GOAL.md` erst, wenn der Nutzer zustimmt.

Was sich gegenueber dem Stand vom 30.08. aendert, steht am Ende unter
"Aenderungsprotokoll" — dort ist auch die **Umkehr** einer frueheren
Entscheidung vermerkt.

---

# GOAL — DankYeeter's Bluetooth Dashboard

Zielbild des Director-Zyklus. Angelegt 2026-08-30, neu gefasst 2026-09-03.
Aenderungen nur durch Entscheidung des Nutzers, mit Datum und Grund.

## Ziel

Die App macht den Bluetooth-Audiopfad des Telefons **sichtbar, stellbar und
optimierbar** — in dieser Reihenfolge, weil jede Stufe auf der vorigen ruht.

Sie zeigt ehrlich, was der Stack gerade tut: Codec, Stufe, gemessene Bitrate,
Verluste, und die Firmware-Telemetrie der Funkstrecke selbst. Sie gibt dem
Nutzer die Kontrolle ueber die Einstellungen, die diesen Pfad bestimmen, und
stellt sie dort selbst, wo das ohne Root moeglich ist. Und sie fuehrt aus
beidem einen Optimierungsprozess ab: Sie misst, sie erklaert den Zusammenhang,
sie schlaegt die naechste Massnahme vor und weist den Nutzer an, sie
umzusetzen — und misst danach gegen.

**Die leitende Frage ist "was muss gegeben sein, damit LDAC 990 kbps stabil
laeuft".** Nicht "wie erkennen wir, dass 990 kippt". Die hoechste Stufe ist das
Ziel des Nutzers, nicht ein zu vermeidender Grenzfall.

Und sie tut das alles, **ohne den Klang zu verschlechtern, den sie misst**.
Das Messen ist ein Instrument, kein Eingriff. Eine Anzeige, die das Encodieren
stoert, ist schlechter als keine Anzeige.

## Abnahmekriterien

### Bestand — gilt unveraendert weiter

- **AK-1 (Nicht-Einmischung):** Bei laufender LDAC-Wiedergabe unterscheidet
  sich die gemessene Verlustrate zwischen "App laeuft mit allen Live-Ansichten"
  und "App-Prozess nicht vorhanden" nicht ausserhalb der Streuung der
  Referenzlaeufe. Belegt durch Messreihen am Pixel 11 Pro in
  `docs/perf/baselines.md`.
- **AK-2 (Kein Detailverlust):** Der Informationsgehalt der Live-Ansichten
  bleibt gegenueber Stand babe3d8 erhalten — Codec, Quality-Mode, gemessene
  Bitrate, Underflow-/Dropout-Zaehler, Nahaufnahme mit >= 2 Hz effektiver
  Aufloesung.
- **AK-3 (Ehrlichkeit bleibt):** Kein Wert wird interpoliert, geschaetzt oder
  als MEASURED ausgegeben, wenn er es nicht ist. Degradierte Pfade melden
  "cannot check", nie einen falschen Freispruch.
- **AK-4 (Hintergrund kostet nichts):** Ohne offene Monitor-Oberflaeche fuehrt
  die App keine wiederkehrende Arbeit aus, die den Audiopfad beruehrt. Jede
  periodische Arbeit im Prozess ist inventarisiert und begruendet.
- **AK-5 (Regressionsfrei):** Volle Testsuite gruen; A16-Bau-Regel eingehalten
  (neue Features auf der bewiesenen API-Flaeche, sonst versions-gegated mit
  ehrlicher Meldung).
- **AK-6 (Sicherheit):** Jede Erweiterung des privilegierten Helpers ist vom
  `security-reviewer` geprueft; keine Verbreiterung der Angriffsflaeche ohne
  dokumentierte Begruendung.

### Neu — Saeule 1: Sichtbarkeit

- **AK-7 (Die Funkstrecke wird gezeigt, nicht erraten):** Die Anzeige stuetzt
  sich auf die Firmware-Telemetrie aus dem BQR-Abschnitt von
  `dumpsys bluetooth_manager` — Pakettyp, RSSI, Sendeleistungsstufe,
  Wiederholrate, Nicht-Empfang, AFH-Kanalauslass — und nicht mehr allein auf
  Rueckschluesse aus der Sendewarteschlange. Ist der Abschnitt auf einem Geraet
  leer oder anders geformt, meldet die App das als "kann ich hier nicht lesen"
  und rechnet nicht ersatzweise.
- **AK-8 (Jede Zahl traegt ihre Herkunft):** Zu jeder angezeigten Groesse ist
  im Programm nachvollziehbar, ob sie **gemessen**, **vom System gemeldet**
  oder **abgeleitet** ist. Abgeleitete Groessen nennen die Eingangsgroessen.
  Eine Schwelle ohne Messung ist ein eigener Typ (`Open`/`None`), keine Zahl —
  das ist bereits als AD-019/AD-024 entworfen und wird hier zum Kriterium.

### Neu — Saeule 2: Kontrolle ueber die Einstellungen

- **AK-9 (Alles Relevante ist an einer Stelle sichtbar und stellbar):** Die
  App zeigt zu jeder Einstellung, die den Audiopfad bestimmt, den **Ist-Wert**
  und was er bewirkt. Wo Android das Setzen ohne Root zulaesst, setzt die App
  sie **selbst**; wo nicht, springt sie per Deep-Link an die Stelle und sagt,
  was zu tun ist. Welche Einstellung in welche Kategorie faellt, wird am Geraet
  ermittelt und dokumentiert, nicht angenommen.
- **AK-10 (Jedes Helfer-Kommando einzeln geprueft):** Jedes neue Kommando des
  privilegierten Helpers ist einzeln vom `security-reviewer` abgenommen, in der
  Whitelist benannt und begruendet. Ein Kommando, das mehr kann als die eine
  Einstellung, die es stellen soll, wird nicht aufgenommen. Nichts, was Root
  oder ein deaktiviertes SELinux braucht, wird angeboten — auch nicht als
  Hinweis.
- **AK-11 (Rueckweg garantiert):** Zu jeder Einstellung, die die App selbst
  setzt, kann sie den vorherigen Wert wiederherstellen. Vor dem ersten Setzen
  wird der Ausgangszustand festgehalten. Der Nutzer kommt aus jedem
  Tuning-Zustand mit einer Handlung zurueck auf den Stand von vorher.

### Neu — Saeule 3: Optimieren

- **AK-12 (Der Prozess enthaelt nur Belegtes):** Der gefuehrte Prozess besteht
  aus den sechs in R-010 belegten Massnahmen. Massnahmen, die in Wahrheit die
  Bitrate senken (Stufe herunter, ABR, Codec-Wechsel, 44,1-kHz-Familie),
  erscheinen ausschliesslich in einer ausdruecklich als **"Ausweichen"**
  benannten Kategorie und nie als Behebung. Widerlegte Ratschlaege erscheinen
  gar nicht.
- **AK-13 (Vorher/Nachher statt Behauptung):** Jede Massnahme, die der Prozess
  vorschlaegt, wird gemessen — Zustand vorher, Umsetzung, Zustand nachher, mit
  denselben Groessen und derselben Dauer. Bleibt die Wirkung innerhalb der
  Nachweisgrenze, sagt die App das, statt einen Erfolg zu behaupten.
- **AK-14 (Der Messrahmen steht in der Anzeige, nicht in einer Fussnote):**
  Ein Schnelldurchlauf misst relative Verbesserung unter Stress, nicht
  Alltagsqualitaet. Das steht dort, wo das Ergebnis steht.
- **AK-15 (Generalisierbares und Geraetespezifisches sind getrennt):**
  Stack-Konstanten (Bitratenleiter, Warteschlangentiefe, Encodertakt,
  Scheduling) sind fest hinterlegt und gelten geraeteuebergreifend.
  Hardwarefakten (Pakettyp, effektive MTU, EDR-Klasse der Gegenstelle,
  Wiederholrate, Encoder-Ort) werden **am Geraet gelesen** und nie von einem
  anderen Geraet uebernommen. Kann eine davon nicht gelesen werden, sagt die
  App das und leitet aus ihr nichts ab.
- **AK-16 (Die App kennt die Grenze ihres Rats):** Ergibt die Messung, dass die
  Paarung 990 kbps aus strukturellen Gruenden nicht tragen kann, sagt die App
  das — statt Massnahmen vorzuschlagen, die daran nichts aendern koennen.

## Nicht-Ziele

- Kein Klang-"Verbessern" jenseits der spezifizierten Hoerkompensation.
- Keine neue Datensammlung ueber das hinaus, was der Audiopfad hergibt.
- Kein Rueckbau der Ehrlichkeitsregeln zugunsten huebscherer Graphen.
- Keine Installationen mehr auf dem Pixel 8 Pro (abgenommen 28.8.).
- **Nichts, was Root oder Systemrechte jenseits des vorhandenen Helfers
  braucht** — weder gebaut noch empfohlen.
- **Keine Massnahme, die als Verbesserung auftritt, aber die Bitrate senkt.**
- **Keine erfundene Schwelle.** Solange ein Hoerbarkeitspunkt nicht gemessen
  ist, gibt es dort kein Urteil, keine Ampel und kein abstufendes Wort
  (Regel R-E).

## Rahmen

- Zielgeraet: **Pixel 11 Pro, Android 17** (`67011FDKX004XG`, per Kabel).
  Android 16 bleibt unterstuetzt, wird aber nicht mehr bespielt.
- Gegenstelle: Noble FoKus Prestige Encore. Die Linkfakten dieser Paarung
  (MTU, EDR-Klasse, Pakettyp) sind **Eigenschaften der Paarung**, nicht des
  Telefons, und werden nicht auf andere Geraete uebertragen.
- Kotlin/Compose, Oboe NDK, eigener privilegierter Helper (`app_process`, per
  ADB-Loopback gestartet). Kein Shizuku mehr.
- Toolchain: Temurin 21, compileSdk/targetSdk 36, AGP 8.9.3, Gradle 8.11.1.
- Emulator existiert nicht (kein Hypervisor) — alles ausser Geraetetests laeuft
  ueber Unit-Tests und Robolectric.

## Bekannte Luecken im Fundament dieses Zielbilds

Ehrlich benannt, damit niemand darauf baut, ohne es zu wissen:

- **Kein belegter Hoerbarkeitspunkt.** R-006: fuer A2DP-Musik existiert keine
  Literaturschwelle. Der Wert `LOSS_ALERT_RATE_PER_MIN` hat ausserhalb dieses
  Projekts keinen Rueckhalt. AK-12 bis AK-14 setzen deshalb auf **Vergleich**,
  nicht auf absolute Bewertung.
- **Kein belegter Stresshebel.** Externe Funklast liess sich in T-028 ueber
  acht gueltige Abschnitte **nicht** reproduzieren; alle diese Tests liefen
  aber bei 660, wo die Strecke riesige Reserven hat. Ob bei 990 ein Hebel
  greift, ist offen. **Bis dahin ist die Testsuite (AK-13) auf den
  Vorher/Nachher-Vergleich ohne kuenstlichen Stress beschraenkt.**
- **Keine der sechs Massnahmen ist unter gepinnten 990 gemessen.** Die
  Rangfolge in R-010 ist Belegstaerke, nicht Wirkungsgroesse.
- **Die Luftzeitrechnung ist eigene Arithmetik**, kein Messwert. T-032 soll sie
  durch Ist-Werte ersetzen.

## Aenderungsprotokoll

**2026-09-03 — Neufassung, Entscheidung des App Designers.**

1. **Drittes Standbein aufgenommen: Optimieren.** Das Zielbild vom 30.08.
   endete bei "sichtbar und korrigierbar". Grund: Der Nutzer will einen
   gefuehrten Tuning-Prozess, weil moeglicherweise jedes Geraet anders ist und
   eine feste Regelliste deshalb nicht traegt.
2. **Zielpraezisierung: 990 kbps ist das Ziel, nicht der Fehler.** Grund:
   ausdrueckliche Ansage des App Designers am 02.09. spaet. Folge: Stufe senken
   und Codec-Wechsel gelten als Aufgabe der Qualitaet, nicht als Behebung.
3. **UMKEHR einer frueheren Entscheidung.** Am 02.09. galt: "Die App aendert
   keine Einstellungen selbst, sie leitet nur an — kein Ausbau des
   privilegierten Helfers." Am 03.09. hat der Nutzer entschieden: **Die App
   stellt selbst, wo es ohne Root geht.** Grund: Die Basis soll Kontrolle ueber
   die Einstellungen bieten, und die Testsuite ist nur so automatisierbar.
   **Preis, ausdruecklich in Kauf genommen:** Der privilegierte Helfer waechst,
   und damit die Angriffsflaeche. AK-10 und AK-11 sind die Gegengewichte —
   Einzelpruefung je Kommando und garantierter Rueckweg. AK-6 bleibt und wird
   dadurch scharf gestellt statt aufgeweicht.
4. **Kategorie "Ausweichen" wird Pflicht** (AK-12). Grund: Entscheidung des
   Nutzers am 03.09. aus den drei vorgelegten Varianten.
5. **Trennung generalisierbar/geraetespezifisch aufgenommen** (AK-15). Grund:
   R-009 hat gezeigt, dass die Stack-Konstanten uebertragbar sind, die
   Hardwarefakten aber je Geraet **und je Paarung** neu gelesen werden muessen.
   Das war zuvor nicht unterschieden.
