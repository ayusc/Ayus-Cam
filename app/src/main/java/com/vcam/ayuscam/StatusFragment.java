package com.vcam.ayuscam;

import android.os.Bundle;
import android.view.LayoutInflater;
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
import java.util.Locale;

public class StatusFragment extends Fragment {

    private TextView tvDaemonState, tvSocketStatus, tvRotationState, tvZoomState, tvScaleState, tvConsoleLogs;
    private Button btnClearLogs;

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
        btnClearLogs = root.findViewById(R.id.btn_clear_logs);

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
        File mediaFile = new File(config.mediaPath);

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
        if (!logFile.exists()) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvConsoleLogs.setText("[" + time + "] Daemon initialized.\n[" + time + "] Ready for camera replacement.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            tvConsoleLogs.setText(sb.toString());
        } catch (Exception e) {
            tvConsoleLogs.setText("Error reading logs: " + e.getMessage());
        }
    }
}
