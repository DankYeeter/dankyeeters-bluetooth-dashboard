## Hier geht es weiter

**Autonomer Lauf 22.09. 23:51 – 23.09. 04:30 beendet**, Branch
`auto/2026-09-23`, PR offen, **nicht gemerged** (Merge beim Nutzer).
Gebaut und abgenommen: T-040..T-042 (Bereinigung; Zyklus gesamt inkl. neuem
Feature `git diff --shortstat 5f62b15..60e7a44` ohne Schemas: +2534/−3184 in
Code/Build; 7 Abhaengigkeits-Deklarationen entfernt, 1 Testabhaengigkeit neu), T-041 Umbauten AD-026..028, T-039 AK-17
Beobachtungslauf, T-044 Fixe + D-001 (DataStore-Sperre). Pruefphase T-043:
Security PASS, Design ship-ready; QA-Retest T-044e **PASS**. Suite **2498/0**
(Director 23.09. 04:14, `4507182`, `clean test --no-build-cache`;
QA 04:23/04:25 je 2498/0). Retrospektive: `docs/lessons.md` BD-L-004..006,
drei Vorschlaege fuers Agenten-Repo beim Nutzer.
**T-045 Bestandsaufnahme** (`docs/berichte/T-045-architect.md`): von AK-8..16
nur AK-10 gebaut (leer), AK-8/9/15 teilweise, **AK-11..14 und AK-16 offen** —
Saeule 3 fehlt. Schnitt P-1..P-9.

**Zyklus 4 (Session 23.09. 16:35, Branch `auto/2026-09-23-saeule3`, auf dem
offenen PR-Branch aufgesetzt): Saeule 3 bauen, alles ohne Geraet, sammeln statt
Release.** **Bau abgeschlossen (Gate):** T-046 (Device test raus, Pause >
`RUN_GAP_MAX` beendet den Lauf, P-1, Whitelist-KDoc) und T-047 Saeule 3
(AD-030..038; S3-1..S3-6 plus T-047j) auf `8d98a80`. Suite **2607/0**
(Director 19:14, `6a92e20` = Code-Stand von `8d98a80`, `clean test
--no-build-cache`). Code seit `deda17e`: 58 Dateien, +4380/−1144 (`*.kt`,
`*.xml`). Security-Vorlauf CONCERNS, Auflagen M1–M18 eingebaut
(`docs/berichte/T-047b-security-reviewer.md`). **Offen vor Pruefphase:**
Wortlaute, die S3-5/S3-6 selbst gesetzt haben (`docs/berichte/T-047i-developer.md`,
T-047h-Antwort: Dialog „Back to before", Banner bei unlesbarem Ledger,
Pin-Satz, „Start Arm B", „device discovery") → ui-ux-designer Review;
Pruefphase mit QA am Quellstand; Geraeteabnahme zusammen mit T-036.
Nicht gebaut: Schutz, falls ein Arm nach Stufen-/Codecwechsel ungepinnt
neu startet (T-047i an Director) — Beschlossen, nicht beauftragt.
Nutzer 19:16: „Workarounds" und zweiter Rueckweg-Knopf bleiben.
**Geraetesession 23.09. 18:00–22:57 (ohne Nutzerhandgriffe):**
- T-048 QA-Pruefliste (APK `bb8fbe2`): 9 PASS, FAIL durch F-012 (P2);
  4/5b aus Zeit, 3c ohne Nutzer nicht geprueft. F-012 behoben (T-050,
  `b4d39e3`), Retest T-050r am Geraet PASS (Arm-A-Teil nicht pruefbar).
- T-049a Inventur `docs/perf/T-049a-inventur.md` (F-011 siehe unten).
- **T-036 Trennmessung** `docs/perf/T-036-trennmessung.md`: 990 gepinnt,
  **mit** 2,4-GHz-WLAN 26,4 min: 19 `dropouts` in einem Cluster plus
  Reconnect (Lauf 1 verworfen, lief in Verlustphase); **ohne** 30,25 min:
  0 Verluste, 0 BQR, 0 Reconnects. Loest T-029/T-032 zugunsten WLAN-2,4-GHz
  als Ausloeser (je Arm ein Lauf). **Doppelaufnahme AK-T009-24 nicht
  hergestellt** (Episode zu kurz).
- **T-037** `docs/perf/T-037-callback-probe.md`: strukturell blockiert —
  `BluetoothAdapter` ist unter rohem `app_process` `null`; braucht echten
  App-Bootstrap oder AIDL (Bau-, kein Messschritt) → architect.
- Suite nach T-050: 2610/0 (developer-Angabe, nicht nachgemessen).

**Nutzerentscheide 28.09. 22:2x (Zyklus 5):** Ablauf: erst Pruefphase + Fix
ohne Nutzer, dann Geraetetermin mit Nutzer (Handgriffe 3c, ggf. T-008,
Wireless-Debugging); sammeln, kein Release. **F-014:** Sampler an Monitor
binden, AK-4 bleibt woertlich. **Sample-Rate-Wechsel beendet den
Beobachtungslauf.** **Oboe → AudioTrack:** `GOAL.md:169` wird geaendert, wenn
W-7 am Geraet besteht; W-8 erst danach. **F-011:** neutral umformulieren, ohne
Root-Hinweis (AK-10).

**Autonomer Lauf 28.09. (Nutzer 22:22):** Obergrenze 7 h (bis ca. 05:30),
**kein Geraet**, endet pausiert auf `auto/2026-09-23-saeule3` mit PR, nie
gemerged. Warteschlange: Pruefphase T-051 → ein Fixauftrag (Befunde + drei
Nutzerentscheide) → ein Retest; bis zu drei vom Nutzer freigegebene
Kandidaten aus T-053 (`docs/product/BACKLOG.md`); Recherche Play Store T-052
(C-001, R-012, R-013). Ende: Warteschlange leer oder Budget erreicht.
**T-051a Design-Review (22:24):** ship-ready; DR-006 („Back to before“ als
Dialogtitel → „Settings put back“, `DeviceProfilesScreen.kt:1457`) und
Ersatztext Ledger-Banner (`:1429`) gehen in den Fixauftrag.

**Pruefphase T-051 zurueck (22:31):** QA FAIL (F-015, F-019, F-020 P2;
F-016..018, F-021 P3; Suite 2610/0 QA 22:17), Security PASS (SR-031 P4),
Design ship-ready. **Nutzerentscheide 22:3x:** F-015 Messphase laeuft bei
Display aus weiter — **AK-4 um Ausnahme ergaenzt** (GOAL.md, freigegeben);
F-019 LDAC-Stufe waehrend Messphase gesperrt, Rueckweg bleibt und beendet den
Vergleich; F-020 Profileditor-Codec/Stufe kommt in den Rueckweg (AK-11); P3
jetzt mitfixen. Naechst: architect (F-015/DEEP, F-020, F-021), dann
Fixlaeufe developer, dann ein Retest. Recherche: R-012, R-013 fertig, C-001 laeuft.

**Entwurf T-054 (22:51):** AD-039..043 (`7a3c341`), UI_SPEC T-054b mit
AK-T054-1..11. Nutzer 22:5x: „Forget this list“ mit Warn-Dialog ja; Hinweis
nur als Zeile; Benachrichtigung mit Symbol nur waehrend Messung ja. Nutzer ab
22:51 abwesend. **Bau T-055** (`docs/tasks/T-055.md`): Welle 1 X-1 (a) ∥
Security-Vorlauf (s), dann X-2 (b), X-3 (c), X-4 (d), ein Retest; danach
P-001/P-004, P-002, ggf. P-003.
**Stand 23:1x:** T-055a erledigt bis Teil 4 (`e9b9546`, Suite 2618/0
developer 23:02; Teil 4 Sample-Rate an X-4). T-055s CONCERNS (SR-032, SR-033,
V1–V21). T-056 Audit-Bereinigung fertig auf `t056-bereinigung` (`6587363`,
−268 Zeilen netto, Suite gruen 23:10 laut developer) — **Merge nach T-055b**.
Laufen: T-055b (Branch), T-055c (Worktree `t055c-measurement`), T-057a/b
(Entwurf Features). **Fuer den Security-Pruefdurchgang am Ende:**
`PairingTrace` loggt SPAKE2-Nachrichten und Schluessel-Fingerabdruck in jedem
Build; `Ed25519.isOnCurve` ohne main-Aufrufer, KDoc sagt „Used to reject peer
input“ (Audit Nr. 12).
**T-055b erledigt** (`4ac22ab`: AD-041/042, SR-031/032/033, F-011, DR-006,
Banner). T-056 gemergt. **Suite 2649/0** (Director 23:21, `4d5f5e6`, `clean
test --no-build-cache`). Offen daraus: Satz fuer „Verwerfen ohne Wirkung“
selbst gesetzt vom developer („Nothing was forgotten — the list could not be
changed just now.“) → ui-ux im Review; 0-Byte-Ledger liest als leer (M3-Luecke,
theoretisch) → Security am Ende. K-2 „root“: `SystemAccessScreen.kt:128`,
`SetupWizardScreen.kt:408` sagen korrekt „not root“ — kein Befund.
**23:4x:** T-055c erledigt und gemergt (`2a575b0`; MeasurementService, F-014,
DEEP-Grenze; Suite 2646/0 laut developer im Worktree). Entscheid: „Cancel“ und
„Stop“ ein Pfad (Option A, in T-055d). T-058s Security PASS (W1–W7,
SR-034..038). Laufen: T-055d (X-4, Branch), T-058a (Y-1, Worktree
`t058a-pruefstand`). Danach T-058b ∥ T-058c, dann ein QA-Retest + ui-ux-Review
der selbst gesetzten Texte + Security-Pruefdurchgang.
**23:57:** T-058a gebaut auf `t058a-pruefstand` (`117b912`, Suite 2696/0 laut
developer; `88a8898` aendert eine Zeile `ComparisonSection.kt`). Merge nach
T-055d; danach Fortsetzung T-058a: Move `readBackSentence`/`ConditionBook.shown`/
`Condition.displayName` nach `ConditionStatus.kt` (AD-044 P. 5) und
`readConditions` weg vom Main-Dispatcher (alle 2 s Settings-Lesungen auf dem
UI-Thread, ungemessen, AK-1).
**00:06:** T-055d erledigt (`94b15fe`; F-015..F-019-Oberflaeche, Sample-Rate-Ende,
Cancel=Stop als `Cancelled`, `@Volatile`, Engine-Tests haengen nicht mehr),
T-058a gemergt. **Suite 2709/0** (Director 00:06, `6be9c2f`). Fixzyklus T-055
damit gebaut; eigene Texte der developer (T-055c/d, T-058a Annahmen 1–4) →
ui-ux-Review. Laufen: T-058a-Fortsetzung (Move + Dispatcher), architect
Nachtrag `StoppedOutside`→`Cancelled`.
**00:27 — alles gebaut:** T-058b (`964cccf`, P-002) und T-058c (`ea08aed`,
P-003, `ACCESS_NETWORK_STATE`) gemergt. **Suite 2727/0** (Director 00:27,
`d6d1890`). Pruefphase **T-059** laeuft (QA, ui-ux Review, Security) auf
eingefrorenem Stand `d6d1890`. README „No INTERNET permission“ falsch → nach
der Pruefung technical-writer.
**T-057b Spec fertig (23:16):** `UI_SPEC.md:3886-4210`, AK-T057-1..10.
Zwei Designfragen im autonomen Lauf nach Empfehlung entschieden (Nutzer
nachtraeglich vorlegen): Sprungknoepfe an beiden Orten; P-001-Panel nicht
einklappbar.
**Backlog 22:3x:** Nutzer gab P-001, P-004, P-002 frei (GOAL AK-18..20),
P-003 als Nachruecker nur ohne Standortberechtigung (AK-21 bedingt);
`adaptiveBitrateIndex` streichen angenommen. Bau erst nach dem Fixzyklus
(P-002 und F-011 beruehren dieselben Dateien).
**Play Store (C-001, R-012, R-013):** kein Gesetz verbietet es; ROT
`QUERY_ALL_PACKAGES` (Alternative `<queries>` existiert), GRAU Helfer-Review und
Hoerkompensation (MDR, anwaltlich), GELB Data Safety/Datenschutzerklaerung/
Lizenzseite (Conscrypt fehlt in THIRD_PARTY_LICENSES.md); Auflagen A-001..A-020
in `docs/legal/AUFLAGEN.md`. Nutzerentscheide dazu offen (unten).

**Beim Nutzer — offen:** Play-Store-Vorlage C-001 (Monetarisierung, welche
Fassung, EU-Beschraenkung, Helfer-Restrisiko, MDR-Klaerung); Drei Retrospektive-Vorschlaege fuers Agenten-Repo
(`docs/lessons.md`); Merge des Draft-PR `auto/2026-09-23`; UI_SPEC T-047
offene Frage 3 (Rueckweg-Banner voll oder nur Knopf, Empfehlung: lassen).

**Naechste Geraetesession:** W-7..W-9 (AD-025); T-048 Punkte 3c, 4, 5b;
Saeule-3-Abnahme (Vergleich verlangt USB ab → Wireless-Debugging oder
Nutzer); AK-4 Bildschirm-aus-Messung. **Kein `dumpsys bluetooth_manager`
zur Zwischenkontrolle — leert die BQR-Queue (R-011); App und Helfer vor
jeder Messung beenden (F-014).**

**Beschlossen, nicht beauftragt:** F-004, F-005, F-009 (QA-C), SR-023,
SR-024, F-011 (Nutzertext „only a rooted phone can change“ in
`DeviceProfilesScreen.kt:316` und `BluetoothSystemControls.kt:105` — laut
T-045 falsch und Root-Hinweis gegen AK-10; **T-049a (Geraet, 20:08) fand in
den Entwickleroptionen keinen Menuepunkt fuer die drei Werte** — T-045 P-4
war insoweit falsch, der Satz steht weiter im Code; Wortlaut gegen AK-10 neu
bewerten, `docs/perf/T-049a-inventur.md`), F-013 (P3), neu-C am Geraet bestaetigt.
Messbefunde und Tuning-Grundlagen (frueher hier):
`docs/archiv/state-messbefunde-2026-09-03.md`.


