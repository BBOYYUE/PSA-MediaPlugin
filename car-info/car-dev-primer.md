# 车机开发入门科普

> 基于 PSA Blue-i 3.0 (Android 9, MTK spm8666p1_64) 的实战经验

---

## 1. 车机 Android vs 手机 Android

车机跑的是 **Android Automotive**，不是手机版 Android 接个 CarPlay/Android Auto。

关键差异：

- **无 Google Play / GMS** — 没有 Google 服务框架，APP 通过厂商渠道分发
- **系统应用可更新** — APP 装在 `/system/app/` 下，有平台签名就能更新
- **所有 APP 都是 system uid** — 权限高，但这把车机搞得像一个大应用
- **Launcher 是厂商定制** — 桌面长得和手机不一样，卡片组件是车机特有

车机 Android 多了个 **Car Service** 框架层：

```
APP 层:     语音助手 / 多媒体 / 导航 / 空调 ...
             |
Car API:    CarAudioManager / CarCabinManager / CarPropertyManager ...
             |
Car Service: 音频路由 / 车辆属性 / 驾驶安全策略 ...
             |
HAL 层:    车辆总线 (CAN Bus) 通信
```

## 2. 车机多媒体是怎么工作的

```mermaid
QQ音乐 APP
  → 创建 MediaSession (发送歌名/封面/进度)
     ↓
PSA MediaPlugin (MediaBrowserServiceCompat)
  → 监听 QQ音乐的 MediaSession (通过 NotificationListenerService)
  → 创建自己的 MediaSession (替换系统默认媒体服务)
     ↓
车机 Launcher
  → MediaBrowser 客户端连接 PSA MediaSession
  → 显示卡片: 歌名、封面、进度条、控制按钮
  → 用户点播放/暂停 → Callback → PSA → QQ音乐
```

核心概念：

**MediaSession** — Android 媒体控制中枢。每个音乐 APP 创建自己的 session，Launcher 选一个"活跃的"来显示卡片。

**MediaBrowserService** — PSA 继承它建了自己的 session，对 Launcher 伪装成"系统媒体服务"。Launcher 通过 `onGetRoot()` 连接，获取元数据。

**NotificationListenerService** — PSA 用它监听通知栏，从 QQ音乐的 MediaNotification 里提取 MediaSession.Token，然后创建 MediaController 连接过去，拿到歌名和播放状态。

## 3. DUI SDK (思必驰) — 车机语音助手

Blue-i 3.0 的语音助手用的是思必驰 DUI 方案（不是 Google Assistant）：

```
你说"暂停"
  → 麦克风录音 → VAD 检测 → 云端 ASR → NLU 语义理解
     → 返回意图: {action: "pause", target: "media"}
        → StateManager.musicControlPause()
           → 检查 audioType、mediaPkg、播放状态
              → 通过 → MediaSession.getTransportControls().pause()
              → 不通过 → TTS "当前场景不支持"
```

关键组件：

| 组件 | 作用 |
|------|------|
| `CuscAgent` | 语音交互主控 |
| `StateManager` | 场景状态管理，判断当前是什么场景 |
| `CloudDmKernel` | 云端对话管理 (WebSocket: wss://dds.dui.ai) |
| `CloudTtsKernel` | 云端 TTS 合成 |
| `CarCabin` | 车机总线状态管理 (audioType 来源) |

**DUI 开放平台**: www.dui.ai — 管理产品配置、技能(NLU)、唤醒词等。产品 ID 绑定到设备。

## 4. 系统签名 — 为什么重要

车机系统 APP 需要 **platform 签名** 才能：
- 申请 `BIND_VOICE_INTERACTION` 等系统权限
- 替换 `/system/app/` 下的应用
- 访问 Car Service 的受保护 API

签名的密钥在车机厂商（PSA/神龙汽车）手里。
如果车机是 `test-keys` 编译的，可以用 AOSP 的通用 platform 签名。

检查方法：
```bash
adb shell getprop ro.build.tags    # test-keys vs release-keys
adb shell dumpsys package com.cusc.misc | grep signatures
```

## 5. CarCabin — 车辆属性总线

Android Automotive 通过 **Vehicle HAL** 读取车辆 CAN 总线数据。
应用层通过 **CarPropertyManager** 获取车辆属性。

PSA 车机用 `CarCabin` 封装了这一层：
```
语音助手 StateManager
  → CarCabin GroupId:2 ChannelId:11
     → 车辆 MCU
        → 获取媒体源类型 (audioType)
```

每个 `GroupId:ChannelId` 对应一个车辆功能域（媒体、空调、座椅等）。

## 6. 推荐学习资源

**Android 车机官方文档:**
- https://source.android.com/docs/automotive — Android Automotive OS
- https://developer.android.com/training/cars — Car API 开发指南
- MediaSession 机制: https://developer.android.com/guide/topics/media-apps

**车机逆向/社区:**
- XDA Developers 车机板块 — 大量实战经验
- 汽车之家/懂车帝论坛 — 车机破解教程
- GitHub 搜索 `cusc` / `PSA` / `Blue-i` / `spm8666p1` — 相关开源项目

**DUI SDK:**
- https://www.dui.ai — 思必驰开放平台（需要注册）
- https://www.duiopen.com/docs — 开发文档（部分需要登录）

**关键技能点:**
1. 会看 `adb logcat` 日志 — 车机开发80%靠这个
2. 理解 MediaSession/MediaBrowserService 机制
3. 会 `adb pull` / `adb push` 系统文件
4. 会用 `dumpsys` 分析系统状态
5. 了解 Android 签名机制 (apksigner / jarsigner)
