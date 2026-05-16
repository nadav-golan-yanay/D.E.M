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

## Current State

- Active firmware: [D.E.M.ino](D.E.M.ino)
- User-facing setup and operation guide: [README.md](README.md)
- Agent/project workflow guidance: [AGENTS.md](AGENTS.md)
- This historical summary: [TIMELINE.md](TIMELINE.md)
