plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.dankyeeter.btdashboard.audio"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
        // The acoustic verification runs on a real device: it plays a tone,
        // records it back through the microphone and measures the difference.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // Oboe is consumed as a Gradle prefab package; C++17 for the engine.
                // 16 KB page alignment.
                //
                // Newer Pixels run with 16 KB memory pages, and Android 17
                // refuses to treat a 4 KB-aligned library as compatible - it
                // says so in a dialog on first launch, and Google Play requires
                // the alignment outright. Found on a Pixel 11 Pro; the older
                // device runs 4 KB pages and could never have shown it.
                //
                // The flag is what NDK r27 does by default; setting it
                // explicitly keeps the result independent of which NDK happens
                // to be installed.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
                cppFlags += listOf("-std=c++17", "-fno-exceptions", "-Wall")
                // Belt and braces: older NDKs ignore the argument above, and a
                // silently 4 KB-aligned library is exactly the failure that
                // only shows up on hardware nobody tested on.
                arguments += listOf("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures { prefab = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    /**
     * Android stubs throw by default in unit tests, so a single `Log.i` fails a
     * test that never went near the framework. The equaliser logs on every
     * enable and on every release that misbehaves - which is precisely the code
     * the effect-lifecycle tests are about. Same setting, same reason, as
     * :core-system.
     */
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.oboe)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
