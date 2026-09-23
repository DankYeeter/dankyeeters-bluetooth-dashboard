# Architektur

Angelegt 2026-08-31 vom `architect` im Zuge von T-005. Fortgeschrieben, nie
ueberschrieben. Ueberholte Entscheidungen werden als abgeloest markiert, nicht
geloescht — der Verlauf ist der Zweck dieser Datei.

## Ueberblick

Die App macht den Bluetooth-Audiopfad eines Pixel 11 Pro sichtbar und
korrigierbar: sie liest, was der Stack tut (Codec, Bitrate, Verluste), sie legt
eine System-EQ-Kette zur Hoerkompensation in den Signalweg, und sie tut beides,
ohne den Klang zu verschlechtern, den sie misst (`GOAL.md`).

Hauptbausteine:

- ein **privilegierter Helper**, den `app_process` per ADB-Loopback aus der
  eigenen APK startet (Uid 2000, `u:r:shell:s0`). Er bietet eine geschlossene
  Menge benannter Operationen an, keine Shell.
- eine **Beobachtungsschicht**, die drei `dumpsys`-Ausgaben mit reinen Parsern
  in typisierte Momentaufnahmen uebersetzt.
- eine **Systemschicht**, die Geraeteprofile, `Settings.Global`-Schreibungen und
  die EQ-Anbindung haelt.
- eine **Oberflaeche** in Compose, die beides zusammenfuehrt.

Datenfluss: Helper → Rohtext → reiner Parser → Momentaufnahme + Ereignisse →
Repository/Ansicht. Aenderungen laufen den umgekehrten Weg und **immer mit
Read-back**: eine angenommene Schreibung ist kein Beleg.

Seit AD-030 ff. (23.09.) kommt Saeule 3 dazu: ein gefuehrter Vorher/Nachher-
Vergleich aus zwei Beobachtungslaeufen und einer reinen Vergleichsfunktion,
und ein Ledger, das vor jedem ersten Schreiben den Ausgangszustand festhaelt.

## Struktur

| Modul | Verantwortung |
|---|---|
| `:core-audio` | DSP- und EQ-Grundtypen |
| `:core-hearing` | Hoertest und Kompensationskurven |
| `:core-monitor` | Beobachtung der Strecke: Parser, Live-Quelle, Ereignisse, Room-Historie, reine Logik des gefuehrten Vorher/Nachher-Prozesses (`optimize/`, AD-037; `diagnostic/` faellt mit T-046) |
| `:core-system` | Zustand des Telefons: Geraeteprofile, `Settings.Global`, Systemeigenschaften, EQ-Anbindung, Dienste |
| `:app` | Oberflaeche, privilegierter Helper und Transport, Verdrahtung |

**Erlaubte Abhaengigkeitsrichtung** (verifiziert an den Gradle-Dateien, zyklenfrei):

```
:app  ->  :core-monitor
:app  ->  :core-system  ->  :core-hearing  ->  :core-audio
```

`:core-monitor` und `:core-system` kennen einander **nicht** und duerfen es
nicht anfangen. Was beide braucht, wird als Port im brauchenden Modul deklariert
und in `:app` implementiert und installiert — so entstanden `ShellRunner`,
`CodecStatusSource`, `CodecPreferenceController`, `SecureSettingsController`,
`SystemPropertyReader`. `:app` ist der einzige Ort, an dem beide Haelften
zusammenkommen (Beispiel: `LdacTuning`).

**Wo Zustand liegt:** Verlaufsdaten in der Room-Datenbank von `:core-monitor`;
Wuensche des Nutzers (Geraeteprofile, EQ) in den Stores von `:core-system`;
fluechtiger Laufzustand in `StateFlow`s neben der Oberflaeche. Der Helper haelt
keinen fachlichen Zustand ausser seiner Identitaet.

**Historie:** Die Entscheidungen vor AD-001 sind nicht rueckwirkend als AD
gefasst worden. Sie stehen ausfuehrlich im KDoc der betroffenen Typen und in
`docs/archiv/HANDOVER.md`; sie dort zu suchen ist zuverlaessiger, als sie hier aus dem
Gedaechtnis zu rekonstruieren.

---

## Entscheidungen

### AD-001 — Diese Datei beginnt beim heutigen Stand, nicht bei Null (2026-08-31, Status: aktiv)

**Kontext:** `ARCHITECTURE.md` existierte nicht. Die Struktur ist gewachsen und
im KDoc ungewoehnlich gut begruendet; `HANDOVER.md` (seit T-040 `docs/archiv/HANDOVER.md`) traegt 222 KB Verlauf.
T-005 verlangt neue Entscheidungen, die irgendwo hinmuessen.

**Optionen:**
A. Bestehende Struktur rueckwirkend in AD-Form giessen. Konsequenz: viel
Schreibarbeit, und jede rekonstruierte Begruendung ist eine Vermutung ueber die
Vergangenheit — genau die Sorte Behauptung, die dieses Projekt sonst verbietet.
B. Nur Ueberblick und Struktur beschreiben, ab heute nummerieren. Konsequenz:
die Datei ist ab sofort vollstaendig fuer alles Neue und verweist fuer Altes
dorthin, wo die echten Gruende stehen.
C. Gar keine Datei, alles in den Bericht. Konsequenz: der naechste `architect`
hat kein Gedaechtnis.

**Entscheidung:** B.

**Konsequenzen:** Leicht wird, neue Entscheidungen sauber abzulegen. Dauerhaft
schwer bleibt, eine alte Entscheidung als "abgeloest" zu markieren, wenn sie nie
eine Nummer hatte — dann muss der Verweis auf die Codestelle gehen, wie es
AD-006 vormacht.

**Umkehrbarkeit:** leicht.

---

### AD-002 — Der Scan liegt in `:core-monitor`, kein neues Modul (2026-08-31, Status: aktiv)

**Kontext:** Ein Befund-Scan braucht beide Haelften: Streckenbeobachtung
(`:core-monitor`) und Telefonzustand (`:core-system`). Die beiden kennen
einander nicht.

**Optionen:**
A. Neues Modul `:core-scan`, das von beiden abhaengt. Konsequenz: sauber
geschichtet, aber ein Gradle-Modul, ein Manifest und ein Testaufbau mehr fuer
eine Faehigkeit, die es noch nicht gibt — Architektur auf Vorrat.
B. In `:core-monitor`, Systemseite ueber Ports. Konsequenz: `:core-monitor`
bekommt zwei weitere Ports (`EnvironmentFacts`, `EnvironmentControls`), die
`:app` bedient. Es gibt bereits das Vorbild: `DeviceDiagnosticRunner` liegt dort,
ist nutzerausgeloest, aendert den Codec ueber einen Port und stellt hinterher
wieder her — der Scan ist dieselbe Gattung.
C. In `:app` neben `LdacTuning`. Konsequenz: keine neue Struktur, aber die reine
Entscheidungslogik (Urteile, Schwellen, Registry) landet im Modul mit dem
meisten Android und dem wenigsten Testkomfort.

**Entscheidung:** B.

**Konsequenzen:** Leicht wird, die Urteilsrechnung ohne Telefon zu testen und
die vorhandenen Parser, Zaehler und Honesty-Typen direkt zu benutzen. Dauerhaft
schwer wird, den Scan spaeter herauszuloesen, falls `:core-monitor` zu gross
wird; Ports und reine Logik machen es machbar, aber es waere ein Umzug.

**Umkehrbarkeit:** mittel — ein Paketumzug plus Gradle-Modul, kein Datenmodell.

**Nachtrag 2026-09-23 (T-047a):** Das Vorbild `DeviceDiagnosticRunner` ist
**abgeloest durch T-046** (entfernt, AK-12). Die Entscheidung selbst stuetzt
sich nicht darauf und bleibt; die reine Logik des Vorher/Nachher-Prozesses liegt
nach demselben Muster in `:core-monitor` (`optimize/`, AD-037).

---

### AD-003 — Zwei Phasen: Bestandsaufnahme immer, Belege einzeln freigegeben (2026-08-31, Status: teilweise abgeloest durch AD-037 — Phase 2)

**Kontext:** Ein Scan, der alles misst, dauert Minuten und braucht laufende
Musik. Ein Scan, der nur liest, ist in zwei Sekunden fertig und beantwortet
"wie sieht mein Telefon aus", aber keine Wirkungsfrage. AK-4 verbietet
wiederkehrende Hintergrundarbeit.

**Optionen:**
A. Ein Lauf, alles oder nichts. Konsequenz: der Nutzer bekommt nie schnell eine
Antwort und muss jedes Mal Musik anwerfen.
B. Zwei Phasen; Phase 1 immer, Phase 2 je Experiment einzeln freigegeben.
Konsequenz: zwei Ergebnistypen im selben Bericht, dafuer ist Phase 1 fuer sich
lieferbar und Phase 2 waechst schrittweise.
C. Nur Bestandsaufnahme, keine Experimente. Konsequenz: die App kann nie eine
Wirkung belegen und bleibt bei "diese Einstellung ist an" stehen — nach AK-3
darf sie dann nichts weiter behaupten, und der Nutzen bleibt aus.

**Entscheidung:** B. Der Lauf ist eine Coroutine im Scope des Bildschirms, der
ihn gestartet hat — dieselbe Bauform wie `DeviceDiagnosticRunner`, ausdruecklich
nicht wie `MonitorEngine`. Kein Service, kein WorkManager, kein Timer.

**Konsequenzen:** Leicht wird die Einhaltung von AK-4 und eine schrittweise
Lieferung. Dauerhaft schwer wird ein Scan, der ueber Stunden mitlaeuft — das ist
Absicht.

**Umkehrbarkeit:** leicht.

**Nachtrag 2026-09-23 (T-047a):** Phase 2 (einzeln freigegebene Wirkungsbelege)
ist fuer Umgebungsmassnahmen **abgeloest durch AD-037**: der gefuehrte Prozess
misst dieselbe Frage, mit dem Nutzer als Ausfuehrendem statt der App. Die
Bauform "Lauf lebt im Scope des Bildschirms, kein Service" bleibt und gilt fuer
den Prozess (AD-030). Der Verweis auf `DeviceDiagnosticRunner` ist Geschichte
(T-046).

---

### AD-004 — Das Evidenzniveau ist Teil des Datenmodells (2026-08-31, Status: aktiv)

**Kontext:** AK-3 verlangt, dass keine Wirkung behauptet wird, die nicht gezeigt
werden kann. Die Versuchung eines Scans ist, aus "diese Einstellung ist an" die
Zeile "das kostet dich Bitrate" zu machen.

**Optionen:**
A. Regel in der UI-Schicht, Datenmodell bleibt schlicht. Konsequenz: die Regel
haengt an Textbausteinen und wird beim naechsten Redesign gebrochen.
B. `Evidence` am Befund, `EffectClaim` am Faktor, und `effect != null` nur mit
`MEASURED_EFFECT` konstruierbar. Die Wirkungsaussage haengt am *Faktor*
(allgemein), nie am *Befund* (diesem Telefon). Konsequenz: etwas mehr Typen,
dafuer ist die Verwechslung nicht mehr formulierbar.
C. Nur `MEASURED_EFFECT` ueberhaupt anzeigen. Konsequenz: der Bericht ist meist
leer und damit nutzlos.

**Entscheidung:** B, mit `Reading.Unreadable(reason)` als drittem Zustand — der
Grund reist woertlich aus der Datenschicht mit, wie es die Live-Ansicht mit
ihren `warnings` bereits tut.

**Konsequenzen:** Leicht wird ein ehrlicher Bericht. Dauerhaft schwer wird eine
knappe, marketing-taugliche Zeile — auch das ist Absicht.

**Umkehrbarkeit:** mittel, sobald Berichte persistiert werden (offene Frage 6).

---

### AD-005 — WLAN-Fakten als typisierte, lesende Helper-Operation, nicht als Whitelist-Exec (2026-08-31, Status: ruht — nicht gebaut; Saeule 3 kommt ohne neues Helfer-Kommando aus, AD-035)

**Kontext:** Das Band des aktiven WLAN-Links und die affiliierten MLO-Links sind
die einzige Katalog-Position, die mit den vorhandenen Zugriffen gar nicht
erreichbar ist.

**Optionen:**
A. Im App-Prozess ueber `WifiManager`. Konsequenz: braucht `ACCESS_WIFI_STATE`
und je nach Feld `ACCESS_FINE_LOCATION` bzw. `NEARBY_WIFI_DEVICES`. Diese App
haelt heute zwei Permissions, keine davon Standort. Eine Standortpermission
dauerhaft ins Manifest zu schreiben ist eine groessere und sichtbarere
Ausweitung als alles andere in diesem Entwurf. (Ob `getFrequency()` ohne
Standort auskommt, war nicht sicher zu klaeren — die Unsicherheit selbst ist ein
Argument gegen diesen Weg.)
B. `["dumpsys", "wifi"]` auf die Whitelist. Konsequenz: hunderte KB, gespeicherte
Netze, SSIDs und MAC-Adressen wandern in den App-Prozess. Fuer drei Zahlen.
C. `["cmd", "wifi", "status"]` auf die Whitelist. Konsequenz: schmal und
exakt-argv pruefbar, aber SSID und BSSID des Heimnetzes reisen trotzdem in den
App-Prozess und muessten dort weggeworfen werden.
D. Typisierte Operation `wifiFacts`: der Helper ruft das feste Argument-Vektor
selbst auf — wie `restartBluetooth` es bereits tut, dessen Vektoren
ausdruecklich *nicht* in `ALLOWED` stehen — parst es und gibt nur
`{band, freqMhz, rssiDbm, linkSpeedMbps, standard, mloLinks[]}` zurueck.

**Entscheidung:** D, als **lesende** Operation (`mutates = false`), Helper-
Version 6. Sie ist die einzige Form, in der der Netzname den Helper nie
verlaesst. Der Parser bleibt rein und im Stil von `PrivilegedProtocol`
unit-getestet.

**Konsequenzen:** Leicht wird eine ehrliche B-1-Zeile ohne neue App-Permission
und ohne Namen im App-Prozess. Dauerhaft schwer wird das Nachfordern weiterer
WLAN-Felder — jedes braucht eine Protokollaenderung. Das ist der Preis dafuer,
dass die Operation eng bleibt.

**Umkehrbarkeit:** mittel — AIDL-Methode, Versionssprung, Sicherheitspruefung.
Ein Rueckbau kostet dasselbe noch einmal.

---

### AD-006 — Zwei Scan-Schalter duerfen geliehen werden; das loest eine dokumentierte Gegenposition ab (2026-08-31, Status: ruht — Nutzer 23.09. F3: kein Leihen der Scan-Schalter im Prozess, AD-035; offene Frage 1 bleibt unbeantwortet)

**Kontext:** `wifi_scan_always_enabled` und `ble_scan_always_enabled` sind die
zwei plausibelsten periodischen 2,4-GHz-Verbraucher. Ohne sie zu veraendern
kann die App zu ihnen nie mehr sagen als "steht auf 1". `BluetoothDeveloperOptions`
haelt heute ausdruecklich fest, `ble_scan_always_enabled` sei eine
Standorteinstellung und diese App habe dort nichts zu suchen.

**Optionen:**
A. Bei der alten Position bleiben, nur anzeigen. Konsequenz: zwei Faktoren
bleiben fuer immer PLAUSIBEL, und die App darf sie nach AK-3 nie als Ursache
benennen.
B. Als dauerhafte Profileinstellung anbieten. Konsequenz: genau das, wovor der
alte Kommentar warnt — die App verschlechtert die Ortung fremder Apps
unbemerkt und auf Dauer.
C. Leihen: nur waehrend eines Experiments, nur nach ausdruecklicher
Einwilligung, immer mit garantierter Rueckgabe.

**Entscheidung:** C. Der alte Satz gilt weiter fuer B; dieser Entwurf
widerspricht ihm nur fuer C, und das steht hier, damit niemand denkt, er waere
uebersehen worden. Die Positivliste ist genau diese zwei Schluessel.

**Konsequenzen:** Leicht wird ein Beleg statt einer Vermutung. Dauerhaft schwer
wird die Zusicherung "die App laesst nichts zurueck" — sie haengt vollstaendig
an AD-007 und muss dort getestet werden, nicht nur behauptet.

**Umkehrbarkeit:** leicht als Entscheidung (Positivliste leeren), schwer als
Vertrauen, wenn eine Rueckgabe je fehlschlaegt.

---

### AD-007 — Geliehene Einstellungen haben ein persistiertes Ledger in `:core-system` (2026-08-31, Status: teilweise abgeloest durch AD-033 — Ort und "vor der Aenderung schreiben" bleiben; automatische Rueckgabe beim App-Start ruht, weil nichts mehr geliehen wird)

**Kontext:** Ein Experiment, das eine Einstellung aendert und dann abstuerzt,
laesst das Telefon veraendert zurueck — ohne dass irgendwer weiss, was.

**Optionen:**
A. Rueckgabe nur im `finally` des Laufs. Konsequenz: deckt Abbruch und
Bildschirmwechsel ab, nicht aber einen getoeteten Prozess. Genau der Fall, der
wirklich passiert.
B. Ledger auf Platte, geschrieben **vor** der Aenderung, zurueckgegeben im
`finally` **und** beim naechsten App-Start.
C. Gar nichts aendern (siehe AD-006, Option A).

**Entscheidung:** B, im Persistenz-Bereich von `:core-system` — dort, wo die
Einstellungen ohnehin zu Hause sind. Nicht in der Room-Datenbank von
`:core-monitor`: der Monitor hat kein Geschaeft damit zu wissen, wie man
Systemzustand zuruecknimmt.

**Konsequenzen:** Leicht wird eine belastbare Zusage. Dauerhaft schwer: jede
kuenftige Leihgabe muss durch dieses Nadeloehr, auch wenn sie im Moment
harmlos aussieht. Scheitert eine Rueckgabe, sagt die App das so lange, bis es
gelingt.

**Umkehrbarkeit:** leicht.

---

### AD-008 — Der Treppen-Optimierer wird Maschine des Scans, nicht eigenes Feature (2026-08-31, Status: ruht mit T-005; der Prozess aus AD-037 hat keine Treppe)

**Kontext:** T-005 stellt den Optimierer aus T-003 zurueck, aber der Scan
braucht selbst eine Treppe, um die geforderte Rate zu variieren.

**Optionen:**
A. Zwei getrennte Dinge. Konsequenz: D-7-Anmeldung, armweiser Vergleich,
Rueckgabe und `INCONCLUSIVE` werden zweimal gebaut — zwei Muster fuer dasselbe
Problem.
B. Optimierer als letzter Schritt des Scans ("erst Umfeld raeumen, dann
Treppe"). Konsequenz: klingt richtig, ist aber zu teuer, weil die
Umgebungs-Experimente Minuten kosten und der billige Diskriminator ganz vorne
gehoert.
C. Die Treppe ist ein Experiment unter anderen; die Reihenfolge lautet
Diskriminator (zwei Stufen) → falls noetig Umgebungs-Experimente → falls
gewuenscht volle Treppe unter geraeumten Bedingungen. Kein Optimize-Knopf, kein
automatisches Pinnen am Ende.

**Entscheidung:** C.

**Konsequenzen:** Leicht wird, den Optimierer spaeter als duenne Oberflaeche auf
demselben Ausfuehrer nachzuliefern. Dauerhaft schwer wird ein Optimierer, der
etwas grundlegend anderes tut als messen, aendern, zurueckgeben.

**Umkehrbarkeit:** leicht.

---

### AD-009 — Der Diskriminator laeuft vor der Umgebungserfassung (2026-08-31, Status: teilweise abgeloest durch AD-032 — fuer den Vorher/Nachher-Vergleich gelten A/B und der Binomialtest statt A/B/A und 2sd; E-0 selbst ruht mit T-005)

**Kontext:** Der belegte Befund ist ein Stocken im ~3-s-Takt bei gepinnt 990.
`docs/state.md` nennt zwei Deutungen: einen periodischen Stoerer und einen
Grenzzyklus aus Ueberlastung. **Beide sagen Periodizitaet voraus.** Die Periode
allein unterscheidet sie nicht.

**Optionen:**
A. Der Reihe nach alles erfassen und hoffen, dass etwas heraussticht.
Konsequenz: die App nennt den plausibelsten Kandidaten, ohne ihn zeigen zu
koennen — der Fehler, den AK-3 verbietet.
B. E-0 zuerst: 60 s bei 990 und 60 s bei 660, identische Kadenz, Zeitreihe der
Sendeschlangenlaenge und der Verlustzaehler. Bleibt die Periode ueber beide
Stufen gleich, spricht das fuer einen Stoerer; aendert sie sich stark oder
verschwindet sie, und fuellt sich die Schlange dazwischen monoton, spricht das
fuer Ueberlastung.

**Entscheidung:** B. Das kostet keinen neuen Zugriff — Pinnen kann die App, die
Zahlen stehen in einem bereits erlaubten Dump, und `docs/perf/baselines.md`
Block 1 hat gemessen, dass dieser Dump selbst bei 0,5-s-Kadenz keine einzige
Verlustmetrik verschlechtert.

Methodische Bindung: A/B/A statt A/B (die Umgebung driftet), gleiche Kadenz in
allen Armen (der Beobachter faellt als konstanter Offset heraus, wie in
`baselines.md` begruendet), genau eine Variable je Experiment, und ein Urteil
nur bei `delta > max(2sd_baseline, 2sd_beobachtet)`. Die Baseline-Streuung
stammt aus dem ungestoerten Regime und traegt im gestoerten nicht allein.

**Konsequenzen:** Leicht wird eine belastbare Antwort auf die eine Frage, die
seit dem 30.08. offen ist. Dauerhaft schwer wird der bequeme Weg, die
WLAN-Spur als Ursache zu praesentieren, bevor sie gezeigt ist.

**Umkehrbarkeit:** leicht.

---

### AD-010 — Eine grosse exec-Antwort reist als Dateideskriptor, nie als benannter Pfad (2026-09-01, Status: aktiv — Review bestanden mit Auflagen; **braucht Geraete-Spike U-0 und Retest U-6**)

**Kontext:** SR-001. `ExecSpill` legt jede exec-Antwort ueber 64 KB unter dem
festen Namen `/data/local/tmp/btdash_exec_current.out` ab. Am 01.09. am Geraet
nachgemessen (T-007, App **deinstalliert**): Modus **0666**, 118 KB, zwei Tage
alt und die Deinstallation ueberlebt. Welt-**lesbar** war der bekannte Befund;
welt-**schreibbar** ist neu und macht aus einem Vertraulichkeits- zusaetzlich
einen Integritaetsbefund.

**Ursache des 0666, praezise:** Der Code setzt den Modus nicht falsch, er setzt
ihn gar nicht. `File.writeBytes` legt mit `0666 & ~umask` an; der Helper erbt
die umask der ADB-Shell, die ihn gestartet hat, und die ist auf diesem Geraet
**0**. `setReadable(true, false)` und `setWritable(true, true)` sind danach
No-ops — sie koennen Rechte nur *setzen*, nicht die schon vergebenen fremden
Schreibrechte nehmen. Dieselbe Wurzel erklaert `btdash_helper.log` (0666, per
Shell-Redirect angelegt) und `btperf` (0777, `mkdir -p` aus den Messwerkzeugen).
Ein einziger geerbter Wert, drei Befunde.

**Die Kraft, die alles entscheidet:** Der Helper laeuft als `shell` (2000), die
App als eigene App-Uid. Damit die App eine Datei **ueber ihren Pfad** oeffnen
kann, muss deren Modus `o+r` tragen — Linux-DAC kennt kein "lesbar genau fuer
diese eine fremde Uid". `chown` scheitert (die Shell hat kein CAP_CHOWN), ACLs
gibt es nicht, ein Verzeichnis, das nur die App betreten darf, kann der Helper
nicht beschreiben. **Jede Uebergabe ueber einen Pfad ist deshalb zwingend
welt-lesbar.** Das ist kein Fehler in `ExecSpill`, das ist die Bauart.

**Optionen:**

A. **Im Bestand bleiben, Modus reparieren** (0644 statt 0666). Konsequenz:
schliesst die Schreibbarkeit, nicht die Lesbarkeit. Nach dem Absatz oben ist
o+r unvermeidlich, solange die App den Pfad oeffnet. Bei offener Live-Ansicht
liegt dort weiterhin sekuendlich der volle `dumpsys bluetooth_manager` mit
Namen und MACs aller gekoppelten Geraete. Loest den Befund nicht.

B. **Option (c) des Reviews: unvorhersagbarer Name je Aufruf + sofortiges
Loeschen.** Konsequenz: **neu bewertet und verworfen.** Sie verkleinert ein
Fenster, das gar nicht das Problem ist. Die Datei bleibt o+r (Absatz oben), sie
bleibt o+w, sie ueberlebt die Deinstallation, und ein Beobachter, der das
Verzeichnis in einer Schleife liest, sieht jeden Namen sofort — Raten ist
nicht noetig. Zusaetzlich holt sie den Befund zurueck, der den festen Namen
ueberhaupt erzwungen hat: ~470 Dateien in zwei Minuten, ~200 MB Flash je
Sweep-Fenster. Sie ist nicht das Minimum, sie ist die schlechteste der drei.

C. **Chunking unter dem Binder-Limit.** Konsequenz: keine Datei, aber der
Helper muss die Antwort zwischen den Abrufen halten — er bekaeme fachlichen
Zustand, den ihm die Struktur oben ausdruecklich abspricht, samt Ablauf,
Verdraengung und einer Reply-Id als neuem, vom Aufrufer variierbarem Parameter
auf einer privilegierten Flaeche. Die Rechnung ist ausserdem teurer als sie
aussieht: stdout reist Base64 (+33 %) und als Java-String in UTF-16 (x2), 222 KB
werden zu ~592 KB im 1-MB-Puffer, und die Chunk-Groesse muss **nach** der
Kodierung bemessen werden — genau die Art Rechnung, die man einmal falsch
macht. Bei 1-Hz-Kadenz sind das vier bis fuenf Binder-Runden je Dump statt
einer.

D. **Dateideskriptor ueber den Binder.** Der Helper schreibt die Nutzlast in
einen Deskriptor; der Kernel installiert ihn im Zielprozess. Konsequenz: es
entsteht **kein Name, den irgendwer nennen koennte** — nicht ein schwer zu
ratender, sondern keiner. Ein Dritter kann nicht oeffnen, was kein Pfad ist.
Kein Flash-Schreibzugriff, kein Sweep, kein Modus, der falsch sein kann.

**Entscheidung:** **D**, und zwar **ohne Schwelle**: *jede* exec-Antwort reist
kuenftig durch den Deskriptor, auch die kurze. Zwei Formen fuer eine Antwort
heissen ein selten begangener Zweig, und der selten begangene war hier der
gefaehrliche. `INLINE_LIMIT_BYTES` behaelt nur noch seine zweite Aufgabe, die
Begrenzung von stderr.

**Welche Bauform des Deskriptors, entscheidet die Messung.** Der Socket-Weg ist
in diesem Projekt schon einmal an SELinux gestorben und das wurde erst am
Geraet sichtbar (`PrivilegedServer`, `avc: denied { connectto }`). Ich behaupte
deshalb nicht, dass FD-Durchreichung zwischen `shell` und `untrusted_app`
erlaubt ist.

**Erste Reihenfolge (01.09., vom Review abgeloest — bleibt stehen, weil die
Korrektur ohne sie nicht lesbar ist):** 1. Pipe der App, Schreibende an den
Helper. 2. Pipe des Helpers, Leseende an die App. 3. Regulaere Datei in
`/data/local/tmp`, sofort nach dem Oeffnen entlinkt. 4. `SharedMemory`.

#### Nachtrag nach dem Sicherheitsreview (2026-09-01) — geltende Reihenfolge

Der Reviewer hat die Reihenfolge zurueckgewiesen und dabei drei Dinge
gefunden, von denen zwei meine eigenen Vorschlaege entwerten. Geltend ist:

> **3' → 4 → 1 → 2 → 3**

**3' — regulaere Datei im *App-privaten* Verzeichnis, von der **App** angelegt,
von der App sofort entlinkt, Schreib-Deskriptor an den Helper.** Mir entgangen,
weil ich einen Satz zu breit gezogen hatte: „ein Verzeichnis, das nur die App
betreten darf, kann der Helper nicht beschreiben" gilt fuer das **Oeffnen ueber
einen Pfad** — nicht fuer das Schreiben in einen Deskriptor, den die App dort
bereits geoeffnet hat. Genau das ist der Zweck von Deskriptor-Durchreichung:
sie delegiert einen bereits vollzogenen Zugriff. Der Inode entsteht damit nie
in einem geteilten Verzeichnis, das Verzeichnis ist 0700 der App-Uid, der Modus
ist gleichgueltig, es gibt keinen Namen — und anders als eine Pipe blockiert
sie nie. Sie schlaegt meine Bauform 3 auf jeder Achse bei gleichem
Betriebsverhalten.

**Warum sie trotzdem messbar bleibt und nicht gesetzt ist:** `shell` schreibt
hier auf `app_data_file` mit den MLS-Kategorien der App — die Grenze, zu deren
Ueberschreitung es `run-as` ueberhaupt gibt. Mein Vorbehalt: das ist der Arm
mit der geringsten Vorab-Wahrscheinlichkeit. Mein Gegenargument dazu: bei
einem durchgereichten Deskriptor ist die Zugriffsentscheidung beim `open()` in
der Domaene des Oeffners gefallen; der verbleibende Test ist im Wesentlichen
`fd use`. Welche der beiden Lesarten stimmt, entscheidet **U-0**, nicht dieses
Dokument.

**SR-013 — meine Bauform 3 ist SR-001 mit kuerzerem Fenster, keine Behebung.**
Entlinken wirkt nicht rueckwirkend: wer den konstanten Namen im Fenster
zwischen `open()` und `unlink()` trifft — bei 1 Hz jede Sekunde ein neues
Fenster — haelt danach einen eigenen Deskriptor auf den Inode und liest den
vollen Dump in Ruhe. Das Verzeichnis ist 0771 (Director-Nachmessung, siehe
`security/findings.md`): auflisten kann dort niemand, **oeffnen bei exakt
bekanntem Namen** schon, und der Name steht als Konstante in einer
sideloadbaren APK. Bauform 3 faellt damit ans Ende und darf nur gebaut werden,
wenn der Modus **beim Anlegen** 0600 ist **und** der Name je Aufruf
unvorhersagbar. Ein nachtraegliches `setReadable(false, false)` ist zu spaet
(Auflage A11).

**SR-014 — meine Bauformen 1 und 2 koennen den unsterblichen Helper dauerhaft
verklemmen.** Eine Pipe fasst 64 KB, die Nutzlast 115–222 KB. Schreibt der
Helper aus dem Binder-Thread, waehrend der Aufrufer synchron auf dieselbe
Transaktion wartet, blockiert er nach 64 KB, und die App liest nicht, weil sie
wartet: vollstaendiger Deadlock. Der vorhandene Timeout deckt nur den
Kindprozess ab, nicht das Schreiben in den Deskriptor. Bei 1 Hz sind alle
Binder-Threads in Sekunden verbraucht — der Helper waere tot, ohne zu sterben,
und genau das darf ihm nach `PrivilegedServer` nie passieren. Werden 1 oder 2
doch gebaut, gilt: nie aus dem Binder-Thread schreiben, eigener
Schreib-Timeout, garantiertes `close()` auf jedem Pfad, Leser **vor** dem
Aufruf gestartet (Auflage A14).

**Mein Streaming-Argument fuer die Pipes traegt heute nicht.** `execute()` liest
stdout ohnehin vollstaendig in einen Java-String, bevor irgendetwas kodiert
wird. Solange das so ist, kostet Bauform 4 keinen Byte mehr als Bauform 1 —
und 4 blockiert nicht. Damit ruecken die beiden nicht-blockierenden Formen
(3', 4) vor die beiden blockierenden (1, 2), und das ist die ganze Begruendung
der neuen Reihenfolge.

**SR-018 — der Nutzlast-Deskriptor darf nicht an `dumpsys` vererbt werden.**
Erbt der Kindprozess ihn, faellt am Schreibende nie EOF, und zusammen mit
SR-015 ergibt das eine stumme Kuerzung. Strukturelle Behebung, nicht Messung:
`FD_CLOEXEC` auf dem empfangenen Deskriptor setzen (`Os.fcntlInt`, oeffentliche
API), **bevor** irgendein Kindprozess gestartet wird. U-0 misst zusaetzlich, ob
die Laufzeit das ohnehin tut (Auflage A12).

Scheitern alle fuenf am Geraet, ist diese Entscheidung hinfaellig und C
(Chunking) wird zur Rueckfalloption — dann zurueck an den `architect`, nicht
selbst umschwenken.

**Was strukturell getragen wird** (gilt auch, wenn jemand unaufmerksam ist):

- Es existiert kein Dateiname fuer eine Antwort. Kein Dritter kann oeffnen,
  benennen oder ueberschreiben, was keinen Pfad hat.
- Der Deskriptor erreicht genau einen Prozess; der Kernel stellt ihn zu.
- Der Client **kann** nicht mehr auf eine Datei gezeigt werden: das **Pfadfeld**
  verschwindet aus der Antwort, `ExecSpill.isMine` entfaellt ersatzlos. Eine
  Antwort hat kein Feld mehr, in dem ein Pfad stuende.
- `exec` hat genau eine Antwortform.
- `FD_CLOEXEC` auf dem Nutzlast-Deskriptor: kein Kindprozess kann ihn erben
  und damit das EOF verzoegern (SR-018).

**Korrektur nach Review — SR-015: `byteCount` ueberlebt den Umbau.** Ich hatte
`ExecHandoff` als Ganzes zum Loeschen vorgesehen und damit versehentlich die
einzige Erkennung stiller Trunkierung mitgenommen. Bricht das Schreiben ab,
bekommt die App einen kuerzeren, syntaktisch einwandfreien Dump — und die
Parser stromabwaerts akzeptieren kurze Dumps als Normalfall, weil ein
getrennter Kopfhoerer genau so aussieht. Das waere ein falscher Freispruch und
verletzt AK-3 unmittelbar. Es faellt also nur der **Pfad** weg; die
Binder-Antwort traegt weiterhin `exitCode`, **`byteCount`** und `stderr`, und
der Client verweigert bei Abweichung zwischen angekuendigter und gelesener
Laenge das Parsen. Damit ist die Pruefung sogar staerker als heute: sie
vergleicht nicht mehr gegen ein `stat`, sondern gegen das, was der Schreiber
tatsaechlich geschrieben zu haben meint.

**Was nur Konvention bleibt** (muss geprueft werden, traegt sich nicht selbst):

- Der Helper schliesst den empfangenen Deskriptor auf **jedem** Pfad —
  ausdruecklich auch auf den **Ablehnpfaden** (SR-020): der Kernel installiert
  den Deskriptor, bevor `refuse()` laeuft, also leckt jede fruehe Rueckkehr
  einen. Der Helper ist absichtlich unsterblich; bei 1 Hz ist das
  Deskriptor-Limit in Minuten erreicht. Ebenso ist `null` fuer einen
  `ParcelFileDescriptor` ein zulaessiger Aufrufwert und darf auf privilegierter
  Flaeche keine Ausnahme ausloesen. Pruefpunkt QA: 500 Aufrufe, davon die
  Haelfte mit falschem Token und je einer mit `null`, danach `/proc/<pid>/fd`
  unveraendert gross.
- Der Helper protokolliert keinen Nutzlastinhalt (SR-009).
- `EXEC_LOCK` bleibt bestehen — siehe Konsequenzen.

**Konsequenzen:** Leicht wird die Zusage "kein dritter Prozess sieht die
Dumps", weil sie nicht mehr von einem Modus abhaengt. Der Weg wird ausserdem
**billiger**, was AK-1 verlangt: die ~118 KB Flash je Dump entfallen (bei
offener Live-Ansicht rund 425 MB/h), der Sweep bei jedem Schreiben entfaellt,
die Base64-Runde ueber die Nutzlast entfaellt. Der teure Anteil bleibt
unveraendert, weil er woanders liegt: `dumpsys bluetooth_manager` kostet
gemessen 172–176 ms und +3,4 bis +14,1 CPU-Punkte im Bluetooth-Prozess
(`docs/perf/baselines.md`, Block 1) — daran aendert der Transport nichts, und
Block 1 hat gezeigt, dass selbst 0,5-s-Kadenz keine Verlustmetrik
verschlechtert. Der Umbau bewegt sich also strikt nach unten gegenueber einem
Budget, das schon als unbedenklich belegt ist.

Dauerhaft schwer wird ein Aufrufer, der die Antwort als Datei weiterreichen
will — den gibt es nicht und soll es nicht geben.

**Eine Kopplung faellt weg, und das ist gefaehrlicher als es klingt:**
`PrivilegedShellRunner.EXEC_LOCK` ist heute fuer die *Korrektheit* tragend (ein
wiederverwendeter Dateiname vertraegt keine zwei gleichzeitigen Aufrufe). Nach
dem Umbau ist er das nicht mehr — beide alten Begruendungen (Binder-Puffer und
Dateiname) sind erledigt. Der Lock **bleibt trotzdem**, mit neuer und einziger
Begruendung: zwei gleichzeitige `dumpsys bluetooth_manager` waeren doppelte
Last im Bluetooth-Prozess, und AK-1 verbietet Mehrlast im Audiopfad. Diese
Begruendung steht nur im KDoc; kein Test faengt ihre Entfernung. Wer den Lock
spaeter anfasst, muss das hier gelesen haben.

**Umkehrbarkeit:** mittel — AIDL-Signatur, Versionssprung, Sicherheitspruefung.
Ein Rueckbau kostet dasselbe noch einmal und waere die Wiederherstellung eines
bestaetigten Befunds.

---

### AD-011 — Aufgeraeumt wird nur vom Helper, nur nach Namensform, und ein Fall bleibt ungeloest (2026-09-01, Status: aktiv)

**Kontext:** Die Reste in `/data/local/tmp` ueberleben die Deinstallation. Das
Verzeichnis gehoert nicht zum App-Datenverzeichnis, der Paketmanager raeumt
dort nichts, und die App kann dort nicht entlinken: Loeschen braucht
Schreibrecht am **Verzeichnis**, und das hat nur `shell`. AD-010 verhindert
kuenftige Reste; die vorhandenen verschwinden davon nicht.

**Optionen:**
A. Nichts tun, weil AD-010 die Quelle schliesst. Konsequenz: die 118 KB von
heute liegen weiter da — der Befund waere behoben und die Beute bliebe liegen.
B. Aufraeumen in der App beim Start. Konsequenz: geht nicht, siehe oben; ein
Aufruf, der immer scheitert, sieht im Code aus wie eine Zusicherung.
C. Aufraeumen im Helper, beim Start und beim geordneten Ende, ausschliesslich
nach der Namensform `btdash_exec_*.out`, nicht rekursiv, nur Dateien.

**Entscheidung:** C. `ExecSpill` verschwindet und wird durch eine reine
**Loeschklasse** ersetzt, die kein Schreibverfahren besitzt — eine Klasse ohne
Schreibpfad kann nichts stagen, und das ist eine strukturelle Zusicherung statt
einer Absichtserklaerung. Die Altersgrenze (`SPILL_MAX_AGE_MS`, 5 min) faellt
ersatzlos: nach AD-010 stagt niemand mehr, also ist **jede** Datei dieser Form
ein Rest.

**Erste Fassung (01.09., vom Review korrigiert):** aufgeraeumt wird beim Start
des Helpers und in `shutdown()`.

**Korrektur nach Review — SR-021: der `shutdown()`-Zweig laeuft im
beschriebenen Fall gar nicht.** In `PrivilegedServer.main()` steht
`reapOtherHelpers()` **vor** `handOver()`: der neue Helper SIGKILLt seine
Vorgaenger, bevor der Provider ueberhaupt dazu kommt, `shutdown()` auf dem
alten aufzurufen — deshalb steht in `PrivilegedProvider.retire()` auch schon
heute die Zeile „the replaced helper did not acknowledge shutdown". Der Zweig
waere totes Gewicht, das aussieht wie eine Zusicherung. Er **entfaellt**.

Geltend sind damit drei Stellen, alle ereignisgetrieben, keine davon periodisch
(AK-4):

1. **Beim Start des Helpers**, bevor er bedient. Deckt den Wiedereinbau
   (`adb install -r`), den Helperwechsel (der Nachfolger raeumt auf, was der
   SIGKILLte Vorgaenger liegen liess) und jeden Absturz.
2. **Beim Erkennen, dass das Paket fort ist** — siehe R1. Das ist der
   **Hauptweg**.
3. **Auf ausdrueckliche Nutzeraktion** — siehe den Nachtrag „Beenden und
   aufraeumen" weiter unten. Das ist die **Absicherung fuer den Restfall**,
   nicht der Hauptweg.

Stelle 3 belebt denselben `shutdown()`-Zweig wieder, den ich zwei Absaetze
weiter oben gestrichen habe. Das ist kein Widerspruch, sondern der Unterschied
zwischen zwei Aufrufern: beim Helperwechsel ruft `retire()` in einen Prozess,
den `reapOtherHelpers()` schon getoetet hat — dort ist der Zweig unerreichbar.
Auf Stelle 3 ruft die App selbst, in einen lebenden Helper, und prueft danach
nach. Der Zweig kehrt also zurueck, aber an einen Pfad, der ihn wirklich
erreicht.

**Verallgemeinerte Inode-Regel** (vom Reviewer bestaetigt und hier weiter
gefasst als urspruenglich): *Kein Helper loescht je einen Pfad, den ein
Nachfolger bereits geoeffnet haben kann.* Deshalb faellt das Log aus dem
Startfall heraus (der Redirect des eigenen Startbefehls hat es gerade
geoeffnet) und aus einem etwaigen Shutdown-Fall erst recht. Die einzige
Ausnahme ist der Fall, in dem **beweisbar kein Nachfolger existieren kann** —
und das ist genau R1: ist das Paket fort, gibt es keine APK mehr, aus der ein
`app_process` starten koennte.

**Was ausdruecklich nicht geloescht wird:** alles, was nicht auf die Namensform
passt. Das Helper-Log nicht (siehe AD-012 und die Inode-Regel oben) — ausser im
R1-Fall, wo es umgekehrt richtig ist. `btperf` nicht — das gehoert den Messwerkzeugen,
nicht dem Produkt. Ein privilegierter Prozess, der ein Verzeichnis pauschal
leerraeumt, ist ein groesserer Fehler als der, den er beheben soll.

**Restrisiken, benannt statt wegdefiniert:**

- **R1 — App deinstalliert. Meine erste Fassung war falsch, und zwar am Code
  nachweisbar (SR-016).** Ich hatte geschrieben: „nach der Deinstallation
  laeuft nichts von uns mehr". Das stimmt nicht. Der Helper ist ein **eigener
  Prozess als Uid 2000**; die Deinstallation beendet ihn nicht. Sein
  Reconnect-Thread in `PrivilegedServer.watchApp` pollt danach `/proc` alle
  3–15 s auf eine Uid, die es nicht mehr gibt — unbegrenzt, bis zum Neustart.
  Im Regelfall existiert also genau die Instanz, die aufraeumen koennte.
  Schlimmer, und das ist der eigentliche Befund: ein privilegierter Prozess mit
  Binder und geladenem Code **aus einer entfernten APK** ueberlebt die App
  unbegrenzt und hat keine Beendigungsbedingung.

  **Behebung:** Die Abbruchbedingung des Reconnect-Threads wird von
  *Prozess-Existenz* auf **Paket-Existenz** umgestellt. Ist das Paket fort,
  raeumt der Helper auf — Spill-Reste **und** das Log, hier nach der
  Inode-Regel korrekt, weil ohne APK kein Nachfolger mehr entstehen kann — und
  beendet sich. Kein neuer periodischer Weg: der Poll laeuft ohnehin. Die
  Paketabfrage ist ein Binder-Aufruf und gehoert deshalb **nicht** in jede
  Iteration, sondern erst an die Ruecklaufsperre (`RECONNECT_POLL_MAX_MS`,
  15 s), und nur solange die App abwesend ist.

  **Was uebrig bleibt und wirklich unloesbar ist:** ein **Neustart** zwischen
  dem letzten Helperlauf und der Deinstallation. Dann ist der Helper vorher
  gestorben, niemand kann die Bedingung mehr auswerten, und Android kennt
  keinen Haken, der im entfernten Paket noch feuert
  (`ACTION_PACKAGE_FULLY_REMOVED` geht an *andere* Apps). Dafuer bleibt der
  einmalige Handgriff per adb — ein deutlich kleinerer Rest als der, den ich
  zuerst als unloesbar ausgegeben hatte.
- **R2 — Geraete, auf denen nie ein neuer Helper startet.** Wie der Rest von R1.
- **R3 — Die drei heutigen Reste.** Loeschen ist eine Nutzerentscheidung
  (`security/findings.md`, Sofortmassnahme). Sie ist erst **nach** AD-010 und
  AD-012 dauerhaft: davor legt der naechste Lauf sie wieder an.
- **R4 — Wer die Daten bereits kopiert hat, hat sie.** Nicht ruecknehmbar.
- **R5 — Verzeichnisrechte von `/data/local/tmp`: nachgemessen, Annahme
  bestaetigt.** `drwxrwx--x shell:shell` (0771, Director-Nachmessung 01.09.).
  Eine fremde App kann dort weder auflisten noch Eintraege anlegen oder
  entlinken — Symlink-Unterschieben ist damit ausgeschlossen und die Frage
  geschlossen. Sie kann aber **traversieren** (`--x`) und eine Datei bei
  **exakt bekanntem Namen** oeffnen, und dieser Name ist eine Konstante in
  einer sideloadbaren APK. Die Angriffsflaeche ist damit praezise begrenzt,
  nicht kleiner — SR-001 gilt unveraendert, und SR-013 folgt direkt daraus.

#### Nachtrag: „Beenden und aufraeumen" als sichtbare Nutzeraktion (App Designer, 2026-09-01)

Der App Designer hat die offene Frage entschieden: **ja**. Die App bekommt eine
sichtbare Aktion, mit der der Nutzer den Helper beendet und die Reste entfernt.
Was sie technisch leisten muss und welche Zusicherung sie geben darf, steht
hier; **ob und wo sie in der Oberflaeche auftaucht, entscheidet der
`ui-ux-designer`** — dieser Abschnitt schreibt ihm keine Darstellung vor.

**Rang:** Absicherung, nicht Hauptweg. Der Entwurf darf sich nicht darauf
verlassen, dass der Nutzer sie vor einer Deinstallation findet; der Regelfall
bleibt Stelle 2 (Paket fort, automatisch). Daraus folgt unmittelbar: die Aktion
darf **nicht** automatisch beim App-Start mitlaufen — sonst waere sie doch der
Hauptweg, und zwar ein schlechterer.

**Keine neue Binder-Flaeche.** Das Aufraeumen wird dem vorhandenen
`shutdown(token)` vorangestellt, statt eine Methode danebenzustellen. Eine
neue Methode waere zusaetzliche Flaeche auf einem privilegierten Binder, sie
fiele unter A4 und muesste in Version 6 mitgetragen werden — fuer eine
Reihenfolge, die man auch hinter der bestehenden Tuer herstellen kann. `exec`
in AD-013 an Ort und Stelle zu erweitern und hier eine Methode hinzuzufuegen
waere zwei Maessen an einem Tag.

**Die Inode-Regel wird pruefbar statt hoffend.** Bisher stand sie als Verbot da
(„kein Helper loescht je einen Pfad, den ein Nachfolger bereits geoeffnet haben
kann"). Sie laesst sich strukturell absichern: der Helper vergleicht vor dem
Loeschen `(st_dev, st_ino)` seines **eigenen stdout-Deskriptors** mit dem, was
unter `HELPER_LOG_PATH` liegt (`Os.fstat` / `Os.stat`, oeffentliche API).
Stimmen beide ueberein, ist es sein eigenes Log und er darf es entfernen;
weichen sie ab, hat jemand anderes den Pfad bereits neu angelegt und er ruehrt
ihn nicht an. Damit gilt die Regel auch dann, wenn ein Auto-Start zufaellig
gleichzeitig laeuft — sie haengt nicht mehr daran, dass niemand einen
ungluecklichen Moment erwischt. Gleiche Pruefung auf Stelle 2.

**Was die Aktion zusichern darf — und was nicht.** Sie meldet **nicht**, was
der Helper getan zu haben behauptet, sondern was die App **danach nachgemessen
hat**. Das ist dieselbe Read-back-Regel, die im Ueberblick dieser Datei fuer
jede Aenderung gilt: eine angenommene Schreibung ist kein Beleg. `shutdown` ist
`void` und der Prozess toetet sich selbst, bevor die Transaktion zuruecklaeuft
— ein Rueckgabewert waere hier ohnehin nicht vertrauenswuerdig, und deshalb
wird auch keiner eingefuehrt. Drei Stufen, und die dritte ist die wichtige:

1. **Belegt — der Helper ist beendet.** Die App sieht den Binder-Tod ueber den
   vorhandenen Death-Recipient in `PrivilegedConnection`. Beobachtung, keine
   Annahme.
2. **Belegt — die Dateien mit bekanntem konstantem Namen sind fort.** Die App
   kann `/data/local/tmp` nicht auflisten (0771, kein `r`), aber sie kann
   **traversieren und bei exakt bekanntem Namen oeffnen** — genau die
   Faehigkeit, die SR-001 ueberhaupt erst zum Befund macht. Hier wird sie
   einmal nuetzlich: schlaegt das Oeffnen mit ENOENT fehl, ist die Datei
   wirklich weg, und das hat die App selbst festgestellt.
3. **Nicht belegbar — ob aeltere Builds weitere Dateien hinterlassen haben.**
   Die per-Aufruf-Namen der Vorversion (`btdash_exec_<variabel>.out`) kann die
   App weder erraten noch auflisten. Nach AK-3 ist das ein **"cannot check"**,
   kein Freispruch. Die Aktion sagt also "die bekannten Dateien sind entfernt",
   niemals "das Verzeichnis enthaelt nichts mehr von uns".

**Fehlerfaelle, ehrlich statt still:** Ist kein Helper verbunden, kann die App
gar nichts entfernen — sie kann nicht entlinken, das braucht Schreibrecht am
Verzeichnis. Dann meldet sie das, prueft die bekannten Namen trotzdem nach und
sagt, ob dort noch etwas liegt. Ein "erledigt" ohne beobachteten Binder-Tod und
ohne ENOENT gibt es nicht.

**Bauform:** eine Coroutine im Scope des Bildschirms, der sie ausgeloest hat —
dieselbe Gattung wie `DeviceDiagnosticRunner` (Verweis abgeloest durch T-046,
Klasse entfernt; die Bauform bleibt) und wie AD-003 es fuer den Scan
festgelegt hat. Kein Service, kein WorkManager, kein Timer. `btperf` fasst sie
nicht an (SR-012, fremdes Eigentum).

**Konsequenzen:** Leicht wird eine belegbare Aussage "das Verzeichnis enthaelt
nach einer Sitzung nichts von uns". Dauerhaft schwer bleibt jede Zusage ueber
den Zustand nach einer Deinstallation — die gibt es nicht, und sie wird auch
nicht behauptet. Die neue Aktion **verkleinert** den Restfall aus R1, sie hebt
ihn nicht auf: sie wirkt nur, wenn der Nutzer sie vor der Deinstallation
benutzt, und genau darauf darf sich der Entwurf nicht verlassen.

**Umkehrbarkeit:** leicht.

---

### AD-012 — Das Helper-Log bleibt, wo es ist, und hoert auf, fuer alle da zu sein (2026-09-01, Status: aktiv)

**Kontext:** SR-009, am 01.09. auf **hoch** hochgestuft. `btdash_helper.log`
liegt mit 0666 im selben Verzeichnis und ueberlebt die Deinstallation. Welt-
schreibbar heisst: jede App kann genau die Datei faelschen, die man bei einer
Fehlersuche liest. Der Helper schreibt dort, weil er als `shell` gestartet wird
und in das App-Datenverzeichnis nicht schreiben darf.

**Optionen:**
A. **Ins App-Datenverzeichnis verlegen.** Konsequenz: geht nicht — Uid und
SELinux verbieten es dem Helper. Am selben Punkt gescheitert wie der Socket.
B. **Ueber den Binder an die App melden statt in eine Datei.** Konsequenz:
faellt genau dann aus, wenn man es braucht. Die Daseinsberechtigung des Logs
ist die Frage "warum ist der Helper nie bis zur Uebergabe gekommen" — in dem
Moment gibt es keinen Binder. Das hat schon einmal eine ganze Fehlersuche
gekostet (KDoc `shellCommand`).
C. **Nach logcat statt in die Datei.** Konsequenz: die frueheste Klasse von
Fehlern geht verloren. Was `app_process` vor unserem ersten Codepfad ausgibt
(VM-Start, fehlende Klasse), geht nach stderr, nicht nach logcat — und das ist
die Ausgabe, die den Fall erklaert, in dem der Helper 36 ms nach dem Start
starb. Zusaetzlich ist logcat ein Ringpuffer.
D. **Ort behalten, Exposition entfernen.**

**Entscheidung:** D, zweistufig, und die beiden Stufen tragen unterschiedlich
schwer:

1. **Strukturell:** Der Helper verengt beim Start den Modus seines eigenen Logs
   auf Eigentuemer-only (`setReadable(false, false)`/`setWritable(false, false)`
   gefolgt von `setReadable(true, true)`/`setWritable(true, true)` — die
   Reihenfolge ist noetig, weil die Java-API fremde Rechte nur ueber den
   `ownerOnly=false`-Aufruf *entfernen* kann). Das gilt unabhaengig davon,
   welchen Befehl der Nutzer eingefuegt hat, und deckt alles ab, was nach dem
   Helper-Start ins Log geht.
2. **Konvention:** Der ADB-Befehl in `PrivilegedBootstrap.shellCommand` beginnt
   mit `umask 077; rm -f <log>;`. Das `rm -f` ist noetig, weil ein Redirect eine
   vorhandene Datei nur kuerzt und ihren Modus behaelt — ohne das bliebe ein
   altes 0666-Log fuer immer 0666. Konvention deshalb, weil sie am eingefuegten
   Befehl haengt: wer eine alte Kopie aus der Zwischenablage benutzt, bekommt
   das Fenster zwischen Redirect und Helper-Start weiterhin offen. Stufe 1
   schliesst es, sobald der Helper laeuft.

Das `umask 077` traegt weiter als das Log: es wird vom Helper-Prozess geerbt
und macht **alles**, was er je anlegt, standardmaessig 0600. Java kann die
umask nicht selbst setzen (`android.system.Os` bietet sie nicht an), der
startende Befehl schon.

#### Nachtrag nach dem Sicherheitsreview (2026-09-01)

**SR-017, erste Korrektur — „ein Hebel, alle drei Befunde" war zu gross
gegriffen. Es sind zwei.** `umask 077` steht in `shellCommand()` und erreicht
`docs/perf/tools/*.sh` nicht; die Skripte bringen kein eigenes mit. SR-012
(`btperf`, 117 Dateien, 13 MB Roh-Dumps) bleibt vollstaendig ausserhalb dieser
Entscheidung und ausserhalb von T-006 — es ist kein Anwendungscode. Wer
SR-001/SR-009 abnimmt, hat SR-012 nicht mit abgenommen.

**SR-017, zweite Korrektur — an einer Stelle war ich zu bescheiden.** Auf dem
**Auto-Start-Pfad** (die App startet den Helper ueber den ADB-Loopback selbst)
ist `umask 077` **strukturell getragen**, nicht Konvention: `shellCommand()` ist
die einzige Quelle beider Startwege, und auf diesem Weg fasst der Nutzer den
Befehl nie an. Konvention bleibt nur der **Kopier-Pfad** — eine alte Zeile aus
der Zwischenablage —, und dessen Fenster schliesst Stufe 1, sobald der Helper
laeuft.

**SR-017, dritte Korrektur — die Aussage „alles 0600" braucht einen Test.**
Eine umask wirkt nur, solange niemand hinterher explizit `ownerOnly = false`
setzt; genau ein solcher Aufruf (`file.setReadable(true, false)` in
`ExecSpill.stage`) hebelt sie heute aus. Auflage A15 verlangt einen Test, der
`ownerOnly = false` im Helper-Code verbietet. Der ist ab U-4 auch erfuellbar:
mit `ExecSpill` verschwindet die einzige Stelle, die das tut.

**A10 — Reihenfolge: `umask 077` wird nicht vorgezogen.** Uebernommen. Die
Begruendung des Reviews korrigiere ich allerdings, weil sie so nicht am Code
haelt: befuerchtet wird ein Totalausfall der Live-Ansichten, weil die
Spill-Datei fuer die App unlesbar wuerde. Das traete nicht ein.
`ExecSpill.stage` ruft **nach** dem Anlegen `setReadable(true, false)` — also
`chmod a+r` — und stellt die Lesbarkeit damit wieder her. Der tatsaechliche
Effekt eines vorgezogenen `umask 077` waere 0644 statt 0666: die
**Schreib**haelfte von SR-001 und SR-009 waere geschlossen, die Lesehaelfte
unveraendert, kein Ausfall. Das ist derselbe Mechanismus, der gerade SR-017
(dritte Korrektur) begruendet — er kann nicht in der einen Richtung wirken und
in der anderen nicht.

Ich folge A10 trotzdem, aus einem anderen Grund: Version 6 soll genau einen
Zustand herstellen und nicht einen dritten, halben. Ein Zwischenstand 0644, den
niemand je abnimmt, waere ein weiterer Befundtext in `findings.md` und kein
Fortschritt. Zusammen mit A9 (v6 traegt AD-010, AD-011 **und** AD-012) ist die
Reihenfolge damit ohnehin erledigt.

Diagnose kostet das nichts: der einzige berechtigte Leser ist `adb`, und der
**ist** `shell`. Ein 0600-Log gehoert ihm.

**Konsequenzen:** Leicht wird die Aussage, dass das Log weder mitgelesen noch
gefaelscht werden kann. Dauerhaft schwer wird das Auslesen des Logs aus der
App heraus — das war nie moeglich und soll es nicht werden. Restrisiko: nach
der Deinstallation bleibt ein 0600-Log liegen (R1 aus AD-011); lesen kann es
dann nur, wer adb hat, also dieselbe Partei, die es angelegt hat. Es wird
**nicht** in `shutdown()` geloescht — siehe die Inode-Falle in AD-011.

**Umkehrbarkeit:** leicht.

---

### AD-013 — Zwei Versionsspruenge statt einem: 6 ist der Transport, 7 ist `wifiFacts` (2026-09-01, Status: aktiv — **braucht Bestaetigung des `director`**)

**Kontext:** AD-005 hat `wifiFacts` als Helper-Version 6 vorgesehen. AD-010
aendert dasselbe Protokoll. T-006 fragt, ob beide in einen Sprung gehoeren.
Lage: AD-010 behebt einen bestaetigten Befund hoher Prioritaet, AD-005 ist vom
`director` nur **mit Auflagen und vorbehaltlich Nutzerentscheidungen**
freigegeben und noch nicht gebaut.

**Optionen:**
A. Beides als 6 buendeln. Konsequenz: die Sicherheitsbehebung wartet auf ein
Feature, das noch auf Antworten des Nutzers wartet. Der Preis eines gesparten
Zaehlerstands ist eine offene 0666-Datei bis dahin.
B. Transport wird 6, `wifiFacts` wird 7. Konsequenz: zwei Sprunge.
C. AIDL-Platz fuer `wifiFacts` vorab reservieren. Konsequenz: geht nicht — ein
Transaktionscode entsteht nur durch eine deklarierte Methode, und eine leere
Methode auf einer privilegierten Flaeche ist schlimmer als ein zweiter Sprung.

**Entscheidung:** B. Der Sprung kostet hier naemlich fast nichts, und das ist
der Punkt: **Versionen werden in diesem Projekt nicht verhandelt, sie werden
verweigert.** `PrivilegedProvider` lehnt jede Uebergabe ab, deren Version nicht
exakt der erwarteten entspricht, und zwar *bevor* der Token rotiert. Damit
gibt es keinen Mischbetrieb, den man vertraeglich halten muesste:

| | Helper v5 | Helper v6 | Helper v7 |
|---|---|---|---|
| App v6 | abgelehnt ("helper is version 5, this app expects 6") | bedient | abgelehnt |
| App v7 | abgelehnt | abgelehnt | bedient |

Der Nutzer sieht in jedem Fehlfall dieselbe vorhandene Meldung und tut
dieselbe vorhandene Sache: den ADB-Befehl neu ausfuehren. Ein zweiter Sprung
kostet also eine KDoc-Zeile, kein Verhalten. Anders als bei 4 → 5 gibt es
diesmal auch kein stilles Restfenster: 6 fuehrt eine geaenderte Signatur ein,
ein v5-Helper wuerde sie gar nicht beantworten — und wird vorher abgelehnt.

**Reihenfolgeregel, damit das nicht ausufert:** Wer zuerst liefert, bekommt den
niedrigeren Transaktionscode; jede neue Methode geht ans Ende (Auflage A4 /
SR-010). `PrivilegedProtocolTest` erzwingt weiterhin, dass AIDL und
`PrivilegedOperation` uebereinstimmen.

**Eine Abweichung von Auflage A4, bewusst und offen:** `exec` bekommt den
Deskriptor als zusaetzlichen Parameter **an Ort und Stelle**, statt daneben ein
`execStream` zu stellen. A4 verlangt neue Methoden am Ende, um
Transaktionscodes nicht zu verschieben — hier gibt es aber keinen
Mischbetrieb, in dem sich Codes verschieben koennten (Tabelle oben), und die
Alternative hiesse, den alten `String exec(...)` als tote, aber aufrufbare
Flaeche stehen zu lassen: genau den Pfad, der 222 KB inline schickt und die
Datei anlegt. Eine erreichbare tote Methode auf einem privilegierten Binder ist
das groessere Uebel.

**Genehmigt (Director, 2026-09-01), A4 begrenzt aufgehoben**, an drei
Bedingungen — und die erste schliesst eine Luecke, die ich nicht gesehen habe:

- **A6:** Ein Test erzwingt **genau eine** `exec`-Methode, keine Ueberladung.
  Noetig, weil `PrivilegedProtocolTest` heute ueber *Namen* reflektiert und eine
  zweite Signatur gleichen Namens anstandslos durchwinken wuerde — die alte,
  inline antwortende `exec` koennte also unbemerkt stehenbleiben, und genau sie
  ist der Befund.
- **A7:** Signatur und Versionssprung liegen in **einem** Commit. Ein Zustand,
  in dem die Signatur neu und die Version alt ist, waere ein Helper, den der
  Provider nicht ablehnt, obwohl er anders spricht.
- **A8:** Der Wortlaut der Ablehnmeldung des Providers bleibt unveraendert —
  sie ist die einzige Zeile, die dem Nutzer sagt, was zu tun ist.

**Fuer neue Methoden gilt A4 unveraendert weiter:** `wifiFacts` geht in
Version 7 ans Ende der Schnittstelle.

**Konsequenzen:** Leicht wird, die Behebung sofort zu liefern. Dauerhaft schwer
wird nichts — ausser dass die VERSION-Historie im KDoc zwei Zeilen statt einer
bekommt.

**Umkehrbarkeit:** leicht.

---

### AD-014 — Dieser Umbau bekommt eine eigene Schrittfolge `U-0..U-6` (2026-09-01, Status: aktiv)

**Kontext:** SR-022. Ich hatte die Umsetzungsschritte `S-1..S-7` genannt. Diese
Namen sind vergeben: `S-1..S-7` gehoeren zum Scan-Entwurf
(`docs/scan/T-005-ENTWURF.md`), wo `S-3` `wifiFacts` und `S-6` die
Leih-Experimente sind. Eine Auflage „vor S-2" haette damit zwei Bedeutungen
gehabt — und Auflagen sind das Einzige, was zwischen einer Freigabe und einem
Befund steht.

**Optionen:**
A. Bei `S-` bleiben und hochzaehlen (`S-8` ff.). Konsequenz: eine Folge, zwei
Vorhaben, und die Nummer sagt nicht mehr, wozu sie gehoert.
B. Eigener Praefix je Vorhaben.
C. Auflagen an AD-Nummern statt an Schritte haengen. Konsequenz: eine AD ist
eine Entscheidung, kein Zeitpunkt — „vor AD-010" ergibt keinen Sinn.

**Entscheidung:** B, mit dem Vorschlag des Reviewers:

| Schritt | Inhalt | Auflagen, die hier haengen |
|---|---|---|
| **U-0** | Geraete-Spike: Bauformen `3' → 4 → 1 → 2 → 3` | A11, A12 |
| **U-1** | Protokoll ohne Pfad, mit `byteCount`; AIDL-Signatur | A6, A7, A13 |
| **U-2** | Helper-Schreibseite | A14 |
| **U-3** | Client-Leseseite | A8 |
| **U-4** | Aufraeumklasse, Paket-Existenz als Abbruchbedingung, Nutzeraktion „Beenden und aufraeumen" | — |
| **U-5** | Log-Verengung + `umask 077` | A10, A15 |
| **U-6** | Geraete-Retest | **A16**; A5 faellt hier, nicht mit der Auslieferung |

Regel fuer die Zukunft: **jedes Vorhaben mit eigener Schrittfolge bekommt einen
eigenen Praefix.** `S-` bleibt beim Scan, `U-` gehoert diesem Umbau.

**Nachtrag zum Abnahmekriterium von U-6 (`security-reviewer`, 2026-09-01):**
Der Retest haengt **nicht am Dateimodus**, sondern daran, dass nach einer
Live-Sitzung **keine Datei dieser Form mehr existiert**. Das folgt aus der
A10-Korrektur in AD-012: ein `stat`, das 0644 zeigt, ist exakt die dort
verworfene Option A — es sieht in einem Bericht wie eine Behebung aus und ist
keine. Ein Retest, der Modi prueft, koennte also einen Zustand abnehmen, den
dieses Dokument ausdruecklich ablehnt. Gemessen wird Abwesenheit, nicht
Freundlichkeit der Rechte.

**Konsequenzen:** Leicht wird, eine Auflage eindeutig zu verankern. Dauerhaft
schwer wird nichts; der Preis ist ein Buchstabe.

**Umkehrbarkeit:** leicht.

---

### AD-015 — Die Verlustmechanik ist reine Logik in `:core-monitor`; gefaltet wird im ViewModel (2026-09-02, Status: aktiv)

**Kontext:** T-021 hat belegt, dass die T-002/T-009-Mechanik im Code nicht
existiert: keine der Parameterkonstanten, kein `SETTLING`, keine
Zustandsmaschine. Die heutige `LossRow` rechnet eine rohe Poll-zu-Poll-Differenz.
Rund 25 Akzeptanzkriterien sind damit gegenstandslos. Die Maschine traegt
fachliche Regeln (Schwellen, Coverage, Hysterese, Episoden), braucht aber
Historie und muss ohne Geraet testbar sein.

**Kraefte:** Die Regeln gehoeren dorthin, wo sie ohne Android geprueft werden
koennen; die Woerter gehoeren dorthin, wo Plural und Satzbau leben (`:app`, so
begruendet im KDoc von `TxLossChannel`); das Fenster darf nicht laenger leben
als der Bildschirm, der es fuellt.

**Optionen:**
A. Alles in `:app` neben `LiveTraceModel.kt`. Konsequenz: die Urteilsrechnung
landet im Modul mit dem meisten Android und dem wenigsten Testkomfort, und die
Schwellen stuenden neben den Saetzen, die sie beschreiben — genau die
Vermischung, die `TxLossChannel` aufloest.
B. Alles in `:core-monitor`, inklusive der Faltung, gehalten in
`LiveLinkSource` neben `MeasuredBitrateTracker`. Konsequenz: ein Produzent fuer
Zustand und Ereignisse — aber der Zustand haengt dann am prozessweiten
Singleton `MonitorGraph.liveLink`, ueberlebt das Schliessen des Bildschirms und
haelt Punkte aus einer Zeit, in der niemand gemessen hat. Genau das lehnt der
Bestand fuer die Graphen ausdruecklich ab („Both windows are rebuilt rather than
persisted", `MonitorViewModel`). Zusaetzlich teilen sich zwei moegliche
Poll-Schleifen (`liveLinkUpdates` und `updates(interval)`) dieselbe Instanz;
`MeasuredBitrateTracker` traegt diese Gefahr heute schon und sie ist dort als
Wart benannt.
C. Regeln und Datentypen in `:core-monitor` als **reine, unveraenderliche
Faltung**; die Faltung selbst laeuft im `MonitorViewModel` ueber denselben
`liveUpdates`-Fluss, aus dem heute `overviewTrace` entsteht. Konsequenz: die
Lebensdauer des Fensters ist die des Bildschirms, ohne dass jemand sie
verwalten muss; die Regeln sind mit einer Liste von Lesungen testbar; der
Umschaltmarker aus `LdacTuning` (D-7) ist im ViewModel ohne neuen Port zu
haben — `LdacTuning` liegt in `:app` und `:core-monitor` darf es nicht kennen.

**Entscheidung:** C. Neues Paket
`core-monitor/.../monitor/link/live/verdict/`, kein neues Gradle-Modul
(dieselbe Begruendung wie AD-002), keine neue Abhaengigkeit, keine neue
Bibliothek. Abhaengigkeitsrichtung unveraendert: `:app -> :core-monitor`, und
die Maschine kennt weder Compose noch Android.

**Konsequenzen:** Leicht wird, jedes der ~25 Kriterien als Unit-Test ueber eine
Folge erfundener Lesungen zu schreiben, ohne Geraet und ohne Robolectric.
Leicht wird auch, die Woerter zu erzwingen: die Zustands- und Grundtypen sind
Bezeichner, die `when`-Ausdruecke in `:app` sind erschoepfend — ein neuer
Zustand kompiliert nicht, bevor die Oberflaeche einen Satz dafuer hat. Dauerhaft
schwer wird, den Zustand einem zweiten Bildschirm zu geben: der muesste ein
zweites Mal falten. Solange es einen Monitor-Bildschirm gibt, ist das kein
Preis; kaeme ein zweiter, waere die Faltung nach `MonitorGraph` zu heben, und
dann gilt Option B samt ihrer Lebensdauer-Frage.

**Umkehrbarkeit:** leicht — die Faltung ist eine Funktion; sie an einem anderen
Ort aufzurufen ist ein Umzug von Aufrufstellen, kein Datenmodell.

---

### AD-016 — Die Maschine bekommt keine Uhr; die Zeit reist in den Lesungen (2026-09-02, Status: aktiv)

**Kontext:** Ein Fenster ueber `LOSS_WINDOW_MS`, ein Halten ueber
`LOSS_CLEAR_HOLD_MS`, eine Karenz ueber `SETTLE_AFTER_TRANSITION_MS` und ein
Deckel ueber `SETTLE_MAX_SPAN_MS` brauchen Zeit. Es gibt kein Geraet; alles
laeuft ueber Unit-Tests und Robolectric.

**Wie der Bestand es loest — zweistufig:** Die Poll-Schleifen bekommen eine
einspeisbare Uhr (`LiveLinkSource(clock = System::currentTimeMillis)`,
`A2dpTxProbe(clock = …)`), und sie **stempeln damit die Lesung**
(`TxProbeReading.timestampMs`, `LinkLiveSnapshot.timestampMs`). Alles, was
danach rechnet, bekommt gar keine Uhr mehr, sondern liest die Stempel aus den
Daten: `A2dpTxProbe.sampleBetween(previous, current)` ist genau deshalb rein und
oeffentlich, `MeasuredBitrateTracker.onReading(timestampMs, …)` ebenso, und
`LiveTrace` schneidet sein Fenster gegen `newestMs` statt gegen die Wanduhr.

**Optionen:**
A. Eigene `clock: () -> Long` im Konstruktor der Maschine, wie in den
Poll-Schleifen. Konsequenz: sieht nach demselben Muster aus, ist aber die
**zweite** Stufe — der Zustand koennte sich zwischen zwei Lesungen aendern und
damit ueber Zeit urteilen, in der nichts gemessen wurde. Das widerspricht D-2
(„Nenner ist die Summe der gemessenen `windowMs`"), D-8 und D-12.
B. Virtuelle Zeit ueber `TestScope`/`kotlinx-coroutines-test` in den Tests.
Konsequenz: ein zweites Zeitmuster im Projekt, und die Produktion behielte die
Wanduhr — die Tests wuerden etwas anderes pruefen als das, was laeuft.
C. Keine Uhr. Jede Frist wird gegen den Zeitstempel der **neuesten Lesung**
ausgewertet.

**Entscheidung:** C.

**Warum das nicht zu einem eingefrorenen Freispruch fuehrt** — mit der
Bedingung, unter der die Aussage geprueft ist: `LiveLinkSource.readPass`
liefert auch dann eine Lesung, wenn die Helfer-Identitaet fehlt (Snapshot mit
`warnings`, `tx = null`). Eine sterbende Strecke erzeugt also weiter Lesungen,
und die Maschine faellt an einer Lesung auf `CANNOT_TELL`, nicht an einer
Zeitschranke. Stillstehen kann die Faltung nur, wenn die Poll-Schleife steht —
und die steht nur, wenn der Bildschirm weg ist und niemand hinsieht. Fuer
`readOnce` gilt das **nicht**: die Maschine wird ausschliesslich aus dem
Poll-Fluss gefuettert, dieselbe Trennung, die `EncoderStarvationTripwire` aus
demselben Grund hat (`CodecModeCalibrator` verhandelt absichtlich neu).

**Konsequenzen:** Leicht wird der Test: eine Liste von Lesungen mit erfundenen
Zeitstempeln hinein, eine Liste von Zustaenden heraus, ohne Nebenlaeufigkeit und
ohne Scheduler. Leicht wird auch die Ehrlichkeit: ueber ungemessene Zeit kann
die Maschine nichts sagen, weil sie sie nicht sieht. Dauerhaft schwer wird eine
Frist, die **ohne** Lesung ablaufen soll: sie feuert erst an der naechsten
Lesung, also bis zu ein Poll-Intervall (1–5 s) spaet. Bei `LOSS_CLEAR_HOLD_MS`
(35 s) und `SETTLE_AFTER_TRANSITION_MS` (20 s) sind das unter 25 % der Frist,
und in der Anzeige ist es nicht bemerkbar, weil sie sich ohnehin nur an
Lesungen aendert.

**Umkehrbarkeit:** mittel — eine Uhr nachtraeglich hineinzureichen ist billig,
aber jede Frist, die dann ueber ungemessene Zeit liefe, muesste einzeln gegen
D-2/D-8/D-12 geprueft werden.

---

### AD-017 — Ein Fenster, in `:core-monitor`; der Ueberblicks-`LiveTrace` wird daraus projiziert (2026-09-02, Status: aktiv)

**Kontext:** `LiveTrace` (`:app`, `LiveTraceModel.kt`) haelt bereits 60 s
`TracePoint`s, kennt „nicht gemessen" gegen „null gemessen"
(`lossCount: Long?`), zaehlt `measuredWindowCount`/`unmeasuredWindowCount` und
den Warteschlangenanteil. Die Maschine braucht dasselbe Fenster — plus je Kanal
getrennte Zaehler, die gemessene Spanne je Lesung, Coverage, den Umschaltmarker
und die Stufenfelder (D-2, D-9, D-10).

**Kraefte:** Doppelte Fensterhaltung ist Debt — und zwar genau der Debt, den
QA-010 gerade teuer beseitigt hat, nur in der Zeitachse statt in der
Kanalliste: zwei Fenster haetten zwei Nenner, und die Bildunterschrift („{k} of
{n} windows") koennte dem Verdikt widersprechen, ohne dass ein Test das merkt.
Ein zweckentfremdeter Trace waere ebenso Debt: `TracePoint.lossCount`
**summiert** die Kanaele, und eine Summe darf nach AK-T002-8 und R-D nie
Grundlage eines Verdikts sein. `LiveTrace` kann die Maschine also nicht
fuettern — und die Maschine kann `LiveTrace` nicht lesen, weil `:core-monitor`
`:app` nicht kennt.

**Optionen:**
A. Die Maschine bekommt einen eigenen Ring, `LiveTrace` bleibt wie er ist.
Konsequenz: zwei Ringe ueber dieselben Lesungen, zwei Nenner, zwei
Trimm-Regeln. Billig heute, und der Fehlertyp von QA-010 kehrt zurueck.
B. `LiveTrace` nach `:core-monitor` umziehen und die Maschine daraus speisen.
Konsequenz: greift in den Graphen ein, der als eigener Schritt (G-1..G-8)
ohnehin umgebaut wird — zwei Umbauten derselben Datei nacheinander.
C. Der Ring wird `LossWindow` in `:core-monitor` und ist der einzige;
`LiveTrace` fuer den **Ueberblick** wird bei jeder Emission daraus
**projiziert** (`window.toOverviewTrace(expectedIntervalMs)`), Form und
Zeichenpfad unveraendert. `LiveTrace.plus/append` bleibt fuer die
**Nahaufnahme**, die ein anderer Kanal mit einem anderen Gegenstand ist (10 s,
nur Stack-Zaehler, `A2dpTxProbe`) und der Maschine bewusst nicht zugefuehrt
wird — ihre Coverage ist konstruktionsbedingt `PARTIAL`.

**Entscheidung:** C. `LOSS_WINDOW_MS` wird in `:core-monitor` definiert und
`LiveTrace.OVERVIEW_WINDOW_MS` daraus abgeleitet — eine Zahl, ein Ort. Dass
Zeile, Graph und Verlustfenster dasselbe Fenster meinen, ist seit T-009
(Selbstkorrektur eins) Voraussetzung dafuer, dass man sie nebeneinander lesen
darf, und keine Kosmetik mehr.

**Konsequenzen:** Leicht wird, dass Bildunterschrift und Verdikt nie
auseinanderlaufen koennen — sie zaehlen dieselben Lesungen. Leicht wird auch der
Uebergang zum Graphen-Schritt: G-1..G-8 brauchen Stufenverlauf und ABR-Zaehler
je Lesung, und beides liegt dann schon im Fenster. Dauerhaft schwer wird nichts,
aber ein Pruefpunkt bleibt: die Projektion muss dieselben Zahlen liefern wie die
heutige Akkumulation (`lossWindowCount`, `measuredWindowCount`,
`unmeasuredWindowCount`, `queuePressureFraction`, `breakBefore`) — ein
Regressionstest ueber eine feste Lesungsfolge, kein Augenschein.

**Umkehrbarkeit:** mittel — die Projektion zurueckzunehmen heisst, die
Akkumulation im ViewModel wiederherzustellen; die Zeichenschicht wird nicht
angefasst.

---

### AD-018 — `TxLossChannel` bleibt der Zaehlersatz des Stacks; `LossChannel` ist die gezeigte Fuenfermenge (2026-09-02, Status: aktiv)

**Kontext:** Die Anzeige kennt fuenf Kanaele (App-Underruns, Mixer-Underruns,
dropped packets, stack dropouts, encoder underflows). `TxLossChannel` kennt
zwei — die beiden, die dem `A2dpTxDelta` gehoeren und ein Verdikt tragen
duerfen. Diese Kopplung („ein Kanal kompiliert nicht, bevor die Oberflaeche ein
Wort dafuer hat") ist der teuer erkaufte Fix aus T-018/QA-010 und wird nicht
angefasst.

**Optionen:**
A. `TxLossChannel` auf fuenf erweitern. Konsequenz: der Typ verspraeche, die
Zaehler des Bluetooth-Stacks zu benennen, und truege dann zwei, die aus
`media.audio_flinger` kommen. Und die dokumentierte Zusage von `lossByChannel` —
„jeder Kanal steht mit seinem Wert da, Null eingeschlossen" — waere fuer drei
Eintraege falsch; genau die Verwechslung „zaehlte nichts" gegen „wird nicht
gefragt", die der Typ verhindern soll.
B. Eine zweite Aufzaehlung ohne Verbindung zur ersten. Konsequenz: zwei
handgepflegte Listen fuer „was ist ein Kanal" — QA-010 noch einmal.
C. `LossChannel` (fuenf Werte) als die **geurteilte und gezeigte** Menge, plus
**eine totale Abbildung** `TxLossChannel.judged(): LossChannel` als
erschoepfendes `when`. Ein neuer Stack-Zaehler kompiliert damit nicht, bevor er
in der gezeigten Menge angekommen ist; ein Test haelt beide Richtungen gegen
`entries` — der Restfall, den der Director am 02.09. zu `lossByChannel` benannt
hat.

**Entscheidung:** C. Eingang der Maschine ist damit nicht `A2dpTxDelta` allein,
sondern ein Lesungssatz, in `:core-monitor` aus dem vorhandenen
`LinkLiveUpdate` gebaut:

```kotlin
data class LossReading(
    val timestampMs: Long,
    /** Gemessene Spanne dieser Lesung; null = keine vergleichbare Vorlesung. */
    val measuredMs: Long?,
    /** null je Kanal = in dieser Lesung nicht lesbar. Nie 0 als Ersatz. */
    val counts: Map<LossChannel, Long?>,
    val observability: LinkObservability,
    val helperAvailable: Boolean,
    val linkPresent: Boolean,
    /** U-6: nur Verbindung, Codec, Playback, gepinnter Modus. Nie eine ABR-Stufe. */
    val transition: LinkTransition?,
    /** D-7: ein angemeldeter Umschaltlauf der App laeuft. */
    val retuningAnnounced: Boolean,
    val measuredKbps: Int?,
    val isAdaptive: Boolean?,
    val abr: AbrFacts?,          // siehe AD-022
    val queueNotEmpty: Boolean?,
)
```

**Konsequenzen:** Leicht wird, dass „nicht lesbar" nie zu einer Null wird — der
Typ hat fuer beides verschiedene Werte, und die Rate je Kanal bekommt dadurch
ihren eigenen Nenner: ein Kanal, der die halbe Zeit blind war, teilt durch die
halbe Zeit, nicht durch das Fenster (D-2, AK-T002-9). Dauerhaft schwer wird,
einen sechsten Kanal aufzunehmen, ohne drei Stellen anzufassen — was gewollt
ist.

**Umkehrbarkeit:** leicht.

---

### AD-019 — Eine Schwelle ohne Messung existiert nicht; ein Kanal ohne Schwelle spricht kein Verdikt (2026-09-02, Status: aktiv)

**Kontext:** `UI_SPEC.md` setzt die meisten Parameter, laesst aber welche offen,
und die offenen sind nicht alle von derselben Art: fuer App- und
Mixer-Underruns ist **nie gemessen worden** (M-1), fuer `dropped` und
`underflows` gibt es **bewusst keine** Alarmschwelle (dasselbe Ereignis bzw.
kein gemessener gestoerter Wert). Beides als `null` zu fuehren, wuerde zwei
verschiedene Aussagen gleich aussehen lassen. AD-004 hat fuer genau diese Frage
schon entschieden: das Evidenzniveau gehoert ins Datenmodell.

**Optionen:**
A. Offene Werte mit einer plausiblen Zahl fuellen und `TODO` daneben.
Konsequenz: die Zahl wirkt, der Kommentar nicht — ein geratener Wert verstoesst
gegen AK-3 und gegen die bindende Vorgabe „uebernimm sie, erfinde keine".
B. Offene Werte als `Double?` fuehren. Konsequenz: eine Null-Referenz fuer zwei
verschiedene Gruende; die Anzeige kann nicht sagen, ob sie auf eine Messung
wartet oder ob es nie eine Schwelle geben wird.
C. Ein eigener Typ mit drei Faellen, nach dem Vorbild von AD-004:

```kotlin
sealed interface LossThreshold {
    /** Gesetzt, mit der Messung, auf der er ruht. */
    data class Measured(val ratePerMin: Double, val source: String) : LossThreshold
    /** Offen, mit der einen Messung, die ihn schliesst — TODO(M-x). */
    data class Open(val measurement: String) : LossThreshold
    /** Bewusst keine, mit dem Grund. Kein Verdikt aus diesem Kanal. */
    data class None(val reason: String) : LossThreshold
}
```

**Entscheidung:** C. Ein Kanal, dessen `NOTICE` nicht `Measured` ist, ist
**lesbar, aber nicht beurteilbar**: seine Zahlen erscheinen (zweite Ebene,
`GOAL.md` AK-2), er geht in **kein** Verdikt ein, und die Coverage nennt ihn.
Gibt es in einem Fenster **keinen** beurteilbaren Kanal, ist der Zustand
`CANNOT_TELL` und nie `CLEAN` (AD-020).

**Stand der Parameter, wie in `UI_SPEC.md` vorgefunden** — uebernommen, keiner
erfunden:

| Parameter | Stand |
|---|---|
| `LOSS_WINDOW_MS` = 60 000, `LOSS_CLEAR_HOLD_MS` = 35 000, `SETTLE_AFTER_TRANSITION_MS` = 20 000, `LOSS_EVENT_COOLDOWN_MS` = 600 000, `RATE_MIN_EVENTS_IN_WINDOW` = 10, `LOSS_ALERT_SUSTAINED_WINDOWS` = 2 (Mindestabstand `LOSS_WINDOW_MS / 4` = 15 s) | `Measured` |
| `LOSS_NOTICE_RATE_PER_MIN[dropped, dropouts]` = 1/min; `LOSS_CLEAR_RATE_PER_MIN[dieselben]` = 0 | `Measured` (T-011/M-5) |
| `LOSS_ALERT_RATE_PER_MIN[dropouts]` = 12/min | `Measured`, mit dem Vorbehalt der konfundierten Kalibrierpunkte im KDoc |
| `LOSS_ALERT_RATE_PER_MIN[dropped]`, `[underflows]` | `None` — dasselbe Ereignis bzw. kein gemessener gestoerter Wert |
| `LOSS_NOTICE/ALERT/CLEAR_RATE_PER_MIN[app underruns, mixer underruns]` | **`Open`, TODO(M-1)** |
| `SETTLE_MAX_SPAN_MS`, nicht angemeldeter Fall | **`Open`, TODO(M-8)**; angemeldeter Fall als Formel `SETTLE_AFTER_TRANSITION_MS × (N + 1)` |
| `LADDER_WINDOW_MS` = 60 000 (= `LOSS_WINDOW_MS`), `LADDER_SETTLING_MIN_DISTINCT_STEPS` = 3, `LADDER_SETTLING_SUBWINDOW_MS` = 20 000, `LADDER_REJECTED_MAX_READINGS` = 1, `LADDER_QUEUE_PRESSURE_FRACTION` = 0,20 | `Measured` |

**Konsequenzen:** Leicht wird AK-T009-31: ein Wert **kann** nicht ohne Herkunft
angelegt werden, weil der Konstruktor sie verlangt — statt einer Grep-Regel, die
heute vakuum-gruen ist (QA-012). Dauerhaft schwer wird, eine Schwelle mal eben
zu setzen; das ist der Zweck.

**Ein bewusster Widerspruch zu `UI_SPEC.md`, benannt:** Die T-009-Tabelle
„Verlustzeile — was sich gegenueber T-002 aendert" laesst `encoder underflows`
noch `OCCASIONAL` tragen. Der Director hat am 02.09. anders entschieden und der
Fix ist gebaut: **Underflow traegt kein Verdikt, in keiner Richtung.** Diese
Entscheidung folgt dem gebauten Stand; die Zeile in `UI_SPEC.md` gehoert
nachgezogen — Sache des `ui-ux-designer` bzw. des Directors, nicht meine.

**Umkehrbarkeit:** leicht — eine `Open`-Schwelle wird zu `Measured`, sobald die
Messung da ist; nichts anderes aendert sich.

---

### AD-020 — `CANNOT_TELL` ist ein tragender Zustand mit typisiertem Grund (2026-09-02, Status: aktiv)

**Kontext:** `GOAL.md` AK-3 verlangt „cannot check" statt eines falschen
Freispruchs. Ein `CANNOT_TELL`, das als Sonderfall von `CLEAN` gebaut ist, wird
frueher oder spaeter wie `CLEAN` behandelt — der heutige Code zeigt es:
`LossRow` faellt in den ruhigen Zweig, und die Coverage-Qualifikation steht
darunter statt darin.

**Optionen:**
A. Ein Bool `canTell` neben dem Verdikt. Konsequenz: zwei Felder, die gemeinsam
gelesen werden muessen, und jede Stelle, die es vergisst, spricht einen
Freispruch aus.
B. `CANNOT_TELL` als Wert einer Verdikt-Aufzaehlung. Konsequenz: besser, aber
der **Grund** bliebe eine Zeichenkette aus der Datenschicht, und die Vorgabe
verlangt fuer jeden Grund einen eigenen Satz.
C. Ein versiegelter Zustandstyp, in dem `CannotTell` einen typisierten Grund
traegt und `Clean` ohne mindestens einen gelesenen, beurteilbaren Kanal gar
nicht konstruierbar ist:

```kotlin
sealed interface LossState {
    data class Settling(val transitions: Int, val sinceMs: Long) : LossState
    data object Measuring : LossState
    data class CannotTell(val reason: CannotTellReason) : LossState
    data class Clean(val coverage: Coverage) : LossState
    data class Occasional(val channels: List<LossChannelReadout>) : LossState
    data class Disturbed(val channel: LossChannelReadout) : LossState
}

enum class CannotTellReason {
    OFFLOADED, NO_HELPER, NO_LINK, COUNTERS_RESET,
    SETTLING_SPAN_EXCEEDED, NO_JUDGEABLE_CHANNEL,
}
```

**Entscheidung:** C. Die Saetze zu den Gruenden stehen in `:app` in einem
erschoepfenden `when` — dieselbe Kopplung wie `TxLossChannel.singularLabel()`:
ein neuer Grund kompiliert nicht, bevor die Oberflaeche ihn ausspricht.

**Zwei Regeln, die dieser Typ traegt:**

- **Kein Ruecksprung nach `Clean` aus `CannotTell` ohne neue Messung.** Der Weg
  zurueck fuehrt immer ueber `Measuring` (U-5, AK-T009-37): nach dem Beenden des
  Helpers, nach einem Zaehler-Reset und nach einer gerissenen Umschaltspanne gibt
  es keine vergleichbare Lesung, und ohne die gibt es kein Urteil.
- **`NO_JUDGEABLE_CHANNEL` ist neu gegenueber `UI_SPEC.md` und folgt aus
  AD-019:** Coverage `NONE` ist nicht der einzige Fall, in dem nichts gesagt
  werden darf. Sind alle lesbaren Kanaele solche ohne gesetzte Schwelle, ist
  ebenfalls nichts beurteilt — „alle gelesenen Kanaele unter der Schwelle" waere
  dann eine Aussage ueber die leere Menge.

**Konsequenzen:** Leicht wird der Compose-Test ueber alle Zustaende
(AK-T002-15): der Typ zaehlt sie auf. Leicht wird auch die
Graustufen-Unterscheidung aus AK-T002-3, weil sie aus dem Typ folgt (`Clean`
ohne Pill, `CannotTell` mit) und nicht aus einer Regel im Kopf des Entwicklers.
Dauerhaft schwer wird, `CANNOT_TELL` versehentlich wie `CLEAN` zu behandeln — es
gibt keinen gemeinsamen Zweig.

**Umkehrbarkeit:** leicht.

---

### AD-021 — Zwei Maschinen nebeneinander, kein gemeinsamer Zustand, keine Zwischenstufe (2026-09-02, Status: aktiv)

**Kontext:** T-009 legt zwei Regime mit zwei Leitgroessen fest und verbietet
ausdruecklich eine gemeinsame Ampel: es gibt keine Messung, die „492 statt 660"
und „13 Aussetzer/min" vergleichbar machte. R-E verbietet zusaetzlich jede
Abstufung fuer Raten echt zwischen 0 und 12/min — dauerhaft, weil M-11 nicht
messbar ist und die zwei Kalibrierpunkte konfundiert sind.

**Optionen:**
A. Eine Maschine mit einem Schweregrad 0..1, aus dem die Oberflaeche Worte
waehlt. Konsequenz: die Interpolation zwischen zwei gemessenen Punkten waere im
Datenmodell verankert, und R-E waere nur noch eine Bitte an den Textautor.
B. Eine Maschine mit einem gemeinsamen Zustand fuer Stufe und Verlust.
Konsequenz: die verbotene gemeinsame Ampel, nur nicht so genannt.
C. Zwei unabhaengige Faltungen ueber dasselbe Fenster: `LossState` (AD-020) und
`LadderState`. Kein Typ, der beide zusammenfasst; das Rueckgabeobjekt haelt sie
nebeneinander und hat selbst kein Urteil.

**Entscheidung:** C, mit drei strukturellen Sperren gegen R-E-Verstoesse:

1. `LossState` hat **keinen** numerischen Schweregrad, kein `Comparable`, keine
   Ordnung ausser der Reihenfolge im Typ.
2. Eine Rate entsteht nur, wenn `k >= RATE_MIN_EVENTS_IN_WINDOW`; darunter
   traegt der Readout ein Alter — `LossChannelReadout` fuehrt `Rate` und `Age`
   als zwei Faelle, nicht als zwei optionale Felder derselben Klasse.
3. Zwischen `Occasional` und `Disturbed` gibt es keinen dritten Zustand und
   keine Funktion, die aus einer Rate einen Anteil, einen Prozentsatz oder eine
   Stufe macht.

**Namensregel, aus `UI_SPEC.md` uebernommen:** Der `SETTLING`-Zustand der
Verlustanzeige und der `LADDER_SETTLING`-Zustand der Stufenzeile duerfen im Code
nicht denselben Namen tragen. Die Verlustmaschine heisst `LossState.Settling`,
die Stufenmaschine `LadderState.MovingBetweenSteps` — die Vergleichbarkeit der
**Zaehler** nach einem Umschalten und die Unruhe der **Stufenleiter** sind
verschiedene Dinge, und eine ABR-Stufe loest weiterhin kein `SETTLING` aus
(U-6, AK-T002-22).

**Konsequenzen:** Leicht wird AK-T009-25: zwei Zustaende nebeneinander, ohne
dass ein Satz sie verrechnet — es gibt keinen Ort, an dem das ginge. Dauerhaft
schwer wird, spaeter doch eine Gesamtaussage zu bauen; sie braeuchte einen neuen
Typ und damit eine sichtbare Entscheidung, was der Zweck ist.

**Umkehrbarkeit:** leicht.

---

### AD-022 — Im gepinnten Modus fehlen die ABR-Fakten, nicht die Stufe (2026-09-02, Status: aktiv)

**Kontext:** T-022 hat am Geraet belegt: der Stack druckt
`LDAC adaptive bit rate encode quality mode index` und `adjustments` **nur** bei
`LDAC quality mode: ABR`. Im gepinnten Modus fehlen beide Zeilen — und Verluste
treten auf diesem Geraet ausschliesslich im gepinnten Zustand auf. Der Auftrag
fasst das als „auch das ist `CANNOT_TELL`" zusammen. An der Fixture
nachgeprueft ist das **zu weit gefasst**:
`bt_manager_pixel11_ldac_990_loss.txt` traegt `LDAC quality mode: HIGH`,
`LDAC transmission bitrate (Kbps): 990` und
`LDAC saved transmit queue length: 11`. Die **gemessene Stufe** ist da, die
Verlustzaehler sind da; nicht da sind die **ABR-Fakten**.

**Optionen:**
A. Die ganze Stufenzeile faellt im gepinnten Modus auf `CANNOT_TELL`.
Konsequenz: ein gemessener Wert (990 kbps, MEASURED) verschwindet vom
Bildschirm — Detailverlust gegen `GOAL.md` AK-2, ausgerechnet in dem Zustand, in
dem der App Designer die Stoerung hoert. `UI_SPEC.md` haelt `LADDER_PINNED`
ausdruecklich fuer richtig geloest.
B. Fehlende ABR-Zeilen als 0 Wechsel lesen. Konsequenz: „keine ungesehenen
Wechsel" waere behauptet, wo nichts gezaehlt wurde — der Freispruch aus AK-3,
und G-4 haengt genau daran.
C. Die ABR-Fakten sind ein eigener, abwesenheitsfaehiger Block:

```kotlin
data class AbrFacts(val rungIndex: Int?, val adjustments: Long?)
// im Lesungssatz: val abr: AbrFacts?  — null mit Grund:
enum class AbrGap { NOT_ADAPTIVE, NOT_PRINTED_BY_BUILD, NOT_READ }
```

**Entscheidung:** C. Der **Verlustzustand ist davon unberuehrt** — die
Verlustzaehler sind im gepinnten Modus vollstaendig lesbar. Die Stufenzeile
bleibt `LADDER_PINNED` mit Soll- und Messwert. `CANNOT_TELL` gilt fuer den
**Stufenverlaufs-Block**: Anteile je Stufe, Wechselzahl, „angesteuert und
verworfen" und die G-4-Markierung „enthaelt ungesehene Wechsel" sagen dort
*nicht bestimmbar* — nie null und nie eine aus der Rate geratene Stufe.

**Randbedingung dieser Aussage, damit sie nicht ueberdehnt wird:** geprueft an
drei 990er-Dumps und 1795 ABR-Samples des Pixel 11 Pro (T-022, T-011). Ob ein
anderer Build die Zeilen auch unter ABR weglaesst, ist **nicht** geprueft —
deshalb `NOT_PRINTED_BY_BUILD` als eigener Grund neben `NOT_ADAPTIVE`.

**Konsequenzen:** Leicht wird, dass Verlustpfad und Stufenpfad im gepinnten
Modus unabhaengig ehrlich sind. Dauerhaft schwer wird der Graphen-Schritt: G-4
kann seine Zusage („der Zaehler deckt auf, was die Abtastung verpasst hat") im
gepinnten Modus **nicht** einloesen und muss dort eine Wissensgrenze zeichnen.
Das ist keine Umsetzungsluecke, sondern eine Eigenschaft des Geraets, und sie
gehoert so in den Graphen-Auftrag.

**Umkehrbarkeit:** leicht.

---

### AD-023 — Episoden ersetzen die Poll-Ereignisse, mit genau einem Produzenten (2026-09-02, Status: aktiv)

**Kontext:** `LiveLinkSource.lossEvent()` feuert heute bei **jedem** Poll mit
irgendeinem Delta > 0 — ohne Schwelle, ohne Sustain, ohne Cooldown, und jedes
davon wird als `DROPOUT` (`loud = true`) persistiert. T-002 verlangt statt
dessen Episoden: eine je zusammenhaengender Stoerung, mit Dauer, Spitzenrate,
dominantem Kanal und der gefahrenen Stufe, begrenzt durch
`LOSS_EVENT_COOLDOWN_MS`; aus einer Umschaltspanne kommt gar kein `DROPOUT`,
sondern ein einziger Detail-Eintrag (U-3, U-4).

**Optionen:**
A. Episodenlogik neben dem alten `lossEvent()` aufbauen und spaeter umschalten.
Konsequenz: zwei Produzenten fuer dasselbe Ereignis — der Fehlertyp, den QA-010
gerade beseitigt hat.
B. `lossEvent()` in `LiveLinkSource` zur Episodenlogik ausbauen. Konsequenz: die
Episode braucht das Fenster und den Umschaltmarker; beide liegen nach
AD-015/AD-017 in der Faltung, nicht im Poller. Der Poller muesste sie sich
zurueckholen.
C. Der Episodenverfolger ist Teil derselben Faltung und gibt seine Ereignisse
mit dem Zustand zurueck (`LossFold(state, events)`); `LiveLinkSource.lossEvent()`
wird im selben Schritt **geloescht**. Die Ereignistypen bleiben in
`:core-monitor` (`LinkEvent`), nur die Aufrufstelle wandert dorthin, wo heute
schon persistiert wird (`MonitorViewModel.recordLiveEvents`).

**Entscheidung:** C.

**Konsequenzen:** Leicht wird AK-T002-7 und AK-T002-20 als Unit-Test: eine
Lesungsfolge hinein, die Ereignisliste heraus. Leicht wird auch, dass Zeile,
Graph und Zeitachse dieselbe Episode meinen. Dauerhaft schwer — und das ist der
Preis dieser Entscheidung: Verlust-Ereignisse entstehen nur noch, solange der
Monitor-Bildschirm faltet. Heute kostet das nichts (`recordLiveEvents` schreibt
ohnehin nur von dort, und `MonitorGraph.liveLinkEvents` hat ausserhalb keinen
Abnehmer), aber es ist die Stelle, die sich meldet, wenn jemand spaeter
Ereignisse ohne offenen Bildschirm will — dann ist das eine Frage an `GOAL.md`
AK-4 und nicht an diese Datei.

**Umkehrbarkeit:** mittel — die Faltung in den Poller zu heben ist machbar
(AD-015 Option B), aendert aber die Lebensdauer des Fensters.

---

### AD-024 — Dieser Bau bekommt die Schrittfolge `V-1..V-7` (2026-09-02, Status: aktiv)

**Kontext:** AD-014 hat die Regel gesetzt: jedes Vorhaben mit eigener
Schrittfolge bekommt einen eigenen Praefix. `S-` gehoert dem Scan, `U-` dem
Transport. Dieser Bau bekommt `V-` (Verdikt).

**Entscheidung:** Sieben Schritte, jeder einzeln lauffaehig, einzeln testbar,
**keiner braucht ein Geraet**:

| Schritt | Inhalt | Geraet | Haengt ab von |
|---|---|---|---|
| **V-1** | `LossThreshold` (AD-019) und alle Parameter als benannte Konstanten mit ihrer Messung im KDoc bzw. `TODO(M-x)`. Kein Verhalten. Test: Vollstaendigkeitsguard statt vakuum-gruener Grep-Regel (QA-012) | nein | — |
| **V-2** | `LossChannel`, totale Abbildung aus `TxLossChannel`, `LossReading`, `LossWindow` (Ring, Trimmung, Zaehler und Nenner je Kanal). Rein. Tests: AK-T002-9 (halbe Fenster fehlen ⇒ gleiche Rate), „nicht lesbar" ≠ 0 | nein | V-1 |
| **V-3** | `LossState` und die Faltung: Coverage, Beurteilbarkeit, Schwellen, Hysterese, Sustain, `Measuring`, `CannotTell`-Gruende. Tests: AK-T009-24, -37, AK-T002-17, -18, -21 | nein | V-2 |
| **V-4** | Umschaltlatch und Uebergaenge U-1..U-6 inkl. angemeldetem Lauf (D-7). Tests: AK-T002-5, -6, -20, -22 | nein | V-3 |
| **V-5** | Episodenverfolger und Episoden-Ereignis; `LiveLinkSource.lossEvent()` **loeschen**; `MonitorEventSummary`-Wortlaut. Tests: AK-T002-7, -20, AK-T009-28 | nein | V-4 |
| **V-6** | `LadderState` inkl. `AbrFacts?` (AD-022), Verweildauer nur ueber luecklos gemessene Zeit, `MovingBetweenSteps`, „angesteuert und verworfen", Warteschlangenanteil aus dem Fenster. Tests: AK-T009-25, -27, -28, -30 und der gepinnte Fall aus T-022 | nein | V-2 |
| **V-7** | Verdrahtung: Faltung im `MonitorViewModel` ueber `liveUpdates` und `LdacTuning.busy`; `overviewTrace` wird Projektion (AD-017); die zweite Akkumulation entfaellt. Test: Bildunterschriftszahlen unveraendert ueber eine feste Lesungsfolge | nein | V-3, V-6 |

Die Oberflaeche ist **nicht** Teil dieser Folge. Was die Zeile sagt, entscheidet
der `ui-ux-designer` gegen `UI_SPEC.md`; was sie wissen kann, steht nach V-7
fest. Der Bitratengraph (G-1..G-8) ist ein eigener Schnitt und setzt auf
`LossWindow` auf.

**Risiken und Pruefpunkte:**

| Risiko | Woran man es merkt | Rueckweg |
|---|---|---|
| Die Projektion aus V-7 aendert die Bildunterschrift | Regressionstest ueber `lossWindowCount`/`measuredWindowCount`/`unmeasuredWindowCount`/`queuePressureFraction` schlaegt aus | V-7 ist der einzige Schritt, der den Graphenpfad beruehrt, und fuer sich zuruecknehmbar |
| `NOTICE` = 1/min erzeugt im Alltag Pills | Eine Sitzung ueber 30 min zeigt `Occasional` ohne hoerbaren Anlass | M-5 belegt 0,063/min als Obergrenze; tritt es doch auf, ist die Antwort eine Sustain-Bedingung, kein hoeherer Wert (T-009) |
| App-/Mixer-Kanaele bleiben dauerhaft unbeurteilt | Coverage meldet nie fuenf beurteilte Kanaele | M-1; bis dahin sind die Zahlen sichtbar und ohne Verdikt (AD-019) |
| Zustandswechsel kommt bis zu ein Poll-Intervall spaet (AD-016) | Eine Frist laeuft zwischen zwei Lesungen ab | Bewusst; die Anzeige aendert sich ohnehin nur an Lesungen |
| Die Faltung stirbt mit dem Bildschirm (AD-023) | Kein Episoden-Eintrag aus einer Zeit ohne offenen Monitor | Heute identisch zum Bestand; eine Aenderung waere eine AK-4-Frage |

**Umkehrbarkeit:** leicht — eine Schrittfolge ist ein Plan, kein Bauwerk.

---

### AD-025 — Der Pruefton kommt aus `AudioTrack`, Oboe und NDK fallen weg (2026-09-22, Status: aktiv — **Freigabe `director`; Geraetenachweis W-7 vor W-8**)

**Kontext:** T-040c/1. Der einzige native Code des Projekts ist der Tongenerator
des Hoertests (`core-audio/src/main/cpp/*`, 363 Zeilen, `wc -l` 22.09.) samt
JNI-Bruecke, Oboe 1.9.3, CMake, gepinntem NDK (~2,5 GB), 16-KB-Seitenausrichtung,
drei ABIs und einer ProGuard-Regel. Einziger Nutzer:
`HearingTestViewModel.launchRun` (`NativeToneGenerator()`, Grep 22.09.).

**Was der Hoertest verlangt** (aus `HughsonWestlakeTestController`, Stand
`86e8a45`): drei Pulse zu 220 ms mit 200 ms Pause, Antwortfenster 900 ms nach dem
letzten Puls, Abfrage alle 25 ms. Ein Druck zaehlt also bis 1960 ms nach dem
Einschalten (aus den Konstanten gerechnet, nicht gemessen). Daraus:
- **Latenz:** muss nur *konstant* und *klein gegen 1960 ms* sein — sie verschiebt
  das hoerbare Fenster, nicht die Schwelle. Ueber Bluetooth dominiert ohnehin der
  A2DP-Puffer, durch den beide Engines gleich laufen (Annahme, nicht gemessen —
  deshalb Punkt 3 in W-7).
- **Pegeltreue:** rein digital, `10^(dB/20)` auf Float-Samples; kein
  Systemvolumen, kein `setVolume`. Das ist in Kotlin dieselbe Rechnung.
- **Rampe:** Raised-Cosine ueber `rampMs`, bei jedem Zielwechsel neu gespannt,
  Laenge unabhaengig vom Pegel; stummer Kanal exakt `0.0f`. Ein Knacken oder
  Aussetzer waere ein hoerbarer Hinweis und verfaelscht die Schwelle — das ist
  das eigentliche Risiko eines Wechsels (GC-Pause im Schreib-Thread).

**Optionen:**
A. **Im Bestand bleiben.** Konsequenz: funktioniert; Preis ist die ganze native
Werkzeugkette fuer ~170 Zeilen Sinus, und die Synthese ist ohne Geraet nicht
testbar.
B. **`AudioTrack`, `ENCODING_PCM_FLOAT`, `MODE_STREAM`**, eigener Schreib-Thread,
`ToneGenerator`-Schnittstelle unveraendert. Konsequenz: NDK, CMake, Oboe, JNI,
ProGuard-Regel und `.cxx/` entfallen; die Synthese wird eine reine Klasse mit
JVM-Test. Preis: Unterlaufrisiko im Kotlin-Thread, am Geraet zu belegen.
C. **`AudioTrack` `MODE_STATIC`** mit vorberechnetem Pulszug je Darbietung.
Konsequenz: sample-genaues Gating ohne Thread — aber der Controller bricht einen
Puls bei Tastendruck ab und schaltet einzeln (`setToneActive`), das passt nicht
zu einem fertigen Puffer. Aendert den `ToneGenerator`-Vertrag; verworfen.

**Entscheidung:** B, als Portierung, nicht als Neuentwurf:

```kotlin
// core-audio/.../audio/tone/AudioTrackToneGenerator.kt — eine Datei
internal class ToneRenderer(sampleRate: Int) {          // rein, JVM-testbar
    fun render(out: FloatArray, frames: Int, p: ToneParams) // 1:1 aus onAudioReady
}
class AudioTrackToneGenerator : ToneGenerator, Closeable // Thread + AudioTrack
```

Rate `AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC)`, Puffer 2x
`getMinBufferSize`, `PERFORMANCE_MODE_LOW_LATENCY`, `USAGE_MEDIA`/`MUSIC` wie
bisher, Thread mit `THREAD_PRIORITY_URGENT_AUDIO`, **keine Allokation in der
Schleife**, Parameter als `@Volatile`. `write(...) < 0` setzt `isRunning` auf
false — heute bleibt das Kotlin-Flag nach `onErrorAfterClose` faelschlich true
(Nebenbefund, faellt mit weg).

**Geraetenachweis (W-7), A/B beide Engines, gleiche Sitzung, Pixel 11 Pro:**
1. Pegelschritt −10 dB per Goertzel: Differenz zwischen den Engines innerhalb
   der Wiederholstreuung, die `AcousticEqTest.measuring_the_same_thing_twice…`
   liefert.
2. Pulsdauer 220 ms: zwischen den Engines gleich bis auf einen Schreibblock.
3. Einsatzlatenz am BT-Weg ueber Leckschall (wie `…through_headphone_leakage`):
   beide Zahlen werden **berichtet, nicht geschwellt**; reicht der Stoerabstand
   nicht, meldet der Test „nicht messbar“ statt einer Zahl.
4. `AudioTrack.underrunCount == 0` ueber 5 min Pulsmuster des Hoertests.
Faellt 4 oder weicht 1/2 ab: W-8 wird nicht gebaut, Rueckgabe an `architect`.

**Konsequenzen:** Leicht wird der Build (kein NDK auf neuen Rechnern) und der
Test der Synthese. Dauerhaft schwer wird nichts. **Widerspricht dem Wortlaut von
`GOAL.md`, Rahmen („Oboe NDK“)** — offene Frage an den Nutzer ueber den
`director`; gebaut wird W-8 erst nach seiner Antwort.

**Umkehrbarkeit:** leicht — ein Revert von W-8 stellt Oboe wieder her.

---

### AD-026 — Die zwei MiniJson-Codecs ziehen auf `org.json`, getestet unter Robolectric (2026-09-22, Status: aktiv)

**Kontext:** T-040c/2. Im Persistenzpfad gibt es heute drei JSON-Muster:
`org.json` in `AudiogramStore`, `CompensationProfileStore`, `DeviceProfileStore`;
ein eigener Parser `MiniJson` (178 Zeilen) fuer `DerivedCalibrationJson` und
`PreferenceProfileJson`; kotlinx.serialization nur im Backup (`:app`,
`BackupCodec`) — Grep 22.09. `MiniJson` existiert nur, weil `org.json` auf dem
Host-JVM „not mocked“ wirft (KDoc von `MiniJson`). Das Backup traegt eigene
Typen, nicht die DataStore-Strings; es ist von hier nicht betroffen.

**Optionen:**
A. **Im Bestand bleiben.** Konsequenz: zweites Muster und ein handgeschriebener
Parser bleiben; getestet und funktionsfaehig.
B. **`org.json` + `testImplementation("org.json:json")`.** Konsequenz: neue
Abhaengigkeit, und der Test laeuft gegen die Maven-Implementierung, nicht gegen
die des Geraets (Zahlformat und Toleranzen weichen dort ab).
C. **kotlinx.serialization.** Konsequenz: Compiler-Plugin in `:core-hearing`,
ein drittes Muster im Store-Paket statt eines weniger.
D. **`org.json` wie die Nachbarstores, Codec-Tests unter Robolectric.**
Konsequenz: Robolectric fuehrt Androids eigenes `org.json` aus; es ist im
Katalog und in `:core-monitor` aus genau diesem Grund schon Testabhaengigkeit.

**Entscheidung:** D. Ein Muster im Paket, keine neue Abhaengigkeit, und der Test
prueft die Implementierung, die auf dem Telefon laeuft (Bedingung: Robolectric
SDK 35, das Geraet laeuft 37 — dass `org.json` dazwischen unveraendert ist, ist
Annahme). Schluessel, Form und Ein-Buchstaben-Keys bleiben **wortgleich**.
Zwei Stellen, an denen `org.json` anders reagiert als `MiniJson`, bleiben
explizit: NaN/Unendlich wird vor `put` zu `0.0` (`put` wirft sonst), und eine
unlesbare Zeile faellt einzeln weg, ein unlesbarer String ergibt `emptyList()`.

**Der Test, der die gespeicherten Daten bindet (W-2, vor dem Umbau):** je Codec
ein Literal `LEGACY_…_WRITTEN_BY_MINIJSON`, erzeugt vom **heutigen** Encoder aus
einer Fixture mit Randwerten (Name `null`, Anfuehrungszeichen und Zeilenumbruch
im Label, E-Notation wie `1.0E-4`, 13-stellige Millis, leere Listen), und
`parse(LEGACY) == fixture`. Der Test wird gruen auf `MiniJson` committet und
bleibt danach **unveraendert** gruen. Er faellt, sobald ein Schluessel, ein
Zahlpfad oder die Escape-Behandlung beim Lesen abweicht.

**Konsequenzen:** `MiniJson.kt` entfaellt, die Codecs werden kuerzer. Die
Codec-Tests laufen langsamer (Robolectric-Start je Klasse). Ein Rueckweg auf
eine aeltere App-Version liest das neue Format, weil die Form gleich bleibt —
nicht eigens getestet.

**Umkehrbarkeit:** leicht — das Format aendert sich nicht; das Literal bindet es.

---

### AD-027 — Das Helfer-Protokoll bleibt bei Strings; nur totes Socket-Erbe faellt (2026-09-22, Status: aktiv — **`security-reviewer` vor W-1**)

**Kontext:** T-040c/3. Die AIDL-Methoden liefern Base64-Zeilen
(`OK`/`FILE`/`ERR`/`CODEC`/`HDAUDIO`). Die **Anfragen** sind bereits typisiert
(`int`, `String`, `List<String>`); der Helfer dekodiert keinen fremden String —
die Decoder laufen in der App auf Antworten des eigenen Helfers
(`PrivilegedServer` dekodiert nur seine eigene Antwort, `:626`). `PrivilegedServer`
enthaelt kein `Socket` mehr (Grep 22.09.). `encodeAuth`/`decodeAuth`/`encodeRun`/
`decodeRun` haben nur Aufrufer in `PrivilegedProtocolTest` (Grep 22.09.; Praemisse
haelt). Die AIDL-KDoc haelt die Stringform als bewusste Entscheidung fest.

**Optionen:**
A. **Strings behalten**, totes Socket-Erbe loeschen, Vergleich auf Bibliothek.
B. **`Bundle`-Antworten.** Konsequenz: die App entpackt Bundles aus einem
Shell-Prozess — ein Bundle kann beliebige Parcelables tragen und laedt Klassen;
das ist eine bekannte Fehlerklasse, die heute nicht existiert. Dazu
Versionssprung.
C. **Eigene Parcelables.** Konsequenz: je Antworttyp eine Klasse mit
`writeToParcel`/`createFromParcel`, die im Gleichschritt bleiben muessen
(Mismatch-Fehlerklasse), neue AIDL-`parcelable`-Deklarationen, Versionssprung,
Sicherheitspruefung — fuer ~150 Zeilen Codec, die getestet sind.

**Entscheidung:** A. Die Angriffsflaeche des Helfers aendert sich durch B/C
nicht (er parst schon heute nichts Fremdes); B und C kosten jeweils einen
Versionssprung, und AD-013 sagt, was der kostet: der Nutzer muss den ADB-Befehl
neu ausfuehren. **Wenn** die Antwortform je geaendert wird, dann im Sprung von
U-1 (AD-010), der die `exec`-Signatur ohnehin anfasst — nicht in einem eigenen.

Konkret in W-1:
- `encodeAuth`, `decodeAuth`, `encodeRun`, `decodeRun` und ihre Tests loeschen;
  die KDoc von `decodeOrNull` und die Testkommentare (`PrivilegedProtocolTest`
  :123, :420) sprechen noch von einem Socket — mitziehen.
- `tokensMatch`: die Pruefung auf `null`/leer **bleibt** (`MessageDigest.isEqual`
  liefert fuer zwei leere Arrays `true`), der Rumpf wird
  `MessageDigest.isEqual(offered.toByteArray(UTF_8), expected.toByteArray(UTF_8))`.
  Geprueft am AOSP-Quelltext `libcore/ojluni/.../MessageDigest.java`, Zweig
  `main`, 22.09.: Laenge und Inhalt ohne fruehen Ausstieg verglichen.
  **Praezisiert (T-041d, `security-reviewer`, 22.09.):** Zweig
  `android12-release` (API 31) bricht bei ungleicher Laenge frueh ab, der
  Inhalt wird konstantzeitig verglichen — harmlos, Token ist eine UUID fester,
  oeffentlicher Laenge, beide Aufrufer pruefen vorher die uid. Pruefung bleibt
  `isNullOrBlank` (nicht nur leer). Bindender Test existiert:
  `PrivilegedTokenTest.tokens match only when…` faellt, wenn die Leer-Pruefung
  wegfaellt (`tokensMatch("", "")`).
- Nebenbefund, selbe Datei: die KDoc von `SHUTDOWN` (`:111-114`) haengt ueber
  `GRANT_SECURE_SETTINGS`, `SHUTDOWN` selbst hat keine.

**Konsequenzen:** Kein Versionssprung, kein neuer Typ auf der privilegierten
Flaeche. Die Stringform bleibt Handarbeit — mit Tests, die es schon gibt.

**Umkehrbarkeit:** leicht.

---

### AD-028 — Das Codec-Mode-Kalibrieren wird entfernt, die Tabelle per Hand-Migration 3→4 gedroppt (2026-09-22, Status: aktiv — **Freigabe `director`: loescht Nutzerdaten**)

**Kontext:** T-040c/4. `codecModeCalibrator`, `codecModeSignatures`,
`installCodecModePinner` haben ausserhalb ihrer Definition in `MonitorGraph.kt`
**keinen** Aufrufer in main, test, androidTest, XML oder ProGuard (Grep 22.09.,
Praemisse haelt). Die KDoc von `MonitorGraph.liveLink` sagt selbst, dass die
gelernten Baender an einem Zaehler gemessen wurden, der kein Paketzaehler ist.
Die gespeicherten Zeilen sind also unerreichbar **und** inhaltlich falsch (AK-3).
`CodecModeSignature`/`LdacModeSignatures` (statische Rahmengroessen der
Inferenz) sind etwas anderes und bleiben; `LiveLinkSource.readOnce` bleibt
(`A2dpTxProbe`). Das Backup traegt keine Signaturen.

**Optionen:**
A. **Nur Code entfernen, Tabelle und Entity lassen.** Konsequenz: tote Tabelle
mit falschen Daten auf dem Geraet, Entity ohne Nutzer.
B. **`@DeleteTable`-AutoMigration.** Konsequenz: zweites Migrationsmuster; der
Bestand schreibt Migrationen von Hand (`MIGRATION_1_2`, `MIGRATION_2_3`) und
testet sie handgerollt ohne `MigrationTestHelper` (KDoc des Tests begruendet das).
C. **Entity weg, Version 4, `MIGRATION_3_4` von Hand.**

**Entscheidung:** C. Einzige Anweisung:

```sql
DROP TABLE IF EXISTS `codec_mode_signatures`
```

**Probe (L-033):** gegen eine frische Datenbank aus
`core-monitor/schemas/…MonitorDatabase/3.json` (alle `createSql`, Indizes,
`setupQueries`), je eine Zeile in `monitor_events` und `codec_mode_signatures`,
dann die Anweisung zweimal. Befehl `python probe.py <3.json>` (Scratchpad
T-040c), 22.09. 23:06, SQLite 3.49.1 (Host, nicht Android): Tabelle und ihr
Autoindex weg, alle anderen Tabellen und Indizes unveraendert,
`monitor_events` behaelt 1 Zeile, zweiter Lauf fehlerfrei. Unter Androids SQLite
bindet es erst der Migrationstest aus W-6.

Room prueft beim Oeffnen nur deklarierte Entities; eine liegengebliebene
Tabelle faellt ihm **nicht** auf. Deshalb muss der Test die Abwesenheit selbst
pruefen.

**Migrationstest (W-6), in `MonitorDatabaseMigrationTest`, handgerollt wie der
Bestand:** `writeVersion3Database()` mit Verlaufszeilen und einer
Kalibrierzeile; Oeffnen mit allen Migrationen bei Version 4; (a) Verlauf
zeilengleich, (b) `sqlite_master` kennt `codec_mode_signatures` nicht mehr,
(c) Kette 1→4 ohne Verlust. Faellt, wenn der `DROP` fehlt (b), oder wenn
`MIGRATION_3_4` nicht registriert ist (Room wirft, kein destruktiver
Fallback). Die zwei Kalibriertests der Datei entfallen mit der Entity.

**Konsequenzen:** Weg faellt ein mutierender Pfad (Codec-Neuverhandlung je
Modus) ohne Nutzer. Die Klammer in AD-016 („`CodecModeCalibrator` verhandelt
absichtlich neu“) wird damit Geschichte; stehen gelassen. `schemas/4.json`
entsteht beim Build und wird eingecheckt.

**Umkehrbarkeit:** Code leicht (Revert); **Daten schwer** — die Zeilen sind nach
dem ersten Oeffnen unter Version 4 weg. Sie waren falsch gemessen; ein
Rueckbau wuerde neu kalibrieren, nicht wiederherstellen.

---

### AD-029 — Diese Umbauten bekommen die Schrittfolge `W-1..W-9` (2026-09-22, Status: aktiv)

**Kontext:** Regel aus AD-014. `B-` ist in `docs/release/ROLLOUT.md` belegt,
`W-` nirgends (Grep ueber `*.md` 22.09.). Alle Schritte starten erst, wenn
T-040a/b auf `master` sind — W-1, W-7, W-8 liegen in deren Dateien.

| Schritt | AD | Dateien (≤5 geaendert; Loeschungen extra genannt) | Stufe | Test | Geraet | security-reviewer |
|---|---|---|---|---|---|---|
| **W-1** | 027 | `app/.../privileged/PrivilegedProtocol.kt`, `app/src/test/.../privileged/PrivilegedProtocolTest.kt` | normal | bestehender `PrivilegedTokenTest` bindet; K-2-Suche `socket` in `privileged/` | nein | **ja, vorher** (Auth-Vergleich im Helfer) |
| **W-2** | 026 | `core-hearing/src/test/.../store/DerivedCalibrationJsonTest.kt`, `PreferenceProfileJsonTest.kt` | normal | Legacy-Literal, gruen auf `MiniJson` | nein | nein |
| **W-3** | 026 | `core-hearing/build.gradle.kts` (`testImplementation(libs.robolectric)`), `core-hearing/src/test/resources/robolectric.properties` (`sdk=35`, wie `:app`), `DerivedCalibrationJson.kt`, `DerivedCalibrationJsonTest.kt` (`@RunWith`) | normal | W-2-Literal unveraendert gruen + bestehende | nein | nein |
| **W-4** | 026 | `PreferenceProfileJson.kt`, `PreferenceProfileJsonTest.kt`; loeschen: `MiniJson.kt` | normal | wie W-3; K-2-Suche `MiniJson` | nein | nein |
| **W-5** | 028 | `MonitorGraph.kt`, `link/live/CodecModeCalibration.kt` (nur `ModeSignatureSample` und das Interface `CodecModeSignatureStore` bleiben bis W-6 — `MonitorDatabase.kt:21` importiert es); loeschen: `data/RoomCodecModeSignatureStore.kt`, `CodecModeCalibrationTest.kt`, `CodecModeSignatureStoreTest.kt` | normal | Suite gruen; kein Schema-Eingriff | nein | nein |
| **W-6** | 028 | `data/MonitorDatabase.kt` (Version 4, `MIGRATION_3_4`, Entity/DAO/Mapper weg), `MonitorDatabaseMigrationTest.kt`, `link/live/LiveLinkSource.kt` (KDoc :91); loeschen: `CodecModeCalibration.kt`; erzeugt: `schemas/.../4.json` | **hoch** (Daten) | Migrationstest oben | nein | nein |
| **W-7** | 025 | neu `core-audio/.../tone/AudioTrackToneGenerator.kt`, neu `core-audio/src/test/.../tone/ToneRendererTest.kt`, neu `core-audio/src/androidTest/.../tone/ToneGeneratorAcousticTest.kt`, `AcousticEqTest.kt` (Aufnahme-/Goertzel-Helfer `internal` statt kopieren) | normal | JVM: Rampenlaenge pegelunabhaengig, Rampenenden steigungsfrei, stummer Kanal exakt 0, Pegel `10^(dB/20)` nach Einschwingen; Geraet: Punkte 1–4 aus AD-025 | **ja** | nein |
| **W-8** | 025 | `HearingTestViewModel.kt`, `core-audio/build.gradle.kts` (NDK, CMake, `prefab`, `ndk{}`, Oboe raus), `gradle/libs.versions.toml` (`oboe`), `app/proguard-rules.pro`, `ToneGeneratorAcousticTest.kt` (Oboe-Arm raus); loeschen: `NativeToneGenerator.kt`, `src/main/cpp/` (4 Dateien) | normal | Suite + `assembleDebugAndroidTest`; ein Hoertestlauf am Geraet | **ja** (Smoke) | nein |
| **W-9** | 025 | Texte: `README.md`, `THIRD_PARTY_LICENSES.md` (Oboe-Eintrag), `core-audio/.../eq/SystemEqualizer.kt` (Kommentar), `docs/release/ROLLOUT.md` | normal | K-2-Suche `oboe`/`NDK`/`JNI`/`cpp`, zweite Maske `native`; `docs/archiv/` bleibt | nein | nein |

`GOAL.md` und `docs/state.md` (NDK-Zeile) zieht der `director` nach.
Reihenfolge: W-1 und W-2 sofort parallel; W-3→W-4; W-5→W-6; W-7→(Nutzerantwort
zu „Oboe NDK“)→W-8→W-9. Die vier Straenge beruehren keine gemeinsame Datei.

**Umkehrbarkeit:** leicht.

---

<!-- Saeule 3 — T-047a, 2026-09-23. Grundlage: docs/berichte/T-045-architect.md §2-4,
     Nutzerentscheide F1-F5 und Directorentscheid F-D1 vom 23.09. (docs/tasks/T-047.md). -->

### AD-030 — Der Vergleich besteht aus zwei `ObservationRun`s und einer reinen Funktion (2026-09-23, Status: aktiv)

**Kontext:** AK-13 verlangt Vorher und Nachher „mit denselben Groessen und
derselben Dauer". `ObservationRun` (AK-17, `link/live/ObservationRun.kt`) zaehlt
bereits abgedeckte Intervalle mit der **einen** Lueckenregel `isReadingGap`,
deren KDoc ausdruecklich sagt, dass es genau eine geben soll (`:6-13`). Es fehlen
ihm fuer den Vergleich vier Dinge: die Aussetzer-Zaehlung, ein Ende bei
Zieldauer, die Kadenz und ein lesbarer Link.

**Optionen:**
A. **Eigener `ComparisonRun`** mit Armen, Phasen und Zaehlung. Konsequenz: eine
zweite Zaehl- und Lueckenlogik neben `ObservationRun`; genau das Muster, das
`isReadingGap` verhindern soll.
B. **Zwei `ObservationRun`s plus `compare(before, after)`** als reine Funktion;
die Phasen (waehlen, Arm A, anleiten, Arm B, Ergebnis) haelt ein Controller in
`:app` nach dem Vorbild `ObservationRunController`.
C. **Im Bestand bleiben:** der Nutzer startet zwei Beobachtungslaeufe von Hand
und vergleicht selbst. Konsequenz: keine Nachweisgrenze, keine Gleichheit der
Dauer — AK-13 unerfuellt.

**Entscheidung:** B. `ObservationRun` bekommt genau diese Ergaenzungen:

```kotlin
// core-monitor/.../link/live/ObservationRun.kt
enum class RunEnd { /* bestehende + T-046 */ TARGET_REACHED }   // Zieldauer erreicht
data class ObservationRun(
    // ... bestehende Felder ...
    /** MEASURED (vom System gemeldet): Summe der `dropouts`-Zuwaechse ueber abgedeckte Intervalle. */
    val dropouts: Long = 0L,
    /** Abgedeckte Zeit, in der der Zaehler an einem Ende fehlte oder rueckwaerts lief. */
    val dropoutsUncountedMs: Long = 0L,
    /** Die erwarteten Poll-Intervalle, die abgedeckte Intervalle trugen. */
    val cadencesMs: Set<Long> = emptySet(),
    val targetMs: Long? = null,
    val link: RunLink? = null,              // bisher private, jetzt lesbar
    private val lastDropoutTotal: Long? = null,   // tx.dropoutCount der letzten Raten-Lesung
)
companion object { fun startedAfter(afterMs: Long?, targetMs: Long? = null): ObservationRun }
```

- Gezaehlt wird aus dem **absoluten** Zaehler `snapshot.tx?.dropoutCount`, nicht
  aus `txDelta.dropouts`: `LiveLinkSource.txDelta` macht aus einem fehlenden
  Zaehler `?: 0` (`LiveLinkSource.kt:381`), und eine erfundene Null waere der
  Freispruch aus AK-3. Fehlt der Wert an einem Ende oder faellt er, landet das
  Intervall in `dropoutsUncountedMs`, nicht in `dropouts`.
- `TARGET_REACHED` setzt `withRate`, sobald `observedMs >= targetMs`. Die
  Ueberschreitung ist hoechstens ein Intervall; AD-032 rechnet sie exakt ein.
- Ein AK-17-Lauf (`targetMs == null`) verhaelt sich wie heute.

**Konsequenzen:** Eine Zaehlregel, eine Lueckenregel, ein Test-Satz. Der
Vergleich erbt T-046s Pausengrenze ohne eigenen Code. Dauerhaft schwer wird ein
Vergleich ueber etwas anderes als abgedeckte Intervalle — das ist Absicht.

**Umkehrbarkeit:** leicht (nichts persistiert).

---

### AD-031 — Die Messgroesse ist die `dropouts`-Zaehlung bei gepinnten 990 (2026-09-23, Status: aktiv — Nutzer 23.09. F1)

**Kontext:** Nur bei gepinnten 990 treten auf diesem Geraet Verluste auf; unter
ABR wurde 990 nie gehalten (`LiveLinkPanel.kt:576`), vorher und nachher stuenden
dort beide auf null. Im gepinnten Modus fehlen nur die ABR-Zeilen, nicht Rate
und Verlustzaehler (AD-022, Randbedingung dort: drei 990er-Dumps des Pixel 11 Pro).

**Optionen:** A. `dropouts` bei 990 gepinnt. B. Anteil ≥ 990 unter ABR —
signallos (s. o.). C. `dropped` statt `dropouts` — zaehlt je Raeumung viele
Eintraege (Beispiel „one window with 525 dropped packets", KDoc `lossCount`,
`LinkLiveModels.kt:671`); ein Binomialtest ueber abhaengige Einzelereignisse
waere falsch.

**Entscheidung:** A. Der Kanal ist `TxLossChannel.STACK_DROPOUTS`; eine Episode
zaehlt einmal. Preis, vom Nutzer angenommen: waehrend der Arme sind Aussetzer
hoerbar.

**Konsequenzen:** Der Prozess pinnt 990 vor Arm A (AD-033 haelt den Zustand
davor). `compare` verlangt denselben `RunLink` in beiden Armen, also dieselbe
Stufe, Familie und Abtastrate. Laeuft der Link in der 44,1-kHz-Familie, ist die
Stufe 909, nicht 990 — der Messrahmen nennt die gemessene Stufe, nicht die
gewuenschte.

**Umkehrbarkeit:** leicht.

---

### AD-032 — A/B mit je 15 min, exakter bedingter Binomialtest, α als Konvention (2026-09-23, Status: aktiv — Nutzer 23.09. F2)

**Kontext:** AK-13 verlangt, dass eine Wirkung innerhalb der Nachweisgrenze als
solche benannt wird. R-F (`UI_SPEC.md:2226`) verbietet Raten je Minute in der
Verlustanzeige. Aussetzer kommen in Clustern mit Ruhephasen bis 4,4 min (R-010 C4).

**Optionen:**
A. **A/B, 15 min je Arm, exakter bedingter Binomialtest** auf zwei Zaehlungen.
Konsequenz: 30 min; Drift steckt ungetrennt in der Differenz und wird im
Messrahmen benannt.
B. **A/B/A nach AD-009.** Robust gegen Drift, 45 min — vom Nutzer nicht gewaehlt.
C. **2sd-Regel aus AD-009.** Braucht eine Streuung aus Wiederholungen, die es
bei 990 nicht gibt; jede Zahl waere erfunden.

**Entscheidung:** A.

```kotlin
// core-monitor/.../optimize/Comparison.kt
const val ARM_TARGET_MS = 15 * 60_000L       // F2
/** Konvention, keine Messung. Je Richtung einseitig; die Chance auf einen falschen
 *  Befund in *irgendeine* Richtung ist damit bis 2α — so im KDoc benannt. */
const val DETECTION_ALPHA = 0.05

data class Arm(val run: ObservationRun, val start: ConditionBook, val end: ConditionBook)

enum class Verdict { FEWER_DETECTED, MORE_DETECTED, WITHIN_DETECTION_LIMIT, NO_BASELINE_EVENTS }

sealed interface NotComparable {
    data object ArmAIncomplete : NotComparable      // before.run.end != TARGET_REACHED
    data object ArmBIncomplete : NotComparable      // after.run.end != TARGET_REACHED
    data object LinkDiffers : NotComparable         // before.run.link != after.run.link
    data object CadenceDiffers : NotComparable      // cadencesMs nicht beide dieselbe Einermenge
    data object DropoutsUncounted : NotComparable   // dropoutsUncountedMs > 0 in einem Arm
    data class ConditionChanged(val condition: Condition, val where: Where) : NotComparable
    data class MeasureNotInEffect(val condition: Condition) : NotComparable
    enum class Where { IN_ARM_A, IN_ARM_B, BETWEEN_ARMS }
}

sealed interface ComparisonResult {
    data class NotComparableResult(val reasons: List<NotComparable>) : ComparisonResult
    data class Compared(
        val before: Arm, val after: Arm, val measure: Measure,
        val verdict: Verdict, val pValue: Double,
        val verification: Verification,            // Rueckgelesen? (AD-035)
        val notChecked: List<Condition>,           // nicht lesbare Bedingungen, im Rahmen genannt
    ) : ComparisonResult
}

fun compare(before: Arm, after: Arm, measure: Measure): ComparisonResult
/** Kleinste Vorher-Zaehlung, bei der "0 nachher" nachweisbar ist (gleiche Dauer). */
fun smallestDetectableBaseline(alpha: Double = DETECTION_ALPHA): Int
```

Rechnung: a = `before.run.dropouts`, b = `after.run.dropouts`, n = a + b,
p0 = tB / (tA + tB) mit t = `observedMs`. Unter H0 gilt b ~ Bin(n, p0).
`FEWER_DETECTED`, wenn P(X ≤ b) ≤ α; `MORE_DETECTED`, wenn P(X ≥ b) ≤ α; sonst
`WITHIN_DETECTION_LIMIT`. Ist a = 0 und nicht `MORE_DETECTED`, lautet das
Urteil `NO_BASELINE_EVENTS` (F2: „keine Wirkung nachweisbar"). Die Summe laeuft
im Log-Raum, weil 0,5^n fuer n > 1074 in `Double` auf null faellt. Verglichen
werden Zaehlungen mit ihrem Fenster, keine Rate — R-F bleibt.

**Warum p0 aus den Dauern statt 1/2:** Arm B endet erst nach dem Intervall, das
tA ueberschreitet (AD-030). Die Gewichtung macht den Test fuer diese Ueberschreitung
exakt; bei gleicher Dauer ist p0 = 1/2 und die Literale unten gelten wortgleich.

**Konsequenzen:** Die Nachweisgrenze ist ein Satz, den die App ausrechnet, nicht
ein Wert, den jemand eintraegt: bei α = 0,05 sind **5 gegen 0** nachweisbar
(p = 1/32), **4 gegen 0 nicht** (p = 1/16). Bei der T-029-Rate (10 Cluster in
25 min) reichen 15 min im Mittel dafuer; bei der T-032-Lage (0 in 27,78 min)
sagt die App `NO_BASELINE_EVENTS`. Dauerhaft schwer: ein Urteil ueber
Wirkungs*groesse* — der Test sagt nur, ob ein Unterschied nachweisbar ist.

**Umkehrbarkeit:** leicht — α und Armlaenge sind Konstanten.

---

### AD-033 — Das Ledger haelt den Ausgangszustand vor dem ersten Schreiben; „geliehen" entfaellt im ersten Schnitt (2026-09-23, Status: aktiv — **Freigabe `director`**: Persistenz, kehrt einen Teil von AD-007 und das HD-Audio-Verhalten aus `HdAudioTest.kt:201` um)

**Kontext:** AK-11 verlangt: vor dem ersten Setzen den Ausgangszustand
festhalten, jeden selbst gesetzten Wert wiederherstellen koennen, mit **einer**
Handlung zurueck. Heute gibt es kein Ledger (`ledger|priorValue|valueBefore`: 0
Treffer im Hauptcode, T-045). T-045 schlug zwei Arten vor: „geliehen" (App setzt
fuer ein Experiment, geht automatisch zurueck) und „gewaehlt" (Nutzer setzt,
geht nur auf Wunsch zurueck).

**Wer schreibt heute Audiopfad-Einstellungen** (Grep `write(key|setEnabled|
clear|hdAudio.apply|LdacTuning.pin` in `app/src/main` und `core-system/src/main`,
23.09., Arbeitsbaum `d5b262c`): `DeviceProfileApplier` (Globals :189,
Absolute Volume :117/:132, HD-Audio :245, Codec), `DeviceProfilesViewModel`
(`setGlobalNow` :310, `setAbsoluteVolumeNow` :478), `LdacTuning.pin` (aus
`DeviceProfilesViewModel.kt:502` und `MonitorViewModel.kt:405`).

**Optionen:**
A. **Zwei Arten nach T-045.** Konsequenz: Mit F3 leiht der Prozess nichts mehr
aus — die einzige App-eigene Schreibung ist das 990-Pinnen, und 990 ist das
Ziel des Nutzers. Eine automatische Rueckgabe nach dem Vergleich oder beim
naechsten Start machte genau den Zustand rueckgaengig, den der Prozess
herstellen soll. Die Art „geliehen" haette keinen einzigen Nutzer.
B. **Eine Art: Ausgangszustand je Einstellung vor dem ersten Schreiben der App**,
ein Rueckweg fuer alles. Das 990-Pinnen des Prozesses ist eine Wahl des Nutzers,
der den Prozess mit dem Satz „pinnt 990" startet.
C. **Nur `finally` im Prozess.** Deckt keinen getoeteten Prozess und keine
Schreibung ausserhalb des Prozesses — AK-11 unerfuellt.

**Entscheidung:** B. **Benannter Widerspruch zu AD-007:** dort geht Geliehenes
beim App-Start automatisch zurueck; hier geht nichts automatisch zurueck, alles
mit der einen Handlung. Ort bleibt `:core-system`, Schreiben **vor** der
Aenderung bleibt. Die Art „geliehen" kommt zurueck, sobald AD-006 beantwortet und
eine Leihgabe beschlossen ist.

```kotlin
// core-system/.../devices/SettingsLedger.kt
sealed interface LedgerEntry {
    /** Settings.Global: die vier Entwickleroptionen und `bluetooth_disable_absolute_volume`.
     *  prior == null heisst "Schluessel war nicht gesetzt" -> Rueckweg ist `clear`. */
    data class Global(val key: String, val prior: String?) : LedgerEntry
    /** Vom Stack je Geraet persistiert (R-010 C4: a2dpOptionalCodecsEnabled). */
    data class HdAudio(val deviceKey: String, val prior: HdAudioPreference) : LedgerEntry
    /** Profilwunsch und Live-Stufe; priorLive == null: vorher nicht lesbar/nicht verbunden. */
    data class Ldac(val deviceKey: String, val priorWish: CodecPreference?, val priorLive: Long?) : LedgerEntry
}
/** Gleiche Einstellung = gleicher Typ und gleicher key/deviceKey. */
fun List<LedgerEntry>.withBaseline(entry: LedgerEntry): List<LedgerEntry>   // rein; vorhandene gewinnt

interface SettingsLedger {
    /** Persistiert [entry], falls fuer diese Einstellung noch keins liegt. false = nicht
     *  persistiert -> der Aufrufer schreibt NICHT. */
    suspend fun recordIfAbsent(entry: LedgerEntry): Boolean
    suspend fun entries(): List<LedgerEntry>
    suspend fun remove(entry: LedgerEntry)
}
object NoSettingsLedger : SettingsLedger        // Default fuer Bestandstests, wie UnavailableHdAudioController
class SettingsLedgerStore(context: Context) : SettingsLedger   // DataStore "settings_ledger", ein JSON-Schluessel, org.json

/** Liest den Vorwert und haelt ihn fest. false: Aufrufer ueberspringt die Schreibung. */
suspend fun SettingsLedger.recordGlobal(settings: SecureSettingsController, key: String): Boolean
/** Unreadable -> false, ausser es liegt schon ein Eintrag fuer das Geraet. */
suspend fun SettingsLedger.recordHdAudio(deviceKey: String, before: HdAudioState): Boolean
```

Regeln:
- **Keine erste Schreibung ohne festgehaltenen Vorwert.** Ist der Vorwert nicht
  lesbar und liegt noch kein Eintrag, wird nicht geschrieben; der Grund steht als
  `ProfileAction.Skipped` bzw. im Satz des Bildschirms. Das kehrt die dokumentierte
  Haltung „an unreadable state does not stop the write" (`HdAudioTest.kt:201-209`)
  um — AK-11 geht vor. Liegt der Eintrag schon, wird geschrieben wie bisher.
- **Randbedingung Globals:** `GlobalSettingsController.read` macht aus einem
  Lesefehler `null` (`GlobalSettingsController.kt:34-36`). Ein Fehler wuerde also als
  „nicht gesetzt" festgehalten, der Rueckweg waere `clear`. Geprueft ist nur, dass
  diese Schluessel weltlesbar sind (KDoc `AbsoluteVolumeGate.kt:14`); ein Werfen
  von `getString` ist nicht beobachtet.
- **Codec-Familie aus Profilen** wird nicht festgehalten: der Stack haelt sie je
  Verbindung, nicht dauerhaft (KDoc `LdacTuning`, „survive the reconnect"). Nach dem
  Rueckweg setzt AD-034 das Autoapply aus; die Familie faellt beim naechsten
  Verbinden auf den Stack-Standard. Der Rueckweg-Bericht sagt das.
- **Nicht im Ledger:** Medienlautstaerke und EQ-Kompensation. Sie sind die
  Hoerfunktion der App, keine Audiopfad-Einstellung im Sinn von AK-9 (offene Frage).
- **Nicht sichern, nicht mitnehmen:** `backup_rules.xml` und
  `data_extraction_rules.xml` schliessen heute `datastore/` ganz ein. Ein Ledger
  auf einem neuen Telefon stellte Werte eines anderen Geraets her (AK-15-Geist).
  Beide Dateien bekommen ein `<exclude domain="file"
  path="datastore/settings_ledger.preferences_pb"/>` (drei Stellen).
- **Eintraege verschwinden nur**, wenn ihr Rueckweg per Read-back bestaetigt ist.
  Was nicht zurueckging, zeigt die App, bis es gelingt (AD-007, bleibt).
- **D-001 beachten:** Ledger-Schreibungen nicht aus `viewModelScope` auf Main
  ohne Abwarten im Test ausloesen (`docs/debug/D-001.md`).

**Konsequenzen:** Jede Schreibung haengt an einem `suspend`-Aufruf davor; die
drei Schreiber in `DeviceProfilesViewModel` werden dafuer `launch`-Bloecke. Eine
kuenftige Schreibstelle muss `recordGlobal`/`recordHdAudio`/`recordIfAbsent`
rufen — der Applier-Test prueft die Reihenfolge, fuer neue Schreiber gibt es
keinen Waechter (Risiko unten).

**Umkehrbarkeit:** mittel — eine neue DataStore-Datei. Loeschen verliert die
Vorwerte; das Format ist ein JSON-String wie bei `DeviceProfileStore` und
wiederverwendet dessen `CodecPreference`-Kodierung (internal machen, kein zweites
Format).

---

### AD-034 — Der Rueckweg setzt das Autoapply der beruehrten Profile aus (2026-09-23, Status: aktiv — Director 23.09. F-D1)

**Kontext:** Nach „zurueck auf vorher" schriebe das naechste Verbinden eines
Profilgeraets die Profilwuensche sofort wieder (`DeviceProfileApplier.onDeviceConnected`,
`if (!profile.autoApply)`).

**Optionen:** A. Autoapply aller Profile aus. Konsequenz: auch reine EQ-/Lautstaerke-
Profile verstummen. B. Autoapply nur der Profile aus, deren Wuensche eine
Audiopfad-Einstellung beruehren. C. Profile unangetastet lassen — der Rueckweg
haelt nur bis zum naechsten Verbinden.

**Entscheidung:** B. Beruehrt heisst: `codecPreference != null ||
developerOptions.isNotEmpty() || absoluteVolumeEnabled != null ||
absoluteVolumeSystemDefault || hdAudio != null` (Felder aus `DeviceProfile.kt:28-77`).
Das Aussetzen geschieht **zuerst**, vor den Rueckschreibungen, damit ein Verbinden
waehrend des Rueckwegs nichts wieder anwendet. Es bleibt aus, bis der Nutzer den
vorhandenen Schalter im Profil wieder einschaltet — kein neuer Zustand, kein Ledger-Eintrag.

**Konsequenzen:** Ein beruehrtes Profil verliert auch seine EQ- und Lautstaerke-
Wuensche, bis es wieder eingeschaltet ist; der Bericht nennt die Profile beim Namen.

**Umkehrbarkeit:** leicht.

---

### AD-035 — Der Prozess leitet an und liest zurueck; die Lesbarkeit stellt die Laufzeit fest (2026-09-23, Status: aktiv — Nutzer 23.09. F3; loest die Abhaengigkeit von P-3)

**Kontext:** F3: kein neues Helfer-Kommando, kein WLAN-Schalten, kein Leihen der
Scan-Schalter. P-3 (Geraeteinventur) faellt in die Geraetesession; der Code darf
nicht auf sie warten. AK-14 verlangt den Umgebungszustand am Ergebnis.

**Optionen:**
A. **Zustandsbuch aus vorhandenen Quellen**, Lesbarkeit zur Laufzeit: `Settings.Global`
(`wifi_on`, `wifi_scan_always_enabled`), Battery-Sticky-Intent (`EXTRA_PLUGGED`) und
der ohnehin gepollte Dump (zweite ACL-Verbindung, `Discovering:`). Nicht lesbar
heisst `Unreadable(grund)` auf dem Schirm.
B. **`WifiManager`** fuer Band und Assoziation. Braucht `ACCESS_WIFI_STATE`, fuer
das Band je nach Feld Standortrechte (AD-005, Option A verworfen).
C. **Erst P-3 abwarten**, dann die Kategorien fest einbauen. Blockiert den Bau.

**Entscheidung:** A.

```kotlin
// core-monitor/.../optimize/Measures.kt
enum class Condition(val stable: Boolean) {
    WIFI_RADIO(true), WIFI_SCAN_ALWAYS(true), USB_POWER(true), OTHER_ACL_LINKS(true),
    DISCOVERY_SEEN(false),   // Ereignis, kein Zustand: nur im Rahmen, blockiert nie
}
sealed interface ConditionValue {
    data class Read(val value: String) : ConditionValue   // "on"/"off", "usb"/"other"/"none", "0".."n", "yes"/"no"
    data class Unreadable(val reason: String) : ConditionValue
}
typealias ConditionBook = Map<Condition, ConditionValue>
enum class Verification { VERIFIED, CONTRADICTED, NOT_VERIFIABLE }

// app/.../ui/tuning/EnvironmentConditions.kt
fun readConditions(context: Context, snapshot: LinkLiveSnapshot, discoverySeen: Boolean?): ConditionBook
internal fun settingValue(raw: String?, error: Throwable?): ConditionValue   // "1"/"0" -> on/off, null -> Unreadable("not set on this phone"), Wurf -> Unreadable(message)
internal fun usbValue(plugged: Int?): ConditionValue                         // USB -> "usb", AC/WIRELESS/DOCK -> "other", 0 -> "none", null -> Unreadable
```

- **Vergleichbarkeit (in `compare`):** Fuer jede `stable`-Bedingung gilt: beide
  Enden eines Arms `Read` und verschieden → `ConditionChanged(IN_ARM_A|IN_ARM_B)`;
  Ende A und Anfang B `Read` und verschieden → `ConditionChanged(BETWEEN_ARMS)`,
  **ausser** es ist die Bedingung der gewaehlten Massnahme. `Unreadable` blockiert
  nie, sondern landet in `notChecked` und im Rahmen.
- **Rueckgelesen:** Die Massnahme nennt ihre Bedingung und was als umgesetzt gilt
  (AD-037). Vor Arm B wird gelesen: `CONTRADICTED` startet Arm B nicht, `VERIFIED`
  und `NOT_VERIFIABLE` schon; der Rahmen sagt, welches von beiden.
- **Nur Kabel, nie „USB 3":** `EXTRA_PLUGGED` sagt, ob eine USB-Quelle speist, nicht
  welche Signalisierung laeuft.
- **Kein Band:** `wifi_on` sagt „Funk an", nicht „2,4 GHz". Bei A2 gilt deshalb
  „off" als `VERIFIED`, „on" als `NOT_VERIFIABLE` (ein 5-GHz-Netz ist moeglich) —
  nie als Widerspruch.
- **`ble_scan_always_enabled` kommt nicht ins Zustandsbuch:** laut R-010 C7 wirkt
  „Bluetooth-Scannen" nur bei ausgeschaltetem Bluetooth, im Prozess laeuft es
  immer. Nach AK-12 erscheint Widerlegtes gar nicht. P-3 inventarisiert den
  Schluessel trotzdem (AK-9).
- **Discovery** liest jede Lesung eines Arms aus dem Dump; `DISCOVERY_SEEN` ist
  „yes", wenn eine sie sah. Zwischen den Lesungen bleibt sie ungesehen, der Rahmen
  sagt das.

**Konsequenzen:** Kein neues Kommando, keine neue Permission, AK-10 bleibt leer
erfuellt. P-3 bestaetigt spaeter nur, was die Laufzeit schon ehrlich meldet;
aendert sich dabei eine Kategorie, ist das ein Text- und kein Strukturwechsel.
Dauerhaft schwer: eine Aussage ueber das WLAN-Band. Sie kaeme erst mit AD-005.

**Umkehrbarkeit:** leicht.

---

### AD-036 — AK-16 sagt „strukturell nicht bestimmbar", bis T-037 den Pakettyp klaert (2026-09-23, Status: aktiv — Nutzer 23.09. F4)

**Kontext:** Ob eine Paarung 990 strukturell tragen kann, haengt am Pakettyp
(R-010 Teil 0). Der ist nur ueber BQR lesbar, und dort wird nach AK-7 nichts
gebaut. Die Luftzeitrechnung ist laut `GOAL.md` eigene Arithmetik. Auf dieser
Paarung (MTU 883, `Support 3Mbps: true`) schlaege eine MTU-Regel ohnehin nie an.

**Optionen:** A. Fester Satz „strukturell nicht bestimmbar" plus die lesbaren
Paarungsfakten (P-2). B. MTU-Regel (≤ 351 → 990 unmoeglich) als Urteil. Konsequenz:
ein Urteil aus einer Sekundaertabelle (Habr), ohne Messung — gegen AK-3.
C. „Alle Massnahmen ohne nachweisbare Wirkung" als strukturelles Urteil. Konsequenz:
ein empirischer Befund ueber getestete Bedingungen wird zur Aussage ueber die Paarung.

**Entscheidung:** A. Kein Typ, keine Schnittstelle: ein Satz im Prozess, daneben
die Fakten aus P-2 („3 Mbps: ja, MTU 883 — gelesen an dieser Paarung"), und
ausdruecklich „Pakettyp und Wiederholrate: ohne BQR nicht lesbar". C wird nie
als strukturell formuliert; da Vergleiche nicht persistiert werden (AD-037), gibt
es im ersten Schnitt auch keinen Sammelsatz darueber.

**Konsequenzen:** AK-16 ist erfuellt, indem die App ihre Grenze nennt. Ein echtes
Urteil kommt erst mit T-037; dann ist es eine Erweiterung dieser Entscheidung.

**Umkehrbarkeit:** leicht.

---

### AD-037 — Der Prozess gilt, wo er dasselbe tut wie Scan-Entwurf und Geraetetest; der Katalog ist Daten (2026-09-23, Status: aktiv — T-045 vorgesehen; F5)

**Kontext:** Der ruhende Scan (T-005, AD-003/AD-008/AD-009) und der
„Device test" (`DeviceDiagnostic`, entfernt in T-046, F5) ueberschneiden sich mit
Saeule 3. AK-12 verlangt genau sechs belegte Massnahmen (R-010 A1–A5, A7), die
Bitratensenker nur als „Ausweichen", Widerlegtes gar nicht.

**Optionen:**
A. **Der Prozess ist die eine Form des Vorher/Nachher**; Scan-Phase 2 und AD-009
sind dafuer abgeloest (Statuszeilen oben), der Katalog ist eine Aufzaehlung in
`:core-monitor/optimize/`.
B. **Prozess neben dem Scan-Entwurf**, beide mit eigener Versuchslogik.
Konsequenz: zwei Muster fuer dasselbe Problem, sobald T-005 erwacht.
C. **Katalog als Texte in der UI.** Konsequenz: AK-12 haengt an Textbausteinen und
ist nicht als Literal pruefbar (AD-004, Option A, derselbe Fehler).

**Entscheidung:** A.

```kotlin
// core-monitor/.../optimize/Measures.kt
/** R-010 Teil 1, in der Rangfolge dort. A6 fehlt: Eigenschaft, keine Einstellung. */
enum class Measure(val r010: String, val condition: Condition?) {
    NO_2_4_GHZ_WIFI("A2", Condition.WIFI_RADIO),
    NO_DISCOVERY("A1", null),               // Ereignis, per Lesung nicht als abwesend belegbar
    NO_SECOND_DEVICE("A3", Condition.OTHER_ACL_LINKS),
    BODY_OUT_OF_PATH("A4", null),
    USB_CABLE_OFF("A5", Condition.USB_POWER),
    SINK_ALLOWS_LDAC("A7", null);
    fun verify(value: ConditionValue?): Verification
}
/** R-010 Teil 2: senken die Bitrate. Nie als Behebung, nur unter "Ausweichen". */
enum class Fallback { LOWER_STEP, ADAPTIVE_BITRATE, CODEC_CHANGE, FAMILY_44_1_KHZ }
```

`verify`: `condition == null` → `NOT_VERIFIABLE`; A2 siehe AD-035; A3 „0" →
`VERIFIED`, sonst `CONTRADICTED`; A5 „none" → `VERIFIED`, sonst `CONTRADICTED`;
`Unreadable` → `NOT_VERIFIABLE`. `Fallback` hat keinen Vergleich und kein
Pinnen — es ist Wissen, keine Aktion. Die Texte liefert `UI_SPEC.md`; der Code
kennt nur Kennungen.

- **Keine Persistenz von Vergleichen.** Arm A liegt als Wert im
  `MonitorViewModel` (wie der AK-17-Lauf) und uebersteht das Verlassen der App,
  nicht den Prozesstod. Der Ablauf sagt das.
- **Kein Ersatz fuer den Geraetetest** (F5): P-8 steht allein.

**Konsequenzen:** AK-12 ist ein Literaltest. Wacht T-005 auf, baut der Scan auf
`compare` und `Measure` auf, statt eine zweite Versuchslogik zu schreiben.

**Umkehrbarkeit:** leicht.

---

### AD-038 — Bauschnitt fuer Saeule 3: sechs Laeufe aus P-2, P-4..P-9 (2026-09-23, Status: aktiv)

**Kontext:** Schnitt P-1..P-9 aus T-045 §2. P-1 laeuft in T-046, P-3 in der
Geraetesession. Regel: hoechstens fuenf Dateien mit eigener Logik je Lauf,
Verdrahtung und Tests zaehlen nicht. **Alle Laeufe starten erst, wenn T-046 auf
dem Branch liegt:** T-046 aendert `ObservationRun.kt`, `LinkLiveModels.kt`,
`MonitorScreen.kt`, `MonitorViewModel.kt`, die hier wieder beruehrt werden.
Keine Datenbank-Anweisung in diesem Schnitt (L-033 nicht beruehrt).

| Lauf | P | Dateien mit Logik (Verdrahtung) | schreibt Einstellungen → Security-Vorlauf | UI-Spec liefert |
|---|---|---|---|---|
| **S3-1** | P-2 | `A2dpLinkDumpParser.kt`, `LinkLiveModels.kt` (`LiveLinkSource.kt`: Feld durchreichen) | nein | nichts (Anzeige in S3-6) |
| **S3-2** | P-6, P-7 | `ObservationRun.kt`, neu `optimize/Comparison.kt`, neu `optimize/Measures.kt` | nein | nichts |
| **S3-3** | P-5 (Kern) | neu `devices/SettingsLedger.kt`, `DeviceProfileApplier.kt`, `DeviceProfileStore.kt` (`CodecPreference`-JSON internal) (`SystemGraph.kt`, `backup_rules.xml`, `data_extraction_rules.xml`) | **ja** — Schreibpfad des Appliers, neue persistierte Vorwerte, Backup-Ausschluss | Skip-Grund „Wert vorher nicht lesbar, deshalb nicht umkehrbar — nicht geschrieben" |
| **S3-4** | P-4 | neu `app/.../ui/tuning/EnvironmentConditions.kt` | nein (liest nur) | nichts (Anzeige in S3-6) |
| **S3-5** | P-5 (Rueckweg) | `LdacTuning.kt`, neu `app/.../ui/tuning/SettingsRestore.kt`, `DeviceProfilesViewModel.kt`, `DeviceProfilesScreen.kt` (Instanz in der App-Verdrahtung) | **ja** — schreibt Globals, HD-Audio, Codec zurueck; setzt Autoapply aus | Knopf „zurueck auf vorher" (Bluetooth-Tab), Bestaetigung, Bericht: zurueckgestellt / nicht zurueck mit Grund / Autoapply ausgesetzt fuer {Profil} / Codec-Familie erst beim naechsten Verbinden; Dauerhinweis fuer offene Eintraege mit „erneut versuchen" |
| **S3-6** | P-8, P-9, Anzeige P-2 | neu `ComparisonController.kt`, neu `ComparisonSection.kt`, `LiveLinkPanel.kt` (Paarungsfakten) (`MonitorViewModel.kt`, `MonitorScreen.kt`) | **ja** — pinnt 990 ueber den vorhandenen `LdacTuning.pin`-Pfad | Einstieg und Hinweis (F1: Aussetzer hoerbar); Massnahmenliste (6) und eigene Sektion „Ausweichen" (4) mit Begruendung, warum keine Behebung — Kategoriename muss als „Ausweichen" erkennbar sein; Anleitung je Massnahme; Rueckleseworte (bestaetigt / widerspricht / nicht pruefbar); Armfortschritt „{T} von 15 min beobachtet"; Armende-Gruende (Bestand + `TARGET_REACHED`); vier Urteilssaetze + je `NotComparable` ein Satz; **Messrahmen am Ergebnis**: beide Dauern, Stufe, Codec, Kadenz, Zustandsbuch Anfang/Ende je Arm, `notChecked`, Drift-Satz (F2), „relative Aussage ueber genau diese Bedingungen, keine Aussage ueber Alltagsqualitaet", Nachweisgrenze aus `smallestDetectableBaseline()`; AK-16-Satz; Paarungsfakten-Zeilen; Satz zum Prozesstod; Rueckweg-Knopf. R-A, R-F, R-G gelten. |

**Schnittstellen je Lauf** (Signaturen der Kernlogik stehen in AD-030..AD-037):

- **S3-1:** `data class PairingFacts(val edr: Boolean?, val threeMbps: Boolean?,
  val otherAclLinks: Int?, val discovering: Boolean?)`; `A2dpLinkDump.pairing` und
  `LinkLiveSnapshot.pairing: PairingFacts? = null`. `edr`/`threeMbps` aus dem
  Peer-Block unter `A2DP Source State` (`EDR:`, `Support 3Mbps:`); `otherAclLinks`
  = Zeilen mit `[ACL BR/EDR:Y` minus eins, wenn ein A2DP-Peer verbunden ist, nie
  unter 0; `discovering` aus der ersten `Discovering:`-Zeile. Die effektive MTU
  liegt schon in `LdacStackState.effectiveMtu`. Randbedingung der Zaehlregel:
  in allen drei Pixel-11-Fixtures genau eine `Y`-Zeile, und die ist die Gegenstelle
  (Grep 23.09.); ob andere Builds die aktive Verbindung anders auflisten, ist
  nicht geprueft.
- **S3-3:** Applier bekommt `ledger: SettingsLedger = NoSettingsLedger` als
  letzten Konstruktorparameter (Muster `codec`/`hdAudio`). Vor `secureSettings.write/
  clear` → `ledger.recordGlobal(secureSettings, key)`; vor `absoluteVolume.setEnabled/
  clear` → `ledger.recordGlobal(secureSettings, KEY_DISABLE_ABSOLUTE_VOLUME)`; vor
  `hdAudio.apply` → `ledger.recordHdAudio(key, before)` mit dem schon gelesenen
  `before`. `false` → `Skipped`, keine Schreibung. `SystemGraph` baut
  `SettingsLedgerStore` einmal (lazy) und reicht ihn hinein.
- **S3-5:** `LdacTuning.pin` haelt vor `store`/`apply` `LedgerEntry.Ldac(key,
  profil?.codecPreference, LdacQuality.codeOf(live mode) oder null)` fest.
  `setGlobalNow`/`setAbsoluteVolumeNow` rufen `recordGlobal` in einem `launch`.
  ```kotlin
  class SettingsRestore(
      ledger: SettingsLedger, globals: SecureSettingsController, hdAudio: HdAudioController,
      profiles: DeviceProfileStore, connected: suspend () -> List<BtAudioDevice>,
      requestLdac: suspend (address: String, quality: Long) -> CodecApplyOutcome,
  ) { suspend fun restoreAll(): RestoreReport }
  data class RestoreReport(
      val restored: List<LedgerEntry>,
      val pending: List<Pair<LedgerEntry, String>>,   // Grund, woertlich
      val autoApplyPausedFor: List<String>,           // Profilnamen
  )
  ```
  Reihenfolge: Autoapply aussetzen (AD-034) → Globals (`prior == null` → `clear`) →
  HD-Audio (nur verbundene Geraete, Zuordnung per `DeviceKey.fromAddress`) → LDAC
  (Profil `codecPreference = priorWish`; live nur wenn verbunden und `priorLive != null`;
  `NONE` wird als `ADAPTIVE` angefragt). Eintrag weg nur bei Read-back-Erfolg;
  Nicht-Verbunden gilt beim Live-Teil als erledigt (die Stufe stirbt mit der Verbindung).
- **S3-6:** `ComparisonController(scope)` nach dem Muster `ObservationRunController`:
  `onReading(snapshot, expectedIntervalMs)` fuettert den laufenden Arm und merkt
  `discovering`; Phasen `Choose → PinAndCheck → ArmA → Instruct → ArmB → Result`;
  Pinnen ueber `LdacTuning.pin(HIGH_QUALITY, shownAddress = …)`, weiter nur bei
  `CodecApplyOutcome.Applied`; Arm A `startedAfter(newest, ARM_TARGET_MS)`, Arm B
  `startedAfter(newest, armA.run.observedMs)`; vor Arm A: steht die Massnahme schon
  `VERIFIED`, sagt der Ablauf „bereits umgesetzt" und startet nicht; vor Arm B:
  `CONTRADICTED` startet nicht. Endet ein Arm ohne `TARGET_REACHED`, wird nur dieser
  Arm wiederholt; Arm A bleibt. Kein neuer persistierter Hinweis-Schalter.

**Testfaelle als Literale** (je positiver und negativer Fall, Lehre QA-018):

- **S3-1** (Fixture `bt_manager_pixel11_ldac_990_loss.txt`): `edr == true`,
  `threeMbps == true`, `otherAclLinks == 0`, `discovering == false`,
  `effectiveMtu == 883`. Text ohne `A2DP Source State` → `edr`/`threeMbps` null;
  zweite Zeile mit `[ACL BR/EDR:Y` ergaenzt → `otherAclLinks == 1`; ohne
  Geraeteliste → `otherAclLinks == null` (nie 0).
- **S3-2 ObservationRun** (990 kbps, spielend, Kadenz 1000): Lesungen t = 0/1000/2000
  mit `dropoutCount` 10/12/15 → `dropouts == 5`, `observedMs == 2000`,
  `cadencesMs == {1000}`. Mittlere Lesung mit `dropoutCount = null` →
  `dropouts == 0`, `dropoutsUncountedMs == 2000`. Zaehler 15 → 3 →
  Intervall in `dropoutsUncountedMs`, `dropouts` unveraendert. `targetMs = 2000` →
  nach t = 2000 `end == TARGET_REACHED`, Lesung t = 3000 aendert nichts;
  `targetMs = 2001` → nach t = 2000 `end == null`. `targetMs = null` → Verhalten
  wie Bestand (bestehende Tests unveraendert gruen).
- **S3-2 compare** (tA = tB = 900 000, gleicher Link, Kadenz {1000}, Buecher gleich):
  5/0 → `FEWER_DETECTED`, `pValue == 1.0/32`; 4/0 → `WITHIN_DETECTION_LIMIT`,
  `pValue == 1.0/16`; 0/5 → `MORE_DETECTED`; 0/0 und 0/4 → `NO_BASELINE_EVENTS`;
  10/10 → `WITHIN_DETECTION_LIMIT`; 1000/900 → `FEWER_DETECTED` (Log-Raum, kein
  NaN); 5/0 mit tA = 900 000, tB = 905 000 → `FEWER_DETECTED`, `pValue` = (900/1805)^5
≈ 0,030820 (Python `math`, 23.09.). 1000/900 bei gleicher Dauer: p ≈ 0,01155
(ebenda, exakte Summe).
  `smallestDetectableBaseline() == 5`, `smallestDetectableBaseline(0.1) == 4`.
  `NotComparable`: Arm A endet `STOPPED` → `ArmAIncomplete`; Link `HIGH` gegen
  `ADAPTIVE` → `LinkDiffers`; Kadenz {1000} gegen {2000} → `CadenceDiffers`;
  `dropoutsUncountedMs = 1` → `DropoutsUncounted`; `WIFI_RADIO` on→off in Arm A →
  `ConditionChanged(WIFI_RADIO, IN_ARM_A)`; `USB_POWER` wechselt zwischen den Armen
  bei Massnahme A2 → `ConditionChanged(USB_POWER, BETWEEN_ARMS)`; `WIFI_RADIO`
  wechselt zwischen den Armen bei A2 → vergleichbar; `WIFI_SCAN_ALWAYS` `Unreadable`
  → vergleichbar, in `notChecked`; `DISCOVERY_SEEN` yes in Arm B → vergleichbar.
- **S3-2 Katalog:** `Measure.entries.map { it.r010 } == listOf("A2","A1","A3","A4","A5","A7")`;
  `Fallback.entries.size == 4`; kein Name aus `Measure` in `Fallback`; keine Kennung
  `A6`, `C1`..`C10` in `Measure`. `verify`: A2 „off" → `VERIFIED`, „on" →
  `NOT_VERIFIABLE`; A3 „0" → `VERIFIED`, „1" → `CONTRADICTED`; A5 „none" →
  `VERIFIED`, „usb" → `CONTRADICTED`; A4 → `NOT_VERIFIABLE`; `Unreadable` → `NOT_VERIFIABLE`.
- **S3-3:** `withBaseline`: zweiter `Global("k", "2")` nach `Global("k", null)` →
  Liste unveraendert, Vorwert bleibt `null`. Applier mit Fake-Ledger: Globalwunsch →
  Ledger-Eintrag **vor** der Schreibung (Reihenfolge-Log); zweites Anwenden → kein
  zweiter Eintrag. HD-Audio `Unreadable` ohne Eintrag → keine Schreibung, `Skipped`
  mit Grund (ersetzt `HdAudioTest.kt:201`); mit vorhandenem Eintrag → geschrieben.
  Ledger meldet `false` → keine Schreibung. `SettingsLedgerStore` (Robolectric):
  Rundreise aller drei Typen inkl. `prior = null` und `priorWish = null`.
  Backup-Regeln: Test liest beide XML und findet den `exclude`.
- **S3-4** (Robolectric): `wifi_on` = "1" → `Read("on")`, "0" → `Read("off")`,
  nicht gesetzt → `Unreadable`; `usbValue(BATTERY_PLUGGED_USB) == Read("usb")`,
  `usbValue(0) == Read("none")`, `usbValue(null)` → `Unreadable`;
  `OTHER_ACL_LINKS` aus `pairing == null` → `Unreadable`, nie `Read("0")`.
- **S3-5:** Ledger mit `Global("k", null)`, `Global("j","1")`, `HdAudio(d, DISABLE)`
  (Geraet nicht verbunden), `Ldac(d, null, ADAPTIVE)`: → `clear("k")`, `write("j","1")`,
  HD in `pending` mit Grund „nicht verbunden", Profil `codecPreference == null`,
  live `ADAPTIVE` angefragt; Autoapply des beruehrten Profils `false`, eines reinen
  EQ-Profils unveraendert `true`; Aussetzen vor der ersten Rueckschreibung
  (Reihenfolge-Log). Schlaegt `write` fehl → Eintrag bleibt, `pending` nennt ihn.
  `LdacTuning.pin`: Eintrag vor `store` (Reihenfolge-Log), zweiter Pin → kein
  neuer Eintrag.
- **S3-6** (Robolectric/Controller): Lesungsfolge ueber 15 min Arm A mit 5 Aussetzern,
  Massnahme A5, Buch vor Arm B `USB_POWER = none`, Arm B ohne Aussetzer →
  `Result` mit `FEWER_DETECTED`; `USB_POWER = usb` vor Arm B → Arm B startet nicht;
  Pinnen `NotObserved` → Arm A startet nicht; Arm B endet `READING_GAP` → Arm A
  bleibt, nur B wird neu gestartet. Oberflaeche: der Rahmen steht im selben
  Composable wie das Urteil (kein Fussnoten-/Detail-Umweg, AK-14); K-2-Suche
  der Wortfamilien aus R-G im neuen Text.

**Reihenfolge und Parallelitaet** (Worktrees nur bei disjunkten Dateilisten):

1. **Welle 1**, parallel: S3-1 ∥ S3-2 ∥ S3-3. Dateilisten disjunkt (Parser/Modelle ·
   `ObservationRun` + `optimize/` · `:core-system` + zwei XML). Security-Vorlauf
   vor S3-3 prueft AD-033/AD-034 zusammen und deckt S3-5 mit ab. Der
   `ui-ux-designer` schreibt parallel die Texte fuer S3-3, S3-5, S3-6.
2. **Welle 2**, parallel: S3-4 (braucht S3-1, S3-2) ∥ S3-5 (braucht S3-3 und UI-Spec).
3. **Welle 3:** S3-6 (braucht S3-2, S3-4, S3-5, UI-Spec). Security-Vorlauf fuer den
   Pin-Pfad, falls der erste Vorlauf ihn nicht abgedeckt hat.
4. **Geraet:** Abnahme von S3-6 faellt mit T-036 zusammen (Massnahme A2 unter 990).
   Monitor ist dabei offen — der Vergleich braucht die Lesungen; T-037 bleibt
   getrennt (BQR-Queue, T-045 Nebenfund 1).

**Risiken und Pruefpunkte:**

| Risiko | Woran man es merkt | Rueckweg |
|---|---|---|
| Ein neuer Schreiber umgeht das Ledger | Grep der Schreibstellen (s. AD-033) findet einen Aufruf ohne `record…` davor | Schreibstelle nachziehen; bleibt es wiederholt, Ledger als Dekorator an die Ports (dafuer muessten `write`/`setEnabled` `suspend` werden — heute zu breit) |
| Tabwechsel raeumt das `MonitorViewModel` und damit Arm A | S3-6-Test oder Geraet: Arm A fehlt nach Tabwechsel | Controller eine Ebene hoeher halten (Activity-Scope); keine Persistenz |
| 15 min reichen bei ruhiger Strecke nie fuer 5 Aussetzer | T-036 zeigt Arm A regelmaessig < 5 | Das ist die ehrliche Aussage (`NO_BASELINE_EVENTS`/`WITHIN…`); Armlaenge ist eine Konstante, Aenderung beim Nutzer |
| `wifi_on` ist aus der App-Uid nicht lesbar | P-3 oder Laufzeit zeigt `Unreadable` | Nichts zu bauen: der Schirm sagt es bereits (AD-035) |
| D-001-Muster im neuen Store | Haengende Robolectric-Tests nach S3-3/S3-5 | Wie D-001: Abwarten im Test, keine Main-Schreibung ohne Join |

**Umkehrbarkeit:** leicht — eine Schrittfolge ist ein Plan, kein Bauwerk.

---

## Bewusst nicht getan

- **Ein zweiter Ring fuer die Zustandsmaschine neben `LiveTrace`.** Zwei Fenster
  ueber dieselben Lesungen haetten zwei Nenner, und Bildunterschrift und Verdikt
  koennten auseinanderlaufen, ohne dass ein Test es merkt — QA-010 in der
  Zeitachse. Wieder interessant, wenn der Ueberblicksgraph eines Tages ein
  anderes Fenster als die Verlustzeile bekommt; dann ist es keine Doppelung
  mehr, sondern eine Absicht. (AD-017)
- **Eine eigene Uhr in der Zustandsmaschine.** Sie wuerde erlauben, ueber
  ungemessene Zeit zu urteilen, und damit D-2/D-8/D-12 unterlaufen. Wieder
  interessant nur, wenn die Anzeige eines Tages ohne Lesungen weiterlaufen
  soll — was heute niemand will. (AD-016)
- **Ein Schweregrad oder eine Skala zwischen `OCCASIONAL` und `DISTURBED`.**
  Zwischen 0 und 12 Dropouts/min gibt es keinen Kalibrierpunkt, und die zwei
  vorhandenen sind konfundiert. Wieder interessant, wenn M-11 ein Verfahren
  findet, Zwischenpunkte bei **gleicher** Stufe zu erheben; solange nicht,
  bleibt R-E bindend. (AD-021)
- **Geratene Schwellen fuer App- und Mixer-Underruns.** Ein Wert ohne Messung
  wirkt genau wie ein gemessener. Wieder interessant mit M-1 — bis dahin sind
  diese Kanaele lesbar, sichtbar und unbeurteilt. (AD-019)
- **`TxLossChannel` auf fuenf Kanaele erweitern.** Der Typ benennt die Zaehler
  des Bluetooth-Stacks; App- und Mixer-Underruns kommen aus einem anderen Dump
  und wuerden die Zusage von `lossByChannel` brechen. Wieder interessant, wenn
  der Stack selbst eines Tages Eingangszaehler druckt. (AD-018)
- **Neues Modul `:core-scan`.** Erst wieder interessant, wenn der Scan Logik
  bekommt, die weder Beobachtung noch Systemzustand ist, oder wenn
  `:core-monitor` aus anderen Gruenden geteilt wird. (AD-002)
- **`ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES` im App-Manifest.** Wieder
  interessant, wenn der `security-reviewer` die Helper-Operation aus AD-005
  ablehnt und WLAN-Fakten trotzdem gewuenscht sind. Dann ist es eine Frage an
  den Nutzer, nicht an den Architekten.
- **`dumpsys wifi` auf der Whitelist.** Nur falls `cmd wifi status` auf diesem
  Build die MLO-Links nicht ausweist und kein anderer schmaler Weg bleibt.
- **`setprop` durch den Helper** (A2DP-Offload, Snoop-Log, max. Audiogeraete).
  Am Geraet verifiziert unmoeglich: `init` verweigert der Shell-Domain den
  Schreibzugriff auf `persist.bluetooth.*`. Wieder interessant nur mit Root, und
  Root ist kein Ziel dieses Projekts.
- **`logcat` als Quelle.** Aktenkundig geprueft und leer: zwei Stunden
  LDAC-Wiedergabe enthielten keine einzige LDAC-, ABR- oder Bitratenzeile.
- **Fremde Bluetooth-Geraete trennen.** Wieder interessant, wenn der App
  Designer es ausdruecklich will; es waere eine mutierende Operation an Geraeten,
  die dem Scan nicht gehoeren, und die Systemeinstellungen koennen es bereits.
- **Automatisches Pinnen am Ende eines Laufs.** Widerspricht der
  Rueckgabe-Regel; der Nutzer tippt den vorhandenen Chip an. (AD-008)
- **Der Scan als Hintergrunddienst.** Verboten durch AK-4, und der 990-Befund
  zeigt, dass die interessanten Faelle ohnehin am Geraet und bewusst
  herbeigefuehrt gemessen werden.
- **Antworten ueber einen Dateipfad uebergeben — in jeder Spielart.** Nicht
  mit besserem Modus, nicht mit unvorhersagbarem Namen, nicht mit kuerzerer
  Lebensdauer. Solange die App den Pfad oeffnet, muss die Datei `o+r` tragen
  (AD-010). Vom `security-reviewer` gegen `chown`, POSIX-ACL, `chgrp` und den
  App-Verzeichnis-Weg geprueft und bestaetigt. Wieder interessant nur, wenn die
  Plattform der Shell einen Weg gibt, eine Datei genau einer fremden Uid
  zugaenglich zu machen — heute gibt es den nicht.
- **Entlinkte Datei in `/data/local/tmp` (Bauform 3) als bevorzugter Weg.**
  Ans Ende der Reihenfolge geschoben: Entlinken wirkt nicht rueckwirkend, und
  bei 0771 genuegt der bekannte konstante Name im Oeffnungsfenster (SR-013).
  Wieder interessant nur, wenn `3'`, `4`, `1` und `2` am Geraet alle scheitern —
  und dann nur mit 0600 beim Anlegen und unvorhersagbarem Namen je Aufruf.
- **Chunking ueber den Binder.** Zurueckgestellt, nicht verworfen: es ist die
  Rueckfalloption, falls der Spike U-0 zeigt, dass SELinux hier gar keine
  FD-Durchreichung zulaesst. Dann zurueck an den `architect`, weil damit auch
  die Aussage "der Helper haelt keinen fachlichen Zustand" faellt.
- **Das Helper-Log nach logcat oder ueber den Binder.** Beide verlieren genau
  die Ausgabe, fuer die es das Log gibt: die eines Helpers, der die Uebergabe
  nie erreicht hat (AD-012). Wieder interessant, wenn der Helper eines Tages
  nicht mehr per ADB-Redirect gestartet wird.
- **Ein Aufraeumen, das `/data/local/tmp` pauschal leert.** Ein privilegierter
  Prozess, der Dateien loescht, ueber die er nichts weiss, ist ein groesserer
  Fehler als die Reste, die er beseitigt (AD-011). Gilt insbesondere fuer
  `btperf` — das gehoert den Messwerkzeugen.
- **Ein eigener `ComparisonRun`.** Zweite Zaehl- und Lueckenregel neben
  `isReadingGap`. Wieder interessant nur, wenn ein Vergleich etwas anderes als
  abgedeckte Intervalle zaehlen muss. (AD-030)
- **Die Ledger-Art „geliehen" und die automatische Rueckgabe beim App-Start.**
  Ohne Leihgabe gibt es nichts zurueckzugeben, und das 990-Pinnen ist das Ziel
  des Nutzers. Wieder interessant, sobald AD-006 beantwortet und eine Leihgabe
  beschlossen ist. (AD-033)
- **Vergleichsergebnisse persistieren.** Neue Datensammlung, und AK-17 hat die
  Aufzeichnungsvariante ausdruecklich nicht gewaehlt. Wieder interessant, wenn
  der Nutzer einen Verlauf ueber Sitzungen will — dann auch fuer AK-16s
  empirischen Sammelsatz. (AD-036, AD-037)
- **WLAN-Band ueber `WifiManager` oder ein Helfer-Kommando.** F3 und AD-005.
  Wieder interessant, wenn der Nutzer A2 genauer als „Funk aus" belegen will.
  (AD-035)
- **Eine MTU-Regel als AK-16-Urteil.** Urteil aus einer Sekundaertabelle ohne
  Messung. Wieder interessant, wenn T-037 den Pakettyp lesbar macht. (AD-036)
- **Das Ledger als Dekorator an den Settings-Ports.** Der einzige Ort, der
  jede Schreibung faengt, braeuchte `suspend` an `write`/`setEnabled` und an
  allen Fakes. Wieder interessant, wenn ein Schreiber das Ledger umgeht. (AD-038)
- **Der Pruefton als `MODE_STATIC`-Puffer je Darbietung.** Braeuchte einen
  anderen `ToneGenerator`-Vertrag, weil der Controller Pulse einzeln schaltet
  und bei Tastendruck abbricht. Wieder interessant, wenn W-7 zeigt, dass der
  Schreib-Thread unterlaeuft und kein Puffermass das behebt. (AD-025)
- **`org.json:json` als Testabhaengigkeit** und **kotlinx.serialization in
  `:core-hearing`.** Das erste testet eine andere Implementierung als die des
  Geraets, das zweite fuehrt ein drittes Muster ins Store-Paket. Wieder
  interessant, wenn alle Stores gemeinsam umziehen sollen — dann als eigene
  Entscheidung ueber alle fuenf, nicht ueber zwei. (AD-026)
- **`Bundle`/Parcelable-Antworten des Helfers.** Keine kleinere
  Angriffsflaeche, aber ein Versionssprung und eine neue Fehlerklasse. Wieder
  interessant nur zusammen mit U-1 (AD-010), wenn die `exec`-Antwort ohnehin
  ihre Form aendert. (AD-027)
- **`@DeleteTable`-AutoMigration.** Zweites Migrationsmuster neben den
  handgeschriebenen. Wieder interessant, wenn der Bestand insgesamt auf
  AutoMigrationen umzieht. (AD-028)
