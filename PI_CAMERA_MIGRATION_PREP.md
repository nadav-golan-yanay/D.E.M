# Raspberry Pi + Camera Migration Prep

Document version: `0.3.35`

## Goal

Prepare migration from dual-ESP32 ESP-NOW telemetry bridge to a Raspberry Pi based telemetry and video node while keeping Mission Planner compatibility and deterministic recovery behavior.

## Confirmed Architecture Decision

1. Vehicle stack is Pixhawk 4 Mini + ArduPilot + Mission Planner on ground.
2. ESP32 pair remains telemetry-only (MAVLink transport), not video transport.
3. Camera is attached to Raspberry Pi 4 Model B companion computer.
4. Pixhawk sends MAVLink to Raspberry Pi, and Raspberry Pi forwards telemetry and streams video as separate flows.
5. Video is carried over IP as a dedicated stream, not tunneled through MAVLink.

## Current Operational Baseline (Must Stay Intact)

1. Existing ESP32 ESP-NOW telemetry bridge remains the known-good path.
2. Ground node serial output remains MAVLink-clean in normal operation.
3. Debug and diagnostics remain behind explicit command paths or debug build flags.
4. Offline synthetic heartbeat remains disabled by default.
5. Pi integration must be incremental and reversible to the ESP32 path.

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

## Interface Contract (Telemetry vs Video vs Future RF)

Use this contract to keep migration modular:

1. FC telemetry interface:
- MAVLink UART from flight controller is an input-only source for transport services.
2. Companion telemetry interface:
- `mavlink-routerd` (or MAVProxy) consumes FC UART MAVLink and emits network MAVLink.
3. Video interface:
- Camera pipeline emits only IP video stream (RTP/UDP or RTSP), never MAVLink payloads.
4. Ground telemetry interface:
- Mission Planner receives MAVLink from either:
  - ESP32 Ground USB serial (fallback path), or
  - Pi network endpoint (migration path).
5. RF abstraction interface:
- Any future IP radio replacement (DTC, CreoMagic, Doodle Labs, Microhard) swaps only the IP link layer; FC, Pi services, and camera pipeline stay unchanged.

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
2. `camera-stream.service` starts after network is online and after MAVLink router service starts.
3. Both services use `Restart=always` and bounded restart delay.
4. `dem-healthcheck.timer` runs periodic health checks and reports failures via `journalctl`.

## Logging and Health

1. Runtime logs:
- Use `journalctl -u mavlink-router.service -f` and `journalctl -u camera-stream.service -f`.
2. Periodic health checks:
- `dem-healthcheck.service` verifies expected service state.
- Optional UDP port check validates camera bind/listen state.
3. Failure response:
- If Pi services fail repeatedly, keep or restore ESP32 fallback telemetry link for operations.

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
5. Cross-corruption check:
- Verify no camera/video bytes ever appear on MAVLink telemetry path.

## Rollback Plan

1. Keep ESP32 firmware binaries and known-good wiring map available.
2. Keep Mission Planner COM profile for ESP32 ground fallback.
3. If Pi migration fails in field test, restore ESP32 bridge topology and verify heartbeat/msg flow with existing playbook.

## Bench Test Checklist (Required Before Cutover)

1. Pixhawk/FC -> ESP32 Air telemetry
- Confirm FC UART baud matches `FC_BAUDRATE` and heartbeat appears on Ground capture.
2. ESP32 Ground -> Mission Planner
- Confirm heartbeat and parameter exchange work with no debug text corruption.
3. Pi camera stream -> laptop
- Confirm stream open, stable frame cadence, acceptable latency.
4. Pi MAVLink routing (if enabled)
- Confirm Mission Planner receives heartbeat and params via Pi endpoint.
5. Telemetry/video isolation
- Confirm video stream does not alter MAVLink parse quality and MAVLink traffic does not disturb video stream.
6. Failure fallback
- Disable Pi services and verify immediate return to known-good ESP32 telemetry workflow.

## Service Deployment Steps (Pi)

1. Copy env template:
- `sudo mkdir -p /etc/dem`
- `sudo cp pi/config/pi.env.example /etc/dem/pi.env`
2. Install runtime scripts:
- `sudo install -m 755 pi/bin/dem-mavlink-router-start.sh /usr/local/bin/dem-mavlink-router-start.sh`
- `sudo install -m 755 pi/bin/dem-camera-stream-start.sh /usr/local/bin/dem-camera-stream-start.sh`
- `sudo install -m 755 pi/bin/dem-healthcheck.sh /usr/local/bin/dem-healthcheck.sh`
3. Install systemd units:
- `sudo cp pi/systemd/mavlink-router.service /etc/systemd/system/`
- `sudo cp pi/systemd/camera-stream.service /etc/systemd/system/`
- `sudo cp pi/systemd/dem-healthcheck.service /etc/systemd/system/`
- `sudo cp pi/systemd/dem-healthcheck.timer /etc/systemd/system/`
4. Enable and start:
- `sudo systemctl daemon-reload`
- `sudo systemctl enable --now mavlink-router.service camera-stream.service dem-healthcheck.timer`

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
3. Systemd health-check units:
- [pi/systemd/dem-healthcheck.service](pi/systemd/dem-healthcheck.service)
- [pi/systemd/dem-healthcheck.timer](pi/systemd/dem-healthcheck.timer)
4. Startup scripts:
- [pi/bin/dem-mavlink-router-start.sh](pi/bin/dem-mavlink-router-start.sh)
- [pi/bin/dem-camera-stream-start.sh](pi/bin/dem-camera-stream-start.sh)
- [pi/bin/dem-healthcheck.sh](pi/bin/dem-healthcheck.sh)
5. Example environment values:
- [pi/config/pi.env.example](pi/config/pi.env.example)

## Notes for Future AI Models

1. Always verify telemetry first, camera second.
2. Mission Planner connection health is defined by two conditions:
- heartbeat present
- parameter exchange successful
3. Keep one-path-at-a-time changes to simplify root-cause analysis.
4. Never route camera stream through MAVLink telemetry transport in this project architecture.

## Related Workstream

For focused companion backhaul via phone hotspot/tether, see [PHONE_LINK_WORKSTREAM.md](PHONE_LINK_WORKSTREAM.md).
