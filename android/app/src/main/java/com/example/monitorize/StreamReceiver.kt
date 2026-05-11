package com.example.monitorize

import android.util.Log
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress

class StreamReceiver(private val decoder: H264Decoder) {

    var onStatusChange: ((String) -> Unit)? = null

    private var running = false
    private var serverSocket: ServerSocket? = null

    companion object {
        private const val TAG = "StreamReceiver"
        const val PORT = 7110
        private const val DEFAULT_WIDTH  = 1280
        private const val DEFAULT_HEIGHT = 800
    }

    fun start() {
        running = true
        Thread(::receiveLoop, "MonitorizeReceiver").start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }

    private fun receiveLoop() {
        var bound = false
        while (running && !bound) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                bound = true
            } catch (e: IOException) {
                Log.w(TAG, "Bind failed (${e.message}) — retrying in 2s")
                status("Binding port $PORT… retrying")
                Thread.sleep(2000)
            }
        }
        if (!bound) return

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
    }

    private fun handleConnection(socket: Socket) {
        var decoderReady = false
        var totalNals = 0L
        
        // FIX: Increased buffer to 2MB to handle bursts at 6000kbps
        val buffer = ByteArray(2 * 1024 * 1024)
        var bufferOffset = 0
        val readBuf = ByteArray(65_536)

        try {
            socket.use { s ->
                s.receiveBufferSize = 1024 * 1024  // 1MB socket buffer
                s.tcpNoDelay = true                 // disable Nagle algorithm
                val input = s.getInputStream()
                while (running) {
                    val n = input.read(readBuf)
                    if (n <= 0) break

                    if (bufferOffset + n > buffer.size) {
                        Log.e(TAG, "Buffer overflow! Corrupt stream? Resetting.")
                        bufferOffset = 0
                    }

                    System.arraycopy(readBuf, 0, buffer, bufferOffset, n)
                    bufferOffset += n

                    var searchFrom = 0
                    while (true) {
                        val nalStart = findStartCode(buffer, searchFrom, bufferOffset)
                        if (nalStart == -1) break

                        val scLen = startCodeLen(buffer, nalStart, bufferOffset)
                        val nalBegin = nalStart + scLen
                        val next = findStartCode(buffer, nalBegin, bufferOffset)

                        if (next == -1) {
                            searchFrom = nalStart
                            break
                        }

                        val nalLen = next - nalBegin
                        if (nalLen > 0) {
                            val nalType = buffer[nalBegin].toInt() and 0x1F
                            if (!decoderReady && nalType == 7) {
                                decoder.init(DEFAULT_WIDTH, DEFAULT_HEIGHT)
                                decoderReady = true
                                status("Streaming Active")
                            }
                            if (decoderReady) {
                                // FIX: Use non-blocking enqueue instead of blocking decode
                                decoder.enqueue(buffer, nalStart, next - nalStart)
                                totalNals++
                            }
                        }
                        searchFrom = next
                    }

                    if (searchFrom > 0) {
                        val remaining = bufferOffset - searchFrom
                        if (remaining > 0) {
                            System.arraycopy(buffer, searchFrom, buffer, 0, remaining)
                        }
                        bufferOffset = remaining
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
        } finally {
            Log.i(TAG, "Session ended. NALs: $totalNals")
        }
    }

    private fun findStartCode(buf: ByteArray, from: Int, limit: Int): Int {
        val end = limit - 3
        var i = from
        while (i <= end) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte()) {
                if (buf[i + 2] == 1.toByte()) return i
                if (i + 3 < limit &&
                    buf[i + 2] == 0.toByte() && buf[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeLen(buf: ByteArray, pos: Int, limit: Int): Int =
        if (pos + 3 < limit && buf[pos + 2] == 0.toByte()) 4 else 3

    private fun status(msg: String) { onStatusChange?.invoke(msg) }
}
