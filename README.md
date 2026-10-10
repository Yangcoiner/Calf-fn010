# fn010-bridge

An Android app that acts as a **Buttplug (buttplug.io) server** for the
ILLUSIONCLUB "Belt Knight" gen 2 (model FN010, broadcast name `FN010-RX`).

The phone connects to the toy over BLE and exposes a standard Buttplug
WebSocket endpoint on **port 12345**, so Intiface Central (or any Buttplug
client / game) on the same network can connect to `ws://<phone-ip>:12345`
and control the toy — no desktop Intiface BLE scanning needed.

Device protocol was documented via HCI capture; see
intiface/intiface-central#297 and buttplugio/buttplug#968.

## Features

- Buttplug JSON protocol, MessageVersion 3 (`RequestServerInfo`,
  `RequestDeviceList`, `StartScanning`, `ScalarCmd`, `StopDeviceCmd`,
  `StopAllDevices`, `Ping`)
- One device exposed: `FN010-RX` with two scalar features:
  - Index 0: **Vibrate** (9 steps)
  - Index 1: **Oscillate** (9 steps)
- Motors are force-stopped when the last client disconnects

## Usage

1. Install the APK (built by GitHub Actions, see below).
2. Turn on the toy, open the app, tap **Start Server + Scan** and grant
   Bluetooth permissions.
3. Find your phone's IP (e.g. in Wi-Fi settings).
4. In Intiface Central: connect to a custom server at `ws://<phone-ip>:12345`
   (or point your game/client there).
5. Tap **Stop** in the app to shut everything down safely.

## Building

GitHub Actions builds a debug APK on every push: see the **Actions** tab,
latest run, artifact `fn010-bridge-debug-apk`. No local toolchain required.

## AI disclosure

This project was developed with AI assistance; all code was reviewed and
tested by the maintainer.

## License

MIT
