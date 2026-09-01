package com.vcam.ayuscam;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.io.File;

public class HomeFragment extends Fragment implements TextureView.SurfaceTextureListener {

    private AppConfig config;
    private TextureView previewTextureView;
    private ImageView previewImageView;
    private TextView tvPreviewStatus, tvEmptyMedia, tvMetaInfo;
    private MaterialButton btnTogglePreview, btnToggleVirtualCam, btnPickPhoto, btnPickVideo;
    private ImageButton btnRotateCamera;
    private LinearLayout llMediaList, llPreviewOverlay;

    private MediaPlayer mediaPlayer;
    private Camera mCamera;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    
    private boolean isPreviewRunning = false;

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
        tvMetaInfo = root.findViewById(R.id.tv_meta_info);
        
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

        btnRotateCamera.setOnClickListener(v -> {
            currentCameraId = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) 
                    ? Camera.CameraInfo.CAMERA_FACING_FRONT 
                    : Camera.CameraInfo.CAMERA_FACING_BACK;
            
            if (isPreviewRunning && !config.enabled) {
                restartPreviewMode();
            }
        });

        updateUI();
        return root;
    }

    private void handleMediaResult(Uri uri, String type) {
        if (uri == null) return;
        
        String realPath = getRealPathFromURI(uri);
        if (realPath == null) {
            Toast.makeText(requireContext(), "Error: Invalid file format or cloud storage.", Toast.LENGTH_LONG).show();
            return;
        }

        String displayName = "Unknown";
        try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) displayName = cursor.getString(nameIndex);
            }
        } catch (Exception e) { e.printStackTrace(); }

        config.mediaPaths.add(realPath);
        config.mediaTypes.add(type);
        config.mediaNames.add(displayName);
        config.selectedIndex = config.mediaPaths.size() - 1;
        config.save();

        updateUI();
        restartPreviewMode();
    }

    private String getRealPathFromURI(Uri uri) {
        if (android.provider.DocumentsContract.isDocumentUri(requireContext(), uri)) {
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                String docId = android.provider.DocumentsContract.getDocumentId(uri);
                String[] split = docId.split(":");
                String type = split[0];
                if ("primary".equalsIgnoreCase(type)) {
                    return android.os.Environment.getExternalStorageDirectory() + "/" + split[1];
                } else {
                    return "/storage/" + type + "/" + split[1];
                }
            } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                String id = android.provider.DocumentsContract.getDocumentId(uri);
                if (id != null && id.startsWith("raw:")) {
                    return id.substring(4);
                }
                String[] contentUriPrefixesToTry = new String[]{
                        "content://downloads/public_downloads",
                        "content://downloads/my_downloads"
                };
                for (String contentUriPrefix : contentUriPrefixesToTry) {
                    try {
                        Uri contentUri = android.content.ContentUris.withAppendedId(android.net.Uri.parse(contentUriPrefix), Long.parseLong(id));
                        String path = getDataColumn(contentUri, null, null);
                        if (path != null) return path;
                    } catch (Exception e) {}
                }
            } else if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                String docId = android.provider.DocumentsContract.getDocumentId(uri);
                String[] split = docId.split(":");
                String type = split[0];
                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }
                String selection = "_id=?";
                String[] selectionArgs = new String[]{split[1]};
                return getDataColumn(contentUri, selection, selectionArgs);
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataColumn(uri, null, null);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        return null;
    }

    private String getDataColumn(Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        String column = android.provider.MediaStore.MediaColumns.DATA;
        String[] projection = {column};
        try {
            cursor = requireContext().getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(index);
            }
        } catch (Exception e) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private void updateUI() {
        tvMetaInfo.setText(String.format("%d° | %d%% | Vol: %d%% | %s", config.rotation, config.zoom, config.volume, config.scaleMode));

        if (config.enabled) {
            btnToggleVirtualCam.setText("Disable Virtual Camera");
            btnToggleVirtualCam.setBackgroundColor(0xFFD32F2F);
        } else {
            btnToggleVirtualCam.setText("Enable Virtual Camera");
            btnToggleVirtualCam.setBackgroundColor(0xFF2E7D32);
        }

        if (isPreviewRunning) {
            btnTogglePreview.setText("Stop Preview");
            tvPreviewStatus.setText("");
            llPreviewOverlay.setVisibility(View.GONE);
            btnRotateCamera.setVisibility(View.VISIBLE);
        } else {
            btnTogglePreview.setText("Enable Preview");
            tvPreviewStatus.setText("Live Preview Disabled");
            llPreviewOverlay.setVisibility(View.VISIBLE);
            btnRotateCamera.setVisibility(View.GONE);
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
            TextView itemView = new TextView(requireContext());
            itemView.setText(config.mediaNames.get(i) + " [" + config.mediaTypes.get(i) + "]");
            itemView.setPadding(16, 24, 16, 24);
            itemView.setTextSize(14f);
            
            if (config.selectedIndex == index) {
                itemView.setBackgroundColor(0x33FF2A42);
                itemView.setTextColor(Color.WHITE);
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT);
                itemView.setTextColor(0xFF8A909E);
            }

            itemView.setOnClickListener(v -> {
                config.selectedIndex = index;
                config.save();
                updateUI();
                restartPreviewMode();
            });

            itemView.setOnLongClickListener(v -> {
                config.mediaPaths.remove(index);
                config.mediaNames.remove(index);
                config.mediaTypes.remove(index);
                if (config.selectedIndex == index) config.selectedIndex = -1;
                else if (config.selectedIndex > index) config.selectedIndex--;
                config.save();
                updateUI();
                restartPreviewMode();
                return true;
            });

            llMediaList.addView(itemView);
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
            Toast.makeText(requireContext(), "Media missing or unreadable.", Toast.LENGTH_SHORT).show();
            return;
        }

        if ("IMAGE".equals(config.getActiveMediaType())) {
            previewTextureView.setVisibility(View.GONE);
            previewImageView.setVisibility(View.VISIBLE);
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
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
            mCamera.setPreviewTexture(surfaceTexture);
            mCamera.startPreview();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Camera in use or permission denied.", Toast.LENGTH_SHORT).show();
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
        } catch (Exception e) {
            e.printStackTrace();
        }
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

    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { stopAllPreviews(); return true; }
    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopAllPreviews();
    }
}
