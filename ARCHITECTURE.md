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

## Bewusst nicht getan

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
