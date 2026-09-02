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

## Struktur

| Modul | Verantwortung |
|---|---|
| `:core-audio` | DSP- und EQ-Grundtypen |
| `:core-hearing` | Hoertest und Kompensationskurven |
| `:core-monitor` | Beobachtung der Strecke: Parser, Live-Quelle, Ereignisse, Room-Historie, gefuehrte Laeufe (`diagnostic/`) |
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
`HANDOVER.md`; sie dort zu suchen ist zuverlaessiger, als sie hier aus dem
Gedaechtnis zu rekonstruieren.

---

## Entscheidungen

### AD-001 — Diese Datei beginnt beim heutigen Stand, nicht bei Null (2026-08-31, Status: aktiv)

**Kontext:** `ARCHITECTURE.md` existierte nicht. Die Struktur ist gewachsen und
im KDoc ungewoehnlich gut begruendet; `HANDOVER.md` traegt 222 KB Verlauf.
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

---

### AD-003 — Zwei Phasen: Bestandsaufnahme immer, Belege einzeln freigegeben (2026-08-31, Status: aktiv)

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

### AD-005 — WLAN-Fakten als typisierte, lesende Helper-Operation, nicht als Whitelist-Exec (2026-08-31, Status: aktiv — **braucht Freigabe durch `security-reviewer` und `director`**)

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

### AD-006 — Zwei Scan-Schalter duerfen geliehen werden; das loest eine dokumentierte Gegenposition ab (2026-08-31, Status: aktiv — **braucht Antwort des App Designers, offene Frage 1**)

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

### AD-007 — Geliehene Einstellungen haben ein persistiertes Ledger in `:core-system` (2026-08-31, Status: aktiv)

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

### AD-008 — Der Treppen-Optimierer wird Maschine des Scans, nicht eigenes Feature (2026-08-31, Status: aktiv)

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

### AD-009 — Der Diskriminator laeuft vor der Umgebungserfassung (2026-08-31, Status: aktiv)

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
dieselbe Gattung wie `DeviceDiagnosticRunner` und wie AD-003 es fuer den Scan
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
