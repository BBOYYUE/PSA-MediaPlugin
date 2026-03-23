package com.cusc.media.base.player;

import android.media.session.PlaybackState;

public interface MediaInfoCallback {
    void onMediaInfoUpdated(String title, String artist, long duration, String albumArtUri);
    void onPlaybackStateChanged(PlaybackState state);
    void onPackageChanged(String packageName);
}