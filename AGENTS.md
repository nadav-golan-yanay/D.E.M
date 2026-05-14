# AGENTS.md

## Project Scope

- This repository is an Arduino IDE ESP32 firmware project for an nRF24-based MAVLink telemetry bridge.
- The main implementation lives in [D.E.M.ino](D.E.M.ino).

## Build And Validation

- Run commands from the repository root.
- Open [D.E.M.ino](D.E.M.ino) in Arduino IDE.
- Install the ESP32 board package and the RF24 library before building.
- Set `NODE_ROLE` to ground or air in [D.E.M.ino](D.E.M.ino) before compiling for each device.
- When changing shared logic in [D.E.M.ino](D.E.M.ino), validate both roles, not just one.
- Use board `ESP32 Dev Module` and monitor speed `115200` unless hardware requirements change.

## Architecture Notes

- There is a single firmware source file: [D.E.M.ino](D.E.M.ino).
- Role selection is compile-time only, via the `NODE_ROLE` constant in [D.E.M.ino](D.E.M.ino).
- Ground node bridges USB Serial to RF24; air node bridges RF24 to `Serial2` on GPIO16/GPIO17.
- The RF24 packet format is a fixed 32-byte frame with a maximum payload of 27 bytes. Do not change packet size, offsets, magic byte, or addressing on one side only.

## Change Guidance

- Preserve the `NODE_ROLE` selection block unless the role configuration model changes intentionally.
- Keep role-specific behavior inside the existing `#ifdef GROUND_NODE` and `#ifdef AIR_NODE` branches.
- Keep debug output behind `DEBUG_ENABLED`; default behavior should stay production-safe with debug disabled.
- Prefer minimal changes in [D.E.M.ino](D.E.M.ino); this repo does not yet have a broader module structure.
- Bump the project version on every code or documentation change. Keep `DEM_VERSION` in [D.E.M.ino](D.E.M.ino) and the version line in [README.md](README.md) in sync.
- If you change serial speed, RF24 channel/rate/CRC, packet structure, or pin assignments, update both roles coherently and call that out in the summary.

## Key Dependencies

- RF24 library in Arduino Library Manager.
- ESP32 board support in Arduino Boards Manager.

## Current Gaps

- There are no automated tests in this repository.
- Prefer an Arduino compile check for both roles as the primary validation after firmware changes.