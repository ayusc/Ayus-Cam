package com.vcam.ayuscam;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
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

        // Pan commands ONLY register if Zoom is not 0
        btnPanUp.setOnClickListener(v -> { if(config.zoom != 0) { config.panY -= 10; config.save(); } });
        btnPanDown.setOnClickListener(v -> { if(config.zoom != 0) { config.panY += 10; config.save(); } });
        btnPanLeft.setOnClickListener(v -> { if(config.zoom != 0) { config.panX -= 10; config.save(); } });
        btnPanRight.setOnClickListener(v -> { if(config.zoom != 0) { config.panX += 10; config.save(); } });
        
        btnPanCenter.setOnClickListener(v -> { 
            config.isPaused = !config.isPaused; 
            config.save(); 
            updateUI(); 
        });
    }

    private void setScaleMode(String mode) {
        config.scaleMode = mode;
        config.save();
        updateUI();
    }

    private void updateUI() {
        // Enforce the new bounds explicitly to prevent UI crashing on older save states
        sliderRotation.setValue(Math.min(config.rotation, 270));
        sliderZoom.setValue(Math.min(config.zoom, 200));
        sliderVolume.setValue(config.volume);

        tvRotationVal.setText(config.rotation + "°");
        tvZoomVal.setText(config.zoom + "%");
        tvVolumeVal.setText(config.volume + "%");

        btnPanCenter.setImageResource(config.isPaused ? 
            android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause);

        // Aspect ratio red selection border highlighting
        int activeColor = ContextCompat.getColor(requireContext(), R.color.accent_red);
        int inactiveColor = ContextCompat.getColor(requireContext(), R.color.inner_box_dark);
        
        btnFit.setBackgroundTintList(ColorStateList.valueOf("FIT".equals(config.scaleMode) ? activeColor : inactiveColor));
        btnStretch.setBackgroundTintList(ColorStateList.valueOf("STRETCH".equals(config.scaleMode) ? activeColor : inactiveColor));
        btnFill.setBackgroundTintList(ColorStateList.valueOf("FILL".equals(config.scaleMode) ? activeColor : inactiveColor));

        btnHudToggle.setText(config.showHud ? "Hide Floating HUD" : "Show Floating HUD");
    }
}
