# Raspberry Pi + Camera Migration Prep

## Goal

Prepare migration from dual-ESP32 ESP-NOW telemetry bridge to a Raspberry Pi based telemetry and video node while keeping Mission Planner compatibility and deterministic recovery behavior.

## Confirmed Architecture Decision

1. Vehicle stack is Pixhawk 4 Mini + ArduPilot + Mission Planner on ground.
2. ESP32 pair remains telemetry-only (MAVLink transport), not video transport.
3. Camera is attached to Raspberry Pi 4 Model B companion computer.
4. Pixhawk sends MAVLink to Raspberry Pi, and Raspberry Pi forwards telemetry and streams video as separate flows.
5. Video is carried over IP as a dedicated stream, not tunneled through MAVLink.

## Scope of This Prep

- Define target architecture.
- Define hardware and software bill of materials.
- Define boot and service model.
- Define validation and rollback strategy.
- Provide ready-to-use service templates.

## Recommended Target Architecture

1. Air side (on vehicle): Raspberry Pi + camera + UART to PX4.
2. Ground side (operator PC): Mission Planner on Windows.
3. Transport options:
- Primary: MAVLink over UDP/TCP from Raspberry Pi to ground network endpoint.
- Optional fallback: USB serial passthrough bridge for local bench testing.

## Link and RF Guidance

1. For Wi-Fi range, prefer 2.4 GHz operation in early builds.
2. Use proper antenna placement and separation from noisy power electronics.
3. Prefer USB Wi-Fi adapter with external antenna over embedded antenna when possible.
4. Keep transport abstraction IP-based so you can later swap Wi-Fi for professional IP radios (for example CreoMagic or DTC) without changing drone-side telemetry/camera architecture.

## Hardware Baseline

1. Raspberry Pi 4B (4GB or higher) or Raspberry Pi 5.
2. Raspberry Pi Camera Module v3 (or HQ camera for optics flexibility).
3. Reliable 5V power rail sized for Pi + camera + radio.
4. UART connection from Pi to PX4 TELEM port:
- Pi TX -> PX4 RX
- Pi RX -> PX4 TX
- GND -> GND
5. Dedicated RF/network link as required by mission profile.

## Software Baseline

1. OS: Raspberry Pi OS Lite (64-bit), current stable.
2. Telemetry routing: mavlink-routerd (recommended) or MAVProxy.
3. Camera streaming stack:
- Low-latency option: `libcamera-vid` + `gst-rtsp-server` or UDP RTP pipeline.
- Broad compatibility option: `v4l2` + MJPEG/RTSP pipeline.
4. Service orchestration: systemd units with restart policies.
5. Diagnostics: journalctl logs + lightweight health script.

## Network and Port Plan (Example)

1. MAVLink uplink to Ground GCS:
- UDP out: ground_ip:14550
- Optional TCP server: 0.0.0.0:5760
2. Camera stream:
- RTSP: 8554
- Optional RTP/UDP: 5600

Adjust these values to match your ground station setup and firewall policy.

## Boot and Service Order

1. `mavlink-router.service` starts first (telemetry path).
2. `camera-stream.service` starts after network is online.
3. Both services use `Restart=always` and bounded restart delay.

## Validation Plan

1. UART validation (Pi <-> PX4):
- Confirm incoming heartbeat from PX4 locally on Pi.
2. Ground telemetry validation:
- Mission Planner receives heartbeat.
- Parameter download completes.
- Core telemetry messages update continuously.
3. Camera validation:
- Ground endpoint can open stream with acceptable latency.
4. Soak test:
- 30-60 minutes with service restarts and link interruptions.

## Rollback Plan

1. Keep ESP32 firmware binaries and known-good wiring map available.
2. Keep Mission Planner COM profile for ESP32 ground fallback.
3. If Pi migration fails in field test, restore ESP32 bridge topology and verify heartbeat/msg flow with existing playbook.

## Phased Migration

1. Phase 1: Bench shadow mode
- Keep ESP32 system active.
- Run Pi telemetry in parallel and compare msg rates.
2. Phase 2: Telemetry cutover
- Switch Mission Planner to Pi telemetry endpoint.
3. Phase 3: Camera integration
- Add camera stream and measure end-to-end latency.
4. Phase 4: Field acceptance
- Run complete mission profile and confirm stability.

## Command Prep (Pi)

Example packages to install (adjust per distro release):

```bash
sudo apt update
sudo apt install -y mavlink-router gstreamer1.0-tools gstreamer1.0-plugins-good \
  gstreamer1.0-plugins-bad libcamera-apps
```

## Repository Prep Artifacts

1. Systemd template for MAVLink router:
- [pi/systemd/mavlink-router.service](pi/systemd/mavlink-router.service)
2. Systemd template for camera stream:
- [pi/systemd/camera-stream.service](pi/systemd/camera-stream.service)
3. Example environment values:
- [pi/config/pi.env.example](pi/config/pi.env.example)

## Notes for Future AI Models

1. Always verify telemetry first, camera second.
2. Mission Planner connection health is defined by two conditions:
- heartbeat present
- parameter exchange successful
3. Keep one-path-at-a-time changes to simplify root-cause analysis.
4. Never route camera stream through MAVLink telemetry transport in this project architecture.
