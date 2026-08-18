package dev.dankyeeter.btdashboard.audio.tone

import android.util.Log
import java.io.Closeable

/**
 * Oboe-backed [ToneGenerator]. One instance owns exactly one native object;
 * always [close] it (or call [stop] then [close]) to avoid leaking the stream.
 */
class NativeToneGenerator : ToneGenerator, Closeable {

    private var handle: Long = nativeCreate()
    private var running = false

    override val isRunning: Boolean get() = running
    override val sampleRate: Int get() = if (handle == 0L) 0 else nativeSampleRate(handle)

    override fun start(): Boolean {
        if (handle == 0L) return false
        if (running) return true
        running = nativeStart(handle)
        if (!running) Log.w(TAG, "Oboe stream could not be opened")
        return running
    }

    override fun stop() {
        if (handle == 0L || !running) return
        nativeStop(handle)
        running = false
    }

    override fun setFrequency(hz: Double) {
        if (handle != 0L) nativeSetFrequency(handle, hz)
    }

    override fun setLevelDbFs(db: Double) {
        if (handle != 0L) nativeSetLevelDbFs(handle, db.coerceAtMost(0.0))
    }

    override fun setEar(ear: ToneEar) {
        if (handle != 0L) nativeSetChannel(handle, ear.nativeValue)
    }

    override fun setToneActive(active: Boolean) {
        if (handle != 0L) nativeSetToneActive(handle, active)
    }

    override fun setRampMs(ms: Double) {
        if (handle != 0L) nativeSetRampMs(handle, ms)
    }

    override fun close() {
        if (handle == 0L) return
        stop()
        nativeDestroy(handle)
        handle = 0L
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeSetFrequency(handle: Long, hz: Double)
    private external fun nativeSetLevelDbFs(handle: Long, db: Double)
    private external fun nativeSetChannel(handle: Long, channel: Int)
    private external fun nativeSetToneActive(handle: Long, active: Boolean)
    private external fun nativeSetRampMs(handle: Long, ms: Double)
    private external fun nativeSampleRate(handle: Long): Int

    companion object {
        private const val TAG = "NativeToneGenerator"

        init {
            System.loadLibrary("btdashboard_audio")
        }
    }
}
