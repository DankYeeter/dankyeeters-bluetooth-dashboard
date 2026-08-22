# Android-17-Vorbereitung — Bestandsaufnahme (22. August 2026)

Read-only-Audit. Zentrale Erkenntnis vorweg: **der Build steht auf SDK 35, das
Zieltelefon läuft aber schon Android 16/SDK 36** — der Sprung nach 17 ist real
ein Doppelsprung 35→36→37, und AGP 8.7.3 kann 36 nicht bauen.

## Jetzt vorbereitbar (ohne 17-SDK)
1. **Toolchain + compileSdk/targetSdk 35→36** in allen fünf Modulen
   (`app/build.gradle.kts:10,15` + vier `core-*`). AGP 8.7.3 → 8.9+; Pflicht-
   Zwischenschritt, schließt den Geräte-Rückstand. Aufwand mittel.
2. **`android:enableOnBackInvokedCallback="true"`** in `app`-`<application>` —
   Predictive Back ist ab targetSdk 36 default. Trivial.
3. **FGS-Fehlerpfad härten** (`EqForegroundService.kt` onFailure): Typ-Refusal
   (`ForegroundServiceStartNotAllowedException`/`MissingForegroundServiceType`)
   von generischem Fehler unterscheidbar loggen → 17-Test zeigt sofort, ob der
   Typ brach. Klein.
4. **Reflection-Audit-Test** gegen compileSdk 36: jeden @hide-Methodennamen aus
   §2 per getDeclaredMethod auf Existenz prüfen → Form-Brüche zur Build-Zeit.
5. **IME/safeDrawing-Insets** auf Eingabe-Screens (Hörtest) durchsehen.
6. `resolveContentProvider`-Deprecation (`PrivilegedServer.kt:325`) bereinigen.

## FGS connectedDevice — das Hauptrisiko
`core-system/AndroidManifest.xml:41-46`, `EqForegroundService.kt:164`.
Semantisch hält der Service einen Effekt am **Output-Mix** (Session 0) = eher
mediaPlayback als connectedDevice; connectedDevice passt heute nur, weil ein
BT-Kopfhörer der Anlass ist. Die Vorbedingung ist derzeit über gehaltenes
`BLUETOOTH_CONNECT` erfüllt — **auch ohne verbundenes Gerät**. Verlangt 17 ein
tatsächlich verbundenes Gerät zur startForeground-Zeit, scheitert der Start bei
Boot-Restore und im Lautsprecherbetrieb. Hedge (`specialUse` mit PROPERTY-
Deklaration, oder `connectedDevice|mediaPlayback`) erst am 17-Beta einbauen,
nicht blind.

## Reflection: die überraschende Richtung
- **Der Helfer (uid 2000 via app_process) ist von der Non-SDK-Blockliste
  faktisch ausgenommen** (Shizuku-Muster, trusted domain) — er bricht NICHT an
  SDK-Versionen, nur an konkreten API-Form-Änderungen des (jetzt Mainline-/
  APEX-updatebaren) Bluetooth-Stacks.
- **Die In-App-Reflection (Zygote/untrusted_app) IST blocklist-pflichtig** und
  wird durch den targetSdk-37-Bump zusätzlich exponiert — betrifft
  `A2dpCodecStatusSource` (isA2dpPlaying/getActiveDevice/getCodecsSelectable),
  alles heute in runCatching gefangen → Degradation, kein Crash.
- Größter Helfer-Bruchkandidat: der private `BluetoothAdapter(IBluetoothManager,
  Context, AttributionSource)`-Ctor (`HelperBluetooth.kt:483`) — sein Nachbar
  `createAdapter(AttributionSource)` verschwand schon auf 16. Fällt er, ist die
  Shell-Attribution weg und setCodecConfigPreference muss neu bewertet werden.
  Fallback-Kette (Route 2/3) ist vorhanden.

## Erst mit 17-SDK/-Beta testbar
- Ob connectedDevice als FGS-Typ bestehen bleibt bzw. ein real verbundenes
  Gerät verlangt wird → entscheidet über den Hedge.
- Formstabilität von `getContentProviderExternal` (`PrivilegedServer.kt:407`)
  und dem BluetoothAdapter-Ctor.
- Ob die Shell-Identität BQR/setCodecConfigPreference auf dem 17-Mainline-Stack
  noch erreicht.
- Ob die greylist-Reflection durch targetSdk 37 hart blockiert wird.
