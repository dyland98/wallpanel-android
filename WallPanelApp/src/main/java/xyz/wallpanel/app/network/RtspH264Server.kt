/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.app.network

import android.util.Base64
import timber.log.Timber
import xyz.wallpanel.app.modules.H264Frame
import xyz.wallpanel.app.modules.RtspStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class RtspH264Server(
    private val port: Int,
    private val activeStreamsChanged: (Set<RtspStream>) -> Unit
) {

    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val frameDispatching = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<RtspClient>()
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var sps = mapOf<RtspStream, ByteArray>()
    @Volatile
    private var pps = mapOf<RtspStream, ByteArray>()

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        acceptExecutor.execute {
            try {
                serverSocket = ServerSocket(port, 10, InetAddress.getByName("0.0.0.0"))
                Timber.i("Started RTSP H264 server on $port")
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    RtspClient(socket).also {
                        clients.add(it)
                        it.start()
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Timber.e(e, "RTSP H264 server stopped unexpectedly")
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }

        clients.forEach { it.close() }
        clients.clear()
        notifyActiveStreamsChanged()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.e(e, "Unable to close RTSP H264 server socket")
        }
        serverSocket = null
        frameExecutor.shutdownNow()
        acceptExecutor.shutdownNow()
    }

    fun submitFrame(frame: H264Frame) {
        if (!running.get()) {
            return
        }
        if (!frameDispatching.compareAndSet(false, true)) {
            return
        }
        try {
            frameExecutor.execute {
                try {
                    dispatchFrame(frame)
                } finally {
                    frameDispatching.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            frameDispatching.set(false)
        }
    }

    private fun dispatchFrame(frame: H264Frame) {
        if (!running.get()) {
            return
        }

        frame.sps?.let { sps = sps + (frame.stream to stripStartCode(it)) }
        frame.pps?.let { pps = pps + (frame.stream to stripStartCode(it)) }

        val nalUnits = splitNalUnits(frame.data)
        if (nalUnits.isEmpty()) {
            return
        }

        clients.forEach { client ->
            if (client.isPlaying && client.selectedStream == frame.stream) {
                client.sendAccessUnit(frame, nalUnits)
            }
        }
    }

    private inner class RtspClient(private val socket: Socket) {
        private val thread = Thread { run() }
        private val sessionId = UUID.randomUUID().toString().replace("-", "").take(12)
        private val outputLock = Any()
        private var output: OutputStream? = null
        @Volatile
        var isPlaying: Boolean = false
            private set
        @Volatile
        var selectedStream: RtspStream = RtspStream.SUB
            private set
        private var sequenceNumber = 0
        private val ssrc = sessionId.hashCode()
        private var sentParameterSets = false
        private var transportMode = TransportMode.TCP
        private var udpSocket: DatagramSocket? = null
        private var clientRtpPort = 0

        fun start() {
            thread.name = "WallPanelRtspH264Client"
            thread.start()
        }

        private fun run() {
            try {
                socket.tcpNoDelay = true
                output = BufferedOutputStream(socket.getOutputStream(), TCP_OUTPUT_BUFFER_SIZE)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (!socket.isClosed) {
                    val requestLine = reader.readLine() ?: break
                    if (requestLine.isBlank()) {
                        continue
                    }
                    val headers = readHeaders(reader)
                    handleRequest(requestLine, headers)
                }
            } catch (e: Exception) {
                Timber.e(e, "RTSP H264 client disconnected")
            } finally {
                close()
            }
        }

        private fun readHeaders(reader: BufferedReader): Map<String, String> {
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) {
                    break
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                        line.substring(separator + 1).trim()
                }
            }
            return headers
        }

        private fun handleRequest(requestLine: String, headers: Map<String, String>) {
            val method = requestLine.substringBefore(' ').uppercase(Locale.US)
            streamFromRequestLine(requestLine)?.let {
                selectedStream = it
            }
            val cSeq = headers["cseq"] ?: "1"
            when (method) {
                "OPTIONS" -> sendResponse(cSeq, extraHeaders = "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER\r\n")
                "DESCRIBE" -> sendDescribe(cSeq)
                "SETUP" -> sendSetup(cSeq, headers["transport"].orEmpty())
                "PLAY" -> {
                    isPlaying = true
                    notifyActiveStreamsChanged()
                    Timber.i("RTSP play ${selectedStream.path} using $transportMode")
                    sendResponse(
                        cSeq,
                        extraHeaders = "Session: $sessionId\r\nRTP-Info: url=rtsp://0.0.0.0:$port/camera/${selectedStream.path}/trackID=0\r\n"
                    )
                }
                "TEARDOWN" -> {
                    sendResponse(cSeq, extraHeaders = "Session: $sessionId\r\n")
                    close()
                }
                "GET_PARAMETER" -> sendResponse(cSeq, extraHeaders = "Session: $sessionId\r\n")
                else -> sendResponse(cSeq, status = "405 Method Not Allowed")
            }
        }

        private fun sendDescribe(cSeq: String) {
            val formatParameters = formatParameters()
            val sdp = buildString {
                append("v=0\r\n")
                append("o=- 0 0 IN IP4 0.0.0.0\r\n")
                append("s=WallPanel Camera\r\n")
                append("t=0 0\r\n")
                append("m=video 0 RTP/AVP 96\r\n")
                append("a=rtpmap:96 H264/90000\r\n")
                append("a=fmtp:96 packetization-mode=1$formatParameters\r\n")
                append("a=control:trackID=0\r\n")
            }
            sendResponse(
                cSeq,
                extraHeaders = "Content-Type: application/sdp\r\nContent-Length: ${sdp.toByteArray().size}\r\n",
                body = sdp
            )
        }

        private fun formatParameters(): String {
            val localSps = sps[selectedStream]
            val localPps = pps[selectedStream]
            return if (localSps != null && localPps != null) {
                val spsText = Base64.encodeToString(localSps, Base64.NO_WRAP)
                val ppsText = Base64.encodeToString(localPps, Base64.NO_WRAP)
                ";sprop-parameter-sets=$spsText,$ppsText"
            } else {
                ""
            }
        }

        private fun sendSetup(cSeq: String, transport: String) {
            if (transport.contains("RTP/AVP/TCP", ignoreCase = true)) {
                transportMode = TransportMode.TCP
                sentParameterSets = false
                Timber.i("RTSP setup ${selectedStream.path} over TCP")
                sendResponse(
                    cSeq,
                    extraHeaders = "Transport: RTP/AVP/TCP;unicast;interleaved=0-1;ssrc=${ssrcHex()}\r\nSession: $sessionId\r\n"
                )
                return
            }

            val clientPort = CLIENT_PORT_PATTERN.find(transport)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (transport.contains("RTP/AVP", ignoreCase = true) && clientPort != null) {
                udpSocket?.close()
                udpSocket = DatagramSocket()
                clientRtpPort = clientPort
                transportMode = TransportMode.UDP
                sentParameterSets = false
                Timber.i("RTSP setup ${selectedStream.path} over UDP clientPort=$clientPort")
                sendResponse(
                    cSeq,
                    extraHeaders = "Transport: RTP/AVP;unicast;client_port=$clientPort-${clientPort + 1};server_port=${udpSocket?.localPort ?: 0}-${(udpSocket?.localPort ?: 0) + 1};ssrc=${ssrcHex()}\r\nSession: $sessionId\r\n"
                )
                return
            }

            sendResponse(cSeq, status = "461 Unsupported Transport")
        }

        private fun sendResponse(cSeq: String, status: String = "200 OK", extraHeaders: String = "", body: String = "") {
            val response = buildString {
                append("RTSP/1.0 $status\r\n")
                append("CSeq: $cSeq\r\n")
                append(extraHeaders)
                append("\r\n")
                append(body)
            }.toByteArray()
            synchronized(outputLock) {
                output?.write(response)
                output?.flush()
            }
        }

        fun sendAccessUnit(frame: H264Frame, nalUnits: List<NalUnit>) {
            try {
                val timestamp = (frame.timestampUs * 90L / 1000L).toInt()
                val streamSps = sps[selectedStream]
                val streamPps = pps[selectedStream]
                if ((frame.isKeyFrame || !sentParameterSets) && streamSps != null && streamPps != null) {
                    sendNalUnit(NalUnit(streamSps, 0, streamSps.size), timestamp, marker = false, flush = false)
                    sendNalUnit(NalUnit(streamPps, 0, streamPps.size), timestamp, marker = false, flush = false)
                    sentParameterSets = true
                }
                nalUnits.forEachIndexed { index, nal ->
                    val isLastUnit = index == nalUnits.lastIndex
                    sendNalUnit(nal, timestamp, marker = isLastUnit, flush = isLastUnit)
                }
            } catch (e: Exception) {
                Timber.e(e, "Unable to send RTSP H264 frame")
                close()
            }
        }

        private fun sendNalUnit(nal: NalUnit, timestamp: Int, marker: Boolean, flush: Boolean) {
            if (nal.length <= 0) {
                return
            }

            if (nal.length <= MAX_RTP_PAYLOAD) {
                val header = ByteArray(RTP_HEADER_SIZE)
                writeRtpHeader(header, timestamp, marker)
                writePacket(header, nal.data, nal.offset, nal.length, flush)
                sequenceNumber = (sequenceNumber + 1) and 0xffff
                return
            }

            val nalHeader = nal.data[nal.offset].toInt() and 0xff
            val fuIndicator = (nalHeader and 0xe0) or 28
            val nalType = nalHeader and 0x1f
            var offset = 1
            while (offset < nal.length) {
                val remaining = nal.length - offset
                val chunkSize = min(MAX_RTP_PAYLOAD - FU_A_HEADER_SIZE, remaining)
                val isStart = offset == 1
                val isEnd = offset + chunkSize >= nal.length
                val header = ByteArray(RTP_HEADER_SIZE + FU_A_HEADER_SIZE)
                writeRtpHeader(header, timestamp, marker && isEnd)
                header[RTP_HEADER_SIZE] = fuIndicator.toByte()
                header[RTP_HEADER_SIZE + 1] = ((if (isStart) 0x80 else 0) or (if (isEnd) 0x40 else 0) or nalType).toByte()
                writePacket(header, nal.data, nal.offset + offset, chunkSize, flush && isEnd)
                sequenceNumber = (sequenceNumber + 1) and 0xffff
                offset += chunkSize
            }
        }

        private fun writeRtpHeader(packet: ByteArray, timestamp: Int, marker: Boolean) {
            packet[0] = 0x80.toByte()
            packet[1] = ((if (marker) 0x80 else 0) or RTP_PAYLOAD_TYPE_H264).toByte()
            packet[2] = (sequenceNumber shr 8).toByte()
            packet[3] = sequenceNumber.toByte()
            packet[4] = (timestamp shr 24).toByte()
            packet[5] = (timestamp shr 16).toByte()
            packet[6] = (timestamp shr 8).toByte()
            packet[7] = timestamp.toByte()
            packet[8] = (ssrc shr 24).toByte()
            packet[9] = (ssrc shr 16).toByte()
            packet[10] = (ssrc shr 8).toByte()
            packet[11] = ssrc.toByte()
        }

        private fun writePacket(header: ByteArray, payload: ByteArray, payloadOffset: Int, payloadLength: Int, flush: Boolean) {
            if (transportMode == TransportMode.UDP) {
                val datagramSocket = udpSocket ?: return
                val packet = ByteArray(header.size + payloadLength)
                System.arraycopy(header, 0, packet, 0, header.size)
                System.arraycopy(payload, payloadOffset, packet, header.size, payloadLength)
                datagramSocket.send(DatagramPacket(packet, packet.size, socket.inetAddress, clientRtpPort))
                return
            }

            val packetSize = header.size + payloadLength
            synchronized(outputLock) {
                output?.write('$'.code)
                output?.write(0)
                output?.write(packetSize shr 8)
                output?.write(packetSize)
                output?.write(header)
                output?.write(payload, payloadOffset, payloadLength)
                if (flush) {
                    output?.flush()
                }
            }
        }

        private fun ssrcHex(): String {
            return "%08x".format(ssrc)
        }

        fun close() {
            isPlaying = false
            clients.remove(this)
            notifyActiveStreamsChanged()
            udpSocket?.close()
            udpSocket = null
            try {
                socket.close()
            } catch (e: Exception) {
                Timber.e(e, "Unable to close RTSP H264 client socket")
            }
        }
    }

    private fun streamFromRequestLine(requestLine: String): RtspStream? {
        val target = requestLine.split(' ').getOrNull(1).orEmpty().lowercase(Locale.US)
        return when {
            target.contains("/mainstream") -> RtspStream.MAIN
            target.contains("/substream") || target.endsWith("/camera") -> RtspStream.SUB
            else -> null
        }
    }

    private fun notifyActiveStreamsChanged() {
        activeStreamsChanged(clients.filter { it.isPlaying }.map { it.selectedStream }.toSet())
    }

    private fun splitNalUnits(data: ByteArray): List<NalUnit> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < data.size - 3) {
            val startCodeLength = startCodeLengthAt(data, i)
            if (startCodeLength > 0) {
                starts.add(i to startCodeLength)
                i += startCodeLength
            } else {
                i++
            }
        }
        if (starts.isNotEmpty()) {
            return starts.mapIndexed { index, start ->
                val nalStart = start.first + start.second
                val nalEnd = starts.getOrNull(index + 1)?.first ?: data.size
                NalUnit(data, nalStart, nalEnd - nalStart)
            }.filter { it.length > 0 }
        }

        val lengthPrefixed = splitLengthPrefixedNalUnits(data)
        return lengthPrefixed.ifEmpty { listOf(NalUnit(data, 0, data.size)) }
    }

    private fun splitLengthPrefixedNalUnits(data: ByteArray): List<NalUnit> {
        val units = mutableListOf<NalUnit>()
        var offset = 0
        while (offset + 4 <= data.size) {
            val length = ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)
            if (length <= 0 || offset + 4 + length > data.size) {
                return emptyList()
            }
            units.add(NalUnit(data, offset + 4, length))
            offset += 4 + length
        }
        return if (offset == data.size) units else emptyList()
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        val startCodeLength = startCodeLengthAt(data, 0)
        return if (startCodeLength > 0) data.copyOfRange(startCodeLength, data.size) else data
    }

    private fun startCodeLengthAt(data: ByteArray, index: Int): Int {
        return when {
            index + 3 <= data.size &&
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte() -> 3
            index + 4 <= data.size &&
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() &&
                data[index + 3] == 1.toByte() -> 4
            else -> 0
        }
    }

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val FU_A_HEADER_SIZE = 2
        private const val RTP_PAYLOAD_TYPE_H264 = 96
        private const val MAX_RTP_PAYLOAD = 1200
        private const val TCP_OUTPUT_BUFFER_SIZE = 64 * 1024
        private val CLIENT_PORT_PATTERN = Regex("""client_port=(\d+)(?:-\d+)?""")
    }

    private enum class TransportMode {
        TCP,
        UDP
    }

    private data class NalUnit(
        val data: ByteArray,
        val offset: Int,
        val length: Int
    )
}
