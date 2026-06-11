# D.E.M

Telemetry bridge stack for Pixhawk and Mission Planner, with ESP32 firmware, Android relay tooling, and Raspberry Pi companion templates.

Current firmware/docs version: `0.3.50`
Current Android app version: `1.3.0` (versionCode `27`)

## Branching Model (Controller-Based)

Long-lived branches are organized by controller type:

- `main` = integration/default branch for shared and cross-system work
- `phone` = phone app track, focused on [android-telemetry-app](android-telemetry-app)
- `esp` = ESP telemetry firmware track, focused on [D.E.M.ino](D.E.M.ino)
- `pi` = Pi telemetry/companion track, focused on [pi/](pi/)

If `phone` is not yet created in remote, phone work can temporarily flow through `main` until the split is completed.

### Ownership by scope

- Phone app work belongs on `phone` (or temporarily `main` during migration).
- ESP firmware work belongs on `esp`.
- Pi telemetry/companion work belongs on `pi`.
- Root architecture docs that affect all subsystems stay coordinated across long-lived branches.

### Merge policy

- Start feature work from the relevant long-lived branch:
  - phone feature branch -> `phone`
  - ESP feature branch -> `esp`
  - Pi feature branch -> `pi`
- Use `main` for integration work and deliberate cross-system changes.
- Only cross-merge between controller branches when a change intentionally impacts multiple controller types.

### PR and branch protection expectations

- Require review and validation per long-lived branch.
- Keep PR scope aligned to one controller type when possible.
- Do not mix unrelated Android, ESP, and Pi changes in one PR unless the change is explicitly cross-platform.

## Repository Layout

- [D.E.M.ino](D.E.M.ino): ESP32 ESP-NOW telemetry bridge firmware (ground/air roles).
- [android-telemetry-app](android-telemetry-app): Android foreground relay app for MAVLink + GPS injection + optional phone camera RTP stream.
- [pi](pi): Raspberry Pi service templates and scripts for MAVLink router + camera stream.
- [MP_RECOVERY_PLAYBOOK.md](MP_RECOVERY_PLAYBOOK.md): Mission Planner recovery workflow.
- [PI_CAMERA_MIGRATION_PREP.md](PI_CAMERA_MIGRATION_PREP.md): Pi camera migration plan and architecture.
- [PI_CONNECTION_HANDOFF.md](PI_CONNECTION_HANDOFF.md): Pi network/SSH handoff notes.

## Current Architecture

- Stable production path:
  Pixhawk UART -> Air ESP32 -> ESP-NOW -> Ground ESP32 USB -> Mission Planner
- Android path (active development):
  phone app relays MAVLink over TCP/UDP, injects phone GPS via MAVLink GPS_INPUT, and can stream phone camera as H264 RTP/UDP.
- Pi path (companion migration):
  telemetry and camera stay as separate IP flows and must never be mixed into MAVLink byte stream.

## Quick Start (ESP32 Telemetry)

1. Open [D.E.M.ino](D.E.M.ino) in Arduino IDE.
2. Install Espressif ESP32 board package.
3. Build and flash one board as ground role and one board as air role.
4. Use board ESP32 Dev Module unless intentionally using ESP32-CAM.
5. Keep telemetry baud at 115200.
6. Connect Mission Planner to Ground ESP32 COM port at 115200.

## Android Relay Highlights

See [android-telemetry-app/README.md](android-telemetry-app/README.md) for app usage details.

Current app capabilities:

- TCP Server mode (Mission Planner connects to phone).
- TCP Client mode (phone connects out to Mission Planner).
- UDP Relay mode.
- Persistent app logs for field diagnosis.
- Phone GPS injection to Pixhawk GPS2 path via MAVLink GPS_INPUT.
- Direct phone camera stream to configurable host/port as H264 RTP/UDP.

## Mission Planner Video Notes

- Video is sent as RTP/UDP H264 from phone or Pi endpoints.
- Recommended default destination port is 5600 when aligning with Pi templates.
- Camera/video transport remains separate from MAVLink telemetry transport.

## Build and Validation Guidance

- Firmware changes in [D.E.M.ino](D.E.M.ino): validate both ground and air roles.
- Android changes in [android-telemetry-app](android-telemetry-app): run Gradle build before release.
- Mission Planner acceptance: validate both heartbeat detection and parameter exchange.

## Publish Checklist

1. Build Android release APK from [android-telemetry-app](android-telemetry-app).
2. Verify no compile/lint errors in modified app modules.
3. Confirm version synchronization across:
   - [D.E.M.ino](D.E.M.ino)
   - [README.md](README.md)
   - [android-telemetry-app/README.md](android-telemetry-app/README.md)
   - [android-telemetry-app/app/build.gradle.kts](android-telemetry-app/app/build.gradle.kts)

## Versioning Rule

- Every code or documentation change must bump version.
- Keep firmware/docs and app versions synchronized with repository documentation.