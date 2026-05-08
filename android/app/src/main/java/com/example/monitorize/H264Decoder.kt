package com.example.monitorize

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import android.util.Log

class H264Decoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private val TAG = "H264Decoder"
    private var frameCount = 0

    fun init(width: Int, height: Int) {
        if (codec != null) return
        try {
            Log.i(TAG, "Sync Init: $width x $height")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec?.configure(format, surface, null, 0)
            codec?.start()
            frameCount = 0
            Log.i(TAG, "Decoder Started Successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Init Failed", e)
        }
    }

    fun decode(data: ByteArray, offset: Int, size: Int) {
        val c = codec ?: return
        try {
            // Wait for input buffer
            var inputIndex = -1
            while (inputIndex < 0) {
                inputIndex = c.dequeueInputBuffer(5000)
                if (codec == null) return
            }
            
            c.getInputBuffer(inputIndex)?.let { buf ->
                buf.clear()
                buf.put(data, offset, size)
                c.queueInputBuffer(inputIndex, 0, size, SystemClock.elapsedRealtime() * 1000, 0)
            }

            // Render all available output buffers
            val info = MediaCodec.BufferInfo()
            var outputIndex = c.dequeueOutputBuffer(info, 0)
            
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.i(TAG, "Decoder format recognized: ${c.outputFormat}")
            }

            while (outputIndex >= 0) {
                c.releaseOutputBuffer(outputIndex, true)
                frameCount++
                if (frameCount % 60 == 0) Log.i(TAG, "Rendered $frameCount frames")
                outputIndex = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode Error", e)
        }
    }

    fun release() {
        Log.i(TAG, "Releasing. Total frames: $frameCount")
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {}
        codec = null
    }
}
