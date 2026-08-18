#ifndef BTDASHBOARD_TONEGENERATOR_H
#define BTDASHBOARD_TONEGENERATOR_H

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>

namespace btdashboard {

/**
 * Which ear the tone is routed to. Channel isolation is strict: the silent
 * channel is written as exact 0.0f, never an attenuated copy. This matters for
 * pure-tone audiometry, where crosstalk would invalidate per-ear thresholds.
 */
enum class ToneChannel : int32_t {
    Left = 0,
    Right = 1,
    Both = 2,
};

/**
 * Sample-accurate stereo sine generator on top of Oboe.
 *
 * Design notes:
 *  - Phase is a double accumulator advanced per sample, so frequency is exact
 *    and independent of callback buffer sizes.
 *  - Level is set in dBFS attenuation (<= 0). Never use system volume for the
 *    5 dB audiometry steps: Bluetooth absolute volume quantises far too
 *    coarsely (typically 16 steps over the whole range).
 *  - Amplitude changes and start/stop are ramped (raised-cosine) to avoid
 *    click artefacts, which would otherwise be audible cues for the listener
 *    and bias the threshold measurement.
 *  - The audio callback is lock-free: it only reads std::atomic parameters.
 */
class ToneGenerator : public oboe::AudioStreamDataCallback,
                      public oboe::AudioStreamErrorCallback {
public:
    ToneGenerator() = default;
    ~ToneGenerator() override;

    /** Opens and starts the output stream. Returns true on success. */
    bool start();

    /** Stops and closes the output stream. Safe to call when not started. */
    void stop();

    /** Sets the sine frequency in Hz (ramp-free; only change while muted). */
    void setFrequency(double hz);

    /** Sets attenuation in dBFS (<= 0). -inf equivalent: use setToneActive(false). */
    void setLevelDbFs(double db);

    /** Selects the active ear. */
    void setChannel(ToneChannel channel);

    /**
     * Gates the tone on/off with a ramp of rampMs milliseconds.
     * Clinical protocols use pulsed tones; the state machine in :core-hearing
     * drives the pulsing through this call.
     */
    void setToneActive(bool active);

    /** Ramp length applied on gate changes, in milliseconds. */
    void setRampMs(double ms);

    /** Actual stream sample rate, or 0 if not started. */
    int32_t sampleRate() const { return mSampleRate; }

    /** True once the current ramp has fully settled at its target amplitude. */
    bool isSettled() const { return mSettled.load(std::memory_order_relaxed); }

    // oboe callbacks
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    void recomputePhaseIncrement();

    std::shared_ptr<oboe::AudioStream> mStream;
    int32_t mSampleRate = 0;

    std::atomic<double> mFrequency{1000.0};
    std::atomic<double> mTargetAmplitude{0.0};
    std::atomic<int32_t> mChannel{static_cast<int32_t>(ToneChannel::Both)};
    std::atomic<double> mRampMs{30.0};
    std::atomic<bool> mSettled{true};
    std::atomic<bool> mActive{false};

    // Audio-thread-only state.
    double mPhase = 0.0;
    double mPhaseIncrement = 0.0;
    double mCurrentAmplitude = 0.0;
};

} // namespace btdashboard

#endif // BTDASHBOARD_TONEGENERATOR_H
