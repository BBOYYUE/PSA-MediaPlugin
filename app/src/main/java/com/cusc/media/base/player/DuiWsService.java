package com.cusc.media.base.player;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 轻量级 DUI WebSocket 客户端 — 不需要 DDS SDK，只用 OkHttp。
 * 连接语音助手同一产品 (279605823)，监听云命令。
 */
public class DuiWsService extends Service {
    private static final String TAG = "DuiWsService";

    // 复用语音助手的设备和产品 ID（apikey=null 已验证可行）
    private static final String DUI_URL = "wss://dds.dui.ai/dds/v3/prod" +
            "?serviceType=websocket" +
            "&productId=279605823" +
            "&deviceName=LDC973B42S3020865" +
            "&communicationType=fullDuplex";

    private final OkHttpClient mClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    private WebSocket mWebSocket;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final WebSocketListener mListener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.d(TAG, "DUI WS connected: " + response.code());
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject msg = new JSONObject(text);
                String topic = msg.optString("topic", "");
                if ("dm.output".equals(topic)) {
                    JSONObject dm = msg.optJSONObject("dm");
                    if (dm != null) {
                        JSONObject command = dm.optJSONObject("command");
                        if (command != null) {
                            String api = command.optString("api", "");
                            JSONObject param = command.optJSONObject("param");
                            String data = param != null ? param.toString() : "{}";
                            handleCommand(api, data);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Parse error", e);
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.e(TAG, "DUI WS failed: " + t.getMessage() + " code=" + (response != null ? response.code() : 0));
            mHandler.postDelayed(DuiWsService.this::connect, 5000);
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.d(TAG, "DUI WS closed: " + code + " " + reason);
            mHandler.postDelayed(DuiWsService.this::connect, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        connect();
    }

    private void connect() {
        if (mWebSocket != null) {
            mWebSocket.close(1000, "reconnect");
        }
        Request request = new Request.Builder().url(DUI_URL).build();
        mWebSocket = mClient.newWebSocket(request, mListener);
        Log.d(TAG, "Connecting to DUI...");
    }

    private void handleCommand(String api, String data) {
        Log.d(TAG, "Cloud command: " + api + " data=" + data);
        MusicService service = MusicService.getInstance();
        if (service == null) return;

        switch (api) {
            case "com.ileja.music.searchAndPlay": {
                try {
                    JSONObject p = new JSONObject(data);
                    String singer = p.optString("singerName", "");
                    String song = p.optString("songName", "");
                    String query = singer.isEmpty() ? song : (song.isEmpty() ? singer : singer + " " + song);
                    if (!query.isEmpty()) {
                        android.media.session.MediaController mc = service.getMediaController();
                        if (mc != null) {
                            mc.getTransportControls().playFromSearch(query, null);
                        } else {
                            service.launchMusicApp();
                        }
                        Log.d(TAG, "Search executed: " + query);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Search parse error", e);
                }
                break;
            }
            case "com.ileja.music.pause":
                service.getMediaSession().getController().getTransportControls().pause();
                break;
            case "com.ileja.music.play":
                service.getMediaSession().getController().getTransportControls().play();
                break;
            case "com.ileja.music.next":
                service.getMediaSession().getController().getTransportControls().skipToNext();
                break;
            case "com.ileja.music.previous":
                service.getMediaSession().getController().getTransportControls().skipToPrevious();
                break;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mWebSocket != null) mWebSocket.close(1000, "destroy");
        super.onDestroy();
    }
}
