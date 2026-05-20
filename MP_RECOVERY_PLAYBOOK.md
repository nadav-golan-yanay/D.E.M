# Mission Planner Recovery Playbook (Ground COM10, Air COM13)

## Purpose

Use this document as a handoff summary for any engineer or AI model when Mission Planner connection fails.

## Known-Good Baseline

- Ground node port: COM10
- Air node port: COM13
- Air node connected to PX4 UART
- Board: ESP32 Dev Module
- Baud: 115200
- Firmware version: 0.3.7 (baseline when this was validated)
- Ground role: NODE_ROLE_GROUND
- Air role: NODE_ROLE_AIR
- ESP-NOW channel: 1
- Debug serial prints: disabled in normal operation
- Offline synthetic heartbeat: disabled by default

## What Was Verified Working

- Ground and Air were flashed with explicit role overrides.
- COM10 stream contained valid MAVLink2 traffic from PX4 through the bridge.
- Heartbeat packets were present on COM10.
- Parameter flow was verified by sending PARAM_REQUEST_LIST and receiving PARAM_VALUE responses.

Evidence from successful simulation run:

- Heartbeat count (msg id 0): 13
- PARAM_VALUE count (msg id 22): 500
- STATUSTEXT count (msg id 253): 18
- Additional telemetry present: attitude, GPS, system status, and others

## Fast Recovery Procedure

1. Confirm ports are present: Ground on COM10 and Air on COM13.
2. Flash Air explicitly as AIR role to COM13.
3. Flash Ground explicitly as GROUND role to COM10.
4. Ensure PX4 telemetry UART matches 115200 and MAVLink is enabled.
5. Connect Mission Planner to COM10 at 115200.
6. If Mission Planner still fails, run packet capture on COM10 and check:
- Heartbeat (msg id 0) must be present.
- Parameter values (msg id 22) should appear after PARAM_REQUEST_LIST.

## Failure Signatures and Meaning

- No heartbeat on COM10:
  - Air not linked, wrong role flashed, wrong PX4 UART/baud, or broken wiring.
- Connected but stuck on Getting Params:
  - Heartbeat exists but parameter responses are missing or blocked.
- Human-readable debug text in telemetry stream:
  - Debug prints are contaminating MAVLink bytes.
- Repeating ESP32 boot text:
  - Board is resetting or serial line is being toggled repeatedly by tools.

## Required Wiring (Air to PX4)

- ESP32 GPIO17 (TX2) -> PX4 RX
- ESP32 GPIO16 (RX2) -> PX4 TX
- ESP32 GND -> PX4 GND
- 3.3V logic only

## Notes for Future Models

- Do not assume roles are correct; always flash both nodes with explicit role overrides first.
- Validate with packet-level decoding, not only serial text output.
- Treat Mission Planner connection as two stages:
  - Stage 1: heartbeat detection
  - Stage 2: parameter exchange
- Ground-only heartbeat should remain disabled by default to avoid false-positive connects.
