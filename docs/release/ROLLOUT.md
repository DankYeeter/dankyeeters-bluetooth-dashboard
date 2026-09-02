# ROLLOUT

Rollout- und Releasefaehigkeit dieses Projekts, gefuehrt vom `release-manager`.
Ein `## {Version}`-Abschnitt je Lauf, mit Datum. **Bestehende Abschnitte werden
nie ueberschrieben** — Nachtraege kommen als neuer Abschnitt.

---

## 0.3.0 — 2026-09-02 — Toolchain-Rollout Zweitrechner (Modus `plan`, T-010)

Zielsystem dieses Laufs ist **nicht** der Endnutzer-Zielrechner, sondern der
**Entwicklungs-Zweitrechner**: Windows 10 Home 10.0.19045, x86_64, 32 GiB RAM
(34.204.610.560 Byte), 8 Kerne / 16 Threads, 270 GiB frei auf `C:`.
Kein Hypervisor, also kein Emulator.

**Dieser Abschnitt ist ein Plan. Es wurde nichts installiert und nichts
heruntergeladen.** Ausgefuehrt wurden ausschliesslich lesende Pruefungen,
Versionsabfragen und HTTP-HEAD-Anfragen zur Groessenmessung.

---

### 1. Bestandsaufnahme — belegt

Jede Zeile mit dem Kommando, das sie zeigt. Ausgefuehrt in Git Bash.

| Sache | Befund | Belegkommando |
|---|---|---|
| `java` im PATH | **nein** | `where java` → "Could not find files" |
| `JAVA_HOME` | **leer** | `echo "$JAVA_HOME"` → leer |
| `javac`, `gradle`, `adb`, `cmake`, `ninja` im PATH | **alle nein** | `where javac` / `where gradle` / `where adb` / `where cmake` / `where ninja` |
| `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_NDK_HOME` | **alle leer** | `echo "[$ANDROID_HOME] [$ANDROID_SDK_ROOT] [$ANDROID_NDK_HOME]"` |
| JDK an Standardorten | **keiner** (`Program Files\Java`, `Eclipse Adoptium`, `Amazon Corretto`, `Zulu`, `~\.jdks` fehlen alle) | Schleife ueber die acht Standardpfade mit `[ -d "$d" ]` |
| **JDK versteckt vorhanden** | **JA: `C:\Users\Daniel\tools\jdk\jdk-17.0.20.1+1`** — Temurin 17.0.20.1+1, x86_64, 304 MB | `"$HOME/tools/jdk/jdk-17.0.20.1+1/bin/java" -version` → `OpenJDK Runtime Environment Temurin-17.0.20.1+1`; `grep IMPLEMENTOR .../release` |
| **Android-SDK versteckt vorhanden** | **JA: `C:\Users\Daniel\tools\android-sdk`**, 2,4 GB | `du -sh ~/tools/android-sdk` |
| `~\AppData\Local\Android\Sdk` | **existiert, ist aber leer** — nur `.sdk\` Metadatenhuelle, 415 KB, kein einziges Paket | `find ~/AppData/Local/Android/Sdk -maxdepth 3` |
| choco / scoop | **nicht installiert** | `where choco`, `where scoop`, `ls /c/ProgramData/chocolatey` |
| winget | vorhanden (`...\WindowsApps\winget.exe`) | `where winget` |
| MSYS2 | `C:\Users\Daniel\tools\msys64` vorhanden (fuer diesen Bau ohne Bedeutung) | `ls ~/tools` |
| Fremd-adb | `C:\RSL\2.1HF5\adb\adb.exe`, Binary von 2021 | `ls -la /c/RSL/2.1HF5/adb/` |
| Arbeitsverzeichnis | sauber bis auf **untracked `docs/tasks/T-010.md`** | `git status --porcelain` |
| HEAD | `4de36b6` | `git log --oneline -1` |

**Damit ist die Zeile in `docs/state.md` („Zweitrechner: kein JDK, kein SDK,
kein Gradle") in zwei von drei Teilen sachlich falsch.** Ein JDK ist da
(Version 17, nicht 21), ein halbes SDK ist da. Was fehlt, steht unter 3.
Die Zeile fuehrt `docs/state.md`, nicht ich — Korrektur beim Director.

#### Was das versteckte SDK enthaelt

Abgefragt mit
`sdkmanager.bat --sdk_root="C:/Users/Daniel/tools/android-sdk" --list_installed`
(JAVA_HOME nur fuer den Aufruf gesetzt, nicht persistiert):

| Paket | Version | Beleg |
|---|---|---|
| `cmdline-tools;latest` | 23.0 | `cat cmdline-tools/latest/source.properties` |
| `ndk;27.3.13750724` | r27d | `cat ndk/27.3.13750724/source.properties` |
| `platform-tools` | 37.0.1 | `adb.exe version` → `Version 37.0.1-15733141` |
| Lizenz `android-sdk-license` | akzeptiert, Hash `24333f8a63b6825ea9c5514f83c2829b004d1fee` | `cat licenses/android-sdk-license` |

**Nicht vorhanden:** `platforms/`, `build-tools/`, `cmake/`, `emulator/`,
`system-images/`, `extras/` — alle sechs Verzeichnisse fehlen.

#### Die Gradle-9.7.1-Auffaelligkeit — aufgeklaert

- `~/.gradle/wrapper/dists/gradle-9.7.1-bin/...` ist **vollstaendig entpackt**
  (164 MB, `.zip.ok` vorhanden), dazu `~/.gradle/caches/9.7.1`, `jars-9`,
  `modules-2` (43 Gruppen), Stand 2026-09-01 22:15 bis 2026-09-02 02:38.
- Sie gehoert **nicht zu diesem Repo**. Der Wrapper dieses Repos zeigt
  eindeutig auf 8.11.1:
  `distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip`
  (`cat gradle/wrapper/gradle-wrapper.properties`).
- Ein Scan aller `gradle-wrapper.properties` unterhalb von
  `C:\Users\Daniel\Desktop` findet **nur 8.11.1** (dieses Repo und
  `pension-manager`). Kein Projekt dort benutzt 9.7.1.
- Die Daemon-Logs weisen das fremde Projekt aus: `Project ':shared'`,
  Abhaengigkeiten `app.cash.sqldelight:runtime-jvm:2.3.2` und
  `androidx.compose.runtime:runtime-annotation-jvm:1.12.0`. Beides kommt in
  `gradle/libs.versions.toml` dieses Repos **nicht** vor (`grep sqldelight`,
  `grep 1.12.0` → leer). Ein Kotlin-Multiplatform-Projekt mit `:shared`-Modul.
  Sein Verzeichnis steht in den Logs nicht; **Herkunftsort ungeklaert**.
- **Risiko daraus: gering, aber nicht null.** Kein Versionskonflikt — Gradle
  haelt Distributionen und Caches je Version getrennt, unser Wrapper zieht
  sich 8.11.1 daneben. Was bleibt: geteiltes `GRADLE_USER_HOME`, also
  Plattenplatz und gleichzeitig laufende Daemons zweier Versionen.
  **Nicht loeschen.** Wer sie loescht, zwingt das fremde Projekt zum
  Neu-Download.
- **Wichtiger Nebenbefund aus denselben Logs:** Der Daemon lief unter
  `javaHome=C:\Users\Daniel\tools\jdk\jdk-17.0.20.1+1, javaVendor=Eclipse Adoptium`.
  So wurde das versteckte JDK ueberhaupt gefunden.

---

### 2. Soll-Toolchain aus dem Repo — und die Abweichungen zu `GOAL.md`

Aus den Build-Dateien selbst gelesen, nicht aus `GOAL.md` uebernommen:

| Sache | Repo sagt | Quelle im Repo | `GOAL.md` sagt | Deckung |
|---|---|---|---|---|
| Gradle | **8.11.1** | `gradle/wrapper/gradle-wrapper.properties` | 8.11.1 | **deckt sich** |
| AGP | **8.9.3** | `gradle/libs.versions.toml`, `agp = "8.9.3"` | 8.9.3 | **deckt sich** |
| compileSdk / targetSdk | **36 / 36** | alle fuenf `build.gradle.kts` | 36 | **deckt sich** |
| minSdk | **31** | alle Module | — | in `GOAL.md` nicht genannt |
| Kotlin | **2.0.21**, KSP `2.0.21-1.0.28` | Versionskatalog | „Kotlin/Compose" | ohne Version — kein Widerspruch |
| Java-Bytecode | **17** (`sourceCompatibility`/`targetCompatibility` VERSION_17, `jvmTarget = "17"`) | alle fuenf Module | Temurin **21** | **Abweichung, siehe unten** |
| JDK-Toolchain | **nicht gesetzt** — kein `jvmToolchain(...)`, kein `org.gradle.java.home` | `grep` ueber alle `.kts`/`.properties` | Temurin 21 | **das Repo erzwingt 21 nicht** |
| NDK-Version | **nicht gepinnt** — kein `ndkVersion`, kein `ndkPath` | `grep -rn "ndkVersion\|ndkPath"` → leer | „Oboe NDK" | **Luecke, siehe 3.** |
| CMake | **3.22.1**, hart gesetzt | `core-audio/build.gradle.kts`, `externalNativeBuild.cmake.version = "3.22.1"`; `cmake_minimum_required(VERSION 3.22.1)` | — | in `GOAL.md` nicht genannt |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` | `core-audio` `ndk.abiFilters` | — | — |
| Robolectric | **4.14.1**, Tests laufen gegen **SDK 35** | Katalog; `app/src/test/resources/robolectric.properties` `sdk=35`; ausserdem `@Config(sdk = [35])` in 20 Testklassen | „Robolectric" | ohne Version |

**Befund T-010/B-1 an den Director — JDK 17 vs. 21.**
Das Repo verlangt **JDK 17 als Mindestmass** und nichts darueber: Bytecode-Ziel
17, keine Toolchain-Deklaration. Der bereits vorhandene Temurin 17.0.20.1+1
wuerde AGP 8.9.3 und Gradle 8.11.1 formal genuegen. `GOAL.md` schreibt aber
**Temurin 21** als Rahmen fest, und der Rahmen ist bindend. Ich plane deshalb
21 ein — und melde zugleich: es gaebe einen **Null-Download-Sofortweg** ueber
das vorhandene JDK 17, falls der Director die Kette schneller aufmachen will
als 196 MiB Download dauern. Das waere dann eine bewusste Abweichung vom
Rahmen und muesste in `GOAL.md` vermerkt werden, nicht still geschehen.
Meine Empfehlung bleibt **21**: einen zweiten Rechner auf einer anderen
JDK-Hauptversion laufen zu lassen als die zugesagte, macht jeden spaeteren
„laeuft bei mir"-Streit unentscheidbar.

**Befund T-010/B-2 an den `developer` — irrefuehrender Kommentar.**
`app/src/test/resources/robolectric.properties` begruendet `sdk=35` mit
„35 is this project's compileSdk/targetSdk". Das stimmt seit der Anhebung auf
36 nicht mehr. Der **Wert 35 ist vermutlich richtig** (Robolectric 4.14.1
bringt kein Instrumentierungs-Image fuer API 36 mit — ungeprueft, siehe 7.),
die **Begruendung ist falsch**. Ein Kommentar, der eine falsche Ursache nennt,
laedt zum falschen Reparieren ein. Nur der Kommentar gehoert korrigiert, nicht
der Wert — und beides ist Sache des `developer`, nicht meine.

---

### 3. Welche SDK-Komponenten noetig sind — je Komponente mit Begruendung

| Komponente | Version | Warum noetig | Bereits da? |
|---|---|---|---|
| `platforms;android-36` | rev 2 | `compileSdk = 36` in **allen fuenf** Modulen. Ohne `android.jar` fuer API 36 kompiliert kein Modul, auch kein Unit-Test. | **nein** |
| `build-tools;36.0.0` | 36.0.0 | AAPT2, D8/R8, Ressourcen-Merge. Wird auch fuer Unit-Tests gebraucht: `app` und `core-monitor` setzen `unitTests.isIncludeAndroidResources = true`, das heisst Ressourcen und Manifest werden fuer die Robolectric-Laeufe wirklich gemerged. | **nein** |
| `cmake;3.22.1` | 3.22.1 | **Hart gefordert**, nicht geraten: `core-audio` setzt `externalNativeBuild.cmake.version = "3.22.1"`, `CMakeLists.txt` fordert `cmake_minimum_required(VERSION 3.22.1)`. Eine andere CMake-Version im SDK genuegt AGP hier nicht. | **nein** |
| `ndk` | 27.x | Belegter Bedarf: `core-audio/src/main/cpp/{jni_bridge.cpp,ToneGenerator.cpp,ToneGenerator.h}` existieren, Oboe wird als Prefab-Paket ueber CMake gelinkt (`find_package(oboe REQUIRED CONFIG)`). Kein Download „auf Verdacht". | **ja, r27d = 27.3.13750724** |
| `platform-tools` | 37.0.1 | `adb` fuer Geraetetests und den ADB-Loopback des privilegierten Helpers. | **ja** |
| `cmdline-tools;latest` | 23.0 | `sdkmanager` / `android sdk`, um obiges zu installieren. | **ja** |
| `emulator`, `system-images` | — | **ausdruecklich nicht.** Kein Hypervisor (`GOAL.md`). Waeren mehrere GB ohne jeden Nutzen. | nein, und soll so bleiben |

**Offene NDK-Frage, ehrlich benannt (T-010/B-3).**
Weil `ndkVersion` nirgends gesetzt ist, waehlt AGP 8.9.3 seine **eigene
Default-NDK-Version**. Welche das ist, konnte ich **nicht messen**: die
Konstante steckt im AGP-Artefakt, das auf diesem Rechner nicht im
Gradle-Cache liegt (`ls ~/.gradle/caches/modules-2/files-2.1 | grep com.android`
→ leer), und es herunterzuladen waere ein Download, den dieser Auftrag
verbietet. Stimmt der Default nicht mit dem vorhandenen 27.3.13750724
ueberein, bricht AGP mit einer Meldung ab, **die die geforderte Version exakt
nennt** („No version of NDK matched the requested version ... Versions
available locally: 27.3.13750724"). Zwei Wege, Entscheidung beim Director:

- **(a) empfohlen, 0 Byte Download:** Der `developer` pinnt in
  `core-audio/build.gradle.kts` `ndkVersion = "27.3.13750724"`. Damit ist die
  Frage fuer alle Rechner beantwortet, nicht nur fuer diesen — und der
  Kommentar im selben File („The flag is what NDK r27 does by default")
  bestaetigt, dass die r27-Linie die gewollte ist. **Diese Zeile schreibe ich
  nicht**, sie steht in einer Gradle-Datei eines Anwendungsmoduls.
- **(b) rund 2,5 GB Download:** die von AGP genannte NDK-Version zusaetzlich
  installieren und die vorhandene liegen lassen.

---

### 4./5. Der Plan — Schritte, Quellen, Systemwirkung, Rueckweg, Pruefpunkt

Reihenfolge ist bindend. **Jeder Schritt wird erst begonnen, wenn der
Pruefpunkt des vorigen erfuellt ist.** Alle Downloadgroessen unten sind
**gemessen** (HTTP-HEAD, `Content-Length`), nicht geschaetzt; wo geschaetzt
wird, steht es dabei.

---

#### S-1 — Temurin 21 als ZIP entpacken

- **Quelle:** `https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse`
  Diese URL leitet (gemessen, HTTP 307) auf
  `https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip`
- **Dateiname:** `OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip`
- **Groesse:** **205.073.461 Byte = 195,6 MiB** (gemessen). Entpackt ~330 MB
  (geschaetzt am vorhandenen JDK 17: 304 MB).
- **Pruefsumme:** dieselbe GitHub-URL mit angehaengtem `.sha256.txt`.
  **Vor dem Entpacken pruefen** — `Get-FileHash -Algorithm SHA256`.
- **Ziel:** `C:\Users\Daniel\tools\jdk\jdk-21.0.12.1+1\` (neben dem 17er, nicht
  darueber).
- **Warum ZIP und nicht MSI:** Der MSI-Installer schreibt nach
  `C:\Program Files`, legt Registry-Schluessel an, setzt optional systemweite
  Umgebungsvariablen und Dateizuordnungen und **verlangt Administratorrechte**.
  Das ZIP aendert **ausschliesslich** ein Verzeichnis unterhalb des
  Benutzerprofils. Kein Admin, keine Registry, kein Dienst.
- **Aenderung am System:** ein neues Verzeichnis. Sonst nichts.
- **Rueckweg:** Verzeichnis loeschen. Vollstaendig.
- **Pruefpunkt:**
  `& "$HOME\tools\jdk\jdk-21.0.12.1+1\bin\java.exe" -version`
  muss `Temurin-21.0.12.1+1` und `64-Bit Server VM` melden.

#### S-2 — `JAVA_HOME` und PATH auf Benutzerebene setzen

- **Quelle:** keine, reine Konfiguration.
- **Aenderung am System:** **Registry**, `HKEY_CURRENT_USER\Environment` —
  Werte `JAVA_HOME` (neu) und `Path` (ergaenzt um `%JAVA_HOME%\bin`).
  Nur Benutzerkontext, **keine Adminrechte**, keine Wirkung auf andere Konten.
- **Kommando (PowerShell, nicht ausgefuehrt):**
  `[Environment]::SetEnvironmentVariable("JAVA_HOME","C:\Users\Daniel\tools\jdk\jdk-21.0.12.1+1","User")`
- **Warum nicht ueber `org.gradle.java.home` in `gradle.properties`:** das ist
  eine Repo-Datei und waere ein rechnerspezifischer Pfad im Versionsstand.
  Ausserdem nicht meine Datei.
- **Achtung:** Der bestehende User-`Path` wird **ergaenzt, nicht ersetzt**.
  Vorher `[Environment]::GetEnvironmentVariable("Path","User")` sichern und den
  Text ablegen — das ist der Rueckweg.
- **Rueckweg:** `SetEnvironmentVariable("JAVA_HOME",$null,"User")` und den
  gesicherten `Path` zuruecksetzen.
- **Pruefpunkt:** **neue** Shell oeffnen (die alte hat den alten Block),
  dann `java -version` → 21.0.12.1, und `echo $env:JAVA_HOME` → der Pfad.

#### S-3 — SDK-Ort festlegen

- **Aenderung am System:** `HKCU\Environment\ANDROID_HOME` =
  `C:\Users\Daniel\tools\android-sdk`. Zusaetzlich im Repo die Datei
  `local.properties` mit `sdk.dir=C:\\Users\\Daniel\\tools\\android-sdk`.
  `local.properties` ist in `.gitignore` (`/local.properties`) — sie wandert
  also **nicht** in den Versionsstand. Belegt: `cat .gitignore`.
- **Falle, ausdruecklich:** `C:\Users\Daniel\AppData\Local\Android\Sdk`
  existiert, ist aber eine **leere Huelle** (nur `.sdk\`, 415 KB, kein Paket).
  Wird `ANDROID_HOME` versehentlich dorthin gesetzt, faengt der Bau an,
  2,4 GB ein zweites Mal zu laden. **Nicht dorthin zeigen.** Ob die Huelle
  geloescht wird, entscheidet der Nutzer; noetig ist es nicht.
- **Rueckweg:** Variable auf `$null`, `local.properties` loeschen.
- **Pruefpunkt:**
  `sdkmanager.bat --list_installed` **ohne** `--sdk_root` muss dieselben drei
  Pakete zeigen wie heute mit explizitem Root.

#### S-4 — Fehlende SDK-Pakete nachinstallieren

- **Quelle:** Google-SDK-Repository, vom `sdkmanager` selbst aufgeloest
  (`https://dl.google.com/android/repository/...`). Direkt-URLs unten nur zur
  Groessenmessung und als Offline-Rueckfallweg.
- **Kommando (nicht ausgefuehrt):**
  `sdkmanager.bat "platforms;android-36" "build-tools;36.0.0" "cmake;3.22.1"`
  Hinweis: cmdline-tools 23.0 meldet beim Start „The SDK Manager CLI tool
  (sdkmanager) is deprecated. Android CLI will be used instead." — der Aufruf
  funktioniert weiter und delegiert an `android sdk`. Wer die Warnung
  vermeiden will, ruft direkt `android sdk install ...` auf.

| Paket | Direkt-URL | Groesse (gemessen) |
|---|---|---|
| `platforms;android-36` | `https://dl.google.com/android/repository/platform-36_r02.zip` | 65.878.410 B = **62,8 MiB** |
| `build-tools;36.0.0` | `https://dl.google.com/android/repository/build-tools_r36_windows.zip` | 58.699.878 B = **56,0 MiB** |
| `cmake;3.22.1` | `https://dl.google.com/android/repository/cmake-3.22.1-windows.zip` | 16.116.742 B = **15,4 MiB** |

Summe Download **134,2 MiB**, entpackt geschaetzt **~300 MB**.

- **Lizenzakzeptanz — ausdruecklich:** Die Datei
  `~/tools/android-sdk/licenses/android-sdk-license` existiert bereits mit dem
  Hash `24333f8a63b6825ea9c5514f83c2829b004d1fee`; unter dieser Lizenz stehen
  alle drei Pakete, eine erneute Zustimmung ist daher **voraussichtlich nicht
  noetig**. Verlangt der SDK-Manager dennoch eine Lizenz, gilt:
  **`sdkmanager --licenses` fuehrt der Nutzer selbst aus und liest, wozu er ja
  sagt.** Kein `yes | sdkmanager --licenses`, kein Agent, der stellvertretend
  zustimmt. Eine Lizenzannahme ist eine Willenserklaerung des Menschen.
- **Aenderung am System:** nur Verzeichnisse unterhalb
  `C:\Users\Daniel\tools\android-sdk` sowie ggf. `licenses\`-Dateien und ein
  Repository-Cache unter `~\.android\`. Keine Registry, kein Admin.
- **Rueckweg:** `sdkmanager --uninstall "platforms;android-36" ...` oder die
  drei Verzeichnisse loeschen.
- **Pruefpunkt:** `sdkmanager --list_installed` zeigt genau sechs Eintraege:
  `cmdline-tools;latest`, `ndk;27.3.13750724`, `platform-tools`,
  `platforms;android-36`, `build-tools;36.0.0`, `cmake;3.22.1`.

#### S-5 — Gradle-Distribution 8.11.1 holen (durch den Wrapper)

- **Quelle:** `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip`
  — genau die URL aus `gradle-wrapper.properties`, mit
  `validateDistributionUrl=true`.
- **Groesse:** 136.920.070 B = **130,6 MiB** (gemessen). Entpackt geschaetzt
  ~200 MB (Vergleichswert: die vorhandene 9.7.1-Distribution belegt 164 MB).
- **Kommando:** `.\gradlew.bat --version`
- **Aenderung am System:** neues Verzeichnis unter
  `~\.gradle\wrapper\dists\gradle-8.11.1-bin\`. Die fremde 9.7.1 bleibt
  unberuehrt.
- **Rueckweg:** dieses eine Verzeichnis loeschen.
- **Pruefpunkt:** Ausgabe nennt `Gradle 8.11.1` und
  `JVM: 21.0.12.1 (Eclipse Adoptium ...)`. **Steht dort 17, hat S-2 nicht
  gegriffen** — dann nicht weitergehen.

#### S-6 — Konfigurationslauf ohne Tests

- **Kommando:** `.\gradlew.bat --console=plain projects`
- **Warum vor dem Testlauf:** trennt Toolchain-Fehler (JDK, SDK, NDK, CMake,
  Distribution) von Testfehlern. Genau hier faellt die NDK-Frage aus 3. auf —
  und die Fehlermeldung nennt die geforderte Version exakt.
- **Aenderung am System:** Gradle-Cache unter `~\.gradle\caches\8.11.1` und
  `modules-2` (Abhaengigkeiten), Konfigurationscache im Repo unter `.gradle\`
  (gitignored).
- **Pruefpunkt:** die fuenf Module `:app`, `:core-audio`, `:core-hearing`,
  `:core-system`, `:core-monitor` werden aufgelistet, kein `CXX`-Fehler, kein
  „SDK location not found".
- **Wenn hier ein NDK-Fehler kommt:** **stehenbleiben und melden**, nicht
  raten. Die genannte Versionsnummer geht an den Director; dann Weg (a) oder
  (b) aus Abschnitt 3.

#### S-7 — Abhaengigkeiten und Robolectric-Laufzeit

- **Quelle:** Maven Central und `dl.google.com/dl/android/maven2` ueber die in
  `settings.gradle.kts` erklaerten Repositorien.
- **Groesse:** die Bibliotheken selbst sind **nicht gemessen**, geschaetzt
  **1,5–2,5 GB** (Compose-BOM, AGP, Kotlin, Room/KSP, Robolectric).
  Gemessen ist dagegen der grosse Einzelbrocken, den Robolectric **zur
  Testlaufzeit** nachlaedt, ausserhalb des Gradle-Caches:
  `org.robolectric:android-all-instrumented:15-robolectric-12650502-i7`
  = 199.527.464 Byte = **190,3 MiB** (HTTP 200 gegen `repo1.maven.org`
  geprueft). Er wird gebraucht, weil **alle** Robolectric-Tests hier
  `sdk = 35` setzen.
- **Konsequenz, die leicht uebersehen wird:** Der erste Testlauf braucht
  **Netz**, auch wenn die Toolchain vollstaendig ist. Ohne Netz scheitert er
  an einem Download, nicht an einem Fehler im Code.
- **Pruefpunkt:** faellt mit S-8 zusammen.

#### S-8 — Abschlusstest (siehe Abschnitt 6)

---

### 6. Der Abschlusstest — die Abnahme dieses Tasks

```
cd C:\Users\Daniel\Desktop\ClaudeCode\dankyeeters-bluetooth-dashboard
.\gradlew.bat --console=plain test
```

`test` deckt die Abnahme genau ab:

- Es ist die Unit-Test-Aufgabe **aller fuenf Module** (132 Testdateien:
  `app` 50, `core-monitor` 34, `core-hearing` 30, `core-system` 11,
  `core-audio` 7).
- Es schliesst die **Robolectric**-Tests ein — sie laufen als normale
  Unit-Tests auf der Host-JVM; darunter `MonitorDatabaseMigrationTest` und
  `ScreenSmokeTest`.
- Es schliesst **keine** Geraetetests ein (`androidTest`, 8 Dateien). Die
  brauchen das Pixel und sind nicht Gegenstand von T-010.
- Fuer `:app` laeuft nur die Debug-Variante — der Release-Unit-Test ist im
  Buildfile bewusst abgeschaltet.

**Beleg, dass Robolectric wirklich gelaufen ist** (Exit-Code allein genuegt
nicht — eine leere Suite endet auch mit 0):

```
Get-ChildItem -Recurse -Filter "TEST-*.xml" -Path .\*\build\test-results |
  ForEach-Object { ([xml](Get-Content $_.FullName)).testsuite } |
  Measure-Object -Property tests,failures,errors,skipped -Sum
```

**Abnahmekriterium, scharf formuliert:**

1. `.\gradlew.bat --console=plain test` laeuft **bis zu einem Testergebnis**
   durch — kein Abbruch an JDK, SDK, NDK, CMake oder Distribution.
2. Die Summe der `tests` ueber alle `TEST-*.xml` ist **> 0** und enthaelt
   `MonitorDatabaseMigrationTest` (Robolectric) und `ScreenSmokeTest`
   (Robolectric + Compose).
3. Ein zweiter Lauf desselben Kommandos **ohne Netz** kommt bis zum
   Testergebnis. Das trennt „laeuft" von „laedt beim Laufen noch nach".

**Was ausdruecklich *nicht* Abnahmekriterium ist:** dass alle Tests gruen
sind. Ein roter Test ist ein Befund fuer `developer`/`qa-engineer` — die
Toolchain hat dann trotzdem geliefert, was T-010 verlangt. Ein Abbruch **vor**
dem ersten Testergebnis ist dagegen ein Toolchain-Fehler und meiner.

---

### 7. Risiken und Plattenbedarf

#### Plattenbedarf

| Posten | Download | Auf Platte |
|---|---|---|
| Temurin 21 (ZIP) | 195,6 MiB (gemessen) | ~330 MB (geschaetzt) |
| `platforms;android-36` | 62,8 MiB (gemessen) | ~120 MB (geschaetzt) |
| `build-tools;36.0.0` | 56,0 MiB (gemessen) | ~130 MB (geschaetzt) |
| `cmake;3.22.1` | 15,4 MiB (gemessen) | ~50 MB (geschaetzt) |
| Gradle 8.11.1 | 130,6 MiB (gemessen) | ~200 MB (geschaetzt) |
| Robolectric `android-all-instrumented` API 35 | 190,3 MiB (gemessen) | ~190 MB |
| Bibliotheken im Gradle-Cache | **nicht gemessen**, ~1,5–2,5 GB | dito |
| `build/`- und `.gradle/`-Verzeichnisse nach dem ersten Lauf | — | **nicht gemessen**, ~1–2 GB |
| **Summe** | **~2,2–3,2 GB** | **~4–6 GB** |

**650,7 MiB davon sind exakt gemessen**, der Rest ist als Schaetzung
gekennzeichnet. Frei auf `C:` sind 270 GiB (`df -h /c`) — Plattenplatz ist
kein Hinderungsgrund.

#### Adminrechte

**Kein Schritt dieses Plans braucht Administratorrechte.** Das ist der Grund
fuer ZIP statt MSI und fuer `HKCU` statt `HKLM`. Zwei *optionale*
Verbesserungen bruechten Admin und sind bewusst **nicht** Teil des Plans:
Windows-Defender-Ausnahmen fuer `~\.gradle` und das Repo (Bauzeit), und
`LongPathsEnabled` (siehe R-1).

#### Risiken

- **R-1 — Windows-Pfadlaenge (260 Zeichen).** Der ernsthafteste
  Windows-spezifische Fallstrick. KSP/Room-generierte Quellen und vor allem
  die CMake-Zwischenpfade unter `core-audio\.cxx\...\arm64-v8a\...` werden
  tief. Der Repo-Pfad ist mit 58 Zeichen guenstig kurz, das hilft. **Nicht
  geprueft**, weil dazu ein Bau gehoert. Kommt es, meldet es sich als
  „path too long" oder als unerklaerlicher CMake-Abbruch. Gegenmittel:
  `LongPathsEnabled=1` in
  `HKLM\SYSTEM\CurrentControlSet\Control\FileSystem` (**Admin**, Neustart),
  oder das Repo naeher an `C:\` legen.
- **R-2 — zwei adb-Binaries, ein Server.** `C:\RSL\2.1HF5\adb\adb.exe` (2021)
  und `platform-tools\adb.exe` 37.0.1 vertragen sich nicht: wer als zweiter
  startet, killt den Server des ersten („adb server version doesn't match").
  Fuer T-010 ohne Wirkung (Unit-Tests brauchen kein adb), fuer die
  Geraetesitzungen des `power-user` sehr wohl. **Regel: nur
  `%ANDROID_HOME%\platform-tools\adb.exe` benutzen**, und `C:\RSL\...` nicht
  in den PATH aufnehmen. Die vorhandenen `adbkey`/`adbkey.pub` in
  `~\.android` (vom 05.07.2021) werden von beiden geteilt — das Pixel ist
  also vermutlich schon autorisiert.
- **R-3 — Kein Hypervisor, kein Emulator.** Bestaetigt durch `GOAL.md` und
  nicht aufloesbar: Windows 10 **Home** hat kein Hyper-V. Folge: die acht
  `androidTest`-Dateien (`core-audio` 7, `app` 1) laufen **ausschliesslich**
  am Pixel 11 Pro am Kabel. Kein Blocker fuer T-010, aber ein Teil der
  Testflaeche haengt dauerhaft an einem Geraet, das nur zeitweise angesteckt
  ist.
- **R-4 — Erster Testlauf braucht Netz.** 190,3 MiB Robolectric-Laufzeit-JAR
  plus 1,5–2,5 GB Bibliotheken. Ein Testlauf, der daran scheitert, sieht aus
  wie ein kaputtes Setup. Deshalb ist der netzlose Zweitlauf Teil der Abnahme.
- **R-5 — Geteiltes `GRADLE_USER_HOME` mit einem fremden Projekt.** Siehe 1.
  Kein Versionskonflikt, aber zwei Daemons koennen gleichzeitig Speicher
  halten (der fremde lief mit `-Xmx3072m`, unserer bekommt ueber
  `gradle.properties` `-Xmx2560m` plus `org.gradle.parallel=true`). Bei
  32 GiB RAM unkritisch.
- **R-6 — Konfigurationscache.** `org.gradle.configuration-cache=true` ist
  gesetzt. Auf einer frisch eingerichteten Toolchain ist der erste Lauf
  deshalb langsamer und Fehlermeldungen sind manchmal in
  Konfigurationscache-Meldungen verpackt. Der Kommentar zu
  `verifyNoNulBytes` in `build.gradle.kts` zeigt, dass das Projekt diese
  Klippe schon einmal umschifft hat. **Bei unklarem Abbruch einmal mit
  `--no-configuration-cache` gegenpruefen**, bevor irgendetwas anderes
  verdaechtigt wird.
- **R-7 — Zeichensatz.** Die Daemon-Logs des fremden Projekts zeigen
  `-Dfile.encoding=windows-1252` als Systemvorgabe dieses Rechners. Unsere
  `gradle.properties` erzwingt `-Dfile.encoding=UTF-8` — gut so. Wer
  `org.gradle.jvmargs` ueberschreibt, holt windows-1252 zurueck und mit ihm
  kaputte Umlaute in Testnamen und Ressourcen.
- **R-8 — `platform-tools` 37.0.1 zu `compileSdk` 36.** Neuere
  Plattform-Tools sind abwaertskompatibel; kein bekanntes Problem.
  **Ungeprueft**, weil kein Bau lief.

#### Was dieser Plan ausdruecklich nicht beantwortet

- Ob `./gradlew test` nach der Einrichtung tatsaechlich durchlaeuft. Das ist
  Sache des Abschlusstests, nicht dieses Plans.
- Ob AGP 8.9.3 das vorhandene NDK 27.3.13750724 akzeptiert (siehe 3.).
- Ob Robolectric 4.14.1 API 36 unterstuetzt — irrelevant, solange alle Tests
  `sdk = 35` setzen, aber es waere zu wissen, bevor jemand `sdk` anhebt.
- Alles, was den **Endnutzer**-Zielrechner betrifft: Erstinstallation der APK,
  Update-Pfad ueber vorhandene Nutzerdaten (Room-Datenbank in `core-monitor`,
  DataStore in `core-hearing`), Deinstallation samt privilegiertem Helper.
  Fuer dieses Projekt existiert bislang **kein `CHANGELOG.md`** und keine
  Migrationsdokumentation. Das ist ein eigener Auftrag im Modus `notes`.

---

## 0.3.0 — 2026-09-02 — Toolchain-Rollout AUSGEFUEHRT (T-010, Schritte S-1..S-8)

Der Plan im Abschnitt darueber wurde vom Director freigegeben und hier
ausgefuehrt. Dieser Abschnitt haelt fest, **was tatsaechlich geschah** —
einschliesslich der Stellen, an denen der Plan falsch war.

Umgebung: derselbe Rechner wie oben. Ausgefuehrt zwischen 08:22 und 08:45 Uhr.
Der Lauf war **nicht isoliert**; siehe „Fremdeinwirkung" weiter unten.

### Ergebnis je Schritt

| Schritt | Ergebnis | Beleg |
|---|---|---|
| S-1 JDK 21 entpacken | **bestanden** | Download 205.073.461 Byte in 14,6 s, SHA256 `f9d6e191...8b4e` gegen Adoptium geprueft (`sha256sum -c` → OK). `java -version` → `Temurin-21.0.12.1+1`, 64-Bit Server VM, LTS. 329 MB auf Platte. |
| S-2 `JAVA_HOME` + PATH | **bestanden** | Registry-Rueckgabe: `JAVA_HOME` REG_SZ, `Path` weiterhin **REG_EXPAND_SZ** mit erhaltenem `%USERPROFILE%`. Prueflauf in einem Prozess mit **frischem Umgebungsblock** (ueber den Taskplaner erzwungen): `JAVA_HOME` gesetzt, `where java` → JDK-21-Pfad, `java -version` → Temurin 21. |
| S-3 SDK-Ort | **bestanden** | `local.properties` mit `sdk.dir=C:/Users/Daniel/tools/android-sdk`, von `.gitignore:3` erfasst. `sdkmanager --list_installed` **ohne** `--sdk_root` findet das SDK. |
| S-4 SDK-Pakete | **bestanden, aber anders als geplant** | Nur `cmake/3.22.1` musste nachinstalliert werden (44 MB). `platforms/android-36` und `build-tools/35.0.0` waren zum Zeitpunkt des Schritts bereits da — von fremder Hand. **Keine Lizenzabfrage**, also nie die Frage nach stellvertretender Zustimmung. |
| S-5 Gradle 8.11.1 | **bestanden, ohne Download** | Distribution lag schon seit 08:09:47 vor (fremder Bau). `gradlew --version` → `Gradle 8.11.1`, `Launcher JVM: 21.0.12.1 (Eclipse Adoptium)`, `Daemon JVM: ...jdk-21.0.12.1+1`. |
| S-6 Konfigurationslauf | **bestanden** | `gradlew --console=plain projects` → BUILD SUCCESSFUL in 1m 13s, Exit 0. Alle fuenf Module gelistet. Kein NDK-Fehler, kein „SDK location not found", keine Build-Tools-Klage. |
| S-7 Abhaengigkeiten | **bestanden** | Modul-Cache aufgeloest; Robolectric-Laufzeit in `~/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7/`, **199.527.464 Byte — auf das Byte die im Plan gemessene Groesse**, unter genau der vorhergesagten Koordinate. |
| S-8 Abschlusstest | **bestanden** | siehe naechster Abschnitt |

### Die Abnahme, gegen die drei selbst gesetzten Kriterien

Kommando: `.\gradlew.bat --console=plain test` — **BUILD SUCCESSFUL in 3m 27s**,
Exit 0, 203 Tasks (162 ausgefuehrt, 41 aus dem Cache).

1. **Laeuft bis zu einem Testergebnis** — bestanden. Kein Abbruch an JDK, SDK,
   NDK, CMake oder Distribution.
2. **Summe `tests` > 0, mit den zwei Robolectric-Belegen** — bestanden.
   230 Ergebnisdateien, **2332 Tests, 0 failures, 0 errors, 0 skipped**.
   Je Modul: `core-hearing` 714, `core-monitor` 676, `app` 490,
   `core-system` 276, `core-audio` 176.
   `MonitorDatabaseMigrationTest` 5 Tests gruen (in beiden Varianten),
   `ScreenSmokeTest` 6 Tests gruen.
3. **Zweitlauf ohne Netz** — **bestanden mit benannter Einschraenkung.**
   `gradlew --offline --rerun-tasks test` → BUILD SUCCESSFUL in 1m 11s,
   **203 von 203 Tasks wirklich neu ausgefuehrt**, erneut 2332 Tests, 0 Fehler,
   Ergebnisdateien frisch von 08:39:30–08:40:27.
   **Einschraenkung, ehrlich:** `--offline` sperrt Gradles Netzzugriff hart,
   nicht aber den von Robolectric. Eine echte Netztrennung war nicht moeglich —
   eine auf `jdk-21\java.exe` gezielte Firewall-Regel braucht
   Administratorrechte (nicht vorhanden, gemessen), und das Netz global zu
   kappen haette den parallel laufenden Fremdbau sabotiert. Die
   Robolectric-Seite ist damit **belegt, aber nicht erzwungen**.

**Alle Tests gruen.** Es gibt aus diesem Lauf **keinen** Befund fuer
`developer` oder `qa-engineer` aus fehlschlagenden Tests.

### Zusatzpruefungen, die der Plan nicht vorsah

**Der NDK-Pin aus T-012 ist jetzt hart verifiziert, nicht nur konfiguriert.**
S-6 allein war ein schwacher Beleg: `core-audio/.cxx` existierte danach nicht,
der native Bau war also nie angefasst worden. Deshalb nachgeholt:
`gradlew :core-audio:externalNativeBuildDebug` → BUILD SUCCESSFUL in 8 s,
alle drei ABIs. `CMakeCache.txt` beweist, was wirklich benutzt wurde:

- `ANDROID_NDK = C:\Users\Daniel\tools\android-sdk\ndk\27.3.13750724` — **der
  gepinnte NDK, nicht irgendein Default.**
- `CMAKE_COMMAND = .../cmake/3.22.1/bin/cmake.exe` — die geforderte Version.
- `ANDROID_PLATFORM = android-31` — passend zu `minSdk 31`.

**Damit ist B-3 beantwortet: Variante (a) traegt, der 2,5-GB-Nachladeweg (b)
entfaellt.**

**16-KB-Seitenausrichtung geprueft.** `core-audio/build.gradle.kts` verspricht
sie, weil Android 17 eine 4-KB-ausgerichtete Bibliothek zurueckweist. Mit
`llvm-readelf -l` aus dem NDK gegen alle drei erzeugten `.so`:
`LOAD align = 0x4000` (16384 Byte) fuer `arm64-v8a`, `armeabi-v7a` **und**
`x86_64`. Die Zusage haelt auf dieser Toolchain.

### Vier Stellen, an denen mein Plan falsch war

1. **S-2, die ernsteste.** Der Plan wollte den User-`Path` ueber
   `[Environment]::GetEnvironmentVariable/SetEnvironmentVariable` lesen und
   schreiben und `%JAVA_HOME%\bin` anhaengen. Der Path ist hier
   **`REG_EXPAND_SZ`** und enthaelt roh `%USERPROFILE%\AppData\Local\Microsoft\WindowsApps`.
   Der geplante Weg haette (a) diese Indirektion durch den expandierten Pfad
   einbetoniert und (b) den Werttyp auf `REG_SZ` gekippt, womit jede
   verbleibende `%VAR%`-Angabe tot gewesen waere. **Stattdessen ausgefuehrt:**
   Registry-API mit `DoNotExpandEnvironmentNames`, Rueckschreiben explizit als
   `ExpandString`, und angehaengt wurde der **literale** JDK-Pfad statt
   `%JAVA_HOME%\bin` — die Expansionsreihenfolge innerhalb von
   `HKCU\Environment` garantiert nicht, dass `JAVA_HOME` schon definiert ist,
   wenn `Path` expandiert wird.
2. **S-3.** Der Plan schrieb `sdk.dir=C:\\Users\\Daniel\\tools\\android-sdk`.
   In einer `.properties`-Datei ist `\t` aus `\tools` ein **Tabulator**; die
   Datei waere still falsch gewesen. Ausgefuehrt mit Schraegstrichen und mit
   `cat -A` und einer Tabulator-Suche gegengeprueft.
3. **S-4, Kommandosyntax.** `sdkmanager "cmake;3.22.1"` scheitert mit
   „Package cmake not found. Package 3.22.1 not found." — cmdline-tools 23.0
   delegiert an die neue `android`-CLI, die am Semikolon zerlegt. Richtig ist
   **`cmake/3.22.1`** mit Schraegstrich.
4. **S-4, `build-tools`.** Der Plan nannte **36.0.0**. Das war die einzige
   Zahl im ganzen Plan, die ich **nicht gemessen, sondern angenommen** hatte,
   und sie war falsch: AGP 8.9.3 ist mit **35.0.0** zufrieden — belegt dadurch,
   dass Konfiguration, Ressourcen-Merge und 2332 Tests damit durchlaufen.
   36.0.0 wurde deshalb **nicht** geladen; 56 MiB gespart.

### Fremdeinwirkung waehrend des Laufs — die Isolierung, die es nicht gab

Waehrend ich arbeitete, veraenderte eine zweite Hand denselben Rechner. Das
gehoert in den Bericht, weil es erklaert, warum Teile meines Plans „schon
erledigt" waren:

- **Gradle 8.11.1** (08:09:47), **AGP im Modul-Cache** (43 → 135 Gruppen),
  **`platforms/android-36`** und **`build-tools/35.0.0`** (08:23:07 bzw.
  08:23:24) stammen **nicht** aus meinem Lauf. Die Daemon-Logs unter
  `~/.gradle/daemon/8.11.1/` weisen alle auf `root project 'pension-manager'`
  und liefen auf **JDK 17**. Das ist ein anderes Projekt auf diesem Rechner,
  das ebenfalls Gradle 8.11.1 benutzt.
- **Damit ist Risiko R-5 aus dem Plan (geteiltes `GRADLE_USER_HOME`) kein
  theoretisches mehr, sondern beobachtet.** Es hat hier nicht geschadet — die
  Gradle-Versionen sind identisch, die Caches vertragen sich — aber der
  41-Tasks-Cache-Treffer im ersten Testlauf kam aus einem gemeinsamen
  Build-Cache, und wer diesen Rechner spaeter fuer eine
  Reproduzierbarkeitsaussage benutzt, muss das wissen.
- **`docs/state.md` wurde um 08:24:50 fremd ueberschrieben** (113 Zeilen rein,
  216 raus), mitten in meinem Lauf. Nicht von mir; die Datei fuehrt der
  Director.

**Folge fuer die Aussagekraft:** Die Abnahme selbst ist davon unberuehrt — die
Tests liefen auf JDK 21 gegen genau dieses Repo. Aber die Aussage „so wird eine
frische Maschine eingerichtet" ist durch diesen Lauf **nicht** vollstaendig
belegt, weil drei Posten schon dalagen. Wer den Plan auf einem wirklich leeren
Rechner nachfahren will, laedt zusaetzlich Gradle (130,6 MiB),
`platforms/android-36` (62,8 MiB) und `build-tools/35.0.0` an.

### Warnungen, die nicht verschluckt werden

- **`sdkmanager` gibt bei geglueckter Installation Exit-Code 127 zurueck.**
  `cmake/3.22.1` wurde nachweislich korrekt installiert (`cmake.exe --version`
  → 3.22.1, 44 MB), der `.bat`-Aufruf meldete trotzdem 127. Ein Skript, das
  Exit-Codes prueft, haelt diese Installation fuer gescheitert. **Fuer jede
  spaetere Automatisierung: Erfolg am Dateisystem pruefen, nicht am Exit-Code.**
- **`sdkmanager` ist ab cmdline-tools 23.0 abgekuendigt** und meldet das bei
  jedem Aufruf; Nachfolger ist `android sdk`.
- **JVM-Warnung bei jedem SDK-Aufruf:** „The UseAllWindowsProcessorGroups flag
  is not supported on this Windows version and will be ignored." Harmlos,
  Windows 10 kennt das Flag nicht.
- **Drei Deprecation-Warnungen im Bau** (an den `developer`, nicht an mich):
  `resourceConfigurations` (`app/build.gradle.kts:23`), `enableUnitTest`
  (`app/build.gradle.kts:78`, **entfaellt in AGP 9.0**) und
  `Looper.prepareMainLooper()` (`PrivilegedServer.kt:135`).
- **„1 incompatible ... Daemon could not be reused"** beim ersten Lauf — der
  unvertraegliche Daemon ist der JDK-17-Daemon des Fremdprojekts. Erwartet.

### Plattenbedarf: Schaetzung gegen Messung

| Posten | Plan | Gemessen |
|---|---|---|
| JDK 21 auf Platte | ~330 MB | **329 MB** |
| `cmake/3.22.1` | ~50 MB | **44 MB** |
| Gradle 8.11.1 entpackt | ~200 MB | **146 MB** |
| Gradle-Caches gesamt | 1,5–2,5 GB | **2,5 GB** (mit Fremdanteil) |
| Robolectric-Laufzeit | 190 MB | **191 MB** (`~/.m2/repository`) |
| Bau-Ergebnisse im Repo | 1–2 GB | **122 MB** (`.gradle` 7,3 + fuenf `build/` 112 + `.cxx` 2,8) |
| SDK gesamt | — | **2,8 GB** |

Die Schaetzung fuer die Bau-Ergebnisse war um rund eine Groessenordnung zu
hoch. Frei auf `C:` unveraendert **270 GiB**.

### Was zurueckbleibt

- **Dauerhaft:** `HKCU\Environment` mit `JAVA_HOME`, `ANDROID_HOME` und dem
  erweiterten `Path`; JDK 21 unter `~\tools\jdk\jdk-21.0.12.1+1`;
  `cmake/3.22.1` im SDK; `local.properties` im Repo (gitignored);
  Gradle-Caches und Bau-Ergebnisse.
- **Sicherungen fuer den Rueckweg** liegen im Scratchpad dieser Sitzung:
  `BACKUP_HKCU_Environment.reg` (voller Registry-Export vor der Aenderung),
  `BACKUP_user_path_RAW.txt` (unexpandierter Path). **Scratchpad-Verzeichnisse
  sind fluechtig** — wer den Rueckweg dauerhaft will, muss die `.reg`-Datei an
  einen bestaendigen Ort kopieren.
- **Laufende Gradle-Daemons:** vier `java`-Prozesse, zusammen rund 5 GB
  Arbeitsspeicher. Zwei davon gehoeren dem Fremdprojekt. **`gradlew --stop`
  wurde bewusst nicht ausgefuehrt**, weil es die Daemons des Fremdbaus mit
  beenden koennte.
- **Nicht angefasst:** `adb`. Der Prozess der laufenden M-5-Messung
  (PID 15084, gestartet 01.09. 21:12:52) ist am Ende des Laufs mit
  unveraenderter PID und Startzeit vorhanden. Weder das SDK-`adb` noch das
  RSL-`adb` wurde aufgerufen, `platform-tools` steht nicht im PATH.
- **Temporaer angelegt und wieder entfernt:** eine geplante Aufgabe
  `RM_FreshEnvProbe` samt Hilfsskript, nur um einen frischen Umgebungsblock zu
  erzwingen. Beides geloescht und die Loeschung geprueft.

### Weiterhin ungeprueft

- **Die Zielplattform selbst.** Nichts von diesem Lauf sagt etwas ueber das
  Pixel 11 Pro. Die acht `androidTest`-Dateien liefen **nicht** — das Geraet
  war fuer die M-5-Messung gebunden und `adb` war gesperrt.
- **R-1 Pfadlaenge:** nicht mehr rein theoretisch widerlegt, aber auch nicht
  bewiesen — der native Bau lief durch, also bleibt die 260-Zeichen-Grenze in
  dieser Repo-Lage folgenlos. Bei tieferer Ablage ungeprueft.
- **Robolectric ohne Netz** (siehe Kriterium 3).
- **Ein wirklich leerer Rechner** — siehe „Fremdeinwirkung".
- **Alles zur Auslieferung an Endnutzer:** kein `CHANGELOG.md`, keine
  Migrationsdokumentation fuer die Room-Datenbank (`core-monitor`) und den
  DataStore (`core-hearing`), kein Update- und kein Deinstallationsweg. Der
  privilegierte Helper ueberlebt laut `SR-016` die Deinstallation. Das ist ein
  eigener Auftrag im Modus `notes` und die eigentliche Luecke bis zur
  Releasefaehigkeit.
