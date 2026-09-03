package com.vcam.ayuscam;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class HomeFragment extends Fragment implements TextureView.SurfaceTextureListener {

    private AppConfig config;
    private TextureView previewTextureView;
    private ImageView previewImageView;
    private TextView tvPreviewStatus, tvEmptyMedia, tvBadgeDaemon, tvBadgePreviewState;
    private MaterialButton btnTogglePreview, btnToggleVirtualCam, btnPickPhoto, btnPickVideo;
    private ImageButton btnRotateCamera;
    private LinearLayout llMediaList, llPreviewOverlay;

    private MediaPlayer mediaPlayer;
    private Camera mCamera;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    
    // FIXED: Made static so it survives Fragment recreation when switching tabs
    private static boolean isPreviewRunning = false; 

    private final ActivityResultLauncher<String[]> pickPhotoLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> handleMediaResult(uri, "IMAGE"));

    private final ActivityResultLauncher<String[]> pickVideoLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> handleMediaResult(uri, "VIDEO"));

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);
        config = AppConfig.load();

        previewTextureView = root.findViewById(R.id.preview_texture_view);
        previewImageView = root.findViewById(R.id.preview_image_view);
        tvPreviewStatus = root.findViewById(R.id.tv_preview_status);
        tvEmptyMedia = root.findViewById(R.id.tv_empty_media);
        tvBadgeDaemon = root.findViewById(R.id.tv_badge_daemon);
        tvBadgePreviewState = root.findViewById(R.id.tv_badge_preview_state);

        btnTogglePreview = root.findViewById(R.id.btn_toggle_preview);
        btnToggleVirtualCam = root.findViewById(R.id.btn_toggle_virtual_cam);
        btnPickPhoto = root.findViewById(R.id.btn_pick_photo);
        btnPickVideo = root.findViewById(R.id.btn_pick_video);
        btnRotateCamera = root.findViewById(R.id.btn_rotate_camera);

        llMediaList = root.findViewById(R.id.ll_media_list);
        llPreviewOverlay = root.findViewById(R.id.ll_preview_status_overlay);

        previewTextureView.setSurfaceTextureListener(this);

        btnPickPhoto.setOnClickListener(v -> pickPhotoLauncher.launch(new String[]{"image/*"}));
        btnPickVideo.setOnClickListener(v -> pickVideoLauncher.launch(new String[]{"video/*"}));

        btnToggleVirtualCam.setOnClickListener(v -> {
            config.enabled = !config.enabled;
            config.save();
            updateUI();
            restartPreviewMode();
        });

        btnTogglePreview.setOnClickListener(v -> {
            isPreviewRunning = !isPreviewRunning;
            updateUI();
            restartPreviewMode();
        });
        
        // FIXED: Added click listener to toggle preview by clicking the ON/OFF text
        tvBadgePreviewState.setOnClickListener(v -> {
            isPreviewRunning = !isPreviewRunning;
            updateUI();
            restartPreviewMode();
        });

        btnRotateCamera.setOnClickListener(v -> {
            int numCameras = Camera.getNumberOfCameras();
            if (numCameras > 1) {
                currentCameraId = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK)
                        ? Camera.CameraInfo.CAMERA_FACING_FRONT
                        : Camera.CameraInfo.CAMERA_FACING_BACK;
                restartPreviewMode();
            }
        });

        updateUI();
        return root;
    }

    private void handleMediaResult(Uri uri, String type) {
        if (uri == null) return;

        String displayName = "media_" + System.currentTimeMillis();
        try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) displayName = cursor.getString(nameIndex);
            }
        } catch (Exception ignored) {}

        File targetDir = new File(AppConfig.BASE_DIR);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
            targetDir.setReadable(true, false);
            targetDir.setWritable(true, false);
            targetDir.setExecutable(true, false);
        }

        String ext = "IMAGE".equals(type) ? ".jpg" : ".mp4";
        File localFile = new File(targetDir, System.currentTimeMillis() + ext);

        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(localFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            localFile.setReadable(true, false);
            localFile.setWritable(true, false);

            config.mediaPaths.add(localFile.getAbsolutePath());
            config.mediaTypes.add(type);
            config.mediaNames.add(displayName);
            config.selectedIndex = config.mediaPaths.size() - 1;
            config.save();

            updateUI();
            restartPreviewMode();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to load file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUI() {
        boolean isActive = config.enabled && new File(config.getActiveMediaPath()).exists();
        if (isActive) {
            tvBadgeDaemon.setText("● ACTIVE");
            tvBadgeDaemon.setTextColor(0xFF00E676);
            btnToggleVirtualCam.setText("Disable Virtual Camera");
            btnToggleVirtualCam.setBackgroundColor(0xFF8B0000);
        } else {
            tvBadgeDaemon.setText("● Not Started");
            tvBadgeDaemon.setTextColor(0xFF8A909E);
            btnToggleVirtualCam.setText("Enable Virtual Camera");
            btnToggleVirtualCam.setBackgroundColor(0xFF121620);
        }

        if (isPreviewRunning) {
            tvBadgePreviewState.setText("Preview ON");
            tvBadgePreviewState.setTextColor(0xFF00E676);
            llPreviewOverlay.setVisibility(View.GONE);
        } else {
            tvBadgePreviewState.setText("Preview OFF");
            tvBadgePreviewState.setTextColor(0xFF8A909E);
            llPreviewOverlay.setVisibility(View.VISIBLE);
        }

        renderMediaList();
    }

    private void renderMediaList() {
        llMediaList.removeAllViews();
        if (config.mediaPaths.isEmpty()) {
            llMediaList.addView(tvEmptyMedia);
            return;
        }

        for (int i = 0; i < config.mediaPaths.size(); i++) {
            final int index = i;
            RelativeLayout row = new RelativeLayout(requireContext());
            row.setPadding(24, 16, 24, 16);

            if (config.selectedIndex == index) {
                row.setBackgroundColor(0x3300E676);
            } else {
                row.setBackgroundColor(Color.TRANSPARENT);
            }

            LinearLayout leftContainer = new LinearLayout(requireContext());
            leftContainer.setOrientation(LinearLayout.HORIZONTAL);
            leftContainer.setGravity(Gravity.CENTER_VERTICAL);
            RelativeLayout.LayoutParams lpLeft = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lpLeft.addRule(RelativeLayout.ALIGN_PARENT_START);
            lpLeft.addRule(RelativeLayout.START_OF, 1000 + index);

            ImageView icon = new ImageView(requireContext());
            boolean isImage = "IMAGE".equals(config.mediaTypes.get(i));
            icon.setImageResource(isImage ? android.R.drawable.ic_menu_gallery : android.R.drawable.ic_media_play);
            icon.setColorFilter(config.selectedIndex == index ? 0xFF00E676 : 0xFF8A909E);
            LinearLayout.LayoutParams lpIcon = new LinearLayout.LayoutParams(36, 36);
            lpIcon.setMarginEnd(16);
            leftContainer.addView(icon, lpIcon);

            TextView title = new TextView(requireContext());
            title.setText(config.mediaNames.get(i));
            title.setTextColor(config.selectedIndex == index ? Color.WHITE : 0xFF8A909E);
            title.setTextSize(14f);
            title.setSingleLine(true);
            leftContainer.addView(title);

            row.addView(leftContainer, lpLeft);

            ImageButton btnDelete = new ImageButton(requireContext());
            btnDelete.setId(1000 + index);
            btnDelete.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            btnDelete.setColorFilter(0xFFFF2A42);
            btnDelete.setBackgroundColor(Color.TRANSPARENT);
            RelativeLayout.LayoutParams lpRight = new RelativeLayout.LayoutParams(48, 48);
            lpRight.addRule(RelativeLayout.ALIGN_PARENT_END);
            lpRight.addRule(RelativeLayout.CENTER_VERTICAL);
            row.addView(btnDelete, lpRight);

            row.setOnClickListener(v -> {
                config.selectedIndex = index;
                config.save();
                updateUI();
                restartPreviewMode();
            });

            btnDelete.setOnClickListener(v -> {
                File f = new File(config.mediaPaths.get(index));
                if (f.exists()) f.delete();
                config.mediaPaths.remove(index);
                config.mediaNames.remove(index);
                config.mediaTypes.remove(index);
                if (config.selectedIndex == index) {
                    config.selectedIndex = config.mediaPaths.isEmpty() ? -1 : 0;
                } else if (config.selectedIndex > index) {
                    config.selectedIndex--;
                }
                config.save();
                updateUI();
                restartPreviewMode();
            });

            llMediaList.addView(row);
        }
    }

    private void restartPreviewMode() {
        stopAllPreviews();
        if (!isPreviewRunning) return;

        if (config.enabled && config.selectedIndex != -1) {
            startVirtualPreview();
        } else {
            startRealCameraPreview();
        }
    }

    private void startVirtualPreview() {
        File file = new File(config.getActiveMediaPath());
        if (!file.exists()) {
            Toast.makeText(requireContext(), "Selected file not found", Toast.LENGTH_SHORT).show();
            return;
        }

        if ("IMAGE".equals(config.getActiveMediaType())) {
            previewTextureView.setVisibility(View.GONE);
            previewImageView.setVisibility(View.VISIBLE);
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap != null && config.rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(config.rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            }
            previewImageView.setImageBitmap(bitmap);
        } else {
            previewImageView.setVisibility(View.GONE);
            previewTextureView.setVisibility(View.VISIBLE);
            if (previewTextureView.isAvailable()) {
                playVideoPreview(previewTextureView.getSurfaceTexture());
            }
        }
    }

    private void startRealCameraPreview() {
        previewImageView.setVisibility(View.GONE);
        previewTextureView.setVisibility(View.VISIBLE);
        if (previewTextureView.isAvailable()) {
            openPhysicalCamera(previewTextureView.getSurfaceTexture());
        }
    }

    private void openPhysicalCamera(SurfaceTexture surfaceTexture) {
        try {
            mCamera = Camera.open(currentCameraId);
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(currentCameraId, info);

            int rotation = requireActivity().getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0: degrees = 0; break;
                case Surface.ROTATION_90: degrees = 90; break;
                case Surface.ROTATION_180: degrees = 180; break;
                case Surface.ROTATION_270: degrees = 270; break;
            }

            int result;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360;
                result = (360 - result) % 360;
            } else {
                result = (info.orientation - degrees + 360) % 360;
            }
            mCamera.setDisplayOrientation(result);
            mCamera.setPreviewTexture(surfaceTexture);
            mCamera.startPreview();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Camera unavailable: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void playVideoPreview(SurfaceTexture surfaceTexture) {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setSurface(new Surface(surfaceTexture));
            mediaPlayer.setDataSource(config.getActiveMediaPath());
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(config.volume / 100f, config.volume / 100f);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
        } catch (Exception ignored) {}
    }

    private void stopAllPreviews() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
                mCamera.release();
            } catch (Exception ignored) {}
            mCamera = null;
        }
        previewImageView.setVisibility(View.GONE);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        restartPreviewMode();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        stopAllPreviews();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAllPreviews();
    }
}
