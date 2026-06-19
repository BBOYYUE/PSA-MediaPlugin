package com.cusc.media.base.player;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.aispeech.dui.dds.DDS;
import com.aispeech.dui.dds.DDSAuthListener;
import com.aispeech.dui.dds.DDSConfig;
import com.aispeech.dui.dds.DDSInitListener;
import com.aispeech.dui.dsk.duiwidget.CommandObserver;

import org.json.JSONObject;

/**
 * DDS SDK 客户端 — 使用精减版 SDK (9.6MB)，监听云端音乐命令。
 */
public class PsaDdsService extends Service {
    private static final String TAG = "PsaDdsService";

    private static final String[] MUSIC_COMMANDS = {
            "com.ileja.music.searchAndPlay",
            "com.ileja.music.pause",
            "com.ileja.music.play",
            "com.ileja.music.next",
            "com.ileja.music.previous",
    };

    private DDSInitListener mInitListener = new DDSInitListener() {
        @Override
        public void onInitComplete(boolean isFull) {
            Log.d(TAG, "DDS init complete: " + isFull);
            if (isFull) registerMusicObserver();
        }
        @Override
        public void onError(int what, String msg) {
            Log.e(TAG, "DDS init error: " + what + " " + msg);
        }
    };

    private DDSAuthListener mAuthListener = new DDSAuthListener() {
        @Override public void onAuthSuccess() { Log.d(TAG, "DDS auth success"); }
        @Override public void onAuthFailed(String id, String msg) { Log.e(TAG, "DDS auth failed: " + id); }
    };

    private CommandObserver mCmd = new CommandObserver() {
        @Override
        public void onCall(String command, String data) {
            Log.d(TAG, "CMD: " + command + " " + data);
            handle(command, data);
        }
    };

    @Override public void onCreate() { super.onCreate(); initDDS(); }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    private void initDDS() {
        try {
            DDS.getInstance().setDebugMode(2);
            DDSConfig c = new DDSConfig();
            c.addConfig(DDSConfig.K_PRODUCT_ID, "279605823");
            DDS.getInstance().init(getApplicationContext(), c, mInitListener, mAuthListener);
            Log.d(TAG, "DDS init started");
        } catch (Exception e) { Log.e(TAG, "init failed", e); }
    }

    private void registerMusicObserver() {
        try {
            DDS.getInstance().getAgent().subscribe(MUSIC_COMMANDS, mCmd);
            Log.d(TAG, "Music commands registered");
        } catch (Exception e) { Log.e(TAG, "subscribe failed", e); }
    }

    private void handle(String cmd, String data) {
        MusicService s = MusicService.getInstance();
        if (s == null) return;
        switch (cmd) {
            case "com.ileja.music.searchAndPlay": doSearch(data, s); break;
            case "com.ileja.music.pause": s.getMediaSession().getController().getTransportControls().pause(); break;
            case "com.ileja.music.play": s.getMediaSession().getController().getTransportControls().play(); break;
            case "com.ileja.music.next": s.getMediaSession().getController().getTransportControls().skipToNext(); break;
            case "com.ileja.music.previous": s.getMediaSession().getController().getTransportControls().skipToPrevious(); break;
        }
    }

    private void doSearch(String data, MusicService s) {
        try {
            JSONObject p = new JSONObject(data);
            String q = p.optString("singerName","") + " " + p.optString("songName","");
            q = q.trim();
            if (q.isEmpty()) q = p.optString("operation","");
            if (!q.isEmpty()) {
                android.media.session.MediaController mc = s.getMediaController();
                if (mc != null) mc.getTransportControls().playFromSearch(q, null);
                else s.launchMusicApp();
            }
        } catch (Exception e) { Log.e(TAG, "search error", e); }
    }

    @Override public void onDestroy() {
        try { DDS.getInstance().getAgent().unSubscribe(mCmd); DDS.getInstance().release(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
