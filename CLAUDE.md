# dankyeeters-bluetooth-dashboard

## Repo-Kategorie

**Kategorie: privat**

Erlaubte Remotes: ausschließlich der persönliche GitHub-Account
`github.com/DankYeeter`.

Dieses Projekt enthält keinen Firmencode und keine Firmendaten. Es wird
niemals in ein Firmen-Remote gepusht.

Deklariertes Remote: `https://github.com/DankYeeter/dankyeeters-bluetooth-dashboard.git`

Der `archivist` prüft vor jedem Push `git remote -v` gegen diese Angabe.
Weichen Remote und Kategorie voneinander ab, wird **nicht** gepusht,
sondern gemeldet. Das ist eine arbeitsrechtliche Grenze und hat Vorrang
vor jeder anderen Regel.

## Arbeitskonventionen

Aus `docs/lessons.md` abgeleitet, **vom Nutzer angenommen 2026-09-03**. Sie
gelten fuer **alle Rollen** in diesem Projekt, den Director eingeschlossen.

### K-1 — Abwesenheit wird geprueft, nicht geschlossen (L-002)

Bevor du sagst oder schreibst, dass etwas **nicht existiert**, **nicht
geliefert**, **nicht dokumentiert** oder **nicht abgedeckt** ist, pruefst du es
**direkt an der primaeren Quelle** — Dateisystem, Git, die Datei selbst.
Niemals aus einer abgeleiteten Quelle: nicht aus `docs/state.md`, nicht aus
einem fremden Bericht, nicht aus einer Fehlermeldung, nicht aus dem Ausbleiben
einer Rueckmeldung.

**Warum diese Regel existiert — drei Vorkommen in drei Tagen:**
1. Ein Zielbild galt als fehlend, weil eine Suche nach Bezeichnern es nicht
   fand — es stand unter anderem Namen da.
2. Zwei `researcher`-Laeufe meldeten einen Abbruch am Nutzungslimit. Der
   Director schloss daraus, sie haetten nichts geliefert. **Beide Dateien lagen
   vollstaendig auf der Platte** — der Abbruch traf nur den Abschlussbericht.
3. Eine laengst erledigte Notiz in `docs/state.md` behauptete, ein Commit sei
   durch PII blockiert. Der `archivist` las sie und meldete den Falschbefund
   weiter. Die Dateien waren seit Stunden redigiert und committet.

**Ein gemeldeter Agentenabbruch heisst nicht, dass nichts geschrieben wurde.**
Vor jeder Aussage „nicht geliefert“ gehoert ein Blick ins Dateisystem.

Eine falsche Abwesenheits-Behauptung ist teurer als eine unterlassene: Sie
loest einen Auftrag aus, der etwas Vorhandenes noch einmal beschafft.

### K-2 — Ein Textbefund ist erst nach projektweiter Suche behoben (L-003)

Betrifft Befunde an Kommentaren, KDoc, Doku, Fehlermeldungen und jeder anderen
wiederholten Textbehauptung: Der Fix ist **erst fertig, wenn projektweit
gesucht** wurde — nicht, wenn die im Auftrag genannten Stellen erledigt sind.
Das Suchergebnis gehoert in den Bericht, auch wenn es leer ist.

**Warum:** QA-016 wurde dreimal an Stellen behoben, die jemand **zufaellig
gesehen** hatte. Erst ein erzwungener Grep fand den vierten Fundort und belegte,
dass es keinen fuenften gibt. Vier Runden fuer einen Kommentarfehler.

**Ein Zufallsfund ist kein Suchergebnis.** Wer nur die genannten Stellen
abarbeitet, behebt Symptome in der Reihenfolge, in der sie zufaellig auffallen.
