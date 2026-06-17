package com.cusc.media;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Launcher 硬编码启动这个类，改不了。
 * 唯一出路：进来就跳 QQ音乐，然后藏到后台。
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private boolean launched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 防止多次启动时重复跳转
        if (launched) {
            finish();
            return;
        }
        launched = true;
        launchQQMusic();
        // 退到后台，不销毁 — 避免 Launcher 再次启动
        moveTaskToBack(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Launcher 再次点卡片 → 重新尝试跳 QQ音乐
        launchQQMusic();
        moveTaskToBack(true);
    }

    private void launchQQMusic() {
        String[] candidates = {"com.tencent.qqmusiccar", "com.tencent.qqmusic"};
        for (String pkg : candidates) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                try {
                    startActivity(intent);
                    Log.d(TAG, "Launched: " + pkg);
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "Failed: " + pkg, e);
                }
            }
        }
        Log.w(TAG, "QQMusic not found");
    }
}
