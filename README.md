# D.E.M

ESP32 MAVLink telemetry bridge over nRF24L01+.

Current version: `0.1.1`

## Arduino IDE Setup

1. Open [D.E.M.ino](D.E.M.ino) in Arduino IDE.
2. Install `esp32` by Espressif Systems from Boards Manager.
3. Install `RF24` from Library Manager.
4. Select board `ESP32 Dev Module`.
5. Set the port for the connected ESP32.
6. In [D.E.M.ino](D.E.M.ino), change `NODE_ROLE` before each upload:
   - `NODE_ROLE_GROUND` for the USB-connected ground node.
   - `NODE_ROLE_AIR` for the air-side node connected to the flight controller on GPIO16/GPIO17.
7. Upload once for each ESP32 with the correct role selected.
8. Use Serial Monitor at `115200` baud for the ground node.

## Wiring

- nRF24 CE: GPIO4
- nRF24 CSN: GPIO5
- nRF24 SCK: GPIO18
- nRF24 MOSI: GPIO23
- nRF24 MISO: GPIO19
- Air node FC RX: GPIO16
- Air node FC TX: GPIO17

## Protocol Notes

- Fixed RF24 packet size: 32 bytes
- Maximum payload: 27 bytes
- Magic byte: `0xA5`
- RF channel: `76`
- RF data rate: `250 Kbps`

Both nodes must use the same packet format, addresses, channel, and CRC settings.

## Link Verification

- Firmware now sends a 1 Hz heartbeat packet (`HBG` or `HBA`) when local serial is idle.
- With debug enabled, this should make TX and RX counters move on both nodes even when no MAVLink traffic is present.