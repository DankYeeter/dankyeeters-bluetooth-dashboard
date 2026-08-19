#include "ToneGenerator.h"

#include <android/log.h>
#include <cmath>
#include <algorithm>

#define LOG_TAG "BtDashToneGen"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace btdashboard {

namespace {
constexpr double kTwoPi = 6.283185307179586476925286766559;
constexpr int32_t kChannelCount = 2;
} // namespace

ToneGenerator::~ToneGenerator() {
    stop();
}

bool ToneGenerator::start() {
    if (mStream) return true;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            // The hearing test values correctness over latency; Oboe will fall
            // back to a shared stream automatically if exclusive is refused.
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(kChannelCount)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK || !mStream) {
        LOGW("Failed to open output stream: %s", oboe::convertToText(result));
        mStream.reset();
        return false;
    }

    mSampleRate = mStream->getSampleRate();
    recomputePhaseIncrement();

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGW("Failed to start stream: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        mSampleRate = 0;
        return false;
    }
    return true;
}

void ToneGenerator::stop() {
    if (!mStream) return;
    mStream->stop();
    mStream->close();
    mStream.reset();
    mSampleRate = 0;
    mCurrentAmplitude = 0.0;
    mPhase = 0.0;
    mRampArmed = false;
    mRampPosition = 0.0;
    mRampStartAmplitude = 0.0;
    mRampTargetAmplitude = 0.0;
}

void ToneGenerator::recomputePhaseIncrement() {
    if (mSampleRate <= 0) {
        mPhaseIncrement = 0.0;
        return;
    }
    mPhaseIncrement = kTwoPi * mFrequency.load(std::memory_order_relaxed) / mSampleRate;
}

void ToneGenerator::setFrequency(double hz) {
    mFrequency.store(std::max(1.0, hz), std::memory_order_relaxed);
    recomputePhaseIncrement();
}

void ToneGenerator::setLevelDbFs(double db) {
    // Attenuation only. Positive values would clip the output stage.
    const double clamped = std::min(0.0, db);
    const double amplitude = std::pow(10.0, clamped / 20.0);
    mTargetAmplitude.store(amplitude, std::memory_order_relaxed);
    mSettled.store(false, std::memory_order_relaxed);
}

void ToneGenerator::setChannel(ToneChannel channel) {
    mChannel.store(static_cast<int32_t>(channel), std::memory_order_relaxed);
}

void ToneGenerator::setToneActive(bool active) {
    mActive.store(active, std::memory_order_relaxed);
    mSettled.store(false, std::memory_order_relaxed);
}

void ToneGenerator::setRampMs(double ms) {
    mRampMs.store(std::max(1.0, ms), std::memory_order_relaxed);
}

oboe::DataCallbackResult ToneGenerator::onAudioReady(oboe::AudioStream * /*stream*/,
                                                     void *audioData,
                                                     int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);

    const bool active = mActive.load(std::memory_order_relaxed);
    const double target = active ? mTargetAmplitude.load(std::memory_order_relaxed) : 0.0;
    const int32_t channel = mChannel.load(std::memory_order_relaxed);

    // Re-arm the ramp whenever the effective target moved. The ramp always runs
    // from wherever the envelope currently is to the new target over the full
    // configured time — never a rate proportional to the target, which would
    // make a gate-off from a quiet tone take minutes (and from a loud one,
    // hours) instead of the configured milliseconds.
    if (!mRampArmed || target != mRampTargetAmplitude) {
        mRampStartAmplitude = mCurrentAmplitude;
        mRampTargetAmplitude = target;
        mRampLengthSamples =
                std::max(1.0, mRampMs.load(std::memory_order_relaxed) * 0.001 * mSampleRate);
        mRampPosition = 0.0;
        mRampArmed = true;
        mSettled.store(false, std::memory_order_relaxed);
    }

    const bool leftOn = channel != static_cast<int32_t>(ToneChannel::Right);
    const bool rightOn = channel != static_cast<int32_t>(ToneChannel::Left);

    for (int32_t i = 0; i < numFrames; ++i) {
        if (mRampPosition < mRampLengthSamples) {
            mRampPosition += 1.0;
            // Raised cosine: zero slope at both ends, so no click cues the
            // listener that a stimulus started or stopped.
            const double t = mRampPosition / mRampLengthSamples;
            const double shaped = 0.5 * (1.0 - std::cos(M_PI * std::min(1.0, t)));
            mCurrentAmplitude =
                    mRampStartAmplitude + (mRampTargetAmplitude - mRampStartAmplitude) * shaped;
        } else {
            mCurrentAmplitude = mRampTargetAmplitude;
        }

        const auto sample = static_cast<float>(mCurrentAmplitude * std::sin(mPhase));
        mPhase += mPhaseIncrement;
        if (mPhase >= kTwoPi) mPhase -= kTwoPi;

        out[i * kChannelCount] = leftOn ? sample : 0.0f;
        out[i * kChannelCount + 1] = rightOn ? sample : 0.0f;
    }

    if (mRampPosition >= mRampLengthSamples) {
        mSettled.store(true, std::memory_order_relaxed);
    }
    return oboe::DataCallbackResult::Continue;
}

void ToneGenerator::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    // Typical cause: the Bluetooth device disconnected mid-test. The Kotlin
    // layer polls isRunning() and aborts the run; we just drop the stream here.
    LOGW("Stream closed with error: %s", oboe::convertToText(error));
    mStream.reset();
    mSampleRate = 0;
}

} // namespace btdashboard
