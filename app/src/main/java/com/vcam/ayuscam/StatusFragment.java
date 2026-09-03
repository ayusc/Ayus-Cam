package com.vcam.ayuscam;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

public class StatusFragment extends Fragment {

    private TextView tvDaemonState, tvSocketStatus, tvRotationState, tvZoomState, tvScaleState, tvConsoleLogs;
    private View scrollConsoleLogs;
    private Button btnClearLogs;

    @SuppressLint("ClickableViewAccessibility")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_status, container, false);

        tvDaemonState = root.findViewById(R.id.tv_daemon_state);
        tvSocketStatus = root.findViewById(R.id.tv_socket_status);
        tvRotationState = root.findViewById(R.id.tv_status_rotation);
        tvZoomState = root.findViewById(R.id.tv_status_zoom);
        tvScaleState = root.findViewById(R.id.tv_status_scale);
        tvConsoleLogs = root.findViewById(R.id.tv_console_logs);
        scrollConsoleLogs = root.findViewById(R.id.scroll_console_logs);
        
        if (scrollConsoleLogs == null && tvConsoleLogs != null) {
            scrollConsoleLogs = (View) tvConsoleLogs.getParent();
        }
        btnClearLogs = root.findViewById(R.id.btn_clear_logs);
        
        if (scrollConsoleLogs != null) {
            scrollConsoleLogs.setOnTouchListener((v, event) -> {
                if (v.getParent() != null) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
                return false;
            });
        }
        
        btnClearLogs.setOnClickListener(v -> {
            File logFile = new File(AppConfig.LOG_FILE);
            if (logFile.exists()) logFile.delete();
            tvConsoleLogs.setText("");
        });
        
        loadStatus();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadStatus();
    }

    private void loadStatus() {
        AppConfig config = AppConfig.load();
        File mediaFile = new File(config.getActiveMediaPath());
        boolean isActive = config.enabled && mediaFile.exists();
        
        tvDaemonState.setText(isActive ? "ACTIVE" : "IDLE");
        tvDaemonState.setTextColor(isActive ? 0xFF00E676 : 0xFFE53935);
        tvSocketStatus.setText("CONNECTED");
        
        tvRotationState.setText(config.rotation + "°");
        tvZoomState.setText(config.zoom + "%");
        tvScaleState.setText(config.scaleMode);
        
        loadLogs();
    }

    private void loadLogs() {
        File logFile = new File(AppConfig.LOG_FILE);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        
        if (!logFile.exists()) {
            tvConsoleLogs.setText(time + "  Process started\n" + time + "  Awaiting camera initialization");
            return;
        }
        
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            LinkedList<String> lines = new LinkedList<>();
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (lines.size() > 100) {
                    lines.removeFirst();
                }
            }
            
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append("\n");
            }
            
            if (sb.length() == 0) {
                sb.append(time).append("  Process started\n").append(time).append("  Awaiting camera initialization\n");
            }
            
            tvConsoleLogs.setText(sb.toString());
            
            if (scrollConsoleLogs != null) {
                scrollConsoleLogs.post(() -> {
                    try {
                        if (scrollConsoleLogs instanceof androidx.core.widget.NestedScrollView) {
                            ((androidx.core.widget.NestedScrollView) scrollConsoleLogs).fullScroll(View.FOCUS_DOWN);
                        } else if (scrollConsoleLogs instanceof android.widget.ScrollView) {
                            ((android.widget.ScrollView) scrollConsoleLogs).fullScroll(View.FOCUS_DOWN);
                        }
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            tvConsoleLogs.setText("Log error: " + e.getMessage());
        }
    }
}
