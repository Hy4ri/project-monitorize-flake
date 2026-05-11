package com.example.monitorize

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * H.264 hardware decoder backed by Android MediaCodec (synchronous mode).
 *
 * Usage:
 *  1. Create with target Surface.
 *  2. Call [init] once you know the stream resolution.
 *  3. Feed every NAL unit (Annex-B) to [decode].
 *  4. Call [release] when done.
 */
class H264Decoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var frameCount = 0L
    private var initialized = false

    companion object {
        private const val TAG = "H264Decoder"
        private const val TIMEOUT_US = 10_000L      // 10 ms dequeue timeout
        private const val MAX_INPUT_BYTES = 2 * 1024 * 1024  // 2 MB buffer
    }

    /**
     * Initialise MediaCodec for the given stream dimensions.
     * Safe to call multiple times — re-initialises if already running.
     */
    fun init(width: Int, height: Int) {
        if (initialized) {
            Log.w(TAG, "Already initialized — releasing before re-init")
            release()
        }
        try {
            Log.i(TAG, "Initialising decoder: ${width}×${height}")
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { c ->
                c.configure(format, surface, null, 0)
                c.start()
            }
            frameCount   = 0
            initialized  = true
            Log.i(TAG, "Decoder started OK")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            initialized = false
        }
    }

    /**
     * Feed one NAL unit (or a contiguous block of Annex-B data) to the decoder.
     * Must be called after [init].
     */
    fun decode(data: ByteArray, offset: Int, size: Int) {
        val c = codec ?: return
        if (!initialized) return
        try {
            // ── Feed input ──────────────────────────────────────────────────
            val inputIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                c.getInputBuffer(inputIndex)?.run {
                    clear()
                    put(data, offset, size.coerceAtMost(capacity()))
                }
                val pts = SystemClock.elapsedRealtime() * 1_000L  // µs
                c.queueInputBuffer(inputIndex, 0, size, pts, 0)
            }

            // ── Drain output ────────────────────────────────────────────────
            val info = MediaCodec.BufferInfo()
            var outIndex = c.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                c.releaseOutputBuffer(outIndex, true)   // true = render to Surface
                frameCount++
                if (frameCount % 120 == 0L) {
                    Log.d(TAG, "Decoded $frameCount frames")
                }
                outIndex = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Codec in bad state", e)
        } catch (e: Exception) {
            Log.e(TAG, "Decode error", e)
        }
    }

    fun release() {
        initialized = false
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        Log.i(TAG, "Decoder released. Total frames decoded: $frameCount")
    }
}
