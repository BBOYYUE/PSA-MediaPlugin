package com.cusc.media;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cusc.media.base.player.MusicService;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private TextView permissionStatusText;
    private TextView connectedAppText;

    private final MusicService.MusicServiceCallback musicServiceCallback = new MusicService.MusicServiceCallback() {
        @Override
        public void onPackageChanged(String packageName) {
            runOnUiThread(() -> updateConnectedApp(packageName));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button actionBtn = findViewById(R.id.btn_restore);
        if (actionBtn != null) {
            actionBtn.setOnClickListener(v -> {
                Uri packageUri = Uri.parse("package:" + getPackageName());
                Intent uninstallIntent = new Intent(Intent.ACTION_DELETE, packageUri);
                startActivity(uninstallIntent);
            });
        }

        LinearLayout permissionStatusLayout = findViewById(R.id.layout_permission_status);
        permissionStatusText = findViewById(R.id.text_permission_status);
        connectedAppText = findViewById(R.id.text_connected_app);

        permissionStatusLayout.setOnClickListener(v -> {
            if (!isNotificationServiceEnabled()) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();

        MusicService musicService = MusicService.getInstance();
        if (musicService != null) {
            musicService.setMusicServiceCallback(musicServiceCallback);
        } else {
            Log.w(TAG, "MusicService not running, cannot register callback");
            connectedAppText.setText(R.string.none);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        MusicService musicService = MusicService.getInstance();
        if (musicService != null) {
            musicService.setMusicServiceCallback(null);
        }
    }

    private void updatePermissionStatus() {
        if (isNotificationServiceEnabled()) {
            permissionStatusText.setText(R.string.permission_status_granted);
            permissionStatusText.setTextColor(getResources().getColor(R.color.green, getTheme()));
        } else {
            permissionStatusText.setText(R.string.permission_status_not_granted);
            permissionStatusText.setTextColor(getResources().getColor(R.color.red, getTheme()));
        }
    }

    private void updateConnectedApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            connectedAppText.setText(R.string.none);
            return;
        }

        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            connectedAppText.setText(label != null ? label.toString() : packageName);
        } catch (PackageManager.NameNotFoundException e) {
            connectedAppText.setText(packageName);
        }
    }

    private boolean isNotificationServiceEnabled() {
        String packageName = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat != null) {
            String[] names = flat.split(":");
            for (String name : names) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && cn.getPackageName().equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
