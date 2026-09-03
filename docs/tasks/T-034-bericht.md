# T-034 — Bericht des `developer` (abgelegt vom Director, L-005)

STATUS: **teilweise** · Commit `0dbea4e` auf `master` · 2026-09-03

Teil 1 (Parser-Golden-Test) erledigt. Teil 2 (R-D am echten Dump) faellt aus —
Begruendung unten, sie ist der wichtigste Inhalt dieses Berichts.

## Was entstanden ist

`core-monitor/src/test/java/dev/dankyeeter/btdashboard/monitor/Ldac990LossGoldenTest.kt`
— eine Datei, sechs Tests, 243 Zeilen. Kein Produktivcode geaendert, keine neue
Abhaengigkeit, Fixture unveraendert.

| Test | prueft |
|---|---|
| liest als host-encodeter LDAC-Link auf HIGH | Geraet connected/active/playing; LDAC 96 kHz/32 bit/STEREO; `mCodecSpecific1 = 1000`; `isOffloaded = false`; keine Parser-Warnung |
| Zaehler verbatim aus dem A2DP-Block | flushed 0, dropped 1851, dropouts 74, max dropped 26, underflow 623, underflow bytes 637952, enqueue/dequeue/readbuf 229391/532363/740475 |
| LDAC-Block meldet HIGH bei 990 | quality mode `HIGH`, 990 kbps, Effective MTU 883, saved queue 11, `isAdaptive = false` |
| gepinnte Stufe druckt keine ABR-Zeilen, und es werden keine erfunden | keine `LDAC adaptive bit rate`-Zeile **und** `adaptiveBitrateIndex`/`adaptiveBitrateAdjustments` **null**, nicht 0 |
| Probe reicht die Zahlen als host-encodete Messung weiter | `A2dpTxProbe.readOnce`: `HOST_ENCODED`, `unavailable == null`, 1851/74 und 990 kommen unveraendert an |
| Panel zeigt gepinnte 990, vom Stack bestaetigt | `LdacState.from(...)`: `HIGH_QUALITY`, nominal 990, gemessen 990, `Honesty.MEASURED`, nicht adaptiv |

## Bodenwahrheit — aus der Datei gelesen, nicht aus der README

```
2311  Counts (enqueue/dequeue/readbuf)  : 229391 / 532363 / 740475
2313  Frames per packet (total/max/ave) : 2763962 / 12 / 12
2314  Counts (flushed/dropped/dropouts) : 0 / 1851 / 74
2315  Counts (max dropped)              : 26
2317  Counts (underflow)                : 623
2318  Bytes (underflow)                 : 637952
2335  LDAC quality mode                 : HIGH
2336  LDAC transmission bitrate (Kbps)  : 990
```

`grep -in adaptive` ueber die ganze Datei: **null Treffer** (3134 Zeilen).
`Counts (underflow)` steht dreimal in der Datei — 2317 (A2DP, 623), 2663 und
2706 (Hearing-Aid- bzw. LE-Audio-HAL, beide 0); dass der Test die 623 findet,
belegt zugleich, dass die Abschnittsgrenze haelt. LDAC fehlt in
`codecConfigOffloading` (nur SBC, AAC, Opus) — die Zaehler sind echte Zahlen.

**Abweichung zur README: keine.** Alle fuenf Angaben stimmen.

## Rot-vorher-Beleg — vier Mutationen, einzeln angewandt und zurueckgespielt

| Mutation in `A2dpLinkDumpParser.kt` | rot geworden |
|---|---|
| M1 Zaehler-Schluessel verfaelscht (`...dropout)`) | Zaehler-Test, Probe-Test — `expected:<0> but was:<null>` |
| M2 Bitrate fest auf 660 | LDAC-Block, Probe, Panel — `expected:<990> but was:<660>` |
| M3 `abrIndex` default `null` → `0` | ABR-Test — `must be absent and not 0, expected null but was:<0>` |
| M4 `isOffloaded` fest `true` | Link-Test, Probe — `expected:<HOST_ENCODED> but was:<OFFLOADED>` |

Jeder der sechs Tests ist von mindestens einer Mutation rot geworden. Nach dem
Rueckspielen ist `git diff -- core-monitor` leer, der Produktivcode bytegleich
mit HEAD. Ohne Verfaelschung: **6 Tests, 0 failures**.

**Einschraenkung, vom `developer` selbst benannt:** Die Textpruefung in Test 4
(kein `LDAC adaptive bit rate` im Dump) haengt an der Fixture, nicht am Parser —
sie bliebe bei kaputtem Parser gruen. Sie ist der Bodenwahrheits-Anker; die
mechanismus-gebundene Haelfte desselben Tests sind die beiden `assertNull`, und
die sind ueber M3 belegt.

## Verdikt-Maschine: nicht vorhanden — selbst nachgeprueft

`grep -rn "CANNOT_TELL\|OCCASIONAL\|DISTURBED\|SETTLING\|CLEAN" --include=*.kt .`
→ **fuenf Treffer, alle in Kommentaren**. Kein `enum class` mit diesen Werten.
`grep -rni verdict` → nur ADB-/Helper-Code, nichts am Audiopfad.
**V-1..V-7 sind nicht gebaut, und der `developer` hat sie nicht gebaut.**

## Warum Teil 2 ausfaellt — der wichtigste offene Punkt

Was es stattdessen gibt, ist `A2dpTxDelta.lossByChannel` / `hasLoss` mit
`TxLossChannel` (`LinkLiveModels.kt:594 ff.`). Dort ist R-D tatsaechlich
verankert — `underflows` ist bewusst kein Kanal.

**Die Fixture kann dort nicht hindurch:** `lossByChannel` lebt auf einem
**Delta zwischen zwei Lesungen**. Die Fixture ist **ein** Dump. Eine zweite
Lesung herzustellen hiesse entweder Zahlen von Hand zu setzen — exakt der
blinde Fleck, den T-034 schliessen sollte — oder eine zweite Fixture
anzubinden, was die Scope-Grenze verbot.

**R-D bleibt damit nur gegen handgesetzte Zaehler belegt** (`A2dpTxProbeTest`).

Als erreichbare Zwischenstufe hat der `developer` `A2dpTxProbe.readOnce`
mitgetestet — die Schicht, die nach korrektem Parsen die Zaehler noch wegwerfen
kann. Echte Fixture, keine erfundene Zahl. **Vom Director als im Scope
bestaetigt.**

## Testlauf

`./gradlew.bat test --rerun-tasks` → **BUILD SUCCESSFUL**, 2482 Tests,
0 failures. Basislinie eigenhaendig gegengemessen: ohne die neue Datei 2470,
mit ihr 2482 — Differenz **exakt 12** = 6 Tests x (Debug + Release).
**Die in Projektnotizen gefuehrten „2390“ passen zu dieser Zaehlweise nicht**
und stammen aus einem aelteren Stand; `docs/state.md` ist entsprechend
korrigiert.

## Funde an den Director — vom Director bearbeitet

1. **„Wird von keinem Testcode geladen“ war unpraezise.** `FixtureSweepTest`
   zaehlt ueber `RepoTree.dumpFixtures` das **Verzeichnis** auf und schickt die
   Datei durch jeden Parser — aber nur gegen Invarianten („wirft nicht,
   erfindet nichts“), die auch gelten, wenn jeder Zaehler `null` liest. Der
   Befund bleibt gueltig, aber die genauere Fassung lautet: **die Datei war
   geladen und unbehauptet.** Uebernommen.
2. **Widerspruch zu „Korrektur 1“ in `docs/state.md`.** Behandelt, siehe
   `docs/state.md` — die Begruendung war ueberdehnt, der Schluss haelt.
3. **R-D auf Echtdaten** braucht eine Doppelaufnahme derselben Sitzung. Die
   aeltere T-022-Lesung existiert nicht mehr (`C:\Users\Daniel\` haelt nur noch
   t027-, t029- und t032-rawdata; vom Director geprueft). **Loesung: als
   Anforderung in die kommende Messung T-036 aufgenommen** statt als eigener
   Task.
4. **Branch heisst `master`, nicht `main`.** Vom Director bestaetigt
   (`git branch --show-current` → `master`). Kein Handlungsbedarf; die
   Projektnotizen, die von `main` sprechen, meinen das Elternverzeichnis.
5. **Tippfehler in der Commit-Message** („gepruefta“). Kein Amend — bei
   bewegtem HEAD wuerde das fremde Commits umschreiben. **Bleibt so.**
6. Arbeitsbaum unsauber durch Director-Dateien — erwartet, kein Befund.
7. **Zeilenenden:** `A2dpLinkDumpParser.kt`, `GOAL.md`, `docs/state.md` liegen
   mit LF im Arbeitsbaum, der Rest CRLF. Vorbestehend. Zurueckgestellt.
8. **Kein Sicherheitsfund.** Keine Secrets, keine neue Abhaengigkeit, keine
   Verbreiterung der Angriffsflaeche. Die im Test erwartete MAC ist der bereits
   redigierte Wert aus der Fixture.

## An den `qa-engineer`

- Retest: `./gradlew.bat :core-monitor:testDebugUnitTest --tests "*Ldac990LossGoldenTest*" --rerun-tasks`,
  `JAVA_HOME=~/tools/jdk/jdk-21.0.12.1+1`, `ANDROID_HOME=~/tools/android-sdk`.
- **Die vier Mutationen gegenpruefen** — das ist der Kern gegen QA-012.
- Nicht abgedeckt, ausdrueckliche Luecke: R-D auf echten Daten.
- Reine JVM-Unit-Tests, kein Geraet, kein Emulator, kein Robolectric noetig.
