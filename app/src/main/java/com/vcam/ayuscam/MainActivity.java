package com.vcam.ayuscam;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!checkRootAccess()) {
            showRootDeniedScreen();
            return;
        }
        
        setContentView(R.layout.activity_main);
        checkAndRequestPermissions();
        
        AppConfig config = AppConfig.load();
        config.showHud = false;
        config.save();
        
        stopService(new Intent(this, FloatingWindowService.class));
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new HomeFragment())
                    .commit();
        }
        
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                selectedFragment = new HomeFragment();
            } else if (itemId == R.id.nav_control) {
                selectedFragment = new ControlFragment();
            } else if (itemId == R.id.nav_status) {
                selectedFragment = new StatusFragment();
            }
            if (selectedFragment != null) {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.fragment_container, selectedFragment)
                        .commit();
            }
            return true;
        });
    }

    private boolean checkRootAccess() {
        try {
            Process process = Runtime.getRuntime().exec("su -c exit");
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void showRootDeniedScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(android.graphics.Color.BLACK);
        
        TextView tv = new TextView(this);
        tv.setText("This app needs root access to work properly!\nPlease grant root access via your root manager.");
        tv.setTextColor(android.graphics.Color.RED);
        tv.setTextSize(20f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(64, 64, 64, 64);
        
        layout.addView(tv);
        setContentView(layout);
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }
        
        List<String> permissionsNeeded = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQ_CODE);
        } else {
            File cameraDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/");
            if (!cameraDir.exists()) cameraDir.mkdirs();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            File cameraDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/");
            if (!cameraDir.exists()) cameraDir.mkdirs();
        }
    }
}
