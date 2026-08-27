# Handover — 20. August 2026

Stand: **364 Unit-Tests grün**, APK gebaut, auf dem Pixel installiert und live
geprüft. Weiterhin nichts committet (bewusst). Version `0.3.0`.

Gerät: Pixel 8 Pro, Android 16. Bathys = `…35:6A`, Klipsch The Fives = `…91:81`.

---

## Shizuku ist abgeloest — der eigene Helfer laeuft

**Auf dem Geraet nachgewiesen.** Das Protokoll des Helfers selbst:

```
privileged helper: serving as uid 2000, version 1
privileged helper: exec dumpsys media.audio_flinger
privileged helper: exec ps -A -o PID,NAME
```

Beide Befehle liefen ueber den eigenen Helfer, nicht ueber Shizuku — wichtig,
weil Shizuku installiert ist und `MonitorGraph` sonst stillschweigend darauf
zurueckfaellt.

### Zwei Sackgassen, die dokumentiert bleiben muessen

**1. LocalSocket geht nicht.** Der Helfer startet, laeuft als uid 2000 in
`u:r:shell:s0`, bindet den Socket — und die App kommt nicht heran:

```
avc: denied { connectto } for path=@dankyeeter_btdashboard_privileged
  scontext=u:r:untrusted_app:s0:... tcontext=u:r:shell:s0
  tclass=unix_stream_socket permissive=0
```

SELinux erlaubt `untrusted_app` keine Verbindung zu einem Socket der
`shell`-Domaene. Keine Berechtigung aendert das. Deshalb reicht Shizuku einen
Binder durch.

**2. `ContentResolver.call` geht auch nicht.**

```
Given calling package android does not match caller's uid 2000
```

Der einzige Context, den ein blankes `main()` bekommt, stammt aus
`ActivityThread.systemMain()` und gehoert zum Paket `android`; der Prozess
laeuft aber als uid 2000. Das System prueft beides gegeneinander.
`createPackageContext("com.android.shell")` hilft **nicht** — der gemeldete
Paketname haengt an der Attribution des Contexts, nicht an seiner Paketinfo.

### Was stattdessen funktioniert

Den Provider direkt ansprechen, wie Shizuku es tut:

1. `ActivityManager.getService()` → `getContentProviderExternal(authority, 0,
   token, tag)` → das Feld `provider` des Holders ist der `IContentProvider`;
2. `IContentProvider.call(...)` mit einer `AttributionSource`, die
   `com.android.shell` **ausdruecklich** nennt — dieses Paket besitzt uid 2000
   wirklich, also stimmen Aufrufer und Paket ueberein;
3. im Bundle: der Binder, der Token und die Version.

`call` wird nach Form gesucht (5 Parameter, erster ist `AttributionSource`),
nicht nach exakter Signatur — die hat sich zwischen Releases bewegt.

### Absicherung

`PrivilegedProvider` ist exportiert, weil der Aufrufer eine andere uid hat.
Zwei unabhaengige Pruefungen ersetzen die fehlende Dateirechte-Huerde:

- `Binder.getCallingUid()` muss die Shell-uid sein — die liefert der Kernel,
  die kann keine App faelschen;
- der Token muss stimmen — er entsteht in der App und erreicht den Helfer nur,
  weil der Nutzer ihn in einen ADB-Befehl einsetzt.

Dazu die Whitelist in `PrivilegedProtocol`: **genau drei** Argumentvektoren,
vollstaendig verglichen, kein Praefix-Match. `dumpsys` mit einem anderen Dienst
wird abgelehnt. Der Helfer bietet also keine allgemeine Shell an, anders als
Shizuku.

### Noch offen

Shizuku ist als `ShellRunner` abgeloest, aber nicht aus dem Projekt entfernt:
`ShizukuQualityReportSource` (BQR) und `SystemGraph.shizuku` (Onboarding,
Zustandsanzeige) haengen noch daran.

~~Der ADB-Befehl steht in `PrivilegedBootstrap.adbCommand()`, hat aber noch
keine Oberflaeche~~ — **erledigt**, siehe „System access“ weiter unten. Was
dabei *nicht* erledigt wurde und wichtig ist: **der globale EQ-Attach haengt
weiterhin an Shizuku**, nicht am Helfer. `GlobalAttachmentStrategy.activate()`
prueft `ShizukuState.Ready` und gibt sonst `Unavailable` zurueck. Der Helfer
allein stellt den globalen Attach also **nicht** wieder her.

**Der Token muss existieren, bevor der Helfer startet.** Er wird deshalb in
`BtDashboardApplication.onCreate` erzeugt.

## System access, Designsystem, stille Benachrichtigung

Drei zusammenhängende Stücke: die Oberfläche für den eigenen Helfer, das
Ausrollen von `Panel`/`Type` auf die letzten drei Screens, und die eine
Benachrichtigung, die den Nutzer stören konnte.

### 1. Onboarding zeigt jetzt den Helfer zuerst

`ui/screens/onboarding/ShizukuOnboardingScreen.kt`, Titel im UI: **System
access**. Aufbau: „App helper" (primär, mit Live-Zustand und dem ADB-Befehl) →
„What needs it" → „Shizuku" (sekundär) → WRITE_SECURE_SETTINGS →
Kopfhörer-App.

Der Helfer-Zustand kommt aus `PrivilegedConnection.service` als Flow, nicht aus
einem Flag — der Helfer stirbt beim Reboot, und der Death-Recipient dahinter ist
das, was den Screen wieder ehrlich macht. Der Befehl kommt aus
`PrivilegedBootstrap.adbCommand()`, gezeigt über das vorhandene
`CopyableCommand`.

Ausgesprochen, weil der Nutzer es genau so verlangt hat: einmal pro Boot, von
einem Rechner mit ADB; uid 2000, also Shell-Ebene und **nicht** root; stirbt
beim Reboot, und ohne root gibt es keinen Weg daran vorbei — Shizuku hat
dieselbe Grenze aus demselben Grund.

**Der Befehl wird bei Verbindungswechsel neu geholt.** Der andere Worker hat den
Token auf Rotation umgestellt: `promote()` verbraucht den Pending-Token, sobald
ein Helfer angenommen wird. `remember(context, helperRunning)` statt
`remember(context)` — sonst zeigt der Screen nach dem Verbinden weiter eine
Zeile, die nicht mehr funktioniert.

### 2. Was der Screen *nicht* behauptet

Die Aufgabe nannte drei Verluste ohne Helfer. Zwei davon stimmen wörtlich
(dumpsys-Codec-Lesung, Fremd-EQ-Scan — beide gehen über `MonitorGraph.shell`).
Der dritte nicht: der globale EQ-Attach prüft Shizuku, nicht den Helfer.

Deshalb steht in der Zeile „System-wide EQ" **kein geschriebener Satz**, sondern
der Live-`AttachmentStatus` samt seiner eigenen Begründung. Das kann nicht
veralten, wenn jemand den Attach-Pfad umbaut, und es behauptet heute nichts
Falsches. Dasselbe im Settings-Tab.

Nebenbei repariert: dort stand vorher `Text("EQ attachment: $attachment")` mit
`AttachmentStatus.toString()` — dem Nutzer wurde `ActiveSessions(sessionIds=[])`
vorgesetzt.

### 3. Designsystem auf den letzten drei Screens

Die gemessene Abdeckung („0 design-system") war ein Artefakt der Messung: der
Grep suchte `Panel(`, die Screens schrieben `Panel {`. Die äußere Fläche war
schon umgestellt, das *Innere* nicht — rohe `Text(title, titleMedium)` statt
`PanelHeader`, kein `Pill`, kein `Readout`, dazu tote Importe von `Card`,
`GoldCard` und `GoldTitle` in allen dreien.

Umgestellt auf die Sprache von `BluetoothScreen`/`EqScreen`: 16 dp Screen-Rand
und 12 dp Abstand (vorher 24/16), `PanelHeader` mit `Pill` als Zustandsmarke,
`PanelDivider` statt `HorizontalDivider`, `GoldButton`/`GoldOutlinedButton`
statt roher Material-Buttons, Sekundärtext durchgehend `onSurfaceVariant`.

Der Wizard hat sichtbar am meisten gewonnen: die Schrittliste war eine Reihe
`TextButton("✓ Bluetooth access")`, jetzt sind es anklickbare Zeilen mit
Status-Pille. Der Shizuku-Schritt zeigt beide Wege, Helfer zuerst.

### 4. Der Readout ist ein Verlauf, und Verläufe haben keinen einen Kontrast

Beim Nachrechnen für `ContrastTest` aufgefallen: `Readout` füllt die Glyphen
unter Edgy mit `MetalPalette.vertical`. Dessen untere Stopps messen gegen ein
Panel **2,70:1** (`Gold.Deep`) und **1,27:1** (`Gold.Shadow`).

Das ist kein Fehler — die dunkle Hälfte ist die Schattierung, die die Rampe
überhaupt nach Metall aussehen lässt; ohne sie sieht sie nach Farbe aus. Die
Folge ist eine Regel für die Screens, nicht eine Änderung an der Rampe: **ein
Zustand, der in einem `Readout` steht, muss zusätzlich flach dastehen.** Deshalb
sagt das Helfer-Panel seinen Zustand doppelt — einmal im Readout, einmal in
einer Pille, die für sich 4,5:1 schafft.

Gemessen und in `ContrastTest` festgenagelt:

| Paarung | Verhältnis | Minimum |
|---|---|---|
| Pill ACCENT (Label auf eigenem 14%-Tint) | **5,08:1** | 4,5 |
| Pill WARN | 7,94:1 | 4,5 |
| Pill NEUTRAL | 8,04:1 | 4,5 |
| `PanelHeader`-Eyebrow (Gold.Base @85%) | **4,81:1** | 4,5 |
| `outline` auf `surfaceContainerHigh` | 6,03:1 | 4,5 |
| Rampe hell (Pale…Base) | ≥ 6,21:1 | 4,5 |

Alle Werte gegen den *hellsten* Stopp des Panel-Verlaufs, also den ungünstigsten
— das ist der Kopf des Panels, wo Header und erste Zeile sitzen. Die alte
`ContrastTest` prüfte `surfaceContainer` und `surfaceContainerHighest`, aber
weder `surfaceContainerHigh` noch `surfaceContainerLow`, und das sind zwei der
drei Stopps, aus denen `Panel` wirklich malt.

**Nicht geprüft, und das ist eine Entscheidung:** Füllung und 30%-Rand der Pille
liegen bei 1,2–1,35:1 bzw. 1,6–2,1:1. Ein Tint, der dunkel genug ist, dass
farbiger Text darauf lesbar bleibt, kann gegen ein fast schwarzes Panel nicht
gleichzeitig herausstechen. Die Information trägt das Label, nicht die Form —
dieselbe Begründung wie bei der Codec-Spur der Timeline. **In der Kontur einer
Pille darf nie etwas kodiert sein.**

### 5. Die Benachrichtigung stört nicht mehr — und lügt weiterhin nicht

`BootReceiver` war das einzige im ganzen Projekt, das unaufgefordert auftauchen
konnte, auf `IMPORTANCE_DEFAULT`, also mit Ton. Jetzt `IMPORTANCE_LOW`, dazu
`setSound(null)`, keine Vibration, kein Licht, kein Badge, `setSilent(true)` und
`PRIORITY_LOW`.

**Neue Kanal-ID (`eq_status_quiet`), alte gelöscht.** Android lässt eine
bestehende Kanal-Wichtigkeit nur *manchmal* senken — abhängig davon, ob der
Nutzer schon ein Feld angefasst hat, und OEM-Builds weichen ab. Neue ID heißt:
„macht nie ein Geräusch" ist eine Eigenschaft des Codes, keine Hoffnung auf die
Plattform.

Der Text nennt Shizuku nicht mehr als *den* Mechanismus, sondern beschreibt den
Verlust und leitet auf „Settings → System access". Der Langtext wird aus dem
tatsächlichen `AttachmentStatus` gebaut.

**Eine Verhaltensänderung, bewusst.** Die Bedingung war
`status !is ActiveGlobal && !shizukuReady`. War Shizuku bereit und der globale
Attach scheiterte trotzdem — etwa weil der Build den globalen Effekt ablehnt —
sagte niemand etwas. Das ist genau der stille Fehlalarm-frei-Fall, den die
Ehrlichkeitsregel verbietet. Jetzt zählt nur noch das Ergebnis: kein
`ActiveGlobal` → stille Notiz. Gelingt der globale Attach, wird eine alte Notiz
aus einem früheren Boot **gelöscht**; sonst bliebe sie stehen und wäre falsch.

Die Spannung selbst ist im Kopfkommentar der Datei festgehalten: leise ist das
Maximum an Zurückhaltung, das noch mit „nie behaupten, was nicht bewiesen ist"
vereinbar ist. Die ehrliche Version von „ganz unsichtbar" wäre `IMPORTANCE_MIN`,
nicht „nicht mehr posten".

### Zahlen zum Nachprüfen

`testDebugUnitTest assembleDebug` grün. **392 Tests, 0 Fehler** — Basis waren
364, davon sechs aus dieser Runde (`ContrastTest` 8 → 12, neu
`SystemAccessScreenTest` mit 2), der Rest kam von den parallel laufenden
Workern.

Abdeckung des Designsystems, gemessen als `Panel|PanelHeader|PanelDivider|Readout|Pill`
gegen `Card|GoldCard`:

| Screen | vorher | nachher | alte Cards |
|---|---|---|---|
| `onboarding/ShizukuOnboardingScreen.kt` | 0 | **20** | 0 |
| `wizard/SetupWizardScreen.kt` | 0 | **12** | 0 |
| `settings/SettingsScreen.kt` | 7 | **22** | 0 |

Kein `HorizontalDivider`, kein rohes `Button`/`OutlinedButton` und keine toten
`Card`/`GoldCard`/`GoldTitle`-Importe mehr in den dreien.

Der zweite Test in `SystemAccessScreenTest` ist der interessante: er fragt
`PrivilegedBootstrap` nach dem Befehl und sucht dann **genau diese Zeichenkette**
auf dem Screen. Ein Screen, der sauber komponiert und den falschen (oder gar
keinen) Befehl zeigt, sieht sonst völlig gesund aus.

---

## In dieser Sitzung gebaut

### 1. Bluetooth-Erkennung ist push-basiert, der Refresh-Knopf ist weg

Zwei Ursachen, nicht eine:

- `A2dpCodecStatusSource.connectedDevicesFlow()` war eine Attrappe: ein
  `trySend`, dann `awaitClose { }`. Sie sendete nie wieder. Niemand hat sie je
  gesammelt, deshalb ist es nie aufgefallen.
- **Der A2DP-Proxy bindet asynchron.** `init { refresh() }` lief regelmäßig
  davor und bekam eine leere Liste — also „No Bluetooth audio device
  connected“ bei verbundenem Kopfhörer. Der Knopf hat den Fehler kaschiert.

Neu: `link/BtConnectionEvents.kt`. Zwei Trigger, **kein Timer**: `proxyBound`
(als `StateFlow` zugleich der Erstlesevorgang) und die Broadcasts aus
`BtActions.connectionRelevant`. Weil `ACL_CONNECTED` vor der Aushandlung kommt
und `CODEC_CONFIG_CHANGED` danach, wird bei jedem neu gelesen — keine geratene
Wartezeit. `distinctUntilChanged()` macht daraus ein UI-Update statt drei.

Nebenbei: `FallbackCodecStatusSource` reichte den Flow des Primary durch und
übersprang den dumpsys-Fallback.

Auf dem Gerät verifiziert: Bathys erscheint von selbst, `aptX · 48 kHz · 16 bit`.

**Merkposten:** `dumpsys` zeigt daneben einen globalen `codecConfigOffloading`-
Block, der `LDAC 96000/32` behauptet. Das ist die Falle aus Fehler 7 — nicht der
ausgehandelte Codec. Der Parser der App liest korrekt aptX.

### 2. Interpolation statt mehr Messtöne

Empirisch ausgewertet (`freq_eval.py` im Scratchpad), bevor gebaut wurde:

| 20-Band-Layout | max. Fehler | RMS |
|---|---|---|
| 8 Punkte, linear (vorher) | 3,61 dB | 0,99 |
| **8 Punkte, PCHIP** | **1,25 dB** | **0,35** |
| 13 Punkte, linear | 3,61 dB | 0,64 |

Zusätzliche Messtöne bringen beim 10-Band-Layout **0,00 dB** — die Bandmitten
*sind* die Messpunkte. 125 Hz bringt ebenfalls nichts, weil NAL-Rs C-Tabelle bei
250 Hz auf −17 dB steht und die Verschreibung dort unter null drückt.

Also: `logInterpolateMonotone` (Fritsch-Carlson) für Audiogramm und
Bandzuordnung. Monoton, nicht irgendeine Spline: eine natürliche Spline
überschwingt an einer Kerbe und erfände eine Senke, die niemand gemessen hat.
C(f) bleibt linear — das ist eine Spezifikation, keine Messkurve.

**Offen (`#12`):** ob Daniels echte Kurve nach unten hin abfällt. Wenn ja, wird
125 Hz doch wertvoll. Entscheidung nach dem ersten echten Durchlauf.

### 3. Adjusted Reference

Ausgezeichnetes, **generiertes** Profil: aus dem Median der Hörtestläufe
abgeleitet, nicht gespeichert — kann also nie vom Messwert abweichen. Braucht
3 Läufe, hat kein Load und kein Delete, und die Bandregler sind gesperrt. Die
Sperre sitzt im ViewModel (`curveIsGenerated()`), nicht nur in der UI.

**Annahme, die zu bestätigen ist:** Der Intensitätsregler bleibt bedienbar. Er
ist ein Parameter der *Erzeugung*, kein Eingriff in die Kurve — und die
Teilkompensation hängt daran. Wenn wirklich gar nichts verstellbar sein soll,
ist das eine Zeile.

### 4. Monitoring-Timeline auf echte Messwerte

Die alte zeichnete RSSI, das `dumpsys` für eine *verbundene* A2DP-Strecke nie
liefert — eine leere Achse, die wie eine ruhige Verbindung aussah. Jetzt Spuren:
Playing, Codec (mit Wechsel-Trennlinien), Sample-Rate als Stufenkurve, Events.
Die RSSI-Spur erscheint nur, wenn wirklich etwas sie liefert.

Schlafphasen des Samplers werden über alle Spuren grau hinterlegt: „niemand hat
hingesehen“ und „nichts ist passiert“ sind gegensätzliche Aussagen.
Segmentierung in `TimelineModel.kt`, 8 Tests.

### 5. Entwickleroptionen pro Gerät

`DeviceProfile.developerOptions` (AVRCP-, MAP-, PBAP-Version), angewendet beim
Verbinden wie die Absolute-Volume-Einstellung.

**Der wichtige Teil:** Lesen kann „unterstützt, aber ungesetzt“ nicht von
„unbekannt“ unterscheiden — `bluetooth_disable_absolute_volume` liest `null`
und funktioniert nachweislich. Also schreibt `GlobalSettingsController` und
**liest zurück**; passt der Wert nicht, meldet die App „did not stick“ statt
Erfolg. Was der Stack danach tut, kann keine App beobachten — deshalb ist
„Bluetooth aus- und einschalten“ eine ausgesprochene Aussage, keine geprüfte.

### 6. Theme: Kontrastprüfung und ableitbare Metalle

`Contrast.kt` (WCAG 2.1) plus `ContrastTest`, der jedes Farbpaar prüft, auf dem
wirklich Text liegt. Hat zwei echte Fehler gefunden:

- `onSecondary` auf `secondary` lag bei **3,14:1** (Schwarz auf dunklem Gold).
  Jetzt Parchment, 5,4:1.
- Meine eigene Codec-Spur lag bei **1,23:1**. Jetzt tragen Trennlinie und
  Beschriftung die Information, nicht die Füllung.

`MetalPalette.from(color)` leitet die sechs Verlaufsstufen aus einer Farbe ab.
Fünf Akzente wählbar (Gold, Silber, Kupfer, Stahlblau, Rosé); Gold behält
seine handgestimmten Werte.

**Korrektur einer Annahme:** Ich hatte gedacht, das Glanzlicht entsättige zu
Weiß hin. In HSL gemessen tut es das Gegenteil — 0,82 gegen 0,66 an der Basis.
Entsättigen würde die Rampe nach Plastik aussehen lassen.

### 7. Graph der 3 Durchläufe

War bereits gebaut **und korrekt verdrahtet** — `AudiogramChart` zeichnet jeden
Lauf dünn, den Median dick, nicht konvergierte Punkte hohl. Kein Code nötig.

---

## Fallen, die diese Sitzung gekostet haben

- **Bash-Heredocs zerlegen Backticks und Escape-Sequenzen.** Zwei KDoc-Blöcke
  und ein `trimEnd('\r', '\n')` sind daran zerbrochen. Python-Skript in eine
  Datei schreiben und ausführen — nicht über `python -c` mit Kotlin-Text darin.
- **`adb pull` mit Git-Bash-Pfaden:** Geräteseite braucht `MSYS_NO_PATHCONV=1`,
  Hostseite den Windows-Pfad (`C:/...`). Beides gleichzeitig geht nicht anders.
- **Compose-Tests:** ein Collector muss auf `UnconfinedTestDispatcher` laufen,
  sonst sammelt er zu spät und jede Erwartung ist leer.

---

## Sofort weitermachen bei

**Der akustische EQ-Test ist geschrieben, aber noch nie gelaufen.**

`core-audio/src/androidTest/.../AcousticEqTest.kt` spielt einen Ton über den
Lautsprecher, nimmt ihn per Mikrofon zurück auf und misst mit einem
Goertzel-Filter, ob ein um 12 dB angehobenes Band tatsächlich lauter in der Luft
ankommt. Er kompiliert; ausgeführt wurde er nicht.

```bash
./gradlew :core-audio:connectedDebugAndroidTest
```

Telefon dabei ruhig hinlegen, Lautsprecher frei. Der Test setzt die Lautstärke
selbst auf 80 % und stellt sie danach wieder her.

Das ist die einzige Prüfung im Projekt, die durchfallen kann, weil das Audio-HAL
etwas anders macht als gedacht. Alles andere prüft nur, dass eine Zahl in einem
Objekt angekommen ist.

**Offene Frage, die der Test beantworten soll:** Liegt unser Effekt bei
A2DP-Offload überhaupt noch im Signalweg? Manche Geräte umgehen
Software-Effekte, wenn der Codec in Hardware läuft. Deshalb nach dem
Lautsprecher-Durchlauf denselben Test über die Klipsch wiederholen.

---

## In dieser Sitzung gefunden und behoben

Reihenfolge nach Schwere.

### 1. Der EQ war bei 20 und 31 Bändern wirkungslos

`writeBand()` las die Mittenfrequenz aus `EqBands.CENTER_FREQUENCIES_HZ` — das
zeigt seit der Layout-Umstellung auf das **Standardlayout mit 10 Einträgen**.
Ab Band 10 griff das ins Leere, die `IndexOutOfBoundsException` wurde von
`guard()` geschluckt und setzte `alive = false`. Regler bewegten sich, Werte
wurden gespeichert, der Klang änderte sich nicht.

Von mir verursacht, beim Einbau der variablen Bandzahl. Gefixt; `BandWriteRangeTest`
deckt es ab.

### 2. `RECEIVER_NOT_EXPORTED` verwarf alle Bluetooth-Broadcasts

Lässt nur Sender **derselben uid** durch. Der Bluetooth-Stack ist ein eigenes
Paket auf **uid 1002**, die App läuft auf 10440. Die Registrierung meldete `true`
und verwarf danach jeden Broadcast.

Zwei Features lagen deshalb tot:
- Geräteprofile wurden bei Verbindung nie angewendet
- Die Monitor-Timeline zeichnete durchgehend `0 events` auf

Beides jetzt `RECEIVER_EXPORTED` — unbedenklich, weil alle betroffenen Actions
protected broadcasts sind und nicht gefälscht werden können.

### 3. Der Hörtest, drei übereinanderliegende Ursachen

- **Ramp:** Beim Ausblenden war `target = 0`, der Dekrement wurde
  `max(0, 1e-6) * step` statt eines echten Schritts. Ausblendzeit **169 s statt
  30 ms**, bei Maximalpegel vier Stunden. Jetzt echter Raised-Cosine-Ramp.
- **Pegel:** Media-Volume für A2DP stand auf 3/25, Startpegel −45 dBFS war
  unhörbar. Die App setzt jetzt selbst 70 %, sperrt das und stellt danach
  wieder her.
- **Sichtbarkeit:** Ein passiver Durchlauf dauert konstruktionsbedingt ~10 Minuten
  und zeigte nur „Left ear". Jetzt `Tone 3 of 8 · 1 kHz`.

### 4. Der Monitor schrieb 207 Samples pro Minute statt 1

Der Collector legte eine Zeile pro Adresse aus dem Dump an — und der listet
**jedes je gesehene Gerät** (196 auf diesem Telefon). Dazu zählte derselbe
Kopfhörer doppelt, weil echte und redigierte MAC als zwei Geräte galten.
`purgeOlderThan` existierte, wurde aber nie aufgerufen.

### 5. Der Leerlauf-Sampler pollte alle 30 s

**2.880 Aufwachvorgänge pro Tag**, um jedes Mal festzustellen, dass es nichts zu
tun gibt. Alle Aufweckgründe sind signalgetrieben. Wartet jetzt auf ein Signal,
mit 10-Minuten-Deckel. `IdleWakeupTest` belegt: null Aufrufe in zehn Minuten.

### 6. Der EQ konnte still sterben

Der Kommentar über `reattachIfNeeded` versprach einen Watchdog — es gab keinen.
Einziger Auslöser war ein Regler-Zug. Ein Geräteschwenk reißt den Output-Mix ab;
der EQ blieb tot, bis der Nutzer zufällig etwas anfasste. Jetzt prüft jeder
ACL-Connect nach.

### 7. Codec wurde falsch gelesen

Der Parser nahm den globalen `codecConfigOffloading`-Block (SBC zuerst) statt der
geräteeigenen `mCodecConfig`-Zeile und las auch historische
`CODEC_CONFIG_CHANGED`-Einträge. Dazu: `AptX-HD` wurde als `APTX` erkannt, weil
nur `_` normalisiert wurde, nicht `-`.

**Befund nebenbei:** Die Bathys läuft auf **aptX, nicht aptX HD**, obwohl sie
aptX HD anbietet. Real, kein Anzeigefehler.

### 8. Der Fremd-EQ-Zähler log

56 gemeldete Apps. Gemessen auf dem Gerät: 83 Apps halten
`MODIFY_AUDIO_SETTINGS`, genau **1** deklariert ein EQ-Bedienfeld. Die
Berechtigung ist raus. Ergebnis: **3 statt 56** (MusicFX, Focal & Co, Sony),
geprüft in 125 ms über 414 Apps.

---

## Architektur-Merkposten

Drei Fehler derselben Bauart sind in dieser Codebase schon aufgetreten:
**gebaut, aber nie aufgerufen** (`purgeOlderThan`, der Connection-Watcher, die
Monitor-Engine). Bei neuen Komponenten lohnt der Blick, ob der Starter wirklich
irgendwo aufgerufen wird. Das Prüfskript dafür liegt im Scratchpad
(`audit_files.py`), findet aktuell nichts.

Achtung bei Textersetzungen per Skript: Regex mit `[^"\\]` wird im Bash-Heredoc
zerlegt. Skript in eine Datei schreiben und mit `python datei.py` aufrufen.

Das UI-Hilfsskript im Scratchpad (`ui.sh`) prüft vor jedem Tap, ob unsere App im
Vordergrund ist — ich bin einmal versehentlich in einem fremden Chat gelandet.

---

## Offene Punkte, in deiner Reihenfolge

1. ~~**Bluetooth-Einstellungen ohne Verbindung anzeigen.**~~ **Erledigt**, siehe
   „Einstellungen bleiben stehen" am Dateiende. Am Gerät noch nicht gesehen.
2. **Hörtest-Frequenzen erweitern.** Achtung: Die `offsetsDb`-Listen aller
   Kalibrier-Presets sind index-gleich mit `TEST_FREQUENCIES_HZ` — sie müssen
   mitwachsen, sonst kippt die Kompensation still.
3. **Bluetooth-Entwickleroptionen pro Gerät**, persistent über die DB.
   Vorher prüfen, was ohne `BLUETOOTH_PRIVILEGED` real setzbar ist.
4. **Monitoring mit Graphen.** Die Timeline ist leer, weil sie RSSI zeichnet —
   den liefert `dumpsys` für die aktive A2DP-Verbindung nicht. Auf Messwerte
   umstellen, die wirklich ankommen: Codec-Wechsel, Sample-Rate,
   Playing-Zustand, Verbindungslücken.
5. **Custom-Theme** mit eigenen Farben, aus denen die Verläufe abgeleitet werden.
   Kontrastprüfung nicht vergessen — `outline` lag bei 3,1:1 und war unlesbar,
   Minimum ist 4,5:1.
6. **Design weiter ausbauen.** Typo- und Flächensystem stehen
   (`Type.kt`, `Panel.kt`, `Gold.kt`). Angewendet sind jetzt Bluetooth, EQ,
   **System access, Setup-Wizard und Settings**. Offen bleiben:
   `devices/DeviceProfilesScreen.kt`, `hearing/HearingTestScreen.kt`,
   `monitor/MonitorScreen.kt` und `eq/CompensationSection.kt` — letzteres malt
   noch ein rohes `Card(Modifier.padding(16.dp))` um alles.

   Wer das fortsetzt: der Grep, mit dem Abdeckung gemessen wird, muss
   `Panel\s*[({]` suchen, nicht `Panel(`. Alle drei Screens dieser Runde galten
   als „0 design-system", weil sie `Panel {` schrieben.

---

## Was noch offen ist an Verifikation

- Der akustische Test (siehe oben) — die eigentliche Antwort auf „wirkt der EQ".
- Ob die Monitor-Timeline nach dem Receiver-Fix Events aufzeichnet. Der Fix ist
  drin und der Auto-Apply nachweislich gelaufen, aber ein Verbindungswechsel bei
  laufender App wurde danach nicht mehr beobachtet.

---

## Needs device verification — System access, Design, Benachrichtigung

Kein Gerät angeschlossen, als das gebaut wurde. Alles hier ist begründet, aber
nicht gemessen. Reihenfolge nach Risiko.

### Muss geprüft werden

1. **Die Benachrichtigung ist wirklich still.** Neu starten, während der EQ an
   ist und kein Helfer läuft. Erwartet: erscheint lautlos im Schatten, kein Ton,
   keine Vibration, kein Heads-up-Einblenden, kein Badge.
   `adb shell dumpsys notification --noredact | grep -A5 eq_status_quiet` zeigt
   die tatsächliche Wichtigkeit. **Der alte Kanal `eq_status` muss verschwunden
   sein** — wenn er in den App-Benachrichtigungseinstellungen noch steht, hat
   `deleteNotificationChannel` nicht gegriffen.
2. **Der ADB-Befehl auf dem Screen startet den Helfer wirklich.** Kopieren,
   ausführen, und die Pille muss **ohne Zutun** auf „Running" springen —
   der Zustand kommt aus dem Flow, es gibt keinen Re-Check-Knopf dafür.
3. **Der Befehl wechselt nach dem Verbinden.** Vorher/nachher vergleichen: der
   Token muss ein anderer sein (der andere Worker rotiert ihn in `promote()`).
   Der alte darf danach **nicht** mehr funktionieren.
4. **Was „What needs it" bei laufendem Helfer und totem Shizuku behauptet.** Das
   ist der interessante Fall: erwartet werden „Codec details: Available",
   „Other-equalizer check: Available" und beim EQ **nicht** „Global", sondern
   der Satz aus `SessionAttachmentStrategy` — der nennt heute noch Shizuku
   (siehe unten). Behauptet der Screen dort „Global", stimmt etwas nicht.
5. **Kontrast am Gerät, nicht im Rechenblatt.** Die Pillen im Edgy-Theme bei
   Sonnenlicht ansehen. 5,08:1 ist rechnerisch in Ordnung und praktisch die
   engste Paarung der App.

### Zu prüfen, wenn Zeit ist

6. Wizard und System access bei sehr großer Schriftgröße (Systemschrift auf
   Maximum). Die Status-Pillen sitzen rechts in Zeilen mit `SpaceBetween`; bei
   langen Labels wie „Session only" könnte es eng werden.
7. Der `Readout` „Not running" ist der breiteste Monospace-Wert im Projekt.
   Rechnerisch passt er auf 360 dp; einmal ansehen.

### Nicht meine Dateien, aber im Vorbeigehen gefunden

- **`MonitorGraph` friert den `ShellRunner` beim ersten Zugriff ein.**
  `codecSource`, `foreignEqScanner` und `engine` bauen `ShellDumpsysLinkSource(shell)`
  genau einmal, und `shell` wird dabei ausgewertet. Startet der Helfer *nach*
  dem ersten Zugriff auf eine dieser Eigenschaften, bleibt die Quelle für immer
  auf `shizukuShell` — obwohl `installedShell.isAvailable` inzwischen true ist.
  Verwandt mit der „gebaut, aber nie aufgerufen"-Sammlung, hier eher „einmal
  gelesen, nie neu gelesen". Am Gerät prüfbar: App starten *ohne* Helfer, Codec
  ansehen, dann Helfer starten und schauen, ob die dumpsys-Lesung anspringt.
- **`SessionAttachmentStrategy.status`** sagt im Unavailable-Fall wörtlich
  „require the global attachment via Shizuku". Der Satz steht so im UI, weil die
  neuen Screens die Begründung absichtlich durchreichen statt sie zu ersetzen.
  Heute korrekt; wenn der globale Attach je auf den Helfer umgestellt wird, muss
  dieser Satz mit.
- **`Pill` und `PanelHeader` greifen unter Edgy hart auf `Gold.Base`** statt auf
  `LocalMetalPalette.current`. Bei gewähltem Silber/Kupfer/Stahlblau bleiben
  Pillen und Eyebrows golden, während `Readout` korrekt mitwechselt. Nicht
  angefasst — an dem Paket lesen andere Worker.
- **`AttachmentStatus.Inactive` ist zweideutig:** Initialwert des Controllers
  *und* Aus-Zustand. Beide neuen Screens formulieren das deshalb offen („either
  … or"). Ein eigener `NotApplied`-Zustand wäre die saubere Lösung.

### Umbenennungen, die eine fremde Datei brauchen

- `ShizukuOnboardingScreen` heißt im UI „System access" und handelt
  überwiegend nicht mehr von Shizuku. Datei, Composable und `ROUTE_ONBOARDING`
  umzubenennen berührt `ui/BtDashboardApp.kt`.
- `SetupStep.SHIZUKU` (`core-system/.../setup/SetupSteps.kt`) trägt Titel
  „Shizuku" und wird allein aus dem Shizuku-Zustand als DONE bewertet. Der
  Schritt zeigt jetzt beide Wege, aber ein laufender Helfer macht ihn **nicht**
  grün. Richtig wäre ein Schritt „Shell access", der auf beides schaut.
- `SystemAccessScreenTest` liegt in
  `app/src/test/.../ui/screens/onboarding/` statt in `ScreenSmokeTest` — nur,
  weil an der geteilten Testklasse parallel gearbeitet wird. Zusammenlegen,
  sobald das gelandet ist.
---

## 20. August 2026, später — Kompensation: ehrliche Zahlen, 20 Bänder, „cannot check"

Grundlage ist `RESEARCH_COMPENSATION.md` (heute geschrieben). Drei Aufträge,
alle umgesetzt, **nichts committet**. Von mir kommen **22 neue Unit-Tests**
(20 in `core-hearing`, 2 in `app`), also 364 → 386 aus meinem Anteil. Der
Gesamtstand ist höher und bewegt sich, weil zwei weitere Agenten parallel Tests
hinzufügen — beim letzten vollständigen Lauf **412, alle grün**, `assembleDebug`
läuft durch.

### 1. Der Regler sagt jetzt, wovon er 60 % nimmt

Vorher stand dort „Strength — 60 %" ohne Bezugsgröße. Wer das liest, ergänzt
still „60 % meines Hörverlusts" — und das ist rund das **Dreifache** dessen, was
wirklich passiert. Zwei Änderungen, keine davon technisch an der Oberfläche:

- Die Skala heißt nach dem, was sie skaliert: **„Correction strength"**, 100 % =
  die ganze Korrektur, die die App verschreibt.
- Darunter eine Live-Zeile aus dem tatsächlichen Ergebnis, z. B.
  `Lifts up to +9.4 dB, strongest around 4.5 kHz.` Sie kommt aus
  `CompensationResult.peakBand`, also aus der **gerechneten Kurve**, nicht aus
  dem Prozentwert. Genau das ist der Punkt: an der 12-dB-Grenze, am
  Flankenlimiter und außerhalb des gemessenen Bereichs ist die Kurve nicht mehr
  proportional zum Prozentwert. Eine aus 60 % hochgerechnete Zahl wäre dort
  schlicht falsch, und niemand könnte es sehen.
- Ohne Testergebnis: keine Zeile. Bei 0 %: „Nothing is lifted at this setting —
  playback is unchanged." Das ist beweisbar, nicht beruhigend gemeint — bei
  s = 0 sind alle Bandgains *und* der Pre-Gain exakt 0.

Hinter dem `?`-Icon steht die unangenehme Hälfte, die an der Oberfläche nichts
zu suchen hat: NAL-R ist konstruktionsbedingt eine Halbgain-Regel (≈ 0,46 dB pro
dB Verlust), also deckt 100 % auf diesem Regler ≈ 46 % des gemessenen Verlusts
ab und die 60-%-Voreinstellung ≈ 28 %. Der Text erklärt, **warum das Absicht
ist**: volle Kompensation überhebt alles, was ohnehin hörbar war (Rekrutierung —
laute Stellen werden hart, nicht detaillierter), und als die Regel empirisch
gegen echte Hörer geprüft wurde (NAL-NL2), ging die Verstärkung *runter*, nicht
rauf; knapp die Hälfte der Probanden wollte weniger als die Theorie.

Gleiches Muster wie die übrigen `?`-Erklärungen des Screens
(`ui.theme.ExplainedRow`), inline nachgebaut statt wiederverwendet, weil das
Icon **neben den Reglerbalken** muss und nicht vor ein Label. `ui/theme` gehörte
nicht zu meinen Dateien.

### 2. Adjusted Reference läuft jetzt auf 20 Bändern

`AdjustedReference.LAYOUT = EqBandLayout.HALF_OCTAVE_20`. Der Befund aus dem
Report ist **bewiesen, nicht übernommen** — `AdjustedReferenceLayoutTest` misst
ihn nach:

- Verschiebt man im 10-Band-Layout die gemessenen Schwellen bei **3 kHz und
  6 kHz um 40 dB**, ändert sich am Ausgang **exakt gar nichts** (Assertion auf
  Listengleichheit von `eq.leftGainsDb`). Die Oktavmitten *sind* sechs der acht
  Messfrequenzen, der monotone Interpolant geht durch seine Knoten, und 3k/6k
  stecken auch nicht im PTA. Zwei der acht Töne, die man sich anhört, werden
  gemessen und danach weggeworfen.
- Eine Kerbe bei 3 kHz oder 6 kHz erzeugt im 10-Band-Layout eine **komplett
  flache Kurve**. Im 20-Band-Layout landet sie auf 3200 bzw. 6400 Hz.

**Korrektur am Report (und an meinem Auftrag):** Der Satz „eine klassische
4-kHz-Kerbe erzeugt null Kompensation" stimmt so **nicht**. 4000 Hz *ist* eine
Oktavmitte, die Kerbe kommt an. Was das grobe Raster mit ihr macht, ist etwas
anderes und ebenfalls schlecht: der 3-Punkte-Mittelwert legt **denselben Hub auf
2 kHz und 8 kHz**, wo nichts gemessen wurde — die Korrektur wird über zwei
Oktaven verschmiert statt weggeworfen. Im 20-Band-Layout bleibt sie zwischen
3,2 und 6,4 kHz. Beides ist als Test festgenagelt, damit die falsche Kurzfassung
nicht weitergetragen wird. Das Argument für R2 wird dadurch stärker, nicht
schwächer.

Umsetzung: Die Regel steht im ViewModel (`compensationLayoutFor`), nicht in der
UI — wie bei den gesperrten Bandreglern. Solange das generierte Profil aktiv
ist, ist die Bandzahl fest; der Umschalter ist deaktiviert **und**
`setBandLayout()` weist die Änderung ab. Manuelle Profile behalten, was der
Nutzer gewählt hat.

`syncGeneratedCurve()` ist neu und hält das Versprechen der Karte („Median aus
N Läufen") auch im laufenden EQ: bisher folgte nur die *Vorschau*, wenn sich die
Läufe änderten, während der EQ die alte Kurve weiterspielte. Das ist zugleich
der Migrationspfad — ein vor dieser Änderung ausgewähltes Adjusted Reference
sitzt auf 10 Bändern und wird beim nächsten Start einmalig neu gerechnet.
Bewusst **nicht** über `applyCompensation()` geführt, damit eine laufende
A/B-Umschaltung nicht heimlich abgebrochen wird.

### 3. Ehrlichkeits-Etiketten

- **Die 6-dB/Oktave-Flankengrenze** trug den Ton einer klinischen Konstante,
  obwohl sie eine Hausregel ist. Der Doc-Kommentar an
  `MAX_SLOPE_DB_PER_OCTAVE` sagt das jetzt ausdrücklich („no published work
  validates any particular inter-band gain-slope ceiling"), nennt die zwei
  Fakten, die sie einrahmen (NAL-R verschreibt selbst nur 0,31 × die
  Audiogrammflanke; Welligkeit über ±5 dB senkt messbar die bewertete
  Klangqualität) und die Bedingung, unter der ~8 dB/Oktave vertretbar wären:
  wenn der A/B zeigt, dass die Grenze eine *gemessene* Kerbe glattbügelt.
- **Cochleäre tote Regionen.** Neu: `DEAD_REGION_FLAG_DB = 70.0` und
  `CompensationResult.possibleDeadRegionFrequenciesHz`, gerechnet auf den
  **geräte-korrigierten** Schwellen (der Rohwert enthält noch den
  Kopfhörergang — sonst gäbe man dem Ohr die Schuld für die Elektronik; als
  Test festgehalten). Die Warnung heißt „Cannot check: 6 kHz" und sagt, dass
  die Zellen dort möglicherweise nicht mehr arbeiten, dass ein Anheben dann
  Lautheit und Rauheit statt Detail bringt, und dass **die App es nicht
  feststellen kann** — der TEN-Test braucht kalibriertes Gerät, das ein
  Bluetooth-Kopfhörer nicht ist. Keine Diagnose, kein Entwarnungstext: Die
  Warnung erscheint nie in der negativen Form („keine toten Regionen"), weil
  das eine Behauptung wäre, die niemand belegen kann. Unter 70 dB gibt es
  bewusst gar keine Logik.

### Zwei Fehler, die dabei aufgefallen sind (beide behoben)

1. **Die Kompensations-Vorschau war fest auf 10 Bänder verdrahtet.**
   `CompensationSection` las die Bandmitten aus `EqBands.CENTER_FREQUENCIES_HZ`
   (immer 10 Einträge) statt aus der Kurve. Bei 20 Bändern hätte sie das
   25-Hz-Band „31.5 Hz" genannt und die letzten zehn Bänder stillschweigend
   verschwiegen. Dieselbe Bauart wie Fehler 1 der Vormittagssitzung
   (`writeBand` las dieselbe Liste). Jetzt aus `result.eq.centersHz` /
   `result.eq.layout.extrapolatedIndices`.
2. **`CompensationProfileStore` speicherte die Bandzahl nicht.** `encodeEq`
   schrieb kein `layout`, `parseEq` erzwang `EqBands.COUNT` — ein gespeichertes
   20-Band-Preset kam als **zehn leere Bänder** zurück. Vorher schwer
   erreichbar, ab jetzt der Normalfall („Kopie der Referenz speichern"). Jetzt
   wird `layout` mitgeschrieben und eine abweichend lange Kurve resampelt statt
   verworfen — dieselbe Regel, die `EqSettingsStore` schon anwendet. Alte
   Einträge ohne `layout`-Schlüssel lesen sich unverändert (10 Bänder).
   **Ohne Unit-Test:** `org.json` + DataStore brauchen Robolectric, das
   `core-hearing` nicht hat, und die Build-Konfiguration gehört nicht zu meinen
   Dateien. Steht deshalb unten in der Geräteliste.

### Bewusst nicht gebaut

- **Der Gain-Deckel für geflaggte Bänder** (R6 zweite Hälfte: Band nicht über
  den Nachbarn heben). Der Auftrag verlangte die Warnung, nicht den Eingriff —
  und das ist auch die richtige Reihenfolge: der Deckel ändert, was Daniel
  hört, auf Verdacht, und alle klanglichen Änderungen stehen bis zum Hörtest.
  Die Warnung sagt derzeit ausdrücklich „compensation is applied there
  unchanged"; wenn der Deckel kommt, muss dieser Satz mit.
- **„Flat restoration"** (R3) und **WDRC/Kompression** (R4) — laut Auftrag
  ausgeschlossen.

### Was nachgezogen werden muss (fremde Dateien, deshalb nur gemeldet)

- **`COMPENSATION.md`** ist als autoritativ deklariert und beschreibt in §3.6
  die Abbildung „onto the 10 EQ band centers" sowie in §5 die Flankengrenze
  ohne Vorbehalt. Beides stimmt jetzt nicht mehr mit dem Code. Die Datei braucht
  zwei Absätze: das generierte Profil liegt auf `EqBandLayout.HALF_OCTAVE_20`,
  und die 6 dB/Oktave sind eine Hausregel ohne Literaturdeckung.
- **Backup/Restore kann das generierte Profil nicht mehr sichern.**
  `BackupCodec.hasUsableBands()` akzeptiert genau 10 Bänder (`BAND_COUNT = 10`,
  bewusst als Literal, „If the app ever moves to a different band layout, that
  becomes a schema migration"). Genau dieser Fall ist jetzt eingetreten: der
  Export schreibt 20 Werte, der Import lehnt sie mit einer Warnung ab. Immerhin
  nicht still — aber die EQ-Kurve fehlt nach dem Wiederherstellen. Das ist eine
  echte Schema-Migration im `transfer`-Paket und gehörte nicht zu meinen
  Dateien.

### Needs device verification — Kompensation

Ohne Gerät nicht belegbar, in dieser Reihenfolge wichtig:

1. **Liegt der EQ bei 20 Bändern überhaupt im Signalweg?** `DynamicsProcessing`
   wird beim Layoutwechsel neu gebaut. Das ist jetzt der *Normalfall* für das
   generierte Profil, also hängt mehr daran als vorher. Der Weg dahin ist der
   längst geschriebene, nie gelaufene `AcousticEqTest` — erst Lautsprecher,
   dann über die Klipsch.
2. **Adjusted Reference auswählen:** Der EQ muss auf 20 Bänder springen, die
   Bandliste 25 Hz … 18,1 kHz zeigen, Bandregler *und* Bandzahl-Umschalter
   müssen gesperrt sein, und „Applied" muss sofort stehen (nicht erst nach
   einem Tap auf „Apply to EQ").
3. **Migration:** App mit bereits aktivem Adjusted Reference auf 10 Bändern
   starten. Erwartung: einmaliger Wechsel auf 20 Bänder, Kurve neu angewendet,
   nichts anderes verloren. Das ist der Pfad mit dem meisten Race-Potenzial —
   `settings` und `activeProfileId` kommen aus demselben DataStore, ohne
   garantierte Reihenfolge; deshalb der `settingsLoaded`-Riegel in
   `syncGeneratedCurve()`. Nachweisbar nur am Gerät.
4. **Preset-Roundtrip mit 20 Bändern:** bei aktiver Referenz „Save" unter einem
   Namen, App neu starten, „Load". Muss dieselbe Kurve zurückgeben, nicht flach
   (siehe Fehler 2 oben — der Fix ist ungetestet).
5. **Live-Zeile beim Ziehen:** Der dB-Wert muss während der Bewegung mitlaufen,
   nicht erst beim Loslassen. Die Vorschau wird bei jedem `setIntensity` neu
   gerechnet, aber ob das auf dem Pixel flüssig bleibt, sagt nur das Gerät.
6. **Dead-Region-Warnung:** Erscheint bei Daniels vermuteten Schwellen
   voraussichtlich **nicht** (sie braucht > 70 dB an einer Messfrequenz). Wer
   die Darstellung sehen will, braucht einen künstlich hohen Lauf. Nicht
   erschrecken, wenn nichts kommt — das ist der erwartete Zustand.
7. **Lautstärke/Headroom nach dem Layoutwechsel:** Der Pre-Gain wird aus der
   neuen Kurve neu berechnet und liegt bei 20 Bändern etwas anders (im
   Beispielaudiogramm −9,0 dB bei 10 Bändern gegen −9,4 dB bei 20). Hörbar
   sollte das nicht sein; dass beim Umschalten nichts springt oder knackt,
   ist trotzdem zu prüfen.

---

# Helfer gehärtet + A2DP-Codec-Steuerung (20. August, später Nachmittag)

Stand danach: **445 Unit-Tests grün** (53 neue), `assembleDebug` läuft durch.
Nichts committet. Helfer-`VERSION` von 1 auf **2** erhöht — ein noch laufender
Helfer aus der alten APK wird deshalb sauber abgelehnt statt halb bedient, mit
genau dieser Begründung im Log.

**Kein einziger Punkt aus diesem Abschnitt ist am Gerät nachgewiesen.** Die
Liste am Ende sagt für jeden, was ihn beweisen würde.

## 1. Zweiter Faktor: der Helfer prüft jetzt die Aufrufer-uid

Vorher prüfte `PrivilegedService.exec` und `shutdown` **nur** den Token. Ein
geleakter Token — ein Screenshot des Setup-Screens, eine Shell-History — war
damit ein vollständiger Bypass, obwohl die Klassendokumentation zwei
unabhängige Faktoren behauptete.

Jetzt prüft jede Operation beides, an *einer* Stelle (`refuse()`), damit eine
neue Methode nicht mit nur einem der beiden hinzugefügt werden kann.

**Wie der Helfer die uid der App erfährt — und warum so:** über
`PackageManager.resolveContentProvider(AUTHORITY)` → `applicationInfo.uid`,
**nicht** über einen fest verdrahteten Paketnamen. Der Helfer reicht seinen
Binder an denjenigen weiter, dem die Authority gehört; die uid, der er trauen
muss, ist also per Definition genau dessen uid. Beide aus einer Tatsache
abgeleitet heißt: sie können nicht auseinanderlaufen. `APP_PACKAGE` ist nur
noch Fallback, und ein Test hält es an die Authority gekoppelt.

Weitere Entscheidungen, die im Code begründet sind:

- **Einmal beim Start aufgelöst, nicht pro Aufruf.** `exec` liegt auf dem
  heißen Pfad des Monitors. Wichtiger: eine Neuinstallation gibt der App eine
  *neue* uid, und ein Helfer, der stillschweigend mitwandert, wäre genau der
  Replay, den das hier verhindern soll. Nach einer Neuinstallation hat die App
  ohnehin einen neuen Token — richtig ist also, dass der alte Helfer niemanden
  mehr bedient.
- **Fail closed.** Lässt sich die uid nicht auflösen, startet der Helfer nicht.
  Trotzdem zu starten hieße, privilegierte Operationen hinter nur einem Faktor
  anzubieten, während die Dokumentation zwei verspricht.
- **Volle uids werden verglichen, nicht App-ids.** Eine Instanz der App in
  einem zweiten Android-Nutzer oder Arbeitsprofil wird damit *nicht* bedient —
  passend dazu, dass `handOver` den Provider ausdrücklich für User 0 holt.
- `version()` bleibt ungeprüft: es liefert eine Zahl, die ohnehin in der APK
  steht, und würde geprüft eine Versions-Fehlanpassung wie einen
  Authentifizierungsfehler aussehen lassen.

## 2. Token-Rotation, ohne einen verbundenen Helfer abzuwürgen

Die Falle steht genau da, wo die Aufgabe sie vermutet hat: der Provider
vergleicht gegen den *gespeicherten* Token, und ein bereits verbundener Helfer
schickt weiterhin den, mit dem er gestartet wurde. Den gespeicherten Wert
mitten in der Sitzung zu ersetzen, hätte jeden Folgeaufruf mit „bad token"
scheitern lassen — am Telefon nicht von einem abgestürzten Helfer zu
unterscheiden.

Deshalb zwei Slots statt einem:

| Slot | Was er ist | Wer ihn liest |
|---|---|---|
| **active** (`token`) | Token des verbundenen Helfers | jeder Live-Aufruf |
| **pending** (`pending_token`) | Token im zuletzt erzeugten ADB-Befehl | noch niemand |

**Die Reihenfolge, um die es geht:** Der Provider akzeptiert eine Übergabe mit
*einem von beiden*. Der aktive Token wird an genau einem Moment ersetzt — wenn
ein Helfer mit dem *pending*-Token angenommen wird. **Nie** beim Erzeugen eines
Befehls. Ein neuer Befehl kann einen laufenden Helfer also nicht stören; genau
das prüft `PrivilegedTokenTest`.

Im Provider ist die Reihenfolge zusätzlich: Versionsprüfung **vor** der
Promotion (ein veralteter Helfer darf den Token nicht unter einem
funktionierenden wegrotieren) → Promotion persistieren → Binder annehmen → erst
dann den ersetzten Helfer per `shutdown` stilllegen. Andersherum würde ein
funktionierender Helfer auf Verdacht getötet.

**Warum pro Sitzung und nicht pro Aufruf.** Wörtlich bei jedem `adbCommand()`
zu würfeln wäre mehr Rotation und schlechter: Setup-Screen und Wizard rendern
den Befehl beide, eine Recomposition hätte die Zeile entwertet, die der Nutzer
längst in der Zwischenablage hat, und er hätte einen Befehl eingefügt, den die
App gerade verworfen hat. Also: ein Token pro App-Prozess, dazu
`newAdbCommand()` für „gib mir ausdrücklich einen neuen". Ein Befehl aus einer
älteren Sitzung ist tot, sobald ein neuerer existiert — das war das Ziel.

Der Prefs-Key des aktiven Tokens heißt weiterhin `token`, damit eine bestehende
Installation ihren aktiven Wert behält.

## 3. `PrivilegedProvider` noch einmal durchgesehen

Drei echte Lücken, eine davon abstürzbar:

1. **Ein feindliches Bundle konnte die App abschießen.** `Bundle.getString`
   löst das Auspacken aus; enthält das Bundle eine Klasse, die dieser Prozess
   nicht laden kann, fliegt `BadParcelableException` — von einem Aufrufer, der
   zu dem Zeitpunkt noch gar nicht authentifiziert ist. Jetzt in `runCatching`,
   plus gesetztem ClassLoader, und eine Ablehnung statt eines Absturzes.
2. **Der Token-Vergleich war `!=`**, während der Helfer konstante Zeit nutzte.
   Beide teilen sich jetzt `PrivilegedProtocol.tokensMatch`. Ein Timing-Angriff
   über Binder ist hier kein realistisches Szenario; die *Asymmetrie* zwischen
   beiden Enden war das eigentliche Problem.
3. **Leer authentifizierte fast.** `tokensMatch` gibt bei null/leer immer
   `false` — „nie etwas gesetzt" darf niemanden einlassen.

Was geprüft und für in Ordnung befunden wurde: `query/insert/update/delete/
getType` bleiben absichtlich stumm statt zu werfen; `bulkInsert` und
`applyBatch` laufen über eben diese und tun damit nichts; die 4-Parameter-
Variante von `call` delegiert auf die geprüfte.

Über das Manifest lässt sich das **nicht** absichern: es gibt keine Permission,
die ausgerechnet `com.android.shell` hält und eine Fremd-App nicht anfordern
könnte. uid + Token im Code ist der einzige Weg.

## 4. Kein Inaktivitäts-Shutdown — entschieden, nicht vergessen

Steht jetzt als eigener Abschnitt in `PrivilegedServer`, mit Begründung: ein
Helfer, der sich nach einer ruhigen Stunde beendet, braucht zum Neustart einen
Rechner, ein Kabel und den ADB-Befehl. Die App säße bis dahin degradiert da.
Der Preis dafür, keinen zu haben, ist ein Leerlaufprozess mit einem Binder und
einem Looper — keine Wakelocks, keine Timer, kein Polling.

## 5. Codec-Steuerung: zwei typisierte Operationen, Lese- und Schreibpfad getrennt

`NoOpCodecController` ist keine Attrappe mehr, sondern der **Fallback**, wenn
kein Helfer läuft — und bleibt genau dafür bestehen: ohne Helfer kann nichts
gesetzt und, genauso wichtig, nichts *zurückgelesen* werden.

Auf `IPrivilegedService` kamen zwei Methoden dazu, mit typisierten Parametern
statt eines String-Kommandos:

- `codecStatus(token, address)` — **lesend**. Nötig, weil die Liste der vom
  Kopfhörer angebotenen Codecs nur aus `getCodecStatus()` kommt; `dumpsys`
  zeigt die aktuelle Konfiguration, nicht die Fähigkeiten der Gegenstelle.
- `setCodecPreference(token, address, codecType, sampleRateHz, bitsPerSample,
  channelMode, ldacQuality)` — **schreibend**. Die erste Operation dieser App
  überhaupt, die etwas am Telefon verändert.

**Die Trennung, die dabei wichtig war:** `PrivilegedProtocol.PrivilegedOperation`
nennt jeden Einstiegspunkt des Binders beim Namen und sagt für jeden, ob er
mutiert; `WRITE_OPERATIONS` ist eine eigene, kurze Liste, kein Flag auf dem
Lesepfad. `PrivilegedProtocolTest` spiegelt über das AIDL-Interface und
scheitert, wenn dort eine Methode auftaucht, die hier nicht eingeordnet ist —
ein neuer Einstiegspunkt auf einem shell-uid-Prozess kommt damit nicht
unbemerkt durch.

**Ehrlichkeit beim Ergebnis.** `HelperBluetooth` ruft
`setCodecConfigPreference` und **liest danach zurück**, im Geist von
`GlobalSettingsController.write`. Ein Aufruf, der nicht geworfen hat, heißt
angenommen, nicht befolgt. `matched` ist dreiwertig:

- `true` — Rückgelesenes deckt sich mit jedem angefragten Feld;
- `false` — deckt sich nicht. **Ob der Stack abgelehnt hat oder noch
  neu verhandelt, ist von der App aus nicht unterscheidbar**, also stehen beide
  Lesarten im Text, zusammen mit der verstrichenen Zeit;
- `null` — es wurde nichts angefragt, das war ein reines Lesen.

Die UI sagt entsprechend „Codec is now X — read back, not just requested" oder
„Codec still reads Y: …". Kein grüner Haken auf einem unbeobachteten Schreiben.

**aptX Adaptive fehlt bewusst** in der Auswahl: seine Codec-id ist ein
Vendor-Wert, der sich zwischen Android-Versionen bewegt hat — `CodecDecoding`
führt deshalb ein *Set* beobachteter ids. Lesen und benennen ist sicher;
schreiben hieße eine Zahl raten. Steht so auch in der UI, weil ein still
fehlender Codec für jemanden mit passendem Kopfhörer wie ein Bug aussieht.

**Das Attributions-Problem tritt hier ein zweites Mal auf.** Jede
Bluetooth-API trägt seit Android 12 eine `AttributionSource`, deren Paketnamen
das System gegen die uid des Aufrufers prüft — dieselbe Falle, die
`ContentResolver.call` erledigt hat. Der Adapter wird deshalb über
`BluetoothAdapter.createAdapter(AttributionSource)` gebaut, mit
`com.android.shell` ausdrücklich benannt. Fehlt die Methode, gibt es einen
dokumentierten Fallback auf den Default-Adapter — der voraussichtlich abgelehnt
wird, aber eine präzise Fehlermeldung erzeugt statt gar keiner.

## 6. Pro Gerät gespeichert und beim Verbinden angewendet

`DeviceProfile.codecPreference`, in derselben Form wie `developerOptions`:
gespeicherter Wunsch, gegen eine Registry validiert, beim Verbinden erneut
angewendet. Unterschied, der auch so in der UI steht: dieser hier ist
*wirklich* pro Gerät (die API nimmt ein `BluetoothDevice`), nur eben nicht
dauerhaft — der Stack verhandelt bei jedem Connect neu.

Der Codec-Schritt läuft im Applier **als letzter**: Neuverhandeln unterbricht
kurz die Wiedergabe, also passiert vorher alles, was das nicht tut.

`DeviceProfileApplier` sieht sonst nie eine rohe Adresse — es hasht sofort auf
einen `DeviceKey`. Ein Codec wird aber *auf* einem `BluetoothDevice` gesetzt.
Deshalb ist die Adresse ein Parameter von `applyNow(profile, address)` und kein
Feld, und ohne sie meldet der Schritt „nicht versuchbar" statt still zu
verschwinden. Beim manuellen „Apply now" sucht das ViewModel die rohe Adresse
unter den verbundenen Geräten, indem es jede erneut hasht — der Profilspeicher
lernt weiterhin keine MAC.

## 7. Aufgabe 3: es gibt keine weiteren ehrlichen Schlüssel

Geprüft und **abgelehnt**, mit Begründung in `BluetoothDeveloperOptions`: Was
in den Entwickleroptionen sonst noch unter Bluetooth steht, ist überwiegend gar
keine `Settings.Global`-Eintragung, sondern eine System-Property —
`persist.bluetooth.a2dp_offload.disabled` (A2DP-Hardware-Offload),
`persist.bluetooth.btsnooplogmode` (HCI-Snoop-Log),
`persist.bluetooth.maxconnectedaudiodevices`. WRITE_SECURE_SETTINGS fasst
System-Properties nicht an. Sie hier anzubieten würde einen gleichnamigen
`Settings.Global`-Schlüssel anlegen, der sauber schreibt, sauber zurückliest
und **nichts** tut — der grüne Haken für eine Option, die nie mit irgendetwas
verbunden war. Genau das, was der Rücklesevorgang verhindern soll.

`ble_scan_always_enabled` *ist* ein echter Schlüssel, aber eine
Standort-Einstellung: sie regelt Scans bei ausgeschaltetem Bluetooth. Ihn
umzulegen kann Standort für fremde Apps beschädigen.

Die Codec-Auswahlfelder auf demselben Screen sind weder Setting noch Property —
Androids eigene UI ruft `setCodecConfigPreference` direkt. Das ist der Grund,
warum Codecs über den Helfer laufen und nicht über diese Registry.

**Merkposten:** Der A2DP-Offload-Schalter ist der schmerzhafte Ausfall — er
wäre die Antwort auf „liegt unser EQ überhaupt im Signalweg" (siehe akustischer
Test). Eine Shell *kann* ihn setzen, `setprop` ist genau das, was ADB tut. Das
wäre aber eine zweite mutierende privilegierte Operation plus ein
Whitelist-Eintrag für ein schreibendes Kommando — eine Entscheidung, die
bewusst zu treffen ist und nicht nebenbei beim Ausfüllen einer Liste.

## 8. Dateien außerhalb des zugewiesenen Bereichs

Angefasst, weil die Aufgabe sie verlangt und es keinen ehrlichen Weg drumherum
gab — alle Änderungen rein additiv, keine Umbauten:

- `core-system/.../DeviceProfile.kt` — Feld `codecPreference` + Sanitizing,
  zwei neue `ProfileAction`-Fälle.
- `core-system/.../DeviceProfileStore.kt` — JSON-Encode/Parse dafür.
- `core-system/.../DeviceProfileApplier.kt` — Codec-Schritt, neuer
  Konstruktor-Parameter **mit Default** (alle bestehenden Aufrufer und Tests
  kompilieren unverändert) und `applyNow(profile, address = null)`.
- `core-system/.../SystemGraph.kt` — `installCodecPreferenceController(...)`,
  im Stil von `MonitorGraph.installShellRunner`.
- `app/.../BtDashboardApplication.kt` — installiert den Controller; der
  vorgezogene `PrivilegedBootstrap(this).token()` ist entfallen, weil der Token
  jetzt beim Erzeugen des Befehls entsteht.
- `app/.../ui/screens/monitor/MonitorViewModel.kt` — die in der Aufgabe
  genannte Aufrufstelle.

**Warum `:core-system` den Codec als String speichert:** `CodecFamily` liegt in
`:core-monitor`, und `:core-system` hängt nicht davon ab. Eine Modulabhängigkeit
dafür einzuziehen wäre ein größerer Eingriff als das Feature. Gespeichert wird
der Name der Enum-Konstante, validiert gegen `BluetoothCodecOptions` — dieselbe
Form wie bei `developerOptions`. Ein Test hält beide Listen aneinander, sonst
würde ein gespeichertes Profil je nach Seite still verworfen oder abgelehnt.

## 9. Eine Kleinigkeit, die noch keine Oberfläche hat

`PrivilegedBootstrap.newAdbCommand()` existiert und ist getestet, wird aber von
keinem Screen aufgerufen — Setup-Screen und Wizard rendern `adbCommand()`, das
pro Sitzung rotiert. Ein Knopf „Generate a new command" gehört dazu, für den
Fall, dass der Nutzer einen Token für kompromittiert hält und nicht auf einen
App-Neustart warten will. Die beiden Screens gehören in dieser Sitzung jemand
anderem, deshalb ist es hier nur notiert und nicht gebaut. Ein Aufruf genügt;
der laufende Helfer wird davon nicht gestört (siehe Abschnitt 2).

## Was am Gerät zu prüfen ist

Nach Wichtigkeit. Nichts davon konnte hier nachgewiesen werden — kein Gerät
angeschlossen.

1. **Startet der Helfer überhaupt noch?** Er verweigert den Start, wenn er die
   uid der App nicht auflösen kann. `resolveContentProvider` aus einem
   System-Context als uid 2000 heraus ist plausibel, aber ungeprüft. Der Befehl
   ohne `>/dev/null` laufen lassen; erwartet wird
   `privileged helper: serving as uid 2000 for app uid <10xxx>, version 2`.
   Bleibt die Zeile aus und kommt stattdessen „cannot resolve the uid", ist der
   Fallback über `APP_PACKAGE` ebenfalls gescheitert und die Auflösung muss
   anders gebaut werden.
2. **Weist der Helfer einen fremden Aufrufer wirklich ab?** Der eigentliche
   Punkt von Aufgabe 1. Nachweisbar nur mit einer zweiten App, die den Binder
   in die Hände bekäme — praktisch nicht herstellbar. Ersatzweise: im Log muss
   bei jedem *erfolgreichen* `exec` die App-uid der geprüften entsprechen.
3. **Rotation von Ende zu Ende.** Setup-Screen öffnen, Befehl laufen lassen,
   `dumpsys media.audio_flinger` über die App auslösen (Monitor-Tab) — muss
   funktionieren. Dann App neu starten, Setup-Screen erneut öffnen (neuer
   Befehl, neuer Token), den **alten** Befehl laufen lassen: muss abgelehnt
   werden („a token this app did not issue"). Danach den neuen laufen lassen:
   muss angenommen werden, und der vorherige Helferprozess muss verschwinden
   (`ps -A | grep btdash_privileged` → genau einer).
4. **Ist `setCodecConfigPreference` aus dem Helfer heraus erreichbar?** Das
   Kernstück von Aufgabe 2 und der wackeligste Teil. Vier Dinge können
   einzeln scheitern, und jedes meldet sich mit eigenem Text:
   - `BluetoothAdapter.createAdapter(AttributionSource)` existiert auf
     Android 16? Fehlt sie → „createAdapter is missing" auf stderr.
   - Bindet `getProfileProxy` im Helferprozess? Sonst „the A2DP profile did not
     bind within 8 s".
   - Wird die Attribution `com.android.shell` akzeptiert oder kommt dieselbe
     „does not match caller's uid"-Ablehnung wie damals bei
     `ContentResolver.call`? Das ist das wahrscheinlichste Scheitern.
   - Hat `BluetoothCodecConfig` auf diesem Build den Builder oder den
     Neun-Argumente-Konstruktor? Wenn keins → „neither a usable Builder nor the
     known constructor", und es wurde nichts versucht.
5. **Wird ein gesetzter Codec wirklich übernommen?** Bathys auf LDAC stellen
   (sie läuft laut Befund auf aptX, obwohl sie aptX HD anbietet). Die Meldung
   muss entweder „read back" sagen oder benennen, was stattdessen dasteht.
   **Gegenprobe nicht vergessen:** was `dumpsys` daneben als globalen
   `codecConfigOffloading`-Block zeigt, ist nicht der ausgehandelte Codec —
   das ist die Falle aus Fehler 7.
6. **Reicht das Zeitfenster von 2,5 s?** `HelperBluetooth.SETTLE_BUDGET_MS`.
   Braucht die Neuverhandlung länger, meldet die App korrekt „noch nicht
   beobachtet", aber der Nutzer sähe es als Fehlschlag. Wenn ein zweiter Blick
   Sekunden später den gewünschten Codec zeigt, ist das Budget zu klein.
7. **Kommt die Liste der wählbaren Codecs an?** `codecStatus` liefert
   `getCodecsSelectableCapabilities`. Sichtbar am Diagnose-Lauf: „Codec
   cycling" darf nicht mehr „needs privileged access" melden, wenn der Helfer
   läuft. Meldet es das trotzdem, kommt die Fähigkeitsliste leer zurück.
8. **Anwenden beim Verbinden.** Codec im Geräteprofil setzen, Kopfhörer
   trennen und neu verbinden. Die Zeile unter „Device profiles" muss den
   Codec-Schritt als letzten nennen. Hängt am Receiver-Fix vom Vormittag, der
   selbst noch nicht bei einem Verbindungswechsel bei laufender App beobachtet
   wurde.
9. **Unterbricht das Setzen hörbar?** Der Schritt läuft absichtlich zuletzt.
   Ob der Aussetzer beim Verbinden auffällt, sagt nur das Ohr.
10. **Die abgelehnten Entwickleroptionen gegenprüfen.** `adb shell getprop |
    grep bluetooth` gegen `adb shell settings list global | grep bluetooth`.
    Erwartung: `a2dp_offload.disabled`, `btsnooplogmode` und
    `maxconnectedaudiodevices` erscheinen nur als Property. Fällt einer davon
    doch in `Settings.Global`, gehört er in die Registry und der Absatz im Code
    ist zu korrigieren.

---

# Einstellungen bleiben stehen (20. August, Abend)

**Aufgabe 1 der offenen Punkte.** Der Bluetooth-Tab ersetzte die gesamte
Einstellungskarte durch einen Satz, sobald nichts verbunden war — der Screen sah
genau dann leer aus, wenn man wissen wollte, was beim Aufsetzen der Kopfhörer
passieren würde.

Jetzt zeichnet jeder Zustand dieselbe Karte. Unterschiedlich ist nur, ob die
Felder Werte eines Geräts tragen und Eingaben annehmen.

## Was geändert wurde

`ProfileEditorCard` hat zwei neue Parameter, beide mit Vorgabewert, also für
alle bestehenden Aufrufer folgenlos:

- `enabled: Boolean = true` — durchgereicht bis in jedes Bedienelement:
  Textfeld, Slider, Schalter, sämtliche `PickerMenu`s sowie `AbsoluteVolumeEditor`,
  `DeveloperOptionsEditor`, `CodecEditor` und den Speichern-Knopf.
- `note: String? = null` — eine Zeile unter der Überschrift. Regel: **wer
  `enabled = false` übergibt, sagt auch warum.** Ein graues Bedienelement ohne
  Begründung ist eine Fehlermeldung, die noch niemand geschrieben hat.

`PickerMenu` bekam neben dem `enabled` am Knopf auch `expanded && enabled` am
`DropdownMenu` — ein bereits offenes Menü soll nicht überleben, wenn die Karte
in den inerten Zustand wechselt.

`ConnectedDeviceSettings` in `BluetoothScreen.kt` hat jetzt drei inerte
Zustände statt zweier Textabsätze: *lädt noch*, *nichts verbunden* und
*Adresse redigiert*. Der dritte ist inhaltlich unverändert — das Profil greift
weiterhin beim Verbinden, nur der Inline-Editor braucht die Adresse.

## Absichtliche Entscheidung

Die Karte zeigt im leeren Zustand **Vorgabewerte, nicht die Werte des zuletzt
gesehenen Geräts.** Ein Kopfhörer-Profil unter einer Überschrift „Device
settings" anzuzeigen, während dieser Kopfhörer nicht verbunden ist, wäre eine
Behauptung über etwas, das gerade nicht stattfindet. Der Knopf „Turn on now"
für Absolute Volume ist im inerten Zustand mit abgeschaltet, obwohl er
systemweit auch ohne Verbindung funktionieren würde — erreichbar bleibt er über
den Screen „Device profiles".

## Stand

`./gradlew testDebugUnitTest`: **447 Tests, 0 Fehler** (vorher 445; der
erzwungene Lauf mit `--rerun-tasks` bestätigt die Zahl, ohne ihn meldet Gradle
alles `UP-TO-DATE` und die alten XML-Reports zählen falsch).

Neu: `app/src/test/.../ui/screens/bluetooth/BluetoothScreenSettingsTest.kt`,
zwei Tests — die Bedienelemente stehen ohne Verbindung auf dem Screen, und
Speichern ist dabei abgeschaltet. Der Test prüft Abschnittsüberschriften
(„Absolute volume", „Bluetooth codec"), nicht die erklärenden Sätze; anders
lässt sich „alle Einstellungen, abgeschaltet" nicht von „eine Zeile Text"
unterscheiden.

**Am Gerät nicht gesehen** — beim Schreiben war kein Telefon angeschlossen
(`adb devices` leer, Windows meldet Pixel und ADB-Interface als nicht
anwesend). Zu prüfen: dass die Felder beim Verbinden tatsächlich umschalten und
sich füllen, und dass der Wechsel nicht als neuer Screen wirkt.

---

# Am Gerät geprüft (20. August, Abend)

Pixel 8 Pro, Android 16 / SDK 36, App `0.3.0` (Stand 14:02), Helfer läuft
(`btdash_privileged`, uid shell). **Verbindung über Wireless Debugging**, nicht
USB — `adb devices` bleibt leer, bis `adb mdns services` das Gerät gefunden hat;
danach verbindet adb von selbst. Am USB-Bus war das Telefon nicht (Windows
meldet alle vier `VID_18D1`-Einträge als `Present: False`).

## Punkt 10 erledigt: die Entwickleroptionen sind Properties

`settings get global` liefert für alle drei **`null`**:
`a2dp_offload.disabled`, `btsnooplogmode`, `maxconnectedaudiodevices`.
Keiner davon fällt in `Settings.Global`. Die Registry im Code liegt richtig, der
Absatz muss nicht korrigiert werden.

## Die Codec-Anzeige der App stimmt — nachgemessen

Die App zeigt für die Bathys **aptX, 48 kHz · 16 bit**. Der Stack sagt dasselbe:

```
=== A2dpStateMachine for …35:6A (Active) ===   STATE_CONNECTED
  mCodecConfig: {codecName:AptX, mSampleRate:0x2(48000),
                 mBitsPerSample:0x1(16), mChannelMode:0x2(STEREO)}
```

Der Lesepfad ist damit am Gerät belegt. Fehler 7 ist wirklich behoben.

### Fehler 7, noch einmal getreten — diesmal beim Nachmessen

Ein erster Versuch, das gegenzuprüfen, lief mit
`grep -m1 "mCodecConfig:"` über den Dump und meldete LDAC 96 kHz/32 bit. Das war
falsch, und zwar auf eine Art, die es wert ist notiert zu werden:

**`dumpsys bluetooth_manager` gibt einen `A2dpStateMachine`-Block pro gebundenem
Gerät aus, und der erste im Dump ist nicht der verbundene.** Hier stand
`…37:8F` (`STATE_DISCONNECTED`) vor `…35:6A` (`STATE_CONNECTED, Active`) — die
LDAC-Werte gehörten dem getrennten Gerät. Wer den Dump von Hand prüft, muss den
Block über die Adresse **oder** über `STATE_CONNECTED` suchen, nie über das
erste Vorkommen.

### Punkt 5 stimmt zur Hälfte, und die andere Hälfte ändert den Test

„Sie läuft auf aptX" — **richtig**. „Obwohl sie aptX HD anbietet" — **falsch**.
Für die Bathys steht in

```
mCodecsSelectableCapabilities:  AptX (44100|48000, 16 bit), AAC, SBC
```

Mehr nicht. **Weder LDAC noch aptX HD sind für dieses Gerät wählbar.** Beide
tauchen nur in `mCodecsLocalCapabilities` auf — das ist, was das *Telefon* kann,
nicht worauf sich die beiden geeinigt haben.

Folge für den Test: **„Bathys auf LDAC stellen" kann nicht gelingen** und wäre
als Prüfung des Schreibpfads wertlos — ein Fehlschlag ließe sich nicht davon
unterscheiden, dass der Kopfhörer den Codec schlicht nicht anbietet. Ein
belastbarer Test für Punkt 4 muss innerhalb der wählbaren Menge bleiben, also
etwa aptX → AAC → aptX, oder bei aptX die Sample-Rate zwischen 44,1 und 48 kHz
wechseln.

**Noch nicht geprüft:** ob `setCodecConfigPreference` aus dem Helfer heraus
durchgeht (Punkt 4) — das ist ein Schreibvorgang auf eine laufende Verbindung
und wurde bewusst nicht ohne Absprache ausgelöst.

## Punkt 1 belegt, Punkt 3 halb — und ein neuer Fund

Nach `adb install -r` des aktuellen Stands (20:18) meldete die App **„The
privileged helper is not running"**, obwohl der alte Helfer noch lief. Richtig
so: die Neuinstallation erzeugt einen neuen Token, der laufende Helfer hält den
alten. Genau die Rotation aus Abschnitt 2.

Den neuen Befehl aus dem Setup-Screen ausgeführt, Ausgabe nach
`/data/local/tmp/helper.log` statt `/dev/null`:

```
privileged helper: serving as uid 2000 for app uid 10440, version 2
```

**Damit ist Prüfpunkt 1 erledigt.** Die uid-Auflösung über
`resolveContentProvider` aus dem System-Context heraus funktioniert auf
Android 16 — sie war „plausibel, aber ungeprüft".

### Neu: der alte Helfer stirbt nicht

`ps -A | grep btdash_privileged` zeigt danach **zwei** Prozesse:

```
shell  22673  btdash_privileged   <- alt, Token von vor der Neuinstallation
shell  28706  btdash_privileged   <- neu, verbunden
```

Auch vier Sekunden nach dem Start der App und dem Vordergrundwechsel bleiben es
zwei. Prüfpunkt 3 verlangt ausdrücklich „genau einer".

Das ist kein Sicherheitsloch — der alte Helfer nimmt nur seinen alten Token an,
und den hat niemand mehr. Aber es ist ein Prozess auf der Shell-uid, der ohne
Zweck weiterläuft und erst beim Reboot verschwindet. Wer Abschnitt 2 fortsetzt:
der Ablösepfad greift offenbar nur, wenn ein *verbundener* Helfer abgelöst wird,
nicht wenn die App selbst neu installiert wurde und den alten Token gar nicht
mehr kennt. Der neue Helfer weiß nichts vom alten, also kann nur der alte sich
selbst beenden — und dafür bräuchte er einen Anstoß, den er nicht bekommt.

### Codec-Abschnitt fehlte im installierten Build

Die APK von 14:02 hatte gar keine Codec-Oberfläche: die Profilkarte sprang von
den Entwickleroptionen direkt zu „Apply automatically". Der Schreibpfad (Punkt 4)
war damit nicht prüfbar. Nach dem Neubau ist der Abschnitt da.

Ausgangslage für den eigentlichen Test steht: Bathys verbunden,
`mIsPlaying: true`, aptX 48 kHz/16 bit, Helfer läuft.

---

# PAUSE — Übergabe, 20. August 2026, abends

## Zustand

| | |
|---|---|
| HEAD | `aaa1a24` — **unverändert**, seit dem 19.8. nichts committet |
| Arbeitsbaum | 51 geändert, 24 neu. Die gesamte Arbeit vom 18.–20.8. liegt uncommittet |
| Tests | `./gradlew testDebugUnitTest` → **447, 0 Fehler** (erzwungen geprüft) |
| APK | `0.3.0`, gebaut und installiert 20:18, Stand = Arbeitsbaum |
| Gerät | Pixel 8 Pro, Android 16 / SDK 36, **über WLAN-Debugging** |
| Helfer | läuft (PID 28706) — plus ein verwaister alter (22673) |
| Bathys | verbunden, aktiv, spielt, aptX 48 kHz/16 bit |

**Nichts ist halbfertig.** Kein offener Umbau, keine auskommentierte Stelle.

## In dieser Sitzung passiert

1. **Aufgabe 1 gebaut** — Einstellungen bleiben ohne Verbindung sichtbar.
   Eigener Abschnitt „Einstellungen bleiben stehen" weiter oben. +2 Tests.
2. **Prüfpunkt 1 belegt** — der Helfer löst die App-uid auf, Android 16.
3. **Prüfpunkt 10 belegt** — die drei Entwickleroptionen sind Properties,
   nicht `Settings.Global`.
4. **Codec-Lesepfad belegt** — App und Stack sagen beide aptX 48/16.
5. **Zwei Fehler gefunden** — der verwaiste Helfer nach Neuinstallation, und
   die falsche aptX-HD-Annahme in Punkt 5.

## Sofort weitermachen bei: dem Codec-Schreibtest

Alles steht bereit, nichts davon muss neu aufgebaut werden. **Aber der Test
muss anders aussehen als geplant.**

Für die Bathys stehen in `mCodecsSelectableCapabilities` nur **aptX, AAC, SBC**.
LDAC und aptX HD sind für dieses Gerät **nicht wählbar** — sie stehen nur in den
lokalen Fähigkeiten des Telefons. „Auf LDAC stellen" kann deshalb nicht gelingen,
und ein Fehlschlag ließe sich nicht davon unterscheiden, dass der Kopfhörer den
Codec nicht anbietet. **Der Test wäre wertlos.**

Belastbar ist nur, was innerhalb der wählbaren Menge bleibt:

- aptX → AAC → aptX, oder
- aptX bei 44,1 statt 48 kHz.

Gegenlesen immer über den Block der **verbundenen** Adresse (siehe die Falle
unten), und Punkt 9 („unterbricht das Setzen hörbar?") nur mit laufender Musik.

## Danach, in Daniels Reihenfolge

**1. Farbwähler mit Metallic-Ableitung.** Sein Befund: eigene Akzentfarben
stellen manche Details um, andere nicht. Die Ursache steht in zwei Zeilen in
`Theme.kt`:

```kotlin
val scheme = if (theme == AppTheme.EDGY) colorScheme.withAccent(palette) else colorScheme
ProvideGoldAccents(enabled = theme == AppTheme.EDGY, palette = palette) {
```

Außerhalb von Edgy ist der Akzent **vollständig abgeschaltet**: Schalter, Slider
und gefüllte Knöpfe gehören Material You, und alles was `LocalGoldAccents.current`
abfragt (Panel-Rahmen, Titel-Verläufe, Readouts, `GoldOutlinedButton`) zeichnet
ohne Metall. Das ist als Absicht kommentiert — Daniel will es anders.

Zweitens: `AccentChoice` in `AppearanceStore` ist ein **Enum mit festen Farben**.
Eine freie Farbe lässt sich derzeit nicht speichern.

`MetalPalette.from(color)` existiert bereits und leistet genau das Gewünschte.
Zu tun ist also:

- `AccentChoice` durch eine gespeicherte ARGB-Farbe ersetzen, Presets als
  Schnellwahl behalten;
- Farbwähler in Settings → Appearance mit Live-Vorschau der Rampe;
- den Akzent in **allen** Themes anwenden, nicht nur in Edgy;
- Kontrast mitziehen — 4,5:1 ist Minimum, `outline` lag schon einmal bei 3,1:1
  (`ContrastTest`).

**2. Shizuku restlos entfernen.** Ausdrücklicher Wunsch: „wir wollen unsere
access-möglichkeit und sonst nichts". Hängen noch dran:
`ShizukuQualityReportSource` (BQR) und `SystemGraph.shizuku`
(Onboarding, Zustandsanzeige). Der Settings-Screen zeigt Shizuku derzeit als
„Ready" neben dem eigenen Helfer.

**3. Der verwaiste Helfer.** Siehe eigenen Abschnitt oben.

**4.** Danach die unveränderten Punkte 2–6 der alten Liste (Hörtest-Frequenzen,
Entwickleroptionen pro Gerät, Monitoring-Graphen, Design auf den letzten vier
Screens).

## Zwei Fallen, die diese Sitzung gekostet haben

**`dumpsys bluetooth_manager` gibt einen `A2dpStateMachine`-Block pro gebundenem
Gerät aus, und der erste ist nicht der verbundene.** `grep -m1 mCodecConfig`
liefert Werte eines getrennten Kopfhörers. Immer über die Adresse oder über
`STATE_CONNECTED` suchen.

**Gradle meldet `UP-TO-DATE` und die alten XML-Reports zählen falsch.** Für eine
belastbare Testzahl `--rerun-tasks` erzwingen.

## Anschluss ans Gerät

Kein USB — Wireless Debugging. `adb devices` ist leer, bis

```
adb mdns services
```

das Gerät gefunden hat; danach verbindet adb von selbst. Der `adb connect` auf
den dort genannten Port wird abgelehnt, das ist normal und kein Fehler.

---

# Diagnose-Lauf am Gerät (20. August, 23:0x) — Punkt 7 beantwortet

Über USB, Bathys verbunden und spielend, Helfer läuft, App meldet
**„App helper: Running"**. Diagnose aus dem Monitor-Tab gestartet.

```
[OK]      Connection check:  Connected as Focal Bathys and active for media
[OK]      Codec negotiation: Negotiated aptX · 48 kHz · 16 bit
[skipped] Codec cycling:     Codec switching needs privileged access we do not
                             have yet — verify on-device before enabling
```

**Prüfpunkt 7 ist damit beantwortet, und zwar negativ: die Fähigkeitsliste kommt
leer zurück.** `availableCodecs()` liefert nichts, also wurde `selectCodec()`
kein einziges Mal aufgerufen — im Helfer-Log steht kein `setCodecPreference`.
Punkt 4 (kommt `setCodecConfigPreference` durch?) bleibt daher weiterhin
**ungeprüft**: es wurde nie versucht.

## Die Meldung ist an dieser Stelle nachweislich falsch

`PrivilegedCodec` sagt es selbst:

> `controller` deliberately falls back to `NoOpCodecController` … the diagnostic
> words an empty list as "needs privileged access we do not have", which is the
> truth when the helper is absent **and a lie when it is present**.

Der Helfer ist präsent, die App zeigt ihn als „Running", und der Nutzer liest
trotzdem „kein privilegierter Zugriff". Das ist genau der Fall, den der
Kommentar ausschließen wollte. Der Text muss zwischen „kein Helfer" und
„Helfer da, Liste trotzdem leer" unterscheiden.

## Was als Ursache ausscheidet

- **Der Filter nicht.** `A2dpCodecMasks.writableFamilies` verwirft nur
  `APTX_ADAPTIVE` und `UNKNOWN`; SBC, AAC, aptX, aptX HD, LDAC, LC3 und Opus
  sind alle schreibbar. Die selektierbaren Codecs der Bathys liegen sämtlich
  darin.
- **Die gekürzte Adresse nicht.** `codecStatus 35:6A` im Helfer-Log ist eine
  bewusste Log-Schwärzung durch `tail(target)`, keine abgeschnittene Adresse.
- **Ein fehlender Helfer nicht.** `codecStatus` steht im Helfer-Log, der Aufruf
  ist also durchgegangen.

Bleibt: `read(address)` liefert `CodecCallResult.Unavailable`. Der Grund geht in
`Log.i(TAG, "codec capabilities unreadable: …")` und war beim Nachsehen schon
aus dem Ringpuffer gerollt.

**Nächster Schritt, konkret:**

```bash
adb logcat -c
# Diagnose im Monitor-Tab starten, ~30 s warten
adb logcat -d -t 2000 | grep "capabilities unreadable"
```

Der dortige Text benennt, welche der vier Bruchstellen aus Punkt 4 zugeschlagen
hat (`createAdapter` fehlt / A2DP-Proxy bindet nicht / Attribution abgelehnt /
kein brauchbarer `BluetoothCodecConfig`-Konstruktor).

## Zwei Nebenbefunde

**Die Monitor-Timeline zeichnet auf.** 28 Samples, 5 Events, darunter „Playback
started", „is now the active device", „Codec is now aptX HD" und „Focal Bathys
connected". Der offene Verifikationspunkt „ob die Timeline nach dem
Receiver-Fix Events aufzeichnet" ist damit **erledigt**. Die Sampling-Anzeige
wechselt sichtbar zwischen `burst`, `active` und `deep`.

**Der globale EQ-Attach steht.** Settings zeigt „EQ attachment: **Global** —
Attached to the output mix — reaches every app." Achtung bei Shizukus Ausbau:
Shizuku steht daneben auf „Ready", und laut Abschnitt „Noch offen" prüft
`GlobalAttachmentStrategy.activate()` weiterhin `ShizukuState.Ready`. Es ist
also gut möglich, dass dieser Attach **über Shizuku** läuft und mit dessen
Entfernung wegbricht. Vor dem Ausbau prüfen.

## Korrektur an meinem eigenen Eintrag von heute Abend

Weiter oben steht, die Bathys biete aptX HD nicht an. **Das war falsch** — es
war eine Momentaufnahme. Der ausgehandelte Codec wechselte über den Abend
zwischen **LDAC 96 kHz/32 bit**, **aptX 48 kHz/16 bit** und **aptX HD**, je
Verbindung. `mCodecsSelectableCapabilities` ist nicht konstant, sondern das
Ergebnis der jeweiligen Aushandlung. Wer daraus etwas ableitet, muss den Wert
im selben Moment lesen wie den Test — nicht aus einem älteren Dump.

## Punkt 4, zweiter Anlauf: eingekreist, nicht gelöst

Zweiter Diagnose-Lauf mit **geleertem Logcat** (`adb logcat -c`), Bathys
verbunden und spielend. Ergebnis unverändert `[skipped] Codec cycling`.
Der Soak davor lief sauber: 17 Samples, keine Drops, „Most stable codec: aptX,
0 drop(s) over 183s".

### Die Ausschlusskette

Drei Wege können zu einer leeren Codec-Liste führen. Zwei sind widerlegt:

| Weg | Spur, die er hinterlassen müsste | Gefunden? |
|---|---|---|
| Echter Controller, Lesefehler | `Log.i("PrivilegedCodec", "codec capabilities unreadable: …")` | **nein** |
| Echter Controller, Leseerfolg | `privileged helper: codecStatus …` im Helfer-Log | **nein** |
| `NoOpCodecController` | gar nichts — leere Liste, kein Log, kein Helferaufruf | passt |

`adb logcat -d -s PrivilegedCodec:V PrivilegedShell:V` liefert **nichts**, und
das Helfer-Log zeigt in diesem Lauf ausschließlich `exec dumpsys
bluetooth_manager`. **Also greift `PrivilegedCodec.controller()` auf
`NoOpCodecController` zurück.**

### Und genau das dürfte nicht sein

`controller()` ist `installed?.takeIf { it.isAvailable() } ?: NoOpCodecController`.
Beide Vorbedingungen sind am Gerät nachweislich erfüllt:

- **`installed` ist gesetzt.** `BtDashboardApplication` ruft
  `PrivilegedCodec.install(codecController)` bedingungslos auf, mit demselben
  Objekt, das auch `SystemGraph` bekommt.
- **`activeToken() != null`.** `KEY_ACTIVE` ist wörtlich `"token"`, und
  `run-as … cat shared_prefs/privileged.xml` zeigt
  `<string name="token">1808625e-…</string>`.
- **`PrivilegedConnection.isConnected` ist wahr.** Settings zeigt „App helper:
  **Running**", und diese Anzeige hängt an genau demselben Feld
  (`PrivilegedShellRunner.isAvailable` → `PrivilegedConnection.isConnected`).

Drei erfüllte Bedingungen, und trotzdem NoOp. Der Widerspruch lässt sich von
außen nicht weiter auflösen — hier hört Quelltextlesen auf.

### Nächster Schritt: eine Zeile instrumentieren

In `PrivilegedCodec.controller()` vor dem Rückgabewert:

```kotlin
Log.i(TAG, "controller(): installed=${installed != null} " +
    "connected=${PrivilegedConnection.isConnected} " +
    "available=${installed?.isAvailable()}")
```

Bauen, installieren, **Helfer neu starten** (die Neuinstallation dreht den
Token, siehe oben), Diagnose fahren, Zeile lesen. Das benennt die falsche
Bedingung in einem Durchgang.

**Achtung beim Nachstellen:** nach jedem `adb install -r` ist der alte Helfer
wertlos und ein neuer muss mit dem frischen Befehl aus dem Setup-Screen
gestartet werden. Wer das vergisst, misst NoOp aus einem ganz anderen Grund und
hält das Rätsel für gelöst.

### Nebenbei bestätigt

Die Meldung „Codec switching needs privileged access we do not have yet"
erscheint bei **laufendem, verbundenem Helfer**. Unabhängig von der Ursache ist
der Text falsch und gehört aufgeteilt — der Kommentar in `PrivilegedCodec` sagt
das selbst voraus.

---

# Punkt 4 GELÖST — der Codec-Schreibpfad läuft (21. August, nachts)

`[OK] Codec cycling: Applied: aptX, AAC, SBC` — am Gerät, mit Rücklesen.
`selectCodec` liefert eine Familie nur zurück, wenn das Rücklesen zustimmte,
also sind **Punkt 4, 5 und 7 zusammen erledigt**.

## Die Ursache war dreiteilig, und jeder Teil war unsichtbar

**1. `createAdapter(AttributionSource)` gibt es auf Android 16 nicht mehr.**
Reflection am Gerät zeigt als statische Fabriken nur `createAdapter(Context)`
und `getDefaultAdapter()`. Die alte Suche nach der `AttributionSource`-Signatur
warf `NoSuchMethodException`, und das landete in einem `getOrNull()`.

**2. Der ServiceManager-Name ist `bluetooth_manager`, nicht `bluetooth`.**
`Context.BLUETOOTH_SERVICE` ist `"bluetooth"` und bezeichnet einen anderen
Binder. Wer den nimmt, bekommt „ServiceManager has no bluetooth".

**3. `BluetoothFrameworkInitializer` war nie befüllt.** In einer echten App
setzt `ActivityThread` diesen Static, bevor App-Code läuft. In einem blanken
`main()` tut das niemand, und der erste privilegierte Aufruf stirbt an

```
Attempt to invoke virtual method
BluetoothServiceManager.getBluetoothManagerServiceRegisterer()
on a null object reference
```

was wie ein fehlendes Gerät aussieht, nicht wie ein fehlender Initialisierer.

**Der Weg, der funktioniert:** `BluetoothFrameworkInitializer` mit einer frisch
gebauten `BluetoothServiceManager`-Instanz füllen, dann den Konstruktor
`BluetoothAdapter(IBluetoothManager, Context, AttributionSource)` mit einer
AttributionSource auf `com.android.shell` benutzen — den `IBluetoothManager`
über `ServiceManager.getService("bluetooth_manager")`. Bestätigt im Log:
`privileged helper: adapter attributed to com.android.shell`.

## Warum das so lange gedauert hat — und was dagegen jetzt drin ist

Jede der drei Bruchstellen verschwand in `runCatching { }.getOrNull()`. Nach
außen kam eine einzige Meldung, „no BluetoothAdapter is reachable", die weder
den Weg noch den Grund nannte. **Jetzt sammelt `adapterOrBuild` die Fehler
aller drei Wege und hängt sie an die Ausnahme**; jeder Weg meldet sich einzeln
auf stderr. Ohne das wäre keiner der drei Punkte auffindbar gewesen.

## Zwei Fehler, die dabei ans Licht kamen

**Die Skip-Meldung log.** „Codec switching needs privileged access we do not
have yet" erschien bei laufendem, verbundenem Helfer — der Kommentar in
`PrivilegedCodec` sagt selbst voraus, dass das dann eine Lüge ist. Die Meldung
unterscheidet jetzt zwischen „Helfer läuft nicht" und „Helfer läuft, meldet
aber keine wählbaren Codecs". Abgesichert durch
`an empty list from a live controller does not blame the privilege`.

**Der Diagnose-Lauf war zerstörerisch.** `setCodecConfigPreference` nagelt den
gesetzten Codec auf `CODEC_PRIORITY_HIGHEST` fest. Das Cycling ließ das Gerät
auf dem *zuletzt* probierten Codec stehen — am echten Bathys endete ein Lauf mit
`mCodecConfig: {codecName:AAC, mCodecPriority:1000000}`, und **jede** spätere
Verbindung nahm danach AAC statt des sonst ausgehandelten aptX HD. Eine Prüfung,
die ihren Messgegenstand verschlechtert, ist schlimmer als keine.

`cycleCodecs` nimmt jetzt `restoreTo` und stellt den Codec her, auf dem die
Verbindung stand. Best effort, nie Teil des Urteils, und die Meldung sagt, wo
die Verbindung gelandet ist, falls der Restore scheitert. Zwei Tests:
`cycling restores the codec the link started on` und die nachgezogene Erwartung
im bestehenden Cycling-Test.

## Punkt 9 beantwortet: ja, hörbar — und schlimmer

Daniel während der Läufe: „sound was cutting out a little bit" und
**„playback changed to my phone"**, obwohl die Kopfhörer verbunden blieben. Die
Neuverhandlung unterbricht den A2DP-Stream so weit, dass der Player die Ausgabe
auf das Telefon zurückwirft. Wer das Cycling in eine Oberfläche hängt, muss das
vorher ansagen — und es gehört nicht in einen Lauf, den jemand nebenbei startet,
während er Musik hört.

## Offen geblieben: die Nadel wieder lösen

Der Restore setzt den alten Codec, aber immer wieder mit
`CODEC_PRIORITY_HIGHEST`. Es gibt derzeit **keinen Weg zurück zur automatischen
Auswahl** — „Leave alone" im Profil heißt „nicht anfassen", nicht „dem System
zurückgeben". Dafür bräuchte es einen Schreibvorgang mit
`CODEC_PRIORITY_DEFAULT` (0) statt HIGHEST, durch Protokoll und Oberfläche
gezogen. Solange das fehlt, bleibt ein Gerät nach dem ersten Setzen für immer
festgenagelt, und das einzige Mittel dagegen ist Entkoppeln und neu Verbinden.

**Korrektur, gemessen:** ein **Aus- und Einschalten des Kopfhörers genügt**.
Nach dem Neustart der Bathys stand sie wieder auf aptX mit Priorität 3001, also
der normalen Stufe — die 1000000er-Nadel war weg, und die Ausgabe lag wieder am
Headset. Entkoppeln oder Entwickleroptionen sind nicht nötig.

**Der Pin ist damit nicht persistent.** Er überlebt eine Neuverbindung des
Profils, aber keinen Power-Cycle des Geräts. Für die Codec-Funktion ist das die
wichtigere Erkenntnis: eine gesetzte Vorliebe hält **nicht** dauerhaft, sondern
nur bis der Kopfhörer stromlos war. Genau deshalb speichert die App den Codec als
Wunsch und wendet ihn bei jedem Verbinden neu an — dieses Design ist damit am
Gerät bestätigt und nicht bloß vorsichtig.

Der fehlenden Option "zurück zur Systemauswahl" nimmt das die Dringlichkeit,
nicht die Berechtigung: niemand soll seinen Kopfhörer neu starten müssen, um eine
App-Einstellung zurückzunehmen.

**Nebenbefund zur wechselnden Codec-Liste:** direkt nach dem Neustart führt die
selektierbare Liste nur **aptX, AAC, SBC** — obwohl die lokalen Fähigkeiten des
Telefons aptX HD (Priorität 4001) und LDAC (5001) kennen. Die Bathys bietet je
nach Verbindung unterschiedlich viel an. Wer Codec-Logik schreibt, darf die
selektierbare Menge nie als konstant annehmen.

---

# PAUSE 2 — Übergabe, 21. August 2026, 00:30

## Zustand

| | |
|---|---|
| HEAD | `aaa1a24`, unverändert — weiterhin nichts committet |
| Arbeitsbaum | 53 geändert, 24 neu |
| Tests | **449, 0 Fehler** (`./gradlew testDebugUnitTest`) |
| APK auf dem Pixel | Build von **00:08** |
| Gerät | Pixel 8 Pro über **USB**, Bathys verbunden, aptX 48/16 |
| Helfer | läuft, App zeigt „App helper: Running" |

**Achtung:** die APK auf dem Telefon ist **einen Build alt**. Sie enthält den
Adapter-Fix und den Codec-Restore, aber **nicht** die korrigierte
Zusammenfassungszeile („requested but not confirmed"). Vor dem nächsten
Gerätetest neu bauen und installieren — und danach **den Helfer neu starten**,
weil die Neuinstallation den Token dreht.

## Nichts ist halbfertig

Die letzte Änderung ist gebaut und grün. Am nächsten Punkt (Codec-Picker) wurde
noch keine Zeile geschrieben.

## NÄCHSTE AUFGABE — Codec-Picker sortieren und ausgrauen

Daniels Spezifikation, wörtlich:

> Die möglichen Einträge sollen oben in der Liste stehen, die nicht verfügbaren
> am unteren Ende der Liste ausgegraut. Als einheitliche Liste aber ausgegraut
> und nach unten gereiht. Beim drauf klicken ein Pop-Up „Setting not available
> for this device".

**Warum:** der Picker bietet alle sieben Codecs an, die die App *ausdrücken*
kann, sagt aber nicht, welche das verbundene Gerät gerade wirklich anbietet.
Genau das führte heute in eine Sackgasse: aptX HD war einstellbar, wurde zweimal
sauber angefordert und vom Stack ignoriert, weil die Bathys in dieser
Aushandlung nur aptX, AAC und SBC führt.

**Wo es hingehört** (alles in `ui/screens/devices/DeviceProfilesScreen.kt`):

- `PickerMenu` ist der generische Dropdown. Er braucht ein Konzept von
  *nicht wählbar*: eine Menge deaktivierter Werte, Sortierung dieser Einträge
  ans Ende, ausgegraute Darstellung, und statt `onSelect` ein Klick, der die
  Meldung zeigt. Der Dropdown darf sich dabei **nicht** schließen.
- `CodecEditor` rendert „Codec on connect" aus `BluetoothCodecOptions.codecs`
  und muss die tatsächlich angebotene Liste hereingereicht bekommen.
- `ProfileEditorCard` reicht sie durch.

**Woher die angebotene Liste kommt:** `CodecController.availableCodecs(address)`
über `PrivilegedCodec.controller()` — das ist genau
`selectableFamilies` gefiltert auf das, was die App schreiben kann. Die Adresse
ist im Profilscreen auflösbar: `DeviceProfilesViewModel.addressFor(deviceKey)`
existiert bereits (dort ~Zeile 155) und wird schon von `applyNow` benutzt.

**Ehrliche Vorgabe:** ist die Liste **unbekannt** — kein Helfer, Gerät nicht
verbunden, Adresse redigiert — dann wird **nichts** ausgegraut. „Wir wissen es
nicht" darf nicht als „nicht verfügbar" aussehen. Das ist kein Detail, sondern
die Regel, an der sich der Rest des Projekts ausrichtet.

**Und nicht vergessen:** die Menge ist **nicht konstant**. Dieselbe Bathys führte
im Lauf des Abends LDAC 96/32, aptX HD und aptX/AAC/SBC. Die Liste gehört bei
jedem Öffnen des Screens neu geholt, nicht einmal gecacht.

## Danach, unverändert offen

1. **Farbwähler mit Metallic-Ableitung.** Ursache steht in zwei Zeilen
   `Theme.kt` (außerhalb *Edgy* ist der Akzent ganz abgeschaltet), und
   `AccentChoice` ist ein Enum, das keine freie Farbe halten kann.
   `MetalPalette.from()` gibt es schon.
2. **Shizuku restlos entfernen.** Drei unabhängige Stellen:
   `ShizukuQualityReportSource`, `SystemGraph.shizuku`, und ein hart gebautes
   `ShizukuShellRunner()` in `MonitorViewModel` (~Zeile 106). **Vorher prüfen,
   ob der globale EQ-Attach daran hängt** — `GlobalAttachmentStrategy.activate()`
   fragt `ShizukuState.Ready` ab, und der Attach steht derzeit auf „Global".
3. **Zurück zur Systemauswahl beim Codec.** Es fehlt ein Schreibvorgang mit
   `CODEC_PRIORITY_DEFAULT` statt HIGHEST. Nicht dringend (ein Power-Cycle des
   Kopfhörers löst die Nadel), aber niemand sollte dafür Hardware neu starten.
4. **Der verwaiste Helfer** nach `adb install -r`.
5. Die alten Punkte 2–6: Hörtest-Frequenzen, Entwickleroptionen pro Gerät,
   Monitoring-Graphen (vermutlich erledigt, kurz gegenprüfen), Design auf den
   letzten vier Screens.

## Zwei Fallen, die diese Sitzung Zeit gekostet haben

**Git Bash verstümmelt Android-Pfade.** `adb shell cat /data/local/tmp/x.log`
wird zu `C:/Program Files/Git/data/local/tmp/x.log`. Der Befehl schlägt fehl,
und ein nachgeschaltetes `grep -c` zählt seelenruhig `0` — das sah aus wie „der
Codec wurde nie geschrieben", während er in Wahrheit sechsmal geschrieben wurde.
**Für alles mit Gerätepfaden PowerShell benutzen**, nicht Bash.

**`runCatching { }.getOrNull()` hat drei Fehler gleichzeitig verschluckt.** Der
ganze Adapter-Pfad war deshalb monatelang blind. Wer hier etwas anfasst: jeden
Zweig einzeln melden, sonst ist der nächste Fehler genauso unauffindbar.

---

# Codec-Picker: verfügbare oben, nicht verfügbare grau unten (21. August)

Daniels Wunsch umgesetzt und am Gerät geprüft. Eine Liste, die vom Gerät
angebotenen Codecs oben, der Rest ausgegraut ans Ende, Antippen eines grauen
Eintrags zeigt „Setting not available for this device".

## Am Gerät verifiziert

Bathys verbunden, ausgehandelt aptX, angeboten AAC/aptX/SBC. Das Menü:

```
Leave alone   weiß
SBC           weiß
AAC           weiß
aptX          weiß
aptX HD       grau
LDAC          grau
LC3           grau
Opus          grau
```

Tap auf LDAC → Dialog erscheint, **das Menü bleibt dahinter offen**, nach OK
lässt sich weiter auswählen. Tap auf aptX → normal übernommen, Menü schließt.

## Wo es sitzt

- `CodecAvailability.kt` (neu) — die beiden Regeln als reine Funktionen,
  `unavailableCodecs()` und `orderByAvailability()`. Bewusst aus dem Composable
  herausgezogen: an genau diesen zwei Regeln hängt das Verhalten, und in einer
  `@Composable` wären sie nicht prüfbar.
- `PickerMenu` bekam `unavailable: Set<T>`. Gesperrte Einträge bleiben
  **klickbar** — `enabled = false` würde den Tap schlucken, und ein Eintrag, der
  auf Berührung gar nichts tut, ist genau das Rätsel, das behoben werden sollte.
- `DeviceProfilesViewModel.offeredCodecs` — geladen über
  `PrivilegedCodec.controller().availableCodecs(address)`, angestoßen von einem
  `LaunchedEffect(initial.deviceKey)` in `ProfileEditorCard`.

## Die Regel, die dabei am wichtigsten ist

**Unbekannt grau nichts aus.** Kein Helfer, Gerät nicht verbunden, Adresse
redigiert → `offeredCodecs` ist null → kein einziger Eintrag wird gesperrt.
„Wir konnten nicht fragen" darf nie als „dein Kopfhörer kann das nicht"
erscheinen; für die zweite Aussage hat die App keinen Beleg.

Dasselbe gilt für eine **leere** Antwort, und das ist die Falle, die ein Test
gefangen hat: der No-Op-Controller liefert eine leere Liste, wenn kein Helfer
da ist. Wörtlich gelesen hieße das „unterstützt gar keinen Codec" und hätte das
ganze Menü ausgegraut. `unavailableCodecs()` behandelt leer deshalb wie null —
es gibt keinen Kopfhörer, der nichts kann.

## Tests

`CodecAvailabilityTest`, 7 Fälle: unbekannt, leer, normaler Fall, „Leave alone"
nie gesperrt, keine erfundenen Einträge, Reihenfolge, und dass beim Umsortieren
nichts verlorengeht. **Gesamt jetzt 456 Tests, 0 Fehler.**

## Offen an dieser Stelle

Die Liste wird beim Öffnen der Karte geholt, aber **nicht aktualisiert**, wenn
sich die Verbindung ändert, während die Karte offen ist. Wer das nachzieht:
`offeredCodecs` an denselben Ereignisstrom hängen, aus dem die Geräteliste
gespeist wird — nicht pollen.

## „Leave alone" ist aus dem Codec-Picker verschwunden

Daniels Begründung, und sie trägt: **eine Verbindung kann niemals keinen Codec
haben.** Ein A2DP-Link ohne Codec existiert nicht. Ein Eintrag „Leave alone"
benannte damit einen Zustand, in dem die Hardware nie ist, und das Feld zeigte
„nichts gesetzt", während in Wahrheit etwas lief.

Das Feld zeigt jetzt immer einen echten Wert:

| Lage | Anzeige | Zeile darunter |
|---|---|---|
| Wunsch gespeichert | der Wunsch | „Stored — requested every time this device connects." |
| kein Wunsch, Gerät verbunden | der **ausgehandelte** Codec | „Currently negotiated, not stored. Pick one to store it." |
| nichts verbunden | „Not connected" | „No connected device to read a codec from." |

Der gespeicherte Wunsch schlägt den laufenden Wert: das Feld heißt „on connect"
und muss den *nächsten* Verbindungsaufbau versprechen, nicht die Gegenwart
beschreiben.

Warum die Herkunft daneben steht statt nur des Werts: „aptX" als gespeicherter
Wunsch und „aptX" als das, was gerade zufällig läuft, sehen identisch aus. Ohne
den Zusatz behauptete die App eine Einstellung, die sie nie vorgenommen hat.

Der ausgehandelte Codec kommt über `MonitorGraph.codecSource`, **nicht** über
den Helfer — dieser Weg funktioniert ohne jeden privilegierten Zugriff, und
„was läuft gerade" ist genau die Frage, die nicht von einem laufenden Helfer
abhängen darf.

Regeln als reine Funktionen in `CodecAvailability.kt` (`codecToShow`,
`codecOrigin`, `CodecOrigin`), vier Tests dazu. **Gesamt 459, 0 Fehler.**
Am Gerät gegengeprüft: Bathys verbunden, Feld zeigt „aptX HD" mit „Stored —
requested every time this device connects."

### Die Lücke, die das aufreißt

Mit „Leave alone" verschwindet der einzige Weg, eine gesetzte Vorliebe wieder
**zurückzunehmen**. Wer einmal einen Codec gespeichert hat, kann ihn nur noch
durch einen anderen ersetzen oder das ganze Profil löschen. Das ist genau die
`CODEC_PRIORITY_DEFAULT`-Lücke von weiter oben, jetzt ohne Notausgang.

Der saubere Ersatz ist **nicht** ein wiederbelebtes „Leave alone", sondern eine
eigene Handlung — „Hand back to the system" — die mit Priorität 0 statt
`CODEC_PRIORITY_HIGHEST` schreibt. Das ist eine Aktion, kein Wert, und gehört
deshalb neben den Picker und nicht hinein.

**Nicht angefasst:** Sample rate, Bit depth und Channel mode behalten ihr
„Leave alone". Dort heißt es „der Codec soll seine eigene Vorgabe aushandeln",
was eine echte Absicht ist. Dasselbe Argument („eine Verbindung hat immer eine
Abtastrate") ließe sich allerdings anwenden — ungeklärt, bewusst offen gelassen.

---

# „System Default" — die Nadel lässt sich jetzt per Software lösen (21. August)

Daniels Auftrag in zwei Sätzen: nirgends mehr „Leave alone"; entweder man sieht
die unveränderte aktuelle Verbindung, oder den selbst gewählten Wert, der bei
jedem Reconnect angewandt wird. Und als Rückgabeweg ein Eintrag **„System
Default"**, der die Entscheidung wieder an Android abgibt.

## Am Gerät bewiesen, als Vorher/Nachher

| Schritt | mCodecConfig |
|---|---|
| AAC über das Profil angewandt | `AAC, mCodecPriority: 1000000` — festgenagelt |
| „System Default" angewandt | `AptX, mCodecPriority: 3001` — Stack wählt wieder selbst |

Kein Power-Cycle des Kopfhörers nötig. Das war vorher die einzige Abhilfe.
Helfer-Log: `setCodecPreference 35:6A type=-2 …` — der Sentinel kam an.

## Wie es gebaut ist

- **Kein neuer Binder-Aufruf.** „System Default" reist als Sentinel-Codec-Typ
  `-2` (`A2dpCodecMasks.SYSTEM_DEFAULT_SENTINEL`) durch das bestehende
  `setCodecPreference`. Absicht: ein **alter** Helfer, der den Wert nicht
  kennt, lehnt ihn in `rejectRaw` laut ab, statt irgendeinen Codec zu pinnen —
  getestet in `the sentinel is not a real codec type and old helpers reject it`.
- **Helfer:** `handBackToSystem()` liest den aktuell ausgehandelten Codec und
  schreibt ihn mit `CODEC_PRIORITY_DEFAULT` (0) statt `HIGHEST` zurück. Kein
  Settle-Polling: es gibt keinen erwarteten Codec — was der Stack danach
  aushandelt, *ist* das richtige Ergebnis. Die Priorität läuft jetzt als
  Parameter durch `buildConfig`/`viaBuilder`/`viaConstructor` statt verdrahtet.
- **Speicherung:** `CodecPreference(codec = "SYSTEM_DEFAULT")`. `isKnown()`
  verlangt, dass alle Unterwerte 0 sind — „gib die Entscheidung ab" und
  „erzwinge 96 kHz" zugleich wäre ein Widerspruch. Der Editor blendet die
  Unteroptionen bei System Default komplett aus.
- **UI:** erster Eintrag der Liste, niemals ausgegraut (die Entscheidung
  abgeben kann jedes Gerät, immer). Erklärzeile: „Stored — on every connect the
  codec decision is handed back to Android, un-pinning anything this app set
  before."

**463 Tests, 0 Fehler** (4 neue in `PrivilegedCodecTest`).

## Wichtig fürs Verständnis

„System Default" ist als **gespeicherter Wunsch** gebaut, nicht als Einmal-Knopf:
bei jedem Verbinden wird die Entscheidung erneut abgegeben. Das ist bewusst —
so heilt das Profil auch Pins, die eine andere Quelle (Entwickleroptionen,
früherer App-Stand) hinterlassen hat.

## Noch offen aus Daniels Auftrag: die übrigen „Leave alone"

„Es darf **nirgends** ein Leave alone sein." Erledigt ist der Codec. Es bleiben:

1. **Sample rate / Bit depth / Channel mode / LDAC-Qualität** im CodecEditor —
   die Ist-Werte stehen in `CodecStatus` (`sampleRateHz`, `bitsPerSample`,
   `channelMode`) bzw. `CodecObservation.ldacQuality`; der ViewModel muss sie
   neben `negotiatedCodec` durchreichen, Anzeige-Logik wie beim Codec
   (gespeichert schlägt live, Herkunft daneben).
2. **Absolute volume** („On connect: Leave alone") — Live-Wert existiert in
   `AbsoluteVolumeStatus.Available(enabled)`; gleiche Darstellung.
3. **Entwickleroptionen** (jede Option hat „Leave alone") — hier ist die
   dokumentierte Falle: ein ungesetzter `Settings.Global`-Schlüssel liest
   `null`, ob ungestützt oder unberührt. „Aktueller Wert" ist also oft ehrlich
   nur „nicht gesetzt (Android-Vorgabe)" — genau so anzeigen, nicht raten.
4. **PBAP / MAP / „Show devices without names"** auf dem Bluetooth-Tab.

Muster überall dasselbe: Feld zeigt Ist-Wert oder gespeicherten Wert samt
Herkunft; „System Default" als Rückgabe-Eintrag, wo ein Pin entstehen kann.

---

# „Leave alone" ist überall raus (21. August, vormittags)

Daniels Regel: entweder den unveränderten Ist-Wert der Verbindung sehen oder den
selbst gewählten, persistenten Wert. Umgesetzt:

| Stelle | vorher | jetzt |
|---|---|---|
| Codec-Unteroptionen (Rate/Bit/Kanal/LDAC) | Leave alone | „Use System Default"; bei 0 zeigt das Label den live ausgehandelten Wert: „System default (now: 48000 Hz)" |
| Absolute Volume „On connect" | Leave alone | „Use System Default"; ohne Wunsch zeigt das Label „System default (now: on)" |
| Entwickleroptionen (AVRCP/MAP/PBAP/…) | Leave alone | „Use System Default" — **echtes Löschen**, kein Umbenennen |
| EQ preset | Leave alone | „None" (kein Pin möglich, nichts zurückzugeben) |

## Substanz, nicht nur Labels

- **Unteroptionen:** Wert 0 sendete schon immer die NONE-Maske („Stack
  entscheidet") — vom Diagnose-Cycling bewiesen. Neu ist nur Ehrlichkeit:
  Name + Live-Anzeige (`negotiatedStatus` im ViewModel, aus `CodecStatus`).
- **Entwickleroptionen:** neuer Speicherwert
  `BluetoothDeveloperOptions.USE_SYSTEM_DEFAULT` → der Applier **löscht** den
  Schlüssel (`SecureSettingsController.clear()`, putString null + Null-Readback).
  Ein ungesetzter Schlüssel ist der Zustand eines frischen Telefons; das räumt
  auch Werte weg, die andere Schreiber hinterlassen haben. Einmal gewählt gibt
  es kein „nichts gespeichert" mehr — konsistent mit dem Codec-Picker.
- **Live-Werte:** `DeviceProfilesViewModel.liveDevOptions` liest alle Keys per
  `SystemGraph.globalSettings` (neuer Accessor) in `refresh()`.

## Bekannte Lücke (bewusst)

Absolute Volume: „Use System Default" ist dort **wish = null** = nicht
anfassen. Ein echtes Zurücksetzen (Schlüssel `bluetooth_disable_absolute_volume`
löschen) bräuchte einen dritten Zustand im Profilfeld (`Boolean?` reicht nicht).
Klein, aber nicht gemacht — wer es baut: gleiche clear()-Mechanik wie bei den
Entwickleroptionen.

## Falle dieser Runde

`init { refresh() }` stand textlich **über** der neuen Property
`_liveDevOptions` → NPE beim ViewModel-Bau, gefangen von `ScreenSmokeTest`.
Kotlin initialisiert strikt in Textreihenfolge; Properties, die init braucht,
müssen davor stehen.

**Stand: 465 Tests, 0 Fehler.** Am Gerät gesehen: „System default (now: on)",
„Use System Default" bei AVRCP/MAP/PBAP, Codec-Feld „System Default".

---

# Farbwähler mit Metallic-Ableitung (21. August, vormittags)

## Die eigentliche Ursache von „manche Details, nicht alle"

Nicht die Theme-Gate in `Theme.kt` (die frühere Vermutung), sondern
**hartkodiertes Gold** an vier Stellen, die die gewählte Palette ignorierten:

- `Panel.kt` — Panel-Randverlauf (`Gold.Base/Gold.Deep`), Eyebrow-Header,
  `Pill` mit `PillTone.ACCENT`
- `GoldComponents.kt` — `disabledContentColor = Gold.Deep`

Alle vier lesen jetzt `LocalMetalPalette.current`. Ein gewähltes Silber färbte
vorher Knöpfe, aber nicht Panel-Ränder — genau Daniels Beschwerde.

## Der Picker

- **Speicherung:** `KEY_ACCENT_ARGB` (Long) in `AppearanceStore` ist die einzige
  Quelle; `accentArgb`-Flow mit Fallback auf das alte Preset-Feld (Migration).
  Presets schreiben denselben Schlüssel.
- **UI:** Settings → Accent metal. Presets als Rampen-Swatches + „Custom
  colour": HSV-Slider (Hue/Saturation/Brightness) mit **Live-Rampe** der
  abgeleiteten `MetalPalette`. Commit explizit über „Apply this colour" —
  nicht bei jedem Drag, weil jede Änderung die ganze App umfärbt.
- **Brightness-Untergrenze 0,25:** ein fast schwarzer Seed kollabiert die Rampe,
  und 4,5:1 auf den Readouts wäre unerreichbar.

## Am Gerät verifiziert

Grün (Hue ~140) angewandt → **die gesamte App** folgt: Panel-Ränder, Buttons,
Pills, Slider, Tab-Leiste. Zurück auf Preset Gold → ebenso vollständig.
Akzent überlebt Theme-Wechsel (Edgy → Follow system → Edgy).

**465 Tests, 0 Fehler.** Metal bleibt bewusst Edgy-exklusiv („Painting"/
„Inactive"-Pill sagt das); unter Material You gehört der Akzent dem Wallpaper.

## Hinweis für die nächste Geräte-Runde

Nach dem Picker-Install ist der Helfer wieder tokenlos („Not running") — vor
privilegierten Tests neu starten (Setup-Screen-Befehl).

---

# Shizuku ist raus (21. August, vormittags)

Daniels Auftrag wörtlich: „wir wollen unsere access-möglichkeit und sonst
nichts." Vollzogen — Bibliothek, UI, Setup-Schritt, Fallbacks, JitPack-Repo.

## Der Befund, der es erst möglich machte

`GlobalAttachmentStrategy` prüfte `ShizukuState.Ready`, **benutzte Shizuku aber
nie**: nichts von Shizuku floss in den Attach, `MODIFY_AUDIO_ROUTING` kommt im
Projekt nirgends vor, und `DynamicsProcessing` auf Session 0 attacht auf dem
Pixel 8 Pro mit gewöhnlichem `MODIFY_AUDIO_SETTINGS`. Das Gate konnte nur eines:
den Versuch verweigern, der funktioniert hätte. **Am Gerät nach dem Ausbau
bestätigt: „EQ attachment: Global — attached to the output mix", ohne dass
irgendein Shizuku-Code existiert.**

## Was sich geändert hat

- `GlobalAttachmentStrategy`: Gate weg, das Factory-Ergebnis ist die Antwort.
  `AttachmentKind.GLOBAL_SHIZUKU` → `GLOBAL`.
- `MonitorGraph`: Shell-Fallback ist `UnavailableShellRunner` (ehrlich
  degradieren) statt `ShizukuShellRunner`; `shell` ist jetzt public, und
  `MonitorViewModel` benutzt ihn statt eines hart gebauten Shizuku-Runners.
- `ShizukuQualityReportSource` → `ReflectiveQualityReportSource` (die Klasse
  hat Shizuku nie mechanisch benutzt; Name und Doku logen).
- Setup-Schritt `SHIZUKU` → `SHELL_ACCESS`, **id bleibt `"shizuku"`**, weil
  `SetupStore` den Erledigt-Zustand unter diesem String speichert. Erfüllt =
  `PrivilegedConnection.isConnected`.
- Settings-Panel: Shizuku-Zeile weg. Onboarding: ShizukuPanel weg. Wizard: nur
  noch der Helfer. BootReceiver-Text angepasst.
- `SecureSettingsState` in eigene Datei gerettet (lag in der gelöschten
  `ShizukuState.kt`; WRITE_SECURE_SETTINGS war nie Shizuku-abhängig).
- Gradle: `shizuku-api`/`-provider` raus, Version raus, JitPack-Repo raus.

## Die Falle dieser Runde: das Manifest

Nach dem Ausbau **crashte die App beim Start** — `rikka.shizuku.ShizukuProvider`
stand noch im `core-system`-Manifest, die Klasse war weg:

```
RuntimeException: Unable to get provider rikka.shizuku.ShizukuProvider
```

Kein Test fängt das (Robolectric instanziiert keine Manifest-Provider), und die
Blind-Navigation tippte danach minutenlang auf den Launcher statt auf die App —
so landete zweimal Google Lens im Vordergrund. **Wer eine Bibliothek mit
Manifest-Beiträgen entfernt: sofort `grep` über die AndroidManifest.xml,
nicht erst nach dem Gerätetest.** Provider + `<queries>` sind entfernt.

## Bewusst geblieben

- Package-Name `system/shizuku/` für `SecureSettingsGate`/`SecureSettingsState`
  und der Dateiname `ShizukuOnboardingScreen.kt` — reine Namen, Umbenennung
  beim nächsten Anfassen der Dateien.
- **BQR ist damit endgültig „nicht verfügbar auf diesem Build"** — die einzige
  echte Shizuku-Restfunktion war ohnehin nie über die App-uid erreichbar.

## Offene Beobachtung

Nach `force-stop` + Neustart der App zeigt Settings „App helper: Not running",
obwohl der Helferprozess lebt (Log: `serving as uid 2000`). Der Binder-Handover
läuft nur beim Helfer-Start; ob der Helfer ein App-Comeback bemerkt und neu
pusht, ist ungeklärt. Praktisch hieß das bisher immer „Helfer neu starten" —
prüfen, ob ein Reconnect-Pfad fehlt.

**Stand: 465 Tests, 0 Fehler. App läuft am Gerät, EQ global, Farbwähler und
Codec-Arbeit unangetastet.**

---

# Der verwaiste Helfer ist erledigt (21. August)

Offener Punkt seit dem 20.: nach `adb install -r` liefen **zwei**
`btdash_privileged`-Prozesse, obwohl Prüfpunkt 3 genau einen verlangt.

## Warum der bestehende Ablösepfad nicht greifen konnte

`PrivilegedProvider.retire()` schickt `shutdown()` an den Helfer, den die App
kennt — über den Binder, den sie hält. Nach einer Neuinstallation ist die App
ein **frischer Prozess ohne diesen Binder**. Sie kann den alten Helfer also gar
nicht mehr ansprechen, und der alte Helfer hat keinen Grund, sich zu beenden.
Ergebnis: ein Prozess auf der Shell-uid, der auf einen Token wartet, den
niemand mehr besitzt, bis zum Reboot.

## Die Lösung: der neue Helfer räumt auf

`PrivilegedServer.reapOtherHelpers()` läuft **vor** dem Hand-over und beendet
jeden anderen Prozess mit dem Namen `btdash_privileged`. Der neue Helfer ist
die einzige Partei, die das kann: gleiche uid (also darf er `kill`), und er
läuft genau in dem Moment, in dem ein Ersatz existiert. Gesucht wird nach
Prozessnamen, nicht nach dem, was die App sich merkt — der Sinn der Sache ist
ja gerade, vergessene Helfer zu erwischen. Eigene PID ausgenommen; Fehler
werden gemeldet und ignoriert, ein übrig gebliebener Prozess darf nie einen
funktionierenden Helfer am Start hindern.

Der Prozessname liegt jetzt als `PrivilegedContract.HELPER_PROCESS_NAME` an
einer Stelle. Zwei Parteien hängen daran — der ADB-Befehl in
`PrivilegedBootstrap.command()` und der Reaper. Driften sie auseinander, fällt
nichts laut aus, es stapeln sich nur wieder Prozesse; deshalb hält
`the start command names the process the reaper searches for` beides zusammen.

## Am Gerät bewiesen

```
--- Helfer A ---   (nach Installation)
privileged helper: retiring stale helper pid 1227
privileged helper: serving as uid 2000 for app uid 10440, version 2
Prozesse: 1

--- Helfer B ---   (zweiter Start, PID vorher 4062)
privileged helper: retiring stale helper pid 4062
privileged helper: serving as uid 2000 for app uid 10440, version 2
PID nachher: 4383 · Prozesse: 1
```

**Prüfpunkt 3 ist damit vollständig erledigt** — Rotation *und* „genau einer".

**466 Tests, 0 Fehler.**

## Was dabei sichtbar wurde und offen bleibt

Nach `force-stop` der App zeigt Settings „App helper: Not running", obwohl ein
Helfer läuft: der Hand-over passiert nur beim Helfer-Start, und ein neu
gestarteter App-Prozess hat keinen Binder. Praktisch heißt das weiterhin
„Helfer neu starten", was durch den Reaper jetzt wenigstens sauber bleibt.

Ein echter Reconnect bräuchte die Gegenrichtung: die App müsste beim Start
prüfen, ob ein Helfer läuft, und ihn zu einem erneuten Hand-over bewegen. Der
Weg dahin ist nicht offensichtlich — der Helfer lauscht auf nichts, was eine
unprivilegierte App auslösen könnte, und genau diese Asymmetrie ist Absicht
(SELinux lässt `untrusted_app` nicht an die Shell-Domäne heran, siehe die
Socket-Sackgasse in der Klassendoku). Vermutlich braucht es einen Timer im
Helfer, der periodisch neu anbietet — bewusst nicht gebaut, weil das dem
„keine Timer, kein Polling"-Grundsatz des Projekts widerspricht und erst
Daniels Entscheidung braucht.

---

# Reconnect-Pfad: der Helfer überlebt jetzt den Tod der App (21. August)

## Was kaputt war

Der Binder-Handover war ein **Einmal-Ereignis beim Helfer-Start**. Android holt
sich eine im Hintergrund liegende App aber jederzeit zurück, und der neu
gestartete Prozess hatte keinen Weg zu einem Helfer, der putzmunter weiterlief.
Ergebnis: „App helper: Not running" bei lebendem Helfer (Prozess-PID
unverändert nachgemessen), und der Nutzer musste den ADB-Befehl erneut
ausführen — für nichts.

**Konkret ausgefallen** war dabei alles Shell-Abhängige: Codec *setzen*,
Codec-Cycling in der Diagnose, Fremd-EQ-Prüfung, der `dumpsys`-Fallback beim
Codec-Lesen. Weitergelaufen sind der globale EQ-Attach (braucht keinen Helfer)
und das Codec-Lesen über die A2DP-API.

## Wie es jetzt läuft

1. Die App legt in die Antwort des Hand-overs einen **`EXTRA_APP_TOKEN`** —
   `PrivilegedConnection.livenessToken`, ein blanker `Binder`, dessen einzige
   Aufgabe es ist, mit dem Prozess zu sterben.
2. Der Helfer hängt einen `DeathRecipient` daran. Solange die App lebt, kostet
   das **nichts** — kein Timer, kein Polling.
3. Stirbt sie, wartet ein Daemon-Thread und prüft alle 3 s über `/proc`, ob ein
   Prozess mit der App-uid existiert. Erst **dann** wird erneut übergeben.
4. Nach erfolgreichem Re-Attach hängt sich der Watcher an den neuen Token.

**Die App wird nie geweckt.** `getContentProviderExternal` würde den
App-Prozess starten — ein Helfer, der die App alle paar Sekunden aus dem
Hintergrund zerrt, wäre schlimmer als der Fehler. Deshalb die `/proc`-Vorprüfung.

Ein **abgelehnter** Re-Handover (die App hat ihren Token rotiert, also
Neuinstallation) beendet den Watcher endgültig, statt einen Befehl zu
wiederholen, der nie wieder angenommen wird.

## Am Gerät bewiesen

```
privileged helper: serving as uid 2000 for app uid 10440, version 3
privileged helper: the app process is gone, waiting for it to return
privileged helper: re-attached to the restarted app
```

Danach in Settings: **App helper: Running**, EQ weiterhin Global, und
`ps | grep -c btdash_privileged` = **1** — kein verwaister Zweitprozess.

## Protokoll

`VERSION` 2 → 3. Nicht zwingend nötig (beide Richtungen degradieren sauber: ein
alter Helfer ignoriert das Feld, eine alte App sendet keins und der Helfer
bleibt ein Einmal-Helfer), aber ein Versionswechsel erzwingt ohnehin einen
Helfer-Neustart, und der ist nach jeder Neuinstallation fällig.

## Tests

`PrivilegedReconnectTest` — drei Fälle: der Token existiert, er ist **ein**
Objekt pro Prozess (ein frischer pro Handover ließe den Helfer eine lebende App
für tot halten), und er trägt **keine** Schnittstelle. Die Schleife selbst
braucht einen echten Binder-Tod und ist deshalb am Gerät verifiziert, nicht im
Unit-Test. **Gesamt 469, 0 Fehler.**

---

# Der EQ läuft jetzt im Hintergrund weiter (21. August)

## Das Problem

Die gesamte Audiokette — globaler Attach, Profil-Applier, Connect-Listener —
hing an `Application`, also am App-Prozess. Ein `DynamicsProcessing`-Effekt
gehört dem Prozess, der ihn erzeugt hat. App aus Recents wischen (Daniels
Gewohnheit) oder Android holt sich den Speicher zurück → **EQ aus, ohne ein
Wort**. Bei jemandem, der ihn zur Hörkompensation braucht, ist das nicht eine
schwächere Funktion, sondern das Verschwinden der Funktion.

## Die Lösung: `EqForegroundService`

Ein Foreground Service ist auf Android die einzige Konstruktion, die einen
Task-Swipe überlebt und beim Boot wieder gestartet werden kann. WorkManager,
Alarme und gebundene Services können das nicht (jederzeit killbar bzw. kein
Halter für einen lebenden Audio-Effekt).

- `android:stopWithTask="false"` — das ist die Zeile, die den Swipe überlebt.
- `foregroundServiceType="connectedDevice"` — inhaltlich korrekt: der Service
  hält eine Korrektur für einen verbundenen Bluetooth-Kopfhörer und wendet
  dessen Profil beim Reconnect an. Kein `specialUse`-Feigenblatt.
- `START_STICKY`, damit er nach einem Speicher-Reclaim zurückkommt.
- Start aus `Application.onCreate` (App sichtbar → erlaubt) und aus
  `BootReceiver` (BOOT_COMPLETED ist eine der wenigen Ausnahmen, die einen FGS
  aus dem Hintergrund starten dürfen).
- `startForeground` **vor** dem DataStore-Lesen: Android tötet den Prozess,
  wenn ein Service die Frist zum Promoten verpasst.

## Die Notification

Unvermeidlich — Android verlangt sie. Also sagt sie etwas Wahres statt
„BT Dashboard läuft": den tatsächlichen Reichweiten-Zustand des EQ („every
app" / „announced players only" / der konkrete Fehlergrund). IMPORTANCE_LOW,
lautlos, kein Badge, eigener Kanal `eq_running` getrennt vom Boot-Hinweis.

Ist der EQ ausgeschaltet, beendet sich der Service selbst — eine Dauer-Notiz
für etwas absichtlich Abgeschaltetes wäre Lärm.

## Am Gerät bewiesen

| Aktion | Ergebnis |
|---|---|
| `am kill` | **verweigert** — der FGS schützt den Prozess |
| Echter Recents-Swipe | PID **unverändert**, `isForeground=true`, Notification steht |
| `am force-stop` | Prozess weg, 0 ServiceRecords — der Kill-Schalter wirkt |

Damit ist Daniels Vertrag erfüllt: **schließen lässt ihn laufen, „Beenden
erzwingen" beendet ihn.**

## Nebenwirkung: der BootReceiver-Kommentar war überholt

Er behauptete, der EQ komme nach jedem Neustart mit weniger Reichweite zurück,
weil Shell-Zugriff einen Reboot nicht überlebt. Seit dem Shizuku-Ausbau ist das
widerlegt: der globale Attach braucht **keine** Shell-Identität. Was beim Boot
stirbt, ist der privilegierte Helfer — das kostet Codec-Steuerung und die
dumpsys-Lesewege, nicht den EQ. Der Normalfall nach einem Neustart ist jetzt
volle Wiederherstellung **ohne** Notification.

## Noch nicht geprüft

Der Boot-Pfad selbst — dafür müsste das Telefon neu starten. `stopWithTask`
und force-stop sind gemessen, `BOOT_COMPLETED` ist nur verdrahtet.

**Stand: 469 Tests, 0 Fehler.**

## Wie unsichtbar die Service-Notification werden kann (gemessen)

Daniel liest jedes dauerhafte Symbol als Aufforderung. Zwei unabhängige Regler,
beide auf Anschlag gestellt:

| Regler | Gesetzt | Ergebnis |
|---|---|---|
| `setVisibility` | `VISIBILITY_SECRET` | **wirkt** — `vis=SECRET` im Dump, nichts am Sperrbildschirm |
| Kanal-Wichtigkeit | `IMPORTANCE_MIN` | **von Android überstimmt** |

Der Dump zeigt es wörtlich: `mOriginalImp=1, mImportance=2`. Android hebt
Kanäle von Foreground Services auf mindestens `LOW` an; `MIN` ist für sie nicht
erlaubt. Das Statusleisten-Symbol lässt sich also **nicht per Code** abschalten.

Praktisch bleibt trotzdem wenig übrig: Pixel fasst lautlose Notifications zu
einem einzelnen Punkt zusammen statt das App-Symbol zu zeigen — im Screenshot
nach dem Umbau steht dort nur noch „•".

**Was der Nutzer selbst tun kann:** seit Android 13 lässt sich die
FGS-Notification wegwischen; der Service läuft weiter. Danach ist auch der Punkt
weg. Nicht entfernbar bleibt allein der Eintrag unter „Aktive Apps", den man
über den Chip in der Schublade erreicht — der ist Systemsache.

Der Kanal heißt jetzt `eq_running_quiet`; der alte `eq_running` (IMPORTANCE_LOW)
wird beim ersten Start gelöscht, weil Android das Herabstufen eines bestehenden
Kanals nur manchmal zulässt.

## Boot-Pfad: immer noch ungetestet, und warum

Der erste Neustart hat den Service **nicht** gestartet — Ursache war mein
eigener vorheriger `force-stop`-Test. Eine force-gestoppte App steht in
Androids „stopped state" und bekommt **kein** `BOOT_COMPLETED`, bis sie einmal
von Hand geöffnet wird. `dumpsys package … | grep stopped=` zeigte `stopped=true`.

Das ist Android-Politik, kein Fehler im Code — aber es ist ein echtes Verhalten
für den Nutzer: **nach „Beenden erzwingen" bleibt der EQ weg, bis die App
einmal geöffnet wurde.** Gehört in die Oberfläche, steht dort noch nicht.

Jetzt ist `stopped=false`, Service läuft, Notification sagt „EQ active — every
app". Der nächste Neustart prüft den Boot-Pfad zum ersten Mal wirklich.

## Boot-Test, zweiter Anlauf (22. August, 10:04)

Echter Neustart mit `stopped=false`. Bei **2 Minuten Uptime**: kein
App-Prozess, kein Service.

**Das ist kein Beweis für einen Fehler.** Der manuell zugestellte Broadcast
funktioniert einwandfrei:

```
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p dev.dankyeeter.btdashboard
→ Prozess gestartet, isForeground=true
```

Receiver, Manifest-Eintrag, Permission und Service-Start sind damit belegt.
Ebenfalls ausgeschlossen: Standby-Bucket ist **10 (aktiv)**, keine
App-Restriktion, `stopped=false`.

Bleibt die Zustellzeit. Android reiht `BOOT_COMPLETED` für Hintergrund-Apps in
eine gedrosselte Warteschlange, und die Load lag zum Messzeitpunkt bei **22,98**
— der Boot war noch mitten in der Arbeit. **Beim nächsten Neustart erst nach
5–10 Minuten urteilen, nicht nach zwei.**

### Vorschlag, der das Problem umgeht statt es abzuwarten

Ein zweiter, ereignisgetriebener Einstiegspunkt: ein **im Manifest**
registrierter Receiver auf `ACTION_ACL_CONNECTED`. Dann startet der Service,
sobald ein Kopfhörer verbindet — unabhängig davon, wann oder ob
`BOOT_COMPLETED` eintrifft. Für diesen Anwendungsfall ist das sogar der
passendere Auslöser: der EQ zählt erst, wenn Audio über Bluetooth läuft.

`DeviceConnectionWatcher` registriert heute zur Laufzeit und stirbt deshalb mit
dem Prozess; ein Manifest-Receiver überlebt.

## Der Bluetooth-Auslöser funktioniert (22. August, gemessen)

`BluetoothConnectReceiver`, im Manifest auf `ACTION_ACL_CONNECTED`, startet den
Service. Sauber getestet aus totem Zustand:

**Vorher:** kein App-Prozess · 0 ServiceRecords · Bathys getrennt ·
`stopped=false`. Die App wurde nicht angefasst.

**Nach dem Einschalten der Bathys:**

```
A2DP Connected count: 1  →  Focal Bathys <- ACTIVE
App-Prozess:  17800
ServiceRecord: isForeground=true, types=0x10 (connectedDevice), vis=SECRET
Notification: "EQ and Bluetooth adjusted"
```

Damit hat die Kette zwei unabhängige Einstiegspunkte: `BOOT_COMPLETED` (belegt,
aber von Android gedrosselt) und der Kopfhörer-Connect (belegt und sofort). Für
den tatsächlichen Zweck ist der zweite der wichtigere — der EQ zählt erst, wenn
Audio über Bluetooth läuft.

Der Receiver filtert **nicht** nach Gerät: welche Profile greifen, entscheidet
der Service anhand des Speichers. Eine zweite Urteilsstelle im Receiver wäre
eine weitere Stelle, die man mitpflegen müsste, und ein bereits laufender
Service ignoriert den Start ohnehin.

**Stand: 469 Tests, 0 Fehler.**

## Absolute-Volume-Lücke geschlossen (22. August)

Die letzte Stelle, an der „Use System Default" nur ein Etikett war: dort hieß es
`wish = null` = **nicht anfassen**. Eine einmal gespeicherte Vorliebe ließ sich
damit ersetzen, aber nie zurücknehmen.

### Warum ein dritter Zustand nötig war

`absoluteVolumeEnabled: Boolean?` kennt nur `true`, `false` und „kein Wunsch" —
keiner davon heißt „mach rückgängig, was da steht". Neu ist deshalb
`DeviceProfile.absoluteVolumeSystemDefault: Boolean`, und
`AbsoluteVolumeController` bekam `clear()`.

**`clear()` schreibt nicht `0`, sondern löscht den Schlüssel.** Von außen sieht
beides gleich aus, ist es aber nicht: eine geschriebene 0 nagelt einen Wert
fest, den *diese App* gewählt hat, während ein fehlender Schlüssel der Zustand
ist, in dem das Telefon ausgeliefert wurde — und nur Löschen räumt weg, was ein
anderer Schreiber hinterlassen hat. Bestätigt wird per Rücklesen auf `null`.

Der Reset schlägt einen danebenstehenden on/off-Wunsch: er ist eine Entscheidung
über die Einstellung als Ganzes. Die Oberfläche erzeugt die Kombination nie,
wiederhergestellte Daten könnten es.

### Am Gerät bewiesen

```
settings put global bluetooth_disable_absolute_volume 1   → Schluessel: 1
Profil: "absoluteVolumeSystemDefault":true   (in der DataStore-Datei)
"Apply now"                                   → Schluessel: null
```

Die Oberfläche unterscheidet jetzt sichtbar zwischen den beiden Bedeutungen:
ohne gespeicherten Wunsch steht dort „System default (now: off)" — der
*beobachtete* Wert — und nach der Auswahl „Use System Default" plus der Zeile
„On connect the app deletes this setting…".

Zwei Tests: `absolute volume system default deletes the key` (kein `write`,
genau ein `clear`) und `system default outranks a stale on-off wish beside it`.
**Gesamt 471, 0 Fehler.**

## Codec-Anzeige ist jetzt live (22. August)

Die letzte offene Stelle aus dem Picker-Abschnitt: die Liste wurde einmal beim
Öffnen der Karte geholt. Der A2DP-Proxy bindet aber asynchron, also las eine
direkt nach dem App-Start geöffnete Karte „Not connected" für einen sichtbar
verbundenen Kopfhörer — und **korrigierte sich nie**.

### Was jetzt auslöst

`watchCodecInfo()` kombiniert drei Quellen und rechnet bei jeder Änderung neu:

1. `watchedDeviceKey` — welches Gerät die Karte gerade zeigt,
2. `MonitorGraph.codecSource.connectedDevicesFlow()` — dieselbe push-basierte
   Quelle wie das Dashboard; sie feuert, wenn der Proxy fertig bindet und bei
   jedem Connect/Disconnect,
3. `PrivilegedConnection.service` — der Helfer entscheidet, ob die *wählbare*
   Liste überhaupt lesbar ist, und er kommt oft Minuten nach der App.

Kein Polling. Die Frage kündigt ihre eigene Antwort an, und Akkuverbrauch zählt.

### Ein Fehler, den der Gerätetest aufgedeckt hat

Ohne Helfer stand dort **„Not connected"**, während die Bathys verbunden war und
spielte. Die Dashboard-Zeile daneben sagte gleichzeitig
„Focal Bathys · Active · Unknown — InvocationTargetException".

Ursache: `codecOrigin` kannte nur STORED / NEGOTIATED / NONE, und NONE wurde als
„nicht verbunden" gerendert. Tatsächlich hat NONE **zwei** Ursachen — nichts
verbunden, oder verbunden und nicht lesbar. Ohne Helfer wirft die A2DP-Leseroute
und der dumpsys-Fallback fehlt, also traf immer der zweite Fall zu.

Neu ist `CodecOrigin.UNREADABLE` mit eigenem Text: „This headphone is connected,
but Android does not tell an ordinary app which codec it negotiated. Start the
helper to read it." Zwei Tests sichern die Unterscheidung.

### Am Gerät bewiesen

Karte offen, kein Helfer → „Cannot be read". Helfer **von außen** gestartet, App
nicht angefasst → nach ~3 s zeigt dieselbe Karte **„aptX"**. Das Helfer-Log
belegt den ausgelösten `codecStatus`-Aufruf.

### Falle, zum zweiten Mal getreten

`watchedDeviceKey` stand textlich **unter** `init` und war damit null, als
`combine` lief — sichtbar als scheinbar unzusammenhängender Coroutine-Absturz
(`this.$flows[this.$i] is null`), gefangen von `ScreenSmokeTest`. Dieselbe
Ursache wie bei `_liveDevOptions`. **Alles, was `init` benutzt, gehört textlich
darüber.**

**Stand: 473 Tests, 0 Fehler.**

---

# Fallen strukturell entschärft (22. August)

## 1. Die init-Reihenfolge ist jetzt ein Compiler-Fehler

Zweimal an einem Tag getreten: eine Property, die textlich **unter** `init`
steht, ist noch null, wenn `init` läuft. Beim zweiten Mal äußerte sich das als
`this.$flows[this.$i] is null` — ein Absturz, der auf nichts zeigt.

Ein Kommentar („bitte oben deklarieren") hätte den dritten Fall nicht
verhindert. Der Watcher ist deshalb kein `init`-Aufruf mehr, sondern ein
**Property-Initialisierer**:

```kotlin
private val codecInfo: StateFlow<CodecInfo> = combine(watchedDeviceKey, …)
```

Kotlin verbietet Vorwärtsreferenzen in Property-Initialisierern. Dieselbe
Verwechslung ist damit ein **Übersetzungsfehler statt eines Laufzeit-Nulls** —
die Falle existiert nicht mehr, statt beschrieben zu sein.

Nebeneffekt, den es umsonst gab: `WhileSubscribed` heißt, dass die Kette
**nichts** tut, solange der Screen zu ist. Der alte `init`-Collector lief für
die Lebensdauer des ViewModels weiter.

Die vier `MutableStateFlow` sind zu einem `CodecInfo`-Datensatz zusammengefasst:
`connected = true` bei `negotiated = null` ist ein echter Zustand („Kopfhörer da,
Android nennt den Codec nicht") und darf nicht mehr auseinanderdriften.

## 2. Der verwaiste Helfer ist weg — durch den Reconnect-Pfad

Beim Nachstellen zeigte sich, dass ich die Ursache falsch im Kopf hatte:
`adb install -r` **behält** die App-Daten und damit den Token. Das Log des
alten Helfers sagt nach der Neuinstallation schlicht

```
privileged helper: the app process is gone, waiting for it to return
privileged helper: re-attached to the restarted app
```

Der eigentliche Waisen-Fall war ein **zweiter** Helfer neben einem laufenden.
Vorher blieben beide, weil die App nach ihrem eigenen Neustart den alten nicht
mehr kannte und `retire(previous)` deshalb ins Leere lief. Mit dem
Reconnect-Pfad hängt der alte wieder an `PrivilegedConnection` — und wird beim
Start des neuen ordnungsgemäß stillgelegt.

**Gemessen:** Helfer A (23089) läuft, neuer Befehl aus dem Setup-Screen,
Helfer B gestartet → `ps | grep btdash_privileged` zeigt **genau einen** (23488).

Zusätzlich beendet sich ein Helfer jetzt selbst, wenn ein Re-Handover
*abgelehnt* wird: eine Ablehnung beweist, dass jemand anderes die App besitzt.
Ein lebender Helfer wird nie abgelehnt, sondern mit `Active` beantwortet.

## 3. „Beenden erzwingen" steht jetzt in der App

Der einzige Weg, auf dem der EQ ohne sichtbare Ursache aus sein kann: eine
force-gestoppte App bekommt von Android **gar keine** Broadcasts mehr — weder
den Boot- noch einen Kopfhörer-Connect — bis sie einmal von Hand geöffnet wird.
Das steht im System-access-Panel, weil weder die App es intern beheben noch der
Nutzer es erraten kann.

**Stand: 473 Tests, 0 Fehler.**

## Generate a new command + Umbenennung (Worker)

Abschnitt 9 ist erledigt: Im `HelperPanel` des System-access-Screens steht unter
dem ADB-Befehl jetzt ein `GoldOutlinedButton` „Generate a new command", der
`PrivilegedBootstrap.newAdbCommand()` aufruft und die angezeigte Zeile ersetzt.
Der Befehl liegt dafür in einem `mutableStateOf`, dessen `remember`-Key
weiterhin an `helperRunning` hängt — das ist kein zweites Minten: `adbCommand()`
gibt den bereits geprägten Session-Token zurück, solange er nicht verbraucht
wurde, also können automatischer Pfad und Knopf sich nicht in die Quere kommen.

Die Erklärzeile darunter (labelSmall, `onSurfaceVariant`) sagt genau das, was
stimmt: neuer Befehl, alte Zeile wird nicht mehr akzeptiert (der Pending-Token
ist überschrieben), **aber** ein laufender Helfer bleibt unberührt — er
antwortet auf den *aktiven* Token, und der wechselt erst, wenn der neue Befehl
ausgeführt und die Übergabe angenommen wird.

Umbenannt: `ShizukuOnboardingScreen.kt` → `SystemAccessScreen.kt`, Composable
`ShizukuOnboardingScreen` → `SystemAccessScreen`; Package bleibt `onboarding`.
Nachgezogen in `BtDashboardApp.kt` (Import + `composable(ROUTE_ONBOARDING)`) und
in `SystemAccessScreenTest.kt`. Der veraltete Hinweis „Der Dateiname sagt noch
Shizuku" im KDoc ist weg.

**Stand: 473 Tests, 0 Fehler.**

---

# Performance-Runde nach Audit (22. August, vormittags)

Ein Read-only-Audit (voller Bericht beim Auftraggeber) fand 5 HOCH-, 8 MITTEL-,
10 NIEDRIG-Befunde. In dieser Runde behoben (Haupt-Thread):

## H3 — Der globale EQ stapelte Instanzen (Korrektheits-Bug!)

`GlobalAttachmentStrategy.activate()` baute bei jedem Aufruf ein neues
`DynamicsProcessing` und ließ das alte ungeschlossen im Feld verschwinden —
und activate() läuft bei jedem Service-Start und Boot-Restore. Die Instanzen
**stapeln sich auf dem Output-Mix**: die Korrekturkurve wurde doppelt, dreifach
angewandt. Für eine Hörkompensations-App ist das kein Performance-, sondern ein
Falscher-Klang-Bug. Jetzt: lebende Instanz wird wiederverwendet (nur
`apply(current)`), tote wird `close()`d und ersetzt.

## H4 — ACL-Receiver filtert jetzt auf Audio-Geräte

Vorher startete jede Smartwatch/Auto/Gamepad-Verbindung den kompletten Prozess
(drei Graphen, EQ-Attach, DataStore). Jetzt: `BluetoothClass.Major.AUDIO_VIDEO`
aus dem Intent-Extra (kostet nichts). **Unbekannte Klasse startet weiterhin** —
ein Kopfhörer ohne gemeldete Klasse darf seinen EQ nicht an unsere Sparsamkeit
verlieren.

## H5 — Session-Receiver ist im Manifest aus

`AudioEffectSessionReceiver` weckte den Prozess bei jedem Trackwechsel jedes
braven Players — und warf die Zustellung in Global-Modus (Normalfall) in der
ersten Zeile weg. Jetzt `android:enabled="false"`; `EqController` schaltet die
Komponente per `setComponentEnabledSetting(DONT_KILL_APP)` genau dann ein, wenn
Session-Modus wirklich aktiv ist (Callback-Lambda, verdrahtet in SystemGraph —
der Controller selbst bleibt Context-frei).

## M3 — collectAsStateWithLifecycle projektweit

Alle Screens außer MonitorScreen (gehört gerade einem Worker) umgestellt.
Vorher lief `WhileSubscribed(5s)` nie ab, weil die Composition im Hintergrund
bestehen bleibt — Room-Queries, Helfer-Aufrufe und DataStore-Reads liefen
weiter. MainActivity inklusive (`initialValue`-Variante).

## M4, N1–N4 — Kleinvieh

- `SystemGraph.globalSettings` ist jetzt gecacht (war: neuer Controller pro
  Zugriff, N-mal pro Editor-Refresh).
- `MetalPalette`-Brushes sind `val` statt Getter (war: neues Gradient-Objekt
  pro Rekomposition an jeder Nutzstelle).
- Theme-Root: Palette + Scheme in `remember(accent)`/`remember(theme, …)`.
- `LocalGoldAccents`/`LocalMetalPalette` auf `staticCompositionLocalOf`.
- Preset-Swatches und Picker-Vorschau remembered.

## Service/Helfer-Tuning (eigene Runde davor)

- Helfer-Reconnect: exponentieller Backoff 3 s → 60 s Deckel (lief sonst
  stundenlang im 3-s-Takt, wenn die App nicht wiederkam).
- `EqForegroundService`: Notification folgt jetzt dem Attach-Status live
  (Collector, ereignisgetrieben); `started`-Guard, weil jeder BT-Connect
  `start()` ruft — Restore läuft nur einmal pro Service-Instanz.

Parallel laufen zwei Worker: Design-System auf die letzten drei Screens
(inkl. MonitorScreen-M3), und H1+H2 (Sampler-Poll bei Bildschirm-an ohne
Wiedergabe; ein dumpsys pro Durchlauf statt 2..N). Deren Abschnitte folgen.

## H1+H2: Sampler und dumpsys-Kosten (Worker)

Zwei HOCH-Befunde aus dem Performance-Audit, beide in `core-monitor`.

### H1 — „Bildschirm an" ist kein Poll-Grund mehr

`SamplingPolicy` hatte einen Zweig `isScreenOn -> Poll(BACKGROUND)`. Da
`MonitorGraph.ensureRunning()` aus `Application.onCreate` läuft und der
EQ-Service den Prozess am Leben hält, war das ein voller Sample-Durchlauf alle
60 s für jede wache Minute des Telefons — rund **200 Durchläufe pro Tag** ohne
Wiedergabe und ohne Screen, der das Ergebnis anzeigt, jeder mit einem
`dumpsys` durch den Helfer.

Neu in `MonitorConditions`: `uiVisible`. Die Regel lautet jetzt

```
isPlaying && isScreenOn  -> ACTIVE
isPlaying                -> BACKGROUND
isScreenOn && uiVisible  -> BACKGROUND   ("idle, monitor on screen")
sonst                    -> Stopped
```

Gesetzt wird das Flag über `MonitorGraph.setUiVisible(Boolean)` (Default
`false`, damit die billige Variante die ist, die man versehentlich bekommt).
`MonitorEngine` bekommt es als `uiVisible: StateFlow<Boolean>` herein und weckt
den Idle-Wait auch darauf.

**Die Events sind unberührt.** `BluetoothBroadcastSource` läuft weiter, jeder
Connect/Disconnect/Codec-Wechsel wird weiter aufgezeichnet und weckt den
Sampler. Gestoppt ist nur das *Pollen*.

Zwei Nebenkorrekturen, die dabei nötig wurden:

- `awaitWakeSignal()` wartete auf `screenOn.filter { it }`. Ein `StateFlow`
  spielt seinen aktuellen Wert einem neuen Collector sofort zu — solange
  „Screen an" immer Pollen bedeutete, konnte der Stopped-Zweig nie einen
  eingeschalteten Bildschirm sehen, jetzt ist genau das der Normalfall und die
  Schleife hätte sich totgedreht. Jeder Zweig wartet nun auf eine *Änderung*
  gegenüber dem Wert bei Betreten des Waits.
- `signalWake()` stempelte `clock()`. Zwei Signale in derselben Millisekunde
  waren eines, und ein Signal zum Zeitpunkt 0 war gar keines (0 ist auch der
  Startwert). Jetzt ein Zähler.

**Verdrahtet ist nur die ViewModel-Seite:** `MonitorViewModel.init` setzt
`true`, `onCleared()` setzt `false`.

> **Offen, eine Zeile für später** (`MonitorScreen.kt` gehörte während dieser
> Arbeit einem anderen Worker): Im Composable ein
> `LifecycleResumeEffect`/`DisposableEffect` ergänzen, das
> `MonitorGraph.setUiVisible(true)` beim RESUME und `false` beim PAUSE ruft.
> Der ViewModel-Haken deckt „Screen im Backstack" ab, aber nicht „App im
> Hintergrund, Monitor-Screen weiterhin oben" — dort pollt es derzeit noch
> weiter, solange das Display an ist. Dasselbe gilt optional für die
> Dashboard-Seite (`BluetoothDashboardViewModel.startWatchLive`), die aber
> ohnehin über Deep Capture läuft und damit unabhängig vom Flag pollt.

### H2 — ein Durchlauf, ein `dumpsys`

`dumpsys bluetooth_manager` ist der teuerste wiederkehrende Vorgang der App:
`ProcessBuilder`-Exec im Helfer, gesamter Dump Base64 über den Binder, dann ein
239-Zeilen-Regex-Parser. Ein Sample-Durchlauf zahlte ihn **2..N mal**: einmal
für die Geräteliste, danach einmal *pro Gerät* in
`FallbackCodecStatusSource.codecStatus()`, weil `BluetoothA2dp.getCodecStatus()`
auf Stock mit `SecurityException` scheitert und der Fallback deshalb jedes Mal
zieht.

Neu: `dumpsys/CachedDumpsysLinkSource.kt` — ein Dekorator mit TTL (5 s) und
`Mutex`. Der Mutex ist nicht nur für die Felder da: zwei gleichzeitig
ankommende Aufrufer teilen sich einen Dump, statt zwei Execs zu starten.

`MonitorGraph` baut davon **genau einen** (`MonitorGraph.dumpsysSource`) und
reicht ihn an `codecSource`, den Collector der Engine und `collectorSource()`.
`MonitorViewModel` benutzt ihn für den Diagnostik-Soak ebenfalls, statt sich wie
bisher ein eigenes `ShellDumpsysLinkSource` zu bauen.

**Warum TTL und keine Ereignis-Invalidierung:** 5 s ist kürzer als das
schnellste Sampling-Intervall (DEEP, 10 s), der Cache kann also nur *innerhalb*
eines Durchlaufs zusammenfassen und nie eine Lesung in den nächsten
hinübertragen. Eine Verbindungsänderung kann er auch nicht verschleiern, weil
die UI-Geräteliste an `connectedDevicesFlow()` auf den Broadcasts hängt und
nicht an diesem Poll. `invalidate()` existiert für den Aufrufer, der es besser
weiß.

Zusätzlich bricht `LinkSampleCollector.collect()` jetzt früh ab, bevor
irgendetwas eine Shell anfasst: sind weder A2DP-Profil noch Shell erreichbar —
oder ist kein Gerät verbunden und die Shell nicht verfügbar — gibt es keinen
Mechanismus, der eine Zeile liefern könnte.

### Tests

Neu:

- `SamplingPolicyTest`: „Bildschirm an, nichts spielt, UI unsichtbar → Stopped",
  plus offener Monitor-Screen, Display aus bei sichtbarer UI, und Wiedergabe
  pollt unabhängig davon, ob jemand hinsieht.
- `IdleWakeupTest`: heller Bildschirm allein kostet keinen einzigen `sleep`;
  offener Monitor-Screen beendet den Idle-Wait; Verlassen des Screens legt den
  Sampler wieder schlafen.
- `DumpsysSnapshotCacheTest` (neu): **ein voller Durchlauf = ein `dumpsys`**
  (zwei Geräte, A2DP-Profil verweigert, also der teure Pfad), TTL-Grenze,
  acht parallele Aufrufer = ein Dump, `invalidate()`, und „unavailable wird nie
  gedumpt".

Angepasst, mit Begründung im jeweiligen Test-KDoc:

- `SamplingPolicyTest.an anomaly bursts to 5 seconds only while playing` — der
  Idle-Zweig bekommt `uiVisible = true`. Thema des Tests ist das Burst-Fenster,
  nicht die Idle-Regel; ohne das Flag hätte er plötzlich etwas anderes geprüft.
- `IdleWakeupTest.the screen coming on ends the idle wait` → umbenannt in
  `the screen coming on alone does not start polling`; die Erwartung
  BACKGROUND war durch H1 wirklich falsch geworden.
- `IdleWakeupTest.a bluetooth event ends the idle wait` — von CODEC_CHANGED auf
  PLAYING_STARTED. Die Aussage („ein Broadcast beendet den Wait, kein Timer")
  ist unverändert; ein Codec-Wechsel ohne Wiedergabe und ohne offenen Screen
  ist jetzt aber korrekterweise kein Poll-Grund mehr und kann die Behauptung
  nicht mehr tragen.

---

# Design-System: HearingTest/Monitor/Compensation (Worker)

Die letzten drei rohen Screens tragen jetzt dasselbe Panel/Metall-Vokabular wie
Bluetooth und Settings. Kein Verhalten geändert, keine Formulierung ersetzt —
nur Struktur, Typo und Statusdarstellung.

## Was umgestellt wurde

**`ui/screens/hearing/HearingTestScreen.kt`** (11 Panel-Vorkommen)

- Screen-Titel auf `displayMedium`, Seitenränder von 24/16 dp auf die
  Hausmaße 16/12 dp.
- Intro zerfällt in Panels statt in eine Textwand: „What happens", „Before you
  start" (der Satz war vorher die erste Zeile des Blocks und ist jetzt dessen
  Eyebrow), „Your headphones", „Run it", „Stored runs".
- `Button`/`OutlinedButton` → `GoldButton`/`GoldOutlinedButton`; die
  Meldungs-`Card` → `Panel`.
- „Run the fit check first." ist jetzt eine `Pill(WARN)` statt roter Fließtext,
  ein bestandener Fit-Check eine `Pill(ACCENT)` im Panel-Header.
- Ergebnis- und Verlaufsansicht bekommen Panels („This run", „Your hearing",
  „All runs overlaid"); jeder gespeicherte Lauf ist ein eigenes Panel mit dem
  Zeitstempel als Header.
- `Readout` für die Zahl gespeicherter Läufe.

**`ui/screens/monitor/MonitorScreen.kt`** (4 Panel)

- Der Screen hatte die Panels schon; ergänzt wurden die Statuselemente.
- Sampling-Modus ist jetzt eine `Pill` im Header „Data source" — der
  danebenstehende Satz trägt nur noch `status.reason`. Modus-Töne:
  DEEP/BURST/ACTIVE = ACCENT, BACKGROUND/STOPPED = NEUTRAL (ein ruhender
  Poller ist kein Fehler und bekommt keine Warnfarbe).
- Sample-/Event-Zähler als `Readout`.
- Diagnose-Schritte: `[OK]`/`[FAIL]` waren als Klammermarker vor dem Satz
  optisch nicht unterscheidbar — jetzt `Pill` mit Ton, gleiche Wörter.
- Verdikt hinter einem `PanelDivider`; „running"-Pill im Header.
- Tote Importe (`Card`, `Button`, `OutlinedButton`, `GoldCard`, `GoldTitle`)
  entfernt, Panel-Blöcke korrekt eingerückt.
- Zusätzlich auf Wunsch des Koordinators: `collectAsState()` →
  `collectAsStateWithLifecycle()` für alle fünf Flows — die Datei war die
  letzte im `ui`-Baum mit der alten Form.

**`ui/screens/eq/CompensationSection.kt`** (3 Panel)

- Das rohe `Card(Modifier.padding(16.dp))` um alles ist ein `Panel`.
- `HorizontalDivider` → `PanelDivider` (die Vollton-Linie zerschnitt das Panel
  und ließ die Presets wie eine zweite Karte wirken).
- „Correction strength" ist jetzt `PanelHeader` + `Readout` statt Label und
  Prozentwert an den beiden Enden einer Zeile.
- Zwischenüberschriften „Preview", „Left / right difference", „Presets" als
  `PanelHeader`.
- Die zwei inneren `Card(outlinedCardColors)` (Adjusted Reference und die
  gespeicherten Presets) sind `Panel(contentPadding = 12)`; „· active" hängt
  nicht mehr als Text am Namen, sondern ist eine `Pill(ACCENT)`.
- „Save" und „Use it" auf `GoldOutlinedButton`.

## Was bewusst nicht umgestellt wurde

**Der laufende Hörtest (`RunningContent`)** bleibt schwarz, ohne Panel, ohne
Metall, mit gewöhnlichem `Button` für den großen Kreis. Das ist der einzige
Screen, auf dem der Nutzer *hören* und nicht schauen soll: ein goldgerandeter
Rand im Augenwinkel ist hier eine Ablenkung mit messbarem Preis — ein
überhörter leiser Ton ist eine falsche Schwelle. Steht so im Kommentar über der
Funktion, damit es nicht beim nächsten Design-Durchgang „nachgezogen" wird.

**`TextButton`** bleibt überall dort, wo es dicht steht (Load/Delete in
Preset-Zeilen, „OK"/„Dismiss" an Meldungen). Ein umrandeter Metallknopf pro
Listenzeile wäre lauter als der Inhalt.

**Keine Pills in `EventRow`.** Ereignisse sind eine Protokollzeile, kein
Zustand; vierzig Pillen untereinander wären eine Tabelle. Aus demselben Grund
keine Trennlinien zwischen den Zeilen — das Panel setzt den Abstand schon.

**Farben:** keine Datei zieht `Gold.*` direkt; alles läuft über
`Panel`/`Pill`/`Readout`/`Gold*Button`, die intern `LocalMetalPalette` bzw.
`LocalGoldAccents` gaten. Sekundärtext bleibt auf `outline`/`onSurfaceVariant`.

## Abdeckung und Tests

`grep -cE 'Panel\s*[({]'` → HearingTestScreen 11, MonitorScreen 4,
CompensationSection 3. Kein rohes `Card(` mehr in den drei Dateien.

**`./gradlew testDebugUnitTest`: 486 Tests, 0 Fehler** (parallele Arbeit im
selben Baum hat die Zahl seit den 473 weiter erhöht; aus dieser Änderung kommt
kein neuer Test — sie ist reine Darstellung).

## Falle dieser Runde

`bundleLibRuntimeToJarDebug` schlug minutenlang mit
`FileSystemException … classes.jar … used by another process` fehl. Kein
Codefehler: ein zweiter Gradle-Lauf im selben Arbeitsbaum hielt das Jar. Wer
parallel arbeitet, sollte den Testlauf wiederholen statt Daemons zu killen —
ein `--stop` hätte dem anderen Worker den laufenden Build zerrissen.

## Offen

Am Gerät ist nichts davon angesehen worden; die Panels sind nur durch die
Robolectric-Smoke-Tests gelaufen. Insbesondere zwei Stellen sind einen Blick
wert: das verschachtelte `Panel` in `Panel` bei den Presets (Verlauf auf
Verlauf, könnte flach wirken) und die `Readout`-Größe von 30 sp für den
Prozentwert der Korrekturstärke.

## M5 — Deadlock-Fenster im Helfer-exec geschlossen

`execute()` las stdout bis EOF, dann stderr, **dann** kam der Timeout. Füllt ein
Kind den 64-KB-stderr-Puffer, blockiert es beim Schreiben, stdout erreicht nie
EOF, `readText()` hängt — und der Timeout dahinter wird nie erreicht: ein für
immer verkeilter Binder-Thread im Helfer. stderr wird jetzt parallel auf einem
eigenen Thread geleert (`CompletableFuture`), mit eigenem Timeout beim Abholen.
`redirectErrorStream(true)` wäre einfacher gewesen, hätte aber die getrennte
stderr-Übertragung gebrochen, auf der die Codec-Fehlertexte beruhen.

## Gerätemessung nach der Performance-Runde (22. August)

Merge-Build (alle Worker + Haupt-Thread), Bathys verbunden, frisch installiert.

- **Kaltstart sauber**, kein Crash-Buffer-Eintrag, Service `isForeground=true`.
- **EQ-Stapel-Leak (H3) behoben, am Gerät belegt:** `dumpsys media.audio_flinger`
  → „1 effects for session 0", und nach **3× Service-Start** immer noch genau
  eins. Vorher wäre pro Start ein weiterer DynamicsProcessing dazugekommen.
- **Hintergrund-Last (H1/M3):** nach UI-Wegwischen + Bildschirm aus, 45 s Doze:
  App-uid `bg: 0.000161`, taucht nicht unter den Top-Verbrauchern auf. Die
  sichtbare `fgs`-Zeit ist der EQ-Service selbst (gewollt). Kein Poll-Leerlauf
  mehr.
- **Session-Receiver (H5):** Komponente im Manifest disabled; wird von
  EqController nur im Session-Modus eingeschaltet.

MonitorScreen ON_START/ON_STOP → `MonitorGraph.setUiVisible` ist verdrahtet
(die vom Worker offen gelassene Zeile). Voller Testlauf über den
zusammengeführten Baum: **486 Tests, 0 Fehler.**

---

# Autonome Runde — Abschluss (22. August, ~Mittag)

Zwei Read-only-Audits durchgeführt und ins Repo persistiert:
- **`ANDROID17_READINESS.md`** — Doppelrückstand (Build SDK 35, Gerät 16);
  FGS-connectedDevice als Hauptrisiko; Helfer ist blocklist-immun, In-App-
  Reflection nicht; „jetzt vorbereitbar" vs. „erst mit 17-SDK testbar".
- **`PLAYSTORE_COMPLIANCE.md`** — harte Blocker (QUERY_ALL_PACKAGES, Data-Safety-
  Formular, FGS-Deklaration), Risiken, Empfehlung separater Store-Flavor.

Aus den „jetzt vorbereitbar"-Punkten sofort umgesetzt (klein, risikoarm):
- `android:enableOnBackInvokedCallback="true"` (Predictive Back, ab targetSdk 36
  ohnehin default).
- FGS-`onFailure` unterscheidet jetzt Typ-Refusal von generischem Fehler — der
  17-Test zeigt damit sofort, ob `connectedDevice` bricht.
- `THIRD_PARTY_LICENSES.md` (R4: Apache-2.0-Attribution; bislang fehlte NOTICE).

**Bewusst NICHT gemacht** (zu groß/riskant für die autonome Runde, gehören als
eigene Tasks angefasst):
- SDK-Bump 35→36 inkl. AGP-Upgrade — Toolchain-Risiko, eigener Durchlauf mit
  Frischklon-Build.
- QUERY_ALL_PACKAGES entkoppeln (B1) — Feature-Verhalten + Testanpassung.
- Store-Flavor-Trennung (Helfer/QAP raus) — Architekturentscheidung, gehört dem
  Besitzer.

**Gesamtstand dieser Runde: 486 Tests, 0 Fehler; Merge-Build am Gerät sauber
(Kaltstart ok, kein Crash, 1 Effekt auf Session 0, Hintergrund-CPU ~0).**

## Test 1 — Boot-Pfad: bestanden (22. August, 14:04)

Echter Neustart, `stopped=false`, App **nicht** geöffnet. Logcat belegt: Prozess
10498 wurde für `dev.dankyeeter.btdashboard.system.boot.BootReceiver` gestartet —
also der **echte `BOOT_COMPLETED`**, nicht der BT-Trigger. Zeitpunkt: boot_completed
14:03:54 → Prozessstart 14:04:13 (~17 s), Service Foreground nach ~30 s, 1 Effekt
auf Session 0. Bathys verband nebenbei automatisch (Connected count 1), der
ACL-Trigger wäre also ohnehin dagewesen.

Damit ist die frühere „ungetestet"-Notiz erledigt: der Fehlschlag beim ersten
Versuch lag ausschließlich am vorherigen force-stop (stopped-state unterdrückt
BOOT_COMPLETED). Beide Einstiegspunkte sind jetzt am Gerät belegt.

## Test-2-Blocker vorab behoben: RECORD_AUDIO fehlte

`AcousticEqTest` nimmt per Mikrofon auf, aber **kein Modul-Manifest deklarierte
RECORD_AUDIO** — AndroidJUnitRunner hätte es nie granten können, die Aufnahme
wäre Stille, der Test rot aus einem Grund, der nichts mit dem EQ zu tun hat. Neu:
`core-audio/src/androidTest/AndroidManifest.xml` mit RECORD_AUDIO; im gemergten
androidTest-Manifest verifiziert. (Der Test prüft nur `OCTAVE_10` bei 500–8000 Hz,
Schwelle: +12 dB Boost muss ≥ +3 dB an der Luft bringen; Lautsprecher-Route,
kein BT nötig.)

## Test 2 — Akustischer EQ-Test: aufschlussreicher Fehlschlag (22. August, 14:19)

Über Lautsprecher (BT per `svc bluetooth disable` abgeschaltet, danach wieder an),
ruhiger Raum. Zwei Befunde:

**a) RECORD_AUDIO-Autogrant über Gradle versagt.** Der `connectedDebugAndroidTest`-
Lauf warf `AudioRecord uninitialized` — die Permission war trotz Manifest nicht
gewährt. Manueller `adb install -r -g` + `am instrument` behebt es
(`granted=true` verifiziert). **Für belastbare Läufe: Test-APK mit `-g`
installieren und per `am instrument` fahren, nicht über die Gradle-Task.**

**b) Der gemessene Effekt ist null — an allen Bändern:**
```
500 Hz: -0,8 dB   1000 Hz: -0,9 dB   2000 Hz: -1,0 dB   4000 Hz: +0,2 dB   8000 Hz: -2,1 dB
```
Ein +12 dB-Boost ist an keinem der fünf Bänder in der Luft sichtbar (Schwelle
+3 dB). Kein Lautsprecher-Artefakt einer Frequenz, sondern flächendeckend keine
Wirkung.

**Was das heißt — und was NICHT:**
- Der Test erzeugt einen **session-gebundenen** `DynamicsProcessing` auf seinem
  eigenen AudioTrack (`create(track.audioSessionId, …)`). Gemessen wird also der
  **Session-Pfad**, nicht der **globale** Attach auf Session 0, den die App real
  nutzt (und der laut dumpsys als „1 effect for session 0" hängt).
- Damit ist es **kein** Beweis, dass die EQ-Funktion des Nutzers tot ist — aber
  es ist der erste empirische Hinweis überhaupt, dass ein *attachter* Effekt das
  Audio nicht zwingend hörbar verändert. Bisher war „EQ wirkt" nur über die
  Attach-Existenz belegt, nie akustisch.
- Offen bleibt die eigentliche Frage: **wirkt der globale Session-0-Attach
  hörbar?** Der jetzige Test kann das nicht sagen — er misst den falschen Pfad.

**Test wurde umgebaut** (dauerhaft): misst jetzt alle Bänder und urteilt über die
Mehrheit statt beim ersten Band abzubrechen — sonst hätte man die -0,8 dB bei
500 Hz für das ganze Bild gehalten.

**Nächster Schritt (Design-Entscheidung, nicht blind):** einen Messpfad für den
**globalen** EQ bauen — Service-EQ per EqController mit einem Boost
konfigurieren, normalen Media-Ton spielen, per Mikrofon messen. Cross-Prozess
(Service läuft im App-Prozess), daher nicht trivial. Erst wenn das den globalen
Pfad ebenfalls bei 0 dB zeigt, ist die EQ-Wirkung wirklich widerlegt.

## Test 2 — akustischer EQ-Test: aufgeklärt, Bug gefunden und behoben

### Ausgangslage
Erste Messungen zeigten **0 dB auf allen Bändern** — sowohl session-gebunden als
auch global. Daniel hörte ebenfalls keinen Unterschied zwischen den beiden
Testtönen. Zwei Störfaktoren waren offen: YouTube lief im Hintergrund, und die
Messkette selbst war nie validiert.

### Was tatsächlich los war (zwei getrennte Sachen)

**1. Der Lautsprecher-Schutzlimiter machte jede Messung blind.**
Ein Kontrolltest (`the_microphone_can_hear_a_known_level_difference`) spielt
denselben Ton ohne jeden EQ zweimal — bei −12 und −18 dBFS. Die Kette sieht den
6-dB-Schritt sauber (+2,9 bis +7,0 dB). Die Kette funktioniert also.

Damit war die Asymmetrie erklärt: **Absenkungen sind sichtbar, Anhebungen nicht.**
Bei 80 % Lautstärke lässt der Lautsprecherschutz einen Ton nicht lauter werden,
also wurde jede +12-dB-Anhebung nachgelagert weggedrückt — ununterscheidbar von
einem toten EQ. Auch der klassische `Equalizer` zeigte dasselbe Bild, was den
Verdacht auf einen kaputten Effekt entkräftete.

Gegenprobe mit einer **Absenkung** bei 40 % Lautstärke, die kein Limiter
kaschieren kann:

| Pfad | 500 Hz | 1000 Hz | 2000 Hz | 4000 Hz | 8000 Hz |
|---|---|---|---|---|---|
| Session-EQ, −18 dB verlangt | −14,4 | −14,3 | −14,6 | −15,0 | −14,2 |
| **Global (Session 0), −18 dB** | **−13,7** | **−14,3** | **−14,8** | **−14,9** | **−14,9** |
| Alle Bänder, −12 dB verlangt | −11,5 | −11,4 | −11,8 | −11,9 | −11,8 |

Der EQ ist im Signalweg und arbeitet präzise — **auch auf dem Ausgabemix**, dem
Pfad, den die App benutzt. Der ursprüngliche 0-dB-Befund war kein EQ-Fehler.

**2. Ein echter Bug: jedes Band lag eine halbe Oktave zu tief.**
`DynamicsProcessing.EqBand.cutoffFrequency` ist die **Oberkante** eines Bandes,
kein Mittenwert. `DynamicsProcessingEqualizer` schrieb dort Oktav-Mittenfrequenzen
hinein. Am Gerät ausgemessen (Band „1000 Hz" um 18 dB abgesenkt):

| | 400 Hz | 600 Hz | 800 Hz | 1000 Hz | 1300 Hz | 1800 Hz |
|---|---|---|---|---|---|---|
| vorher | −0,6 | **−14,7** | **−14,3** | **−14,1** | −0,2 | 0,0 |
| nachher | −0,2 | 0,0 | −4,8 | **−14,5** | **−14,7** | 0,0 |

Vorher regelte der Regler „1000 Hz" also 600–1000 Hz. Für eine aus dem Hörtest
abgeleitete Korrekturkurve heißt das: die falsche halbe Oktave wird angehoben —
plausibel aussehend und falsch.

**Fix:** `EqBandLayout.upperEdgesHz` (neu) liefert `Mitte × 2^(Oktavanteil/2)`;
`octaveFraction` ist pro Layout hinterlegt (1, ½, ⅓). `DynamicsProcessingEqualizer`
schreibt diese Kanten in `cutoffFrequency` — in `buildConfig` und in `writeBand`.
Regression abgesichert durch `EqBandEdgeTest` (4 Tests, u. a. dass die Kanten in
jedem Layout aufsteigend bleiben — sonst lehnt DynamicsProcessing die Config ab
und der EQ stirbt still).

### Testsuite danach
`AcousticEqTest` hat jetzt 5 Tests, alle grün, alle auf Absenkungen umgestellt:
Kontrolltest, Session-Cut, Global-Cut, Alle-Bänder-Cut, Band-Mapping-Sonde.
Die alten Boost-Tests sind entfernt — sie konnten prinzipbedingt nur den
Lautsprecherschutz messen. `EqDiagnosticTest` (Effekt-Inventar, Legacy-Equalizer)
hat seine Frage beantwortet und wurde wieder entfernt.

Volle Unit-Suite grün. App mit dem Fix gebaut und installiert; Bluetooth wieder
aktiviert, Test-APK deinstalliert.

**Merke für künftige akustische Tests:** über den Telefonlautsprecher immer mit
Absenkungen messen, nie mit Anhebungen.

## Bluetooth: EQ hoerbar? — offen, Beweislage widerspruechlich

### Stand
Ueber **Lautsprecher** ist der EQ bewiesen (18 dB Absenkung -> 14 dB gemessen).
Ueber **A2DP zu den Bathys** berichtet Daniel "klangen ident". Alle strukturellen
Pruefungen sagen aber, dass der Effekt dort laeuft.

### Was geprueft wurde (alles ohne Ohren, per dumpsys/Readback)

**Spatializer-These — geprueft und widerlegt.**
Der A2DP-Ausgang laeuft auf diesem Pixel ueber einen SPATIALIZER-Thread (Typ 7),
nicht ueber den normalen Mixer — Log: *"Enabling Spatial Audio since enabled for
media device: bt_a2dp"*. Naheliegende Vermutung war, dass die Session-0-Kette
dort nicht existiert. Waehrend laufender Wiedergabe zeigt der Thread aber:

```
SPATIALIZER (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)
    1 effects for session 3793   <- track-gebunden
    1 effects for session 0      <- Ausgabemix, unser Attach-Punkt
    1 effects for session -1     <- Spatializer selbst
```

Beide Attach-Punkte liegen auf dem richtigen Thread.

**Effektzustand — gesund.**
```
Session State Registered Internal Enabled Suspended:
00000   003   y          n        y       n
```
State 003 = ACTIVE, enabled = y, **suspended = n**. UUID/Typ bestaetigt
DynamicsProcessing. Kein Suspend durch den Spatializer.

**Konfiguration ueberlebt den Threadwechsel.**
Vermutung war, dass AudioFlinger die Kette beim Wiedergabestart auf den
A2DP-Thread verschiebt und den Effekt dabei flach neu anlegt. Readback nach
Playback-Start: `band8 global=-15.0 session=-15.0` — Gains sind intakt.

**"Active tracks: 0"** auf der Session-0-Kette ist normal: eine Ausgabemix-Kette
verarbeitet den Mix, nicht einzelne Tracks (die Track-Kette zeigt korrekt 1).

### Schlussfolgerung
Jede messbare Groesse sagt "laeuft". Dagegen steht ein subjektiver Bericht. Das
kann nur ein eindeutigerer Reiz oder eine echte Messung aufloesen — nicht noch
mehr dumpsys.

### Naechster Schritt: `AudibleEqDemoTest` (umgebaut, liegt bereit)
Sechs Abschnitte statt fuenf, und der **erste Kontrast ist Lautstaerke, nicht
Klangfarbe**: alle Baender −15 dB. Ein 15-dB-Pegelsprung ist auf jeder
funktionierenden Kette unueberhoerbar. Ist *der* nicht hoerbar, liegt es an
Route oder Lautstaerke, nicht am EQ — und das muss man wissen, bevor man weiter
am Code sucht. Danach erst die beiden Klangfarben-Kontraste.

Weitere Aenderungen: ein Effekt fuer die ganze Demo (Gains werden zwischen den
Abschnitten umgesetzt, statt sechsmal neu anzuhaengen), Re-Apply nach
`track.play()`, Gain-Readback pro Abschnitt im Log, und breitbandigeres Rauschen
— das alte war basslastig, wodurch der "Hoehen abgesenkt"-Abschnitt kaum etwas
zu entfernen hatte.

```
adb shell am instrument -w -r -e class \
  dev.dankyeeter.btdashboard.audio.eq.AudibleEqDemoTest \
  dev.dankyeeter.btdashboard.audio.test/androidx.test.runner.AndroidJUnitRunner
```

**Alternative, die ohne Gehoer auskommt:** eine Bathys-Ohrmuschel direkt an das
Telefonmikrofon legen und `AcousticEqTest` laufen lassen. Dann misst die
bestehende Kette den Kopfhoererausgang statt den Raum, und die Frage ist
objektiv entschieden.

### Nebenbefund: Bandkanten-Fix ist durchgaengig
`cutoffFrequency` wird nur noch an einer Stelle geschrieben
(`DynamicsProcessingEqualizer`), und die nutzt `upperEdgesHz`. Die uebrigen
`centersHz`-Nutzungen sind UI-Beschriftungen und der NAL-R-Rechner, der
Audiogramm-Schwellen auf Bandmitten abbildet — die zeigen jetzt erst auf das,
was das Band tatsaechlich tut. Volle Unit-Suite und App-Build gruen.

## GELOEST: Warum der EQ ueber Bluetooth nichts tat

> **Korrektur:** Dieser Abschnitt nannte zuerst Spatial Audio als Ursache. Das
> war falsch — siehe den Nachtrag am Ende des Abschnitts.

### Der Beweis
Mit dem Telefon an den Ohrmuscheln liess sich die Abstrahlung endlich messen
statt raten. Voraussetzung war ein Stoerabstands-Test: bei voller Lautstaerke
liegt der Ton +47 dB (1 kHz) bis +58 dB (8 kHz) ueber dem Rauschen — bei 500 Hz
nur +0,7 dB, geschlossene Kopfhoerer strahlen keinen Bass ab. Deshalb misst der
Leakage-Pfad ab 1 kHz und mit lauterem Ton als der Lautsprecher-Pfad.

18-dB-Absenkung, ueber die Bathys gemessen:

```
Session EQ:  1000 Hz -10,2   2000 -7,7   4000 -7,7   8000 -7,8   BESTANDEN
Global EQ:   1000 Hz Rauschen  2000 -0,1  4000 -0,4  8000 -0,4   DURCHGEFALLEN
```

Der session-gebundene EQ wirkt ueber Bluetooth. Der globale (Session 0) — den
die App benutzte — wirkt **gar nicht**. Genau Daniels Hoereindruck.

### Die Ursache
Spatial Audio. Bei Bluetooth (nicht am Lautsprecher) laeuft die Ausgabe ueber
einen SPATIALIZER-Thread, und das spatialisierte Signal passiert die
Ausgabemix-Effektkette nicht.

Das Perfide: **nichts meldet einen Fehler.** Der Effekt haengt auf dem richtigen
Thread, meldet ACTIVE, enabled, nicht suspendiert, und seine Gains lesen exakt
so zurueck wie geschrieben. Der einzige sichtbare Hinweis ist `Active tracks: 0`
auf dieser Kette — den hatte ich zuvor faelschlich als normal abgetan.

### Der Fix
`SpatializerGate` (neu) fragt ueber die oeffentliche `Spatializer`-API, ob ein
Session-0-Effekt den Ausgang ueberhaupt erreicht: `isEnabled && isAvailable`
heisst "Spatializer ist gerade aktiv", also ist der Ausgabemix der falsche
Anhaengepunkt. Faellt **offen** aus — bei Unklarheit gewinnt der breitere Modus.

`EqController` fragt das **vor** jedem globalen Attach, weil der Attach selbst
Erfolg meldet und seinen eigenen Zustand nicht misstrauen kann. Bei Nein:
Rueckfall auf Session-Modus.

`ensureAttached()` prueft jetzt zusaetzlich, ob der *lebende* Modus noch der
richtige fuer die aktuelle Route ist. Ohne das blieb der Bug auf dem Umweg
bestehen: am Lautsprecher global angehaengt, dann Bathys verbunden -> Connect
ruft `ensureAttached`, der Effekt lebt, also passiert nichts — und der EQ ist
still. Umgekehrt gewinnt Trennen die breitere Reichweite zurueck, statt den
Nutzer bis zum naechsten Reglerzug im Session-Modus zu lassen.

Der Statustext nennt jetzt die Ursache und die Stellschraube. Vorher zeigte er
den generischen Session-Text, der auf den privilegierten Helfer verweist — hier
schlicht falsch: der Helfer aendert daran nichts, und der Rat haette Daniel auf
den einzigen Bildschirm geschickt, der nicht helfen kann.

Am Geraet verifiziert: kein Session-0-Effekt mehr, Meldung *"Spatial Audio is on
for this output and bypasses the system-wide equalizer... Turn Spatial Audio off
for this device to get the equalizer back for everything."*

### Neue Tests
`EqControllerSpatializerTest` (6 Faelle): Vorzug fuer global wenn hoerbar,
Rueckfall wenn nicht, Receiver nur im Session-Modus, Neubewertung bei jedem
apply, und beide Routenwechsel ueber `ensureAttached`. Die Faelle sind gepinnt,
weil der verhinderte Fehler unsichtbar ist — die App saehe angehaengt aus und
korrigierte nichts.

`AcousticEqTest` um den Leakage-Messpfad erweitert (global, session, plus
eigener Kontrolltest bei denselben Pegeln). `LeakageSnrProbeTest` (neu) misst
den Stoerabstand, damit ein 0-dB-Ergebnis nie wieder mit "nicht messbar"
verwechselt wird.

### Was das fuer den Nutzer heisst
Mit Spatial Audio an erreicht der EQ nur noch Apps, die ihre Audio-Session
ankuendigen. Das ist weniger als vorher versprochen, aber mehr als vorher
geliefert (null). Wer die volle Reichweite will, schaltet Spatial Audio fuer das
Geraet ab — dann greift wieder der globale Pfad, automatisch.

### Nachtrag (22. August, spaeter): Spatial Audio war NICHT die Ursache

Daniel hat Spatial Audio fuer die Bathys abgeschaltet. Der globale Pfad blieb
wirkungslos — drei weitere Laeufe, alle um null streuend. Die Spatializer-These
ist damit widerlegt, obwohl sie gut passte (A2DP laeuft ueber einen
SPATIALIZER-Thread, `Active tracks: 0` auf der Session-0-Kette).

### Zwischenfall: die Messkette war selbst unzuverlaessig

Daniel hoerte, dass "der erste Ton immer lauter ist als der zweite". Da jeder
Test **erst flach, dann abgesenkt** misst, waere ein systematischer Versatz von
einer echten EQ-Wirkung nicht zu unterscheiden gewesen — er haette eine
Absenkung aus dem Nichts erzeugt und die Assertion haette bestanden.

Neuer Test `measuring_the_same_thing_twice_gives_the_same_answer` (A/A): zweimal
dasselbe messen, Erwartung 0 dB. Ergebnis: bis 5 dB Abweichung, bei 1 kHz
45-dB-Ausreisser. Eine *systematische* Richtung zeigte sich nicht — es war
Rauschen, kein Versatz. Aber die vorherigen Einzelmessungen waren damit zu
wackelig fuer die Aussage, die ich aus ihnen gezogen hatte.

Behoben durch `measureMedian`: jede Messung fuenfmal, Median statt Mittelwert
(ein Ausreisser wird ignoriert statt eingerechnet). Danach A/A: −3,2 / −0,2 /
+0,9 / −1,9 dB, also ein Rauschband von rund **±3 dB**.

### Das belastbare Ergebnis

Mit robuster Messung, Spatial Audio aus, 18-dB-Absenkung:

```
Session EQ:  1000 -9,5   2000 -8,9   4000 -7,6   8000 -6,0    BESTANDEN
Global EQ:   1000 -0,2   2000 -0,2   4000 -0,2   8000  0,0    exakt null
```

Der Ausgabemix-Attach erreicht den Bluetooth-Ausgang dieses Geraets nicht —
unabhaengig von Spatial Audio. Der session-gebundene Attach wirkt.

### Fix (korrigiert)

`OutputMixReachGate` ersetzt den falsch benannten `SpatializerGate`. Er prueft
per `getAudioDevicesForAttributes` die Route, die Medien *tatsaechlich* nehmen
wuerden — ein verbundenes, aber ungenutztes Geraet darf nicht in den engeren
Modus zwingen. Bluetooth-Ausgang -> globaler Attach gilt als unzuverlaessig.
Der Spatializer bleibt als *zweite* Bedingung stehen (wo er greift, ist er ein
echter Bypass), aber er ist nicht der Grund fuer Bluetooth.

Faellt **offen** aus: laesst sich die Route nicht bestimmen, gilt global als
nutzbar. Die Asymmetrie traegt das: faelschlich "nicht erreichbar" zu raten
kostet Reichweite bei Apps, die ihre Session nicht ankuendigen — faelschlich
"erreichbar" kostet den Nutzer *alles* und sagt nichts dazu.

Der Statustext nennt jetzt Bluetooth statt Spatial Audio. Am Geraet verifiziert:
kein Session-0-Effekt mehr, Meldung *"Over Bluetooth this phone does not pass
the system-wide equalizer through..."*.

### Ehrliche Einordnung
Ich hatte die Spatializer-These vorschnell als "die Antwort" verkauft, bevor die
Messkette validiert war. Der A/A-Test haette vor jeder Schlussfolgerung stehen
muessen; er existiert jetzt und ist die Bedingung fuer alle weiteren Aussagen.

## Erst-Attach geloest: ein frischer Effekt, kein erneutes Anwenden

**Symptom.** Nach einem Kaltstart haengt der EQ an Tidals Session, wirkt aber
nicht. AudioFlinger zeigt `Registered=y, Enabled=n`, waehrend die App
`setEnabled` erfolgreich meldet, `getEnabled` true zurueckliest und
`hasControl` true ist. Die App kann den Fehler also nicht sehen.

**Zwei Messfehler auf dem Weg, beide meine.** Erstens habe ich lange nur die
erste Trefferzeile im `dumpsys` gelesen — auf der Session liegen aber mehrere
Effekte, darunter eine "Decibel Spatializer Library", und ein `-A40`-Fenster
greift in die Nachbarkette. Zweitens gehoerte eine der abgelesenen Zeilen zu
Session `-0001`, nicht zu 8009. Erst eine Auswertung, die jede Effekt-ID mit
*ihrer eigenen* Flag-Zeile paart, war belastbar. Danach war der Fehler
reproduzierbar: 2 von 3 Kaltstarts.

**Was nicht half.** Ein `setEnabled` mit Rueckleseprüfung und Wiederholung — die
Rueckgabe luegt. Und ein erneutes Anwenden der Einstellungen auf dasselbe
Effekt-Objekt: gemessen ueber drei Kaltstarts, blieb `Enabled=n`.

**Was half.** Ein *frisch erzeugter* Effekt auf derselben Session. Das steckte
die ganze Zeit im Workaround: Pause/Play reparierte es, und was Pause wirklich
tut, ist den Effekt zu schliessen und beim Fortsetzen neu anzulegen.

**Fix.** `SessionAttachmentStrategy.reattachAll()` schliesst und baut jeden
angehaengten Effekt neu. Der Harvester ruft das 2,5 s nach einem erfolgreichen
Attach einmalig auf (`SETTLE_MS`). Kosten: eine Luecke von Millisekunden in der
Korrektur, einmal, direkt nach dem Anhaengen. Verifiziert: 3 von 3 Kaltstarts
`Enabled=y`.

Die Lebenslauf-Logs in `SessionAttachmentStrategy` und `DynamicsProcessingEqualizer`
bleiben drin — sie feuern einmal pro Attach und waren das einzige Mittel, das
diesen Fehler sichtbar gemacht hat.

## Gemessen: wer kuendigt seine Session an

Test mit **ausgeschaltetem Helfer**, damit die Ernte nicht greifen kann - ein
Effekt entsteht dann nur, wenn der Player sich selbst meldet.

| Player | Ankuendigung | Beleg |
|---|---|---|
| Spotify | **ja** | `Session opened: 8137 by com.spotify.music`, Effekt 4203 auf 08137, Enabled=y |
| YouTube Music | **ja** | `Session opened: 8921 by com.google.android.apps.youtube.music`, Effekt 4211, Enabled=y |
| Tidal | **nein** | spielte durchgehend, Empfaenger protokollierte nichts |

Damit ist die frueher aus einem Code-Kommentar uebernommene Behauptung
"Tidal broadcastet nicht" endlich belegt - und die stillschweigende Annahme,
andere Player seien genauso, widerlegt.

**Folge fuer eine Play-Store-Variante ohne Helfer:** fuer Spotify und YouTube
Music voll funktionsfaehig, auch ueber Bluetooth. Nur Tidal braucht den Helfer.
Das verschiebt den Helfer vom "notwendig fuer die Kernfunktion" zum "notwendig
fuer einen Player und fuer Codec-Steuerung".

## Helfer-Wiederverbindung: kein Fehler, eine schlechte Abwaegung

Ich hatte sie heute mehrfach als defekt notiert. Sie ist es nicht - beide
Verdaechtigen sind geprueft und entlastet:

- **Token-Rotation:** `PrivilegedBootstrap.match()` akzeptiert *beide* Token,
  den pending und den aktiven. Ein laufender Helfer wird nach einem
  App-Neustart also nicht deshalb abgewiesen, weil der Setup-Schirm inzwischen
  einen neuen Token gemuenzt hat.
- **Neuinstallation:** direkt getestet, `adb install -r` bei laufendem Helfer,
  Wiederverbindung in unter 10 s.

Was wirklich dahintersteckte: der Backoff. Der Helfer sucht die App mit
verdoppelnder Wartezeit, und die Obergrenze lag bei **60 s**. Mein
Fehlschlag-Test lief 72 s mit 12-s-Raster und konnte den Treffer knapp
verfehlt haben. Reproduzieren liess sich ein echter Ausfall nicht.

**Geaendert:** Obergrenze 60 s -> 15 s. Die Obergrenze entscheidet nur, wie
lange die *App* nach einem Neustart auf ihren Helfer wartet, und diese Wartezeit
ist nicht gratis - ohne Helfer erreicht der EQ Tidal nicht. Ein `/proc`-Scan
alle 15 s waehrend die App nicht laeuft ist dagegen belanglos: kein
Binder-Aufruf, kein Broadcast, kein App-Start.

Gemessen nach der Aenderung, mit absichtlich hochgelaufenem Backoff (App 45 s
tot): **verbunden nach ~8 s**.

# Helferstart ohne PC: Stand 22. August, 23:00

## Warum ueberhaupt

Der privilegierte Helfer stirbt bei jedem Neustart des Telefons und kann sich
nicht selbst starten - eine App darf keinen Prozess mit shell-Identitaet
erzeugen, das *ist* die Sandbox-Grenze. Ohne ihn fehlen zwei Dinge:
Codec-Steuerung und der EQ fuer Tidal (das seine Audio-Session nicht
ankuendigt - gemessen, siehe oben).

Der einzige Weg ohne Kabel: die App verbindet sich per drahtlosem Debugging mit
dem **eigenen** Geraet und fuehrt den Befehl selbst aus. Shizukus Verfahren.

## Was steht (alles committet, Tests gruen)

**Portsuche und TLS** - `cd993ec`. Am Geraet belegt:

    discovered 1 endpoint(s): 192.168.178.22:39099 (connecting via 127.0.0.1)
    auto-start outcome: NeedsPairing (SSLV3_ALERT_CERTIFICATE_UNKNOWN)

Die Kette laeuft bis zum Vertrauensentscheid: mDNS findet den Port, CNXN raus,
STLS zurueck, TLS-Handschlag, und adbd sagt "Zertifikat unbekannt" - er kennt
unseren Schluessel noch nicht. Genau so soll es vor der Kopplung aussehen.

**Sicherheit** - `4321cba`. Aus der mDNS-Ankuendigung wird **nur die
Portnummer** uebernommen; verbunden wird immer gegen 127.0.0.1. Kein Paket kann
das Geraet verlassen, auch nicht wenn etwas anderes im Netz sich als
adb-Daemon ausgibt. Zweite Sperre im TLS-Client, Identitaetspruefung davor.

**Ed25519-Arithmetik** - `0c2168d`. Von Hand, weil Android keine Punktarithmetik
anbietet. Gegen RFC 8032 geprueft, neun Tests.

**SPAKE2-Punkte M und N** - `6a5f4ba`. Nach BoringSSLs Verfahren abgeleitet,
gegen dessen eigene Tabelle verifiziert.

**SPAKE2 komplett** - `f65701d`. Sechs Tests.

## Was fehlt

1. **TLS mit PSK.** Der SPAKE2-Schluessel wird als Pre-Shared Key in den
   TLS-Handschlag der Kopplungsverbindung eingespeist. Machbarkeit geprueft
   (`d38c29c`): `com.android.org.conscrypt.PSKKeyManager` ist per Reflection
   erreichbar, TLS_ECDHE_PSK_* wird unterstuetzt. Reflection auf
   nicht-oeffentliche API - `AdbPairingCapability` meldet, ob das Geraet es
   kann, und muss vor dem Anbieten der Funktion gefragt werden.
2. **Kopplungsprotokoll.** Paketkopf (Version, Typ, Nutzlaenge),
   SPAKE2-Nachrichtenaustausch, danach ueber TLS die Uebertragung des
   oeffentlichen Schluessels im adb-Format, den adbd dann vertraut.
3. **Kommandoausfuehrung.** Nach erfolgreicher Kopplung: `OPEN shell:<befehl>`
   ueber die bestehende Verbindung, um den Helfer zu starten. AdbMessage kann
   die Rahmen bereits.
4. **UI.** Eingabefeld fuer den sechsstelligen Code hinter dem Activate-Knopf,
   plus das zugesagte Aufklaerungs-Popup zur INTERNET-Berechtigung.

## Fallen, die Zeit gekostet haben

- **Drahtloses Debugging laeuft nicht bei aktivem USB-Debugging.** 23 ms nach
  dem Einschalten kam jedesmal `setAdbEnabled(false), transportType=1`. Fuer
  die Funktion egal (Alltagsfall ist "kein PC"), fuers Testen heisst es: ueber
  WLAN arbeiten, nicht ueber Kabel.
- **Es braucht ein verbundenes WLAN.** Ohne Netz schaltet Android es ab.
- **mDNS liefert veraltete Ports.** adbd lauschte auf 35485, die Ankuendigung
  bot 34797. `findAll()` sammelt deshalb alle Kandidaten.
- **Der Port wechselt staendig** - innerhalb einer Stunde 34797, 35485, 39099.
  Nichts zwischenspeichern.

## Testaufbau wiederherstellen

Kabel ab, drahtloses Debugging an, dann am PC:

    adb pair <ip>:<kopplungsport> <code>
    adb mdns services            # aktuellen connect-Port ablesen
    adb connect <ip>:<port>

Danach den Probe ausloesen (der Knopf sitzt mittig):

    adb shell am start -n dev.dankyeeter.btdashboard/.MainActivity \
      --es dev.dankyeeter.btdashboard.OPEN_ROUTE activate
    adb shell input tap 672 1539
    adb logcat -d | grep -aiE "HelperAutoStart|AdbDiscovery"

## Offen aus frueheren Runden

- Play-Store: QUERY_ALL_PACKAGES entkoppeln, Data-Safety-Formular,
  FGS-Deklaration. Neu dazu: die INTERNET-Berechtigung ist jetzt drin und
  muss im Datenschutzabschnitt erklaert werden.
- Der globale EQ-Pfad ueber Bluetooth bleibt tot; Session-IDs erraten und
  hoehere Effekt-Prioritaet sind beide gemessen und verworfen.

---

# Kopplung funktioniert, Activate-Gate (23./24. August)

Stand am Ende der Sitzung. Alles auf Branch `backup/wip-20260822`, letzter Commit
`9d1574a`; die Arbeit dieser Sitzung ist **nicht committet** (siehe unten).
`master` ist unberührt. 930 Tests grün, App läuft.

---

## Das Ergebnis der Sitzung

**Die ADB-Kopplung funktioniert.** Das war wochenlang die Wand, und die Ursache
war weder in SPAKE2 noch in der Kryptografie:

> Das SPAKE2-Passwort ist nicht der sechsstellige Code, sondern
> `Code ‖ 64 Byte TLS-Exporter-Material` (RFC 5705). `adb` bindet den Austausch
> damit an genau diesen TLS-Kanal — `pairing_connection.cpp`, Zeilen 191–199.

Deshalb war jede Schicht einzeln korrekt und der Schlüssel trotzdem
verschieden. Kein Parametertest hätte das gefunden, weil der Fehler in keinem
Parameter lag. Gegenprüfung an der Originalquelle (BoringSSL `spake25519.c`,
AOSP `pairing_auth.cpp`, `aes_128_gcm.cpp`, `tls_connection.cpp`) hat außerdem
bestätigt: Rolle, Masken, Namensterminatoren, Skalar-Konvention,
Transkript-Reihenfolge, HKDF- und AES-Parameter waren **alle schon richtig**.

Zwei Details, die leicht wieder verloren gehen:

- Das Exporter-Label ist `adb-label` mit `sizeof(...)` = **10 Byte inklusive
  NUL**, nicht 9.
- Die Plattform-Conscrypt hat `exportKeyingMaterial`, gibt es aber nicht her:
  `domain=core-platform, api=blocked`, am Gerät dreimal gemessen, dreimal
  `denied`. Deshalb bringt die App Conscrypt jetzt selbst mit und **baut den
  Kopplungs-Socket mit diesem Provider** — sonst rückt der Exporter nichts
  heraus.

Der Helfer startet danach vom Telefon aus, ohne PC. Er starb anfangs 36 ms nach
dem Start, weil adbd die Prozessgruppe der Shell abräumte, während die VM noch
hochfuhr; `setsid` plus drei Sekunden Nachlauf lösen das.

---

## Offen: nur noch der Test

Alles Gebaute ist **ungetestet am Gerät** — bewusst, auf Daniels Ansage
("bau die fehlenden funktionen. der test passiert danach").

### Der Test, der die Verbindung kappt

Der Beweis der Automatik ist ein **Neustart**. Dabei geht die adb-Verbindung
verloren, weil sie über genau das Wireless Debugging läuft, das die App dann
selbst öffnet und wieder schließt. Danach muss es von Hand wieder eingeschaltet
werden, um das Gerät zu erreichen.

Reihenfolge, die am wenigsten kostet:

1. **Erst ohne Neustart**: App starten, Helfer kommt hoch, prüfen ob
   `WRITE_SECURE_SETTINGS` danach gewährt ist
   (`dumpsys package dev.dankyeeter.btdashboard | grep 'WRITE_SECURE_SETTINGS: granted'`).
   Das geht ohne Verbindungsverlust, weil Wireless Debugging von Hand an ist und
   die Abschalt-Logik nur schließt, was die App selbst geöffnet hat.
2. **Dann prüfen, dass nur ein Helfer startet** (`ps -Ao pid,args | grep btdash_privileged`)
   und im Log genau ein `privileged helper connected` steht.
3. **Erst dann der Neustart.**

### Woran es zuletzt lag, und was daraus wurde

Die Vergabe hing am Ende von `HelperAutoStart`. Ein Helfer erreichte die App
aber auf einem anderen Weg zuerst, worauf die Aktivierung über
`if (isConnected) true` aussteigt — die Vergabe wurde jedes Mal übersprungen,
während ringsum alles Erfolg meldete.

Sie hängt jetzt an der **Ankunft eines Helfers** statt an einem Startweg: ein
Collector auf `PrivilegedConnection.service` in `BtDashboardApplication`. Damit
sind alle Wege abgedeckt — Aktivierung, ein überlebender Helfer der sich nach
einem App-Neustart wieder meldet, ein Reconnect. In `HelperAutoStart` steht der
Aufruf zusätzlich, dort aber mit anderer Aufgabe: das Abschalten von Wireless
Debugging in der nächsten Zeile braucht die Berechtigung sofort, und der
Collector ist asynchron.

---

## Diese Sitzung fertig geworden

- **Harter Gate** — ohne Helfer zeigt die App nur den Activate-Knopf, auch wenn
  er im Betrieb stirbt. Der Setup-Wizard steht bewusst *davor*: dort wird die
  Benachrichtigungs-Berechtigung erteilt, ohne die die Kopplung den Code nicht
  entgegennehmen kann.
- **Prompts raus** — „EQ not attached", „EQ is off"-Status, der lange
  Bluetooth-Erklärtext. Von der App bleibt genau eine Meldung: die
  Foreground-Service-Notification, stumm, ohne Text, vor dem Sperrbildschirm
  verborgen. Android verlangt sie, sie lässt sich nicht entfernen.
- **Veralteter Boot-Text** — schickte für einen ADB-Befehl an den PC. Stimmt
  seit dieser Sitzung nicht mehr.
- **Schrittanleitung einmal definiert** (`ActivationSteps.kt`), von beiden
  Notifications und dem Activate-Screen benutzt. Zugeklappt eine Zeile,
  aufgeklappt vier Schritte inklusive Entwickleroptionen.
- **`pm grant` als eigene, als mutierend deklarierte Operation** — nicht als
  Whitelist-Eintrag. `exec` ist als nicht-mutierend klassifiziert; ein Befehl,
  der die Rechte der App dauerhaft erweitert, hätte dort die Lese-Tür benutzt.
  Paket und Berechtigung sind im Helfer festverdrahtet, nicht Parameter.
- **Aktivierung serialisiert** — ein prozessweiter `Mutex`. Zwei gleichzeitige
  Aufrufer starteten je einen Helfer; der zweite verwarf den ersten sofort.
- **Boot-Restore läuft einmal pro Prozess.** Der Filter listet `BOOT_COMPLETED`
  *und* `LOCKED_BOOT_COMPLETED`, und ein Neustart liefert beide aus — der
  Restore lief zweimal. Beide Aktionen bleiben im Filter (welche ankommt, hängt
  an der Direct-Boot-Fähigkeit des Receivers); die zweite wird ignoriert.
- **Kein WLAN wird erkannt und benannt** (`Outcome.NoWifi`). Vorher lief die
  Aktivierung ohne WLAN in eine Portsuche, die nichts finden konnte — ein
  langsamer, unerklärter Fehlschlag statt eines Einzeilers.

### Drei Fehler, die Ursachen waren, keine Symptome

1. **Verlorener Rückgabewert durch Thread-Sichtbarkeit.** `earlyVerdict` wurde
   in den Portsuche-Callbacks auf anderen Threads gesetzt und ohne
   Synchronisation gelesen — die Zuweisung ging verloren
   (`verdict=null endpoints=2` am Gerät). Erklärte auch den Doppelstart des
   Helfers. Jetzt `AtomicReference`, und genau ein Endpunkt darf ihn starten.
2. **`Started` hieß „Kommando abgesetzt", nicht „Helfer läuft".** Die App
   meldete Erfolg an einen Nutzer mit totem EQ. Sie wartet jetzt auf den Binder
   und nennt im Fehlerfall die Logdatei.
3. **Literale NUL-Bytes in Kotlin-Quellen** brechen jetzt den Build
   (`verifyNoNulBytes` in `build.gradle.kts`, verifiziert). Sie kompilieren
   sonst klaglos und machen Dateien für git und grep binär.

Außerdem: Die „EQ is off"-Notification blieb nach erfolgreicher Aktivierung
stehen, wenn man über die App statt über die Notification aktivierte. Sie
verschwindet jetzt, sobald der Helfer sich meldet.

---

## Backlog

- **Play Store** — Data-Safety-Formular, `QUERY_ALL_PACKAGES` entkoppeln,
  FGS-Typen deklarieren, INTERNET begründen.
  *Risiko bleibt bestehen:* Shizuku, das technisch fast identische Vorbild, wird
  nicht über Google Play vertrieben. Entscheidung ist gefallen: wir versuchen
  es, im Zweifel GitHub.
- ~~ABI-Split~~ — **nicht nötig.** Ein App Bundle liefert die native Bibliothek
  pro Gerät aus, und für die GitHub-Variante ist ein universelles APK gewollt.
- **Commit-Entscheidung** — die ganze Sitzung liegt uncommittet auf
  `backup/wip-20260822`.

---

## Gerätezustand

- Pixel 8 Pro, verbunden über `192.168.178.22:44803`.
- `adb` liegt unter `ClaudeCode/android-sdk/platform-tools`, **nicht** im PATH.
- Wireless Debugging ist **von Hand an** — deshalb greift die Abschalt-Logik
  derzeit nicht (sie schließt nur, was die App selbst geöffnet hat).
- `WRITE_SECURE_SETTINGS` ist **entzogen** (absichtlich, für den Test).
- Helfer-Ausgabe: `/data/local/tmp/btdash_helper.log`.
- USB-Kabel muss abgesteckt bleiben: es schaltet Wireless Debugging binnen
  Millisekunden ab.
- Bei langen Wartezeiten sperrt sich der Bildschirm — Tipp-Befehle laufen dann
  ins Leere, ohne zu scheitern.

---

## Nach zwei echten Neustarts (24. August, abends)

**Bewiesen:** Die automatische Aktivierung läuft. Nach einem Neustart steht der
Helfer ohne jedes Zutun (`BtDashBoot: automatic activation after boot: true`,
Helfer-PID vorhanden, Berechtigung gehalten, kein Kopplungscode). Ebenfalls
bestätigt: der Helfer überlebt ein abgeschaltetes Wireless Debugging, und der
Boot-Restore läuft nur noch einmal.

**Nachgereicht und am Gerät bewiesen:** Wireless Debugging schließt sich jetzt
von selbst. Der Auslöser ist nicht mehr das Ende der Aktivierung - eine Stelle,
die oft gar nicht erreicht wird - sondern das Ereignis "ein Helfer ist
verbunden", genau wie bei der Berechtigungsvergabe. Damit ist gleichgültig, wer
den Helfer gestartet hat, und der unten beschriebene unbekannte Startpfad
blockiert nichts mehr.

Der Beweis war der Test selbst: nach dem App-Start meldete adb `device offline`
und das Gerät verschwand aus `adb devices`. Genau das soll passieren.

**Weiterhin offen, jetzt aber ohne Folgen:** der unbekannte Pfad, der den
Helfer vor der Aktivierung startet. Er kostet einen VM-Start pro Aktivierung.

Die Messung sagt genau, wo es hängt, aber nicht warum:

- Im Log steht **keine einzige** `HelperAutoStart`-Zeile - weder die
  Grant-Meldung noch die eigens dafür eingebaute
  `wireless debugging closed after activation`.
- `automatic activation after boot: true` stammt folglich aus der Abkürzung
  `if (PrivilegedConnection.isConnected) true` in `BtDashboardApplication`.
- Es war also **schon ein Helfer verbunden**, bevor die Aktivierung lief.

Der lange als harmlos abgetane Doppelstart ist damit die Blockade: irgendein
Pfad startet den Helfer vor der Aktivierung, die Aktivierung steigt sofort aus,
und alles was hinter ihr liegt - Vergabe *und* Schließen - wird übersprungen.

**Nächster Schritt:** diesen Pfad finden. `AdbPortDiscovery` und
`deviceShellCommand()` haben laut Suche nur `HelperAutoStart` als Aufrufer, das
passt also nicht zusammen und eine der beiden Annahmen ist falsch. Ein Log
gleich zu Beginn von `attempt()` und eines in `PrivilegedProvider.accept()`
(mit `Thread.currentThread().stackTrace`) beantwortet es in einem Durchlauf.

Achtung beim Nachstellen: ein Boot flutet logcat, und die frühen Zeilen sind
nach wenigen Minuten weg. Entweder sofort auslesen oder `logcat -G 16M` setzen.

---

## Zweites Gerät: Pixel 11 Pro, Android 17 (24. August, abends)

Erstinstallation auf einem Telefon, das die App nie gesehen hat. Zwei Funde,
beide auf dem Pixel 8 prinzipiell unsichtbar.

### 1. Nicht 16-KB-kompatibel  (Play-Store-Hindernis)

Android 17 meldet beim Start: *"This app isn't 16 KB-compatible. ELF alignment
check failed."* Neuere Pixel laufen mit 16-KB-Speicherseiten; Google Play
verlangt ausgerichtete native Bibliotheken. Betroffen:

- `libbtdashboard_audio.so` - unser eigener NDK-Code
- `libconscrypt_jni.so` - **heute eingebaut**, Conscrypt 2.5.2 stammt von 2021
- `liboboe.so`, `libc++_shared.so`, `libandroidx.graphics.path.so`,
  `libdatastore_shared_counter.so`

Richtung: eigene Bibliothek über `-Wl,-z,max-page-size=16384` bzw. neueres NDK;
für Conscrypt und AndroidX aktuellere Versionen suchen. **Falls es keine
ausgerichtete Conscrypt gibt, steht die Entscheidung Play Store gegen
TLS-Exporter erneut an** - und damit die ganze Kopplung ohne PC.

### 2. Der Setup-Wizard wird auf einem frischen Gerät übersprungen

Die App zeigt sofort den Activate-Gate. Der Wizard erteilt aber die
Laufzeitberechtigungen, **darunter die für Benachrichtigungen** - und ohne die
kann der Kopplungscode nicht entgegengenommen werden, weil er in einer
Notification eingetippt wird. Der Gate war ausdrücklich so gebaut, dass der
Wizard davor liegt.

Verdacht: in `BtDashboardApp` wird `wizardCompleted` mit `initialValue = true`
gesammelt. Der Gate gewinnt damit sofort, und der NavHost, der zum Wizard
navigieren würde, wird nie gerendert. Zu prüfen, ob sich das nach der ersten
Emission korrigiert - im Test tat es das nicht.

Beides gehört vor den Doppelstart. Fund 1 betrifft eine Entscheidung von heute,
Fund 2 verhindert die Erstinbetriebnahme auf jedem neuen Gerät.

---

## Naechster Umbau: ein Zustand statt drei  (Daniels Entwurf, 24. August)

Beschlossen, noch nicht gebaut. Der Zustand der App ergibt sich kuenftig aus
**drei Faellen, live berechnet** - nicht aus einem gespeicherten Haekchen:

1. **Voller Setup-Prozess**, wenn irgendein Pflichtschritt gerade nicht erfuellt
   ist. Vier Schritte statt fuenf: Bluetooth, Mikrofon, Benachrichtigungen, und
   als letzter **Kopplung und Helfer in einem**.
2. **Nur der Activate-Knopf**, wenn die Berechtigungen sitzen und nur der Helfer
   fehlt.
3. **Gar nichts**, wenn alles steht - dann startet die App normal.

### Warum kein gespeichertes Flag

Android entzieht Berechtigungen ungenutzter Apps von selbst, und der Nutzer kann
Benachrichtigungen jederzeit abschalten. Ein gespeichertes "Setup erledigt"
waere dann falsch, waehrend genau die Berechtigung fehlt, in die der
Kopplungscode getippt wird - die App waere still kaputt. Die Live-Rechnung gibt
es bereits in `AndroidSetupEnvironment`; sie muss nur die Eintrittsbedingung
werden statt nur die Anzeige.

Sobald alles erfuellt ist, ist das Setup **nirgends** zu sehen und lebt als
Eintrag in den Einstellungen weiter. Daniels Wunsch, woertlich: "ich muss die
setup schritte nirgends sehen sobald die rechte erteilt sind."

### Shell-Zugriff und WRITE_SECURE_SETTINGS werden ein Schritt

Sie sind physisch eine Handlung: koppeln, Helfer startet, Helfer erteilt die
Berechtigung. Die Trennung war ein Ueberbleibsel aus der Zeit, als beides je
einen ADB-Befehl vom Rechner brauchte.

### Kein Migrationsaufwand

Daniel deinstalliert die App auf dem Pixel 8 vor dem naechsten Test. Gespeicherte
Schritt-Bezeichner aus der alten Fassung muessen also **nicht** beruecksichtigt
werden. Anspruch: eine Fassung, die auf **Android 16 und 17** frisch installiert
funktioniert - beide Geraete stehen dafuer zur Verfuegung.

### Nicht vergessen

Nach dem Erst-Setup braucht ein Neustart **nichts**: die Aktivierung laeuft von
selbst, bewiesen am 24. August. Der Activate-Knopf ist der Rueckfall, nicht der
Normalweg - die Oberflaeche sollte ihn entsprechend selten zeigen.

---

## SDK 36 und ein Zustand statt drei  (24. August, spaet)

Beides gebaut. **Nichts davon ist am Geraet geprueft** - kein Telefon war
angesteckt.

### Ein Build fuer beide Telefone, kein zweiter Zweig

`compileSdk`/`targetSdk` 35 -> 36, `minSdk` bleibt 31. Das ist keine
Geraetevariante: ein APK laeuft weiter von Android 12 bis 17, und wo sich die
Versionen wirklich unterscheiden, entscheidet `Build.VERSION.SDK_INT` zur
Laufzeit. AGP 8.7.3 -> 8.9.3, Gradle 8.10.2 -> 8.11.1, Build-Tools 36.0.0
nachinstalliert - die kleinste Kombination, die API 36 baut. Kotlin, KSP und
alle Bibliotheken unveraendert.

Durchgesehen, was `targetSdk 36` an Verhalten mitbringt, weil es dann auf
*beiden* Geraeten gilt:

- Predictive Back war schon deklariert.
- Erzwungenes Edge-to-Edge greift, weil `enableEdgeToEdge()` ohnehin gerufen
  wird - aber **Wizard und Activate-Gate rendern vor dem Scaffold** und hatten
  deshalb gar keine Insets. Der Wizard-Titel lag unter der Statusleiste, der
  Weiter-Knopf unter der Gestenleiste, und das Feld fuer den Kopplungscode
  konnte hinter der Tastatur verschwinden. Beide ziehen jetzt `safeDrawing`;
  `systemBars` haette die Tastatur nicht abgedeckt.

Die 16-KB-Ausrichtung wurde nach dem Toolchain-Wechsel neu gemessen: alle
64-Bit-Bibliotheken weiterhin 16 KB. Das eine 4-KB-`libc++_shared` liegt in
`armeabi-v7a`, wo es keine 16-KB-Seiten gibt.

Noch offen aus `ANDROID17_READINESS.md`: der Reflection-Audit-Test gegen die
neue compileSdk, der FGS-Fehlerpfad, die `resolveContentProvider`-Deprecation.
Alle drei sind Absicherung, keine Blockade.

### Der Zustand ergibt sich jetzt, statt gespeichert zu sein

`SetupStatus.phase()` beantwortet live, welches Gesicht die App zeigt:
`FULL_SETUP`, `ACTIVATION_ONLY`, `READY`. Es gibt nichts zu laden und nichts
abzuwarten - eine Berechtigung ist eine synchrone Frage an das System, und der
Helfer ist ein `StateFlow`, der die Antwort schon hat.

**Damit ist der Fehler vom Pixel 11 strukturell weg.** Er entstand, weil das
gespeicherte `wizardCompleted` geraten werden musste, solange es noch gelesen
wurde. Es gibt jetzt keinen Wert mehr, den man raten koennte: `wizardCompleted`
ist aus `SetupStore` entfernt.

Fuenf Schritte sind vier. `SHELL_ACCESS` und `SECURE_SETTINGS` sind ein
Schritt - koppeln, Helfer startet, Helfer erteilt die Berechtigung ist *eine*
Handlung. Der Schritt gilt als erledigt, sobald ein Helfer haengt; ob
`WRITE_SECURE_SETTINGS` durchkam, steht als eigene Zeile daneben. Beides zur
Bedingung zu machen haette den Schritt in den Millisekunden dazwischen
zurueckspringen lassen - und genau dann wirft das Gate den Nutzer aus einer App,
die laeuft.

**Benachrichtigungen sind jetzt Pflicht.** Der Kopplungscode wird in eine
Notification getippt, also ist der letzte Schritt ohne sie nicht zu Ende zu
bringen. Nur das Mikrofon ist noch ueberspringbar; "Skip anyway" bei den
anderen war ein leeres Angebot.

Zwei Feinheiten, die im Betrieb sonst weh tun:

- Ist der Prozess einmal offen, bleibt er offen, bis der Nutzer fertig ist -
  sonst reisst die zuletzt erteilte Pflichtberechtigung den Bildschirm mitten im
  Ablauf weg, und der optionale Schritt dahinter wird nie gefragt.
- Sobald alles steht, schliesst er sich von selbst. Daniels Regel: die
  Setup-Schritte sind nirgends zu sehen, sobald die Rechte erteilt sind.

Die Aktivierung existiert einmal (`ActivateActions`) und wird an zwei Stellen
gezeigt: als ganzer Bildschirm im Gate und im letzten Schritt des Prozesses.

571 Tests gruen, darunter sieben neue fuer die drei Faelle - einer davon fuer
den Fall, den Android selbst herstellt: eine entzogene Berechtigung oeffnet den
Prozess wieder, auch wenn der Helfer laeuft.

### Was am Geraet zu pruefen ist

1. **Pixel 11 Pro, frische Installation**: kommt der Setup-Prozess, und liegt
   nichts mehr unter Status- oder Gestenleiste?
2. **Pixel 8 Pro, nach Deinstallation**: derselbe Durchlauf auf Android 16.
3. **Nach einem Neustart**: nur der Activate-Knopf - und nach der bewiesenen
   Automatik im Normalfall gar nichts.
4. Weiterhin offen und unberuehrt: der unbekannte Pfad, der den Helfer vor der
   Aktivierung startet.

---

## Shizuku ist auch namentlich weg  (24. August, spaet)

Der letzte Rest war Text. Das Paket `system.shizuku` heisst jetzt
`system.secure` - es enthielt immer nur `SecureSettingsGate` und
`SecureSettingsState`, die mit Shizuku nie etwas zu tun hatten; die Datei sagte
das selbst und bat um eine Umbenennung im Ganzen statt stueckweise. Dazu die
toten ProGuard-Regeln fuer `rikka.shizuku.**`.

**Zwei davon waren keine Kommentare, sondern Fehler:** zwei Meldungen fuer den
Nutzer nannten Shizuku als Ursache - *"Shizuku is not ready - other equalizers
cannot be detected"* und *"no shell identity - Shizuku not ready"*. Sie
schickten jemanden los, eine App zu installieren, die dieses Projekt nicht mehr
benutzt. Jetzt heisst es dort "the helper".

Ebenfalls berichtigt, weil es beim Lesen auffiel: der Manifest-Kommentar in
`core-system` behauptete weiterhin *"Deliberately NO
android.permission.INTERNET"*. Die Berechtigung ist seit dem 23. August drin,
fuer die Loopback-Verbindung zum Debugging-Dienst. Der Kommentar sagt jetzt,
warum sie existiert.

**Absichtlich stehen geblieben** sind 15 Nennungen: Shizuku als *Vorbild*
(`PrivilegedServer` - dieselbe Mechanik, `app_process` unter uid 2000, die
Provider-Adressierung) und als ausdrueckliche Historie (`GlobalAttachmentStrategy`,
`ShellRunner`, `SystemAccessScreen`, `BootReceiver`). Das ist Wissen, kein Rest -
wer die Blockliste oder den Helferstart nachvollziehen will, braucht genau diese
Verweise.

571 Tests gruen. Am Geraet aendert sich nichts davon ausser den zwei Meldungen.

---

## Geraetetest Pixel 11 Pro, Android 17  (24. August, 22:30-23:00)

Frische Installation, vorher deinstalliert. Android 17, SDK 37, gebootet mit
4-KB-Seiten (`getconf PAGE_SIZE` = 4096) - die Warnung von heute Mittag kam also
aus der Ausrichtungspruefung selbst, nicht aus dem Seitenmodus des Geraets.

### Bewiesen

- **Der Setup-Prozess kommt.** "Step 1 of 4", vier Schritte, `Pairing and
  helper` als einer. Der Fehler vom Nachmittag - frische Installation landet
  sofort im Gate - ist weg.
- **Keine 16-KB-Warnung**, weder als Dialog noch im Log.
- **Insets stimmen**: nichts liegt unter Status- oder Gestenleiste.
- **Live-Pruefung greift**: jede erteilte Berechtigung schaltet den Schritt
  sofort auf `Done`, ohne Neustart.
- **Nur das Mikrofon zeigt "Skip"**, Benachrichtigungen nicht.
- **Die Klinke haelt**: als mit der Benachrichtigungs-Berechtigung die letzte
  Pflicht erfuellt war und die Phase auf `ACTIVATION_ONLY` sprang, blieb der
  Prozess offen statt zu verschwinden.
- **Schritt 4 zeigt beide Zeilen getrennt**: `App helper: Not running` und
  `Secure settings: Not granted`, darunter derselbe Activate-Knopf wie im Gate.

### Gefunden und behoben: schwarze Schrift auf dunklem Grund

Die Ueberschrift "Setup" war pures Schwarz (0,0,0) auf #1f1f1f. Ursache ist
nicht der Text: **`BtDashboardTheme` setzt nur `MaterialTheme`, kein `Surface`**.
Die Inhaltsfarbe kommt sonst ueberall vom Scaffold - und die beiden
Gate-Bildschirme rendern davor, wo Composes Voreinstellung Schwarz ist. Das
betraf den Activate-Bildschirm, seit es ihn gibt; dort fiel es nur kaum auf,
weil seine Knoepfe ihre Farbe selbst mitbringen. Behoben mit `GateSurface`,
am Geraet nachgeprueft.

### Kein Fehler: der Rücksprung auf Schritt 1

Sah nach einem Zustandsverlust aus - kein Prozesstod, kein Neustart der
Activity, gleiche PID. Daniel hatte in der Schrittliste auf "Bluetooth access"
getippt, und genau das tut die Zeile.

### Offen: die Kopplung scheiterte, und die App liess ihn allein

Wireless Debugging war aus, also `Outcome.NoService`. Die Meldung war korrekt
("Turn on Wireless debugging in Developer options"), aber sie **beschrieb ein
Ziel, das die App selbst haette oeffnen koennen**. Daniels Einwand woertlich:
"das muss aber teil vom activate sein. oder mich zumindest da hin leiten."

Gebaut, **noch nicht am Geraet geprueft**:

- `ActivateState.Failed` traegt jetzt ein `ActivateFix` - eine Handlung, keine
  Beschreibung. `WIRELESS_DEBUGGING` oeffnet die Entwickleroptionen mit dem
  Eintrag namentlich angefragt, `WIFI` die WLAN-Einstellungen.
- Der Fehlerbildschirm zeigt diesen Knopf **vor** "Try again": der naechste
  Versuch kann nichts ausrichten, bevor der Schalter umgelegt ist.
- Im letzten Setup-Schritt steht die Anleitung von Anfang an offen. Dort ist es
  per Definition das erste Mal; im Gate bleibt sie zugeklappt, weil jede
  spaetere Aktivierung ein Tipp ist.

### Was der naechste Durchlauf zeigen muss

1. Activate ohne Wireless Debugging -> Knopf erscheint, fuehrt in die
   Entwickleroptionen.
2. Wireless Debugging an -> Activate -> Kopplungs-Notification kommt.
3. Code eintippen -> Helfer laeuft, `Secure settings: Granted`, Setup
   verschwindet von selbst.
4. Danach: laeuft genau **ein** Helfer? Der unbekannte Startpfad ist weiterhin
   offen.

---

## Android 17: die Kette steht  (25. August)

Am Pixel 11 Pro durchgespielt, mit echter Kopplung.

**Bewiesen:**

- Der neue Fehlerweg fuehrt: Activate ohne Wireless Debugging nennt die Ursache
  und zeigt den Knopf, der in die Entwickleroptionen springt - mit dem Eintrag
  *hervorgehoben*, die Highlight-Anfrage greift auf 17.
- Das Gate zeigt im Fall "nur der Helfer fehlt" den Activate-Knopf allein.
- **Kopplung, Helfer und Vergabe laufen auf Android 17.** Nach dem Eintippen des
  Codes: genau ein Helfer (`uid shell`), `WRITE_SECURE_SETTINGS: granted=true`,
  `adb_wifi_enabled=0` - die App hat das drahtlose Debugging selbst wieder
  geschlossen - und das Setup war von allein verschwunden.
- Der Diensttyp ist unveraendert `_adb-tls-connect._tcp`. ADB Wi-Fi 2.0 hat die
  Ankuendigung also nicht umbenannt; die Sorge aus `ANDROID17_READINESS.md` ist
  fuer die Erkennung ausgeraeumt.

**Eine Sackgasse, die keine war:** Der erste Versuch scheiterte an `adb tcpip
5555`. In diesem Legacy-Modus kuendigt adbd nur `_adb._tcp` an und laesst den
TLS-Dienst weg - die App konnte nichts finden. Messaufbau, nicht App. Wer die
Kopplung am Geraet beobachten will, kann adb nicht ueber tcpip halten; beides
schliesst sich aus.

**Offen geblieben:** Die Kopplungs-Benachrichtigung bleibt nach dem Erfolg
stehen. Das Log sagt `pairing outcome: Started`, die Ruecknahme wird also
aufgerufen, und die Benachrichtigung traegt trotzdem noch
`LIFETIME_EXTENDED_BY_DIRECT_REPLY`. Naechster Schritt: nicht am Erfolgspfad
nachbessern, sondern am Ereignis "ein Helfer ist verbunden" aufhaengen - dort,
wo Vergabe und Schliessen schon haengen.

Der Doppelstart existiert auch auf 17 (`retiring stale helper pid 23438`),
heilt sich aber selbst.

---

## Alte Android-Versionen: zwei echte Fallen  (25. August)

Auf Daniels Ansage geprueft, dass nichts an einer Version klebt. Zwei Funde,
beide auf 16 und 17 unsichtbar:

1. **Benachrichtigungen auf Android 12.** `POST_NOTIFICATIONS` gibt es erst ab
   13; darunter antwortet die Plattform auf den unbekannten String mit
   "verweigert". Seit der Schritt Pflicht ist, haette das Android 12 dauerhaft
   im Setup eingesperrt - `minSdk` ist 31. Gefragt wird jetzt
   `areNotificationsEnabled()`: dieselbe Antwort ab 13, richtig darunter, und
   sie merkt zusaetzlich, wenn der Nutzer Benachrichtigungen spaeter abschaltet.
   Unter 13 fuehrt der Schritt in die Benachrichtigungs-Einstellungen der App
   statt einen Dialog anzubieten, den es dort nicht gibt.
2. **`BigInteger.TWO` in `Ed25519.kt`**, fuenfmal. Java 9, auf Android erst ab
   13 - darunter ein `NoSuchFieldError` mitten in der Kopplungs-Kryptografie,
   also ein Absturz genau dann, wenn der Nutzer einen Code in der Hand haelt,
   den er nicht wiederbekommt.

Beides fand `lintDebug`, nicht das Auge. Von acht Fehlern bleiben drei, und die
sind `RestrictedApi` in `MainActivity` - kein Versionsthema. Der Rest des Codes
verzweigt nur auf Stufen unterhalb von 16 und ist damit unauffaellig.

---

## Android 17 traegt die ganze Funktion  (25. August, spaet)

Am Pixel 11 Pro mit Focal Bathys nachgewiesen, nach einem echten Neustart:

- **Automatische Aktivierung nach dem Boot laeuft auch auf 17.** Uptime zwei
  Minuten, Helfer mit frischer PID, `WRITE_SECURE_SETTINGS` gehalten,
  `adb_wifi_enabled=0`. Im Log: `automatic activation after boot: true`,
  danach `wireless debugging closed: true`. Die App oeffnet das drahtlose
  Debugging also selbst, startet den Helfer und schliesst es wieder - ohne
  Zutun.
- **Codec-Lesen ueber den Helfer**: aptX, 48 kHz, 16 bit, Active.
- **Greylist-Reflection**: `BluetoothA2dp.getActiveDevice()` ist
  `domain=platform, api=unsupported` und wird **erlaubt**.
- **EQ**: haengt im Session-Modus an Tidal, nachgewiesen im Audio-Flinger
  (`DynamicsProcessing` auf der Session des Players).

### Der Session-Modus ist hier kein Rueckfall

`globalAttachReachesOutput()` ist `!routesToBluetooth && !spatializerEngaged`.
Mit Bluetooth-Kopfhoerern ist der Session-Modus also der *vorgesehene* Weg, auf
jeder Android-Version; der globale Angriff auf den Output-Mix ist fuer die
Lautsprecher-Ausgabe da. Kein 17-Bruch, entgegen dem ersten Eindruck.

Nebenbei gemessen und einer alten Annahme widersprochen: **Tidal meldet seine
Audio-Session auf Android 17 an.** Auf 16 tat es das nicht - daher der ganze
globale Pfad. Die Texte behaupten das jetzt nicht mehr als Regel.

### Was noch fehlt, bevor das Pixel 8 weg kann

1. **Codec-Umschaltung** - der Schreibweg ueber den privaten
   `BluetoothAdapter`-Konstruktor. Lesen ist bewiesen, Schreiben nicht.
2. Die haengende Kopplungs-Benachrichtigung.

---

## Nachtschicht 26. August: Feature-Welle und Design-Durchgang

Elf Commits. Der volle Bericht mit Befunden, Focal/Mimi-Erklaerung und offenen
Fragen steht in REPORT-2026-08-26.md -- er ist die Referenz, hier nur die Karte:

- Hoertest: Boden -85 -> -90 dBFS; Audiogramm als Abweichungs-Ansicht (0 in
  der Mitte, adaptiver Massstab); Laeufe geraetegebunden (DeviceKey + Name,
  fremde ausgegraut); Laeufe speichern jetzt das Kalibrierprofil des
  verbundenen Geraets (vorher hart generisch -- die Geraetekalibrierung kam
  nie an).
- EQ: Presets als Dropdown mit "Add new EQ" (flach starten, formen, "Save
  changes to ..."); manuelle Presets geraeteuebergreifend, Personal Reference
  geraetegebunden.
- Design-Durchgang ueber jede Oberflaeche (zwei Opus-Audits, fuenf Worker):
  erste Ebene kurz, Erklaerungen hinter ?-Knoepfen, Sackgassen bekamen
  Knoepfe, Namen statt Zahlen, ADB-Erzaehlung als Fallback degradiert,
  falsches "No INTERNET permission"-Versprechen korrigiert, helpExpanded-Bug
  und BLOCKED-Totcode raus.
- 580 Tests gruen. Neue Fassung aufs Pixel 11 installiert, aber noch von
  niemandem gesehen -- der erste Blick steht aus. Der Helfer ist durch die
  Installation tot und aktiviert sich nach dem Abstecken selbst.

Fuer Daniels Morgen: REPORT Teil 3 (Lautheits-Restauration bauen? Test bei
niedrigerer Lautstaerke? Preset-Rename? HCI-Snoop ausschalten).

---

## Handover 26. August, Abend — Pause mitten in der letzten Bauwelle

Stand: Branch backup/wip-20260822, 91 Commits, letzter 2423f9f. 688 Tests
gruen. **Auf dem Pixel 11 installiert ist noch der Stand von Commit 8c59522**
(QA-Runde) — die grosse Welle danach (klinischer Anker, BT-Settings,
Live-Link-Datenschicht, NAL-R-Fix) ist committet, aber NICHT installiert.

### Nachtrag beim tatsaechlichen Pausieren: alle drei Worker sind gelandet

Die unten beschriebenen Worker sind fertig, integriert und committet
(748 Tests gruen, APK gebaut). Der Wiederaufnahme-Schritt 1/2 entfaellt —
es bleibt: **installieren + Kabel ziehen** (Helfer v4), dann Task 15/16.

**Zentraler Befund der ABR-Inferenz** (aus dem echten Dump der Morgen-
Session): 4.693.895 Frames / 389.197 Pakete = 12,06 Frames/Paket, konstant
ueber ~104 Minuten. Bei 96 kHz passt das nur zu 330 kbps — der Link lief
die GANZE Session auf LDACs Boden, nie 660/990, dazu 788 Underflows.
Daniels angekuendigte Konsequenz ("wenn es durchgehend niedrig ist…")
steht an; das Panel macht es ab jetzt live sichtbar.

Drei offene Entscheidungen des Daten-Workers: (1) VERBOSE-Logging-Probe
nicht gelaufen (setprop = Systemzustand, braucht Daniels Ok; Rezept steht
im Task-Output); (2) Pin-Kalibrierung nur in-memory — Room-Entity wuerde
wegen fallbackToDestructiveMigration die Monitor-Historie loeschen, braucht
bewusste Migration; (3) echter Bug: Codec-Typ 7 ist auf diesem Telefon
LHDCv5, aptxAdaptiveVendorIds beansprucht ihn aber — app-weit falsches
Label, im Live-Paket umgangen und als Task-Chip abgelegt.

### Drei Worker liefen beim Pausieren noch (inzwischen gelandet)

1. **Live-Monitoring-UI**: neues "Live link"-Panel auf dem Monitoring-Tab
   gegen MonitorGraph.liveLink* (Kontrakt in core-monitor/link/live/
   LinkLiveModels.kt — Ehrlichkeitsregeln dort sind bindend), inkl.
   LDAC-Pin-Chips als Live-Tuning.
2. **Kalibrier-Transfer-Verdrahtung**: Store + "Derive headphone calibration"-
   Knopf + Preset-Injektion ("Measured — your <device>") + Auto-Adoption.
   Mathe-Kern existiert und ist getestet (CalibrationTransfer.kt).
3. **Adaptive-Bitrate-Inferenz, alle Codecs** (Folgeauftrag an den
   Datenschicht-Worker; Scope per Daniels letzter Nachricht erweitert: NICHT
   nur LDAC, sondern jeder Codec mit einstellbarer/adaptiver Bitrate).
   Prinzip: Paketrate als Modus-Signatur — feste Rahmendauer, ratenabhaengige
   Rahmengroesse, also ist Pakete/s pro Modus unterscheidbar; Kalibrierung
   durch kurzes Pinnen je Modus, gespeichert je (Geraet, Codec, Modus).
   Codec-agnostische Struktur mit LDAC als erstem Provider; LHDCv5/aptX-
   Adaptive als Stubs (UNKNOWN mit Grund, keine geratenen Konstanten);
   offgeloadete Codecs ehrlich als "host cannot observe" ausgewiesen. Ziel:
   Daniels "wann wechselt ABR, bleibt es niedrig" beobachtbar machen, mit
   Konfidenz-Stufen statt Raterei.

### Wiederaufnahme (in dieser Reihenfolge)

1. `git status` — die Worker hinterlassen uncommittete Aenderungen; ihre
   Berichte stehen als Task-Notifications im Sitzungsverlauf bzw. in den
   tasks/*.output-Dateien.
2. Volle Suite (`gradlew --no-daemon testDebugUnitTest :app:assembleDebug`),
   Integration pruefen (bekannte Naht: EqScreen/CompensationSection zwischen
   Transfer-Worker und bestehendem Source-Switch), committen.
3. **Installieren + Kabel ziehen**: Helfer-Protokoll ist auf v4 (HD-Audio,
   BT-Neustart) — der alte Helfer stirbt bei der Installation, die App
   re-aktiviert sich selbst nach dem Abstecken; erst danach sind die neuen
   HD-Audio-Aufrufe live verifizierbar (bisher nur Kontrakt-Ebene).
4. Danach laut Daniels Plan: **Fable testet die App, Worker fixen** (Task 15),
   dann synthetische Test-Iteration (Task 16). Offene Backlog-Tasks: 18
   (Drift-Tracking), 19 (ISO-7029-Altersprior), 20 (ISO-226-Tilt).

### Die wichtigsten Erkenntnisse des Tages (Details: REPORT-2026-08-26.md)

- **NAL-R bekam dBFS statt dB HL** — die Adjusted Reference war fuer jede
  realistische Messung flach. Gefixt (asRelativeLossHl), vier
  Regressionstests. Das war der Grund fuer "+0,0 ueberall", nicht nur der
  Testboden.
- **Daniels Klinikbefund**: flach 10 dB HL beidseitig = normal, nichts zu
  korrigieren. Noble-Kurve widerspricht der Klinik in der Form
  (Tiefen-Artefakt durch Leckage/Rauschen, Hoehen echt gut). Der ganze
  "Personalisierungs"-Komplex: Mimi misst nur noch Ruhehoerschwellen,
  Praeferenz in Mimis eigener Studie unabhaengig vom Hoerverlust; Noble =
  Audiodo, deren ISO-226-Patent der einzige saubere Mechanismus fuer
  Normalhoerende ist. Unsere ehrliche Antwort darauf: Loudness restoration
  (gebaut) + klinischer Anker (gebaut) + Schwellen-Assay (REPORT 4.5).
- **Live-Link**: A2DP-Quellpfad hat echte Underflow-Zaehler (788 in der
  Morgen-Session — Daniels unsichtbares Problem), LDAC-ABR-Rate ist nativ
  nicht observierbar, daher die Paketraten-Inferenz.
- Merke: parallele Gradle-Builds im selben Repo kollidieren (Locks, korrupte
  Inkremental-Caches); Workern das Kompilieren nur einzeln erlauben.

---

## Handover 27. August, Abend — Fable testet am Geraet, und die ABR-Frage ist geknackt

Stand: Commits bis f435a89 plus ein uncommitteter ICU-Regex-Fix (dieser
Commit). 808 Tests gruen. **Installiert auf dem Pixel 11: der finale
Stand dieses Abends**, Helper v4 aktiv (Self-Activation dreimal
durchlaufen — sie traegt).

### DIE Erkenntnis des Abends: ABR ist direkt ablesbar, keine Inferenz noetig

`dumpsys bluetooth_manager` hat eine Sektion **"A2DP LDAC State"**, die
wortwoertlich druckt:

    LDAC quality mode                : ABR | LOW | ...
    LDAC transmission bitrate (Kbps) : 492
    Effective MTU: 883

Live gemessen (Noble, 96 kHz/32 bit, sauberer Link, Musik laeuft):

- **ABR pendelt zwischen 492 und 660 kbps und erreicht 990 NIE** — auch
  nicht unter Idealbedingungen neben dem Handy. ABR nutzt Zwischenstufen
  (492!), nicht nur 330/660/990.
- **Pinnen funktioniert Ende-zu-Ende**: 990-Chip -> mCodecSpecific1:1000,
  Panel liest "990 kbps (pinned)" zurueck; 330 -> 1002; ABR -> 1003.
  Daniels Konsequenz kann er jetzt selbst ziehen: Wer 990 will, pinnt 990
  — ABR liefert es freiwillig nicht.
- Die Renegotiation kostet hoerbar kurz Audio; die Verlust-Zeile weist
  sie ehrlich aus (25 dropped packets beim Umschalten).

### Genauso wichtig: die Paketraten-Inferenz ist FALSIFIZIERT

Empirisch am Geraet: Der enqueue-Zaehler tickt **konstant ~50/s** in
jedem Modus (er zaehlt Timer-Ticks, nicht Funkpakete), und "Frames per
packet" aus den TxQueue-Zaehlern ist **Spielzeit-Anteil x 15**, keine
Packung (990 gepinnt: 13,5; 330 gepinnt: 10,5 — beides Duty-Cycle).
Damit ist auch die "Morgen-Session lag bei 330"-Analyse vom 26.8.
unbewiesen (die Rohaufnahme enthielt die LDAC-State-Sektion nicht;
rueckwirkend nicht mehr klaerbar — ab jetzt zeichnet die App live auf).

**Wichtigster offener Punkt fuer die naechste Session:** Den
Live-Link-Datenpfad auf die direkten Felder umbauen — `LDAC quality
mode` + `LDAC transmission bitrate (Kbps)` als MEASURED in Snapshot,
Panel und beide Graphen; die CodecModeInference auf diesen Feldern
verankern oder zurueckbauen (ihre Zaehler-Basis traegt nicht); die
Honesty-Texte anpassen ("Adaptive — rate not observable" stimmt auf
diesem Build nicht mehr); neue Voll-Fixture mit der LDAC-State-Sektion
aufnehmen (die bisherigen Fixtures sind ohne sie).

### Behobene Live-Funde des Abends (alle committet)

1. Pin-Chips reichten die maskierte Anzeige-Adresse an den Controller
   (User-Builds maskieren dumpsys) -> raw-Aufloesung uebers A2DP-Profil.
2. Event-Mapper scannte den ganzen Status-Blob -> LDAC-Links hiessen
   "aptX HD" in der Event-Liste -> Config-Sektion wird erst isoliert.
3. LHDCv5 (Typ 7) haette app-weit "aptX Adaptive" geheissen ->
   name-first, unbekannte Vendor-Typen ehrlich "Vendor codec (type N)".
4. Klinik-Dialog verwarf volle Formulare stumm bei Back/Outside-Tap
   (zweimal live passiert) -> Discard-Rueckfrage bei angefasstem Formular.
5. **ICU-Regex-Crash nur am Geraet**: nacktes `}` am Pattern-Ende laeuft
   auf der JVM (alle Tests gruen!), wirft auf Android
   PatternSyntaxException im <clinit> -> ganzes Live-Panel tot ("No
   device"). Lehre fuer Task 16 (synthetische Tests): JVM-Regex-Gruen
   beweist nichts fuer ICU; bare braces vermeiden.

### Ausserdem heute abend erledigt

- Room-Migration v1->2: Pin-Kalibrierung persistent, destruktiver
  Fallback entfernt, exportSchema an, Migrationstest nachweislich
  fail-faehig.
- Live-Graphen: 60s-Uebersicht (alle drei Verlustquellen) + 10s-Nahblick
  (2 Hz, eigener 233-ms-Probe, default aus, Kosten am Schalter).
- Klinisches Audiogramm am Geraet erfasst (links flach 10, rechts 15 bei
  125/250): "stored", Gate sagt korrekt "no loss to correct".
  Kalibrier-Transfer verweigert ehrlich (zu wenig konvergierter Overlap
  mit den 2 alten Runs) — fuer die echte Ableitung braucht Daniel einen
  frischen Hoertest.
- PLAN-A16-ABNAHME.md: finale Pixel-8-Pro-Abnahme vor der Weggabe
  (Bloecke A-E, kritische Trennstellen markiert).
- VERBOSE-Probe gefahren und zurueckgesetzt: bestaetigte 750 Frames/s
  und 20-ms-Encoder-Intervall; keine EQMID-Zeilen im Log noetig, weil
  die dumpsys-Felder alles liefern.

### Wiederaufnahme

1. Datenpfad auf die direkten LDAC-Felder umbauen (siehe oben) — das ist
   der Rest von Task 13/15 und macht Daniels Graphen zur echten
   ABR-Anzeige.
2. Task 16 (synthetische Tests) mit der ICU-Lehre.
3. A16-Abnahme nach PLAN-A16-ABNAHME.md, solange das Pixel 8 Pro da ist.
4. Backlog: 18 (Drift), 19 (ISO 7029), 20 (ISO-226-Tilt).

---

## Nachtrag 27. August, spaeter Abend: der Umbau ist gelandet und installiert

Commit 6eedd5d, 850 Tests gruen, auf dem Pixel 11 installiert und live
verifiziert: Panel zeigt "Adaptive - 396 kbps right now (measured)",
beide Graphen plotten die gemessenen kbps, die Proxy-Zeile ist weg.
Erledigt damit: Task 25 komplett, plus zwei weitere Live-Funde des
Abends (A2dpStateMachine-Block lief ins HFP-Profil und meldete
spielende Links als getrennt; Aktivierungs-Sackgasse "Helper running."
ohne Ausgang - stale Done nach forget()).

Offen fuer naechste Session: Task 16 (synthetische Tests, ICU-Lehre),
Task 24 (A16-Abnahme nach PLAN-A16-ABNAHME.md, solange das Pixel 8 Pro
da ist), Backlog 18-20. Der Hoertest fuer Daniels echte
Kopfhoerer-Kalibrierung steht weiter aus (braucht seine Ohren).

---

## Nachtrag: Task 16 (synthetische Tests) abgeschlossen

Commit 3ce5021, 963 Tests gruen, installiert. 113 neue Tests in zwei
Worker-Wellen: Regex-ICU-Lint (beisst nachweislich auf die historische
nackte Brace), Fixture-Sweep + Trunkierungs-Robustheit ueber alle
Parser (Absenz statt Null), Settle-Regel-Properties, Backup-Roundtrip
ueber 19 Zustaende, Einheiten-Kontrakt-Waechter, MAC-Redaktions-
Invariante, Loudness-Grenzfaelle, Dirty-Regel parametrisiert.

Zwei echte Funde dabei gefixt: Copy-Report leakte rohe MACs in die
Zwischenablage; firstInt las das '0' des 0x-Praefixes als Wert, wenn
der Klammerwert fehlte/abgeschnitten war. Dokumentierte Grenze:
Vorzeichen-Flip ist fuer CalibrationTransfer unsichtbar — Konvention
bleibt Aufrufer-Pflicht (als Identitaet im Test festgehalten).

Es bleiben: Task 24 (A16-Abnahme, braucht das Pixel 8 Pro am Kabel),
Backlog 18–20, frischer Hoertest fuer die Transfer-Ableitung.

---

## Handover 27. August, Pause — Backlog-Welle im Flug

Stand: Commits bis 3ce5021 + Handover-Nachtraege, 963 Tests gruen,
dieser Stand ist installiert (Pixel 11, Helper v4 aktiv). Task 16 fertig.

### Zwei Worker liefen beim Pausieren (Ergebnisse bei Wiederaufnahme einsammeln)

1. **ISO-226-Lautstaerke-Tilt (Task 20)** — :core-audio + EQ-Screen:
   echter ISO-226-Kern (Koeffiziententabelle, keine erfundenen Zahlen),
   Tilt = Form-Differenz zwischen Referenz- und aktueller Lautheit,
   Mitten normiert auf 0, Cap +-12 dB, nie Absenkung ueber Referenz;
   Fraction->Phon-Annahme als dokumentierte Konstanten; komponiert mit
   Kompensation/Loudness-Restoration im preEq-Pfad inkl. Auto-Headroom;
   reagiert live auf Volume-Aenderungen; Toggle default AUS, als
   Schaetzung beschriftet; BackupEq-Feld defaulted.
2. **ISO-7029-Altersprior (19) + Drift-Tracking (18)** — :core-hearing
   + Hoertest-Screen, sequenziell in einem Worker: Alterskurve als
   gedaempfte Chart-Referenz, Geburtsjahr im Store, NIEMALS
   automatisch in den EQ (dokumentierte Grenze), Plausibilitaets-
   Einzeiler bei grossem konvergiertem Abstand; Drift nur bei
   anhaltendem Signal (Median ueber >=3 vergleichbare Runs, >=10 dB
   auf >=2 Frequenzen — wegen 8-17 dB Sitzungs-RMSD), Zustaende
   NOT_ENOUGH_DATA/STABLE/DRIFT_SUSPECTED, ruhige Karte, keine
   Notifications.

### Wiederaufnahme

1. Worker-Ergebnisse einsammeln (git status; Task-Notifications),
   volle Suite (`gradlew --no-daemon testDebugUnitTest
   :app:assembleDebug`), committen, installieren, am Geraet kurz
   pruefen (EQ-Toggle, Chart-Referenz, Drift-Karte).
2. **Dann als Letztes laut Daniel: Android-16-Abnahme** nach
   PLAN-A16-ABNAHME.md — braucht das Pixel 8 Pro am Kabel; danach darf
   es weg.
3. Weiter offen: frischer Hoertest (Daniels Ohren) fuer die echte
   Kalibrier-Ableitung; danach steht die volle Kette Klinik->Transfer->
   Preset->EQ.

### Sicherheitsnetz-Notizen fuer die naechste Session

- Regex: ICU-Lint-Test faengt nackte Braces (RegexIcuCompatibilityTest).
- Parallel-Gradle: Worker raeumen build/tmp/kotlin-classes bei
  Lock/lookups.tab-Fehlern selbst.
- UI-Automation: Chip-Positionen verschieben sich nach Outcome-
  Meldungen — vor jedem Tap frisch dumpen; Klinik-Dialog hat jetzt
  Discard-Schutz.
