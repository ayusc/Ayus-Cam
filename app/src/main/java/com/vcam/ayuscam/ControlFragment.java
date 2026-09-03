package com.vcam.ayuscam;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

public class ControlFragment extends Fragment {
    private AppConfig config;
    private TextView tvRotationVal, tvZoomVal, tvVolumeVal;
    private Slider sliderRotation, sliderZoom, sliderVolume;
    private MaterialButton btnFit, btnStretch, btnFill, btnHudToggle;
    private ImageButton btnPanUp, btnPanDown, btnPanLeft, btnPanRight, btnPanCenter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_control, container, false);
        config = AppConfig.load();
        tvRotationVal = root.findViewById(R.id.tv_rotation_val);
        tvZoomVal = root.findViewById(R.id.tv_zoom_val);
        tvVolumeVal = root.findViewById(R.id.tv_volume_val);
        sliderRotation = root.findViewById(R.id.slider_rotation);
        sliderZoom = root.findViewById(R.id.slider_zoom);
        sliderVolume = root.findViewById(R.id.slider_volume);
        btnFit = root.findViewById(R.id.btn_scale_fit);
        btnStretch = root.findViewById(R.id.btn_scale_stretch);
        btnFill = root.findViewById(R.id.btn_scale_fill);
        btnHudToggle = root.findViewById(R.id.btn_hud_toggle);
        btnPanUp = root.findViewById(R.id.btn_pan_up);
        btnPanDown = root.findViewById(R.id.btn_pan_down);
        btnPanLeft = root.findViewById(R.id.btn_pan_left);
        btnPanRight = root.findViewById(R.id.btn_pan_right);
        btnPanCenter = root.findViewById(R.id.btn_pan_center);
        
        setupListeners();
        updateUI();
        return root;
    }

    private void setupListeners() {
        sliderRotation.addOnChangeListener((slider, value, fromUser) -> {
            int rotation = ((int) value / 90) * 90;
            config.rotation = rotation;
            // FIXED: Added missing degree symbol and closed the string literal
            tvRotationVal.setText(rotation + "°");
            config.save();
        });
        
        sliderZoom.addOnChangeListener((slider, value, fromUser) -> {
            config.zoom = (int) value;
            tvZoomVal.setText(config.zoom + "%");
            config.save();
        });
        
        sliderVolume.addOnChangeListener((slider, value, fromUser) -> {
            config.volume = (int) value;
            tvVolumeVal.setText(config.volume + "%");
            config.save();
        });
        
        btnFit.setOnClickListener(v -> setScaleMode("FIT"));
        btnStretch.setOnClickListener(v -> setScaleMode("STRETCH"));
        btnFill.setOnClickListener(v -> setScaleMode("FILL"));
        btnHudToggle.setOnClickListener(v -> {
            config.showHud = !config.showHud;
            config.save();
            updateUI();
        });
        
        btnPanUp.setOnClickListener(v -> { config.panY -= 10; config.save(); });
        btnPanDown.setOnClickListener(v -> { config.panY += 10; config.save(); });
        btnPanLeft.setOnClickListener(v -> { config.panX -= 10; config.save(); });
        btnPanRight.setOnClickListener(v -> { config.panX += 10; config.save(); });
        btnPanCenter.setOnClickListener(v -> { config.panX = 0; config.panY = 0; config.save(); });
    }

    private void setScaleMode(String mode) {
        config.scaleMode = mode;
        config.save();
        updateUI();
    }

    private void updateUI() {
        sliderRotation.setValue(config.rotation);
        sliderZoom.setValue(config.zoom);
        sliderVolume.setValue(config.volume);
        
        // FIXED: Added missing degree symbol and closed the string literal
        tvRotationVal.setText(config.rotation + "°");
        tvZoomVal.setText(config.zoom + "%");
        tvVolumeVal.setText(config.volume + "%");
        
        btnFit.setAlpha("FIT".equals(config.scaleMode) ? 1.0f : 0.5f);
        btnStretch.setAlpha("STRETCH".equals(config.scaleMode) ? 1.0f : 0.5f);
        btnFill.setAlpha("FILL".equals(config.scaleMode) ? 1.0f : 0.5f);
        btnHudToggle.setText(config.showHud ? "Hide Floating HUD" : "Show Floating HUD");
    }
} // FIXED: Added missing closing bracket
