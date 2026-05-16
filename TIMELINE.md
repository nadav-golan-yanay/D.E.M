# Project Timeline

This timeline summarizes the major milestones completed in this repository so far.

## 2026-05-14 to 2026-05-16

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

## Current State

- Active firmware: [D.E.M.ino](D.E.M.ino)
- User-facing setup and operation guide: [README.md](README.md)
- Agent/project workflow guidance: [AGENTS.md](AGENTS.md)
- This historical summary: [TIMELINE.md](TIMELINE.md)
