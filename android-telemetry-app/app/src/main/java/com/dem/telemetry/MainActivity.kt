package com.dem.telemetry

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TelemetryRelayScreen()
                }
            }
        }
    }
}

@Composable
private fun TelemetryRelayScreen() {
    val context = LocalContext.current
    val status by RelayStatusStore.status.collectAsStateWithLifecycle()
    val logs by AppLogStore.entries.collectAsStateWithLifecycle()
    val autoLogPath by AppLogStore.autoLogPath.collectAsStateWithLifecycle()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var pendingLogExportText by remember { mutableStateOf("") }

    var selectedMode by rememberSaveable { mutableStateOf(RelayMode.TCP_SERVER) }
    var udpInputPortText  by rememberSaveable { mutableStateOf("14550") }
    var tcpServerPortText by rememberSaveable { mutableStateOf("5760") }
    var tcpClientHost     by rememberSaveable { mutableStateOf("192.168.137.1") }
    var tcpClientPortText by rememberSaveable { mutableStateOf("5760") }
    var udpRemoteHost     by rememberSaveable { mutableStateOf("192.168.137.1") }
    var udpRemotePortText by rememberSaveable { mutableStateOf("14550") }
    var phoneGpsInjectionEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by rememberSaveable { mutableStateOf(false) }
    var cameraHost by rememberSaveable { mutableStateOf("192.168.137.1") }
    var cameraPortText by rememberSaveable { mutableStateOf("8080") }
    var cameraPath by rememberSaveable { mutableStateOf("/stream") }

    var hasNotificationPermission by remember {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    }
    var hasLocationPermission by remember {
        mutableStateOf(hasPhoneLocationPermission(context))
    }
    var hasCameraPermission by remember {
        mutableStateOf(hasPhoneCameraPermission(context))
    }
    val pixhawkConnected = status.lastPixhawkHeartbeatMs > 0 &&
        (nowMs - status.lastPixhawkHeartbeatMs) <= 3000L

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            hasPhoneLocationPermission(context)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted || hasPhoneCameraPermission(context)
    }

    val saveLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) {
            AppLogStore.warn("MainActivity", "Save logs canceled")
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream).use { writer ->
                        writer.write(pendingLogExportText)
                    }
                } ?: error("Unable to open selected file")
            }.onSuccess {
                AppLogStore.info("MainActivity", "Logs saved")
            }.onFailure { e ->
                AppLogStore.error("MainActivity", "Failed to save logs", e)
            }
        }
        pendingLogExportText = ""
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(status.running, status.mpConnected) {
        while (status.running || status.mpConnected) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "DEM Telemetry Relay",
            style = MaterialTheme.typography.headlineMedium,
        )

        // ── Mode selector ──────────────────────────────────────────────────────
        Text(text = "Connection mode", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                RelayMode.TCP_SERVER to "TCP Server",
                RelayMode.TCP_CLIENT to "TCP Client",
                RelayMode.UDP_RELAY  to "UDP Relay",
            ).forEach { (mode, label) ->
                if (mode == selectedMode) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = { selectedMode = mode }) { Text(label) }
                }
            }
        }

        val modeDesc = when (selectedMode) {
            RelayMode.TCP_SERVER ->
                "Mission Planner connects TO phone (like COM10, but over IP).\n" +
                "In MP: choose TCP, enter phone IP and the TCP port below.\n" +
                "Cellular: turn on phone hotspot, laptop joins it, enter phone hotspot IP.\n" +
                "Pixhawk USB is auto-detected; if absent, app falls back to UDP input port."
            RelayMode.TCP_CLIENT ->
                "Phone connects OUT to Mission Planner (works through cellular NAT).\n" +
                "In MP: enable TCP server on port 5760. Enter MP public IP/hostname below.\n" +
                "Pixhawk USB is auto-detected; if absent, app falls back to UDP input port."
            RelayMode.UDP_RELAY ->
                "Forward UDP MAVLink from vehicle to a fixed remote host.\n" +
                "Useful when Pi or bridge sends UDP to phone, phone forwards to MP.\n" +
                "Pixhawk USB is auto-detected; if absent, app listens on UDP input port."
        }
        Text(text = modeDesc, style = MaterialTheme.typography.bodySmall)

        // ── Config card ────────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = udpInputPortText,
                    onValueChange = { udpInputPortText = it.filter(Char::isDigit) },
                    label = { Text("UDP input port (MAVLink arrives here from vehicle/bridge)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                when (selectedMode) {
                    RelayMode.TCP_SERVER -> OutlinedTextField(
                        value = tcpServerPortText,
                        onValueChange = { tcpServerPortText = it.filter(Char::isDigit) },
                        label = { Text("TCP server port (MP connects here, default 5760)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    RelayMode.TCP_CLIENT -> {
                        OutlinedTextField(
                            value = tcpClientHost,
                            onValueChange = {
                                tcpClientHost = it
                                    .replace(" ", ".")
                                    .replace("..", ".")
                                    .trimStart()
                            },
                            label = { Text("Mission Planner host / IP or domain") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = tcpClientPortText,
                            onValueChange = { tcpClientPortText = it.filter(Char::isDigit) },
                            label = { Text("Mission Planner TCP port (default 5760)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                    }
                    RelayMode.UDP_RELAY -> {
                        OutlinedTextField(
                            value = udpRemoteHost,
                            onValueChange = { udpRemoteHost = it },
                            label = { Text("Forward to host") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = udpRemotePortText,
                            onValueChange = { udpRemotePortText = it.filter(Char::isDigit) },
                            label = { Text("Forward to port") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                    }
                }

                Text(
                    text = "Phone GPS to Pixhawk (MAVLink GPS_INPUT: lat/lon/sats/hdop)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (phoneGpsInjectionEnabled) {
                        Button(onClick = { phoneGpsInjectionEnabled = false }) { Text("GPS inject ON") }
                    } else {
                        OutlinedButton(onClick = { phoneGpsInjectionEnabled = true }) { Text("Enable GPS inject") }
                    }
                    if (!hasLocationPermission) {
                        OutlinedButton(onClick = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }) {
                            Text("Grant location")
                        }
                    }
                }

                Text(
                    text = "Camera stream (optional)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cameraEnabled) {
                        Button(onClick = { cameraEnabled = false }) { Text("Camera ON") }
                    } else {
                        OutlinedButton(onClick = { cameraEnabled = true }) { Text("Enable camera") }
                    }
                    OutlinedButton(onClick = {
                        val normalizedPath = if (cameraPath.startsWith("/")) cameraPath else "/$cameraPath"
                        val uri = Uri.parse("http://${cameraHost.trim()}:${cameraPortText.toIntOrNull() ?: 8080}$normalizedPath")
                        val viewIntent = Intent(Intent.ACTION_VIEW, uri)
                        runCatching { context.startActivity(viewIntent) }
                            .onFailure { e -> AppLogStore.error("MainActivity", "Failed to open camera stream", e) }
                    }) {
                        Text("Open camera")
                    }
                    if (!hasCameraPermission) {
                        OutlinedButton(onClick = {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }) {
                            Text("Grant camera")
                        }
                    }
                }
                if (cameraEnabled) {
                    OutlinedTextField(
                        value = cameraHost,
                        onValueChange = { cameraHost = it.trimStart() },
                        label = { Text("Camera host / IP") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = cameraPortText,
                        onValueChange = { cameraPortText = it.filter(Char::isDigit) },
                        label = { Text("Camera port") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = cameraPath,
                        onValueChange = { cameraPath = it.trim() },
                        label = { Text("Camera path (for example /stream)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                }
            }
        }

        // ── Buttons ────────────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val intent = Intent(context, TelemetryRelayService::class.java).apply {
                        action = TelemetryRelayService.ACTION_START
                        putExtra(TelemetryRelayService.EXTRA_RELAY_MODE, selectedMode.name)
                        putExtra(TelemetryRelayService.EXTRA_UDP_INPUT_PORT,
                            udpInputPortText.toIntOrNull() ?: TelemetryRelayService.DEFAULT_UDP_PORT)
                        putExtra(TelemetryRelayService.EXTRA_UDP_REMOTE_HOST, udpRemoteHost)
                        putExtra(TelemetryRelayService.EXTRA_UDP_REMOTE_PORT,
                            udpRemotePortText.toIntOrNull() ?: TelemetryRelayService.DEFAULT_UDP_PORT)
                        putExtra(TelemetryRelayService.EXTRA_TCP_SERVER_PORT,
                            tcpServerPortText.toIntOrNull() ?: TelemetryRelayService.DEFAULT_TCP_PORT)
                        putExtra(TelemetryRelayService.EXTRA_TCP_CLIENT_HOST, tcpClientHost)
                        putExtra(TelemetryRelayService.EXTRA_TCP_CLIENT_PORT,
                            tcpClientPortText.toIntOrNull() ?: TelemetryRelayService.DEFAULT_TCP_PORT)
                        putExtra(TelemetryRelayService.EXTRA_PHONE_GPS_INJECTION, phoneGpsInjectionEnabled)
                        putExtra(TelemetryRelayService.EXTRA_CAMERA_ENABLED, cameraEnabled)
                        putExtra(TelemetryRelayService.EXTRA_CAMERA_HOST, cameraHost)
                        putExtra(TelemetryRelayService.EXTRA_CAMERA_PORT,
                            cameraPortText.toIntOrNull() ?: TelemetryRelayService.DEFAULT_CAMERA_PORT)
                        val normalizedCameraPath = if (cameraPath.startsWith("/")) cameraPath else "/$cameraPath"
                        putExtra(TelemetryRelayService.EXTRA_CAMERA_PATH, normalizedCameraPath)
                    }
                    ContextCompat.startForegroundService(context, intent)
                },
                enabled = hasNotificationPermission,
            ) { Text("Start relay") }
            OutlinedButton(onClick = {
                context.stopService(Intent(context, TelemetryRelayService::class.java).apply {
                    action = TelemetryRelayService.ACTION_STOP
                })
            }) { Text("Stop") }
        }
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text(text = "⚠ Notification permission required for foreground service.")
        }

        // ── Status ─────────────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Relay: ${if (status.running) "running (${status.relayMode})" else "stopped"}")
                Text(text = "MP connection: ${if (status.mpConnected) "✓ connected" else "waiting…"}")
                Text(text = "Pixhawk: ${if (pixhawkConnected) "✓ heartbeat active" else "waiting heartbeat"}")
                Text(text = "Phone GPS injection (selected): ${if (phoneGpsInjectionEnabled) "enabled" else "disabled"}")
                Text(text = "Phone GPS injection (running): ${if (status.phoneGpsInjectionEnabled) "enabled" else "disabled"}")
                Text(text = "Camera option (selected): ${if (cameraEnabled) "enabled" else "disabled"}")
                Text(text = "Camera option (running): ${if (status.cameraEnabled) "enabled" else "disabled"}")
                if (status.cameraEnabled) {
                    Text(text = "Camera endpoint: http://${status.cameraHost}:${status.cameraPort}${status.cameraPath}")
                }
                if (status.pixhawkHeartbeatCount > 0) {
                    Text(text = "  sysid=${status.lastHeartbeatSystemId}  hb#${status.pixhawkHeartbeatCount}")
                }
                Text(text = "Packets forwarded: ${status.packetsForwarded}")
                status.lastError?.let { Text(text = "Error: $it") }
            }
        }

        // ── Logs ───────────────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Logs", style = MaterialTheme.typography.titleMedium)
                Text(text = "Auto-save: ${autoLogPath ?: "initializing..."}")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        pendingLogExportText = buildLogExportText(status, logs)
                        val name = "dem-telemetry-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
                        saveLogsLauncher.launch(name)
                    }) { Text("Export logs") }
                    OutlinedButton(onClick = { AppLogStore.clear() }) { Text("Clear logs") }
                }
                val recentLogs = logs.takeLast(15)
                if (recentLogs.isEmpty()) {
                    Text(text = "No log entries yet")
                } else {
                    recentLogs.forEach { entry ->
                        Text(text = "${formatLogTime(entry.timestampMs)} ${entry.level}: ${entry.message}")
                    }
                }
            }
        }
    }
}

private fun formatLogTime(timestampMs: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    return formatter.format(Date(timestampMs))
}

private fun buildLogExportText(status: RelayStatus, logs: List<AppLogEntry>): String {
    val now = System.currentTimeMillis()
    val sb = StringBuilder()
    sb.appendLine("DEM Telemetry Relay log export")
    sb.appendLine("generated_at_utc=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(now))}")
    sb.appendLine("relay_running=${status.running}")
    sb.appendLine("relay_mode=${status.relayMode}")
    sb.appendLine("phone_gps_injection_enabled=${status.phoneGpsInjectionEnabled}")
    sb.appendLine("camera_enabled=${status.cameraEnabled}")
    sb.appendLine("camera_endpoint=http://${status.cameraHost}:${status.cameraPort}${status.cameraPath}")
    sb.appendLine("mp_connected=${status.mpConnected}")
    sb.appendLine("last_error=${status.lastError ?: "none"}")
    sb.appendLine("packets_forwarded=${status.packetsForwarded}")
    sb.appendLine("last_heartbeat_system_id=${status.lastHeartbeatSystemId}")
    sb.appendLine("pixhawk_heartbeat_count=${status.pixhawkHeartbeatCount}")
    sb.appendLine("last_pixhawk_heartbeat_ms=${status.lastPixhawkHeartbeatMs}")
    sb.appendLine()
    sb.appendLine("entries=${logs.size}")
    logs.forEach { entry ->
        sb.appendLine("${formatLogTime(entry.timestampMs)} ${entry.level} ${entry.tag}: ${entry.message}")
    }
    return sb.toString()
}

private fun hasPhoneLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

private fun hasPhoneCameraPermission(context: Context): Boolean {
    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
    return camera == PackageManager.PERMISSION_GRANTED
}
