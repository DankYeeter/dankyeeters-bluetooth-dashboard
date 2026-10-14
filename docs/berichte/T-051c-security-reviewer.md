# T-051c — Security-Pruefung Saeule 3 am Quellstand (security-reviewer, 28.09., Stand `c2a29f5`)

Vom Director aus der Antwort des security-reviewers abgelegt (Rolle ohne Write).

**Urteil: PASS.** AK-6 erfuellt: Helfer nicht erweitert (PrivilegedServer,
HelperBluetooth, AIDL, Manifest ohne Diff; `ALLOWED` unveraendert; `selectCodec`
im Client entfallen). M1–M18: 17 wirksam, M14 teilweise → SR-031.

## SR-031 [P4 | Niedrig] Banner zu offenen Einstellungen fehlt in FULL_SETUP

`BtDashboardApp.kt:160-163`: In FULL_SETUP erscheint `SetupWizardScreen` ohne
`SettingsRestoreBanner` (nur ACTIVATION_ONLY `:175` und Tabs). Pfad: App hat
globale Entwickleroptionen/Absolute Volume geaendert, danach fehlt eine
Pflichtberechtigung → `SetupSteps.phase` = FULL_SETUP (`SetupSteps.kt:180-181`).
Behebung: Banner auch ueber dem Einrichtungsassistenten zeigen.

## Auflagen M1–M18 (Belege)

M1 `GlobalSettingsController.kt:51-59`, `SettingsLedger.kt:90-95` · M2
`SettingsLedger.kt:127-134` · M3 `:135-151` · M4/M11
`DeviceProfileApplier.kt:319-320`, `SettingsRestore.kt:75-82`, `LdacTuning.kt:264`
· M5 XML-Ausschluesse + Tests · M6 `SystemGraph.kt:295` · M7 SettingsLedgerTest:124,
HdAudioTest:204 · M8 `SettingsRestore.kt:110` · M9 `:117` · M10 `:63-67` · M12
`LdacTuning.kt:113-116`, `SettingsRestore.kt:149` · M13/M18 `:251`,
`ComparisonSection.kt:132/292`, `LdacTuning.kt:285` · M14 teilweise · M15
`DeviceProfilesViewModel.kt` unter Sperre · M16 `:226` · M17
`EnvironmentConditions.kt:57-83`.

## Beobachtungen (ohne Prioritaet, Hypothesen)

1. `DeviceProfileStore.profiles` macht aus Lesefehler leere Liste (`:40-41`) —
   Aussetzen „gelingt“ ohne Wirkung; nur Wettlauf bei voruebergehendem Fehler.
2. `countOtherAclLinks` zaehlt `[ACL BR/EDR:Y` per `contains` auch in Zeilen mit
   Geraetenamen — praeparierter Name koennte Zaehlung verfaelschen (Messintegritaet).
3. SR-030-Rest: Devices-Tab `Skipped codec: <reason>` ohne `redactAddresses`
   (`HelperBluetooth.kt:564`).

## Nutzerentscheide — Fundstellen

F-014: alle drei `isPlaying`-Zweige in `SamplingPolicy.kt:59-64` (inkl. BURST)
an Monitor binden; verkuerzt zugleich die Offenlage von SR-001 (`ExecSpill.kt:72-81`,
Spill > 64 KB nach `/data/local/tmp`). Sample-Rate: `ObservationRun.kt:342-350`
(`endAgainst`), `RunLink.sampleRateHz` (`:327`, `:331`) wird mitgefuehrt, nicht
verglichen. F-011: `DeviceProfilesScreen.kt:318`, `BluetoothSystemControls.kt:105-106`.

**Nicht geprueft:** Suite, DataStore-Dateiname und `adb backup` am Geraet,
Serverseite SR-030.
