# D.E.M

ESP32 telemetry bridge using built-in ESP-NOW radio.

Current version: `0.3.1`

## Arduino IDE Setup

1. Open [D.E.M.ino](D.E.M.ino) in Arduino IDE.
2. Install `esp32` by Espressif Systems from Boards Manager.
3. No external radio module or RF24 library is required.
4. Select board `ESP32 Dev Module`.
5. Set the port for the connected ESP32.
6. In [D.E.M.ino](D.E.M.ino), change `NODE_ROLE` before each upload:
   - `NODE_ROLE_GROUND` for the USB-connected ground node.
   - `NODE_ROLE_AIR` for the air-side node connected to the flight controller on GPIO16/GPIO17.
7. Upload once for each ESP32 with the correct role selected.
8. Use Serial Monitor at `115200` baud for the ground node.

## ESP-NOW Bridge Notes

- Uses ESP32 built-in 2.4GHz radio (no nRF24 module).
- Fixed ESP-NOW channel: `1`.
- Ground node bridges USB Serial to ESP-NOW.
- Air node bridges ESP-NOW to `Serial2` (`GPIO16/GPIO17`) at `115200`.
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