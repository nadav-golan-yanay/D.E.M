package com.dem.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RelayMode { UDP_RELAY, TCP_SERVER, TCP_CLIENT }

data class RelayStatus(
    val running: Boolean = false,
    val relayMode: RelayMode = RelayMode.TCP_SERVER,
    // UDP input (all modes — MAVLink arrives here from vehicle/bridge)
    val udpInputPort: Int = 14550,
    // UDP relay target (UDP_RELAY mode only)
    val udpRemoteHost: String = "192.168.137.1",
    val udpRemotePort: Int = 14550,
    // TCP server (TCP_SERVER mode — Mission Planner connects to phone)
    val tcpServerPort: Int = 5760,
    // TCP client target (TCP_CLIENT mode — phone connects to Mission Planner)
    val tcpClientHost: String = "192.168.137.1",
    val tcpClientPort: Int = 5760,
    // Optional phone-to-Pixhawk GPS injection over MAVLink GPS_INPUT
    val phoneGpsInjectionEnabled: Boolean = false,
    // runtime
    val mpConnected: Boolean = false,
    val packetsForwarded: Long = 0,
    val bytesForwarded: Long = 0,
    val heartbeatCount: Long = 0,
    val lastHeartbeatMs: Long = 0,
    val lastHeartbeatSystemId: Int = -1,
    val pixhawkHeartbeatCount: Long = 0,
    val lastPixhawkHeartbeatMs: Long = 0,
    val lastPixhawkComponentId: Int = -1,
    val lastPixhawkVehicleType: Int = -1,
    val lastPixhawkAutopilotType: Int = -1,
    val lastError: String? = null,
)

object RelayStatusStore {
    private val state = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = state.asStateFlow()

    fun setRunning(config: RelayStatus) {
        state.value = config.copy(
            running = true,
            mpConnected = false,
            packetsForwarded = 0,
            bytesForwarded = 0,
            heartbeatCount = 0,
            pixhawkHeartbeatCount = 0,
            lastHeartbeatMs = 0,
            lastPixhawkHeartbeatMs = 0,
            lastHeartbeatSystemId = -1,
            lastPixhawkComponentId = -1,
            lastError = null,
        )
    }

    fun setStopped() {
        state.value = state.value.copy(running = false, mpConnected = false)
    }

    fun setMpConnected(connected: Boolean) {
        state.value = state.value.copy(mpConnected = connected)
    }

    fun recordForwarded(packetSize: Int) {
        state.value = state.value.copy(
            packetsForwarded = state.value.packetsForwarded + 1,
            bytesForwarded = state.value.bytesForwarded + packetSize,
        )
    }

    fun recordHeartbeat(systemId: Int, seenAtMs: Long) {
        state.value = state.value.copy(
            heartbeatCount = state.value.heartbeatCount + 1,
            lastHeartbeatMs = seenAtMs,
            lastHeartbeatSystemId = systemId,
        )
    }

    fun recordPixhawkHeartbeat(hit: HeartbeatHit, seenAtMs: Long) {
        state.value = state.value.copy(
            pixhawkHeartbeatCount = state.value.pixhawkHeartbeatCount + 1,
            lastPixhawkHeartbeatMs = seenAtMs,
            lastHeartbeatMs = seenAtMs,
            lastHeartbeatSystemId = hit.systemId,
            lastPixhawkComponentId = hit.componentId,
            lastPixhawkVehicleType = hit.vehicleType,
            lastPixhawkAutopilotType = hit.autopilotType,
        )
    }

    fun recordError(message: String) {
        state.value = state.value.copy(lastError = message, running = false, mpConnected = false)
    }
}
