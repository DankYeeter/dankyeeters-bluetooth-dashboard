# T-045 — Bestandsaufnahme AK-8 bis AK-16 gegen den Code (architect)

Stand: Branch `auto/2026-09-23` @ `bc28d3c`, gelesen am 23.09.2026 ab 01:00.
Nur gelesen, kein Code geändert. Grundlagen: `GOAL.md`, `docs/state.md`,
`ARCHITECTURE.md` (AD-002..AD-009, AD-019..AD-024, „Bewusst nicht getan"),
`docs/research/R-010.md`, `docs/research/R-011.md`,
`docs/archiv/state-messbefunde-2026-09-03.md`, `docs/perf/T-032-readback.md`,
`UI_SPEC.md` (T-039-Abschnitt), `docs/tasks/T-044.md`.

**Quittung für alle Suchen:** `git grep` über `*/src/main/*.kt` und
`*/src/main/*.xml`, 2026-09-23T01:12+02:00, Commit `bc28d3c`. Die Suchen
decken Kotlin und XML im Hauptcode ab, keine Tests und keine Ressourcen-Dumps.
Eine Null heisst also **mindestens** „nicht im Hauptcode" (L-033). Dass die
Suchmittel greifen, zeigen die zwei Kontrollmuster mit Treffern
(`before…after`: 2, `structural…`: 7); alle Treffer habe ich einzeln gelesen,
keiner gehört zur Sache.

## 1 Urteil je Kriterium

| AK | Urteil | Beleg |
|---|---|---|
| **AK-8** Herkunft | **teilweise** | Gebaut: `Honesty` mit MEASURED/DERIVED/NOMINAL/PROXY/UNAVAILABLE (`LinkLiveModels.kt:16-37`), Herkunft je Feld im KDoc (`LinkLiveModels.kt:75-138`, `LiveTraceModel.kt:21-47`, `ObservationRun.kt:63-73`), „(measured)" in der Oberfläche (`LiveLinkPanel.kt:685-689`). `UI_SPEC.md:2900` AK-T039-14 erkennt „im KDoc nachvollziehbar" als Erfüllung an. **Lücken:** (a) „gemessen" und „vom System gemeldet" tragen dasselbe Etikett: MEASURED heisst laut `LinkLiveModels.kt:17` „Read from a counter or a field the system maintains". (b) Den Schwellen-Typ `Open`/`None` (AD-019) gibt es nicht: `LossThreshold` 0 Treffer; die zweite Suche nach `TODO\(M-` oder `data class Open` fand nichts Einschlägiges. Die einzige Schwelle der Live-Ansicht, `LADDER_QUEUE_PRESSURE_FRACTION` (`LiveLinkPanel.kt:618`), ist ein nackter `Double`, trägt ihre Messung aber im KDoc (`:595-611`). Inhaltlich hält sie die Regel also ein, als Typ nicht. (c) Der Bericht des Gerätetests zählt „drop(s)" aus zwei verschiedenen Dingen, nämlich Stream-Stopps und Proben mit `droppedPackets > 0` (`DeviceDiagnostic.kt:294-297`). Die Eingangsgrössen nennt er nicht, und schon ab `> 0` meldet er „Warned" (`:148-150`). |
| **AK-9** Sichtbar und stellbar | **teilweise** | Die App stellt selbst: LDAC-Stufe (`LdacTuning.kt:59-72`, `SET_CODEC_PREFERENCE` `PrivilegedProtocol.kt:92`), HD-Audio (`:100`) und vier Globals (`BluetoothSystemControls.kt:36`). Letztere sind laut KDoc `:31-34` am Pixel 11 Pro verifiziert. AVRCP ist laut R-010 C2 allerdings wirkungslos für 990. Nur lesend, mit Grund: drei Properties (`BluetoothSystemControls.kt:98-123`). **Es fehlen** die Ist-Werte aller Einstellungen hinter den sechs R-010-Massnahmen: `2[.,]4 ?GHz` 0, `multipoint` 0, `wifi_scan_always\|ble_scan_always\|wifi_on\|WifiManager` 0 (die Treffer dieser Suche betrafen nur `usb` in Aktivierungstexten und `connectedDevices`). Deep-Links gibt es nur für Aktivierung und Berechtigungen (`ActivateScreen.kt:340,347`, `SetupWizardScreen.kt:317,371`, `BluetoothSection.kt:560`). Keiner führt zu einer Audiopfad-Einstellung. Am Gerät ermittelt und dokumentiert sind nur die vier Globals. |
| **AK-10** Helfer-Kommando einzeln geprüft | **gebaut** (als Mechanismus; seit 03.09. leer erfüllt) | Whitelist mit exaktem Argument-Vektor und Begründung je Eintrag (`PrivilegedProtocol.kt:45-62`), Einteilung jeder Binder-Methode in lesend/schreibend (`:75-127`), Reflexionstest gegen AIDL (`PrivilegedProtocolTest.kt:338`). `git log --since=2026-09-03` auf `PrivilegedProtocol.kt` und `app/src/main/aidl`: ein Commit, `3b3a3ff` (W-1, entfernt nur). **Kein neues Kommando seit Inkrafttreten.** Zwei Textbefunde siehe Abschnitt 4. |
| **AK-11** Rückweg | **offen** | `(?i)\bledger\b\|priorValue\|valueBefore` 0. Zweite Suche `restore\|revert\|undo` in `:core-system` und den Stell-Bildschirmen: nur die bestmögliche Codec-Rückgabe innerhalb eines Gerätetests (`DeviceDiagnostic.kt:140,174-226`) und „Use System Default", das den Schlüssel löscht (`DeviceProfileApplier.kt:152`). Keines davon hält den Ausgangszustand vor dem ersten Setzen fest, und keines bietet die eine Handlung zurück. Das persistierte Ledger aus AD-007 ist entworfen, aber nicht gebaut. |
| **AK-12** Nur Belegtes, „Ausweichen" | **offen** | `Ausweichen` 0. Zweite Suche `(?i)\b(fallback\|detour\|avoidance) (category\|option\|measure)` 0. Der einzige geführte Ablauf ist der „Device test" (`MonitorScreen.kt:215-238`, `DeviceDiagnostic.kt:12-18`). Er schaltet durch die Codecs und meldet „Most stable codec: X" (`DeviceDiagnostic.kt:54`). Damit zeigt er einen Codec-Wechsel als Ergebnis, **im Widerspruch zu AK-12**. |
| **AK-13** Vorher/Nachher | **offen** | `(?i)detection limit\|nachweisgrenze\|within (the )?noise` 0. `before.{0,20}after` 2 Treffer, beide fachfremd (`SettingsScreen.kt:336`, `EqController.kt:82`). Bausteine sind vorhanden: abgedeckte Intervalle in `ObservationRun` (`ObservationRun.kt:52-62`, gemeinsame Lückenregel `isReadingGap` `:14`) und der Zähler `STACK_DROPOUTS` je Fenster (`LinkLiveModels.kt:596`). |
| **AK-14** Messrahmen an Ort und Stelle | **offen** | `(?i)relative (statement\|comparison)\|under these conditions` 0. Es gibt kein Vergleichsergebnis, an dem ein Rahmen stehen könnte. |
| **AK-15** Generalisierbar gegen gerätespezifisch | **teilweise** | Die Stack-Konstanten sind fest hinterlegt: Stufenleiter (`LdacTuning.kt:59-72`, `LinkLiveModels.kt:385`), 20-ms-Takt im KDoc (`CodecModeInference.kt:158`). Von den Hardwarefakten wird der **Encoder-Ort** am Gerät gelesen und genutzt (`A2dpLinkDumpParser.kt:95`, `LiveLinkPanel.kt:437`). Die **effektive MTU** wird geparst (`A2dpLinkDumpParser.kt:409`), aber nicht angezeigt (`mtu` in `app/src/main` 0). Die **EDR-Klasse** steht als `Support 3Mbps: true` im gepollten Dump (Fixture `bt_manager_pixel11_ldac_990_loss.txt:2443`), wird aber nicht geparst. **Pakettyp** und **Wiederholrate** gibt es nur über BQR, und dort ist wegen AK-7 nichts gebaut. Dass die App diese beiden nicht lesen kann, sagt sie nirgends. |
| **AK-16** Grenze des Rats | **offen** | `(?i)structural\|cannot carry 990\|990.{0,30}(impossible\|not possible)` 7 Treffer, alle fachfremd (Backup-Parser, Aktivierung, Timeline-Spur). |

Zusammen: 1 gebaut, 3 teilweise, 5 offen. Die QA-Meldung aus T-043a
(„geführter Prozess AK-12..16 fehlt, grep ‚Ausweichen' leer") ist mit
zweiter Wortwahl bestätigt.

## 2 Schnitt in lauffähige Schritte (Präfix `P-`)

Nach der Regel aus AD-014 bekommt dieses Vorhaben einen eigenen Präfix. `P-`
ist frei: 0 Treffer in `ARCHITECTURE.md`, `UI_SPEC.md` und `docs/state.md`.
**Kein Schritt des ersten Schnitts braucht ein neues Helfer-Kommando.** Das gilt
nur, wenn F3 wie empfohlen beantwortet wird.

| Schritt | AK | Inhalt | Gerät | Helfer-Kommando | UI-Spec | Entscheidung | Hängt ab von |
|---|---|---|---|---|---|---|---|
| **P-1** | 8 | Der `Honesty`-KDoc ordnet die drei AK-8-Wörter zu: MEASURED heisst vom System gemeldet, Dauern nach der App-Uhr sind DERIVED aus Zeitstempeln. `ObservationRun.observedMs` ist eine Summe und wird DERIVED. `LossThreshold` kommt erst mit V-1, sobald das erste Verdikt gebaut wird, nicht vorher. | nein | nein | nur `UI_SPEC.md:2900-2901` nachziehen (`{T}` steht dort als MEASURED) | Director (Spec-Widerspruch) | T-044 gemergt |
| **P-2** | 15, 9 | Paarungsfakten aus dem **schon gepollten** Dump: `Support 3Mbps` und die Zahl der Geräte mit `[ACL BR/EDR:Y` (Fixtures vorhanden, 123 Zeilen je 990er-Dump). Die effektive MTU wird sichtbar. Pakettyp und Wiederholrate stehen als „nicht lesbar ohne BQR" da, und aus ihnen wird nichts abgeleitet. | nein (Fixture) | nein | ja, klein | nein | — |
| **P-3** | 9 | Geräteinventur am Pixel 11 Pro (`performance-tuner`, `docs/perf/`). Für jede Audiopfad-Einstellung wird festgestellt, ob die App sie selbst stellt, per Deep-Link hinführt oder nur anleiten kann. Dazu gehört die Frage, ob `wifi_on`, `wifi_scan_always_enabled` und `ble_scan_always_enabled` aus der App-Uid lesbar sind und mit `WRITE_SECURE_SETTINGS` schreibbar. Ohne Zwischen-`dumpsys bluetooth_manager` (R-011). | **ja** | nein (nur lesen) | nein | nein | — |
| **P-4** | 9 | Ist-Werte ohne neuen Zugriff: WLAN-Funk, die zwei Scan-Schalter, USB aus dem Battery-Sticky-Intent (nur „Kabel steckt", **nicht** USB 3 behaupten) und das zweite ACL-Gerät aus P-2. Die drei Nur-Lesen-Zeilen verlinken in die Entwickleroptionen (Intent liegt schon in `ActivateScreen.kt:340`), und der Root-Satz fällt weg. | nein (Robolectric) | nein | ja | nein | P-2, P-3 |
| **P-5** | 11 | Ausgangszustand-Ledger nach AD-007 in `:core-system`, **erweitert um „gewählt" neben „geliehen"**. Geliehenes geht automatisch zurück, Gewähltes nur auf Wunsch. Der Eintrag wird vor dem ersten Setzen persistiert, eine Handlung führt zurück. Reihenfolge: zuerst die LDAC-Stufe (der Prozess pinnt 990), dann Globals, HD-Audio und Absolute Volume. | nein | nein | ja | Director: Rückweg gegen Profil-Autoapply (F-D1) | — |
| **P-6** | 12 | Katalog als Daten: sechs Massnahmen (R-010 A1–A5, A7), vier Ausweichen (Stufe senken, ABR, Codec-Wechsel, 44,1-kHz-Familie) in einer **eigenen Liste**, Widerlegtes fehlt. Ein Test prüft beide Listen als Literal und prüft, dass keine Kennung aus R-010 Teil 3 vorkommt. | nein | nein | ja (Texte) | F5 | — |
| **P-7** | 13 | Vergleichskern, rein, in `:core-monitor`. `ObservationRun` summiert zusätzlich `STACK_DROPOUTS` über abgedeckte Intervalle. `compare(vorher, nachher)` verlangt gleiche abgedeckte Dauer (Arm B endet, wenn er die Dauer von Arm A erreicht), gleiche Stufe, gleichen Codec und ein unverändertes Zustandsbuch. Sonst gilt `NotComparable(grund)`. Das Urteil kommt aus dem exakten bedingten Binomialtest auf zwei Zählungen gleicher Dauer. Es werden nur Zählungen verglichen, keine Rate je Minute (R-F bleibt). α ist eine benannte Konstante mit dem Vermerk „Konvention, keine Messung". Literale Testfälle: 5 gegen 0 ist nachweisbar (p = 1/32), 4 gegen 0 liegt in der Nachweisgrenze (p = 1/16). | nein | nein | nein (Logik) | F1, F2 | T-044 gemergt |
| **P-8** | 13, 14 | Geführter Ablauf im Monitor: 990 pinnen (über P-5), Arm A, Anleitung zur Massnahme, Arm B, Ergebnis. **Der Messrahmen steht am Ergebnis:** beide Dauern, Stufe, Zustandsbuch am Anfang und Ende jedes Arms sowie der Satz „relative Aussage über genau diese Bedingungen". Danach der Rückweg. Arm A liegt fertig als Wert im ViewModel und übersteht das Verlassen der App, nicht aber den Prozesstod; das sagt der Ablauf auch. **Ersetzt den Gerätetest**, statt daneben zu stehen. | Code nein; Abnahme **ja**, fällt mit T-036 zusammen (Massnahme 1 unter 990) | nein bei F3 = anleiten | ja | F3, F5 | P-4..P-7 |
| **P-9** | 16 | Solange der Pakettyp nicht lesbar ist, heisst es „strukturell nicht bestimmbar". Der Endzustand „alle belegten Massnahmen ohne nachweisbare Wirkung" ist ein empirischer Satz und keine strukturelle Aussage. | nein | nein | ja | F4 | P-2, P-8 |

**Vorgesehene Entscheidungen, noch nicht eingetragen.** Der Dispatch erlaubt nur
diese Datei. Der Director gibt sie frei, dann trage ich sie als AD-030 ff. ein:
- Der Vergleich besteht aus zwei `ObservationRun`s und einer reinen Funktion.
  Eine neue Laufart gibt es nicht. Verworfen: ein eigener `ComparisonRun`. Er
  hätte eine zweite Lückenregel neben `isReadingGap`, und `isReadingGap` ist
  gerade dafür gebaut, dass es nur eine gibt (`ObservationRun.kt:7-12`).
- Das Ledger unterscheidet „geliehen" und „gewählt". AD-007 kennt nur das
  Leihen und gibt beim App-Start automatisch zurück. Auf eine Wahl des Nutzers
  angewandt wäre das falsch, deshalb ist das ein benannter Widerspruch zu AD-007.
- Der Prozess ersetzt `DeviceDiagnostic` und überschneidet sich mit dem ruhenden
  Scan T-005 (AD-003, AD-008). Wo beide dasselbe tun, gilt der Prozess, und die
  betroffenen AD werden als abgelöst markiert.

## 3 Fragen an den Nutzer vor Säule 3 (je mit Empfehlung)

1. **F1: Woran misst der Vergleich?**
   - *Empfehlung:* bei gepinnten 990 die `dropouts`-Zählung. Der Preis: Während
     der Messung sind Aussetzer hörbar (T-008).
   - Alternative: ABR und der Anteil ≥ 990. Hörbar störungsfrei, aber ohne
     Signal: Die App sagt selbst, adaptiv sei hier nie bei 990 gesehen worden
     (`LiveLinkPanel.kt:576`). Vorher und nachher stünden also beide auf null.
2. **F2: Wie lang ist ein Arm, und reicht A/B?**
   - *Empfehlung:* A/B mit 15 min je Arm. So verlangt es der Wortlaut von AK-13,
     und die Drift wird im Messrahmen benannt.
   - Grund für die Länge: Nachweisbar wird eine Verbesserung auf null erst ab
     5 Ereignissen vorher. Bei der T-029-Rate (10 Cluster in 25 min) sind das
     rund 12,5 min.
   - Alternative: A/B/A nach AD-009. Robust gegen Drift, aber 45 min.
   - Grenze, die die App dann ausspricht: Hat Arm A null Ereignisse (wie T-032,
     27,78 min), ist keine Wirkung nachweisbar.
3. **F3: Setzt die App Massnahmen im ersten Schnitt selbst?**
   - *Empfehlung:* nein. Sie leitet an und liest den Ist-Wert zurück.
   - WLAN selbst auszuschalten bräuchte ein neues Helfer-Kommando. Dann gelten
     AK-10 und der Security-Vorlauf.
   - Die Scan-Schalter zu leihen hängt an AD-006. Dessen offene Frage 1 ist seit
     31.08. unbeantwortet.
4. **F4: Was zählt bei AK-16 als „strukturell"?**
   - *Empfehlung:* vorerst nichts. Die App sagt „strukturell nicht bestimmbar",
     bis T-037 klärt, ob der Pakettyp lesbar wird.
   - Grund: Die Luftzeitrechnung ist laut `GOAL.md` eigene Arithmetik. Auf dieser
     Paarung (MTU 883, 3 Mbps) schlüge eine MTU-Regel ohnehin nie an.
5. **F5: Was wird aus dem „Device test"?**
   - *Empfehlung:* entfernen, und zwar sofort als eigener kleiner Schritt. Er
     verletzt AK-12 schon heute („Most stable codec"), und der neue Prozess
     soll kein zweites Muster daneben sein.
   - Alternative: umformulieren und behalten.

Frage an den Director:

**F-D1: Wie verhält sich der Rückweg zum Profil-Autoapply?**
- Das Problem: Nach „zurück auf vorher" schreibt das nächste Verbinden eines
  Profilgeräts den Profilwunsch sofort wieder (`DeviceProfileApplier`).
- *Empfehlung:* Der Rückweg setzt das Autoapply aus, bis der Nutzer es wieder
  einschaltet.

## 4 Nebenfunde (nicht behoben)

1. **Die offene Monitor-Ansicht leert die BQR-Queue selbst.**
   - Befund: Ihre einzige Quelle ist `dumpsys bluetooth_manager`
     (`DumpsysLinkSource.kt:19`, „The one `dumpsys bluetooth_manager` reader"
     `MonitorGraph.kt:143`). Sie ruft es im Takt von 0,5 bis 5 s auf. Nach R-011
     leert jeder Aufruf die Queue.
   - Folge: Die App ist damit schon heute die „pollende App", deretwegen AK-7
     zurückgestellt wurde.
   - Für T-037: Während des Laufs darf die Monitor-Ansicht nicht offen sein,
     sonst gilt dieselbe Bedingung wie für Zwischen-`dumpsys`. Ob das schon
     irgendwo festgehalten ist, habe ich in `docs/state.md`, `docs/lessons.md`,
     `qa/`, T-033, T-035, T-037 und `UI_SPEC.md` gesucht, ohne Treffer. T-037
     selbst gibt es nicht als Datei.
2. **Der Satz „only a rooted phone can change" ist sachlich falsch.**
   - Fundstellen: `BluetoothSystemControls.kt:105`, `DeviceProfilesScreen.kt:316`
     (2 Nutzertexte; `rooted` hat im Hauptcode 3 Treffer, der dritte ist KDoc in
     `PrivilegedServer.kt:505`).
   - Warum falsch: Alle drei Nur-Lesen-Werte stellt Android in den
     Entwickleroptionen (R-010 A3, C3). Zugleich ist der Satz ein Root-Hinweis,
     den AK-10 ausschliesst.
3. **Der Whitelist-KDoc zählt „three", `ALLOWED` hat vier Einträge.**
   - Fundstellen: `PrivilegedProtocol.kt:18,20,79` gegen `:45-62`. Ob `:29` und
     `BluetoothDeveloperOptions.kt:329` („three dumpsys/ps commands",
     geschichtlich formuliert) auch betroffen sind, entscheidet der Kontext.
   - Warum es zählt: AK-10 verlangt „in der Whitelist benannt".
   - Vor dem Fix nach K-2 projektweit suchen.
4. **Gerätetest: RSSI und „drop(s)".**
   - Der Bericht nennt eine RSSI-Spanne (`DeviceDiagnostic.kt:57`). RSSI ist auf
     einem Serien-Build aber „structurally always empty" (`TimelineModel.kt:85`).
     Ist sie leer, entfällt die Angabe; das ist ehrlich, aber toter Code.
   - Das Zählwort „drop(s)" steht quer zu R-G.
