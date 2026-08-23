plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.dankyeeter.btdashboard"
    compileSdk = 35

    defaultConfig {
        // Instrumented tests live here now: the ADB reachability probe has to
        // run on the device, in the app's own process, because that is the only
        // place its private key and network position exist.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "dev.dankyeeter.btdashboard"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        // English-only app: no translated resources are shipped.
        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // AIDL for the privileged helper's Binder interface. The generated stub
    // has to be loadable in two places: this app, and the helper that
    // app_process starts from this same APK.
    buildFeatures {
        compose = true
        aidl = true
    }

    /**
     * Robolectric smoke tests need the merged resources and the manifest —
     * without this every screen fails on the first `stringResource`/theme
     * lookup rather than on anything meaningful.
     */
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

/**
 * Unit tests run on the debug variant only.
 *
 * The Compose test rule hosts screens in the empty activity that
 * `ui-test-manifest` contributes, and that artifact is deliberately
 * debug-only — merging a test activity into the release manifest would ship it
 * to users. Running the same suite twice bought nothing anyway.
 */
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

dependencies {
    implementation(project(":core-audio"))
    implementation(project(":core-hearing"))
    implementation(project(":core-system"))
    implementation(project(":core-monitor"))

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)

    // Reference arithmetic for the differential test only; never shipped.
    testImplementation(libs.eddsa)
    testImplementation(libs.spake2)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Supplies the empty activity the Compose test rule hosts screens in.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
