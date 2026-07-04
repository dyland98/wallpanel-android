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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.jjoe64.motiondetection.motiondetection.AggregateLumaMotionDetection
import com.jjoe64.motiondetection.motiondetection.ImageProcessing
import timber.log.Timber
import xyz.wallpanel.app.persistence.Configuration
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

class CameraReader @Inject constructor(private val context: Context) {

    private var cameraCallback: CameraCallback? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var faceDetector: FaceDetector? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var aggregateLumaMotionDetection: AggregateLumaMotionDetection? = null
    private var h264SubstreamEncoder: H264StreamEncoder? = null
    private var h264MainstreamEncoder: H264StreamEncoder? = null
    private var h264FrameSink: ((H264Frame) -> Unit)? = null
    private var activeRtspStreams = emptySet<RtspStream>()
    private val byteArray = MutableLiveData<ByteArray>()
    private val h264Frame = MutableLiveData<H264Frame>()
    private var bitmapComplete = true
    private var byteArrayCreateTask: Future<*>? = null
    private val jpegExecutor = Executors.newSingleThreadExecutor()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bitmapCompleteRunnable = Runnable { bitmapComplete = true }
    private val faceDetectionInFlight = AtomicBoolean(false)
    private val barcodeDetectionInFlight = AtomicBoolean(false)
    private var lastRtspSubstreamFrameMs = 0L
    private var lastRtspMainstreamFrameMs = 0L
    private var lastFaceDetectionMs = 0L
    private var currentLifecycleOwner: LifecycleOwner? = null
    private var currentConfiguration: Configuration? = null
    private var currentPreviewView: PreviewView? = null
    private var screenOnProvider: (() -> Boolean)? = null

    private data class FaceDetectionFrame(val bytes: ByteArray, val width: Int, val height: Int)

    fun getJpeg(): LiveData<ByteArray> {
        return byteArray
    }

    fun getH264Frame(): LiveData<H264Frame> {
        return h264Frame
    }

    fun setH264FrameSink(sink: ((H264Frame) -> Unit)?) {
        h264FrameSink = sink
    }

    fun setScreenOnProvider(provider: (() -> Boolean)?) {
        screenOnProvider = provider
    }

    private fun setJpeg(value: ByteArray) {
        byteArray.value = value
    }

    private fun setH264Frame(value: H264Frame) {
        val sink = h264FrameSink
        if (sink != null) {
            sink(value)
        } else {
            h264Frame.postValue(value)
        }
    }

    fun stopCamera() {
        cameraCallback = null
        mainHandler.removeCallbacks(bitmapCompleteRunnable)

        byteArrayCreateTask?.cancel(true)
        byteArrayCreateTask = null

        cameraProvider?.unbindAll()
        cameraProvider = null

        faceDetector?.close()
        faceDetector = null

        barcodeScanner?.close()
        barcodeScanner = null

        h264SubstreamEncoder?.stop()
        h264SubstreamEncoder = null

        h264MainstreamEncoder?.stop()
        h264MainstreamEncoder = null
        activeRtspStreams = emptySet()
        currentLifecycleOwner = null
        currentConfiguration = null
        currentPreviewView = null

        aggregateLumaMotionDetection = null
        faceDetectionInFlight.set(false)
        barcodeDetectionInFlight.set(false)
        lastFaceDetectionMs = 0L
    }

    fun setActiveRtspStreams(streams: Set<RtspStream>) {
        val wasUsingSurfaceMainstream = useSurfaceMainstream(currentConfiguration, currentPreviewView)
        activeRtspStreams = streams
        val shouldUseSurfaceMainstream = useSurfaceMainstream(currentConfiguration, currentPreviewView)
        if (wasUsingSurfaceMainstream != shouldUseSurfaceMainstream) {
            rebindCurrentCamera()
        }
    }

    @SuppressLint("MissingPermission")
    fun startCamera(lifecycleOwner: LifecycleOwner, callback: CameraCallback, configuration: Configuration) {
        Timber.d("startCamera")
        if (configuration.cameraEnabled) {
            startCameraInternal(lifecycleOwner, callback, configuration, null, detectionsOnly = false)
        }
    }

    @SuppressLint("MissingPermission")
    fun startCameraPreview(
        lifecycleOwner: LifecycleOwner,
        callback: CameraCallback,
        configuration: Configuration,
        preview: PreviewView?
    ) {
        Timber.d("startCameraPreview")
        if (configuration.cameraEnabled && preview != null) {
            startCameraInternal(lifecycleOwner, callback, configuration, preview, detectionsOnly = false)
        }
    }

    @SuppressLint("MissingPermission")
    fun startCameraPreviewSolo(
        lifecycleOwner: LifecycleOwner,
        callback: CameraCallback,
        configuration: Configuration,
        preview: PreviewView?
    ) {
        Timber.d("startCameraPreviewSolo")
        if (configuration.cameraEnabled && preview != null) {
            startCameraInternal(lifecycleOwner, callback, configuration, preview, detectionsOnly = true)
        }
    }

    private fun startCameraInternal(
        lifecycleOwner: LifecycleOwner,
        callback: CameraCallback,
        configuration: Configuration,
        previewView: PreviewView?,
        detectionsOnly: Boolean
    ) {
        stopCamera()
        cameraCallback = callback
        currentLifecycleOwner = lifecycleOwner
        currentConfiguration = configuration
        currentPreviewView = previewView
        buildAnalyzers(configuration, detectionsOnly)

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                bindCamera(provider, lifecycleOwner, configuration, previewView)
            } catch (e: Exception) {
                Timber.e(e, "Unable to start camera")
                cameraCallback?.onCameraError()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("MissingPermission")
    private fun bindCamera(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        configuration: Configuration,
        previewView: PreviewView?
    ) {
        provider.unbindAll()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply {
                if (configuration.rtspEnabled) {
                    setResolutionSelector(
                        rtspResolutionSelector(
                            analysisCaptureWidth(configuration, previewView),
                            analysisCaptureHeight(configuration, previewView)
                        )
                    )
                }
            }
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeFrame(imageProxy, configuration)
                }
            }

        val preview = previewView?.let {
            Preview.Builder().build().also { cameraPreview ->
                cameraPreview.setSurfaceProvider(it.surfaceProvider)
            }
        }
        val rtspMainstreamPreview = buildRtspMainstreamPreview(configuration, previewView)
        val useCases = mutableListOf<UseCase>().apply {
            preview?.let { add(it) }
            rtspMainstreamPreview?.let { add(it) }
            add(analysis)
        }

        val requestedSelector = cameraSelectorFor(configuration.cameraId)
        val fallbackSelector = fallbackCameraSelectorFor(configuration.cameraId)
        try {
            provider.bindToLifecycle(lifecycleOwner, requestedSelector, *useCases.toTypedArray())
        } catch (e: Exception) {
            Timber.e(e, "Unable to bind requested camera, trying fallback")
            try {
                provider.bindToLifecycle(lifecycleOwner, fallbackSelector, *useCases.toTypedArray())
            } catch (fallbackError: Exception) {
                Timber.e(fallbackError, "Unable to bind fallback camera")
                cameraCallback?.onCameraError()
            }
        }
    }

    private fun buildRtspMainstreamPreview(configuration: Configuration, previewView: PreviewView?): Preview? {
        if (!useSurfaceMainstream(configuration, previewView)) {
            h264MainstreamEncoder?.stop()
            return null
        }

        val width = configuration.rtspMainstreamWidth
        val height = configuration.rtspMainstreamHeight
        val fps = configuration.rtspMainstreamFps
        return Preview.Builder()
            .setResolutionSelector(rtspResolutionSelector(width, height))
            .build()
            .also { cameraPreview ->
                cameraPreview.setSurfaceProvider(cameraExecutor) { request ->
                    try {
                        val surface = h264MainstreamEncoder?.startSurface(width, height, fps)
                        if (surface == null) {
                            request.willNotProvideSurface()
                        } else {
                            request.provideSurface(surface, cameraExecutor) {
                                h264MainstreamEncoder?.stop()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Unable to provide RTSP mainstream encoder surface")
                        request.willNotProvideSurface()
                    }
                }
            }
    }

    private fun rebindCurrentCamera() {
        mainHandler.post {
            val provider = cameraProvider ?: return@post
            val lifecycleOwner = currentLifecycleOwner ?: return@post
            val configuration = currentConfiguration ?: return@post
            bindCamera(provider, lifecycleOwner, configuration, currentPreviewView)
        }
    }

    private fun useSurfaceMainstream(configuration: Configuration?, previewView: PreviewView?): Boolean {
        return configuration?.rtspEnabled == true &&
            previewView == null &&
            activeRtspStreams.contains(RtspStream.MAIN)
    }

    private fun buildAnalyzers(configuration: Configuration, detectionsOnly: Boolean) {
        if (!detectionsOnly && configuration.httpMJPEGEnabled) {
            bitmapComplete = true
        }

        if (!detectionsOnly && configuration.rtspEnabled) {
            h264SubstreamEncoder = H264StreamEncoder(RtspStream.SUB) { frame ->
                setH264Frame(frame)
            }
            h264MainstreamEncoder = H264StreamEncoder(RtspStream.MAIN) { frame ->
                setH264Frame(frame)
            }
        }

        if (!detectionsOnly && configuration.cameraMotionEnabled) {
            aggregateLumaMotionDetection = AggregateLumaMotionDetection().apply {
                setLeniency(configuration.cameraMotionLeniency)
            }
        }

        if (!detectionsOnly && configuration.cameraFaceEnabled) {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
            faceDetector = FaceDetection.getClient(options)
        }

        if (!detectionsOnly && configuration.cameraQRCodeEnabled) {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            barcodeScanner = BarcodeScanning.getClient(options)
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy, configuration: Configuration) {
        val width = imageProxy.width
        val height = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val nowMs = System.currentTimeMillis()

        if (!shouldReadFrame(configuration, nowMs)) {
            imageProxy.close()
            return
        }

        val nv21 = try {
            imageProxy.toNv21()
        } catch (e: Exception) {
            Timber.e(e, "Unable to read camera frame")
            null
        } finally {
            imageProxy.close()
        } ?: return

        handleMjpegStreaming(nv21, width, height, rotationDegrees, configuration)
        handleRtspStreaming(nv21, width, height, configuration)
        handleMotion(nv21, width, height, configuration)
        handleFaceDetection(nv21, width, height, rotationDegrees, configuration)
        handleBarcodeDetection(nv21, width, height, rotationDegrees, configuration)
    }

    private fun shouldReadFrame(configuration: Configuration, nowMs: Long): Boolean {
        return shouldReadMjpegFrame(configuration)
            || shouldReadRtspFrame(configuration, nowMs)
            || configuration.cameraMotionEnabled
            || shouldReadFaceDetectionFrame(configuration, nowMs)
            || shouldReadBarcodeFrame(configuration)
    }

    private fun shouldReadMjpegFrame(configuration: Configuration): Boolean {
        return configuration.httpMJPEGEnabled && bitmapComplete
    }

    private fun shouldReadRtspFrame(configuration: Configuration, nowMs: Long): Boolean {
        if (!configuration.rtspEnabled) {
            return false
        }
        val mainNeedsNv21 = !useSurfaceMainstream(configuration, currentPreviewView)
        return isRtspStreamDue(RtspStream.SUB, configuration.rtspSubstreamFps, lastRtspSubstreamFrameMs, nowMs)
            || (mainNeedsNv21 && isRtspStreamDue(RtspStream.MAIN, configuration.rtspMainstreamFps, lastRtspMainstreamFrameMs, nowMs))
    }

    private fun isRtspStreamDue(stream: RtspStream, fps: Int, lastFrameMs: Long, nowMs: Long): Boolean {
        if (!activeRtspStreams.contains(stream)) {
            return false
        }
        val frameIntervalMs = 1000L / fps.coerceAtLeast(1)
        return nowMs - lastFrameMs >= frameIntervalMs
    }

    private fun shouldReadFaceDetectionFrame(configuration: Configuration, nowMs: Long): Boolean {
        return configuration.cameraFaceEnabled
            && !faceDetectionInFlight.get()
            && nowMs - lastFaceDetectionMs >= faceDetectionIntervalMs()
    }

    private fun shouldReadBarcodeFrame(configuration: Configuration): Boolean {
        return configuration.cameraQRCodeEnabled && !barcodeDetectionInFlight.get()
    }

    private fun handleMjpegStreaming(
        frameBytes: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        configuration: Configuration
    ) {
        if (!configuration.httpMJPEGEnabled || !bitmapComplete) {
            return
        }

        bitmapComplete = false
        byteArrayCreateTask = jpegExecutor.submit {
            val jpeg = createJpeg(frameBytes, width, height, rotationDegrees, configuration.cameraRotate)
            mainHandler.post {
                jpeg?.let { setJpeg(it) }
                scheduleBitmapComplete(configuration.cameraFPS)
            }
        }
    }

    private fun handleRtspStreaming(
        frameBytes: ByteArray,
        width: Int,
        height: Int,
        configuration: Configuration
    ) {
        if (!configuration.rtspEnabled) {
            return
        }
        handleRtspStream(
            encoder = h264SubstreamEncoder,
            stream = RtspStream.SUB,
            frameBytes = frameBytes,
            sourceWidth = width,
            sourceHeight = height,
            targetWidth = configuration.rtspSubstreamWidth,
            targetHeight = configuration.rtspSubstreamHeight,
            fps = configuration.rtspSubstreamFps,
            configuration = configuration,
            lastFrameMs = lastRtspSubstreamFrameMs
        ) { lastRtspSubstreamFrameMs = it }
        handleRtspStream(
            encoder = h264MainstreamEncoder,
            stream = RtspStream.MAIN,
            frameBytes = frameBytes,
            sourceWidth = width,
            sourceHeight = height,
            targetWidth = configuration.rtspMainstreamWidth,
            targetHeight = configuration.rtspMainstreamHeight,
            fps = configuration.rtspMainstreamFps,
            configuration = configuration,
            lastFrameMs = lastRtspMainstreamFrameMs
        ) { lastRtspMainstreamFrameMs = it }
    }

    private fun handleRtspStream(
        encoder: H264StreamEncoder?,
        stream: RtspStream,
        frameBytes: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        fps: Int,
        configuration: Configuration,
        lastFrameMs: Long,
        setLastFrameMs: (Long) -> Unit
    ) {
        if (encoder == null || !activeRtspStreams.contains(stream)) {
            return
        }
        if (stream == RtspStream.MAIN && useSurfaceMainstream(configuration, currentPreviewView)) {
            return
        }

        val nowMs = System.currentTimeMillis()
        val frameIntervalMs = 1000L / fps.coerceAtLeast(1)
        if (nowMs - lastFrameMs < frameIntervalMs) {
            return
        }
        setLastFrameMs(nowMs)

        val output = if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
            frameBytes
        } else {
            resizeNv21(frameBytes, sourceWidth, sourceHeight, targetWidth, targetHeight)
        }
        encoder.submitNv21(output, targetWidth, targetHeight, fps, System.nanoTime() / 1000L)
    }

    private fun handleMotion(frameBytes: ByteArray, width: Int, height: Int, configuration: Configuration) {
        val detector = aggregateLumaMotionDetection ?: return
        if (!configuration.cameraMotionEnabled) {
            return
        }

        val luma = ImageProcessing.decodeYUV420SPtoLuma(frameBytes, width, height)
        var lumaSum = 0
        for (value in luma) {
            lumaSum += value
        }

        if (lumaSum < configuration.cameraMotionMinLuma) {
            mainHandler.post { cameraCallback?.onTooDark() }
            return
        }

        try {
            if (detector.detect(luma, width, height)) {
                mainHandler.post { cameraCallback?.onMotionDetected() }
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to run motion detector")
        }
    }

    private fun handleFaceDetection(
        frameBytes: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        configuration: Configuration
    ) {
        val detector = faceDetector ?: return
        if (!configuration.cameraFaceEnabled) {
            return
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFaceDetectionMs < faceDetectionIntervalMs()) {
            return
        }

        if (!faceDetectionInFlight.compareAndSet(false, true)) {
            return
        }
        lastFaceDetectionMs = nowMs

        val faceFrame = resizedFaceDetectionFrame(frameBytes, width, height)
        val image = InputImage.fromByteArray(
            faceFrame.bytes,
            faceFrame.width,
            faceFrame.height,
            rotationDegrees,
            InputImage.IMAGE_FORMAT_NV21
        )
        detector.process(image)
            .addOnSuccessListener { faces ->
                val matchingFace = faces.firstOrNull { face ->
                    val requiredFaceSize = (configuration.cameraFaceSize * 0.6f).coerceAtLeast(8f)
                    val faceSize = face.boundingBox.width().toFloat() / faceFrame.width * 100 > requiredFaceSize
                    val faceRotation = if (configuration.cameraFaceRotation) {
                        face.headEulerAngleY > -12 && face.headEulerAngleY < 12
                    } else {
                        true
                    }
                    faceSize && faceRotation
                }
                if (matchingFace != null && configuration.cameraFaceEnabled) {
                    Timber.d("faceDetected")
                    cameraCallback?.onFaceDetected()
                }
            }
            .addOnFailureListener { e ->
                Timber.e(e, "Unable to run face detector")
                cameraCallback?.onDetectorError()
            }
            .addOnCompleteListener {
                faceDetectionInFlight.set(false)
            }
    }

    private fun resizedFaceDetectionFrame(frameBytes: ByteArray, width: Int, height: Int): FaceDetectionFrame {
        if (width <= FACE_DETECTION_MAX_WIDTH) {
            return FaceDetectionFrame(frameBytes, width, height)
        }

        val targetWidth = FACE_DETECTION_MAX_WIDTH
        val targetHeight = (targetWidth * height / width).coerceAtLeast(2).let { it - (it % 2) }
        return FaceDetectionFrame(
            resizeNv21(frameBytes, width, height, targetWidth, targetHeight),
            targetWidth,
            targetHeight
        )
    }

    private fun faceDetectionIntervalMs(): Long {
        return if (screenOnProvider?.invoke() == true) {
            FACE_DETECTION_SCREEN_ON_INTERVAL_MS
        } else {
            FACE_DETECTION_SCREEN_OFF_INTERVAL_MS
        }
    }

    private fun handleBarcodeDetection(
        frameBytes: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        configuration: Configuration
    ) {
        val scanner = barcodeScanner ?: return
        if (!configuration.cameraQRCodeEnabled || !barcodeDetectionInFlight.compareAndSet(false, true)) {
            return
        }

        val image = InputImage.fromByteArray(
            frameBytes,
            width,
            height,
            rotationDegrees,
            InputImage.IMAGE_FORMAT_NV21
        )
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull { it.displayValue != null }?.displayValue?.let { value ->
                    if (configuration.cameraQRCodeEnabled) {
                        Timber.d("Barcode: $value")
                        cameraCallback?.onQRCode(value)
                    }
                }
            }
            .addOnFailureListener { e ->
                Timber.e(e, "Unable to run barcode scanner")
                cameraCallback?.onDetectorError()
            }
            .addOnCompleteListener {
                barcodeDetectionInFlight.set(false)
            }
    }

    private fun ImageProxy.toNv21(): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        unpackPlane(planes[0], width, height, nv21, 0, 1)
        unpackPlane(planes[2], width / 2, height / 2, nv21, ySize, 2)
        unpackPlane(planes[1], width / 2, height / 2, nv21, ySize + 1, 2)

        return nv21
    }

    private fun unpackPlane(
        plane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        output: ByteArray,
        offset: Int,
        pixelStride: Int
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val planePixelStride = plane.pixelStride
        val row = ByteArray(rowStride)
        var outputOffset = offset

        for (y in 0 until height) {
            val bytesToRead = if (y == height - 1) {
                buffer.remaining()
            } else {
                rowStride.coerceAtMost(buffer.remaining())
            }
            buffer.get(row, 0, bytesToRead)
            for (x in 0 until width) {
                output[outputOffset] = row[x * planePixelStride]
                outputOffset += pixelStride
            }
        }
    }

    private fun cameraSelectorFor(cameraId: Int): CameraSelector {
        return if (cameraId == CAMERA_FACING_FRONT) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    private fun fallbackCameraSelectorFor(cameraId: Int): CameraSelector {
        return if (cameraId == CAMERA_FACING_FRONT) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }

    private fun rtspResolutionSelector(width: Int, height: Int): ResolutionSelector {
        val size = Size(width, height)
        val aspectRatioStrategy = if (isSixteenByNine(size)) {
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        } else {
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }
        return ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatioStrategy)
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
    }

    private fun isSixteenByNine(size: Size): Boolean {
        return size.width * 9 == size.height * 16
    }

    private fun rtspCaptureWidth(configuration: Configuration): Int {
        return maxOf(configuration.rtspSubstreamWidth, configuration.rtspMainstreamWidth)
    }

    private fun rtspCaptureHeight(configuration: Configuration): Int {
        return maxOf(configuration.rtspSubstreamHeight, configuration.rtspMainstreamHeight)
    }

    private fun analysisCaptureWidth(configuration: Configuration, previewView: PreviewView?): Int {
        if (configuration.httpMJPEGEnabled || !useSurfaceMainstream(configuration, previewView)) {
            return rtspCaptureWidth(configuration)
        }
        return maxOf(configuration.rtspSubstreamWidth, FACE_DETECTION_MAX_WIDTH)
    }

    private fun analysisCaptureHeight(configuration: Configuration, previewView: PreviewView?): Int {
        if (configuration.httpMJPEGEnabled || !useSurfaceMainstream(configuration, previewView)) {
            return rtspCaptureHeight(configuration)
        }
        val width = analysisCaptureWidth(configuration, previewView)
        val height = width * configuration.rtspSubstreamHeight / configuration.rtspSubstreamWidth.coerceAtLeast(1)
        return height.coerceAtLeast(2).let { it - (it % 2) }
    }

    private fun resizeNv21(source: ByteArray, sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): ByteArray {
        val targetYSize = targetWidth * targetHeight
        val target = ByteArray(targetYSize + targetYSize / 2)

        for (y in 0 until targetHeight) {
            val sourceY = y * sourceHeight / targetHeight
            for (x in 0 until targetWidth) {
                val sourceX = x * sourceWidth / targetWidth
                target[y * targetWidth + x] = source[sourceY * sourceWidth + sourceX]
            }
        }

        val sourceUvOffset = sourceWidth * sourceHeight
        val targetUvOffset = targetYSize
        for (y in 0 until targetHeight / 2) {
            val sourceY = y * sourceHeight / targetHeight
            for (x in 0 until targetWidth / 2) {
                val sourceX = x * sourceWidth / targetWidth
                val sourceIndex = sourceUvOffset + sourceY * sourceWidth + sourceX * 2
                val targetIndex = targetUvOffset + y * targetWidth + x * 2
                target[targetIndex] = source[sourceIndex]
                target[targetIndex + 1] = source[sourceIndex + 1]
            }
        }

        return target
    }

    private fun scheduleBitmapComplete(cameraFps: Float) {
        // For slower FPS settings we lower the rate at which we generate the bitmap to save CPU power.
        when {
            cameraFps <= 5 -> mainHandler.postDelayed(bitmapCompleteRunnable, DELAY_5_FPS)
            cameraFps <= 10 -> mainHandler.postDelayed(bitmapCompleteRunnable, DELAY_10_FPS)
            cameraFps <= 15 -> mainHandler.postDelayed(bitmapCompleteRunnable, DELAY_15_FPS)
            cameraFps <= 20 -> mainHandler.postDelayed(bitmapCompleteRunnable, DELAY_20_FPS)
            else -> bitmapComplete = true
        }
    }

    private fun createJpeg(
        yuvByteArray: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        rotation: Float
    ): ByteArray? {
        val nv21Bitmap = nv21ToBitmap(yuvByteArray, width, height) ?: return null
        val matrix = Matrix()
        matrix.postRotate((rotationDegrees + rotation.toInt()).toFloat())
        val bitmap = Bitmap.createBitmap(nv21Bitmap, 0, 0, width, height, matrix, true)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val byteArrayOut = stream.toByteArray()
        bitmap.recycle()
        nv21Bitmap.recycle()

        return byteArrayOut
    }

    private fun nv21ToBitmap(yuvByteArray: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val image = YuvImage(yuvByteArray, ImageFormat.NV21, width, height, null)
            val stream = ByteArrayOutputStream()
            image.compressToJpeg(Rect(0, 0, width, height), 80, stream)
            val jpeg = stream.toByteArray()
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            Timber.e(e, "Unable to convert camera frame to bitmap")
            null
        }
    }

    companion object {
        private const val CAMERA_FACING_BACK = 0
        private const val CAMERA_FACING_FRONT = 1
        const val DELAY_20_FPS = (100 * 2).toLong() // 200 milliseconds
        const val DELAY_15_FPS = (100 * 3).toLong() // 300 milliseconds
        const val DELAY_10_FPS = (100 * 4).toLong() // 400 milliseconds
        const val DELAY_5_FPS = (100 * 5).toLong() // 500 milliseconds
        const val FACE_DETECTION_SCREEN_OFF_INTERVAL_MS = 500L
        const val FACE_DETECTION_SCREEN_ON_INTERVAL_MS = 30_000L
        const val FACE_DETECTION_MAX_WIDTH = 1280
    }
}
