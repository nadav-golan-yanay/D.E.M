package com.dem.telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.random.Random

internal class PhoneCameraRtpStreamer(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val logTag: String,
) : Closeable {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var socket: DatagramSocket? = null

    private var sequence = Random.nextInt(0, 65535)
    private var timestamp = Random.nextInt()
    private val ssrc = Random.nextInt()
    private val payloadType = 96
    private val mtuPayload = 1200
    private val frameRate = 30
    private val frameTimestampIncrement = 90000 / frameRate

    suspend fun run() = coroutineScope {
        ensureCameraPermission()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = pickRearCamera(cameraManager)
            ?: error("No rear camera available")

        val destinationAddress = InetAddress.getByName(host)
        socket = DatagramSocket()

        cameraThread = HandlerThread("dem-camera").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val encoder = createEncoder()
        codec = encoder
        inputSurface = encoder.createInputSurface()
        encoder.start()

        val device = openCamera(cameraManager, cameraId, cameraHandler!!)
        cameraDevice = device

        val session = createCaptureSession(device, inputSurface!!, cameraHandler!!)
        captureSession = session

        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(inputSurface!!)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(frameRate, frameRate))
        }.build()
        session.setRepeatingRequest(request, null, cameraHandler)

        AppLogStore.info(logTag, "Camera RTP stream active -> $host:$port")

        try {
            drainEncoder(encoder, destinationAddress)
        } catch (_: CancellationException) {
            // Expected on shutdown.
        }
    }

    private fun createEncoder(): MediaCodec {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return encoder
    }

    private suspend fun drainEncoder(encoder: MediaCodec, destinationAddress: InetAddress) {
        val info = MediaCodec.BufferInfo()
        var cachedCodecConfig: ByteArray? = null

        while (coroutineContext.isActive) {
            val outputIndex = withContext(Dispatchers.IO) { encoder.dequeueOutputBuffer(info, 10_000) }
            if (outputIndex < 0) {
                continue
            }

            val outputBuffer = encoder.getOutputBuffer(outputIndex)
            if (outputBuffer != null && info.size > 0) {
                outputBuffer.position(info.offset)
                outputBuffer.limit(info.offset + info.size)
                val chunk = ByteArray(info.size)
                outputBuffer.get(chunk)

                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (isConfig) {
                    cachedCodecConfig = chunk
                } else {
                    if (isKeyFrame && cachedCodecConfig != null) {
                        packetizeAndSend(cachedCodecConfig, destinationAddress, marker = false)
                    }
                    packetizeAndSend(chunk, destinationAddress, marker = true)
                    timestamp += frameTimestampIncrement
                }
            }

            encoder.releaseOutputBuffer(outputIndex, false)
        }
    }

    private fun packetizeAndSend(data: ByteArray, destinationAddress: InetAddress, marker: Boolean) {
        val nals = splitAnnexB(data)
        if (nals.isEmpty()) {
            sendSingleNal(data, destinationAddress, marker)
            return
        }

        nals.forEachIndexed { index, nal ->
            val nalMarker = marker && index == nals.lastIndex
            sendSingleNal(nal, destinationAddress, nalMarker)
        }
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i <= data.size - 4) {
            val isThree = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val isFour = isThree && data[i + 3] == 1.toByte()
            if (isFour) {
                starts.add(i)
                i += 4
            } else if (isThree) {
                starts.add(i)
                i += 3
            } else {
                i += 1
            }
        }

        if (starts.isEmpty()) {
            return emptyList()
        }

        val nals = mutableListOf<ByteArray>()
        for (index in starts.indices) {
            val start = starts[index]
            val prefix = if (start + 3 < data.size && data[start + 3] == 1.toByte()) 4 else 3
            val nalStart = start + prefix
            val nalEnd = if (index + 1 < starts.size) starts[index + 1] else data.size
            if (nalStart < nalEnd) {
                nals.add(data.copyOfRange(nalStart, nalEnd))
            }
        }
        return nals
    }

    private fun sendSingleNal(nal: ByteArray, destinationAddress: InetAddress, marker: Boolean) {
        if (nal.size <= mtuPayload) {
            sendRtpPayload(nal, destinationAddress, marker)
            return
        }

        val nalHeader = nal[0]
        val fuIndicator = ((nalHeader.toInt() and 0xE0) or 28).toByte()
        val nalType = (nalHeader.toInt() and 0x1F).toByte()
        var offset = 1
        var first = true

        while (offset < nal.size) {
            val chunkSize = minOf(mtuPayload - 2, nal.size - offset)
            val end = offset + chunkSize >= nal.size
            val fuHeader = (
                (if (first) 0x80 else 0x00) or
                    (if (end) 0x40 else 0x00) or
                    nalType.toInt()
                ).toByte()
            val payload = ByteArray(2 + chunkSize)
            payload[0] = fuIndicator
            payload[1] = fuHeader
            System.arraycopy(nal, offset, payload, 2, chunkSize)
            sendRtpPayload(payload, destinationAddress, marker && end)
            offset += chunkSize
            first = false
        }
    }

    private fun sendRtpPayload(payload: ByteArray, destinationAddress: InetAddress, marker: Boolean) {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
        packet[2] = ((sequence shr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 12, payload.size)

        socket?.send(DatagramPacket(packet, packet.size, destinationAddress, port))
        sequence = (sequence + 1) and 0xFFFF
    }

    private suspend fun openCamera(
        cameraManager: CameraManager,
        cameraId: String,
        handler: Handler,
    ): CameraDevice {
        return suspendCancellableCoroutine { continuation ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                continuation.resumeWith(Result.failure(SecurityException("Camera permission missing")))
                return@suspendCancellableCoroutine
            }

            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    continuation.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(IllegalStateException("Camera disconnected")))
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(IllegalStateException("Camera error=$error")))
                    }
                }
            }

            cameraManager.openCamera(cameraId, callback, handler)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun createCaptureSession(
        camera: CameraDevice,
        surface: Surface,
        handler: Handler,
    ): CameraCaptureSession {
        return suspendCancellableCoroutine { continuation ->
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        continuation.resume(session)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        continuation.resumeWith(Result.failure(IllegalStateException("Capture session config failed")))
                    }
                },
                handler,
            )
        }
    }

    private fun pickRearCamera(cameraManager: CameraManager): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            error("Camera permission missing")
        }
    }

    override fun close() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        captureSession = null

        runCatching { cameraDevice?.close() }
        cameraDevice = null

        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null

        runCatching { inputSurface?.release() }
        inputSurface = null

        runCatching { socket?.close() }
        socket = null

        runCatching { cameraThread?.quitSafely() }
        cameraThread = null
        cameraHandler = null
    }
}
