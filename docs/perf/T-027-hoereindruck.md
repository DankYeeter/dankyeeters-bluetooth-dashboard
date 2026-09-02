# T-027 — Hoereindruck des App Designers

Gefuehrt vom **Director**, nicht vom `performance-tuner`. Getrennte Datei mit
Absicht: Der Messagent darf ueber Hoerbarkeit nichts schreiben (R-006 —
es gibt dafuer keine Literaturschwelle, jede Schaetzung waere eine erfundene
Zahl). Die Zuordnung Rate zu Hoereindruck entsteht ausschliesslich hier.

Gegenstelle: Kopfhoerer am Pixel 11 Pro, LDAC fest auf **660 kbps**
(„Ausgewogene Audio- und Verbindungsqualitaet"), Musik durchgehend.
Zellenzeiten siehe `docs/perf/T-027-messung.md`.

## Protokoll 2026-09-02

| Zeitraum | Zelle | Stimulus | Aussage des Nutzers | Wortlaut |
|---|---|---|---|---|
| 18:11:35–18:15:58 | `level0_control` | keiner | nicht gesondert abgefragt | — |
| 18:16:19–ca. 18:40 | `level1_1stream` | 1 Strom, ~330–346 Mbit/s auf 5 GHz | **keine Aussetzer** | „keine aussetzer" |

### 2,4-GHz-Leiter (Phase 4), rueckblickend abgefragt — ERGEBNISLOS

| Zeitraum (Geraetezeit) | Zelle | Gemessen | Aussage des Nutzers |
|---|---|---|---|
| 19:24:04–19:28:23 | 1 Strom | 39,3 `dropped`/min, **1,71 `dropouts`/min** | **"Weiss ich nicht mehr sicher"** |
| 19:29:53–19:55:49 | 2/4/8/16/Rueckkehr | durchgehend 0/0 | **"Weiss ich nicht mehr sicher"** |

**Das ist kein Datenverlust durch Nachlaessigkeit, sondern ein Befund ueber das
Verfahren: Rueckblickendes Abfragen funktioniert nicht.** Der Nutzer hoerte
nebenbei Musik, ohne zu wissen, wann ein Reiz anlag; nach einer halben Stunde
ist die Erinnerung an einzelne kurze Aussetzer nicht mehr belastbar. Eine
geratene Erinnerung waere hier genau die erfundene Zahl, die T-027 beseitigen
soll — die Antwort "weiss ich nicht" ist deshalb die richtige und wird als
solche gewertet.

**Konsequenz fuer die naechste Sitzung: Die Hoersitzung muss vorwaerts
gefuehrt werden, nicht rueckwaerts.** Anforderungen:

1. **Aktiv statt nebenbei.** Der Nutzer hoert bewusst zu und meldet in dem
   Moment, in dem er etwas bemerkt — nicht hinterher aus der Erinnerung.
2. **Blind.** Er darf nicht wissen, wann der Reiz anliegt. Sonst misst man
   Erwartung statt Wahrnehmung. Reiz- und Kontrollabschnitte werden in
   zufaelliger Reihenfolge gefahren und erst nach der Sitzung aufgeloest.
3. **Kurze Abschnitte, mehrfach wiederholt.** Vier Minuten am Stueck sind zum
   bewussten Hinhoeren zu lang; zwei bis drei Minuten je Abschnitt, dafuer
   jede Bedingung mehrfach.
4. **Zelle 1 wird dabei wiederholt.** Das beantwortet nebenbei die offene
   Frage, ob der Ausschlag von 19:24 ueberhaupt reproduzierbar ist — bisher
   ist er ein einzelnes, unwiederholtes Ereignis.

### T-028, blinde Sitzung — Meldungen des Nutzers (laufend)

| Host-Zeit | Meldung im Wortlaut | Einordnung |
|---|---|---|
| ca. 20:32:46–20:34:07 | „wlan war kurz weg. keine stoerungen waehrend abwesenheit und jetzt wiederverbindung" | **Unaufgeforderte Beobachtung, nicht abgefragt.** WLAN-Ausfall unabhaengig bestaetigt: Trennung 20:34:17, Wiederverbindung 20:35:38 Geraetezeit (Versatz ~91 s), Dauer rund 81 s. |

| bis 20:58:58 | „nach wie vor keine stoerung ueber die gesamte dauer" | **Null Meldungen ueber neun der zehn Abschnitte.** Zu diesem Zeitpunkt waren nach Reizplan bereits mehrere STIM-Abschnitte gelaufen (u. a. 20:37:40 und 20:48:48). Der Nutzer wusste das nicht — die Blindheit war zu keinem Zeitpunkt verletzt. |

**Warum diese Meldung zaehlt:** Sie ist der erste Punkt der Sitzung, an dem
Hoereindruck und physikalische Bedingung unabhaengig zusammenfallen — ohne
WLAN-Assoziation gibt es keine 2,4-GHz-Belegung, und der Nutzer hoerte in genau
diesem Fenster nichts. Das ist mit dem angenommenen Mechanismus vereinbar. Ein
einzelnes Fenster belegt ihn nicht, aber es widerspricht ihm auch nicht.

**Kanalwechsel waehrend der Sitzung:** Der Access Point ist beim Wiederverbinden
von 2462 MHz (Kanal 11) auf **2437 MHz (Kanal 6)** gewechselt. Bedingungsaenderung
mitten im Lauf, protokolliert statt weggebuegelt; das Pruefkriterium wurde von der
festen Zahl auf „irgendeine Frequenz im 2,4-GHz-Band" umgestellt.

### Hypothese „der Stoerreiz zerstoert sich selbst" — ungeprueft, aber gestuetzt

Zwei unabhaengige Assoziationsverluste unter Last: 19:48:49 mitten in der
16-Strom-Zelle von T-027 Phase 4, und 20:34:17 waehrend T-028. **Wenn die
WLAN-Last die Verbindung abreissen laesst, verschwindet mit der Assoziation auch
die Stoerung** — der Reiz hebt sich selbst auf. Das wuerde das nicht-monotone
Muster aus Phase 4 vollstaendig erklaeren: bei einem Strom hielt die Verbindung
und stoerte, bei 8 und 16 Stroemen brach sie weg und stoerte deshalb *weniger*.

Kein Zufall noetig, keine Saettigung noetig. **Pruefbar** und wird in T-028
mitgeprueft: Treten die Ausfaelle bevorzugt in STIM-Abschnitten auf?

**ABGESCHWAECHT durch eine Auskunft des Nutzers (02.09., waehrend T-028):** Seine
Internetanbindung ueber DSL ist generell instabil, das Problem ist ihm bekannt
und besteht unabhaengig von dieser Messung. Damit gibt es eine **konkurrierende
Erklaerung** fuer beide Assoziationsverluste, die ohne den Stoerreiz auskommt:
schlicht Grundinstabilitaet des Netzes. Ein DSL-Resync kann einen Router zum
Neustart oder zu einem WLAN-Aussetzer bringen.

Wichtige fachliche Abgrenzung, damit die Erklaerung nicht falsch angewandt wird:
**Der Stimulus selbst laeuft rein lokal** — Geraet zu Host-PC ueber WLAN,
192.168.178.x, er verlaesst das Heimnetz nie und beruehrt die DSL-Strecke nicht.
Die DSL-Qualitaet kann also die *Last* nicht beeinflussen, wohl aber die
Stabilitaet der *Assoziation*, wenn der Router darauf reagiert.

**Die Sitzung entscheidet zwischen beiden Erklaerungen**, ohne Zusatzaufwand:
Haeufen sich die Ausfaelle in STIM-Abschnitten, war es der Reiz. Verteilen sie
sich gleichmaessig ueber STIM und KONTROLLE, war es das Netz. Bis dahin ist die
Hypothese **offen, nicht gestuetzt** — mein frueherer Wortlaut „gestuetzt" war
voreilig.

**Verfahrensregel, vom Nutzer gesetzt:** Die Netzinstabilitaet wird nicht
untersucht. Die Assoziationspruefung je Abschnitt bleibt trotzdem bestehen —
nicht um das Netz zu diagnostizieren, sondern weil ein Abschnitt ohne
Assoziation kein Reiz ist und sonst stillschweigend als solcher in die
Auswertung ginge.

### T-028 — Ergebnis der blinden Sitzung (02.09., abgeschlossen)

Zehn Abschnitte gefahren, **acht gueltig** (Abschnitt 4 KONTROLLE ungueltig
wegen WLAN-Ausfall im Fenster; Abschnitt 5 STIM ungueltig, weil der Sink-Server
zwei Sekunden vor Beginn starb und kein Reiz ankam — beides erkannt und
markiert, nicht stillschweigend mitgezaehlt).

**Messseite:** In allen acht gueltigen Abschnitten — 4× STIM mit real
bestaetigter Dosis 3,6–7,1 Mbit/s, 4× KONTROLLE — war Δ`dropped`/Δ`dropouts`
durchgehend **0/0**. Kein Unterschied zwischen den Gruppen, keine Streuung.

**Hoerseite:** Der Nutzer hat ueber die gesamte Sitzung **keine einzige
Stoerung** gemeldet. Die Blindheit war zu keinem Zeitpunkt verletzt.

**Beide Seiten stimmen ueberein: nichts gemessen, nichts gehoert.**

#### Was daraus folgt

1. **Der Ausschlag aus T-027 Phase 4, Zelle 1 ist NICHT reproduzierbar.** Dort
   39,3 `dropped`/min und 1,71 `dropouts`/min bei einem Strom; hier bei
   vergleichbarer Dosis viermal exakt null. Der Einzelbefund war mit hoher
   Wahrscheinlichkeit **kein Reizeffekt**, sondern ein Ereignis, das zufaellig
   in dieses Fenster fiel. **Er taugt nicht als Kalibrierpunkt.**
2. **M-11 bleibt unmessbar** — mit diesem Hebel und bei diesen Dosen. Es gibt
   weiterhin **keinen** gemessenen Punkt zwischen 0 und den 13 `dropouts`/min
   des gepinnten 990er-Arms, und die zwei alten Punkte bleiben konfundiert.
3. **Die Hypothese „der Stoerreiz zerstoert sich selbst" ist widerlegt, nicht
   nur abgeschwaecht.** Der einzige WLAN-Ausfall der Sitzung fiel in eine
   **KONTROLLE**-Bedingung, also ohne jede Last. Damit traegt die Erklaerung des
   Nutzers — Grundinstabilitaet seines Netzes —, und meine nicht. Sie erklaert
   das nicht-monotone Muster aus Phase 4 folglich **nicht**; das bleibt offen.
4. **Die Ehrlichkeitsregeln haben gehalten.** Kein Wert wurde geschaetzt, kein
   Abschnitt geglaettet, keine Dosis heimlich erhoeht, um doch noch ein Signal
   zu erzeugen. Das Ergebnis ist ein Nein, und es steht als Nein da.

## Anmerkungen zur Methodik

- Der Nutzer hat von sich aus darauf hingewiesen, dass die Stufe weiterhin auf
  „Balanced" (660) steht und nicht auf „Best Audio Quality" (990). **Das ist
  die Versuchsbedingung, kein Versehen** — siehe unten.
- Die Abfrage erfolgt offen („hast du etwas gehoert"), nicht suggestiv
  („klingt es jetzt schlechter"). Ein Hinweis darauf, welche Stufe gerade
  anliegt, wird dem Nutzer waehrend der Zelle **nicht** gegeben.

## Warum die Stufe auf 660 bleibt — und was ein Wechsel auf 990 zerstoeren wuerde

Auf 990 gepinnt treten sofort hoerbare Aussetzer auf; das ist seit T-008
belegt (525 `dropped` / 21 `dropouts`, 13/min, durchgehend hoerbar). Dieser
Befund ist aber **wertlos fuer eine Schwelle**, weil dort Stufe und
Verlustrate gemeinsam wandern: Belegt ist „990 gepinnt klingt kaputt", nicht
„13/min sind hoerbar". Genau diese Konfundierung ist der Grund, warum die
bestehende Schwelle `LOSS_ALERT_RATE_PER_MIN` = 12/min als erfunden gilt.

Der ganze Zweck von T-027 ist, die Stufe **konstant** zu halten und allein den
Verlust zu variieren. Ein Wechsel auf 990 waehrend der Messreihe wuerde exakt
den Fehler wiederholen, den diese Messreihe beheben soll.

## Was „keine Aussetzer" hier bedeutet

Bleibt es bis Stufe 16 dabei **und** zeigen die Zaehler ebenfalls null, ist das
ein belastbares negatives Ergebnis: Last auf dem 5-GHz-Link erreicht den
Bluetooth-Pfad nicht. Das waere kein Fehlschlag, sondern der Beleg, dass es den
echten 2,4-GHz-Hebel braucht — und damit die Begruendung fuer eine
Router-Aenderung (eigene 2,4-GHz-SSID) oder ein zweites Bluetooth-Geraet als
Stoerer in der naechsten Sitzung.

## T-029 — Meldungen bei fest 990 kbps (laufend, Host-Zeit)

| Host-Zeit | Meldung | Art |
|---|---|---|
| 21:16:02 | "aktuell nichts" | Ruhephase, unaufgefordert gemeldet |
| 21:19:16 | "jetzt ein paar" | **Stoerereignis**, mehrere Aussetzer, unaufgefordert |
| 21:20:01 | "aktuell ruhig wieder" + "sehr kurz nur" | **Ende des Stoerereignisses.** Episode 21:19:16 bis ca. hier, vom Nutzer als sehr kurz beschrieben |
| 21:22:38 | "da war wieder ein ruckler" | **Stoerereignis**, Einzelaussetzer, unaufgefordert |
| 21:22:54 | "jetzt vermehrt" | **Stoerereignis, hoehere Dichte** — Beginn einer dichteren Phase, unaufgefordert |
| 21:23:08 | "ok wieder vorbei anscheinend" | **Ende der dichteren Phase.** Episode 21:22:38 bis hier (Einzelruckler, dann vermehrt, dann Ende) |
| 21:23:30 | "jetzt viel" | **Stoerereignis, hoechste bisher gemeldete Dichte** (Skala des Nutzers: "ein paar" < "vermehrt" < "viel") |
| 21:25:28 | "wieder ruhiger aber immer noch vereinzelt stoerungen.. schwer ein muster zu erkennen. immer so 15 sekunden zwischen phasen mit rucklern alle 2-3 sekunden." | **STRUKTURBEOBACHTUNG, nicht nur Intensitaet.** Der Nutzer beschreibt Buendelung: Stoerphasen im Abstand von ca. 15 s, innerhalb einer Phase Ruckler alle 2-3 s. Direkt gegen die Zaehlerreihe pruefbar. |

### T-029 ZWISCHENAUSWERTUNG (Director, 21:27) — die Zuordnung TRAEGT

Laufstart 21:16:30 Host. Meldungen des Nutzers gegen die Zaehlerreihe gelegt
(Rohreihe: Scratchpad t029_parsed4.txt, 355 Samples, Spanne 469 s):

| Meldung | Zaehlerreihe |
|---|---|
| 21:19:16 "ein paar" | Ereignisse 21:18:50-21:19:11, unmittelbar davor |
| 21:20:01 "wieder ruhig, sehr kurz" | letztes Ereignis 21:19:58 — drei Sekunden vorher |
| (keine Meldung) | 21:19:58-21:22:00 exakt null, zwei Minuten Stille |
| 21:22:38 "wieder ein ruckler" | neuer Block ab 21:22:00 bis 21:22:40 |
| 21:22:54 "vermehrt" | dichtester Teil dieses Blocks |
| 21:23:08 "wieder vorbei" | Luecke 21:22:40-21:23:04 |
| 21:23:30 "viel" | **Maximum des Laufs**: +100 und +126 dropped in Einzelsekunden |

**Jede Kante getroffen, in beide Richtungen** — Beginn, Ende, die zwei Minuten
Stille, und die staerkste Phase als "viel". Das ist die erste belegte Zuordnung
zwischen gemessener Rate und Hoereindruck bei KONSTANTER Stufe in diesem Projekt.

**Die Strukturbeobachtung des Nutzers ist ebenfalls bestaetigt:** Innerhalb eines
Blocks liegen die Ereignisse 2-4 s auseinander (seine "alle 2-3 Sekunden"), die
Luecken zwischen Bloecken betragen 24 s und 12 s (seine "so 15 Sekunden").

**Nicht gesuchter Nebenfund, der R-005 unabhaengig bestaetigt:** Fast jedes
Ereignis lautet exakt **+25 dropped je +1 dropouts**. Eine Episode ist eine
Raeumung der Sendewarteschlange mit festem Schwung. Erklaert rueckwirkend die
525/21 = 25,0 aus T-008.

**Verteilung im Lauf (30-s-Fenster, n=354):** dropped/min Median 0, Maximum
912, Nullanteil 52,5 Prozent, Streuung 279. dropouts/min Median 0, Maximum 36,4.
**Die Rate schwankt bei fester Stufe massiv** — die Voraussetzung des
T-029-Ansatzes ist damit erfuellt.
| 21:28:36 | "das lied hat vereinzelt ruckler. ruhiger als das davor." | **Vergleichende Intensitaetsangabe UND neue Variable:** Der Nutzer bezieht die Dichte erstmals auf den TITEL. Titelwechsel waehrend des Laufs waren nicht kontrolliert — moeglicher Einflussfaktor (Abtastrate der Quelle, Resampling, Encoder-Last). Als offener Punkt vermerkt, nicht als Befund. |
| 21:28:47 | "rhytmus eher so alle 3-5 sekunden unterbrochen von ruhigeren phasen" | **Zweite Strukturbeobachtung, geaenderter Takt.** Vorher (21:25:28) meldete er Ruckler alle 2-3 s in Bloecken mit ca. 15 s Abstand; jetzt 3-5 s. Der Takt selbst ist damit veraenderlich, nicht konstant. Direkt gegen die Ereignisabstaende pruefbar. |
| 21:29:42 | "neuer song. noch keine ruckler seit er spielt" | **Dritter Titelwechsel, sofort ruhig.** Stuetzt die Titel-Hypothese von 21:28:36: erst dichte Ruckler, dann "ruhiger als das davor", jetzt null seit Titelbeginn. Drei Titel, drei Dichten. Noch kein Beleg (Titel nicht wiederholt, Reihenfolge nicht kontrolliert), aber ein Muster. |
| 21:30:18 | "jetzt sind ruckler" | **Stoerereignis im dritten Titel** — der Titel begann 21:29:42 ruhig, ruckelt jetzt doch. **Schwaecht die Titel-Hypothese**: derselbe Titel zeigt beide Zustaende, die Dichte haengt also nicht allein am Material. |
| 21:32:05 | "seitdem war wieder eine minute ruhig und jetzt 10 sekunden voller ruckeln" | **Praezise Episodenbeschreibung mit Dauern.** Ca. 1 min Ruhe nach 21:30:18, danach ca. 10 s dichtes Ruckeln. Bestaetigt erneut das Bild: lange Ruhestrecken, kurze dichte Ausbrueche — nicht gleichmaessige Stoerung. |
| 21:33:18 | "neuer song. ruhig fuer die ersten 15 sekunden" | Vierter Titelwechsel, Beginn ruhig. Beim dritten Titel (21:29:42) war es genauso, dort begann das Ruckeln erst nach ca. 36 s — moegliche Regelmaessigkeit "ruhig direkt nach Titelwechsel", pruefbar an den Ereignisabstaenden. Noch zu wenig Faelle. |
| 21:37:10 | "im schnitt alle 5-7 sekunden im letzten song. jetzt neues lied" | **Dritte Taktangabe, Trend sichtbar:** 2-3 s (21:25:28), 3-5 s (21:28:47), jetzt 5-7 s. Der Abstand waechst ueber den Lauf, die Stoerung nimmt also ab. Fuenfter Titelwechsel. |
| 21:37:46 | "kein klarer rhytmus. immer wieder heissere phasen. teilweise sehr ruhig" | **Abschliessende Gesamtcharakterisierung des Laufs.** Der Nutzer nimmt seine fruehere Taktangabe damit teilweise zurueck: kein fester Rhythmus, sondern wechselnde Dichte mit sehr ruhigen Strecken. Deckt sich mit der Verteilung (Nullanteil 52,5 Prozent, Maximum 912 dropped/min). |
| 21:40:10 | "neues lied. das lied hatte fast gar keine aussetzer. wenn ueberhaupt einen." | Sechster Titelwechsel. Der vorherige Titel (ab 21:37:10) war ueber ca. 3 min nahezu stoerungsfrei — der ruhigste Abschnitt seit Laufbeginn, und er faellt ans Laufende. Stuetzt den Trend abnehmender Stoerung, nicht die Titel-Hypothese. |
| 21:41:11 | "aktuelles lied auch sehr ruhig. kein ruckler bisher wahrgenommen." | Siebter Titel, ebenfalls ruhig. Damit rund 5 min nahezu stoerungsfrei am Laufende, ueber zwei Titelwechsel hinweg. Bestaetigt den abnehmenden Trend titeluebergreifend. |
| 21:43:11 | "song vorbei. kein einziger ruckler." | **Letzte Meldung.** Ein vollstaendiger Titel ohne einen einzigen Aussetzer, unmittelbar am Laufende (Messlauf endete 21:41:35). Nachlaufend — faellt teilweise ausserhalb des Messfensters. |
