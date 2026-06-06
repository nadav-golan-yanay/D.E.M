package com.dem.telemetry

data class HeartbeatHit(
    val systemId: Int,
    val componentId: Int,
    val vehicleType: Int,
    val autopilotType: Int,
)

object MavlinkHeartbeatDetector {
    private const val MAVLINK_V1_STX = 0xFE
    private const val MAVLINK_V2_STX = 0xFD
    private const val MAVLINK_HEARTBEAT_MSG_ID = 0

    fun findHeartbeat(buffer: ByteArray): HeartbeatHit? {
        var index = 0
        while (index < buffer.size) {
            val stx = buffer[index].toInt() and 0xFF
            if (stx == MAVLINK_V1_STX) {
                val hit = parseV1(buffer, index)
                if (hit != null) {
                    return hit
                }
                index += 1
                continue
            }

            if (stx == MAVLINK_V2_STX) {
                val hit = parseV2(buffer, index)
                if (hit != null) {
                    return hit
                }
            }
            index += 1
        }
        return null
    }

    private fun parseV1(buffer: ByteArray, start: Int): HeartbeatHit? {
        if (start + 6 > buffer.size) {
            return null
        }

        val payloadLen = buffer[start + 1].toInt() and 0xFF
        val frameLen = 8 + payloadLen
        if (start + frameLen > buffer.size) {
            return null
        }

        val msgId = buffer[start + 5].toInt() and 0xFF
        if (msgId != MAVLINK_HEARTBEAT_MSG_ID) {
            return null
        }

        val systemId = buffer[start + 3].toInt() and 0xFF
        val componentId = buffer[start + 4].toInt() and 0xFF
        val payloadStart = start + 6
        val vehicleType = buffer[payloadStart + 4].toInt() and 0xFF
        val autopilotType = buffer[payloadStart + 5].toInt() and 0xFF
        return HeartbeatHit(systemId, componentId, vehicleType, autopilotType)
    }

    private fun parseV2(buffer: ByteArray, start: Int): HeartbeatHit? {
        if (start + 10 > buffer.size) {
            return null
        }

        val payloadLen = buffer[start + 1].toInt() and 0xFF
        val incompatFlags = buffer[start + 2].toInt() and 0xFF
        val hasSignature = (incompatFlags and 0x01) != 0
        val frameLen = 12 + payloadLen + if (hasSignature) 13 else 0
        if (start + frameLen > buffer.size) {
            return null
        }

        val msgId0 = buffer[start + 7].toInt() and 0xFF
        val msgId1 = buffer[start + 8].toInt() and 0xFF
        val msgId2 = buffer[start + 9].toInt() and 0xFF
        val msgId = msgId0 or (msgId1 shl 8) or (msgId2 shl 16)
        if (msgId != MAVLINK_HEARTBEAT_MSG_ID) {
            return null
        }

        val systemId = buffer[start + 5].toInt() and 0xFF
        val componentId = buffer[start + 6].toInt() and 0xFF
        val payloadStart = start + 10
        val vehicleType = buffer[payloadStart + 4].toInt() and 0xFF
        val autopilotType = buffer[payloadStart + 5].toInt() and 0xFF
        return HeartbeatHit(systemId, componentId, vehicleType, autopilotType)
    }
}
