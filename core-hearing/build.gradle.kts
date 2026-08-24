plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.dankyeeter.btdashboard.hearing"
    compileSdk = 36
    defaultConfig { minSdk = 31 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Public API of this module exposes Ear/EqSettings from :core-audio.
    api(project(":core-audio"))
    api(libs.kotlinx.coroutines.android)

    // Run storage: a handful of small records, no queries — DataStore is enough.
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
