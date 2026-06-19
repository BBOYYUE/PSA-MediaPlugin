# PSA MediaPlugin — 代码架构

## 文件结构

```
app/src/main/java/com/cusc/media/
├── MainActivity.java              ← 入口（透明跳板）
└── base/player/
    ├── MusicService.java          ← 核心：桥接器
    ├── MediaSessionListenerService.java ← 监听通知栏，获取 QQ音乐信息
    ├── PsaDdsService.java         ← DDS 客户端，监听云端音乐命令
    ├── MediaInfoCallback.java     ← 回调接口
    ├── AlbumArtServer.java        ← 内嵌 HTTP 服务器
    └── QueueManager.java          ← 播放队列管理
```

## 数据流

```
┌─────────────────────────────────────────────────────────────┐
│                        输入层                                │
│                                                             │
│  QQ音乐 APP ──(MediaNotification)──→  MediaSessionListener   │
│  DUI 云端  ──(WebSocket)─────────→  PsaDdsService           │
│  桌面卡片  ──(点击)──────────────→  MainActivity             │
│  语音助手  ──(MediaSession)──────→  MediaSession.Callback    │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                        核心调度层                             │
│                                                             │
│  ┌─────────────── MusicService ──────────────────────────┐  │
│  │                                                        │  │
│  │  onMediaInfoUpdated()  ← 接收 QQ音乐的歌名/歌手/封面     │  │
│  │  onPlaybackStateChanged() ← 接收 QQ音乐的播放状态        │  │
│  │  onMediaControllerChanged() ← 接收 QQ音乐的控制器        │  │
│  │                                                        │  │
│  │  ↓ 填充自己的 MediaSession  ↓                           │  │
│  │                                                        │  │
│  │  updateMediaMetadata()   → Launcher 卡片 读歌名封面      │  │
│  │  updatePlaybackState()   → Launcher 卡片 读进度条        │  │
│  │  updateSessionActivity() → Launcher 卡片 点哪跳哪        │  │
│  │  updateDisplayIcon()     → Launcher 卡片 显示什么图标    │  │
│  │                                                        │  │
│  │  MediaSession.Callback:                                 │  │
│  │    onPlay()  / onPause() / onSkipToNext() / ...         │  │
│  │    ↓ 转发给 mMediaController（QQ音乐）                   │  │
│  │    onPlayFromSearch() ← 语音搜索/PSA 内部调用           │  │
│  │                                                        │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                        输出层                                │
│                                                             │
│  → Launcher 卡片     （歌名、封面、进度、控制按钮）           │
│  → QQ音乐 APP        （播放、暂停、切歌、搜索）               │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 各组件职责

### 1. MusicService (`extends MediaBrowserServiceCompat implements MediaInfoCallback`)

整个插件的核心。同时扮演两个角色：
- **对 Launcher 来说**：是一个音乐播放器，提供歌曲信息和播放状态
- **对 QQ音乐来说**：是一个遥控器，转发用户的控制指令

启动时做的事（按顺序）：
1. 启动 AlbumArtServer（图片 HTTP 中转）
2. 恢复上次播放信息（SharedPreferences 持久化）
3. 创建 MediaSession + 设置播放状态（extras 注入 `audioType: "music"`）
4. 创建 QueueManager（播放队列）
5. 设置 MediaSession 回调（转发控制指令给 QQ音乐）
6. 注册 MediaSessionListenerService 回调
7. 推送元数据到 Launcher
8. 启动前台通知（保活）
9. 设置 sessionActivity（卡片点击跳 QQ音乐）
10. 启动 PsaDdsService（DDS 云端命令监听）

公开方法：
- `getMediaController()` — 给 PsaDdsService 用
- `launchMusicApp()` — 启动 QQ音乐（fallback）
- `getMediaSession()` — 获取 MediaSession

### 2. MediaSessionListenerService (`extends NotificationListenerService`)

监听系统通知栏。当 QQ音乐播放时，从通知中提取 `MediaSession.Token`，创建 `MediaController` 连过去，拿到：
- 歌名、歌手、时长
- 封面（优先 URI，URI 为空时回退到 Bitmap 落盘）
- 播放状态（通过 MediaController.Callback）

通过 `MediaInfoCallback` 接口回调给 MusicService。

去重保护：四个字段（title/artist/duration/albumArt）都不变时才 skip，避免重复推送。

### 3. PsaDdsService (`extends Service`)

新增的 DUI 云端命令监听。使用精减版 DDS SDK（9.6MB AAR），只保留网络通信模块。

初始化 DDS → 注册 CommandObserver → 收到命令 → 转发给 MusicService：
- `com.ileja.music.searchAndPlay` → 搜索并播放
- `com.ileja.music.pause` → 暂停
- `com.ileja.music.play` → 播放
- `com.ileja.music.next/previous` → 切歌

这是解决"语音搜歌不工作"的关键组件。

### 4. MainActivity (`extends Activity`)

单例（`singleTask`），透明（`Theme.Translucent.NoTitleBar`），不在最近任务中。

- 被 Launcher 启动 → 立即打开 QQ音乐 → `moveTaskToBack(true)` 退到后台
- 用户永远看不到这个界面
- 重复点击走 `onNewIntent`，避免重复创建

### 5. AlbumArtServer

内嵌 HTTP 服务器，端口自动分配（`ServerSocket(0)`）。

作用：QQ音乐有些 cover 是 `file://` 协议（如汽水音乐），Launcher 无法访问。这个 server 把 `file://` 转成 `http://127.0.0.1:PORT/xxx` 供 Launcher 读取。

### 6. QueueManager + MediaInfoCallback

- QueueManager：管理 MediaSession 的播放队列（固定首项 插件版本信息 + 当前歌曲）
- MediaInfoCallback：interface，定义 MediaSessionListenerService → MusicService 的回调方法

## 构建配置

```
app/build.gradle.kts
  ├── compileSdk 34, minSdk 28, targetSdk 34
  ├── Java 17 (源/目标)
  ├── Release 开启混淆+压缩
  ├── 依赖: androidx.media:1.7.0 + DDS mini AAR (本地)
  └── APK 大小: ~17MB
```
