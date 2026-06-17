package com.cusc.media;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 透明跳板：被 Launcher 启动后立即跳转 QQ音乐并关闭。
 * 无论从桌面图标还是桌面卡片进入，用户唯一看到的就是 QQ音乐。
 */
public class RedirectActivity extends Activity {
    private static final String TAG = "RedirectActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String[] candidates = {"com.tencent.qqmusiccar", "com.tencent.qqmusic"};
        for (String pkg : candidates) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                try {
                    startActivity(intent);
                    Log.d(TAG, "Redirected to: " + pkg);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch " + pkg, e);
                }
            }
        }
        finish();
    }
}
