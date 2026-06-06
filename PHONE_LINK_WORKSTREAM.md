# Phone Link Workstream (Companion Backhaul)

Document version: `0.3.35`

## Scope

This workstream focuses on using a phone hotspot/tether as the IP backhaul between Raspberry Pi companion services and the ground laptop.

This does not replace the existing ESP32 ESP-NOW telemetry bridge. ESP32 remains the known-good fallback path.

## Architecture Position

1. FC telemetry source stays unchanged:
- Pixhawk/FC UART MAVLink.
2. Telemetry bridge options:
- Baseline: ESP32 Air -> ESP-NOW -> ESP32 Ground -> Mission Planner (USB serial).
- Phone-link path: FC -> Pi (`mavlink-routerd`) -> phone IP network -> Mission Planner (UDP).
3. Video path stays separate:
- Pi camera stream over IP UDP/RTSP only.
- Never tunnel video through MAVLink.

## Recommended Phone Link Modes

1. Android hotspot mode (preferred for bench and rapid field tests)
- Phone provides Wi-Fi AP.
- Pi and laptop join same SSID.
2. Android USB tether mode (more stable if cable routing is acceptable)
- Pi uses `usb0` route.
- Adjust `PHONE_LINK_INTERFACE` in env.

## Pi Configuration

Edit `/etc/dem/pi.env`:

```env
# Mission Planner laptop endpoint on phone network
GCS_HOST=<LAPTOP_IP_ON_PHONE_NETWORK>
GCS_UDP_PORT=14550

# Enable phone-link checks
PHONE_LINK_ENABLE=1
PHONE_LINK_REQUIRED=1
PHONE_LINK_INTERFACE=wlan0
PHONE_LINK_GATEWAY=<PHONE_GATEWAY_IP>
PHONE_LINK_PING_GATEWAY=1
```

Typical gateway examples:
- Android hotspot: often `192.168.43.1` or vendor-specific.
- Windows hotspot: usually `192.168.137.1`.

## Services Used

1. `mavlink-router.service`
- Routes FC MAVLink UART to UDP endpoint (`GCS_HOST:GCS_UDP_PORT`).
2. `camera-stream.service`
- Streams H264 via UDP to `VIDEO_HOST:VIDEO_PORT`.
3. `dem-healthcheck.timer`
- Runs minute checks via `dem-healthcheck.sh`.
- If phone link is required, route/gateway failures become healthcheck failures.

## Bring-up Sequence

1. Start phone hotspot/tether.
2. Confirm laptop and Pi obtain IPs on the same subnet.
3. Confirm Pi default route uses expected interface (`wlan0` or `usb0`).
4. Start services:

```bash
sudo systemctl restart mavlink-router.service
sudo systemctl restart camera-stream.service
sudo systemctl restart dem-healthcheck.timer
```

5. Verify logs:

```bash
journalctl -u mavlink-router.service -n 50 --no-pager
journalctl -u camera-stream.service -n 50 --no-pager
journalctl -u dem-healthcheck.service -n 50 --no-pager
```

## Bench Checklist (Phone Link)

1. FC -> Pi telemetry ingest
- Heartbeat visible locally on Pi input path.
2. Pi -> Mission Planner over phone link
- Mission Planner receives heartbeat and parameter exchange succeeds.
3. Pi camera stream -> laptop over phone link
- Stream opens and remains stable.
4. Isolation test
- No MAVLink parser errors caused by video traffic.
- No video pipeline instability when MAVLink traffic spikes.
5. Fallback test
- Stop Pi services and confirm ESP32 Ground USB telemetry still connects cleanly.

## Non-Goals

1. No ESP flashing in this workstream.
2. No Android direct telemetry as permanent primary architecture unless explicitly approved in a later decision.
