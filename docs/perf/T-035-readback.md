# T-035 — Haelt uid 2000 `BLUETOOTH_PRIVILEGED`, und ist der BQR-Callback erreichbar?

Read-back, keine Messung, kein Bau. Rolle `performance-tuner`. **Datum:** 2026-09-03.
Anlass: R-011 (`docs/research/R-011.md`), Entscheidung des App Designers 03.09.

## Geraet und Werkzeug

- Pixel 11 Pro `67011FDKX004XG`, per USB-Kabel, adb-Erreichbarkeit genuegte —
  **kein A2DP, keine Kopfhoerer, keine Musik** noetig, keine benutzt.
- **adb-Binary (R-2 beachtet):** ausschliesslich
  `C:\Users\Daniel\tools\android-sdk\platform-tools\adb.exe`, Version
  37.0.1-15733141. `C:\RSL\2.1HF5\adb\adb.exe` wurde nicht aufgerufen, stand
  zu keinem Zeitpunkt im PATH dieser Session.
- **`dumpsys bluetooth_manager` wurde zu keinem Zeitpunkt aufgerufen** — die
  BQR-Ereignis-Queue wurde durch diesen Auftrag nicht beruehrt.
- Fuer Teil B wurde ein eigenes, kleines Diagnosewerkzeug gebaut
  (`T035Probe.java`, javac gegen `platforms/android-36/android.jar`, mit
  `d8` (`build-tools/35.0.0/d8.bat`) zu einer `classes.dex` uebersetzt), nach
  `/data/local/tmp/t035probe.dex` gepusht, per
  `CLASSPATH=... app_process /system/bin T035Probe` unter der bestehenden
  `adb shell`-Session (uid 2000) ausgefuehrt und danach vom Geraet geloescht.
  **Das ist Messwerkzeug, kein Anwendungscode:** lag ausserhalb des Repos im
  Scratchpad, wird nicht ausgeliefert, ist nach dem Lauf vom Geraet entfernt.
  Es registriert nichts, ruft die BQR-API nicht auf — nur reflektives
  Nachschlagen und ein Permission-Check, beides lesend.

## Teil A — Berechtigung, drei unabhaengige Verfahren

### Verfahren 1 — `dumpsys package com.android.shell` (persistierter Grant-Zustand)

Rohausgabe (Auszuege, drei Fundstellen im selben Dump):

```
      android.permission.BLUETOOTH_PRIVILEGED          [Zeile 448, "requested permissions:"]
```
```
      android.permission.BLUETOOTH_PRIVILEGED: granted=true   [Zeile 1046, "install permissions:" paketweit]
```
```
      android.permission.BLUETOOTH_PRIVILEGED: granted=true   [Zeile 1645, "install permissions:" unter "User 0:"]
```

**Lesart:** `com.android.shell` (die App, die uid 2000 besitzt,
`android:sharedUserId="android.uid.shell"`) deklariert die Berechtigung und
sie ist sowohl paketweit als auch fuer User 0 als `granted=true` eingetragen.
Kein `flags=[...]`-Zusatz wie bei anderen Eintraegen (z. B.
`GRANTED_BY_ROLE`) — der Eintrag ist ein einfacher Signature-Grant.

Ergaenzend, weil im Auftrag von R-011 als zweite Voraussetzung genannt
(`allOf = {BLUETOOTH_CONNECT, BLUETOOTH_PRIVILEGED}`):

```
        android.permission.BLUETOOTH_CONNECT: granted=true, flags=[ SYSTEM_FIXED|GRANTED_BY_DEFAULT|RESTRICTION_SYSTEM_EXEMPT|RESTRICTION_UPGRADE_EXEMPT]
```

**Lesart:** auch `BLUETOOTH_CONNECT` ist granted, `SYSTEM_FIXED` (nicht
entziehbar). Beide fuer den Callback verlangten Berechtigungen sind bei
`com.android.shell` vorhanden.

### Verfahren 2 — `pm list permissions -f` (Definition/Schutzstufe, unabhaengig vom Grant-Zustand)

Rohausgabe:

```
  description:null
  protectionLevel:signature
+ permission:android.permission.BLUETOOTH_PRIVILEGED
  package:android
  label:null
```

**Lesart:** Dieses Verfahren beantwortet eine andere Frage als Verfahren 1 —
nicht "ist es gewaehrt", sondern "was fuer eine Berechtigung ist das
ueberhaupt". `protectionLevel:signature` bestaetigt, dass es sich um eine
Signature-Berechtigung handelt (R-011 nannte `signature|privileged` aus
Sekundaerwissen, unbelegt — hier am Geraet direkt als `signature` bestaetigt;
das `|privileged`-Zusatzflag zeigt `pm list permissions -f` an dieser Stelle
nicht separat an, widerspricht dem Kernbefund aber nicht). Kein Widerspruch
zu Verfahren 1.

### Verfahren 3 — direkte Laufzeitpruefung (Binder-Aufruf gegen den Permission-Dienst, aus einem `app_process`-Kontext unter uid 2000)

Rohausgabe (`T035Probe`, ausgefuehrt als `myUid=2000`):

```
T035Probe: Context.checkPermission(android.permission.BLUETOOTH_PRIVILEGED, pid=9831, uid=2000) = 0 (GRANTED)
T035Probe: Context.checkCallingOrSelfPermission(android.permission.BLUETOOTH_PRIVILEGED) = 0 (GRANTED)
T035Probe: PackageManager.checkPermission(android.permission.BLUETOOTH_PRIVILEGED, com.android.shell) = 0 (GRANTED)
```

**Lesart:** Das ist die schaerfste der drei Pruefungen — kein Text-Dump,
sondern derselbe Binder-Pfad (`PermissionManagerService`), den auch
`registerBluetoothQualityReportReadyCallback` intern durchlaeuft, aufgerufen
mit der tatsaechlichen `pid`/`uid` des `app_process`, der unter `adb shell`
(uid 2000) gestartet wurde. Alle drei Zugriffsmuster (pid+uid, calling-uid
implizit, Paketname) liefern `PERMISSION_GRANTED` (0).

**Hinweis zur Herleitung des Context:** `ActivityThread.systemMain()` liefert
einen Context, der (wie in `PrivilegedServer.kt` dokumentiert) am Paket
`android` haengt, nicht an `com.android.shell` — der `checkPermission`-Pfad
prueft aber gegen `pid`/`uid`, nicht gegen das Paket des Context, deshalb
bleibt der Befund fuer uid 2000 gueltig. `Looper.prepareMainLooper()` war
noetig, sonst wirft `systemMain()` `Can't create handler inside thread ...
that has not called Looper.prepare()` — exakt dieselbe Bedingung, die
`PrivilegedServer.main()` bereits kennt und respektiert.

### Ergebnis Teil A

**Alle drei Verfahren stimmen ueberein, kein Widerspruch.** uid 2000
(`com.android.shell`) haelt `android.permission.BLUETOOTH_PRIVILEGED` — sowohl
deklariert und persistiert gewaehrt (Verfahren 1) als auch zur Laufzeit
tatsaechlich durchsetzbar (Verfahren 3), bei einer bestaetigten
Signature-Schutzstufe (Verfahren 2). `BLUETOOTH_CONNECT`, die zweite von
R-011 genannte Voraussetzung, ist ebenfalls gewaehrt.

## Teil B — Erreichbarkeit der API

Reflektives Nachschlagen aus demselben `app_process`-Lauf (uid 2000), **nichts
aufgerufen, nichts registriert**:

```
T035Probe: FOUND method: public int android.bluetooth.BluetoothAdapter.registerBluetoothQualityReportReadyCallback(java.util.concurrent.Executor,android.bluetooth.BluetoothAdapter$BluetoothQualityReportReadyCallback)
T035Probe: modifiers=public
T035Probe: paramTypes=[interface java.util.concurrent.Executor, interface android.bluetooth.BluetoothAdapter$BluetoothQualityReportReadyCallback]
T035Probe: returnType=int
T035Probe: exceptionTypes=[]
T035Probe: getMethod() also found it: public int android.bluetooth.BluetoothAdapter.registerBluetoothQualityReportReadyCallback(java.util.concurrent.Executor,android.bluetooth.BluetoothAdapter$BluetoothQualityReportReadyCallback)
```

**Befund:**

- Die Methode **existiert auf diesem Geraet** in `BluetoothAdapter`, exakt
  wie in R-011 aus dem AOSP-Quelltext zitiert.
- Signatur: `registerBluetoothQualityReportReadyCallback(Executor, BluetoothAdapter.BluetoothQualityReportReadyCallback)`,
  Rueckgabewert `int`, keine deklarierten Checked Exceptions.
- **Kein Hidden-API-Sichtbarkeitsfehler:** Die Methode wurde sowohl ueber
  `getDeclaredMethods()` als auch ueber das strengere, nur-oeffentliche
  `getMethod(...)` sauber gefunden — kein `NoSuchMethodError`, keine
  Blocklist-Ablehnung. Sie ist als `public` markiert, kein Hinweis auf eine
  Greylist/Blocklist-Sperre in diesem Aufrufkontext.
- **Einschraenkung dieses Befunds, ausdruecklich:** Der Aufrufkontext war
  `app_process` unter der `shell`-Domain (uid 2000) — Prozesse dieser Art
  sind von der Hidden-API-Durchsetzungspolitik in aller Regel ausgenommen.
  Ob eine **normal installierte App** (targetSdk-abhaengige Durchsetzung)
  dieselbe Methode ebenso sauber reflektiv erreichen wuerde, ist mit diesem
  Verfahren **nicht** geprueft — nur, dass die Methode auf diesem Geraet
  in diesem Framework-Build ueberhaupt existiert und `public` ist. Da es sich
  laut R-011 um eine `@SystemApi`-Methode handelt (nicht Teil des oeffentlichen
  SDK-Stub-`android.jar`), waere fuer eine normale App ohnehin zuerst die in
  Teil A gepruefte Berechtigung die schaerfere Schranke, nicht die
  Hidden-API-Politik — das ordnet den Befund ein, ersetzt aber keine eigene
  Pruefung aus App-Kontext.

## Teil C — dieselbe Queue oder eigener Zustellweg?

**Bleibt offen — nicht ohne Bau zu entscheiden, und wird hier nicht geraten.**

R-011 (Befund 4, unten zitiert) stuetzt sich auf eine **einfach, nicht
kreuzverifizierte** Quelltextabfrage und stuft sich selbst dort schwaecher ein
als den Kernbefund (Read-Clear, dreifach bestaetigt):

> "Der Auslieferungspfad fuer neue Ereignisse an diesen Callback
> (`bqr_delivery_event(...)`, aufgerufen aus `AddLinkQualityEventToQueue()`)
> ist im Quelltext eine **von der `kpBqrEventQueue`-Befuellung separate**
> Weiterleitung [...] Waere die Berechtigung erreichbar, wuerde ein
> Abonnement dieses Callbacks die Dump-Warteschlange **nicht** zusaetzlich
> belasten oder anderen Lesern etwas wegnehmen [...] Evidenzniveau: [Q],
> einfach abgefragt, nicht kreuzverifiziert — schwaecher belegt als Befund 1."

Dieser Auftrag hat dazu **nichts Neues** beigetragen und konnte es mit den
erlaubten Mitteln (Reflection, kein Registrieren, kein Aufruf, kein
`dumpsys bluetooth_manager`) auch nicht: Ob `bqr_delivery_event()` bei einem
echten, eintretenden BQR-Ereignis auf diesem konkreten Geraet und dieser
konkreten Bluetooth-Modulversion tatsaechlich unabhaengig von
`kpBqrEventQueue`/`DebugDump()` zustellt, ist eine Aussage ueber
**Laufzeitverhalten**, keine, die sich aus einer statischen Methodensignatur
oder einem Permission-Grant ableiten laesst.

**Der Versuch, der es entscheiden wuerde** (nicht durchgefuehrt, da er ein
Registrieren voraussetzt und damit ausserhalb des Scopes dieses Auftrags
liegt): den Callback unter kontrollierten Bedingungen tatsaechlich
registrieren (z. B. testweise ueber denselben `app_process`-Weg wie hier, mit
`Looper.loop()` statt nur `prepareMainLooper()`, um auf den Callback warten zu
koennen), ein echtes BQR-Ereignis ausloesen oder abwarten, und **parallel** an
zwei Stellen beobachten:

1. Feuert der Callback mit dem Ereignis?
2. Zeigt ein anschliessendes `dumpsys bluetooth_manager` (einmalig, am Ende
   des Versuchs, mit dem Wissen, dass es die Queue leert) dasselbe Ereignis
   noch in der BQR-Sektion, oder ist es dort bereits nicht mehr vorhanden,
   obwohl der Callback es soeben zugestellt hat?

Bleibt (2) nach dem Callback-Empfang weiterhin sichtbar (nicht schon vorher
durch den Callback-Empfang selbst geleert), ist das der Beleg fuer einen
eigenen Zustellweg. Verschwindet es synchron mit dem Callback-Empfang, waere
das ein Hinweis auf eine gemeinsame Quelle trotz der im Quelltext getrennt
aussehenden Funktionen (z. B. weil beide Pfade am Ende auf denselben,
im Quelltext nicht eingesehenen Zwischenzustand zugreifen). Das ist ein
Geraeteversuch mit Registrierung, kein Recherche- oder Reflection-Schritt,
und gehoert damit — wenn ueberhaupt gewuenscht — zusammen mit dem eigentlichen
Bau an `developer`/`security-reviewer`, nicht in einen Read-back.

## Kernfrage — Ein-Satz-Antwort

**Ja: uid 2000 (`com.android.shell`) haelt `BLUETOOTH_PRIVILEGED` (und
`BLUETOOTH_CONNECT`) auf diesem Geraet nachweislich sowohl deklariert/gewaehrt
als auch zur Laufzeit durchsetzbar, und
`BluetoothAdapter.registerBluetoothQualityReportReadyCallback` existiert dort
als sauber auffindbare, oeffentliche Methode ohne erkennbare
Hidden-API-Sperre — die Berechtigungs- und Sichtbarkeitsschranke aus R-011
ist fuer den bestehenden privilegierten Helfer (der bereits als uid 2000
laeuft) damit kein Hindernis; offen bleibt ausschliesslich Teil C
(gemeinsame oder getrennte Queue), was nur ein tatsaechlicher
Registrierungsversuch klaeren kann.**

## Offene Punkte

- **Teil C ungeklaert** (s. o.) — entscheidet, ob AK-7 als nicht-destruktiver
  Kanal ueberhaupt etwas an dem in R-011 benannten Zielkonflikt mit AK-1
  aendert, oder ob der Callback selbst still die gleiche Quelle anzapft.
- Die Hidden-API-Pruefung in Teil B lief unter der `shell`-Domain, nicht unter
  `untrusted_app` — fuer eine Aussage ueber eine normal installierte App waere
  das kein zusaetzliches Hindernis (die Berechtigungsschranke greift zuerst),
  aber es ist nicht dasselbe wie ein Test aus App-Kontext.
- `pm list permissions -f` zeigte fuer `BLUETOOTH_PRIVILEGED` nur
  `protectionLevel:signature`, nicht das vollere `signature|privileged` aus
  R-011s Sekundaerwissen — kein Widerspruch (Verfahren 1 und 3 bestaetigen den
  Grant unabhaengig davon), aber nicht als vollstaendig identisch mit R-011s
  Formulierung zu verwechseln.
- Wie in R-011 offen gelassen: ob `android17-release` exakt der auf diesem
  Geraet installierten Bluetooth-Mainline-Modulversion entspricht, wurde auch
  hier nicht zusaetzlich verifiziert — dieser Read-back hat direkt am Geraet
  gemessen, nicht am AOSP-Quelltext, das Problem betrifft also nur die
  Uebereinstimmung mit R-011s Erklaerungsmodell, nicht die hier erhobenen
  Rohbefunde selbst.
- Naechster Schritt liegt beim `director`: Entscheidung, ob Teil C per
  begrenztem Registrierungsversuch (mit `security-reviewer`-Beteiligung wegen
  AK-10) geklaert werden soll, bevor AK-7 festgeschrieben wird.
