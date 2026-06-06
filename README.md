# D.E.M

Telemetry bridge stack for Pixhawk and Mission Planner.

Current firmware/docs version: `0.3.38`
Current Android app version: `1.1.7` (versionCode `15`)

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

## What Is Relevant Now

- Primary reliable path today is ESP32 telemetry bridge:
  - Pixhawk UART -> Air ESP32 -> ESP-NOW -> Ground ESP32 USB -> Mission Planner.
- Android telemetry relay is active development and supports:
  - `TCP Server`
  - `TCP Client`
  - `UDP Relay`
  - persistent app logs
  - phone GPS injection toward Pixhawk GPS2
- Raspberry Pi companion path is staged and documented, but ESP32 remains fallback for operations.

## Quick Start (ESP32 Path)

1. Open [D.E.M.ino](D.E.M.ino) in Arduino IDE.
2. Install ESP32 board package (Espressif).
3. Build and flash one board as ground role and one board as air role.
4. Use board `ESP32 Dev Module` unless you intentionally use ESP32-CAM.
5. Keep telemetry baud at `115200`.
6. On ground laptop, connect Mission Planner to the Ground ESP32 COM port at `115200`.

## Critical Settings

- ESP-NOW channel is fixed to `1`.
- Role split is compile-time in [D.E.M.ino](D.E.M.ino):
  - `NODE_ROLE_GROUND`
  - `NODE_ROLE_AIR`
- Ground serial output must stay MAVLink-clean.
- Debug output is disabled by default (`DEBUG_ENABLED=0`).

## Wiring (Air ESP32 <-> Pixhawk)

- ESP32 `GPIO17` (TX2) -> Pixhawk RX
- ESP32 `GPIO16` (RX2) -> Pixhawk TX
- GND -> GND
- Use 3.3V UART logic only

## Android Telemetry App (Current Scope)

- Project location: [android-telemetry-app](android-telemetry-app)
- Purpose:
  - bridge MAVLink between phone and Mission Planner over IP
  - support reconnect and log capture for field troubleshooting
  - inject phone GPS (lat/lon/sats/hdop stream) toward flight controller GPS2 input path

For app-specific usage and build notes, see [android-telemetry-app/README.md](android-telemetry-app/README.md).

## Pi Companion Track

- Pi services/templates/scripts are in [pi/](pi/).
- This is for migration planning and companion operations.
- Camera/video is IP-only and never mixed into MAVLink telemetry bytes.

Detailed docs:
- [PI_CAMERA_MIGRATION_PREP.md](PI_CAMERA_MIGRATION_PREP.md)
- [PHONE_LINK_WORKSTREAM.md](PHONE_LINK_WORKSTREAM.md)
- [PI_CONNECTION_HANDOFF.md](PI_CONNECTION_HANDOFF.md)

## Troubleshooting First References

- Mission Planner recovery and deterministic checks:
  - [MP_RECOVERY_PLAYBOOK.md](MP_RECOVERY_PLAYBOOK.md)
- Historical milestones and module usage:
  - [TIMELINE.md](TIMELINE.md)

## Versioning Rule

- Every code or documentation change bumps version.
- Keep these synchronized:
  - `DEM_VERSION` in [D.E.M.ino](D.E.M.ino)
  - version line in this [README.md](README.md)