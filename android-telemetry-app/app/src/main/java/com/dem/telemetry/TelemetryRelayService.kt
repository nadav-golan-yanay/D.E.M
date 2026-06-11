package com.dem.telemetry

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.BindException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TelemetryRelayService : Service() {
    private val logTag = "TelemetryRelay"
    private val expectedPixhawkSysId = 1
    private val expectedAutopilotCompId = 1
    private val expectedArduPilotAutopilotType = 3

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var relayJob: Job? = null
    private var lastConfig: RelayStatus? = null
    private var announcedPixhawkHeartbeat = false
    private var mavlinkSeq = 0
    private val vehicleWriteLock = Any()
    private var gpsInjectionJob: Job? = null
    private var cameraStreamJob: Job? = null
    private val relayLifecycleLock = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogStore.info(logTag, "onStartCommand action=${intent?.action ?: "<null>"}")
        when (intent?.action) {
            ACTION_START -> startRelay(intent)
            ACTION_STOP -> stopRelay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLogStore.info(logTag, "Service destroyed")
        stopRelay(stopService = false)
        super.onDestroy()
    }

    private fun startRelay(intent: Intent) {
        val mode = try {
            RelayMode.valueOf(intent.getStringExtra(EXTRA_RELAY_MODE) ?: RelayMode.TCP_SERVER.name)
        } catch (_: IllegalArgumentException) {
            RelayMode.TCP_SERVER
        }

        val config = RelayStatus(
            relayMode = mode,
            udpInputPort = intent.getIntExtra(EXTRA_UDP_INPUT_PORT, DEFAULT_UDP_PORT),
            udpRemoteHost = intent.getStringExtra(EXTRA_UDP_REMOTE_HOST) ?: DEFAULT_HOST,
            udpRemotePort = intent.getIntExtra(EXTRA_UDP_REMOTE_PORT, DEFAULT_UDP_PORT),
            tcpServerPort = intent.getIntExtra(EXTRA_TCP_SERVER_PORT, DEFAULT_TCP_PORT),
            tcpClientHost = normalizeHostInput(intent.getStringExtra(EXTRA_TCP_CLIENT_HOST) ?: DEFAULT_HOST),
            tcpClientPort = intent.getIntExtra(EXTRA_TCP_CLIENT_PORT, DEFAULT_TCP_PORT),
            phoneGpsInjectionEnabled = intent.getBooleanExtra(EXTRA_PHONE_GPS_INJECTION, true),
            cameraEnabled = intent.getBooleanExtra(EXTRA_CAMERA_ENABLED, false),
            cameraHost = normalizeHostInput(intent.getStringExtra(EXTRA_CAMERA_HOST) ?: DEFAULT_HOST),
            cameraPort = intent.getIntExtra(EXTRA_CAMERA_PORT, DEFAULT_CAMERA_PORT),
            cameraPath = intent.getStringExtra(EXTRA_CAMERA_PATH) ?: DEFAULT_CAMERA_PATH,
        )

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting $mode"))
        AppLogStore.info(logTag, "Starting relay mode=$mode udpInput=${config.udpInputPort}")
        AppLogStore.info(logTag, "Phone GPS injection requested=${config.phoneGpsInjectionEnabled}")
        if (config.cameraEnabled) {
            AppLogStore.info(logTag, "Camera option enabled: ${config.cameraHost}:${config.cameraPort}${config.cameraPath}")
        }
        announcedPixhawkHeartbeat = false

        serviceScope.launch {
            relayLifecycleLock.withLock {
                if (relayJob?.isActive == true && config == lastConfig) {
                    AppLogStore.warn(logTag, "Start ignored: relay already running with same config")
                    return@withLock
                }

                gpsInjectionJob?.cancelAndJoin()
                gpsInjectionJob = null
                cameraStreamJob?.cancelAndJoin()
                cameraStreamJob = null
                relayJob?.cancelAndJoin()

                lastConfig = config
                RelayStatusStore.setRunning(config)
                relayJob = launch {
                    cameraStreamJob = startCameraStreamingIfEnabled(config)
                    try {
                        when (mode) {
                            RelayMode.UDP_RELAY -> runUdpRelay(config)
                            RelayMode.TCP_SERVER -> runTcpServerRelay(config)
                            RelayMode.TCP_CLIENT -> runTcpClientRelay(config)
                        }
                    } finally {
                        cameraStreamJob?.cancelAndJoin()
                        cameraStreamJob = null
                    }
                }
            }
        }
    }

    private fun stopRelay(stopService: Boolean = true) {
        serviceScope.launch {
            relayLifecycleLock.withLock {
                AppLogStore.info(logTag, "Stopping relay")
                announcedPixhawkHeartbeat = false
                gpsInjectionJob?.cancelAndJoin()
                gpsInjectionJob = null
                cameraStreamJob?.cancelAndJoin()
                cameraStreamJob = null
                relayJob?.cancelAndJoin()
                relayJob = null
                lastConfig = null
                RelayStatusStore.setStopped()
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (stopService) {
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runUdpRelay(config: RelayStatus) {
        val remoteAddress = InetSocketAddress(config.udpRemoteHost, config.udpRemotePort)
        val buffer = ByteArray(4096)
        AppLogStore.info(logTag, "UDP relay: UDP:${config.udpInputPort} -> ${config.udpRemoteHost}:${config.udpRemotePort}")
        try {
            openVehicleLink(config.udpInputPort).use { vehicleLink ->
                gpsInjectionJob = startPhoneGpsInjectionIfEnabled(config, vehicleLink)
                DatagramSocket().use { outputSocket ->
                    AppLogStore.info(logTag, "Vehicle input active: ${vehicleLink.description}")
                    updateNotification("UDP relay ${vehicleLink.description} -> ${config.udpRemoteHost}:${config.udpRemotePort}")
                    while (coroutineContext.isActive) {
                        val bytesRead = try {
                            vehicleLink.read(buffer)
                        } catch (_: SocketTimeoutException) {
                            continue
                        } catch (e: Exception) {
                            AppLogStore.error(logTag, "Vehicle read failed", e)
                            throw e
                        }

                        val payload = buffer.copyOf(bytesRead)
                        outputSocket.send(DatagramPacket(payload, payload.size, remoteAddress))
                        RelayStatusStore.recordForwarded(bytesRead)
                        checkHeartbeat(payload)
                    }
                }
            }
        } catch (e: Exception) {
            recordCrash(e)
        } finally {
            gpsInjectionJob?.cancel()
            gpsInjectionJob = null
        }
    }

    private suspend fun runTcpServerRelay(config: RelayStatus) {
        AppLogStore.info(logTag, "TCP server: binding TCP:${config.tcpServerPort}, UDP input :${config.udpInputPort}")
        try {
            openVehicleLink(config.udpInputPort).use { vehicleLink ->
                gpsInjectionJob = startPhoneGpsInjectionIfEnabled(config, vehicleLink)
                AppLogStore.info(logTag, "Vehicle input active: ${vehicleLink.description}")
                createReusableServerSocket(config.tcpServerPort).use { server ->
                    AppLogStore.info(logTag, "TCP server ready on :${config.tcpServerPort} — waiting for Mission Planner")
                    updateNotification("Waiting for MP on TCP:${config.tcpServerPort}")
                    while (coroutineContext.isActive) {
                        val client = try {
                            withContext(Dispatchers.IO) { server.accept() }
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        val addr = client.inetAddress.hostAddress ?: "?"
                        AppLogStore.info(logTag, "Mission Planner connected from $addr")
                        updateNotification("MP connected from $addr")
                        RelayStatusStore.setMpConnected(true)
                        try {
                            serveMpSession(client, vehicleLink)
                        } catch (e: Exception) {
                            AppLogStore.error(logTag, "MP session ended", e)
                        } finally {
                            runCatching { client.close() }
                            RelayStatusStore.setMpConnected(false)
                            AppLogStore.info(logTag, "MP disconnected, waiting for next connection")
                            updateNotification("MP disconnected — waiting on TCP:${config.tcpServerPort}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            recordCrash(e)
        } finally {
            gpsInjectionJob?.cancel()
            gpsInjectionJob = null
            RelayStatusStore.setMpConnected(false)
        }
    }

    private suspend fun runTcpClientRelay(config: RelayStatus) {
        AppLogStore.info(logTag, "TCP client: connecting to ${config.tcpClientHost}:${config.tcpClientPort}")
        try {
            openVehicleLink(config.udpInputPort).use { vehicleLink ->
                gpsInjectionJob = startPhoneGpsInjectionIfEnabled(config, vehicleLink)
                AppLogStore.info(logTag, "Vehicle input active: ${vehicleLink.description}")
                while (coroutineContext.isActive) {
                    try {
                        Socket().use { socket ->
                            updateNotification("Connecting to ${config.tcpClientHost}:${config.tcpClientPort}...")
                            socket.connect(InetSocketAddress(config.tcpClientHost, config.tcpClientPort), 10_000)
                            AppLogStore.info(logTag, "TCP connected to ${config.tcpClientHost}:${config.tcpClientPort}")
                            updateNotification("TCP connected to ${config.tcpClientHost}:${config.tcpClientPort}")
                            RelayStatusStore.setMpConnected(true)
                            serveMpSession(socket, vehicleLink)
                        }
                    } catch (e: Exception) {
                        RelayStatusStore.setMpConnected(false)
                        if (!coroutineContext.isActive) {
                            break
                        }
                        val reason = reconnectReason(e)
                        AppLogStore.warn(logTag, "TCP client reconnecting: $reason")
                        updateNotification("TCP retrying ${config.tcpClientHost}:${config.tcpClientPort} ($reason)")
                        delay(2000)
                    }
                }
            }
        } catch (_: CancellationException) {
            // Expected when service is stopping.
        } catch (e: SocketException) {
            if (coroutineContext.isActive) {
                recordCrash(e)
            }
        } catch (e: Exception) {
            recordCrash(e)
        } finally {
            gpsInjectionJob?.cancel()
            gpsInjectionJob = null
            RelayStatusStore.setMpConnected(false)
        }
    }

    private fun normalizeHostInput(rawHost: String): String {
        val trimmed = rawHost.trim()
        if (trimmed.isEmpty()) {
            return DEFAULT_HOST
        }

        val normalized = if (trimmed.contains(' ')) {
            val compact = trimmed.replace(" ", "")
            // Accept common typo where spaces are used instead of dots in IPv4 entry.
            if (trimmed.matches(Regex("^[0-9 ]+$")) && compact.length in 8..12) {
                trimmed.replace(" ", ".")
            } else {
                compact
            }
        } else {
            trimmed
        }

        if (normalized != rawHost) {
            AppLogStore.info(logTag, "Normalized TCP host input to $normalized")
        }
        return normalized
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneGpsInjectionIfEnabled(config: RelayStatus, vehicleLink: VehicleLink): Job? {
        if (!config.phoneGpsInjectionEnabled) {
            AppLogStore.info(logTag, "Phone GPS injection disabled in start request")
            return null
        }
        if (!hasLocationPermission()) {
            AppLogStore.warn(logTag, "Phone GPS injection requested but location permission is missing")
            return null
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val latest = AtomicReference<PhoneGpsSample?>(null)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                latest.set(sampleFromLocation(location))
            }
        }

        val providers = runCatching { locationManager.getProviders(true) }
            .getOrDefault(emptyList())
            .filter { it != LocationManager.PASSIVE_PROVIDER }

        if (providers.isEmpty()) {
            AppLogStore.warn(logTag, "Phone GPS injection requested but no location providers are enabled")
            return null
        }
        AppLogStore.info(logTag, "Phone GPS providers enabled: ${providers.joinToString(",")}")

        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(provider, 1000L, 0f, listener, mainLooper)
            }.onFailure {
                AppLogStore.warn(logTag, "GPS provider $provider subscribe failed: ${it.message ?: "unknown"}")
            }
        }

        // Seed with the newest known location so injection can start immediately.
        val seedProviders = (providers + listOf(LocationManager.PASSIVE_PROVIDER)).distinct()
        val seed = seedProviders
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (seed != null) {
            latest.set(sampleFromLocation(seed))
            AppLogStore.info(logTag, "Phone GPS seeded from ${seed.provider ?: "unknown"} t=${seed.time}")
        } else {
            AppLogStore.warn(logTag, "Phone GPS has no last-known fix yet; waiting for first location update")
        }

        AppLogStore.info(logTag, "Phone GPS injection enabled")
        return serviceScope.launch {
            var sentCount = 0L
            var waitLogTick = 0
            try {
                while (isActive) {
                    val sample = latest.get()
                    if (sample != null) {
                        val packet = MavlinkGpsInputEncoder.buildPacket(
                            sample = sample,
                            sequence = nextMavlinkSeq(),
                        )
                        runCatching { safeVehicleWrite(vehicleLink, packet, packet.size) }
                            .onFailure { AppLogStore.warn(logTag, "Phone GPS inject write failed: ${it.message ?: "unknown"}") }
                            .onSuccess {
                                sentCount += 1
                                if (sentCount % 10L == 0L) {
                                    AppLogStore.info(
                                        logTag,
                                        "Phone GPS injected (GPS_INPUT id=1) lat=${sample.lat} lon=${sample.lon} sats=${sample.satellites} hdop=${"%.2f".format(sample.hdop)}",
                                    )
                                }
                            }
                        waitLogTick = 0
                    } else {
                        waitLogTick += 1
                        if (waitLogTick >= 10) {
                            AppLogStore.warn(logTag, "Phone GPS waiting for first fix (no location sample yet)")
                            waitLogTick = 0
                        }
                    }
                    delay(1000)
                }
            } finally {
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            coarse == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startCameraStreamingIfEnabled(config: RelayStatus): Job? {
        if (!config.cameraEnabled) {
            return null
        }
        if (!hasCameraPermission()) {
            AppLogStore.warn(logTag, "Camera streaming requested but CAMERA permission is missing")
            return null
        }
        val cameraHost = normalizeHostInput(config.cameraHost)
        val cameraPort = config.cameraPort
        return serviceScope.launch {
            val streamer = PhoneCameraRtpStreamer(
                context = this@TelemetryRelayService,
                host = cameraHost,
                port = cameraPort,
                logTag = logTag,
            )
            try {
                AppLogStore.info(logTag, "Starting phone camera RTP stream to $cameraHost:$cameraPort")
                streamer.run()
            } catch (e: Exception) {
                if (isActive) {
                    AppLogStore.error(logTag, "Camera stream failed", e)
                }
            } finally {
                streamer.close()
                AppLogStore.info(logTag, "Camera stream stopped")
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        return camera == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun sampleFromLocation(location: Location): PhoneGpsSample {
        val satellites = location.extras?.getInt("satellites", 0)
            ?: location.extras?.getInt("satellites_used_in_fix", 0)
            ?: 0
        val hdopFromExtras = location.extras?.getFloat("hdop", Float.NaN) ?: Float.NaN
        val hdop = if (!hdopFromExtras.isNaN() && hdopFromExtras > 0f) {
            hdopFromExtras
        } else {
            // Android reports horizontal accuracy in meters; convert to approximate HDOP.
            ((location.accuracy / 5.0f).coerceIn(0.7f, 99.9f))
        }
        return PhoneGpsSample(
            timeMs = location.time,
            lat = location.latitude,
            lon = location.longitude,
            altM = if (location.hasAltitude()) location.altitude else 0.0,
            hasAltitude = location.hasAltitude(),
            hdop = hdop,
            vdop = (hdop * 1.3f).coerceIn(0.7f, 99.9f),
            satellites = satellites.coerceIn(0, 255),
            horizontalAccuracyM = location.accuracy.coerceAtLeast(0.5f),
            verticalAccuracyM = if (location.hasVerticalAccuracy()) {
                location.verticalAccuracyMeters.coerceAtLeast(0.5f)
            } else {
                (location.accuracy * 1.5f).coerceAtLeast(0.8f)
            },
            hasVerticalAccuracy = location.hasVerticalAccuracy(),
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            hasSpeed = location.hasSpeed(),
            speedAccuracyMps = if (location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond.coerceAtLeast(0.2f)
            } else {
                if (location.hasSpeed()) (location.speed * 0.2f).coerceAtLeast(0.5f) else 2.0f
            },
            hasSpeedAccuracy = location.hasSpeedAccuracy(),
            courseDeg = if (location.hasBearing()) location.bearing else -1f,
        )
    }

    private fun nextMavlinkSeq(): Int {
        val seq = mavlinkSeq and 0xFF
        mavlinkSeq = (mavlinkSeq + 1) and 0xFF
        return seq
    }

    private fun safeVehicleWrite(vehicleLink: VehicleLink, data: ByteArray, length: Int) {
        synchronized(vehicleWriteLock) {
            vehicleLink.write(data, length)
        }
    }

    private suspend fun serveMpSession(socket: Socket, vehicleLink: VehicleLink) {
        val tcpOut = socket.getOutputStream()
        val tcpIn = socket.getInputStream()
        val buffer = ByteArray(4096)

        val tcpReader = serviceScope.launch {
            val buf = ByteArray(4096)
            try {
                while (isActive && !socket.isClosed) {
                    val n = tcpIn.read(buf)
                    if (n <= 0) return@launch
                    runCatching { safeVehicleWrite(vehicleLink, buf, n) }
                }
            } catch (_: Exception) {
            }
        }

        try {
            while (coroutineContext.isActive && !socket.isClosed) {
                val bytesRead = try {
                    vehicleLink.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    AppLogStore.error(logTag, "Vehicle read failed", e)
                    break
                }

                val payload = buffer.copyOf(bytesRead)
                checkHeartbeat(payload)
                try {
                    tcpOut.write(buffer, 0, bytesRead)
                    tcpOut.flush()
                    RelayStatusStore.recordForwarded(bytesRead)
                } catch (e: Exception) {
                    AppLogStore.error(logTag, "TCP write failed", e)
                    break
                }
            }
        } finally {
            tcpReader.cancel()
        }
    }

    private suspend fun openVehicleLink(udpInputPort: Int): VehicleLink {
        val usbLink = tryOpenUsbVehicleLink()
        if (usbLink != null) {
            return usbLink
        }
        AppLogStore.warn(logTag, "USB MAVLink not available, using UDP input :$udpInputPort")
        return UdpVehicleLink(udpInputPort)
    }

    private suspend fun tryOpenUsbVehicleLink(): VehicleLink? {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = waitForUsbCdcAcmDevice(usbManager)
        if (device == null) {
            AppLogStore.info(logTag, "No USB serial devices found")
            return null
        }

        if (!usbManager.hasPermission(device)) {
            AppLogStore.info(logTag, "Requesting USB permission for ${device.deviceName}")
            val granted = requestUsbPermission(usbManager, device)
            if (!granted) {
                AppLogStore.warn(logTag, "USB permission denied for ${device.deviceName}")
                return null
            }
        }

        val connection = usbManager.openDevice(device) ?: run {
            AppLogStore.warn(logTag, "USB open failed for ${device.deviceName}")
            return null
        }

        val usbLink = UsbVehicleLink.tryOpen(connection, device)
        if (usbLink == null) {
            runCatching { connection.close() }
            AppLogStore.warn(logTag, "USB device is not a supported CDC data link")
            return null
        }

        AppLogStore.info(logTag, "Using ${usbLink.description}")
        return usbLink
    }

    private suspend fun waitForUsbCdcAcmDevice(usbManager: UsbManager): UsbDevice? {
        repeat(12) {
            val device = findUsbCdcAcmDevice(usbManager)
            if (device != null) {
                return device
            }
            // Pixhawk often appears briefly as bootloader then re-enumerates as runtime CDC.
            delay(500)
        }
        return null
    }

    private fun findUsbCdcAcmDevice(usbManager: UsbManager): UsbDevice? {
        val candidates = usbManager.deviceList.values.filter { device ->
            var hasCdcControl = false
            var hasCdcData = false
            for (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                    hasCdcControl = true
                }
                if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                    hasCdcData = true
                }
            }
            hasCdcControl && hasCdcData
        }

        val runtimeDevice = candidates.firstOrNull { device ->
            val product = (device.productName ?: "").uppercase()
            !product.contains("-BL")
        }
        if (runtimeDevice != null) {
            return runtimeDevice
        }

        val bootloaderCandidate = candidates.firstOrNull()
        if (bootloaderCandidate != null) {
            AppLogStore.info(logTag, "USB device is in bootloader stage, waiting for runtime CDC")
        }
        return null
    }

    private suspend fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (usbManager.hasPermission(device)) {
                continuation.resume(true)
                return@suspendCancellableCoroutine
            }

            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE,
            )
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_USB_PERMISSION) {
                        return
                    }

                    val callbackDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    }

                    // Ignore permission callbacks for different USB devices (bootloader/runtime churn).
                    if (callbackDevice != null && callbackDevice.deviceId != device.deviceId) {
                        return
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                        usbManager.hasPermission(device)
                    runCatching { unregisterReceiver(this) }
                    if (continuation.isActive) {
                        continuation.resume(granted)
                    }
                }
            }

            ContextCompat.registerReceiver(
                this@TelemetryRelayService,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            continuation.invokeOnCancellation { runCatching { unregisterReceiver(receiver) } }
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private interface VehicleLink : Closeable {
        val description: String
        fun read(buffer: ByteArray): Int
        fun write(data: ByteArray, length: Int)
    }

    private class UdpVehicleLink(port: Int) : VehicleLink {
        private val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
            soTimeout = 1000
        }
        private var lastSender: InetSocketAddress? = null

        override val description: String = "UDP:$port"

        override fun read(buffer: ByteArray): Int {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            lastSender = InetSocketAddress(packet.address, packet.port)
            return packet.length
        }

        override fun write(data: ByteArray, length: Int) {
            val destination = lastSender ?: return
            socket.send(DatagramPacket(data, length, destination))
        }

        override fun close() {
            socket.close()
        }
    }

    private class UsbVehicleLink(
        private val connection: UsbDeviceConnection,
        private val controlInterface: UsbInterface,
        private val dataInterface: UsbInterface,
        private val readEndpoint: UsbEndpoint,
        private val writeEndpoint: UsbEndpoint,
        override val description: String,
    ) : VehicleLink {
        override fun read(buffer: ByteArray): Int {
            val count = connection.bulkTransfer(readEndpoint, buffer, buffer.size, 1000)
            if (count <= 0) {
                throw SocketTimeoutException("USB read timeout")
            }
            return count
        }

        override fun write(data: ByteArray, length: Int) {
            if (length <= 0) {
                return
            }
            val transferred = connection.bulkTransfer(writeEndpoint, data, length, 1000)
            if (transferred <= 0) {
                throw IllegalStateException("USB write failed")
            }
        }

        override fun close() {
            runCatching { connection.releaseInterface(dataInterface) }
            runCatching { connection.releaseInterface(controlInterface) }
            runCatching { connection.close() }
        }

        companion object {
            private const val CDC_SET_LINE_CODING = 0x20
            private const val CDC_SET_CONTROL_LINE_STATE = 0x22
            private const val USB_RECIPIENT_INTERFACE = 0x01

            fun tryOpen(connection: UsbDeviceConnection, device: UsbDevice): UsbVehicleLink? {
                val controlInterfaces = findInterfaces(device, UsbConstants.USB_CLASS_COMM)
                val dataInterfaces = findInterfaces(device, UsbConstants.USB_CLASS_CDC_DATA)
                if (controlInterfaces.isEmpty() || dataInterfaces.isEmpty()) {
                    return null
                }

                val descending = dataInterfaces.sortedByDescending { it.id }
                val ascending = dataInterfaces.sortedBy { it.id }
                val orderedDataInterfaces = (descending + ascending).distinctBy { it.id }

                for (dataInterface in orderedDataInterfaces) {
                    val controlInterface = pickControlForData(controlInterfaces, dataInterface) ?: continue
                    val readEndpoint = findEndpoint(dataInterface, UsbConstants.USB_DIR_IN) ?: continue
                    val writeEndpoint = findEndpoint(dataInterface, UsbConstants.USB_DIR_OUT) ?: continue

                    if (!connection.claimInterface(controlInterface, true)) {
                        continue
                    }
                    if (!connection.claimInterface(dataInterface, true)) {
                        connection.releaseInterface(controlInterface)
                        continue
                    }

                    val configured = configureLineCoding(connection, controlInterface)
                    if (!configured) {
                        connection.releaseInterface(dataInterface)
                        connection.releaseInterface(controlInterface)
                        continue
                    }

                    val desc = "USB ${device.manufacturerName ?: "unknown"}/${device.productName ?: "device"} if${dataInterface.id}"
                    return UsbVehicleLink(
                        connection = connection,
                        controlInterface = controlInterface,
                        dataInterface = dataInterface,
                        readEndpoint = readEndpoint,
                        writeEndpoint = writeEndpoint,
                        description = desc,
                    )
                }

                return null
            }

            private fun findInterfaces(device: UsbDevice, usbClass: Int): List<UsbInterface> {
                val matches = mutableListOf<UsbInterface>()
                for (index in 0 until device.interfaceCount) {
                    val iface = device.getInterface(index)
                    if (iface.interfaceClass == usbClass) {
                        matches.add(iface)
                    }
                }
                return matches
            }

            private fun pickControlForData(
                controlInterfaces: List<UsbInterface>,
                dataInterface: UsbInterface,
            ): UsbInterface? {
                return controlInterfaces
                    .filter { it.id <= dataInterface.id }
                    .maxByOrNull { it.id }
                    ?: controlInterfaces.maxByOrNull { it.id }
            }

            private fun configureLineCoding(
                connection: UsbDeviceConnection,
                controlInterface: UsbInterface,
            ): Boolean {
                val lineCoding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
                val setLineCodingResult = connection.controlTransfer(
                    UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT or USB_RECIPIENT_INTERFACE,
                    CDC_SET_LINE_CODING,
                    0,
                    controlInterface.id,
                    lineCoding,
                    lineCoding.size,
                    1000,
                )
                val setControlStateResult = connection.controlTransfer(
                    UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT or USB_RECIPIENT_INTERFACE,
                    CDC_SET_CONTROL_LINE_STATE,
                    0x0003,
                    controlInterface.id,
                    null,
                    0,
                    1000,
                )
                return setLineCodingResult >= 0 && setControlStateResult >= 0
            }

            private fun findEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
                for (index in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(index)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction) {
                        return endpoint
                    }
                }
                return null
            }
        }
    }

    private fun checkHeartbeat(payload: ByteArray) {
        val hit = MavlinkHeartbeatDetector.findHeartbeat(payload) ?: return
        val now = System.currentTimeMillis()
        RelayStatusStore.recordHeartbeat(hit.systemId, now)
        if (
            hit.systemId == expectedPixhawkSysId &&
            hit.componentId == expectedAutopilotCompId &&
            hit.autopilotType == expectedArduPilotAutopilotType
        ) {
            RelayStatusStore.recordPixhawkHeartbeat(hit, now)
            if (!announcedPixhawkHeartbeat) {
                announcedPixhawkHeartbeat = true
                AppLogStore.info(logTag, "Pixhawk heartbeat detected (sysid=${hit.systemId}, compid=${hit.componentId})")
            }
        }
    }

    private suspend fun createReusableServerSocket(port: Int): ServerSocket {
        val maxAttempts = 12
        repeat(maxAttempts) { attempt ->
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 1000
                    bind(InetSocketAddress(port))
                }
            } catch (e: BindException) {
                val inUse = e.message?.contains("EADDRINUSE", ignoreCase = true) == true
                if (!inUse || attempt == maxAttempts - 1) {
                    throw e
                }
                AppLogStore.warn(logTag, "TCP bind retry ${attempt + 1}/$maxAttempts on :$port (address in use)")
                delay(300)
            }
        }
        throw BindException("bind failed on :$port")
    }

    private fun reconnectReason(e: Exception): String {
        return when {
            e is UnknownHostException -> "invalid host"
            e is NoRouteToHostException -> "no route to host"
            e is ConnectException -> "connection refused/unreachable"
            e.message?.contains("EHOSTUNREACH", ignoreCase = true) == true -> "no route to host"
            e.message?.contains("ECONNREFUSED", ignoreCase = true) == true -> "connection refused"
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private fun recordCrash(e: Exception) {
        if (e is CancellationException) {
            return
        }
        val reason = e.message ?: e.javaClass.simpleName
        RelayStatusStore.recordError(reason)
        AppLogStore.error(logTag, "Relay crashed", e)
        updateNotification("relay error: ${reason.take(60)}")
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun createChannel() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.relay_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.relay_channel_description)
            },
        )
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.relay_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private data class PhoneGpsSample(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        val altM: Double,
        val hasAltitude: Boolean,
        val hdop: Float,
        val vdop: Float,
        val satellites: Int,
        val horizontalAccuracyM: Float,
        val verticalAccuracyM: Float,
        val hasVerticalAccuracy: Boolean,
        val speedMps: Float,
        val hasSpeed: Boolean,
        val speedAccuracyMps: Float,
        val hasSpeedAccuracy: Boolean,
        val courseDeg: Float,
    )

    private object MavlinkGpsInputEncoder {
        private const val STX_V2 = 0xFD
        private const val PAYLOAD_LEN = 63
        private const val MSG_ID_GPS_INPUT = 232
        private const val CRC_EXTRA_GPS_INPUT = 151
        private const val SYSTEM_ID = 245
        private const val COMPONENT_ID = 220 // MAV_COMP_ID_GPS

        private const val IGNORE_ALT = 1
        private const val IGNORE_HDOP = 2
        private const val IGNORE_VDOP = 4
        private const val IGNORE_VEL_H = 8
        private const val IGNORE_VEL_V = 16
        private const val IGNORE_SPEED_ACC = 32
        private const val IGNORE_HORIZ_ACC = 64
        private const val IGNORE_VERT_ACC = 128
        private const val GPS_INPUT_ID = 1

        fun buildPacket(sample: PhoneGpsSample, sequence: Int): ByteArray {
            val payload = ByteBuffer.allocate(PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
            val gpsTime = toGpsWeek(sample.timeMs)
            var ignoreFlags = 0
            if (!sample.hasAltitude) {
                ignoreFlags = ignoreFlags or IGNORE_ALT
            }
            if (sample.hdop <= 0f || !sample.hdop.isFinite()) {
                ignoreFlags = ignoreFlags or IGNORE_HDOP
            }
            if (sample.vdop <= 0f || !sample.vdop.isFinite()) {
                ignoreFlags = ignoreFlags or IGNORE_VDOP
            }
            if (!sample.hasSpeed) {
                ignoreFlags = ignoreFlags or IGNORE_VEL_H or IGNORE_VEL_V
            }
            if (!sample.hasSpeedAccuracy) {
                ignoreFlags = ignoreFlags or IGNORE_SPEED_ACC
            }
            if (sample.horizontalAccuracyM <= 0f || !sample.horizontalAccuracyM.isFinite()) {
                ignoreFlags = ignoreFlags or IGNORE_HORIZ_ACC
            }
            if (!sample.hasVerticalAccuracy) {
                ignoreFlags = ignoreFlags or IGNORE_VERT_ACC
            }

            payload.putLong(sample.timeMs * 1000L)
            payload.putInt(gpsTime.weekMs)
            payload.putInt((sample.lat * 1e7).toInt())
            payload.putInt((sample.lon * 1e7).toInt())
            payload.putFloat(sample.altM.toFloat())
            payload.putFloat(sample.hdop)
            payload.putFloat(sample.vdop)
            payload.putFloat(sample.speedMps)
            payload.putFloat(0f)
            payload.putFloat(0f)
            payload.putFloat(sample.speedAccuracyMps)
            payload.putFloat(sample.horizontalAccuracyM)
            payload.putFloat(sample.verticalAccuracyM)
            // MAVLink field order for uint16 fields in GPS_INPUT payload is ignore_flags, then time_week.
            payload.putShort(ignoreFlags.toShort())
            payload.putShort(gpsTime.week.toShort())
            val fixType = when {
                sample.satellites >= 6 -> 3
                sample.satellites >= 4 -> 2
                else -> 1
            }
            payload.put(GPS_INPUT_ID.toByte())
            payload.put(fixType.toByte())
            payload.put(sample.satellites.toByte())

            val header = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            header.put(STX_V2.toByte())
            header.put(PAYLOAD_LEN.toByte())
            header.put(0)
            header.put(0)
            header.put(sequence.toByte())
            header.put(SYSTEM_ID.toByte())
            header.put(COMPONENT_ID.toByte())
            header.put((MSG_ID_GPS_INPUT and 0xFF).toByte())
            header.put(((MSG_ID_GPS_INPUT shr 8) and 0xFF).toByte())
            header.put(((MSG_ID_GPS_INPUT shr 16) and 0xFF).toByte())

            val crc = mavlinkX25(header.array().copyOfRange(1, 10), payload.array(), CRC_EXTRA_GPS_INPUT)

            val packet = ByteBuffer.allocate(10 + PAYLOAD_LEN + 2).order(ByteOrder.LITTLE_ENDIAN)
            packet.put(header.array())
            packet.put(payload.array())
            packet.putShort(crc.toShort())
            return packet.array()
        }

        private fun toGpsWeek(unixMs: Long): GpsWeekTime {
            val gpsEpochMs = 315964800000L
            val delta = (unixMs - gpsEpochMs).coerceAtLeast(0L)
            val weekMsTotal = 7L * 24L * 60L * 60L * 1000L
            val week = (delta / weekMsTotal).toInt()
            val weekMs = (delta % weekMsTotal).toInt()
            return GpsWeekTime(week, weekMs)
        }

        private fun mavlinkX25(headerNoStx: ByteArray, payload: ByteArray, extra: Int): Int {
            var crc = 0xFFFF
            for (b in headerNoStx) {
                crc = x25Accumulate(crc, b.toInt() and 0xFF)
            }
            for (b in payload) {
                crc = x25Accumulate(crc, b.toInt() and 0xFF)
            }
            crc = x25Accumulate(crc, extra and 0xFF)
            return crc and 0xFFFF
        }

        private fun x25Accumulate(crcIn: Int, data: Int): Int {
            var tmp = data xor (crcIn and 0xFF)
            tmp = tmp xor ((tmp shl 4) and 0xFF)
            return ((crcIn shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
        }

        private data class GpsWeekTime(val week: Int, val weekMs: Int)
    }

    companion object {
        const val ACTION_START = "com.dem.telemetry.action.START"
        const val ACTION_STOP = "com.dem.telemetry.action.STOP"
        const val EXTRA_RELAY_MODE = "extra_relay_mode"
        const val EXTRA_UDP_INPUT_PORT = "extra_udp_input_port"
        const val EXTRA_UDP_REMOTE_HOST = "extra_udp_remote_host"
        const val EXTRA_UDP_REMOTE_PORT = "extra_udp_remote_port"
        const val EXTRA_TCP_SERVER_PORT = "extra_tcp_server_port"
        const val EXTRA_TCP_CLIENT_HOST = "extra_tcp_client_host"
        const val EXTRA_TCP_CLIENT_PORT = "extra_tcp_client_port"
        const val DEFAULT_UDP_PORT = 14550
        const val DEFAULT_TCP_PORT = 5760
        const val DEFAULT_HOST = "192.168.137.1"
        const val EXTRA_CAMERA_ENABLED = "extra_camera_enabled"
        const val EXTRA_CAMERA_HOST = "extra_camera_host"
        const val EXTRA_CAMERA_PORT = "extra_camera_port"
        const val EXTRA_CAMERA_PATH = "extra_camera_path"
        const val DEFAULT_CAMERA_PORT = 8080
        const val DEFAULT_CAMERA_PATH = "/stream"
        private const val ACTION_USB_PERMISSION = "com.dem.telemetry.action.USB_PERMISSION"
        private const val CHANNEL_ID = "dem_telemetry_relay"
        private const val NOTIFICATION_ID = 14550
        const val EXTRA_PHONE_GPS_INJECTION = "extra_phone_gps_injection"
    }
}
