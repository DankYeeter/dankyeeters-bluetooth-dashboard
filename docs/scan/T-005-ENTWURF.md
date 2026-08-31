# T-005 — Umgebungs- und Einstellungs-Scan: Katalog und Entwurf

Rolle: `architect`. Angelegt 2026-08-31. Auftrag: `docs/tasks/T-005.md`.
Entscheidungen mit Alternativen stehen als AD-001..AD-008 in `ARCHITECTURE.md`;
dieses Dokument traegt den Katalog, das Datenmodell und den Ablauf.

**Ohne Geraetezugriff geschrieben.** Alles, was hier als *belegt* steht, stammt
aus `docs/perf/baselines.md`, `HANDOVER.md` oder aus KDoc, das eine
Geraetemessung zitiert. Nichts davon wurde in dieser Sitzung nachgemessen.

---

## 0. Die wichtigste Korrektur vorweg

Der Auftrag nennt als aussichtsreichste Spur einen **periodischen Stoerer**
(WLAN/BLE-Dauerscan, MLO auf 2,4 GHz). Diese Spur ist gut, aber sie ist nicht
die einzige Erklaerung fuer ein Stocken im ~3-s-Takt:

> **Eine reine Ueberlastung erzeugt ebenfalls eine feste Periode.** Wenn der
> geforderte Durchsatz ueber dem liegt, was die Strecke traegt, fuellt sich die
> Sendeschlange mit konstanter Rate, laeuft ueber, wird geleert und fuellt sich
> wieder — mit einer Periode, die sich aus Puffertiefe geteilt durch
> Ueberschussrate ergibt. Das ist ein Grenzzyklus, kein Stoerer.

`docs/state.md` fuehrt diese Deutung bereits selbst als "Grenzzyklus" auf.
Beide Erklaerungen sagen "periodisch" voraus. **Die Periodizitaet allein
unterscheidet sie also nicht.** Ein Scan, der das nicht trennt, wird eine
plausible Ursache anbieten und kann sie nicht belegen — genau der Fehler, den
AK-3 verbietet.

Deshalb steht am Anfang des Scans **nicht** die Umgebungserfassung, sondern ein
Diskriminator-Experiment (E-0, Abschnitt 4.3), das mit vorhandenen Mitteln und
ohne jeden neuen Zugriff entscheidet, welche Haelfte des Katalogs ueberhaupt
relevant ist.

---

## 1. Evidenzniveaus

Drei Stufen, und sie sind Teil des Datenmodells, nicht nur der Prosa:

| Stufe | Bedeutung |
|---|---|
| **BELEGT** | An *diesem* Geraet gemessen oder als Ablehnung/Read-back am Geraet gesehen. Quelle wird genannt. |
| **PLAUSIBEL** | Mechanismus ist dokumentiert oder allgemein anerkannt, an diesem Geraet aber nicht gezeigt. |
| **SPEKULATIV** | Nur Ueberlegung. Darf im Bericht erscheinen, aber nie mit einer Wirkungsaussage. |

Dazu je Eintrag: **Auslesbar durch** (und ob dafuer neuer Zugriff noetig ist),
**Nutzer aenderbar**, **App darf aendern**.

Die Rechte-Stufen fuer "App darf aendern":

- `NIE` — die App zeigt es und ruehrt es nicht an.
- `NUR GELIEHEN` — nur innerhalb eines laufenden Experiments, nur mit
  ausdruecklicher Einwilligung, immer mit garantierter Rueckgabe (Abschnitt 5).
- `FREI` — die App darf es als dauerhafte Wahl des Nutzers setzen; existiert
  heute schon (Codec-Wunsch je Geraet).

---

## 2. Katalog der Einflussgroessen

### Gruppe A — heute schon sichtbar, kein neuer Zugriff

| # | Faktor | Auslesbar durch | Evidenz der Wirkung | Nutzer | App |
|---|---|---|---|---|---|
| A-1 | **Ausgehandelte LDAC-Konfiguration: 96 kHz / 32 bit / STEREO** | `dumpsys bluetooth_manager`, `A2dpLinkDumpParser` | Konfiguration BELEGT (`baselines.md`); Wirkung auf Stabilitaet SPEKULATIV — siehe Warnung unten | ja, ueber die App | FREI (bestehende `CodecPreference.sampleRateHz`) |
| A-2 | **Geforderte Bitratenstufe (990 / 660 / 330 / ABR)** | `A2DP LDAC State:`-Block, `LdacState` | **BELEGT**: 990 stockt im ~3-s-Takt, auch bei force-gestoppter App; ABR erreicht 990 nie von selbst und pendelt 492–660 | ja | FREI (bestehende Chips) |
| A-3 | **Effective MTU** (gemessen 883) | derselbe Block | PLAUSIBEL: kleinere MTU = mehr Pakete je Sekunde fuer dieselbe Rate = weniger Reserve | nein | NIE |
| A-4 | **Sendeschlange und Zaehler von `btif_a2dp_source`** (`saved transmit queue length`, Underflow, Enqueue-/Dequeue-Deviation) | derselbe Dump, `A2dpTxStats` | **BELEGT als Instrument**: Streuung und 2sd-Schwellen in `baselines.md` | — | — (Messgroesse, kein Schalter) |
| A-5 | **Offload-Zustand des Streams** | `codecConfigOffloading`, `LinkObservability` | **BELEGT**: dieser Strom wird host-seitig kodiert, die Zaehler leben. `A2dpOffloadEnabled: true` ist nur eine Faehigkeitsangabe | nein | NIE (Abschalten ist verifiziert unmoeglich, A-13) |
| A-6 | **Weitere gleichzeitig verbundene Geraete und aktive Profile** | `dumpsys bluetooth_manager` + `BluetoothProfile` (BLUETOOTH_CONNECT liegt vor) | PLAUSIBEL, gut dokumentiert: alle teilen sich einen Funk und dieselben Zeitschlitze. **Der konkrete Zustand ist bisher nicht ausgelesen** (3 gekoppelt, verbunden unbekannt) | ja, in den Systemeinstellungen | NIE (Trennen fremder Geraete ist nicht Sache der App) |
| A-7 | **Unsere eigene EQ-/DP-Kette und fremde EQs** | `ForeignEqDetector`, `EqCandidateScanner`, `dumpsys media.audio_flinger` | Vorhandensein BELEGT; Wirkung **auf die Funkstrecke** SPEKULATIV — ein Effekt kostet CPU und Latenz, keine Luftrate. Der 990-Befund trat bei force-gestoppter App auf, also ohne unsere Kette | ja | FREI (eigene Kette), NIE (fremde) |
| A-8 | **Samplerate-Konvertierung im AudioFlinger** (Track-Rate vs. Ausgangs-Thread) | `dumpsys media.audio_flinger`, `AudioFlingerTrackParser` | PLAUSIBEL: Resampling kostet CPU und kann Latenzspitzen erzeugen; keine Wirkung auf die Luftrate | teils (Quellmaterial) | NIE |
| A-9 | **RSSI, Entfernung, Abschattung** | `DumpsysBluetoothParser` (RSSI) | Mechanismus BELEGT allgemein; der Messwert ist grob und wird heute nicht waehrend eines Laufs verfolgt | ja (hinlegen, naeher gehen) | NIE |
| A-10 | **Absolute Volume, AVRCP-Version, MAP/PBAP** | `BluetoothDeveloperOptions`, Settings.Global | **Keine bekannte Wirkung auf Durchsatz.** Steht im Katalog, damit die Frage aufhoert | ja | FREI (bestehend) |
| A-11 | **Energiesparmodus** | `PowerManager.isPowerSaveMode()` — **keine Permission noetig** | PLAUSIBEL | ja | NIE |
| A-12 | **Thermische Drosselung** | `PowerManager.getCurrentThermalStatus()` (API 29+) — **keine Permission noetig** | PLAUSIBEL | nein direkt | NIE |
| A-13 | **A2DP-Hardware-Offload, HCI-Snoop-Log, max. verbundene Audiogeraete** | `AndroidSystemProperties` (liest bereits) | Werte BELEGT lesbar; **Aenderung BELEGT unmoeglich**: `setprop persist.bluetooth.a2dp_offload.disabled false` wurde am Geraet mit "Failed to set property" abgelehnt, auch fuer die Shell-Domain | nur mit Root | NIE |
| A-14 | **Waehlbare Codec-Menge des Kopfhoerers** (die "ungenutzte Reserve" aus T-003) | `codecStatus`-Operation, `selectable` | BELEGT, dass sich die Menge zwischen Verbindungen aendert (aktenkundig). Die aktuelle Aushandlung steht bereits am oberen Ende (96 kHz/32 bit) — es gibt hier **keine ungenutzte Reserve nach oben**, sondern eher eine nach unten | ja | FREI |

> **Warnung zu A-1, und sie ist wichtig genug fuer eine eigene Zeile.**
> Die Versuchung ist gross, 48 kHz statt 96 kHz als Entlastung des Funks zu
> empfehlen. **Das waere falsch.** `LdacState.nominalKbps` haelt fest: die
> 48/96-kHz-Familie faehrt dieselbe Leiter 990/660/330. 48 kHz sendet also
> **genau dieselbe Luftlast** wie 96 kHz. Es aendert nur, *was* diese Bits
> tragen (halb so viele Samples, doppelt so viele Bits je Sample) und wieviel
> der Encoder und der Resampler zu tun haben.
> Konsequenz fuer den Bericht: 48 kHz ist ein **Qualitaets**-Hebel, kein
> **Stabilitaets**-Hebel. Wenn 990 nicht durchpasst, passt es bei 48 kHz auch
> nicht durch.

### Gruppe B — braucht neuen Lesezugriff oder ist noch nie gelesen worden

| # | Faktor | Auslesbar durch | Evidenz der Wirkung | Nutzer | App |
|---|---|---|---|---|---|
| B-1 | **WLAN-Band des aktiven Links und MLO-Links** (2437 MHz vs. 5500 MHz; affiliierte Links auf Kanal 6 und 100) | **neu**: eine typisierte Helper-Operation ueber `cmd wifi status` (AD-005) | Mechanismus **PLAUSIBEL und gut dokumentiert** (2,4-GHz-Koexistenz, geteilte Antenne, PTA); fuer dieses Geraet zum Stockzeitpunkt **nicht belegt** — welches Band um 19:41 aktiv war, ist unbekannt | nur ueber den Access Point | NIE |
| B-2 | **`wifi_scan_always_enabled` = 1** | Settings.Global **lesen braucht keine Permission** | PLAUSIBEL. Aber: diese Suchlaeufe laufen im Minutenraster, nicht im 3-s-Raster — als Erklaerung fuer *diesen* Takt schwach | ja (Einstellungen) | NUR GELIEHEN |
| B-3 | **`ble_scan_always_enabled` = 1 und wer tatsaechlich scannt** | Setting frei lesbar; die Scan-Klienten **nur** soweit der `bluetooth_manager`-Dump sie druckt — ob dieser Build das tut, ist **unbekannt** | PLAUSIBEL, und von allen Kandidaten der mit der **passendsten Kadenz**: die AOSP-Scan-Duty-Cycles liegen bei rund 0,5–5 s je Fenster. Das ist die Groessenordnung des beobachteten Takts | ja | NUR GELIEHEN |
| B-4 | **LE Audio aktiv / Dual-Mode** | `dumpsys bluetooth_manager`, `BluetoothProfile` | Strukturell BELEGT: laeuft das Geraet ueber LE Audio, ist A2DP gar nicht im Spiel und der halbe Katalog ist gegenstandslos. Ob das hier zutrifft: bisher nicht geprueft | ja | NIE |
| B-5 | **Anzahl gleichzeitig verbundener Geraete zum Laufzeitpunkt** | wie A-6, aber als Laufkontext festgehalten | siehe A-6 | ja | NIE |

### Gruppe C — nicht auslesbar, nur ueber ihre Wirkung

Entfernung durch Waende, Koerperabschattung, fremde Netze und Bluetooth-Geraete
in der Umgebung, Mikrowelle, ein Zug voller Telefone am Hbf. Die App kann
darueber **nichts** sagen ausser: die Verlustrate ist gestiegen und an diesem
Telefon hat sich nichts geaendert. Genau dieser Satz ist der ehrliche Befund,
und er ist mehr wert als eine erfundene Ursache.

**Regel:** Gruppe C erzeugt nie einen benannten Faktor, sondern hoechstens den
Restbefund "keiner der geprueften Faktoren erklaert das — dann kommt es von
aussen". Dieser Restbefund darf nur erscheinen, wenn die Abdeckung hoch war
(Abschnitt 4.5).

---

## 3. Datenmodell eines Befunds

Formen, keine Endgueltigkeit im Detail. Der `developer` legt die Typen an, die
Konstanten kommen aus T-001.

```kotlin
/** Wie gut ein Befund gestuetzt ist. Teil des Modells, nicht der Prosa. */
enum class Evidence {
    /** Gelesen. Sagt was der Zustand ist, nie was er bewirkt. */
    OBSERVED,
    /** In diesem Lauf gemessen: A/B/A mit einer Zahl und einer Schwelle. */
    MEASURED_EFFECT,
    /** Quelle war da, Wert war nicht zu holen. Traegt den Grund woertlich. */
    CANNOT_CHECK,
    /** Vorbedingung fehlte (keine Musik, kein Helper, Link ausgelagert). */
    NOT_APPLICABLE,
}

/** Was ueber die Wirkung eines Faktors ueberhaupt behauptet werden darf. */
enum class EffectClaim { MEASURED_HERE, DOCUMENTED_ELSEWHERE, MECHANISM_ONLY }

/** Statischer Eintrag im Katalog. Ein Objekt-Registry wie BluetoothDeveloperOptions. */
data class Factor(
    val id: FactorId,
    val title: String,
    val claim: EffectClaim,
    val userChangeable: Boolean,
    val appMayChange: ChangeRight, // NEVER | BORROW_ONLY | FREELY
)

/** Eine Lesung. Nie ein blankes Boolean, nie ein stiller Default. */
sealed interface Reading {
    data class Value(val raw: String, val display: String) : Reading
    data class Unreadable(val reason: String) : Reading
    data object NotApplicable : Reading
}

data class Finding(
    val factor: FactorId,
    val reading: Reading,
    val evidence: Evidence,
    /** Nur und ausschliesslich bei MEASURED_EFFECT belegt. */
    val effect: EffectMeasurement? = null,
    val action: Action?,
)

data class EffectMeasurement(
    val metric: String,          // z.B. "enqueue overdue /min"
    val armBefore: ArmResult,
    val armChanged: ArmResult,
    val armAfter: ArmResult?,    // die Rueckkehr, siehe A/B/A
    val deltaPercent: Double,
    val thresholdPercent: Double,
    val verdict: EffectVerdict,  // WORSE | BETTER | BELOW_NOISE | INCONCLUSIVE
)

sealed interface Action {
    /** Die App kann es selbst, dauerhaft, als Wahl des Nutzers. */
    data class AppCanChange(val label: String, val apply: suspend () -> Unit) : Action
    /** Nur der Nutzer kann es. Traegt einen Deep-Link in den richtigen Screen. */
    data class UserMustChange(val instruction: String, val intent: String?) : Action
    /** Nichts zu tun — in Ordnung, oder in niemandes Hand. */
    data object None : Action
}

data class ScanReport(
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val deviceKey: String?,      // gehasht, nie eine rohe MAC
    val context: RunContext,     // Codec, Rate, Modus, MTU, RSSI-Spanne, Thermik, spielende App
    val findings: List<Finding>,
    val coverage: Coverage,      // gelesen / lesbar gewesen — siehe 4.5
    val restoreClean: Boolean,   // false ⇒ lauter Hinweis, siehe 5
)
```

**Drei Regeln, die das Modell durchsetzt und nicht nur beschreibt:**

1. `effect != null` ist nur mit `evidence == MEASURED_EFFECT` konstruierbar.
2. Ein Text an einem Befund mit `OBSERVED` darf **keine** Wirkungsaussage
   enthalten. Die Wirkung steht getrennt und getrennt formuliert am `Factor`
   (`claim`), nicht am `Finding`. Das ist die Trennung, die AK-3 verlangt.
3. `Reading.Unreadable` traegt den Grund **woertlich aus der Datenschicht**,
   wie es die Live-Ansicht heute schon mit ihren `warnings` tut.

---

## 4. Ausfuehrungsmodell

### 4.1 Zwei Phasen, sehr unterschiedliche Kosten

| Phase | Dauer | Aendert etwas | Braucht Musik | Ergebnis |
|---|---|---|---|---|
| **1 — Bestandsaufnahme** | ~2 s | nein | nein | `OBSERVED`-Befunde ueber alle lesbaren Faktoren |
| **2 — Belege** | Minuten, je Experiment einzeln freigegeben | ja, geliehen | ja | `MEASURED_EFFECT`-Befunde mit Zahl und Schwelle |

Phase 1 ist fuer sich lieferbar und beantwortet bereits "wie sieht mein Telefon
gerade aus". Phase 2 ist das, was aus einer Aufzaehlung einen Befund macht.

### 4.2 Kein Dauerbetrieb (AK-4)

Der Lauf ist eine Coroutine im Scope des Bildschirms, der ihn gestartet hat —
dieselbe Bauform wie `DeviceDiagnosticRunner`, ausdruecklich **nicht** wie
`MonitorEngine`. Kein WorkManager, kein Service, kein Timer, keine Wiederholung.
Bildschirm weg ⇒ Lauf abgebrochen ⇒ Rueckgabe (Abschnitt 5) laeuft trotzdem.

### 4.3 E-0 — der Diskriminator, und warum er zuerst laeuft

Frage: erzeugt die geforderte Rate den 3-s-Takt, oder ein Stoerer?

| | H1 Ueberlastung (Grenzzyklus) | H2 Periodischer Stoerer |
|---|---|---|
| Periode bei 990 vs. 660 | aendert sich stark oder verschwindet | bleibt aehnlich, nur die Tiefe aendert sich |
| `LDAC saved transmit queue length` zwischen zwei Aussetzern | steigt monoton, bricht am Aussetzer ein | bleibt flach, springt nur am Aussetzer |
| Wirkung eines Umgebungs-Experiments | keine | messbar |

Ablauf: je 60 s bei gepinnt 990 und bei gepinnt 660, Zeitreihe der
Schlangenlaenge und der Verlustzaehler mit **identischer Kadenz** in beiden
Armen.

Das kostet **keinen neuen Zugriff**: Pinnen kann die App, die Schlangenlaenge
und die Zaehler stehen in einem Dump, den der Helper schon lesen darf, und
`baselines.md` Block 1 hat gemessen, dass `dumpsys bluetooth_manager` selbst bei
0,5-s-Kadenz **keine** Verlustmetrik verschlechtert. Der Beobachter ist bezahlt.

E-0 entscheidet, ob Gruppe B ueberhaupt eine Rolle spielt. Gewinnt H1, lautet
die Antwort auf den 990-Befund: *"990 passt auf dieser Strecke nicht durch"* —
und die WLAN-Spur ist eine Nebensache, an der man Wochen verlieren kann.

### 4.4 Der Experiment-Ausfuehrer

```
runExperiment(exp):
  1. Vorbedingungen: Musik laeuft, Link host-kodiert, Helper da, Geraet verbunden
  2. beim Monitor anmelden                     <- D-7, siehe 4.6
  3. armBefore  = beobachte(FENSTER)
  4. leihen(exp.change)                         <- Eintrag ins Ledger VOR dem Schreiben
  5. armChanged = beobachte(FENSTER)
  6. zurueckgeben()                             <- immer, auch bei Abbruch
  7. armAfter   = beobachte(FENSTER)
  8. beim Monitor abmelden
  9. Urteil = vergleiche(armBefore, armChanged, armAfter, Schwelle)
```

Vier Eigenschaften, jede mit Grund:

- **A/B/A statt A/B.** Die Umgebung driftet — jemand geht vorbei, das Telefon
  wird umgelegt. Weichen `armBefore` und `armAfter` staerker voneinander ab als
  die Schwelle, ist der Lauf `INCONCLUSIVE`. Das ist ein haeufiges und voellig
  legitimes Ergebnis, und es muss billig zu melden sein.
- **Gleiche Kadenz in allen Armen.** Dann ist der Beobachter ein konstanter
  Offset und faellt beim Vergleich heraus. Genau die Methodik, die
  `baselines.md` bereits fuer sich selbst begruendet — sie wird uebernommen,
  nicht neu erfunden.
- **Kumulative Zaehler werden differenziert, nicht abgetastet**, wo immer die
  Frage das zulaesst. Nur E-0 braucht eine echte Zeitreihe, und sagt das.
- **Genau eine Variable je Experiment.** Zwei gleichzeitig geaenderte Dinge
  ergeben eine Zahl, die niemandem gehoert.

**Schwellen.** Ausgangswert sind die 2sd-Schwellen aus `baselines.md`
(±1,03 % enqueue-overdue, ±1,80 % dequeue-overdue). Diese stammen aus dem
**ungestoerten** Regime. In einem gestoerten Regime ist die Streuung groesser,
und dieselbe Schwelle wuerde Urteile erfinden. Regel: ein Urteil braucht
`delta > max(2sd_baseline, 2sd_beobachtet_im_Arm)`. Reicht ein Arm fuer keine
eigene Streuungsschaetzung, ist das Ergebnis `INCONCLUSIVE`, nicht "kein
Unterschied".

### 4.5 Umgang mit Nichtwissen

Uebernommen aus UI_SPEC ("Coverage"): der Bericht traegt, wieviele Faktoren
gelesen werden konnten und welche nicht. Ein Lauf ohne Helper liest Gruppe B
gar nicht — er meldet das und spricht **keinen Freispruch** aus. Der Restbefund
"dann kommt es von aussen" (Gruppe C) ist nur zulaessig, wenn die Abdeckung
vollstaendig war; sonst lautet er "wir haben X von Y Faktoren nicht gesehen".

### 4.6 D-7 — Anmeldung beim Monitor

Jeder Lauf, der irgendetwas aendert — auch ein Settings-Leihvorgang, nicht nur
ein Pin —, meldet sich vor der ersten Aenderung an und nach der letzten ab.
`LdacTuning` haelt bereits den Ort dafuer (`state.busy`); es bekommt einen
Zaehler offener Umschaltvorgaenge und den Zeitstempel der letzten Aenderung,
genau wie D-7 es beschreibt. **Ein zweiter Anmeldeweg im Scan-Modul ist
ausgeschlossen** — zwei Stellen, die den Latch offen lassen koennen, sind eine
zu viel.

---

## 5. Was die App aendern darf — und die Rueckgabe-Garantie

### Die drei Stufen

- **FREI**, weil es schon so ist: Codec-Wunsch je Geraet inklusive
  `sampleRateHz`, `bitsPerSample`, `ldacQuality`. Bestehende Operation,
  bestehendes Einwilligungsmodell, wird im Geraeteprofil gespeichert.
- **NUR GELIEHEN**, neu und eng: genau zwei Schluessel,
  `wifi_scan_always_enabled` und `ble_scan_always_enabled`, ausschliesslich
  innerhalb eines laufenden Experiments, nie als gespeicherte Praeferenz.
- **NIE**: Systemeigenschaften (verifiziert unmoeglich), WLAN-Band und MLO,
  Trennen fremder Bluetooth-Geraete, LE Audio, Adapter-Neustart als
  automatischer Schritt, alles was mit Standortdiensten ueber diese zwei
  Schluessel hinausgeht.

### Der Widerspruch, den das aufloest

`BluetoothDeveloperOptions` haelt heute ausdruecklich fest, `ble_scan_always_enabled`
sei "eine Standorteinstellung, keine Audioeinstellung", und diese App habe dort
"nichts zu suchen". Dieser Entwurf **widerspricht dem in einem eng begrenzten
Punkt** und muss das benennen (AD-006): der alte Satz gilt fuer das *dauerhafte
Anbieten als Profileinstellung*. Was hier vorgeschlagen wird, ist ein
befristetes, eingewilligtes, garantiert zurueckgegebenes Leihen fuer die Dauer
einer Messung. Ohne diese Moeglichkeit gibt es zu B-2 und B-3 **nie** einen
Beleg, sondern immer nur eine Vermutung — und eine Vermutung darf die App laut
AK-3 nicht als Wirkung praesentieren.

### Das Ledger

Ein `BorrowedSettingsLedger`, persistiert, in `:core-system` bei den anderen
Stores:

```
Eintrag: { key, previousValue, wasUnset, runId, writtenAtMs }
```

Regeln:

1. Eintrag wird **vor** der Aenderung geschrieben und synchron durchgeschrieben.
2. Nur Schluessel aus einer festen Positivliste duerfen ins Ledger.
3. Rueckgabe im `finally` um den gesamten Lauf, also auch bei Abbruch und
   Bildschirmwechsel.
4. **Zusaetzlich beim App-Start**: ist das Ledger nicht leer, wird
   zurueckgegeben und dem Nutzer gesagt, was zurueckgegeben wurde und warum.
   Das ist der Fall "Prozess wurde mitten im Arm gekillt", und er ist der
   einzige Grund, warum das Ledger ueberhaupt auf Platte liegt.
5. Scheitert eine Rueckgabe, sagt der Bericht das ganz oben, und die App sagt
   es weiter, bis es gelingt. Ein Werkzeug, das Einstellungen aendert und nicht
   sagen kann, was es hinterlassen hat, ist schlimmer als eines, das nichts
   aendert.

---

## 6. Verschmelzung mit dem Treppen-Optimierer (T-003)

**Empfehlung: ein Ding, mit dem Scan als Rahmen — aber ohne eigenen
Optimize-Knopf.**

Begruendung:

1. Der Optimierer braucht **exakt dieselben vier Mechanismen** wie der Scan:
   D-7-Anmeldung, armweiser Vergleich gegen eine Streuungsschwelle, garantierte
   Rueckgabe, ehrliches `INCONCLUSIVE`. Zweimal bauen heisst, zwei Muster fuer
   dasselbe Problem zu haben.
2. Der Bitraten-Teil ist fuer den Scan **nicht optional, sondern notwendig**:
   E-0 *ist* eine Treppe mit zwei Stufen. Ohne sie kann der Scan die Ursache
   des belegten 990-Befunds nicht bestimmen.
3. Die Reihenfolge, nach der der Auftrag fragt, ist damit beantwortet und sie
   ist **nicht** "erst Umfeld raeumen, dann Treppe": erst der Diskriminator
   (zwei Stufen, billig), dann — nur falls er auf die Umgebung zeigt — die
   Umgebungs-Experimente, und **danach** gegebenenfalls die volle Treppe unter
   den geraeumten Bedingungen. Andernfalls fuende die Treppe eine Decke, die
   ein Faktor gesetzt hat, den der Scan gleich darauf entfernt haette.
4. **Kein Optimize-Knopf und kein automatisches Pinnen am Ende.** Der App
   Designer hat den Optimierer zurueckgestellt; ausserdem widerspricht ein
   dauerhaftes Pinnen durch die App der Rueckgabe-Regel. Der Lauf endet mit
   einer Zahl und den vorhandenen Chips — antippen tut der Nutzer.

Der Treppen-Optimierer verschwindet damit als *Feature* und ueberlebt als
*Maschine*. Will der App Designer ihn spaeter doch, ist er eine duenne
Oberflaeche auf demselben Ausfuehrer.

---

## 7. Machbarkeitsurteil: eigene Codecs und Firmware-Modding

Kurz, wie beauftragt.

- **Eigener A2DP-Codec: nein, ohne Wenn und Aber.** Die Codec-Liste ist in
  `libbluetooth` einkompiliert; die einzige Schnittstelle nach aussen ist
  `setCodecConfigPreference` mit bekannten Codec-Ids, und `cmd bluetooth_manager`
  bietet nur `enable/disable/enableBle/disableBle/factoryReset/wait-for-state`
  (am Geraet geprueft, aktenkundig in `BluetoothDeveloperOptions`). Vendor-Codecs
  sind signierte Systemkomponenten. Eine App mit Shell-Uid kann dort nichts
  registrieren.
- **Der ABR-Regler selbst: nicht erreichbar.** Er sitzt in `libldacBT_abr`
  innerhalb des Bluetooth-Prozesses. Es gibt keinen Settings-Schluessel, keine
  Systemeigenschaft und kein Shell-Kommando dafuer. Die einzige nach aussen
  gefuehrte Stellschraube ist `mCodecSpecific1` — die vier Modi. Die
  Vorabannahme des Directors ist damit **bestaetigt**.
- **A2DP-Offload abschalten: verifiziert unmoeglich.** `setprop` wurde am
  Geraet abgelehnt, auch fuer `u:r:shell:s0`. Der ganze Zweig "DSP aus dem
  Signalweg nehmen" ist ohne Root geschlossen.
- **Kopfhoerer-Firmware:** ausserhalb dieses Projekts (T-004, zurueckgestellt).
  Von der App aus ist nichts davon erreichbar.

---

## 8. Risiken und Prueppunkte

| Risiko | Woran man es merkt | Rueckweg |
|---|---|---|
| Alle Experimente enden `INCONCLUSIVE`, weil die Umgebung driftet | Quote der A/B/A-Abweichungen ueber mehrere Laeufe | Laengere Fenster; im Zweifel bleibt der Scan eine Bestandsaufnahme plus E-0 |
| 2sd-Schwellen aus dem ruhigen Regime erzeugen im gestoerten Regime Scheinurteile | Urteile, die sich zwischen zwei Laeufen umdrehen | Regel aus 4.4 (Maximum aus Baseline- und Arm-Streuung) ist genau dafuer da |
| Das Ledger laesst das Telefon veraendert zurueck | Rueckgabe beim App-Start findet Eintraege | Ist der Rueckweg. Test, der einen Prozessabbruch mitten im Arm simuliert |
| Der Scan wird doch zu Hintergrundarbeit | Grep-Regel wie AK-T002-19: kein Konstruktionsort ausserhalb eines Screen-Scopes | Bau-Regel, nicht Laufzeitpruefung |
| Link ist ausgelagert kodiert ⇒ alle Zaehler blind | `LinkObservability.OFFLOADED` | Bericht meldet `CANNOT_CHECK`; Offload abschalten ist unmoeglich (A-13). Der Scan ist dann auf Gruppe A/B beschraenkt |
| Keine Musik ⇒ Phase 2 faellt komplett aus | Vorbedingungsprueffung | Phase 1 laeuft trotzdem und ist fuer sich nuetzlich |

---

## 9. Umsetzungsschnitt

Jeder Schritt ist einzeln lauffaehig und einzeln testbar.

| Schritt | Inhalt | Braucht Geraet | Haengt ab von |
|---|---|---|---|
| **S-1** | Datenmodell, Faktoren-Registry, Urteilsrechnung in `:core-monitor/scan/` — rein, mit Fixtures unit-getestet. Keine Verdrahtung, keine UI | nein | — |
| **S-2** | Port `EnvironmentFacts` + Adapter in `:app` ueber **nur vorhandene** Quellen (Settings lesen, Systemeigenschaften, PowerManager, vorhandene Dumps). Phase 1 Ende zu Ende | ja, zum Pruefen | S-1 |
| **S-3** | Helper-Operation `wifiFacts` (Protokoll, AIDL, Version 6) — **erst nach `security-reviewer`**. Ergaenzt B-1 | ja | Freigabe |

> **Vor S-3 ist eine Sache am Geraet zu klaeren, und sie ist nicht geklaert:**
> ob `cmd wifi status` auf diesem Build fuer die Shell-Uid laeuft und ob seine
> Ausgabe die MLO-Links ueberhaupt ausweist. Dieser Entwurf nimmt beides an; die
> Annahme stammt aus fremden Ausgaben, nicht von diesem Telefon. Wie bei jedem
> anderen Dump in diesem Projekt gilt: ohne aufgenommene Fixture gibt es keinen
> Parser. Faellt die Annahme, ist B-1 entweder ueber `dumpsys wifi` zu holen
> (breit, unerwuenscht — siehe "Bewusst nicht getan") oder gar nicht, und der
> Bericht meldet dort ehrlich `CANNOT_CHECK`.
| **S-4** | `BorrowedSettingsLedger` in `:core-system` + Rueckgabe beim App-Start + Hinweis. Noch kein Experiment | nein (Robolectric) | S-1 |
| **S-5** | Experiment-Ausfuehrer + D-7-Anmeldung an `LdacTuning` + **E-0** (nur Pinnen, kein Settings-Leihen) | ja | S-1, S-4 |
| **S-6** | Leih-Experimente E-2 (WLAN-Scan) und E-3 (BLE-Scan) — **nur wenn E-0 auf die Umgebung zeigt** | ja | S-4, S-5, Antwort auf Frage 1 |
| **S-7** | Bericht-Oberflaeche | — | `ui-ux-designer` zuerst |

Reihenfolge-Regeln: S-4 **vor** S-6 (nie leihen ohne Ledger). S-5 **vor** S-6
(billigster Diskriminator zuerst). S-3 ist von S-4/S-5 unabhaengig und kann
warten, ohne etwas zu blockieren.
