# T-068 — developer: F-026 Codec "Unknown" und Ausnahmename nach Bluetooth aus/an

STATUS: teilweise · Branch `t068`, Commits `f0abfb5`, `ca1e293` auf Basis `367afda`.

## Behoben (F-026-Pfad)

- **Ursache "Unknown bis Neustart" (aus dem Code belegt, am Geraet unbestaetigt):**
  Die Karte las den Codec nur bei Aenderung der Geraeteliste
  (`connectedDevicesFlow` ist `distinctUntilChanged`). Nach dem Wiederverbinden
  aendert `A2DP_CODEC_CONFIG_CHANGED` die Liste nicht, also las niemand nach;
  dazu hielt `CachedDumpsysLinkSource` (5 s) einen Dump von vor dem Broadcast.
  Fix: `devicesToRead` in `BluetoothDashboardViewModel.kt` liest nach jedem
  Bluetooth-Broadcast neu und verwirft vorher den Dump.
- **Ausnahmename:** kam aus `A2dpCodecStatusSource.kt` (nicht aus den im Auftrag
  genannten Privileged-Dateien — die speisen die Karte nicht).
  `Method.invoke` verpackt die SecurityException in `InvocationTargetException`
  (ohne Meldung), der Zweig `is SecurityException` griff nie. Fix:
  `codecReadFailure` packt aus; Grund ist ein fester Satz.
- Die QA-Hypothese "veralteter Proxy" passt nicht zum Text: ein verlorener
  Proxy liefert "A2DP profile not connected", keinen Ausnahmenamen.

## Nicht behoben: Klasse "Ausnahmetext in UI"

Suchmuster `javaClass.simpleName|\.message \?:` in `app/src/main`,
`core-*/src/main` (`*.kt`): 51 Treffer, davon 18 auf `Log.`-Zeilen, 33 andere.
Zweite Maske `::class.(java.)simpleName|localizedMessage|${it|e|t|error|cause|throwable}.message`
ohne `Log.`: 12 Treffer, davon 5 neu (alle `System.err` im Helfer, Log-artig).
Von den 33 ist nur `A2dpCodecStatusSource.kt:172` bis in die UI verfolgt (behoben).
Mehr als eine Handvoll: nach Rahmen angehalten. Kandidaten, die dem Namen nach
in UI-Text muenden (nicht verfolgt, also "mindestens"-Liste offen):
`BackupCodec.kt:48`, `BackupRepository.kt:73/95`, `DeviceProfilesViewModel.kt:404/455`,
`EnvironmentConditions.kt:63/92`, `LdacTuning.kt:385`, `PrivilegedFailure.kt:228`,
`PrivilegedServer.kt:709/714/768` (via `encodeError` an den Client),
`HelperBluetooth.kt:601/656/671/680/760`, `ExecSpill.kt:145`,
`AdbPairingClient.kt:119`, `AdbTlsClient.kt:78/139`, `HelperAutoStart.kt:321`,
`EqCandidateScanner.kt:140`, `AndroidEqCandidateSources.kt:79`,
`QualityReportSource.kt:147`, `LiveLinkSource.kt:291`, Parser-`warnings`
(`DumpsysBluetoothParser.kt:60`, `AudioFlingerEffectParser.kt:47`,
`A2dpLinkDumpParser.kt:81`, `AudioFlingerTrackParser.kt:110`),
`EqForegroundService.kt:210`, `PrivilegedClient.kt:409`.

## Probe fuer QA am Geraet

F-026-Ablauf wie in T-066a (App offen, `cmd bluetooth_manager disable`/`enable`,
Kopfhoerer aus/an). Erwartet: Karte zeigt nach dem Verbinden wieder LDAC ohne
App-Neustart; falls "Unknown", steht darunter "privileged permission required"
oder "the codec read failed, so it cannot be checked", nie ein Klassenname.
Dazu `logcat -s A2dpCodecStatus BtConnectionTicks` mitschneiden.
