package com.vcam.ayuscam;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class FloatingWindowService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private AppConfig config;
    private WindowManager.LayoutParams params;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        config = AppConfig.load();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Building the view dynamically to avoid requiring extra XML layouts
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#CC0A0D14")); 
        rootLayout.setPadding(24, 24, 24, 24);

        // Header Draggable Icon
        ImageView iconView = new ImageView(this);
        iconView.setImageResource(android.R.drawable.ic_menu_camera); 
        iconView.setBackgroundColor(Color.parseColor("#FF2A42"));
        iconView.setPadding(24, 24, 24, 24);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(120, 120);
        iconParams.gravity = Gravity.CENTER;
        rootLayout.addView(iconView, iconParams);

        // Expandable Control Panel Layout
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        panel.setPadding(0, 24, 0, 0);

        // Zoom Settings (+/- limits bound natively to 0-200 bounds you requested)
        panel.addView(createControlRow("Zoom", v -> {
            config = AppConfig.load();
            config.zoom = Math.max(0, config.zoom - 5);
            config.save();
        }, v -> {
            config = AppConfig.load();
            config.zoom = Math.min(200, config.zoom + 5);
            config.save();
        }));

        // Rotation Settings
        panel.addView(createControlRow("Rotate", v -> {
            config = AppConfig.load();
            config.rotation = (config.rotation - 90) % 360;
            if(config.rotation < 0) config.rotation += 360;
            config.save();
        }, v -> {
            config = AppConfig.load();
            config.rotation = (config.rotation + 90) % 360;
            config.save();
        }));

        // Volume Settings
        panel.addView(createControlRow("Volume", v -> {
            config = AppConfig.load();
            config.volume = Math.max(0, config.volume - 5);
            config.save();
        }, v -> {
            config = AppConfig.load();
            config.volume = Math.min(100, config.volume + 5);
            config.save();
        }));

        // Source Switcher
        Button btnSource = new Button(this);
        btnSource.setText("Change Source");
        btnSource.setTextColor(Color.WHITE);
        btnSource.setBackgroundColor(Color.parseColor("#181E2B"));
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sourceParams.setMargins(0, 16, 0, 0);
        btnSource.setLayoutParams(sourceParams);

        btnSource.setOnClickListener(v -> {
            config = AppConfig.load();
            if (config.mediaPaths.size() > 0) {
                config.selectedIndex = (config.selectedIndex + 1) % config.mediaPaths.size();
                config.save();
                Toast.makeText(this, "Changed Active Source", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No media available", Toast.LENGTH_SHORT).show();
            }
        });
        panel.addView(btnSource);
        
        Button btnClose = new Button(this);
        btnClose.setText("Close Window");
        btnClose.setTextColor(Color.WHITE);
        btnClose.setBackgroundColor(Color.parseColor("#FF2A42"));
        btnClose.setLayoutParams(sourceParams);
        btnClose.setOnClickListener(v -> {
            config = AppConfig.load();
            config.showHud = false;
            config.save();
            stopSelf();
        });
        panel.addView(btnClose);

        rootLayout.addView(panel);
        floatingView = rootLayout;

        // Toggles expansion of menu on click
        iconView.setOnClickListener(v -> {
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 150;

        // Allows dragging over the screen
        iconView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long lastTouchDown;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        lastTouchDown = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis() - lastTouchDown < 200) {
                            v.performClick(); 
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingView, params);
    }

    private LinearLayout createControlRow(String labelText, View.OnClickListener onMinus, View.OnClickListener onPlus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 8, 0, 8);
        
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Color.WHITE);
        label.setPadding(0, 0, 16, 0);
        label.setWidth(140);
        
        Button btnMinus = new Button(this);
        btnMinus.setText("-");
        btnMinus.setTextColor(Color.WHITE);
        btnMinus.setBackgroundColor(Color.parseColor("#181E2B"));
        btnMinus.setOnClickListener(onMinus);
        
        Button btnPlus = new Button(this);
        btnPlus.setText("+");
        btnPlus.setTextColor(Color.WHITE);
        btnPlus.setBackgroundColor(Color.parseColor("#181E2B"));
        btnPlus.setOnClickListener(onPlus);
        
        row.addView(label);
        row.addView(btnMinus);
        row.addView(btnPlus);
        return row;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
