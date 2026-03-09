package com.cusc.media.base.player;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.core.app.NotificationCompat;

import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import com.bandwa.openadb.service.IMediaControlService;

import java.util.List;

public class MusicService extends MediaBrowserServiceCompat implements MediaInfoCallback {
    private static final String TAG = "SimpleMusicService";
    private static final String MY_MEDIA_ROOT_ID = "media_root_id";
    private static final String CHANNEL_ID = "channel_1";
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder stateBuilder;
    private QueueManager mQueueManager;
    private IMediaControlService mediaControlService;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mediaControlService = IMediaControlService.Stub.asInterface(service);
            Log.d(TAG, "MediaControlService connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mediaControlService = null;
            Log.d(TAG, "MediaControlService disconnected");
        }
    };

    // 存储从MediaSessionListenerService获取的最新媒体信息
    private String latestTitle = "默认歌曲";
    private String latestArtist = "默认歌手";
    private long latestDuration = 180000; // 默认3分钟
    private String latestAlbumArtUri = null;
    // 用于生成唯一的mediaId（默认值避免桌面读取时为 null）
    private String currentMediaId = "0";

    private static final String PREFS_NAME = "MusicServicePrefs";
    private static final String PREF_KEY_TITLE = "last_title";
    private static final String PREF_KEY_ARTIST = "last_artist";
    private static final String PREF_KEY_DURATION = "last_duration";
    private static final String PREF_KEY_ALBUM_ART = "last_album_art";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

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
                                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f);
        mediaSession.setPlaybackState(stateBuilder.build());

        // 步骤3：初始化QueueManager
        mQueueManager = new QueueManager(this);

        // 步骤4：设置MediaSession回调
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                super.onPlay();
                Log.d(TAG, "onPlay");
                if (mediaControlService != null) {
                    try {
                        mediaControlService.playPause();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void onPause() {
                super.onPause();
                Log.d(TAG, "onPause");
                if (mediaControlService != null) {
                    try {
                        mediaControlService.playPause();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
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
                if (mediaControlService != null) {
                    try {
                        mediaControlService.next();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(TAG, "Next");
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                if (mediaControlService != null) {
                    try {
                        mediaControlService.previous();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                Log.d(TAG, "Previous");
            }
        });

        // 步骤5：注册MediaSessionListenerService的回调
        registerMediaInfoCallback();

        // 步骤6：推送元数据
        updateMediaMetadata();

        // 步骤7：前台通知
        initNotification();

        Intent intent = new Intent("com.bandwa.openadb.service.BIND_MEDIA_CONTROL");
        intent.setPackage("com.bandwa.openadb");
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // 注册媒体信息回调
    private void registerMediaInfoCallback() {
        MediaSessionListenerService listenerService = MediaSessionListenerService.getInstance();
        if (listenerService != null) {
            listenerService.setMediaInfoCallback(this);
            Log.d(TAG, "Registered media info callback");
        } else {
            Log.w(TAG, "MediaSessionListenerService not started, cannot register callback");
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
        currentMediaId = String.valueOf(Math.abs(uniqueKey.hashCode()));

        // 创建媒体项并更新队列
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(currentMediaId)
                .setTitle(latestTitle)
                .setSubtitle(latestArtist)
                .setIconUri(latestAlbumArtUri != null ? android.net.Uri.parse(latestAlbumArtUri) : null)
                .build();

        MediaBrowserCompat.MediaItem mediaItem = new MediaBrowserCompat.MediaItem(
                description,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        );

        // 更新队列（只包含当前播放的歌曲）
        mQueueManager.updateCurrentSong(mediaItem);

        // 更新MediaSession元数据
        updateMediaMetadata();
        
        // 持久化保存
        saveLastMediaInfo();
    }

    @Override
    public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
        if (state == null) return;
        
        // 使用带 updateTime 的 setState 方法，确保进度条同步准确
        // 直接透传原始 PlaybackState 的最后更新时间
        stateBuilder.setState(state.getState(), state.getPosition(), state.getPlaybackSpeed(), state.getLastPositionUpdateTime());
        mediaSession.setPlaybackState(stateBuilder.build());
        Log.d(TAG, "Sync playback state: state=" + state.getState() + ", pos=" + state.getPosition() + ", lastUpdateTime=" + state.getLastPositionUpdateTime());
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
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, latestAlbumArtUri);
        }

        MediaMetadataCompat metadata = metadataBuilder.build();
        mediaSession.setMetadata(metadata);
        Log.d(TAG, "Update MediaSession metadata: " + latestTitle + "-" + latestArtist + ", album art: " + latestAlbumArtUri);
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
    public void onDestroy() {
        super.onDestroy();
        if (serviceConnection != null) {
            unbindService(serviceConnection);
        }
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