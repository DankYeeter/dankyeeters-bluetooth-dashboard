# Auflagenregister

Stehendes Register aller rechtlichen Auflagen. Angelegt 2026-09-28 mit C-001.
Fortfuehren, nie ueberschreiben. Status setzt der Nutzer oder der Director;
der compliance-agent prueft im Modus `pruefen` und traegt das Datum ein.

Pflicht ab: **V** = vor Veroeffentlichung (Play oder erste Weitergabe) ·
**P** = nur fuer den Play-Build · **G** = ab Geld (Kaufpreis, IAP, Werbung) ·
**D** = ab erster Datenuebertragung vom Geraet · **E** = empfohlen

| ID | Auflage | Grund/Norm | Rolle | Pflicht ab | Status | Letzte Pruefung |
|---|---|---|---|---|---|---|
| A-001 | Setup und Store-Text legen Helfer vollstaendig offen (Entwickleroptionen, Wireless Debugging, Shell-Prozess, WRITE_SECURE_SETTINGS, geschriebene Einstellungen, Auto-Toggle nach Neustart) mit Zustimmung vor erster Verbindung | Play MUwS-Policy (C-001 Nr. 1) | technical-writer, developer | P | offen | 2026-09-28 |
| A-002 | Jede Systemaenderung vorher gesichert und rueckgaengig machbar (AK-11) | Play MUwS (C-001 Nr. 1) | developer | P | offen (AK-11 teils gebaut) | 2026-09-28 |
| A-003 | Helfer bleibt auf die beworbenen Funktionen beschraenkt (AK-10) | Play Permissions-Policy (C-001 Nr. 1) | architect, security-reviewer | P | laufend | 2026-09-28 |
| A-004 | BLUETOOTH_PRIVILEGED aus dem Play-Build entfernen | C-001 Nr. 1, Review-Risiko | developer | E | offen | 2026-09-28 |
| A-005 | QUERY_ALL_PACKAGES aus dem Play-Build entfernen; Erkennung ueber `<queries>`; UI sagt "nur bekannte EQ-Apps" | Play Package-Visibility-Policy (C-001 Nr. 2) | developer | P | offen | 2026-09-28 |
| A-006 | Manifest-Kommentar Z. 36–39 und docs/archiv/PLAN.md an Play-Absicht anpassen | Widerspruch Bestand/Einreichung (C-001 Nr. 2) | developer, technical-writer | P | offen | 2026-09-28 |
| A-007 | Foreground-Service-Typ connectedDevice in Play Console deklarieren (Beschreibung, Auswirkung, Demo-Video) | Play FGS-Policy (C-001 Nr. 3) | release-manager, qa-engineer | P | offen | 2026-09-28 |
| A-008 | Store-Text nennt jede Berechtigung mit Funktion; INTERNET als "nur eigenes Geraet" | Play Permissions-Policy (C-001 Nr. 3) | technical-writer | P | offen | 2026-09-28 |
| A-009 | Mikrofon nur im Hoertest anfordern, Ablehnung ohne Funktionsverlust ausserhalb | Play Permissions-Policy (C-001 Nr. 3) | developer | P | pruefen | 2026-09-28 |
| A-010 | Datenschutzerklaerung als oeffentliche URL und in der App (Inhalt: C-001 Nr. 4) | Play User-Data-Policy (C-001 Nr. 4) | technical-writer | P | offen | 2026-09-28 |
| A-011 | Data-Safety-Formular ausfuellen; Backup- und Paketlistenfrage vorher klaeren (T-052b) | Play Data-Safety (C-001 Nr. 4) | release-manager | P | offen | 2026-09-28 |
| A-012 | In-App-Loeschung fuer Historie, Audiogramme, Profile | Erwartung an Gesundheitsdaten (C-001 Nr. 4/5) | developer | E | offen | 2026-09-28 |
| A-013 | Entscheidung "unentgeltlich, keine Werbung" festhalten; in Play Console als Nicht-Unternehmer deklarieren | § 5 DDG, DSA Art. 3 lit. f / 30 (C-001 Nr. 6) | Nutzer, director | P | offen | 2026-09-28 |
| A-014 | THIRD_PARTY_LICENSES.md um Conscrypt ergaenzen, Robolectric korrigieren; Lizenztexte als In-App-Seite | Apache-2.0 § 4 (C-001 Nr. 7) | technical-writer, developer | V | offen | 2026-09-28 |
| A-015 | Lizenzpruefung neuer Abhaengigkeiten vor jedem Release (GPL/AGPL = ROT) | Apache-2.0/Copyleft (C-001 Nr. 7) | release-manager | V, laufend | offen | 2026-09-28 |
| A-016 | Zweckbestimmung der Hoerkompensation schriftlich festlegen; UI- und Store-Texte daran ausrichten | Play Health-Policy, MDR (C-001 Nr. 8) | technical-writer, ui-ux-designer | V | offen | 2026-09-28 |
| A-017 | Play-Health-Deklaration; Disclaimer "not a medical device …" und Hinweis auf aerztlichen Rat im Store-Text | Play Health-Policy (C-001 Nr. 8) | release-manager, technical-writer | P | offen | 2026-09-28 |
| A-018 | Anwaltliche/regulatorische Pruefung der MDR-Frage vor erster Weitergabe einer Fassung mit Hoerkompensation | MDR Art. 2 Nr. 1 (nicht verifiziert) (C-001 Nr. 8) | Nutzer | V | offen | 2026-09-28 |
| A-019 | Neue Klaerung vor erster Datenuebertragung vom Geraet (Crash-Reporter, Analytik, Sync) | DSGVO Art. 4 Nr. 7, 13, 9 (C-001 Nr. 5) | director | D | — | 2026-09-28 |
| A-020 | Neue Klaerung vor erster Monetarisierung (Impressum, Traderstatus, Verbraucherrecht) | § 5 DDG, DSA Art. 30 (C-001 Nr. 6) | director | G | — | 2026-09-28 |
