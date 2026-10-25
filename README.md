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

- Buttplug JSON protocol, **Message Spec v4** (`RequestServerInfo`,
  `RequestDeviceList`, `StartScanning`, `OutputCmd`, `StopCmd`, `Ping`)
- One device exposed: `FN010-RX` with two output features:
  - FeatureIndex 0: **Vibrate** (levels 0..9)
  - FeatureIndex 1: **Oscillate** (levels 0..9)
- Motors are force-stopped when the last client disconnects

> **Note on protocol version**: this server speaks Buttplug **message spec v4**,
> which is what current FunFlixDroid (built-in intiface-engine v4) and
> Intiface Central v3+ expect. An earlier v3-based build showed as *connected
> but with an empty device list* in those clients. See `V4_MIGRATION.md`
> for the full diagnosis and the v3 → v4 field mapping.

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

---

## 中文说明

一个 Android 应用,为 ILLUSIONCLUB「腰带骑士」二代(型号 FN010,广播名 `FN010-RX`)提供 **Buttplug(buttplug.io)服务器**功能。

手机通过 BLE 连接玩具,并在**端口 12345** 开放标准 Buttplug WebSocket 服务——同一局域网内的 Intiface Central(或任何 Buttplug 客户端/游戏)连接 `ws://<手机IP>:12345` 即可控制玩具,无需电脑端再做蓝牙扫描。

设备协议经 HCI 抓包整理,详见 intiface/intiface-central#297 与 buttplugio/buttplug#968。

### 功能

- Buttplug JSON 协议,**消息规范 v4**(`RequestServerInfo`、`RequestDeviceList`、`StartScanning`、`OutputCmd`、`StopCmd`、`Ping`)
- 暴露一台设备 `FN010-RX`,两个输出特性:
  - FeatureIndex 0:**震动**(档位 0..9)
  - FeatureIndex 1:**伸缩**(档位 0..9)
- 最后一个客户端断开时自动停止马达

> **协议版本说明**:本服务器使用 Buttplug **消息规范 v4**,这是新版 FunFlixDroid
> (内置 intiface-engine v4)和 Intiface Central v3+ 所使用的版本。
> 早期基于 v3 的版本在这些客户端里会表现为「connected 但 Devices 列表为空」。
> 完整诊断与 v3 → v4 字段对照见 `V4_MIGRATION.md`。

### 使用方法

1. 安装 APK(由 GitHub Actions 云构建,见下)。
2. 打开玩具电源,启动本 App,点 **Start Server + Scan**,授予蓝牙权限。
3. 查看手机 IP(在 Wi-Fi 设置里)。
4. 在 Intiface Central 中连接自定义服务器 `ws://<手机IP>:12345`(或把游戏/客户端指向该地址)。
5. 用完后点 App 里的 **Stop** 安全关闭。

### 构建

GitHub Actions 会在每次 push 时自动构建 debug APK:见 **Actions** 标签页 → 最新一次运行 → Artifacts 下载 `fn010-bridge-debug-apk`。本地无需安装任何开发环境。

### AI 声明

本项目在开发过程中使用了 AI 辅助,所有代码均经维护者人工审校与测试。

### 许可证

MIT
