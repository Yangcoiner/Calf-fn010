# FN010 Bridge —— Buttplug v4 迁移说明

## 问题诊断

**现象**：FunFlixDroid 里显示 connected，但 Devices 列表是空的。

**根因**：不是"伪造的 Interface"不被识别，而是 **Buttplug 协议版本错配**。

| | 旧版 App（你说"之前用过"） | 新版 FunFlixDroid |
|---|---|---|
| 内置引擎 | intiface-engine（说规范 v3） | intiface-engine v4（只说规范 v4） |
| 握手字段 | `MessageVersion` | `ProtocolVersionMajor` / `ProtocolVersionMinor` |
| 设备列表 | `Devices: [...]`（数组） | `Devices: {...}`（map，key=设备索引） |
| 设备特性 | `DeviceMessages` | `DeviceFeatures` |
| 输出指令 | `ScalarCmd` + `Scalars` | `OutputCmd` + `Command:{Vibrate:{Value}}` |
| 停止指令 | `StopDeviceCmd` / `StopAllDevices` | `StopCmd` |
| 值语义 | 比例 0.0~1.0 | 绝对档位 0~9 |

旧服务器说 v3，新客户端只听 v4 —— 于是握手勉强过去，但设备列表结构对不上，
客户端解析不出来，列表就一直是空的。

## 验证方法

没有安卓环境也能验证：把服务器逻辑用 Python 复刻，用**官方 buttplug JS 客户端 v5.0.2**
（spec v4，与 FunFlixDroid 内置引擎同源）实测。

```
服务器 (Python)  ──ws://127.0.0.1:12345──>  官方 buttplug v4 客户端
```

三个对照实验的结果：

| 服务器 | 结果 |
|---|---|
| 旧版 v3 结构（裸对象出站） | ❌ `TypeError: msgs is not iterable`，握手即崩 |
| 新版 v4 结构（裸对象出站） | ❌ 同上 —— 出站必须包数组 |
| 新版 v4 结构（数组包出站） | ✅ CONNECT_OK → 设备数=1 → 震动指令下发成功 → STOP_OK |

关键证据（v4 客户端的 `ButtplugMessageSorter.parseIncomingMessages`）：

```js
for (const x of msgs) { ... }   // msgs 直接迭代
```

服务器若发裸对象 `{"Ok":{...}}`，`msgs` 不是可迭代对象 → 直接抛异常。
规范原文也写了："Array Wrapper: Every message is wrapped in a single-element array"。

## 本次改动

### `app/src/main/java/com/yangcoiner/fn010bridge/ButtplugServer.kt`（重写）

完整切换到 Buttplug 消息规范 v4：

1. `ServerInfo` 用 `ProtocolVersionMajor/Minor`
2. `DeviceList.Devices` 改为 map
3. 设备改用 `DeviceFeatures`，`Output` 值为 `{Type: {Value: [min,max]}}`
4. `OutputCmd` → 按 `FeatureIndex` + `Command` 里的 `Vibrate`/`Oscillate` 分派
5. `StopCmd` → `FeatureIndex` 缺省停全部，指定则停单个特性
6. **所有出站消息包进 JSON 数组**
7. 入站同时兼容单对象和数组两种形式
8. 错误消息用 v4 的 `ErrorCode`（旧版是字符串 `ErrorType`）

### `app/src/main/java/com/yangcoiner/fn010bridge/BleManager.kt`（新增 3 个方法）

```kotlin
fun speedFromLevel(level: Int): Int        // 绝对档位 -> speed 字节
fun vibrateLevel(level: Int, manual: Boolean = false): Boolean
fun oscillateLevel(level: Int, manual: Boolean = false): Boolean
```

原来的 `vibrate(scalar)` / `oscillate(scalar)` 保持不变，App 内部手动滑条和校准功能不受影响。

## 兼容性

- **新版 FunFlixDroid**（intiface-engine v4）→ 已适配
- **旧版 FunFlixDroid**（只说 v3）→ **不再兼容**。v3 与 v4 的设备列表结构互不兼容，
  一个服务器无法同时用两种结构回应同一个 `RequestDeviceList`。

  如果还需要支持旧版，唯一的办法是运行两个实例（不同端口），分别用 v3 和 v4 协议。
  但既然新版已经覆盖绝大多数用户，建议直接用 v4。

## 验证状态

- ✅ 协议层：官方 buttplug v4 客户端实测全通（握手 / 枚举 / 震动 / 停止）
- ✅ 移植层：Kotlin 逻辑用 Python 影子实现复刻，同样实测全通
- ✅ 静态检查：0 错误（方法签名、字段名、括号配平、v3 残留扫描）
- ⏳ 编译与真机：待 GitHub Actions 构建 APK 后，用真实 FN010 验证 BLE 帧

## 预期 BLE 行为

v4 客户端下发 `OutputCmd { FeatureIndex: 0, Command: { Vibrate: { Value: 5 } } }`
应产生 BLE 帧 `ac 01 01 00 01 83`（speed = 0x1F + 0x19×4 = 131 = 0x83）。

停止时 `StopCmd` 应产生 `ac 01 00 00 01 00` 和 `ac 03 00 00 01 00`。
