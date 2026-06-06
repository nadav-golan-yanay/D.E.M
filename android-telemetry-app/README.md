# DEM Telemetry Relay App

Current app version: `1.1.7` (versionCode `15`)

This folder contains a minimal Android app scaffold for phone-side telemetry forwarding.

## What it does

- Runs a foreground service.
- Receives UDP telemetry on a configurable local port.
- Forwards each packet to a configured remote host and port.
- Shows simple running status and counters.

## Intended use

This is the first phone-side telemetry app for the current architecture. It is meant to support phone-link experiments without changing the ESP32 bridge firmware.

## Build notes

- Open this folder in Android Studio.
- Use the Android Gradle project files in the root and app module.
- Package name: `com.dem.telemetry`
- Default relay port: `14550`

## ADB install path

If you want to install from the connected phone later, build an APK in Android Studio and use `adb install` or the Android Studio Run action.

## Current limits

- This scaffold is a UDP relay first cut, not a full MAVLink ground control station.
- Video remains a separate IP stream.
- The ESP32 telemetry bridge remains the fallback path.
