package com.vcam.ayuscam;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;

public class FloatingWindowService extends Service {
    private WindowManager windowManager;
    private View floatingView;
    private AppConfig config;
    private WindowManager.LayoutParams params;

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            config = AppConfig.load();
            updateFloatingUI();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        config = AppConfig.load();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        ContextThemeWrapper ctx = new ContextThemeWrapper(this, R.style.Theme_VCAM);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        floatingView = inflater.inflate(R.layout.layout_floating_window, null);

        View iconContainer = floatingView.findViewById(R.id.floating_icon_container);
        View panelView = floatingView.findViewById(R.id.floating_panel);
        View panelHeader = floatingView.findViewById(R.id.panel_header);

        iconContainer.setOnClickListener(v -> {
            iconContainer.setVisibility(View.GONE);
            panelView.setVisibility(View.VISIBLE);
        });

        floatingView.findViewById(R.id.btn_minimize).setOnClickListener(v -> {
            panelView.setVisibility(View.GONE);
            iconContainer.setVisibility(View.VISIBLE);
        });

        floatingView.findViewById(R.id.btn_close).setOnClickListener(v -> {
            config = AppConfig.load();
            config.showHud = false;
            config.save();
            sendBroadcast(new Intent("com.vcam.ayuscam.UPDATE_UI"));
            stopSelf();
        });

        setupSeekBars();
        setupButtons();
        updateFloatingUI();

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

        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
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
        };

        iconContainer.setOnTouchListener(dragListener);
        panelHeader.setOnTouchListener(dragListener);
        windowManager.addView(floatingView, params);

        IntentFilter filter = new IntentFilter("com.vcam.ayuscam.UPDATE_UI");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncReceiver, filter);
        }
    }

    private void saveAndNotify() {
        config.save();
        updateFloatingUI();
        sendBroadcast(new Intent("com.vcam.ayuscam.UPDATE_UI"));
    }

    private void setupSeekBars() {
        SeekBar seekZoom = floatingView.findViewById(R.id.seek_zoom);
        SeekBar seekRotate = floatingView.findViewById(R.id.seek_rotate);
        SeekBar seekVolume = floatingView.findViewById(R.id.seek_volume);
        SeekBar seekSpeed = floatingView.findViewById(R.id.seek_speed);

        seekSpeed.setMax(7);

        seekZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { config.zoom = progress; saveAndNotify(); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekRotate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int snapped = Math.round(progress / 90f) * 90;
                    seekBar.setProgress(snapped);
                    config.rotation = snapped;
                    saveAndNotify();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { config.volume = progress; saveAndNotify(); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) { config.speed = 0.25f + (progress * 0.25f); saveAndNotify(); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupButtons() {
        floatingView.findViewById(R.id.btn_scale_fit).setOnClickListener(v -> {
            config.scaleMode = "FIT"; saveAndNotify();
        });
        floatingView.findViewById(R.id.btn_scale_stretch).setOnClickListener(v -> {
            config.scaleMode = "STRETCH"; saveAndNotify();
        });
        floatingView.findViewById(R.id.btn_scale_fill).setOnClickListener(v -> {
            config.scaleMode = "FILL"; saveAndNotify();
        });

        floatingView.findViewById(R.id.btn_zoom_minus).setOnClickListener(v -> {
            config.zoom = Math.max(0, config.zoom - 1); saveAndNotify();
        });
        floatingView.findViewById(R.id.btn_zoom_plus).setOnClickListener(v -> {
            config.zoom = Math.min(200, config.zoom + 1); saveAndNotify();
        });

        floatingView.findViewById(R.id.btn_rotate_minus).setOnClickListener(v -> {
            config.rotation = Math.max(0, config.rotation - 90); saveAndNotify();
        });
        floatingView.findViewById(R.id.btn_rotate_plus).setOnClickListener(v -> {
            config.rotation = Math.min(270, config.rotation + 90); saveAndNotify();
        });

        floatingView.findViewById(R.id.btn_volume_minus).setOnClickListener(v -> {
            config.volume = Math.max(0, config.volume - 1); saveAndNotify();
        });
        floatingView.findViewById(R.id.btn_volume_plus).setOnClickListener(v -> {
            config.volume = Math.min(100, config.volume + 1); saveAndNotify();
        });

        floatingView.findViewById(R.id.btn_speed_minus).setOnClickListener(v -> {
            config.speed = Math.max(0.25f, config.speed - 0.25f); saveAndNotify();
        });
        floatingView.findViewById(R.id.btn_speed_plus).setOnClickListener(v -> {
            config.speed = Math.min(2.0f, config.speed + 0.25f); saveAndNotify();
        });

        floatingView.findViewById(R.id.btn_pan_up).setOnClickListener(v -> {
            if(config.zoom != 0) { config.panY -= 10; saveAndNotify(); }
        });
        floatingView.findViewById(R.id.btn_pan_down).setOnClickListener(v -> {
            if(config.zoom != 0) { config.panY += 10; saveAndNotify(); }
        });
        floatingView.findViewById(R.id.btn_pan_left).setOnClickListener(v -> {
            if(config.zoom != 0) { config.panX -= 10; saveAndNotify(); }
        });
        floatingView.findViewById(R.id.btn_pan_right).setOnClickListener(v -> {
            if(config.zoom != 0) { config.panX += 10; saveAndNotify(); }
        });

        floatingView.findViewById(R.id.btn_play_pause).setOnClickListener(v -> {
            config.isPaused = !config.isPaused; saveAndNotify();
        });
    }

    private void updateFloatingUI() {
        ((SeekBar) floatingView.findViewById(R.id.seek_zoom)).setProgress(config.zoom);
        ((TextView) floatingView.findViewById(R.id.tv_zoom_val)).setText(config.zoom + "%");

        ((SeekBar) floatingView.findViewById(R.id.seek_rotate)).setProgress(config.rotation);
        ((TextView) floatingView.findViewById(R.id.tv_rotate_val)).setText(config.rotation + "\u00B0");

        ((SeekBar) floatingView.findViewById(R.id.seek_volume)).setProgress(config.volume);
        ((TextView) floatingView.findViewById(R.id.tv_volume_val)).setText(config.volume + "%");

        ((SeekBar) floatingView.findViewById(R.id.seek_speed)).setProgress(Math.round((config.speed - 0.25f) / 0.25f));
        ((TextView) floatingView.findViewById(R.id.tv_speed_val)).setText(String.format(java.util.Locale.US, "%.2fx", config.speed));

        ImageView playPauseIcon = floatingView.findViewById(R.id.icon_play_pause);
        playPauseIcon.setImageResource(config.isPaused ?
             android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause);

        int activeColor = android.graphics.Color.parseColor("#80FF2A42"); 
        int inactiveColor = android.graphics.Color.parseColor("#400E1118");

        MaterialButton btnFit = floatingView.findViewById(R.id.btn_scale_fit);
        MaterialButton btnStretch = floatingView.findViewById(R.id.btn_scale_stretch);
        MaterialButton btnFill = floatingView.findViewById(R.id.btn_scale_fill);

        btnFit.setBackgroundTintList(ColorStateList.valueOf("FIT".equals(config.scaleMode) ? activeColor : inactiveColor));
        btnStretch.setBackgroundTintList(ColorStateList.valueOf("STRETCH".equals(config.scaleMode) ? activeColor : inactiveColor));
        btnFill.setBackgroundTintList(ColorStateList.valueOf("FILL".equals(config.scaleMode) ? activeColor : inactiveColor));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(syncReceiver);
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
