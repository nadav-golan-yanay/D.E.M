# D.E.M

ESP32 built-in radio link test using ESP-NOW.

Current version: `0.2.0`

## Arduino IDE Setup

1. Open [D.E.M.ino](D.E.M.ino) in Arduino IDE.
2. Install `esp32` by Espressif Systems from Boards Manager.
3. No external radio library is required for ESP-NOW mode.
4. Select board `ESP32 Dev Module`.
5. Set the port for the connected ESP32.
6. In [D.E.M.ino](D.E.M.ino), change `NODE_ROLE` before each upload:
   - `NODE_ROLE_GROUND` for the USB-connected ground node.
   - `NODE_ROLE_AIR` for the air-side node connected to the flight controller on GPIO16/GPIO17.
7. Upload once for each ESP32 with the correct role selected.
8. Use Serial Monitor at `115200` baud for the ground node.

## ESP-NOW Notes

- Uses ESP32 built-in 2.4GHz radio (no nRF24 module).
- Fixed ESP-NOW channel: `1`.
- Each node sends a heartbeat every second.
- Serial logs include RX sender MAC and packet sequence.

## Link Verification

- Firmware sends a 1 Hz heartbeat packet (`role=G` or `role=A`).
- With both boards powered, both monitors should show RX lines and increasing TX/RX counters.