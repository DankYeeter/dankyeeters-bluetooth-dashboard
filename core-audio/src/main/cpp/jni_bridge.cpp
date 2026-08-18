#include <jni.h>
#include "ToneGenerator.h"

using btdashboard::ToneGenerator;
using btdashboard::ToneChannel;

namespace {
inline ToneGenerator *toGen(jlong handle) {
    return reinterpret_cast<ToneGenerator *>(handle);
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new ToneGenerator());
}

JNIEXPORT void JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeDestroy(JNIEnv *, jobject,
                                                                            jlong handle) {
    delete toGen(handle);
}

JNIEXPORT jboolean JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeStart(JNIEnv *, jobject,
                                                                          jlong handle) {
    return toGen(handle)->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeStop(JNIEnv *, jobject,
                                                                         jlong handle) {
    toGen(handle)->stop();
}

JNIEXPORT void JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeSetFrequency(
        JNIEnv *, jobject, jlong handle, jdouble hz) {
    toGen(handle)->setFrequency(hz);
}

JNIEXPORT void JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeSetLevelDbFs(
        JNIEnv *, jobject, jlong handle, jdouble db) {
    toGen(handle)->setLevelDbFs(db);
}

JNIEXPORT void JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeSetChannel(
        JNIEnv *, jobject, jlong handle, jint channel) {
    toGen(handle)->setChannel(static_cast<ToneChannel>(channel));
}

JNIEXPORT void JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeSetToneActive(
        JNIEnv *, jobject, jlong handle, jboolean active) {
    toGen(handle)->setToneActive(active == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeSetRampMs(
        JNIEnv *, jobject, jlong handle, jdouble ms) {
    toGen(handle)->setRampMs(ms);
}

JNIEXPORT jint JNICALL
Java_dev_dankyeeter_btdashboard_audio_tone_NativeToneGenerator_nativeSampleRate(
        JNIEnv *, jobject, jlong handle) {
    return toGen(handle)->sampleRate();
}

} // extern "C"
