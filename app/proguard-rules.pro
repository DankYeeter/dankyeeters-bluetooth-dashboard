# Keep JNI entry points for the native tone generator.
-keepclasseswithmembernames class dev.dankyeeter.btdashboard.audio.tone.NativeToneGenerator {
    native <methods>;
}

# Shizuku API uses reflection against hidden framework classes.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
