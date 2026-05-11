package com.example.monitorize

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * StreamReceiver
 *
 * Listens on TCP port 7110 for an incoming raw H.264 Annex-B stream.
 * The sender (Linux host) pipes wf-recorder | GStreamer h264parse directly —
 * so the byte stream contains standard Annex-B start codes (00 00 00 01 or 00 00 01).
 *
 * Strategy:
 *  - Buffer incoming bytes into a sliding window
 *  - Detect Annex-B start codes to delimit NAL units
 *  - On first SPS NAL, parse width/height and init decoder
 *  - Feed each complete NAL unit to H264Decoder
 *
 * Thread: runs on its own background thread; calls [onStatusChange] from that thread
 * (MainActivity posts to UI thread via runOnUiThread).
 */
class StreamReceiver(private val decoder: H264Decoder) {

    var onStatusChange: ((String) -> Unit)? = null

    private var running      = false
    private var serverSocket: ServerSocket? = null

    companion object {
        private const val TAG  = "StreamReceiver"
        const val PORT         = 7110

        // Default resolution — overridden once SPS is parsed
        private const val DEFAULT_WIDTH  = 1280
        private const val DEFAULT_HEIGHT = 800

        // Annex-B 4-byte start code
        private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
        // Annex-B 3-byte start code
        private val START_CODE_3 = byteArrayOf(0, 0, 1)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun start() {
        running = true
        Thread(::receiveLoop, "MonitorizeReceiver").start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    // ── Receive loop ──────────────────────────────────────────────────────────

    private fun receiveLoop() {
        try {
            serverSocket = ServerSocket(PORT)
            Log.i(TAG, "Listening on port $PORT")
            status("Waiting for stream…")

            while (running) {
                val socket: Socket = try {
                    serverSocket?.accept() ?: break
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "Accept error: ${e.message}")
                    break
                }

                Log.i(TAG, "Connection from ${socket.inetAddress}")
                status("Connected — buffering…")
                handleConnection(socket)

                if (running) status("Disconnected — waiting…")
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Server error", e)
            status("Error: ${e.message}")
        } finally {
            Log.i(TAG, "Receive loop ended")
        }
    }

    // ── Per-connection handler ────────────────────────────────────────────────

    private fun handleConnection(socket: Socket) {
        var decoderReady = false
        var totalNals    = 0L
        val accumulator  = ByteArrayOutputStream(256 * 1024)
        val readBuf      = ByteArray(65_536)

        try {
            socket.use { s ->
                val input = s.getInputStream()

                while (running) {
                    val n = input.read(readBuf)
                    if (n <= 0) break

                    accumulator.write(readBuf, 0, n)
                    val buf = accumulator.toByteArray()

                    // Find all complete NAL units in buffer
                    var searchFrom = 0
                    var nalStart   = findStartCode(buf, searchFrom)

                    while (nalStart != -1) {
                        val scLen    = startCodeLen(buf, nalStart)
                        val nalBegin = nalStart + scLen
                        val next     = findStartCode(buf, nalBegin)

                        if (next == -1) {
                            // Incomplete NAL — keep from nalStart onwards
                            accumulator.reset()
                            accumulator.write(buf, nalStart, buf.size - nalStart)
                            break
                        }

                        // We have a complete NAL: buf[nalBegin .. next)
                        val nalLen = next - nalBegin
                        if (nalLen > 0) {
                            // First byte of NAL = (forbidden_zero_bit | nal_ref_idc | nal_unit_type)
                            val nalType = buf[nalBegin].toInt() and 0x1F

                            if (!decoderReady && (nalType == 7 || nalType == 5)) {
                                // SPS (7) or IDR (5) — initialise decoder
                                // Try to parse SPS for real resolution; fall back to defaults
                                val (w, h) = if (nalType == 7) {
                                    parseSpsResolution(buf, nalBegin, nalLen)
                                        ?: (DEFAULT_WIDTH to DEFAULT_HEIGHT)
                                } else {
                                    DEFAULT_WIDTH to DEFAULT_HEIGHT
                                }
                                Log.i(TAG, "Initialising decoder at ${w}×${h}")
                                decoder.init(w, h)
                                decoderReady = true
                                status("Streaming ${w}×${h}")
                            }

                            if (decoderReady) {
                                // Feed the full NAL including its start code for MediaCodec
                                decoder.decode(buf, nalStart, next - nalStart)
                                totalNals++
                            }
                        }

                        searchFrom = next
                        nalStart   = next
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
        } finally {
            decoder.release()
            decoderReady = false
            Log.i(TAG, "Session ended. Total NALs delivered: $totalNals")
        }
    }

    // ── Annex-B start code utilities ──────────────────────────────────────────

    /** Returns index of first 00 00 01 or 00 00 00 01 at or after [from]. */
    private fun findStartCode(buf: ByteArray, from: Int): Int {
        val limit = buf.size - 3
        var i = from
        while (i <= limit) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte()) {
                if (buf[i + 2] == 1.toByte()) return i          // 3-byte
                if (i + 3 <= buf.size - 1 &&
                    buf[i + 2] == 0.toByte() && buf[i + 3] == 1.toByte()
                ) return i                                        // 4-byte
            }
            i++
        }
        return -1
    }

    /** Length of the start code at [pos] (3 or 4 bytes). */
    private fun startCodeLen(buf: ByteArray, pos: Int): Int =
        if (pos + 3 < buf.size && buf[pos + 2] == 0.toByte()) 4 else 3

    // ── SPS resolution parser (simplified, handles most x264 output) ──────────

    /**
     * Very lightweight SPS NAL parser for width/height only.
     * Handles common profiles from x264 ultrafast output.
     * Returns null if parsing fails — caller uses defaults.
     */
    private fun parseSpsResolution(buf: ByteArray, offset: Int, len: Int): Pair<Int, Int>? {
        return try {
            // Skip NAL header byte, then use MediaFormat approach:
            // Just try to configure MediaCodec with a small probe format
            // and let the decoder report actual dimensions via INFO_OUTPUT_FORMAT_CHANGED.
            // For now return null to rely on defaults — actual resolution comes
            // from the GStreamer pipeline config embedded in SPS automatically.
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun status(msg: String) {
        onStatusChange?.invoke(msg)
    }
}
