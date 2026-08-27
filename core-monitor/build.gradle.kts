plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.dankyeeter.btdashboard.monitor"
    compileSdk = 36
    defaultConfig { minSdk = 31 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * Where Room writes the schema JSON for each version.
 *
 * `MonitorDatabase` sets `exportSchema = true` and no longer falls back to a
 * destructive migration, which means every future version bump has to be
 * written by hand against the previous schema. This directory is that previous
 * schema, in the repository, rather than something reconstructed from whatever
 * the entity classes looked like at the time.
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)

    // Shell identity for BQR registration, dumpsys and ps.

    // Event/sample history needs real queries and time-range scans → Room.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The database tests need real SQLite and a real Context. Both artifacts
    // are already in the catalog and already on :app's test classpath, so this
    // adds nothing new to the build - and the alternative, an instrumented
    // test, cannot run in `./gradlew test` at all.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
