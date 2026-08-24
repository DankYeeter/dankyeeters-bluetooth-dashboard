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
    // Conscrypt, bundled rather than borrowed from the platform.
    //
    // Pairing needs RFC 5705 keying material exported from the TLS connection,
    // and the platform copy of Conscrypt has exactly that method - but it is
    // `domain=core-platform, api=blocked`, so reflection is refused outright
    // rather than merely warned about. Measured on device, not assumed.
    //
    // Bundling costs a few megabytes of native library and makes the exporter
    // a public, supported API that no future Android release can withdraw.
    implementation(libs.conscrypt.android)

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
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Supplies the empty activity the Compose test rule hosts screens in.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * Keeps the bundled Conscrypt off the unit-test classpath.
 *
 * Robolectric runs on the host JVM, where `libconscrypt_jni.so` does not exist,
 * so any test whose class graph reaches Conscrypt dies with an
 * `UnsatisfiedLinkError` that has nothing to do with what it was testing. On
 * the device the library is present and this exclusion does not apply.
 *
 * The exporter it exists for is exercised on the device, not here - a host JVM
 * has no adbd to pair with, so there is nothing lost by leaving it out.
 *
 * Only the `conscrypt-android` artifact is excluded, not the whole group:
 * Robolectric installs an `OpenSSLProvider` while setting up every test, and it
 * needs its own host-native build of Conscrypt to do so. Excluding the group
 * trades one failure for another.
 *
 * The configuration is named `debugUnitTestRuntimeClasspath` - it does not begin
 * with "test", which is what made an earlier attempt at this silently match
 * nothing at all.
 */
configurations
    .matching { it.name.endsWith("UnitTestRuntimeClasspath") }
    .configureEach { exclude(group = "org.conscrypt", module = "conscrypt-android") }
