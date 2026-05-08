package com.example.monitorize

import java.net.ServerSocket
import android.util.Log
import java.io.InputStream
import java.io.ByteArrayOutputStream

class StreamReceiver(private val decoder: H264Decoder) {
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val TAG = "StreamReceiver"
    var onStatusChange: ((String) -> Unit)? = null

    fun start() {
        running = true
        Thread {
            try {
                serverSocket = ServerSocket(7110)
                Log.d(TAG, "Server listening on 7110")
                onStatusChange?.invoke("Ready")
                
                while (running) {
                    val socket = try { 
                        serverSocket?.accept() 
                    } catch (e: Exception) { 
                        null 
                    } ?: break
                    
                    Log.d(TAG, "Connection accepted from ${socket.inetAddress}")
                    onStatusChange?.invoke("Streaming")
                    
                    var totalBytes = 0L
                    try {
                        val input = socket.getInputStream()
                        val networkBuffer = ByteArray(65536)
                        val frameBuffer = ByteArrayOutputStream()
                        
                        decoder.release() 
                        decoder.init(1280, 720)

                        while (running) {
                            val bytesRead = input.read(networkBuffer)
                            if (bytesRead <= 0) break
                            
                            frameBuffer.write(networkBuffer, 0, bytesRead)
                            var data = frameBuffer.toByteArray()
                            
                            var startIndex = findAnnexBStartCode(data, 0)
                            while (startIndex != -1) {
                                val nextStartIndex = findAnnexBStartCode(data, startIndex + 3)
                                if (nextStartIndex != -1) {
                                    // Found a complete NAL unit!
                                    decoder.decode(data, startIndex, nextStartIndex - startIndex)
                                    totalBytes += (nextStartIndex - startIndex)
                                    
                                    // Slice data
                                    val remaining = data.size - nextStartIndex
                                    val newData = ByteArray(remaining)
                                    System.arraycopy(data, nextStartIndex, newData, 0, remaining)
                                    data = newData
                                    startIndex = findAnnexBStartCode(data, 0)
                                } else {
                                    break // Need more data for the next start code
                                }
                            }
                            
                            frameBuffer.reset()
                            frameBuffer.write(data)
                            
                            if (totalBytes > 0 && totalBytes % (1024 * 1024) < bytesRead) {
                                Log.d(TAG, "Sent ${totalBytes / 1024} KB to decoder")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Stream error: ${e.message}")
                    } finally {
                        try { socket.close() } catch (e: Exception) {}
                        decoder.release() 
                        Log.d(TAG, "Finished session. Total bytes: $totalBytes")
                        onStatusChange?.invoke("Ready")
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error: ${e.message}")
            }
        }.start()
    }

    private fun findAnnexBStartCode(data: ByteArray, offset: Int): Int {
        for (i in offset until data.size - 3) {
            // Check for 00 00 00 01
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                return i
            }
            // Check for 00 00 01 (also valid Annex-B)
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte()) {
                return i
            }
        }
        return -1
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }
}
