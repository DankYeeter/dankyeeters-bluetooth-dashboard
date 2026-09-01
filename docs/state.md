# Stand — 2026-09-01

Kurzfassung fuer die Agenten. Historie in `HANDOVER.md`, Zielbild in
`GOAL.md`.

## Der Befund, der alles andere verschiebt

**Auf dieser Strecke bleiben die Verlustzaehler dauerhaft null — und
trotzdem ist Qualitaet verloren.** Gemessen am 01.09. ueber 318 s, App
deinstalliert, Musik laufend (T-007, `docs/perf/T-007-aufnahme.md`):

- LDAC im **ABR**-Modus (nicht gepinnt), 96 kHz / 32 bit / Stereo,
  **Host-Encoding** (fuenf unabhaengige Belege) — die Zaehler bedeuten
  also etwas.
- **Null** Underflows, null Dropouts, null Flushes. `SavedTxQueue` in 260
  von 262 Samples null.
- Stattdessen eine **Bitratenschaukel**: ABR pendelt 50/50 zwischen 660
  und 492 kbps, im Mittel alle 11,2 s ein Wechsel. 990 wird **nie**
  angesteuert. Die beiden einzigen Samples mit gefuellter Sendeschlange
  liegen genau auf den Abstiegen 660 → 492.
- Mechanismus dazu belegt in R-001: **LDAC-ABR entscheidet einzig anhand
  der Sendeschlangentiefe** — nicht RSSI, nicht Paketverlust.

> Der Encoder verliert nichts, **weil er vorher nachgibt.** Der
> Qualitaetsverlust steht nicht in den Verlustzaehlern, er steht in der
> Bitrate. Eine Anzeige, die Underflows zeigt, meldet hier dauerhaft
> "alles gruen", waehrend die Haelfte der Zeit mit 492 statt 660 gespielt
> wird.

**Folge fuer T-002/UI_SPEC:** Die neun wartenden Parameter beruhen auf der
falschen Leitgroesse. Der aussagekraeftige Live-Wert ist **ABR-Stufe,
Wechselrate und Verweildauer**, nicht Underflow. Das ist der naechste
UI-Auftrag.

**Kein falscher Freispruch:** Der ~3-s-Takt aus T-005 wurde **nicht
reproduziert, aber auch nicht widerlegt** — ABR steuert 990 nie an, und
Pinnen waere Schreibzugriff gewesen. Autokorrelation bei 2,76 s / 3,22 s:
r = -0,022 / +0,008 gegen ein Signifikanzband von +-0,159.

## Was als Ursache ausgeschlossen ist (belegt, nicht vermutet)

- **WLAN.** Radio war waehrend der Messung **aus**. Die Schaukel laeuft
  trotzdem. Die Wi-Fi-7/MLO-Hypothese aus T-005 kann sie nicht erklaeren.
- **Energiesparmechanismen.** Kein Doze (`mState=ACTIVE`), kein
  Akkusparmodus, Player und GMS beide Standby-Bucket 5 (EXEMPTED), Player
  in der Doze-Whitelist. R-003 deckt sich damit: Doze/Buckets regulieren
  Jobs, Alarme und Netzwerk — nicht die laufende Audio-Pipeline.
- **CPU-Knappheit.** BT 14,9 %, Player 7,3 %, audioserver 4,2 %.

## Hauptverdaechtiger und zweiter Fund

1. **Drei permanente, gleichzeitige ACTIVE-BLE-Scans von Google Play
   Services** — `nearby_fast_pair` (BALANCED), `nearby_sharing` und
   `nearby_connections` (AMBIENT_DISCOVERY), alle `(Forced)`,
   `MATCH_MODE AGGRESSIVE`, seit ueber 27 min ununterbrochen. **ACTIVE
   heisst: das Funkteil sendet** SCAN_REQ auf denselben 2,4-GHz-Kanaelen
   wie der A2DP-Link. Existenz belegt, kausaler Beitrag plausibel —
   trennbar nur per A/B.
2. **Der Pfad rechnet 48 kHz auf 96 kHz hoch.** Quelle liefert 48 kHz;
   der aktive Mixer-Thread ist `type 7 (SPATIALIZER)` mit 96 kHz und
   5.1-Maske; LDAC wird bei 96 kHz ausgehandelt. Der Spatializer-**Thread**
   sitzt im Pfad, der **Effekt** rechnet nicht (`Enabled: false`,
   Head-Tracking tatsaechlich `DISABLED`). Laut R-002 ist die
   Bitratenleiter bei 48 und 96 kHz identisch — die 96 kHz kosten also
   keine Bandbreite, aber sie verteilen dieselben Bits auf doppelt so
   viele Abtastwerte ohne Informationsgewinn.

## Offener Widerspruch

R-001 haelt **492 kbps fuer keine LDAC-Nominalstufe** (Leiter 990/660/330
bzw. 909/606/303). Am Geraet ist 492 als Index 3 **gemessen**. Die Messung
gewinnt; die Leiter fuer 96 kHz / 32 bit ist damit noch unverstanden.
Nachzufassen bei R-001.

## Methodischer Vorbehalt an unserem eigenen Messaufbau

R-004: **USB-3-Kabel strahlen breitbandig ins 2,4-GHz-Band.** Das Telefon
haengt waehrend jeder Messung am Kabel. Kontrollmessung ueber
drahtloses adb steht aus.

## Sicherheit — am Geraet verschaerft (`security/findings.md`)

**SR-001 und SR-009 bestaetigt und schlimmer als angenommen:** Die Reste
in `/data/local/tmp` stehen auf **0666** (welt-les- UND -schreibbar),
`btperf` auf 0777, und sie **ueberleben die Deinstallation**. Aus einem
Vertraulichkeits- wird zusaetzlich ein Integritaetsbefund. SR-009 von
niedrig auf **hoch** hochgestuft.

## Laufende Auftraege

| ID | Rolle | Thema | Status |
|---|---|---|---|
| T-001 | performance-tuner | Messreihe Pixel 11 | Block 1 fertig; T-007 hat moegliche Budget-Verschiebung gemeldet (Overdue-Zaehler +2,5 %/+5,1 %, ~5 sd) — Vergleichslauf offen |
| T-002 | ui-ux-designer | UI_SPEC Verlustanzeige | **Grundannahme ueberholt**, Neuauftrag noetig (ABR-Stufe statt Underflow) |
| T-005 | architect | Scan-Entwurf | geliefert; wartet auf Nutzerentscheidungen |
| T-006 | architect→developer | SR-001/SR-009 | offen, **hoch**, jetzt inkl. Aufraeumpfad |
| T-007 | researcher + performance-tuner | Deep-Dive | **geliefert** (R-001..R-004, `docs/perf/T-007-aufnahme.md`) |

## Rahmen dieser Session

Zweitrechner: **kein JDK, kein Android-SDK, kein Gradle** — Unit-Tests
sind hier nicht lauffaehig. adb nur ueber ein Fremdprodukt vorhanden
(`C:\RSL\2.1HF5\adb\adb.exe`, v31.0.2). Geraet per Kabel erreichbar.

## Offen / zurueckgestellt

- A/B-Test der GMS-BLE-Scans (braucht Schreibzugriff → Nutzerfreigabe).
- 990 kbps gezielt pinnen, um den T-005-Takt zu reproduzieren (dito).
- Spatializer testweise aus, um die 96-kHz-Frage zu klaeren (dito).
- Kontrollmessung ohne USB-Kabel (drahtloses adb).
- Aufnahme gegen R-001..R-004 abgleichen — der tuner hat sie nicht
  gelesen, sie entstanden waehrend seiner Messung.
- `AudioEffectSessionReceiver` exportiert — eigenes Review ausstehend.
- `NUL`-Datei im Repo-Root entfernen.
- Emulator-Umgebung: zurueckgestellt (Nutzer, 31.08.).
