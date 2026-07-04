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

package xyz.wallpanel.app.modules

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class H264StreamEncoder(private val stream: RtspStream, private val frameCallback: (H264Frame) -> Unit) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var width = 0
    private var height = 0
    private var fps = 15
    private var bitrate = 0
    private var colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val drainExecutor = Executors.newSingleThreadExecutor()
    private val surfaceDraining = AtomicBoolean(false)

    fun submitNv21(frame: ByteArray, frameWidth: Int, frameHeight: Int, frameFps: Int, timestampUs: Long) {
        if (inputSurface != null || codec == null || width != frameWidth || height != frameHeight) {
            restart(frameWidth, frameHeight, frameFps)
        }

        val encoder = codec ?: return
        try {
            val inputIndex = encoder.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.putNv21AsEncoderFormat(frame, frameWidth, frameHeight, colorFormat)
                encoder.queueInputBuffer(inputIndex, 0, frameWidth * frameHeight * 3 / 2, timestampUs, 0)
            }
            drainEncoder(encoder)
        } catch (e: Exception) {
            Timber.e(e, "Unable to encode H264 frame")
            stop()
        }
    }

    fun startSurface(frameWidth: Int, frameHeight: Int, frameFps: Int): Surface {
        if (codec == null || inputSurface == null || width != frameWidth || height != frameHeight || fps != frameFps.coerceIn(1, 30)) {
            restartSurface(frameWidth, frameHeight, frameFps)
        }
        return inputSurface ?: throw IllegalStateException("H264 surface encoder was not started")
    }

    fun stop() {
        surfaceDraining.set(false)
        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Timber.e(e, "Unable to release H264 input surface")
        }
        inputSurface = null
        try {
            codec?.stop()
        } catch (e: Exception) {
            Timber.e(e, "Unable to stop H264 encoder")
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Timber.e(e, "Unable to release H264 encoder")
        }
        codec = null
        width = 0
        height = 0
        sps = null
        pps = null
    }

    private fun restart(frameWidth: Int, frameHeight: Int, frameFps: Int) {
        stop()
        width = frameWidth
        height = frameHeight
        fps = frameFps.coerceIn(1, 30)
        bitrate = bitrateFor(width, height, fps)
        colorFormat = selectEncoderColorFormat()

        codec = configureEncoder(buildFormat(useCompatibilityTuning = true))
            ?: configureEncoder(buildFormat(useCompatibilityTuning = false))
            ?: throw IllegalStateException("Unable to configure H264 encoder")
        Timber.i("Started H264 encoder ${width}x$height@$fps bitrate=$bitrate colorFormat=$colorFormat")
    }

    private fun restartSurface(frameWidth: Int, frameHeight: Int, frameFps: Int) {
        stop()
        width = frameWidth
        height = frameHeight
        fps = frameFps.coerceIn(1, 30)
        bitrate = bitrateFor(width, height, fps)
        colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface

        codec = configureSurfaceEncoder(buildFormat(useCompatibilityTuning = true))
            ?: configureSurfaceEncoder(buildFormat(useCompatibilityTuning = false))
            ?: throw IllegalStateException("Unable to configure surface H264 encoder")
        startSurfaceDrainLoop(codec!!)
        Timber.i("Started surface H264 encoder ${width}x$height@$fps bitrate=$bitrate")
    }

    private fun buildFormat(useCompatibilityTuning: Boolean): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)

            if (useCompatibilityTuning) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(KEY_LOW_LATENCY, 1)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(KEY_MAX_B_FRAMES, 0)
                }
            }
        }
    }

    private fun configureEncoder(format: MediaFormat): MediaCodec? {
        val encoder = try {
            MediaCodec.createEncoderByType(MIME_TYPE)
        } catch (e: Exception) {
            Timber.e(e, "Unable to create H264 encoder")
            return null
        }

        return try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoder
        } catch (e: Exception) {
            Timber.e(e, "Unable to configure H264 encoder with $format")
            try {
                encoder.release()
            } catch (releaseError: Exception) {
                Timber.e(releaseError, "Unable to release failed H264 encoder")
            }
            null
        }
    }

    private fun configureSurfaceEncoder(format: MediaFormat): MediaCodec? {
        val encoder = try {
            MediaCodec.createEncoderByType(MIME_TYPE)
        } catch (e: Exception) {
            Timber.e(e, "Unable to create surface H264 encoder")
            return null
        }

        return try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()
            encoder
        } catch (e: Exception) {
            Timber.e(e, "Unable to configure surface H264 encoder with $format")
            try {
                encoder.release()
            } catch (releaseError: Exception) {
                Timber.e(releaseError, "Unable to release failed surface H264 encoder")
            }
            inputSurface = null
            null
        }
    }

    private fun startSurfaceDrainLoop(encoder: MediaCodec) {
        surfaceDraining.set(true)
        drainExecutor.execute {
            while (surfaceDraining.get() && codec === encoder) {
                try {
                    drainEncoder(encoder, DEQUEUE_TIMEOUT_US)
                } catch (e: Exception) {
                    if (surfaceDraining.get()) {
                        Timber.e(e, "Unable to drain surface H264 encoder")
                        stop()
                    }
                }
            }
        }
    }

    private fun drainEncoder(encoder: MediaCodec) {
        drainEncoder(encoder, 0)
    }

    private fun drainEncoder(encoder: MediaCodec, timeoutUs: Long) {
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    readCodecConfig(encoder.outputFormat)
                }
                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.get(data)
                        val config = parseCodecConfig(data)
                        if (config != null) {
                            sps = config.first
                            pps = config.second
                        } else if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            frameCallback(
                                H264Frame(
                                    stream = stream,
                                    data = data,
                                    timestampUs = bufferInfo.presentationTimeUs,
                                    isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                    sps = sps,
                                    pps = pps
                                )
                            )
                        }
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun readCodecConfig(format: MediaFormat) {
        sps = format.getByteBuffer("csd-0")?.toByteArrayWithoutPosition()
        pps = format.getByteBuffer("csd-1")?.toByteArrayWithoutPosition()
    }

    private fun ByteBuffer.putNv21AsEncoderFormat(nv21: ByteArray, width: Int, height: Int, colorFormat: Int) {
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> putNv21AsI420(nv21, width, height)
            else -> putNv21AsNv12(nv21, width, height)
        }
    }

    private fun ByteBuffer.putNv21AsI420(nv21: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        put(nv21, 0, ySize)
        for (i in 0 until ySize / 4) {
            put(nv21[ySize + i * 2 + 1])
        }
        for (i in 0 until ySize / 4) {
            put(nv21[ySize + i * 2])
        }
    }

    private fun ByteBuffer.putNv21AsNv12(nv21: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        put(nv21, 0, ySize)
        for (i in 0 until ySize / 4) {
            put(nv21[ySize + i * 2 + 1])
            put(nv21[ySize + i * 2])
        }
    }

    private fun ByteBuffer.toByteArrayWithoutPosition(): ByteArray {
        val duplicate = duplicate()
        duplicate.position(0)
        val data = ByteArray(duplicate.remaining())
        duplicate.get(data)
        return stripStartCode(data)
    }

    private fun parseCodecConfig(data: ByteArray): Pair<ByteArray, ByteArray>? {
        val units = splitAnnexB(data)
        val foundSps = units.firstOrNull { (it.firstOrNull()?.toInt() ?: 0) and 0x1f == 7 }
        val foundPps = units.firstOrNull { (it.firstOrNull()?.toInt() ?: 0) and 0x1f == 8 }
        return if (foundSps != null && foundPps != null) {
            stripStartCode(foundSps) to stripStartCode(foundPps)
        } else {
            null
        }
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
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
        if (starts.isEmpty()) {
            return emptyList()
        }
        return starts.mapIndexed { index, start ->
            val nalStart = start.first + start.second
            val nalEnd = starts.getOrNull(index + 1)?.first ?: data.size
            data.copyOfRange(nalStart, nalEnd)
        }.filter { it.isNotEmpty() }
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

    private fun bitrateFor(width: Int, height: Int, fps: Int): Int {
        return (width.toLong() * height * fps / 4)
            .coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong())
            .toInt()
    }

    private fun selectEncoderColorFormat(): Int {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val encoder = codecInfos.firstOrNull { codecInfo ->
            codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) }
        }
        val colorFormats = try {
            encoder?.getCapabilitiesForType(MIME_TYPE)?.colorFormats ?: intArrayOf()
        } catch (e: Exception) {
            Timber.e(e, "Unable to read H264 encoder color formats")
            intArrayOf()
        }

        val preferredFormats = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
            COLOR_QCOM_FORMAT_YUV420_SEMIPLANAR,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        return preferredFormats.firstOrNull { colorFormats.contains(it) }
            ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val COLOR_QCOM_FORMAT_YUV420_SEMIPLANAR = 0x7FA30C00
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val MIN_BITRATE = 350_000
        private const val MAX_BITRATE = 4_000_000
        private const val KEY_LOW_LATENCY = "latency"
        private const val KEY_MAX_B_FRAMES = "max-bframes"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
