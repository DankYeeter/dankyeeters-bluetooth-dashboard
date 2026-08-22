# Third-Party Licenses

DankYeeter's Bluetooth Dashboard bundles the libraries below. All are licensed
under the **Apache License 2.0**, whose terms require this attribution notice to
travel with any distribution of the app. The app's own source is under the
MIT terms in `LICENSE`.

For a Play Store release this list should be surfaced in-app (an
"Open-source licenses" screen); see `PLAYSTORE_COMPLIANCE.md` (R4).

## Apache License 2.0

- **Google Oboe** (`com.google.oboe:oboe`) — © Google LLC
- **AndroidX Core KTX** (`androidx.core:core-ktx`) — © The Android Open Source Project
- **AndroidX Lifecycle** (runtime-ktx, viewmodel-compose, runtime-compose) — © AOSP
- **AndroidX Activity Compose** (`androidx.activity:activity-compose`) — © AOSP
- **AndroidX Navigation Compose** (`androidx.navigation:navigation-compose`) — © AOSP
- **Jetpack Compose** (BOM: ui, ui-graphics, ui-tooling, material3,
  material-icons-extended) — © AOSP
- **AndroidX DataStore** (`androidx.datastore:datastore-preferences`) — © AOSP
- **AndroidX Room** (room-runtime, room-ktx, room-compiler) — © AOSP
- **AndroidX Test** (core-ktx, compose ui-test-junit4, ui-test-manifest) — © AOSP
- **Robolectric** (test only) — © The Robolectric authors
- **kotlinx.coroutines** (coroutines-android, coroutines-test) — © JetBrains s.r.o.
- **kotlinx.serialization** (serialization-json) — © JetBrains s.r.o.

The full Apache 2.0 license text is available at
https://www.apache.org/licenses/LICENSE-2.0 and must be shipped alongside this
notice in any binary distribution.

No third-party code is copied into this repository; every entry above is a
Gradle dependency resolved at build time. The AirPods BLE-beacon support is a
clean re-implementation from public protocol descriptions, not derived from any
GPL-licensed project (see PLAN.md).
