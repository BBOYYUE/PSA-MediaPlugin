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

### 2.1 原厂架构（无 PSA MediaPlugin 时）

原厂没有独立的音乐 APP。多媒体是语音助手内置的 **`ileja` 音乐模块** 实现的：

```
云端 NLU (DUI)
  → skill: "智网音乐"
     → command.api: "com.ileja.music.searchAndPlay"
        → 语音助手本地 ileja 模块直接播放
           → 维护内部媒体状态: audioType = "web_music"
              → Launcher 读取并显示卡片
```

关键：**ileja 不是标准的 Android MediaSession**，它是语音助手进程内的私有模块。不对外暴露 MediaBrowserService。

语音控制走的是云端 NLU → 本地 ileja API：
- 暂停: `com.ileja.music.pause`
- 搜索: `com.ileja.music.searchAndPlay`
- 播放: `com.ileja.music.play`

### 2.2 PSA 安装后的架构

PSA MediaPlugin 创建了自己的 `MediaBrowserServiceCompat` + `MediaSession`：

```
QQ音乐 APP
  → 创建 MediaSession (歌名/封面/进度)
     ↓
PSA MediaPlugin (MediaBrowserServiceCompat)
  → 通过 NotificationListenerService 监听 QQ音乐
  → 创建自己的 MediaSession (冒充系统媒体服务)
     ↓
车机 Launcher
  → 连接 PSA MediaSession → 显示卡片
  → 用户点播放/暂停 → Callback → PSA → QQ音乐 ✅
     ↓
语音助手 (DUI)
  → 检测到 PSA MediaSession（外部媒体源）
  → musicControlPause() 检查 audioType → 为空 ❌
  → TTS "当前场景不支持"
```

核心概念：

**MediaSession** — Android 媒体控制中枢。每个音乐 APP 创建自己的 session，Launcher 选一个"活跃的"来显示卡片。

**MediaBrowserService** — PSA 继承它建了自己的 session，对 Launcher 伪装成"系统媒体服务"。Launcher 通过 `onGetRoot()` 连接，获取元数据。

**NotificationListenerService** — PSA 用它监听通知栏，从 QQ音乐的 MediaNotification 里提取 MediaSession.Token，然后创建 MediaController 连接过去，拿到歌名和播放状态。

### 2.3 对比

| | 原厂 (ileja) | PSA 接管后 |
|---|---|---|
| 播放器 | 语音助手内置 | QQ音乐 (第三方) |
| Launcher 卡片 | ileja 直接提供 | PSA MediaSession 转发 |
| audioType | `web_music` ✅ | 空 ❌ |
| 语音暂停 | `com.ileja.music.pause` ✅ | musicControlPause 失败 ❌ |
| 语音搜歌 | `com.ileja.music.searchAndPlay` ✅ | 无响应 ❌ |
| 语音播放/切歌 | ✅ | ✅ (走 MediaSession 转发) |

## 3. DUI SDK (思必驰) — 车机语音助手

Blue-i 3.0 的语音助手用的是思必驰 DUI 方案（不是 Google Assistant）：

```
你说"暂停"
  → 麦克风录音 → VAD 检测 → 云端 ASR → NLU 语义理解
     → 云端返回: {skill: "智网播控", command: {api: "com.ileja.music.pause"}}
        → 本地 CuscAgent 处理
           → 检查是否有外部 MediaSession 活跃
              → 有 → StateManager.musicControlPause()
                     → 检查 audioType → 空 → 失败 ❌
              → 无 → 直接用 ileja API 执行 ✅
```

关键组件：

| 组件 | 作用 |
|------|------|
| `CuscAgent` | 语音交互主控 |
| `StateManager` | 场景状态管理，musicControlPause/Play 在此 |
| `CloudDmKernel` | 云端对话管理 (WebSocket: wss://dds.dui.ai) |
| `CloudTtsKernel` | 云端 TTS 合成 |
| `ileja` | 内置音乐播放模块 |

关键云端 skill：

| skill | skillId | 作用 |
|-------|---------|------|
| 智网播控 | 2021111900000081 | 暂停/播放/切歌控制 |
| 智网音乐 | 2021110800000149 | 搜索和播放 |

**DUI 开放平台**: www.dui.ai — 产品 ID 279605823

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

## 6. 当前 PSA 插件状态

### 已完成的功能

| 功能 | 状态 |
|------|------|
| 桌面卡片显示歌名/封面/进度 | ✅ |
| 桌面卡片暂停/播放/切歌 | ✅ |
| 桌面卡片点击 → 打开 QQ音乐 | ✅ |
| 语音播放 | ✅ |
| 语音下一首/上一首 | ✅ |
| 本地编译 (JDK 17 + SDK 34) | ✅ |

### 待解决

| 问题 | 原因 | 可能方案 |
|------|------|----------|
| 语音暂停 | audioType 空 → musicControlPause 拒绝 | 在 PlaybackState extras 设 audioType=web_music |
| 语音搜歌 | 同上，且 ileja API 不可外部调用 | 同暂停方案 |

## 7. 推荐学习资源

**Android 车机官方文档:**
- https://source.android.com/docs/automotive — Android Automotive OS
- https://developer.android.com/training/cars — Car API 开发指南
- MediaSession 机制: https://developer.android.com/guide/topics/media-apps

**车机逆向/社区:**
- XDA Developers 车机板块
- 汽车之家/懂车帝论坛
- GitHub 搜索 `cusc` / `PSA` / `Blue-i` / `spm8666p1`

**DUI SDK:**
- https://www.dui.ai — 思必驰开放平台
- https://www.duiopen.com/docs — 开发文档

**关键技能点:**
1. `adb logcat` — 车机开发 80% 靠这个
2. MediaSession / MediaBrowserService 机制
3. `adb pull` / `adb push` 系统文件
4. `dumpsys` 分析系统状态
5. Android 签名机制 (apksigner / jarsigner)
