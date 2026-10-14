# T-047i — S3-6 Geführter Vergleich (developer), 23.09.2026

Stand: erledigt, Commits `35175e7`, `cc654cc`, `dc855a1` auf
`worktree-agent-a0f2042a7f9fedb41` (Basis `6f192a3`).

## Befunde an ui-ux-designer (Spec-Lücken, gebaut mit vorläufigem Wortlaut)

1. **Instruct hat keinen Auslöser für die erste Rücklesung.** Die Spec nennt
   nur „Check again" bei `CONTRADICTED`. Läse die App sofort beim Eintritt,
   startete Arm B bei `NOT_VERIFIABLE` (A1, A4, A7, WLAN „on") bevor der Nutzer
   irgendetwas getan hat. Gebaut: Knopf `"Start Arm B"`, der liest und nur bei
   nicht-`CONTRADICTED` startet. Wortlaut offen.
2. **`DISCOVERY_SEEN` hat keinen Bedingungsnamen** (Zustandsbuch-Zeile,
   „Not checked"-Liste). Gebaut: `"device discovery"`.
3. **Instruct ohne „Cancel"** laut Zustandstabelle — der Nutzer käme dort nur
   über den Prozesstod heraus. Gebaut: `"Cancel"` wie in den anderen Phasen.
4. **„Compare" zusätzlich nur bei lesbarer Rate** (`hasReadableRate`, wie
   „Start" in T-039): sonst pinnt die App 990 (eine Schreibung) für einen Arm,
   der bei der ersten Lesung endet.
5. **Rücklesesatz auch in Arm B sichtbar** (nach `VERIFIED`/`NOT_VERIFIABLE`
   geht Instruct sofort in Arm B über, der Satz stünde sonst nirgends).
6. **„Wortgleich" eingebettete Grundzeile** verliert ihren Schlusspunkt vor der
   Klammer: `"Arm A ended before 15 minutes (Run ended … 3 min observed). Starting Arm A again."`

## Befunde an director

- **Arm-Neustart nach `QUALITY_CHANGED`/`CODEC_CHANGED`** startet laut AD-038
  sofort neu, auch wenn die Verbindung nicht mehr bei 990 steht. Der Rahmen
  nennt dann ehrlich die gemessene Stufe (`"Both pinned to {Stufe des Laufs}"`),
  aber der Arm misst nicht mehr bei 990. Kein Schutz gebaut (nicht in der Spec).
- **„Watch live" (Bluetooth-Tab) navigiert ohne `restoreState`** auf Monitoring
  und legt eine neue Monitor-Instanz an; ein laufender Vergleich liegt dann im
  gespeicherten Tab-Zustand und ist erst über die Leiste wieder sichtbar.
  Tabwechsel über die Leiste hält Arm A (Pflichttest grün).
- **`MonitorViewModel` ist jetzt `AndroidViewModel`** (Zustandsbuch braucht Context).
- Lücke, kein Beleg: der `busy`-Zweig von `LdacTuning.pin` (liefert
  `Unavailable`) ist nicht auslösbar getestet — der Zustand ist privat im Objekt.
