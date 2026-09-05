package com.vcam.ayuscam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

public class ControlFragment extends Fragment {
    private AppConfig config;
    private TextView tvRotationVal, tvZoomVal, tvVolumeVal, tvSpeedVal;
    private Slider sliderRotation, sliderZoom, sliderVolume, sliderSpeed;
    private MaterialButton btnFit, btnStretch, btnFill, btnHudToggle;
    private ImageButton btnPanUp, btnPanDown, btnPanLeft, btnPanRight, btnPanCenter;

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            config = AppConfig.load();
            updateUI();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_control, container, false);
        config = AppConfig.load();

        tvRotationVal = root.findViewById(R.id.tv_rotation_val);
        tvZoomVal = root.findViewById(R.id.tv_zoom_val);
        tvVolumeVal = root.findViewById(R.id.tv_volume_val);
        tvSpeedVal = root.findViewById(R.id.tv_speed_val);

        sliderRotation = root.findViewById(R.id.slider_rotation);
        sliderZoom = root.findViewById(R.id.slider_zoom);
        sliderVolume = root.findViewById(R.id.slider_volume);
        sliderSpeed = root.findViewById(R.id.slider_speed);

        btnFit = root.findViewById(R.id.btn_scale_fit);
        btnStretch = root.findViewById(R.id.btn_scale_stretch);
        btnFill = root.findViewById(R.id.btn_scale_fill);
        btnHudToggle = root.findViewById(R.id.btn_hud_toggle);

        btnPanUp = root.findViewById(R.id.btn_pan_up);
        btnPanDown = root.findViewById(R.id.btn_pan_down);
        btnPanLeft = root.findViewById(R.id.btn_pan_left);
        btnPanRight = root.findViewById(R.id.btn_pan_right);
        btnPanCenter = root.findViewById(R.id.btn_pan_center);

        sliderRotation.setLabelFormatter(value -> String.valueOf((int) value) + "\u00B0");
        sliderZoom.setLabelFormatter(value -> String.valueOf((int) value) + "%");
        sliderVolume.setLabelFormatter(value -> String.valueOf((int) value));
        sliderSpeed.setLabelFormatter(value -> String.format(java.util.Locale.US, "%.2fx", value));

        setupListeners();
        updateUI();

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        config = AppConfig.load();
        updateUI();
        IntentFilter filter = new IntentFilter("com.vcam.ayuscam.UPDATE_UI");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(syncReceiver, filter);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(syncReceiver);
    }

    private void notifySync() {
        requireContext().sendBroadcast(new Intent("com.vcam.ayuscam.UPDATE_UI"));
    }

    private void setupListeners() {
        sliderRotation.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                int rotation = ((int) value / 90) * 90;
                config.rotation = rotation;
                tvRotationVal.setText(rotation + "\u00B0");
                config.save();
                notifySync();
            }
        });

        sliderZoom.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                config.zoom = (int) value;
                tvZoomVal.setText(config.zoom + "%");
                config.save();
                notifySync();
            }
        });

        sliderVolume.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                config.volume = (int) value;
                tvVolumeVal.setText(config.volume + "%");
                config.save();
                notifySync();
            }
        });

        sliderSpeed.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                config.speed = value;
                tvSpeedVal.setText(String.format(java.util.Locale.US, "%.2fx", config.speed));
                config.save();
                notifySync();
            }
        });

        btnFit.setOnClickListener(v -> setScaleMode("FIT"));
        btnStretch.setOnClickListener(v -> setScaleMode("STRETCH"));
        btnFill.setOnClickListener(v -> setScaleMode("FILL"));

        btnHudToggle.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(requireContext())) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + requireContext().getPackageName()));
                startActivity(intent);
                Toast.makeText(requireContext(), "Please grant overlay permissions", Toast.LENGTH_LONG).show();
            } else {
                config.showHud = !config.showHud;
                config.save();
                updateUI();
                notifySync();
                Intent serviceIntent = new Intent(requireContext(), FloatingWindowService.class);
                if (config.showHud) {
                    requireContext().startService(serviceIntent);
                } else {
                    requireContext().stopService(serviceIntent);
                }
            }
        });

        btnPanUp.setOnClickListener(v -> { if(config.zoom != 0) { config.panY -= 10; config.save(); notifySync(); } });
        btnPanDown.setOnClickListener(v -> { if(config.zoom != 0) { config.panY += 10; config.save(); notifySync(); } });
        btnPanLeft.setOnClickListener(v -> { if(config.zoom != 0) { config.panX -= 10; config.save(); notifySync(); } });
        btnPanRight.setOnClickListener(v -> { if(config.zoom != 0) { config.panX += 10; config.save(); notifySync(); } });

        btnPanCenter.setOnClickListener(v -> {
             config.isPaused = !config.isPaused;
             config.save();
             updateUI();
             notifySync();
        });
    }

    private void setScaleMode(String mode) {
        config.scaleMode = mode;
        config.save();
        updateUI();
        notifySync();
    }

    private void updateUI() {
        sliderRotation.setValue(Math.min(config.rotation, 270));
        sliderZoom.setValue(Math.min(config.zoom, 200));
        sliderVolume.setValue(config.volume);
        sliderSpeed.setValue(Math.max(0.25f, Math.min(config.speed, 2.0f)));

        tvRotationVal.setText(config.rotation + "\u00B0");
        tvZoomVal.setText(config.zoom + "%");
        tvVolumeVal.setText(config.volume + "%");
        tvSpeedVal.setText(String.format(java.util.Locale.US, "%.2fx", config.speed));

        btnPanCenter.setImageResource(config.isPaused ?
             android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause);

        int activeColor = ContextCompat.getColor(requireContext(), R.color.accent_red);
        int inactiveColor = ContextCompat.getColor(requireContext(), R.color.inner_box_dark);

        btnFit.setBackgroundTintList(ColorStateList.valueOf("FIT".equals(config.scaleMode) ? activeColor : inactiveColor));
        btnStretch.setBackgroundTintList(ColorStateList.valueOf("STRETCH".equals(config.scaleMode) ? activeColor : inactiveColor));
        btnFill.setBackgroundTintList(ColorStateList.valueOf("FILL".equals(config.scaleMode) ? activeColor : inactiveColor));

        btnHudToggle.setText(config.showHud ? "Hide Floating Window" : "Show Floating Window");
    }
}
