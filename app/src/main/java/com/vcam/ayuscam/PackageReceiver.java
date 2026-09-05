package com.vcam.ayuscam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.vcam.ayuscam.REGISTER_PACKAGE".equals(intent.getAction())) {
            String pkg = intent.getStringExtra("package_name");
            if (pkg != null && !pkg.isEmpty()) {
                AppConfig config = AppConfig.load();
                if (!config.scopedPackages.contains(pkg)) {
                    config.scopedPackages.add(pkg);
                    config.save();
                }
            }
        }
    }
}
