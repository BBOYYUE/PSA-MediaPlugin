package com.cusc.media;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView permissionStatusText;

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