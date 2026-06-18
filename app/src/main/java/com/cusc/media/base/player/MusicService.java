package com.cusc.media.base.player;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.media.session.MediaController;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.annotation.RequiresApi;
import androidx.media.MediaBrowserServiceCompat;
import androidx.core.app.NotificationCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

public class MusicService extends MediaBrowserServiceCompat implements MediaInfoCallback {
    private static final String TAG = "SimpleMusicService";
    private static final String MY_MEDIA_ROOT_ID = "media_root_id";
    private static final String CHANNEL_ID = "channel_1";

    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder stateBuilder;
    private QueueManager mQueueManager;
    private AlbumArtServer mAlbumArtServer;

    /** 当前目标媒体 App 的 MediaController，由 MediaSessionListenerService 回调注入 */
    private MediaController mMediaController;

    /** 单例，供 MediaSessionListenerService 在启动后主动触发回调注册 */
    private static MusicService instance;

    private MusicServiceCallback mMusicServiceCallback;

    public interface MusicServiceCallback {
        void onPackageChanged(String packageName);
    }

    public void setMusicServiceCallback(MusicServiceCallback callback) {
        this.mMusicServiceCallback = callback;
        if (callback != null && lastPackageName != null) {
            callback.onPackageChanged(lastPackageName);
        }
    }

    public static MusicService getInstance() {
        return instance;
    }

    // 存储从MediaSessionListenerService获取的最新媒体信息
    private String latestTitle = "默认歌曲";
    private String latestArtist = "默认歌手";
    private long latestDuration = 180000; // 默认3分钟
    private String latestAlbumArtUri = null;
    private String lastPackageName = null;
    // 用于生成唯一的mediaId（默认值避免桌面读取时为 null）
    private String currentMediaId = "0";
    // 缓存的 APP 显示图标 URL
    private String displayIconUrl = null;

    private static final String PREFS_NAME = "MusicServicePrefs";
    private static final String PREF_KEY_TITLE = "last_title";
    private static final String PREF_KEY_ARTIST = "last_artist";
    private static final String PREF_KEY_DURATION = "last_duration";
    private static final String PREF_KEY_ALBUM_ART = "last_album_art";

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "onCreate");

        // 初始化并启动 HTTP 服务
        mAlbumArtServer = new AlbumArtServer(this);
        mAlbumArtServer.start();

        // 步骤0：恢复上次播放的元数据
        restoreLastMediaInfo();

        // 步骤1：初始化MediaSession
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS |
                        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS
        );
        setSessionToken(mediaSession.getSessionToken());

        // 步骤2：初始化播放状态
        stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                                PlaybackStateCompat.ACTION_PAUSE |
                                PlaybackStateCompat.ACTION_STOP |
                                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                PlaybackStateCompat.ACTION_SEEK_TO |
                                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f);
        // 尝试给语音助手提供 audioType 线索
        Bundle hintExtras = new Bundle();
        hintExtras.putString("audioType", "music");
        hintExtras.putString("com.cusc.media.type", "music");
        // 标记为活跃媒体会话
        hintExtras.putBoolean("android.media.playback.isMusic", true);
        stateBuilder.setExtras(hintExtras);
        // 设置当前活跃的队列项，帮助系统识别媒体状态
        stateBuilder.setActiveQueueItemId(0);
        mediaSession.setPlaybackState(stateBuilder.build());

        // 步骤3：初始化QueueManager
        mQueueManager = new QueueManager(this);

        // 步骤4：设置MediaSession回调
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                Log.d(TAG, "onPlay");
                if (mMediaController != null) {
                    mMediaController.getTransportControls().play();
                } else {
                    // 没有活跃 APP，尝试启动 QQ音乐
                    launchMusicApp();
                }
            }

            @Override
            public void onPause() {
                super.onPause();
                Log.d(TAG, "onPause");
                if (mMediaController != null) {
                    mMediaController.getTransportControls().pause();
                }
            }

            @Override
            public void onStop() {
                super.onStop();
                Log.d(TAG, "onStop");
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                Log.d(TAG, "Next");
                if (mMediaController != null) {
                    mMediaController.getTransportControls().skipToNext();
                }
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                Log.d(TAG, "Previous");
                if (mMediaController != null) {
                    mMediaController.getTransportControls().skipToPrevious();
                }
            }

            /**
             * 处理语音助理的搜索播放指令（如"播放周杰伦"）。
             * 优先通过 MediaController 转发给当前活跃的 APP，
             * 若无活跃 APP 则用 Intent 启动 QQ音乐。
             */
            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                Log.d(TAG, "onPlayFromSearch: query=" + query);
                if (query == null || query.isEmpty()) return;

                // 优先：通过 MediaController 的 TransportControls 转发给当前 APP
                // 这比 Intent 更可靠，因为走的是 MediaSession 协议
                if (mMediaController != null) {
                    try {
                        mMediaController.getTransportControls().playFromSearch(query, extras);
                        Log.d(TAG, "Forwarded search via TransportControls: " + query);
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "TransportControls.playFromSearch failed, fallback to Intent", e);
                    }
                }

                // Fallback：没有活跃 APP，用 Intent 启动
                String targetPkg = resolveFallbackMusicPackage();
                if (targetPkg == null) return;

                Intent searchIntent = new Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH");
                searchIntent.setPackage(targetPkg);
                searchIntent.putExtra(android.app.SearchManager.QUERY, query);
                searchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                try {
                    startActivity(searchIntent);
                    Log.d(TAG, "Launched search via Intent to " + targetPkg + ": " + query);
                } catch (Exception e) {
                    Log.w(TAG, "MEDIA_PLAY_FROM_SEARCH not supported by " + targetPkg + ", launching app instead");
                    Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPkg);
                    if (launchIntent != null) {
                        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launchIntent);
                    }
                }
            }
        });

        // 步骤5：注册MediaSessionListenerService的回调
        registerMediaInfoCallback();

        // 步骤6：推送元数据
        updateMediaMetadata();

        // 步骤7：前台通知
        initNotification();

        // 步骤8：初始化 sessionActivity（fallback 到 QQ音乐），使桌面卡片点击可用
        updateSessionActivity(mMediaController);
    }

    /**
     * 由 MediaSessionListenerService 在其 onCreate 完成后主动调用，
     * 确保 MusicService 重启后能及时重新注册回调，避免 callback 为 null 导致数据丢失。
     */
    public void reRegisterCallback() {
        Log.d(TAG, "reRegisterCallback called by MediaSessionListenerService");
        registerMediaInfoCallback();
    }

    // 注册媒体信息回调
    private void registerMediaInfoCallback() {
        MediaSessionListenerService listenerService = MediaSessionListenerService.getInstance();
        if (listenerService != null) {
            listenerService.setMediaInfoCallback(this);
            Log.d(TAG, "Registered media info callback");
        } else {
            // MediaSessionListenerService 尚未启动，尝试启动它；
            // 启动完成后它的 onCreate 会反向调用 reRegisterCallback() 完成注册
            Log.w(TAG, "MediaSessionListenerService not started, starting it...");
            startService(new Intent(this, MediaSessionListenerService.class));
        }
    }

    private void restoreLastMediaInfo() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        latestTitle = prefs.getString(PREF_KEY_TITLE, "默认歌曲");
        latestArtist = prefs.getString(PREF_KEY_ARTIST, "默认歌手");
        latestDuration = prefs.getLong(PREF_KEY_DURATION, 180000);
        latestAlbumArtUri = prefs.getString(PREF_KEY_ALBUM_ART, null);
        
        // 恢复 currentMediaId，确保排重逻辑正常
        String uniqueKey = latestTitle + latestArtist;
        currentMediaId = String.valueOf(Math.abs(uniqueKey.hashCode()));
        
        Log.d(TAG, "Restored media info: " + latestTitle + " - " + latestArtist);
    }

    private void saveLastMediaInfo() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_KEY_TITLE, latestTitle)
                .putString(PREF_KEY_ARTIST, latestArtist)
                .putLong(PREF_KEY_DURATION, latestDuration)
                .putString(PREF_KEY_ALBUM_ART, latestAlbumArtUri)
                .apply();
        Log.d(TAG, "Saved media info to prefs");
    }

    @Override
    public void onMediaInfoUpdated(String title, String artist, long duration, String albumArtUri) {
        Log.d(TAG, "Received latest media info: " + title + "-" + artist + ", duration: " + duration);

        // 更新本地存储的最新媒体信息
        if (title != null) this.latestTitle = title;
        if (artist != null) this.latestArtist = artist;
        if (duration > 0) this.latestDuration = duration;
        this.latestAlbumArtUri = albumArtUri;

        // 使用 title + artist 的哈希值作为 mediaId，保证同一首歌 ID 不变
        // 避免因 ID 变化导致 UI 频繁刷新或专辑图重新加载
        String uniqueKey = latestTitle + latestArtist;
        String newMediaId = String.valueOf(Math.abs(uniqueKey.hashCode()));
        boolean songChanged = !newMediaId.equals(currentMediaId);
        currentMediaId = newMediaId;

        // 如果是 file:// URI，转换为 HTTP URL 供 Launcher 读取
        String displayUri = mAlbumArtServer.getHttpUrl(latestAlbumArtUri);

        // 创建媒体项并更新队列
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(currentMediaId)
                .setTitle(latestTitle)
                .setSubtitle(latestArtist)
                .setIconUri(displayUri != null ? android.net.Uri.parse(displayUri) : null)
                .build();

        MediaBrowserCompat.MediaItem mediaItem = new MediaBrowserCompat.MediaItem(
                description,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        );

        // 更新队列（只包含当前播放的歌曲）
        mQueueManager.updateCurrentSong(mediaItem);

        // 更新MediaSession元数据
        updateMediaMetadata();
        
        // 仅在歌曲切换时持久化，避免同一首歌的重复元数据回调触发多余磁盘写入
        if (songChanged) {
            saveLastMediaInfo();
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
        if (state == null) return;
        
        // 使用带 updateTime 的 setState 方法，确保进度条同步准确
        // 同时拷贝原始 PlaybackState 的 extras，语音助手依赖其中的字段
        stateBuilder.setState(state.getState(), state.getPosition(), state.getPlaybackSpeed(), state.getLastPositionUpdateTime());
        Bundle merged = state.getExtras() != null ? new Bundle(state.getExtras()) : new Bundle();
        merged.putString("audioType", "music");
        merged.putBoolean("android.media.playback.isMusic", true);
        stateBuilder.setExtras(merged);
        stateBuilder.setActiveQueueItemId(0);
        mediaSession.setPlaybackState(stateBuilder.build());
        Log.d(TAG, "Sync playback state: state=" + state.getState() + ", pos=" + state.getPosition() + ", lastUpdateTime=" + state.getLastPositionUpdateTime());
    }

    @Override
    public void onPackageChanged(String packageName) {
        this.lastPackageName = packageName;
        if (mMusicServiceCallback != null) {
            mMusicServiceCallback.onPackageChanged(packageName);
        }
    }

    @Override
    public void onMediaControllerChanged(MediaController controller) {
        mMediaController = controller;
        Log.d(TAG, "MediaController updated: " + (controller != null ? controller.getPackageName() : "null"));
        // 动态更新 sessionActivity：点击桌面卡片时打开当前正在播放的 APP
        updateSessionActivity(controller);
    }

    /**
     * 设置 MediaSession 的 sessionActivity，使桌面卡片点击时启动当前音乐 APP。
     * 若无活跃 APP，fallback 到 QQ音乐。
     */
    private void updateSessionActivity(MediaController controller) {
        String targetPkg = null;
        if (controller != null) {
            targetPkg = controller.getPackageName();
        }
        // fallback：尝试 QQ音乐车机版 / 标准版
        if (targetPkg == null) {
            targetPkg = resolveFallbackMusicPackage();
        }
        if (targetPkg == null) return;

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPkg);
        if (launchIntent == null) {
            Log.w(TAG, "No launch intent for package: " + targetPkg);
            return;
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, launchIntent, flags);
        mediaSession.setSessionActivity(pi);
        Log.d(TAG, "SessionActivity set to: " + targetPkg);

        // 同时更新 MediaSession 的显示图标为目标 APP 的图标
        updateDisplayIcon(targetPkg);
    }

    /**
     * 按优先级寻找可用的音乐 APP 包名：QQ音乐车机版 → QQ音乐标准版 → null
     */
    private String resolveFallbackMusicPackage() {
        String[] candidates = {
                "com.tencent.qqmusiccar",  // QQ音乐车机版
                "com.tencent.qqmusic",       // QQ音乐标准版（fallback）
        };
        for (String pkg : candidates) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                Log.d(TAG, "Fallback music package resolved: " + pkg);
                return pkg;
            } catch (Exception ignored) {
            }
        }
        Log.w(TAG, "No fallback music package found");
        return null;
    }

    /** 启动默认音乐 APP，供 onPlay / onPlayFromSearch fallback 使用 */
    private void launchMusicApp() {
        String targetPkg = resolveFallbackMusicPackage();
        if (targetPkg == null) return;
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPkg);
        if (launchIntent != null) {
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(launchIntent);
                Log.d(TAG, "Launched music app: " + targetPkg);
            } catch (Exception e) {
                Log.w(TAG, "Failed to launch " + targetPkg, e);
            }
        }
    }

    /**
     * 提取目标 APP 的图标，保存到缓存并通过 HTTP 提供给 MediaSession，
     * 使桌面卡片显示该 APP 的图标而非默认图标。
     */
    private void updateDisplayIcon(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            BitmapDrawable drawable = (BitmapDrawable) pm.getApplicationIcon(ai);
            Bitmap bitmap = drawable.getBitmap();

            String fileName = "icon_" + packageName.replace('.', '_') + ".png";
            File iconFile = new File(getCacheDir(), fileName);
            if (!iconFile.exists()) {
                FileOutputStream fos = new FileOutputStream(iconFile);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
            }

            int port = mAlbumArtServer.getPort();
            if (port > 0) {
                String iconUrl = "http://127.0.0.1:" + port + "/" + fileName;
                displayIconUrl = iconUrl;
                // 刷新 MediaSession metadata，让新图标生效
                updateMediaMetadata();
                Log.d(TAG, "Display icon updated to: " + iconUrl);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to update display icon for " + packageName, e);
        }
    }

    @SuppressLint("ForegroundServiceType")
    private void initNotification() {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID).build();
        startForeground(1, notification);
    }

    private void updatePlaybackState(int state) {
        stateBuilder.setState(state, 0, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());
    }

    private void updateMediaMetadata() {
        String mediaId = (currentMediaId != null && !currentMediaId.isEmpty()) ? currentMediaId : "0";
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, latestTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, latestArtist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, latestDuration);

        if (latestAlbumArtUri != null) {
            String httpUrl = mAlbumArtServer.getHttpUrl(latestAlbumArtUri);
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, httpUrl);
        }

        // 设置 APP 显示图标（桌面卡片图标）
        if (displayIconUrl != null) {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, displayIconUrl);
        }

        // 给车机系统提供音频类型线索
        metadataBuilder.putString("android.media.metadata.AUDIO_TYPE", "music");
        metadataBuilder.putLong("android.media.metadata.CONTENT_TYPE", 2L); // CONTENT_TYPE_MUSIC = 2

        MediaMetadataCompat metadata = metadataBuilder.build();
        mediaSession.setMetadata(metadata);
        Log.d(TAG, "Update MediaSession metadata: " + latestTitle + "-" + latestArtist + ", album art: " + mAlbumArtServer.getHttpUrl(latestAlbumArtUri));
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.d(TAG, "onGetRoot: clientPackageName=" + clientPackageName);
        return new BrowserRoot(MY_MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        Log.d(TAG, "onLoadChildren");
        result.sendResult(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return super.onBind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            android.view.KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (event != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                        || event.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    Log.d(TAG, "MEDIA_BUTTON pause received");
                    if (mMediaController != null) {
                        android.media.session.PlaybackState state = mMediaController.getPlaybackState();
                        if (state != null && state.getState() == PlaybackStateCompat.STATE_PLAYING) {
                            mMediaController.getTransportControls().pause();
                        } else {
                            mMediaController.getTransportControls().play();
                        }
                    }
                } else if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_NEXT) {
                    if (mMediaController != null) mMediaController.getTransportControls().skipToNext();
                } else if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    if (mMediaController != null) mMediaController.getTransportControls().skipToPrevious();
                }
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAlbumArtServer != null) {
            mAlbumArtServer.stop();
        }
        instance = null;
        mMediaController = null;
        MediaSessionListenerService listenerService = MediaSessionListenerService.getInstance();
        if (listenerService != null) {
            listenerService.setMediaInfoCallback(null);
        }
        mediaSession.release();
    }

    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }
}
