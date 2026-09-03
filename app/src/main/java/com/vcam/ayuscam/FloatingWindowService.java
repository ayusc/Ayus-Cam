package com.vcam.ayuscam;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

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

        // Wrap Context to apply Material Theme to inflated XML inside a Service
        ContextThemeWrapper ctx = new ContextThemeWrapper(this, R.style.Theme_VCAM);
        LayoutInflater inflater = LayoutInflater.from(ctx);
        floatingView = inflater.inflate(R.layout.layout_floating_window, null);

        View iconContainer = floatingView.findViewById(R.id.floating_icon_container);
        View panelView = floatingView.findViewById(R.id.floating_panel);
        View panelHeader = floatingView.findViewById(R.id.panel_header);

        // Toggling Panel Visibility
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
            stopSelf();
        });

        // Wire up SeekBars
        setupSeekBars();

        // Wire up D-Pad
        setupDPad();

        // Layout Params
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

        // Make both the Icon and the Header draggable
        View.OnTouchListener dragListener = new View.OnTouchListener() {
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
        };

        iconContainer.setOnTouchListener(dragListener);
        panelHeader.setOnTouchListener(dragListener);

        windowManager.addView(floatingView, params);
    }

    private void setupSeekBars() {
        SeekBar seekZoom = floatingView.findViewById(R.id.seek_zoom);
        SeekBar seekRotate = floatingView.findViewById(R.id.seek_rotate);
        SeekBar seekVolume = floatingView.findViewById(R.id.seek_volume);
        TextView tvZoom = floatingView.findViewById(R.id.tv_zoom_val);
        TextView tvRotate = floatingView.findViewById(R.id.tv_rotate_val);
        TextView tvVolume = floatingView.findViewById(R.id.tv_volume_val);

        seekZoom.setProgress(config.zoom);
        tvZoom.setText(config.zoom + "%");
        seekZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.zoom = progress;
                tvZoom.setText(progress + "%");
                config.save();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekRotate.setProgress(config.rotation);
        tvRotate.setText(config.rotation + "°");
        seekRotate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int snapped = Math.round(progress / 90f) * 90;
                    seekBar.setProgress(snapped);
                    config.rotation = snapped;
                    tvRotate.setText(snapped + "°");
                    config.save();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekVolume.setProgress(config.volume);
        tvVolume.setText(config.volume + "%");
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                config.volume = progress;
                tvVolume.setText(progress + "%");
                config.save();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupDPad() {
        floatingView.findViewById(R.id.btn_pan_up).setOnClickListener(v -> {
            if(config.zoom != 0) { config.panY -= 10; config.save(); }
        });
        floatingView.findViewById(R.id.btn_pan_down).setOnClickListener(v -> {
            if(config.zoom != 0) { config.panY += 10; config.save(); }
        });
        floatingView.findViewById(R.id.btn_pan_left).setOnClickListener(v -> {
            if(config.zoom != 0) { config.panX -= 10; config.save(); }
        });
        floatingView.findViewById(R.id.btn_pan_right).setOnClickListener(v -> {
            if(config.zoom != 0) { config.panX += 10; config.save(); }
        });
        floatingView.findViewById(R.id.btn_play_pause).setOnClickListener(v -> {
            config.isPaused = !config.isPaused;
            config.save();
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
