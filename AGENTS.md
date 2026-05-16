# AGENTS.md

## Project Scope

- This repository is an Arduino IDE ESP32 firmware project for an ESP-NOW built-in radio telemetry bridge.
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
- Ground and air nodes communicate directly using ESP-NOW over the ESP32 built-in 2.4GHz radio.
- Keep ESP-NOW channel and packet structure aligned across both roles.

## Change Guidance

- Preserve the `NODE_ROLE` selection block unless the role configuration model changes intentionally.
- Keep role-specific behavior inside the existing `#ifdef GROUND_NODE` and `#ifdef AIR_NODE` branches.
- Keep debug output behind `DEBUG_ENABLED`; default behavior should stay production-safe with debug disabled.
- Prefer minimal changes in [D.E.M.ino](D.E.M.ino); this repo does not yet have a broader module structure.
- Bump the project version on every code or documentation change. Keep `DEM_VERSION` in [D.E.M.ino](D.E.M.ino) and the version line in [README.md](README.md) in sync.
- If you change serial speed, ESP-NOW channel, packet structure, or role behavior, update both roles coherently and call that out in the summary.

## Key Dependencies

- ESP32 board support in Arduino Boards Manager.

## Current Gaps

- There are no automated tests in this repository.
- Prefer an Arduino compile check for both roles as the primary validation after firmware changes.