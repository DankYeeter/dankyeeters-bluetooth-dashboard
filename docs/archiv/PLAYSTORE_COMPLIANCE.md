# Play-Store-Compliance — Bestandsaufnahme (22. August 2026)

Read-only-Audit gegen den echten Code. **Alle rechtlichen Aussagen sind
Laien-Einschätzung, keine Rechtsberatung.** Policy-Stand wie bekannt; gegen die
aktuelle Play Console gegenprüfen.

## Gute Ausgangslage (verifiziert)
- **Kein INTERNET** in allen vier Modulen — bestätigt. Einziger Treffer ist der
  „Deliberately NO"-Kommentar in `core-system/AndroidManifest.xml:6`.
- MACs gehasht, Mikrofon nur In-Memory (Hörtest), `neverForLocation` an den
  BT-Scan-Permissions gesetzt.
- Alle Fremdbibliotheken (Oboe 1.9.3, Room, Compose, kotlinx, AndroidX,
  DataStore) sind Apache-2.0.

## Harte Blocker
- **B1 — QUERY_ALL_PACKAGES** (`app/AndroidManifest.xml:20`). Play beschränkt
  die Permission auf enge Kategorien; „welche App hat einen EQ" fällt nicht
  darunter. **Entkoppelbar ohne Feature-Totalverlust:** die zwei belastbaren
  Erkennungs-Tiers laufen über die vorhandene `<queries>`-Deklaration
  (`<intent>` DISPLAY_AUDIO_EFFECT_CONTROL_PANEL + 12 Vendor-Pakete). Nur die
  ohnehin als „not evidence" markierte Vollenumeration entfällt.
  Fix: QAP raus, `PackageManagerAppSource` auf die gefilterte Sicht stützen,
  `EqCandidateScannerTest` + UI-Zähler nachziehen. Klein–mittel.
- **B2 — Data-Safety-Formular** fehlt (Projekt war nie Store-gedacht). Formaler
  Blocker; inhaltlich einfach (kein Netz, Mikrofon on-device). Datenschutz-URL
  nötig, sobald Mikrofon deklariert ist.
- **B3 — FGS-Typ `connectedDevice`** (`core-system/AndroidManifest.xml:41`)
  braucht Play-Console-Deklaration + Begründung, evtl. Demo-Video. Typ passt
  inhaltlich, muss aber eingereicht werden.

## Risiken / aufräumen
- **R1 — Privilegierter Helfer** (app_process als shell, @hide-Reflection,
  exportierter `PrivilegedProvider`). Kein automatischer Blocker (Shizuku-Apps
  existieren, ADB-Start ist nutzer-initiiert), aber das größte Review-Risiko —
  besonders bei einer bezahlten App. Sauberster Umgang: Flavor-Trennung (s.u.).
- **R2 — WRITE_SECURE_SETTINGS + BLUETOOTH_PRIVILEGED** deklariert. Üblich, aber
  Reviewer fragen nach. `BLUETOOTH_PRIVILEGED` wird nie gewährt → im Store-Build
  weglassen erspart Rückfragen.
- **R3 — Non-SDK-Reflection außerhalb des Helfers**
  (`AudioManagerPlayingAppsSource.getClientUid`). Fail-closed, aber bei jedem
  targetSdk-Bump gezielt testen.
- **R4 — Lizenz-Attribution.** Apache-2.0 verlangt NOTICE/Lizenztext bei
  Distribution. Repo hat nur `LICENSE` (MIT, eigener Code), **kein NOTICE / kein
  In-App-Lizenzen-Screen**. Für bezahlten Release ergänzen. AirPods-BLE-Prior-Art
  (PLAN.md) gegenprüfen, dass kein GPL-Code übernommen wurde.
- **R5 — Datenschutz inhaltlich**: RECORD_AUDIO, BLUETOOTH_CONNECT/SCAN,
  POST_NOTIFICATIONS deklarieren; „keine Daten gesammelt/geteilt" ist plausibel.
- **R6 — Store-Metadaten**: keine medizinischen Heil-/Diagnoseaussagen (Play
  Health-Policy). Bestehender Disclaimer taugt als Basis.
- **R7 — Signing/versionCode**: kein Release-Signing im Gradle sichtbar;
  App-Signing-Enrollment nötig.

## Empfehlung: separater Store-Flavor
Realistischer Weg. Zwei im Code sichtbare Gründe:
1. QAP ist entkoppelbar — Sideload-Flavor behält es, Store-Flavor nutzt nur die
   `<queries>`-Sicht.
2. Der Helfer ist bereits sauber im `privileged`-Package isoliert; ihn +
   exportierten Provider + BLUETOOTH_PRIVILEGED/WRITE_SECURE_SETTINGS in einen
   `sideload`-Flavor ziehen, dem `store`-Flavor nur Session-Mode (+ optional
   externes Shizuku) lassen. **`GlobalAttachmentStrategy.activate()` hängt NICHT
   am Helfer** (attacht mit reinem MODIFY_AUDIO_SETTINGS auf Session 0) — der
   globale EQ bliebe im Store-Flavor erhalten. Am Gerät verifizieren.

Für beide Flavors bleiben: B2, B3, R3–R7. Der Bezahlaspekt öffnet **keine**
QAP-Ausnahme (Kategorien sind funktions-, nicht preisbezogen), erhöht nur die
Erwartung an Sorgfalt (Lizenz-Attribution, Verbraucher-/Erstattungsrecht).

---

# Nachtrag (22. August, abends): Session-Ernte

Neue Funktion, neue Prüfung. Die App liest über den privilegierten Helfer
`dumpsys audio`, um die Audio-Session-IDs laufender Player zu erfahren — nötig,
weil Android sie einer normalen App bewusst verbirgt (`getSessionId()` liefert
0) und weil Tidal seine Session nie ankündigt.

## Einordnung

**R4 — `dumpsys audio` liest Fremd-App-Zustand.** Das ist der heikelste Punkt
der Funktion, und er soll hier nicht kleingeredet werden: die Anonymisierung
fremder Session-IDs ist eine bewusste Plattform-Entscheidung, und wir umgehen
sie. Vier Umstände relativieren das, keiner hebt es auf:

- Es geht **nur** über den Helfer, den der Nutzer selbst per ADB startet. Ohne
  ihn passiert nichts; die Funktion fehlt einfach.
- Der Parser (`PlaybackSessionParser`) behält **ausschließlich Integer-IDs**.
  Paketname, Metadaten und Inhalt werden verworfen, bevor irgendetwas die
  Funktion verlässt — nachprüfbar, es ist eine reine Funktion über Text.
- Nichts wird gespeichert und nichts gesendet. Die App hat **kein**
  INTERNET-Permission.
- Der Befehl ist rein lesend und steht namentlich auf der Helfer-Whitelist
  (`PrivilegedProtocol.ALLOWED`), die ein Test auf ihre Größe festnagelt.

**Bewertung für den Store:** kein *eigener* harter Blocker, aber es verschärft
**R1**. Ein Reviewer, der den Helfer akzeptiert, wird auch das hier
akzeptieren; wer den Helfer beanstandet, beanstandet beides. Die Funktion steht
und fällt also mit der Flavor-Trennung, nicht mit sich selbst.

**Data Safety (B2):** die Ernte ändert die Antwort nicht — es werden keine
personenbezogenen Daten erhoben, verarbeitet oder übertragen. Eine
Session-ID ist eine prozesslokale Ganzzahl ohne Bezug zur Person. Trotzdem
gehört in die Store-Beschreibung ein ehrlicher Satz, dass die App im
Helfer-Modus systemweite Wiedergabezustände liest, um den Equalizer
anzuwenden.

## Empfehlung, unverändert

Store-Flavor **ohne** Helfer und **ohne** QUERY_ALL_PACKAGES; die Ernte gehört
in den Sideload-Flavor. Damit ist die Store-Version unstrittig und verliert
genau die Fähigkeiten, die ohne Helfer ohnehin nicht funktionieren würden.

Wichtig für die Erwartung: der Store-Flavor kann über Bluetooth dann **nur**
Player korrigieren, die ihre Session ankündigen — der globale Pfad ist dort
gemessen wirkungslos (siehe HANDOVER). Tidal wäre in der Store-Version also
nicht abgedeckt, in der Sideload-Version schon.
