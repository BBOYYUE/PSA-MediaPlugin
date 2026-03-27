package com.cusc.media.base.player;

import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class MediaSessionListenerService extends NotificationListenerService {
    private static final String TAG = "MusicProgressListener";
    private static final long MAX_CACHE_SIZE = 10 * 1024 * 1024; // 10MB
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private MediaController mMediaController;
    private String currentPlayingPackage;
    private static MediaSessionListenerService instance;
    private MediaInfoCallback mediaInfoCallback;

    // 缓存最后一次收到的媒体信息，供 MusicService 重新连接时立即恢复显示
    private String lastTitle;
    private String lastArtist;
    private long lastDuration;
    private String lastAlbumArtUri;
    private String lastPackageName;

    public static MediaSessionListenerService getInstance() {
        return instance;
    }

    public void setMediaInfoCallback(MediaInfoCallback callback) {
        this.mediaInfoCallback = callback;
        if (callback != null) {
            if (lastTitle != null) {
                callback.onMediaInfoUpdated(lastTitle, lastArtist, lastDuration, lastAlbumArtUri);
                Log.d(TAG, "Cached media info pushed to new callback: " + lastTitle + " - " + lastArtist);
            }
            // 同步推送当前 MediaController，确保 MusicService 重连后立即可以发送控制指令
            callback.onMediaControllerChanged(mMediaController);
            if (lastPackageName != null) {
                callback.onPackageChanged(lastPackageName);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // 若 MusicService 已在运行（比如本服务重启），主动让它重新注册回调，
        // 避免 mediaInfoCallback 为 null 导致媒体信息无法同步
        MusicService musicService = MusicService.getInstance();
        if (musicService != null) {
            Log.d(TAG, "MusicService already running, triggering callback re-register");
            musicService.reRegisterCallback();
        } else {
            // MusicService 尚未启动，正常拉起
            Intent serviceIntent = new Intent();
            serviceIntent.setClassName(
                    "com.cusc.media",
                    "com.cusc.media.base.player.MusicService"
            );
            startForegroundService(serviceIntent);
        }
    }

    @Override
    public void onDestroy() {
        // 先清理回调和引用，再关闭线程池
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mControllerCallback);
            mMediaController = null;
        }
        ioExecutor.shutdown();
        instance = null;
        super.onDestroy();
    }

    public void onMusicStateChanged(PlaybackState state) {
        if (state != null) {
            long currentPosition = state.getPosition();
            Log.d(TAG, "[" + currentPlayingPackage + "] Current position: " + currentPosition + " ms");
            if (mediaInfoCallback != null) {
                mediaInfoCallback.onPlaybackStateChanged(state);
            }
        }
    }

    // 专辑图片URI的获取和传递，并缓存供回调重连时使用
    // 优先读取 ALBUM_ART_URI（QQ音乐等），URI 为空时回退到读取 Bitmap 并落盘（汽水音乐等）
    public void onMusicMetadataChanged(MediaMetadata metadata) {
        if (metadata != null) {
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);

            // 1. 优先读取 URI（QQ音乐、网易云等直接提供 URI 的播放器）
            String albumArtUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);

            // 2. URI 为空时，回退到读取 Bitmap（汽水音乐等将封面直接嵌入 Bitmap 的播放器）
            if (albumArtUri == null) {
                Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                // 部分播放器使用 METADATA_KEY_ART 字段
                if (bitmap == null) {
                    bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
                }
                if (bitmap != null) {
                    Log.d(TAG, "[" + currentPlayingPackage + "] URI is null, falling back to Bitmap");
                    albumArtUri = saveBitmapToCache(bitmap, title, artist);
                }
            }

            // 四个字段均与上次一致时，属于播放器重复推送，直接跳过
            boolean unchanged = duration == lastDuration
                    && equals(title, lastTitle)
                    && equals(artist, lastArtist)
                    && equals(albumArtUri, lastAlbumArtUri);
            if (unchanged) {
                Log.d(TAG, "[" + currentPlayingPackage + "] Metadata unchanged, skip");
                return;
            }

            lastTitle = title;
            lastArtist = artist;
            lastDuration = duration;
            lastAlbumArtUri = albumArtUri;

            Log.d(TAG, "[" + currentPlayingPackage + "] Song: " + title + " - " + artist);
            Log.d(TAG, "[" + currentPlayingPackage + "] Total duration: " + duration + " ms");
            Log.d(TAG, "[" + currentPlayingPackage + "] Album art URI: " + (albumArtUri != null ? albumArtUri : "None"));

            if (mediaInfoCallback != null) {
                mediaInfoCallback.onMediaInfoUpdated(title, artist, duration, albumArtUri);
            }
        }
    }

    /**
     * 将 Bitmap 保存到应用缓存目录，返回 file:// URI 字符串。
     * 实际的写盘和清理操作在后台线程执行，避免阻塞主线程。
     */
    private String saveBitmapToCache(Bitmap bitmap, String title, String artist) {
        String safeTitle = (title != null) ? title : "";
        String safeArtist = (artist != null) ? artist : "";
        String fileName = "album_art_" + Math.abs((safeTitle + safeArtist).hashCode()) + ".jpg";
        File cacheFile = new File(getCacheDir(), fileName);
        String uri = cacheFile.toURI().toString();

        if (ioExecutor.isShutdown()) {
            return uri;
        }

        try {
            if (!cacheFile.exists()) {
                // 复制一份 Bitmap 引用，确保在异步线程处理时安全
                ioExecutor.execute(() -> {
                    if (!cacheFile.exists()) {
                        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                            Log.d(TAG, "Album art saved to cache: " + cacheFile.getAbsolutePath());
                            cleanupCache();
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to save album art bitmap to cache", e);
                        }
                    }
                });
            } else {
                // 已存在则仅更新访问时间
                ioExecutor.execute(() -> cacheFile.setLastModified(System.currentTimeMillis()));
            }
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Could not schedule cache task: executor is shut down");
        }

        return uri;
    }

    /**
     * 清理缓存目录，确保总大小不超过 MAX_CACHE_SIZE (10MB)。
     * 采用 LRU 策略，优先删除最久未使用的文件。
     */
    private void cleanupCache() {
        File cacheDir = getCacheDir();
        File[] files = cacheDir.listFiles((dir, name) -> name.startsWith("album_art_"));
        if (files == null || files.length == 0) return;

        long currentSize = 0;
        for (File file : files) {
            currentSize += file.length();
        }

        if (currentSize <= MAX_CACHE_SIZE) return;

        // 按最后修改时间排序（从旧到新）
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        for (File file : files) {
            long fileSize = file.length();
            if (file.delete()) {
                currentSize -= fileSize;
                Log.d(TAG, "Cache size limit exceeded, deleted: " + file.getName());
            }
            if (currentSize <= MAX_CACHE_SIZE) break;
        }
    }

    private static boolean equals(String a, String b) {
        return Objects.equals(a, b);
    }

    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            onMusicStateChanged(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onMusicMetadataChanged(metadata);
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "Notification listener service connected");
        checkActiveMediaSessions();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        handleMediaNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (isMediaNotification(sbn)) {
            if (mMediaController != null && sbn.getPackageName().equals(currentPlayingPackage)) {
                mMediaController.unregisterCallback(mControllerCallback);
                mMediaController = null;
                currentPlayingPackage = null;
                lastPackageName = null;
                if (mediaInfoCallback != null) {
                    mediaInfoCallback.onMediaControllerChanged(null);
                    mediaInfoCallback.onPackageChanged(null);
                }
                Log.d(TAG, "Media session disconnected: " + sbn.getPackageName());
            }
        }
    }

    private void checkActiveMediaSessions() {
        StatusBarNotification[] activeNotifications = getActiveNotifications();
        if (activeNotifications == null) return;
        // 遍历找到第一个带媒体会话的通知
        for (StatusBarNotification sbn : activeNotifications) {
            if (isMediaNotification(sbn)) {
                handleMediaNotification(sbn);
                return;
            }
        }
    }

    private void handleMediaNotification(StatusBarNotification sbn) {
        if (!isMediaNotification(sbn)) {
            return;
        }

        try {
            Bundle extras = sbn.getNotification().extras;
            MediaSession.Token token = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);

            if (token == null) return;

            boolean isSamePackage = sbn.getPackageName().equals(currentPlayingPackage);
            boolean tokenChanged = mMediaController != null && !mMediaController.getSessionToken().equals(token);

            if (isSamePackage && !tokenChanged) {
                return;
            }

            if (mMediaController != null) {
                mMediaController.unregisterCallback(mControllerCallback);
            }

            mMediaController = new MediaController(this, token);
            mMediaController.registerCallback(mControllerCallback);
            currentPlayingPackage = sbn.getPackageName();
            lastPackageName = currentPlayingPackage;
            if (mediaInfoCallback != null) {
                mediaInfoCallback.onMediaControllerChanged(mMediaController);
                mediaInfoCallback.onPackageChanged(currentPlayingPackage);
            }
            Log.d(TAG, "Connected to media session: " + currentPlayingPackage + (tokenChanged ? " (Token updated)" : ""));

            PlaybackState state = mMediaController.getPlaybackState();
            MediaMetadata metadata = mMediaController.getMetadata();
            if (state != null) onMusicStateChanged(state);
            if (metadata != null) onMusicMetadataChanged(metadata);
        } catch (Exception e) {
            Log.e(TAG, "Error handling media notification", e);
        }
    }

    private boolean isMediaNotification(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        return notification != null && notification.extras != null
                && notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
    }
}
