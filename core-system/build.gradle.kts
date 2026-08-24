plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.dankyeeter.btdashboard.system"
    compileSdk = 36
    defaultConfig { minSdk = 31 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    /**
     * Android stubs throw by default in unit tests, so a single `Log.w` on an
     * error path fails a test that is about the error path. The attachment code
     * logs exactly there - when AudioFlinger refuses an effect - and that
     * branch is the one worth testing.
     */
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core-audio"))
    api(project(":core-hearing"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.androidx.datastore.preferences)


    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
