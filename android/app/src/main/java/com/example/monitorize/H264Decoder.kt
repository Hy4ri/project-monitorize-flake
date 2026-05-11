package com.example.monitorize

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * H.264 hardware decoder backed by Android MediaCodec with a dedicated decode thread and queue.
 */
class H264Decoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var frameCount = 0L
    @Volatile
    private var initialized = false
    private val nalQueue = LinkedBlockingQueue<ByteArray>(120)
    private var decodeThread: Thread? = null

    companion object {
        private const val TAG = "H264Decoder"
        private const val TIMEOUT_US = 10_000L
        private const val MAX_INPUT_BYTES = 2 * 1024 * 1024
    }

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
                setInteger(MediaFormat.KEY_OPERATING_RATE, 30)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = real-time
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { c ->
                c.configure(format, surface, null, 0)
                c.start()
            }
            frameCount = 0
            initialized = true
            decodeThread = Thread(::decodeLoop, "MonitorizeDecoder").also { it.start() }
            Log.i(TAG, "Decoder started OK")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            initialized = false
        }
    }

    /**
     * Called by StreamReceiver — enqueues NAL and returns immediately.
     */
    fun enqueue(data: ByteArray, offset: Int, size: Int) {
        if (!initialized) return
        val copy = ByteArray(size)
        System.arraycopy(data, offset, copy, 0, size)
        if (!nalQueue.offer(copy)) {
            Log.w(TAG, "NAL queue full — dropping frame")
        }
    }

    private fun decodeLoop() {
        while (initialized) {
            val nal = try {
                nalQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue

            val c = codec ?: break
            try {
                val inputIndex = c.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    c.getInputBuffer(inputIndex)?.run {
                        clear()
                        put(nal, 0, nal.size.coerceAtMost(capacity()))
                    }
                    val pts = SystemClock.elapsedRealtime() * 1000L
                    c.queueInputBuffer(inputIndex, 0, nal.size, pts, 0)
                }

                val info = MediaCodec.BufferInfo()
                var outIndex = c.dequeueOutputBuffer(info, 0)
                while (outIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            Log.i(TAG, "Format changed: ${c.outputFormat}")
                        outIndex >= 0 -> {
                            c.releaseOutputBuffer(outIndex, true)
                            frameCount++
                            if (frameCount % 120 == 0L)
                                Log.d(TAG, "Decoded $frameCount frames")
                        }
                    }
                    outIndex = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                if (initialized) Log.e(TAG, "Decode loop error", e)
            }
        }
    }

    fun release() {
        initialized = false
        nalQueue.clear()
        try {
            decodeThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        decodeThread = null
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null
        Log.i(TAG, "Decoder released. Total frames: $frameCount")
    }
}
