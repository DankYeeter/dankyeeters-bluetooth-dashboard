# Third-Party Licenses

DankYeeter's Bluetooth Dashboard bundles the libraries below. The app's own
source is under the MIT terms in `LICENSE`.

The text the app shows to users is `app/src/main/assets/licenses.txt` (page
"Open-source licenses"), including the full Apache License 2.0 text. That file
follows the reading rules of the text page (`UI_SPEC.md`, "Die Textseite"): a
blank line ends a paragraph, a line `# ` is a heading, a line `- ` is a bullet,
line breaks inside a paragraph are re-flowed, so wrapped lines must not start
with an indent, `-` or `*`. The Apache text was un-indented for that reason;
its words are unchanged (source: Conscrypt tag 2.5.3, `LICENSE`). This file
is the maintenance list behind it: **when a shipped dependency is added or
removed, change both, in the same commit.** The check before each release is
`gradlew :app:dependencies --configuration releaseRuntimeClasspath`
(C-001 Auflage 15; a GPL or AGPL entry there blocks the release).

## Shipped in the app

### Apache License 2.0

- **Conscrypt 2.5.3** (`org.conscrypt:conscrypt-android`) — Conscrypt project,
  Google. Its license file additionally names a BSD-style license (Chromium
  project, one generated file, reproduced in `licenses.txt`) and a NIST
  public-domain notice (test data). The native library is built on BoringSSL
  (see "Open points" below).
- **Google Oboe 1.9.3** (`com.google.oboe:oboe`) — © Google LLC
- **AndroidX Core KTX** (`androidx.core:core-ktx`) — © The Android Open Source Project
- **AndroidX Lifecycle** (viewmodel-compose, runtime-compose) — © AOSP
- **AndroidX Activity Compose** (`androidx.activity:activity-compose`) — © AOSP
- **AndroidX Navigation Compose** (`androidx.navigation:navigation-compose`) — © AOSP
- **Jetpack Compose** (BOM: ui, ui-graphics, material3) — © AOSP
- **Material Icons** (path data of ten icons, copied from
  `material-icons-extended` 1.7.6 into `app/.../ui/icons/AppIcons.kt`) — © Google LLC
- **AndroidX DataStore** (`androidx.datastore:datastore-preferences`) — © AOSP
- **AndroidX Room** (room-runtime, room-ktx) — © AOSP
- **Kotlin** (kotlin-stdlib) — © JetBrains s.r.o. and Kotlin Programming
  Language contributors
- **kotlinx.coroutines** (coroutines-android) — © JetBrains s.r.o.
- **kotlinx.serialization** (serialization-json) — © JetBrains s.r.o.

### BSD-style (Chromium project), named in Conscrypt's license file

Applies to `constants/src/gen/java/cert_verify_proc_blocklist.inc` in the
Conscrypt source repository. That file exists in the Conscrypt master branch;
in the 2.5.3 tag it does not (checked 29.09.2026: HTTP 404). The notice is
kept because C-001 Auflage 14 asks for it and a later Conscrypt version will
contain it.

### NIST public-domain notice, named in Conscrypt's license file

Applies to test data (`common/src/test/resources/crypto/*`) in the Conscrypt
source repository. Not part of the binary; kept for completeness of the
upstream license file.

## Used only to build and test — not shipped

These are not in the installed app and need no notice there. Listed so the
next reader does not have to look them up again.

- **Robolectric 4.14.1** — **MIT License** (© Xtreme Labs, Pivotal Labs and
  Google Inc.). An earlier version of this file listed it under Apache 2.0;
  that was wrong (LICENSE of the Robolectric repository, checked 29.09.2026).
- **JUnit 4.13.2** — Eclipse Public License 1.0
- **eddsa 0.3.0** (`net.i2p.crypto:eddsa`) — CC0 (public domain dedication);
  used only to cross-check the app's own Ed25519 arithmetic in tests.
- **AndroidX Test** (core-ktx, compose ui-test-junit4, ui-test-manifest) — Apache 2.0, © AOSP
- **kotlinx.coroutines-test** — Apache 2.0, © JetBrains s.r.o.

## Open points (for the compliance-agent)

- **BoringSSL.** Conscrypt's README states that it uses BoringSSL for
  cryptography and TLS; the native library `libconscrypt_jni.so` in the
  `conscrypt-android` AAR is the compiled result. BoringSSL's own license
  (OpenSSL/SSLeay/ISC-style terms) is not named in Conscrypt's license file,
  and C-001 Auflage 14 does not list it. `licenses.txt` points to BoringSSL's
  published license by URL but does not reproduce it. Whether the full text
  has to ship in the binary distribution has not been decided.
- Transitive AndroidX artifacts (annotation, collection, concurrent-futures,
  and similar) are covered by the entry "AndroidX" in words only; the
  release check above is what finds a transitive artifact under another
  license.

## Not copied code

Apart from the Material icon path data above, no third-party code is copied
into this repository; every other entry is a Gradle dependency resolved at
build time. The AirPods BLE-beacon support is a clean re-implementation from
public protocol descriptions, not derived from any GPL-licensed project (see
`docs/archiv/PLAN.md`).
