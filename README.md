# D.E.M

ESP32 telemetry bridge using built-in ESP-NOW radio.

Current version: `0.3.35`
Android app version: `1.1.7` (versionCode `15`)

## Arduino IDE Setup

1. Open [D.E.M.ino](D.E.M.ino) in Arduino IDE.
2. Install `esp32` by Espressif Systems from Boards Manager.
3. No external radio module or RF24 library is required.
4. Select board `ESP32 Dev Module` for standard ESP32, or `AI Thinker ESP32-CAM` when compiling for ESP32-CAM hardware.
5. Set the port for the connected ESP32.
6. In [D.E.M.ino](D.E.M.ino), set build configuration before each upload:
   - `NODE_ROLE_GROUND` for the USB-connected ground node.
   - `NODE_ROLE_AIR` for the air-side node.
    - `AIR_HW_PROFILE`:
     - `AIR_HW_ESP32_DEV` uses FC UART on GPIO16/GPIO17.
     - `AIR_HW_ESP32_CAM` uses FC UART on GPIO13/GPIO14 (SD card pins unavailable in this mode).
       - ESP32-CAM board builds auto-select `AIR_HW_ESP32_CAM`; manual override is still available.
   - Optional: set `ENABLE_AIR_CAMERA 1` only on ESP32-CAM if you want periodic camera capture while telemetry is running.
7. Upload once for each ESP32 with the correct role selected.
8. Use Serial Monitor at `115200` baud for the ground node.

## ESP-NOW Bridge Notes

- Uses ESP32 built-in 2.4GHz radio (no nRF24 module).
- Fixed ESP-NOW channel: `1`.
- Ground node bridges USB Serial to ESP-NOW.
- Air node bridges ESP-NOW to `Serial2` at `115200`.
- Air pin mapping is controlled by `AIR_HW_PROFILE`.
- Optional ESP32-CAM camera mode is telemetry-first and disabled by default.
- ESP32-CAM built-in LED (GPIO4) gives a slight pulse when FC UART traffic is present.
- LED mode can be controlled at runtime from Mission Planner using `MAVLink COMMAND_LONG`:
   - `command=31000`, `param1=0` auto pulse on FC traffic.
   - `command=31000`, `param1=1` LED off.
   - `command=31000`, `param1=2` LED on.
- Mission Planner UI path (no command sender needed):
   - Open `Actions` tab in Flight Data.
   - Use `Set Relay` with relay number `9`.
   - Value `0` -> LED off.
   - Value `1` -> LED on.
   - Value `2` -> LED auto pulse mode.
   - Bridge replies with `COMMAND_ACK` for relay `9` commands so Mission Planner shows command success.
- Each node sends a heartbeat packet every second.
- Framed packets include protocol version, packet type, role, payload length, sequence, and uptime.
- Link stats include missing, duplicate, and out-of-order sequence tracking.

## Link Verification

- With both boards powered, both serial monitors should show periodic RX and stats output.
- `tx_send_ok` and `rx_packets` should increase on both nodes.
- If `rx_missing` or `tx_enqueue_failed` grows quickly, reduce traffic or check channel interference.

## Console Commands

Ground node serial console supports these commands:

- `::help`
- `::stats`
- `::reset`

Commands are line-based and intended for diagnostics when the USB serial stream is not carrying raw telemetry.

## PX4 + Mission Planner Test Prep

For incident handoff and rapid troubleshooting steps, see [MP_RECOVERY_PLAYBOOK.md](MP_RECOVERY_PLAYBOOK.md).
For Raspberry Pi + camera migration planning, see [PI_CAMERA_MIGRATION_PREP.md](PI_CAMERA_MIGRATION_PREP.md).

Architecture decision for camera integration:
- Keep ESP32 pair focused on MAVLink telemetry transport.
- Do not stream video through MAVLink telemetry channels.
- Use Raspberry Pi companion computer for camera and separate IP video stream.

## Raspberry Pi Companion Interface (Migration Track)

Use this interface contract to keep migration deterministic and avoid telemetry/video cross-corruption:

- FC telemetry input on vehicle:
   - Pixhawk/FC UART MAVLink -> Air-side transport endpoint.
- Telemetry transport (current fallback baseline):
   - ESP32 Air Serial2 <-> ESP-NOW <-> ESP32 Ground USB Serial -> Mission Planner.
- Companion telemetry path (optional migration path):
   - Pixhawk/FC UART MAVLink -> Raspberry Pi -> `mavlink-routerd` UDP out to Mission Planner.
- Video path:
   - Raspberry Pi camera -> dedicated IP stream (RTP/UDP or RTSP), never tunneled through MAVLink.
- Startup and fault policy:
   - ESP32 telemetry bridge remains valid fallback/legacy link while Pi services are integrated and bench-validated.
   - Ground serial must stay MAVLink-clean in normal operation.

Pi deployment templates and checks live under [pi/](pi/), and workflow details are in [PI_CAMERA_MIGRATION_PREP.md](PI_CAMERA_MIGRATION_PREP.md).

Phone-link focused integration notes are in [PHONE_LINK_WORKSTREAM.md](PHONE_LINK_WORKSTREAM.md).

The phone-side telemetry app scaffold is in [android-telemetry-app/README.md](android-telemetry-app/README.md).

### 1. Hardware wiring for Air node to PX4

- ESP32 Dev profile:
   - ESP32 `GPIO17` (TX2) -> PX4 telemetry port RX
   - ESP32 `GPIO16` (RX2) -> PX4 telemetry port TX
- ESP32-CAM profile:
   - ESP32-CAM `GPIO14` (TX2) -> PX4 telemetry port RX
   - ESP32-CAM `GPIO13` (RX2) -> PX4 telemetry port TX
- ESP32 GND -> PX4 GND

Use only 3.3V UART logic. Do not connect 5V UART signals directly.

### 2. PX4 serial/MAVLink setup

Configure the PX4 telemetry port you used for MAVLink, with baud matching `FC_BAUDRATE` in [D.E.M.ino](D.E.M.ino).

Common PX4 parameters to check (names can differ by PX4 version/port):

- `MAV_1_CONFIG` (or another MAV instance) to the selected TELEM port
- `MAV_1_MODE` to normal/onboard mode as needed
- `SER_TELx_BAUD` to match firmware baud (currently `115200`)

### 3. Ground node to Mission Planner

- Connect Ground ESP32 over USB to PC.
- In Mission Planner, select the Ground ESP32 COM port.
- Set COM baud to `115200`.

### 4. Firmware behavior for MAVLink tests

- Debug prints are disabled in current version to avoid corrupting MAVLink stream.
- Ground diagnostics commands are disabled during passthrough tests.
- UART bytes are bridged over ESP-NOW with framing and sequence tracking.
- By default, if the air node is offline, the ground node does not emit synthetic MAVLink heartbeats; Mission Planner should only connect when real FC telemetry is present.