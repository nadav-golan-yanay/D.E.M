# Project Timeline

Document version: `0.3.35`

This timeline summarizes the major milestones completed in this repository so far.

## 2026-05-14 to 2026-05-20

1. Initial project standardization
- Added agent guidance file and project conventions for role-based firmware work.

2. PlatformIO to Arduino IDE migration
- Converted project from PlatformIO layout to Arduino sketch layout.
- Main firmware moved to [D.E.M.ino](D.E.M.ino).
- Removed PlatformIO dependency for normal build/upload workflow.

3. Early compile and compatibility fixes
- Fixed ESP32 board/core selection checks.
- Fixed RF24 read-path compile mismatch.
- Fixed RF24 address declarations for exact 5-byte addressing.

4. Versioning and documentation policy introduced
- Added `DEM_VERSION` in firmware.
- Added synchronized version line in [README.md](README.md).
- Established rule: bump version on every code or documentation change.

5. Arduino CLI integration and deployment automation
- Installed Arduino CLI on the development environment.
- Added role override support using compile flags to flash Ground/Air without manual code edits each time.
- Compiled and uploaded firmware variants directly to connected boards.

6. Radio architecture pivot (external nRF24 to built-in ESP32 radio)
- Replaced external nRF24 link path with ESP-NOW over ESP32 built-in 2.4GHz radio.
- Updated repository documentation and project guidance accordingly.

7. Full ESP-NOW telemetry bridge implementation
- Implemented framed packet protocol with:
  - protocol version
  - packet type
  - source role
  - payload length
  - sequence
  - uptime
- Added Ground/Air bridging behavior:
  - Ground: USB Serial <-> ESP-NOW
  - Air: ESP-NOW <-> Serial2 (FC UART)
- Added telemetry integrity stats:
  - send enqueue failures
  - send callback success/failure
  - invalid packets
  - duplicate packets
  - out-of-order packets
  - missing packets
- Added diagnostic console commands on Ground:
  - `::help`
  - `::stats`
  - `::reset`

8. Hardware validation status
- Both ESP32 boards compile and flash successfully with role-specific firmware builds.
- Runtime logs confirm bidirectional heartbeat reception over ESP-NOW.

9. Mission Planner and MAVLink passthrough hardening
- Investigated Ground-node connection failures with Mission Planner using live serial capture and MAVLink packet parsing.
- Identified telemetry corruption risk from debug prints and moved back to MAVLink-clean serial output for normal operation.
- Added and validated synthetic offline HEARTBEAT behavior for diagnostics, then refined behavior based on integration results.
- Confirmed Ground output packet cadence and heartbeat framing over USB with repeated capture runs.

10. ESP32 stability and boot-noise mitigation
- Diagnosed recurring ESP32 boot/core-dump text artifacts observed on Ground serial startup.
- Performed full flash erase and clean reflash cycle to remove stale core-dump data artifacts.
- Re-validated startup stream and MAVLink packet extraction after erase/upload sequence.

11. Connection semantics refinement for Mission Planner
- Updated offline behavior to avoid false-positive Mission Planner "connected" state when Air/FC telemetry is absent.
- Ground firmware now defaults to no synthetic heartbeat unless real telemetry path is active (Air + FC path required for connect).
- Verified no-heartbeat output on Ground when Air/FC is offline, aligning connection state with actual link availability.

12. Version progression completed in this session
- Progressed release versions through iterative debug and integration fixes:
  - `0.3.4`: debug stream cleanup for MAVLink integrity.
  - `0.3.5`: Mission Planner-friendly heartbeat identity adjustments.
  - `0.3.6`: disabled offline synthetic heartbeat by default to prevent params hang.
  - `0.3.7`: timeline/documentation synchronization checkpoint for end-of-session handoff.

13. End-to-end Mission Planner simulation success
- Reflashed both nodes with explicit role overrides to remove role ambiguity:
  - Air node on COM13 with AIR role.
  - Ground node on COM10 with GROUND role.
- Simulated Mission Planner behavior in two stages over COM10:
  - heartbeat detection
  - parameter request/response exchange
- Verified successful MAVLink passthrough from PX4 through Air->Ground bridge:
  - HEARTBEAT (msg id 0) observed
  - PARAM_VALUE (msg id 22) observed in high volume after request
  - Additional telemetry and STATUSTEXT packets observed

14. Recovery handoff documentation
- Added [MP_RECOVERY_PLAYBOOK.md](MP_RECOVERY_PLAYBOOK.md) for reusable incident context and deterministic recovery flow.

15. Documentation and migration preparation checkpoint
- Added [PI_CAMERA_MIGRATION_PREP.md](PI_CAMERA_MIGRATION_PREP.md) with a practical transition plan from ESP32 bridge nodes to Raspberry Pi + camera architecture.
- Added service templates and deployment scaffolding under [pi/](pi/) for reproducible startup and operations.
- Synced project version to `0.3.9` for this documentation and planning update.

16. Camera architecture decision finalized
- Confirmed system direction for Pixhawk 4 Mini + ArduPilot + Mission Planner stack:
  - ESP modules remain telemetry-only.
  - Camera path moves to Raspberry Pi 4 Model B companion computer.
  - Video remains separate IP stream (not MAVLink tunneled video).
- Added RF/range guidance (2.4 GHz preference, antenna placement, external-antenna adapter option).
- Captured long-term compatibility note: future migration to professional IP radios (for example CreoMagic or DTC) should not require drone-side architecture change.

## 2026-06-05

1. Pi companion service hardening and interface definition
- Refined [PI_CAMERA_MIGRATION_PREP.md](PI_CAMERA_MIGRATION_PREP.md) with:
  - explicit telemetry/video/RF interface contract
  - startup ordering and rollback behavior
  - logging and health-check model
  - bench checklist for telemetry, routing, and stream isolation validation

2. Pi deployment scaffolding improvements
- Added startup scripts:
  - [pi/bin/dem-mavlink-router-start.sh](pi/bin/dem-mavlink-router-start.sh)
  - [pi/bin/dem-camera-stream-start.sh](pi/bin/dem-camera-stream-start.sh)
  - [pi/bin/dem-healthcheck.sh](pi/bin/dem-healthcheck.sh)
- Updated service templates:
  - [pi/systemd/mavlink-router.service](pi/systemd/mavlink-router.service)
  - [pi/systemd/camera-stream.service](pi/systemd/camera-stream.service)
- Added health-check systemd units:
  - [pi/systemd/dem-healthcheck.service](pi/systemd/dem-healthcheck.service)
  - [pi/systemd/dem-healthcheck.timer](pi/systemd/dem-healthcheck.timer)
- Expanded environment template in [pi/config/pi.env.example](pi/config/pi.env.example).

3. Version synchronization
- Bumped firmware and docs version to `0.3.19` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)

4. Phone-link workstream kickoff
- Added companion backhaul guidance for hotspot/tether path in:
  - [PHONE_LINK_WORKSTREAM.md](PHONE_LINK_WORKSTREAM.md)
- Added optional phone-link route and gateway checks in:
  - [pi/bin/dem-healthcheck.sh](pi/bin/dem-healthcheck.sh)
  - [pi/config/pi.env.example](pi/config/pi.env.example)

5. Android telemetry relay scaffold
- Added a minimal phone-side telemetry relay app scaffold in:
  - [android-telemetry-app/](android-telemetry-app/)
- Bumped firmware/docs version to `0.3.21` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)

6. Android build configuration fix
- Enabled Compose build support and set a compatible Kotlin Compose compiler extension for the phone telemetry app.

7. Android Pixhawk link indicator
- Added MAVLink heartbeat detection in the phone relay service and a UI connection indicator showing Pixhawk link status.
- Updated phone app version to `0.2.0`.
- Bumped firmware/docs version to `0.3.22` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)

8. Pixhawk parameter-aligned app behavior
- Aligned phone app FC link criteria to the current Pixhawk params baseline (`SYSID_THISMAV=1`) and ArduPilot heartbeat identity.
- Added FC-specific heartbeat counters and metadata display in the app UI.
- Updated phone app version to `0.3.0`.
- Bumped firmware/docs version to `0.3.23` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)

9. In-app diagnostics logging
- Added in-app log buffer and logcat logging for relay lifecycle and socket errors.
- Added log viewer and clear button in the phone UI for faster field diagnostics.
- Updated phone app version to `0.4.0`.
- Bumped firmware/docs version to `0.3.24` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)

10. Relay error diagnostics hardening
- Added relay restart serialization to reduce socket rebind races from rapid start/stop taps.
- Updated relay error notification text to include a brief exception summary.
- Updated phone app version to `0.4.1`.
- Bumped firmware/docs version to `0.3.25` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)

11. Notification flood fix
- Removed per-packet notification updates from the relay hot path to prevent Android notification queue shedding.
- Updated phone app version to `0.4.2`.
- Bumped firmware/docs version to `0.3.26` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)

12. Phone app architecture upgrade — TCP Server + Cellular modes
- Added three relay modes: TCP Server, TCP Client, UDP Relay.
- TCP Server mode: Mission Planner connects to the phone exactly like it connects to COM10.
- TCP Client mode: phone initiates the TCP connection outward, works through cellular NAT.
- Both TCP modes support bidirectional MAVLink (commands from MP flow back to the vehicle).
- Added mode selector UI, per-mode config fields, scrollable layout, and MP-connected indicator.
- Removed RowButtons helper; added inline Start/Stop with OutlinedButton.
- Created [scripts/com10-bridge.ps1](scripts/com10-bridge.ps1): PowerShell serial→UDP bridge that reads the ESP32 ground node on COM10 and forwards bytes to the phone app's UDP input port.
- Updated phone app version to `1.0.0` (versionCode 7).
- Bumped firmware/docs version to `0.3.27` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)

## Module and Tool Usage Log

1. 2026-05-14
- GitHub Copilot coding agent (GPT-5.3-Codex): project conversion and iterative firmware refactoring.
- Arduino IDE + ESP32 board package: baseline compile/upload workflow.

2. 2026-05-14 to 2026-05-15
- Arduino CLI (`arduino-cli`): automated compile and upload for repeatable Ground/Air flashing.
- ESP32 Arduino core libraries (`WiFi`, `esp_now`, `esp_wifi`): implemented ESP-NOW telemetry bridge.

3. 2026-05-15 to 2026-05-16
- Mission Planner: connection and parameter-flow validation target.
- PX4 MAVLink telemetry link: upstream telemetry source for Air node.
- PowerShell serial analysis scripts (`System.IO.Ports.SerialPort`): packet capture and MAVLink msg-id decoding.
- Esptool (`esptool.py` / `esptool.exe`): full flash erase and boot-noise/core-dump artifact cleanup.

4. 2026-05-17
- Explicit role-override flashing via Arduino CLI for both nodes:
  - `-DNODE_ROLE=NODE_ROLE_AIR` to COM13
  - `-DNODE_ROLE=NODE_ROLE_GROUND` to COM10
- Mission Planner connect simulation module (custom MAVLink requester in PowerShell):
  - sent PARAM_REQUEST_LIST
  - verified PARAM_VALUE responses and active telemetry flow

5. 2026-05-20
- Raspberry Pi migration planning module:
  - produced a concrete transition architecture for replacing ESP32 bridge links with Linux services.
  - added camera pipeline options (low-latency and compatible variants), startup ordering, and operations checklist.
- Documentation handoff module:
  - consolidated known-good state and recovery flows for reuse by future AI models and engineers.

6. 2026-05-20 (architecture lock)
- Architecture decision module:
  - explicitly locked telemetry and video separation model.
  - documented migration-safe RF abstraction for later radio replacement.

## Assistant Module Usage (Copilot Tooling)

1. 2026-05-14 to 2026-05-20
- `run_in_terminal`: compile, upload, serial capture, MAVLink parsing, git operations.
- `apply_patch`: firmware/doc edits and version synchronization.
- `create_file`: new handoff and migration preparation documents.
- `read_file`: validation and context gathering from project files.
- `list_dir` and `file_search`: workspace structure and target discovery.
- `multi_tool_use.parallel`: parallelized read-only context collection for faster diagnostics.

## Current State

- Active firmware: [D.E.M.ino](D.E.M.ino)
- User-facing setup and operation guide: [README.md](README.md)
- Agent/project workflow guidance: [AGENTS.md](AGENTS.md)
- This historical summary: [TIMELINE.md](TIMELINE.md)

## 2026-06-07

1. Android GPS injection compatibility hardening
- Updated phone telemetry app to improve phone GPS sampling reliability and GPS feed compatibility with Pixhawk GPS2 expectations.
- Updated phone app version to `1.1.7` (versionCode `15`).

2. Documentation/version synchronization
- Bumped firmware/docs version to `0.3.35` in:
  - [D.E.M.ino](D.E.M.ino)
  - [README.md](README.md)
- Added explicit document version headers to handoff/prep docs for consistent release tracking.
