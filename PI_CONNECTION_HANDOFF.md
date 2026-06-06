# Raspberry Pi Connection Handoff

Document version: `0.3.35`

Date: 2026-05-23
Last updated: 2026-05-23 00:53 +03:00

## Current findings

- `nadav.local` does not resolve on this Windows host.
- `192.168.1.173` is this Windows PC (WiFi), not the Pi.
- Pi should be moved to laptop hotspot network (`192.168.137.x`).
- Previous SD Wi-Fi config targets `NJ1` / `192.168.1.240`, which is not the hotspot path.

## Target setup (Hotspot mode)

- Hostname: `nadav`
- SSH enabled: yes
- SSH password login: disabled (`ssh_pwauth: false`)
- SSH key-only login for user: `pi`
- Hotspot gateway on laptop: `192.168.137.1`
- Pi network should be `192.168.137.x`

## SSH key (keep as-is)

- Private key: `C:\Users\nadav\.ssh\pi_boot_key`
- Public key to keep on Pi:
  - `ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM7GUbrkUayO+7OkREPMtLIBlTdwV31P7Tl8WY4cTKpW pi-boot-access`

## SD card files to edit

Edit on SD boot partition:

- `D:\user-data`
- `D:\network-config`

## Recommended `network-config` (DHCP first)

Use this first for reliability. Replace hotspot SSID/password.

```yaml
version: 2
wifis:
  wlan0:
    optional: true
    access-points:
      "YOUR_HOTSPOT_SSID":
        password: "YOUR_HOTSPOT_PASSWORD"
    dhcp4: true
```

## Optional `network-config` (static IP on hotspot)

Use this only after DHCP works once.

```yaml
version: 2
wifis:
  wlan0:
    optional: true
    access-points:
      "YOUR_HOTSPOT_SSID":
        password: "YOUR_HOTSPOT_PASSWORD"
    dhcp4: false
    addresses:
      - 192.168.137.240/24
    routes:
      - to: default
        via: 192.168.137.1
    nameservers:
      addresses: [1.1.1.1, 8.8.8.8]
```

## Required `user-data` checks

Ensure this user/key content exists and is valid YAML.

```yaml
ssh_pwauth: false

users:
  - name: pi
    shell: /bin/bash
    sudo: ALL=(ALL) NOPASSWD:ALL
    lock_passwd: true
    ssh_authorized_keys:
      - ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM7GUbrkUayO+7OkREPMtLIBlTdwV31P7Tl8WY4cTKpW pi-boot-access
```

## Connection commands after SD update

1. Boot Pi and wait 2-3 minutes.
2. Find Pi IP in hotspot subnet (`192.168.137.x`) from hotspot connected devices.
3. Connect with:

```powershell
ssh -F NUL -i C:\Users\nadav\.ssh\pi_boot_key pi@<PI_IP>
```

## If IP is unknown (PowerShell scan)

```powershell
1..254 | % {
  $ip = "192.168.137.$_"
  if ((Test-NetConnection $ip -Port 22 -WarningAction SilentlyContinue).TcpTestSucceeded) {
    $ip
  }
}
```

## If still failing

1. Re-check SD YAML indentation and quotes.
2. Confirm hotspot SSID/password exact match.
3. Confirm hotspot is enabled before booting Pi.
4. Retry with DHCP config first, then static.
