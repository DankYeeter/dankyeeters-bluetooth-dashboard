# T-029 — Zuordnung Hoereindruck zu gemessener Rate

Erstellt vom **Director** am 2026-09-02. Quellen: `docs/perf/T-029-990-korrelation.md`
(Messseite, `performance-tuner`) und `docs/perf/T-027-hoereindruck.md`
(Hoerseite, 14 Meldungen des App Designers mit Host-Zeitstempel).

**Das ist die erste belegte Zuordnung zwischen gemessener Verlustrate und
Hoereindruck bei KONSTANTER LDAC-Stufe in diesem Projekt.** Alle frueheren
Aussagen zur Hoerbarkeit beruhten auf dem Vergleich verschiedener Stufen und
waren damit konfundiert (T-008). Hier lief die Stufe durchgehend auf fest
990/HIGH, nachgewiesen vor und nach dem Lauf.

Laufzeitraum: 21:16:30 bis 21:41:35 Host-Zeit, 25 min, kein externer Stoerreiz.

## Die Gegenueberstellung

| Host-Zeit | Meldung des Nutzers | Gemessen | Treffer |
|---|---|---|---|
| 21:16:02 | „aktuell nichts" | Ruhephase bis 21:18:50 | **ja** |
| 21:19:16 | „jetzt ein paar" | Cluster 1, 21:18:50–21:19:11, 778 `dropped`/min | **ja**, 5 s danach |
| 21:20:01 | „wieder ruhig, sehr kurz" | Cluster 2 = **ein** Einzelereignis 21:19:58 | **ja**, 3 s danach; „sehr kurz" trifft die Struktur |
| 21:22:38 | „wieder ein ruckler" | Cluster 3 ab 21:22:00 | **ja** |
| 21:22:54 | „jetzt vermehrt" | in Cluster 3 | **ja** |
| 21:23:08 | „ok wieder vorbei anscheinend" | Cluster 3 laeuft bis 21:25:54 | **nein** — Binnenluecke als Ende gedeutet |
| 21:23:30 | „jetzt viel" | in Cluster 3, Bereich der Spitzenwerte | **ja** |
| 21:25:28 | „ruhiger, vereinzelt" | Auslauf von Cluster 3 (endet 21:25:54) | **ja** |
| 21:28:36 | „vereinzelt, **ruhiger als das davor**" | Cluster 4 endet 21:28:36; 474 `dropped`/min gegen 778 in Cluster 1 | **ja, mit korrekter Rangfolge** |
| 21:29:42 | „neuer song, noch keine ruckler" | Ruhephase 21:28:36–21:30:00 | **ja** |
| 21:30:18 | „jetzt sind ruckler" | Cluster 5, 21:30:00–21:30:07 | **ja** |
| 21:32:05 | „eine Minute ruhig, jetzt 10 s voller Ruckeln" | Ruhe 21:30:46–21:31:42 = **56 s**, dann Cluster 7 | **ja, quantitativ** |
| 21:33:18 | „ruhig fuer die ersten 15 Sekunden" | Cluster 7 laeuft bis 21:34:06 | **nein** |
| 21:37:10 | „im Schnitt alle 5–7 s im letzten Song" | nur drei Einzelereignisse in 3 min | **nein**, deutlich ueberschaetzt |
| 21:40:10 / 21:41:11 / 21:43:11 | „fast gar keine" / „sehr ruhig" / „kein einziger" | **kein einziges Ereignis** nach 21:37:13 | **ja** |

**Elf von vierzehn Meldungen treffen**, darunter alle Kanten der grossen
Struktur. Die drei Fehltreffer sind erklaerbar und keine Zufallsfehler: zweimal
wurde eine **Binnenluecke** eines langen Clusters als dessen Ende gehoert
(Cluster 3 dauerte 234 s, Cluster 7 dauerte 144 s), einmal wurde die Dichte
rueckblickend ueberschaetzt — dieselbe Unzuverlaessigkeit des Rueckblicks, die
schon in T-027 auffiel. **Alle drei betreffen Rueckblick oder Dauer, keiner
betrifft die Frage „hoerbar oder nicht".**

## Der Befund, auf den es ankommt

**Hoerbar waren Cluster mit 460–780 `dropped`/min (18–31 `dropouts`/min),
Dauer 7 s bis 234 s.** Jeder einzelne davon wurde gemeldet.

**KORREKTUR, auf Hinweis des App Designers unmittelbar nach der ersten
Auswertung (02.09.):** Der Director hatte aus dem Ausbleiben von Meldungen
geschlossen, isolierte Einzelereignisse seien **nicht hoerbar** gewesen. Das ist
falsch. Der Nutzer stellt klar: *„es gab einzelne ruckler, die habe ich nicht
immer gemeldet."*

**Sie waren hoerbar, sie waren nur nicht meldenswert.** Der Unterschied ist
grundlegend:

- Aus fehlenden Meldungen darf **keine** Unhoerbarkeit abgeleitet werden. Der
  Schluss ist zurueckgenommen.
- Damit ist die **untere Grenze der Hoerbarkeit in diesem Lauf nicht bestimmt**.
  Belegt ist nur: Cluster wurden zuverlaessig gemeldet, Einzelereignisse
  unzuverlaessig — das ist eine Aussage ueber das Meldeverhalten, nicht ueber
  die Wahrnehmung.
- Der urspruengliche Wortlaut („naeher kann eine Selbstauskunft an der Wahrheit
  kaum liegen") beruhte auf demselben Fehlschluss und ist ebenfalls hinfaellig.

**Was den Fehlschluss ueberlebt — und es ist der wertvollere Teil:** Die
**Rangfolge der Dichte** stimmt. Der Nutzer hat aus eigenem Antrieb eine
dreistufige Skala gebildet — „ein paar" < „vermehrt" < „viel" — und sie
korreliert mit den gemessenen Raten, einschliesslich der korrekten Einordnung
von Cluster 4 als „ruhiger als das davor" (474 gegen 778 `dropped`/min). Diese
Zuordnung ist von der Korrektur unberuehrt, weil sie auf **abgegebenen**
Meldungen beruht, nicht auf ausgebliebenen.

**Die tragfaehige Umdeutung des Befundes ist deshalb nicht
„hoerbar gegen unhoerbar", sondern „bemerkbar gegen stoerend":**

- **Bemerkbar** ist offenbar schon das Einzelereignis.
- **Stoerend** — im Sinne von „ich sage etwas dazu" — sind die Buendel.

Das trifft die zweistufige Anlage der Anzeige (`OCCASIONAL` gegen `DISTURBED`)
genauer als die urspruengliche Lesart: Die untere Stufe darf auf einzelne
Ereignisse reagieren, die obere braucht die Buendelung.

## Was das fuer die Anzeige bedeutet — und was es umwirft

**Die hoerbare Groesse ist der Ausbruch, nicht der Minutenmittelwert.**

Cluster 1 trug 11 Episoden in 21 Sekunden. Ueber eine volle Minute gerechnet
sind das 11/min — ein Wert, der unter der bisher gesetzten Alarmschwelle von
12/min liegt und trotzdem klar hoerbar war. Umgekehrt: Verteilt man dieselben
11 Episoden gleichmaessig ueber die Minute, entsteht alle 5,5 s eine einzelne
Stoerung — und Einzelereignisse hat der Nutzer nachweislich **nicht** bemerkt.

**Gleiche Zahl, gegensaetzlicher Hoereindruck.** Eine Rate je Minute kann
Hoerbarkeit deshalb grundsaetzlich nicht abbilden; sie mittelt genau die
Buendelung weg, die den Unterschied macht. Das deckt sich mit R-006: Die
Literatur benutzt Anteile, Lueckenlaengen und Burst-Muster — **eine normierte
Ereignisrate je Zeit taucht dort in keiner Quelle auf.**

Der Nutzer hat das unabhaengig und vor jeder Auswertung so beschrieben:
„immer so 15 Sekunden zwischen Phasen mit Rucklern alle 2-3 Sekunden" und
„kein klarer Rhythmus, immer wieder heissere Phasen, teilweise sehr ruhig".

## Offene Punkte, ausdruecklich nicht ueberdehnt

- **Ein Lauf, eine Person, ein Kopfhoerer, ein Geraet.** Die Zuordnung ist
  belegt, aber nicht wiederholt. Eine zweite Sitzung an einem anderen Tag ist
  noetig, bevor daraus eine Schwelle wird.
- **Die untere Grenze ist nicht eingegrenzt.** Zwischen „isoliertes
  Einzelereignis, nicht bemerkt" und „Cluster mit 18 `dropouts`/min, sicher
  gehoert" liegt ein unvermessener Bereich. Es gab in diesem Lauf keinen
  Cluster mittlerer Dichte.
- **Die Stoerung nahm ueber den Lauf ab** (212,6 → 167,9 → 96,0 `dropped`/min
  ueber die drei Drittel). Position im Lauf ist damit eine Nebenvariable;
  Ausschnitte verschiedener Laeufe sind nicht ohne Weiteres vergleichbar.
- **Die Titel-Hypothese ist offen und eher geschwaecht.** Sie entstand aus drei
  Titeln mit fallender Dichte, wurde aber schon 36 s spaeter dadurch
  erschuettert, dass derselbe Titel erst ruhig war und dann ruckelte. Der
  abnehmende Trend erklaert die Beobachtung ohne das Material.

## Empfehlung an den App Designer (Entscheidung liegt bei ihm)

Die Alarmschwelle als **Rate je Minute** ist nach diesem Lauf nicht nur
unbelegt, sondern **konstruktiv ungeeignet**. Vorzuschlagen ist stattdessen ein
Kriterium ueber die **Buendelung**: mehrere Episoden innerhalb weniger
Sekunden. `LOSS_ALERT_RATE_PER_MIN` waere dann nicht neu zu beziffern, sondern
durch eine andere Groesse zu ersetzen. Das beruehrt `UI_SPEC.md` und AD-019 und
ist deshalb keine Entscheidung des Directors.
