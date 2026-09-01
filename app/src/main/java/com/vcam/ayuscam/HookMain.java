package com.vcam.ayuscam;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.params.OutputConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static Surface fake_Surface;
    private static SurfaceTexture fake_SurfaceTexture;
    private static MediaPlayer mediaPlayer;
    private static Context appContext;
    
    private static Thread imageRenderThread;
    private static volatile boolean renderImage = false;

    private static final Set<Class<?>> hooked_classes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static AppConfig getLiveConfig() {
        return AppConfig.load();
    }

    private static boolean isSubstitutionActive() {
        AppConfig config = getLiveConfig();
        if (!config.enabled) return false;
        String path = config.getActiveMediaPath();
        if (path == null || path.isEmpty()) return false;
        File file = new File(path);
        return file.exists() && file.canRead();
    }

    private static void writeLog(String text) {
        try (FileWriter fw = new FileWriter(AppConfig.LOG_FILE, true)) {
            fw.write(text + "\n");
        } catch (Exception ignored) {}
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.vcam.ayuscam".equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args[0] instanceof Application) {
                            appContext = ((Application) param.args[0]).getApplicationContext();
                        }
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isSubstitutionActive()) return;
                        SurfaceTexture realST = (SurfaceTexture) param.args[0];
                        if (realST != null) {
                            startMediaPlayback(new Surface(realST));
                        }
                        // Redirect real hardware camera to the void
                        if (fake_SurfaceTexture == null) fake_SurfaceTexture = new SurfaceTexture(10);
                        param.args[0] = fake_SurfaceTexture;
                    }
                });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                "takePicture", Camera.ShutterCallback.class, Camera.PictureCallback.class,
                Camera.PictureCallback.class, Camera.PictureCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isSubstitutionActive()) return;
                        AppConfig config = getLiveConfig();
                        if (!"IMAGE".equals(config.getActiveMediaType())) return;

                        Camera camera = (Camera) param.thisObject;
                        Camera.Size picSize = camera.getParameters().getPictureSize();
                        int targetW = picSize != null ? picSize.width : 1920;
                        int targetH = picSize != null ? picSize.height : 1080;

                        Bitmap finalBitmap = getScaledReplacementBitmap(config.getActiveMediaPath(), targetW, targetH, config);
                        if (finalBitmap != null) {
                            ByteArrayOutputStream stream = new ByteArrayOutputStream();
                            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                            byte[] jpegData = stream.toByteArray();
                            
                            Object jpegCallback = param.args[3];
                            if (jpegCallback != null) {
                                XposedHelpers.callMethod(jpegCallback, "onPictureTaken", jpegData, camera);
                            }
                            param.setResult(null); // Stop real photo capture
                        }
                    }
                });
        
        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[1] != null) hookCamera2DeviceCallbacks(param.args[1].getClass());
                    }
                });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                    "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[2] != null) hookCamera2DeviceCallbacks(param.args[2].getClass());
                        }
                    });
        }
    }

    private void hookCamera2DeviceCallbacks(Class<?> stateCallbackClass) {
        if (!hooked_classes.add(stateCallbackClass)) return;
        XposedHelpers.findAndHookMethod(stateCallbackClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                CameraDevice device = (CameraDevice) param.args[0];
                hookCamera2Sessions(device.getClass());
            }
        });
    }

    private void hookCamera2Sessions(Class<?> deviceClass) {
        if (!hooked_classes.add(deviceClass)) return;
        
        // Standard session hook
        XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isSubstitutionActive()) return;
                List<Surface> originalSurfaces = (List<Surface>) param.args[0];
                if (originalSurfaces != null && !originalSurfaces.isEmpty()) {
                    startMediaPlayback(originalSurfaces.get(0));
                    
                    if (fake_SurfaceTexture != null) fake_SurfaceTexture.release();
                    fake_SurfaceTexture = new SurfaceTexture(15);
                    fake_Surface = new Surface(fake_SurfaceTexture);
                    param.args[0] = Collections.singletonList(fake_Surface);
                }
            }
        });

        // Android N+ session hook with OutputConfigurations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSessionByOutputConfigurations", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    List<OutputConfiguration> configs = (List<OutputConfiguration>) param.args[0];
                    if (configs != null && !configs.isEmpty()) {
                        startMediaPlayback(configs.get(0).getSurface());
                        
                        if (fake_SurfaceTexture != null) fake_SurfaceTexture.release();
                        fake_SurfaceTexture = new SurfaceTexture(15);
                        fake_Surface = new Surface(fake_SurfaceTexture);
                        param.args[0] = Collections.singletonList(new OutputConfiguration(fake_Surface));
                    }
                }
            });
        }
    }

    private static void startMediaPlayback(Surface targetSurface) {
        AppConfig config = getLiveConfig();
        stopImageRenderLoop();
        
        if ("VIDEO".equals(config.getActiveMediaType())) {
            if (mediaPlayer != null) mediaPlayer.release();
            mediaPlayer = new MediaPlayer();
            try {
                mediaPlayer.setSurface(targetSurface);
                mediaPlayer.setDataSource(config.getActiveMediaPath());
                mediaPlayer.setLooping(true);
                mediaPlayer.setVolume(config.volume / 100f, config.volume / 100f);
                
                // Native Video Scaling Engine (Fixes Aspect Ratio Stretching)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    if ("FILL".equals(config.scaleMode)) {
                        mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    } else {
                        mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    }
                }
                
                mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                writeLog("[Camera Hook] Playback Error: " + e.getMessage());
            }
        } else if ("IMAGE".equals(config.getActiveMediaType())) {
            startImageRenderLoop(targetSurface, config);
        }
    }

    // Runs a background thread to continually draw the scaled image to the camera feed surface
    private static void startImageRenderLoop(Surface surface, AppConfig config) {
        renderImage = true;
        imageRenderThread = new Thread(() -> {
            while (renderImage) {
                try {
                    if (surface != null && surface.isValid()) {
                        Canvas canvas = surface.lockCanvas(null);
                        if (canvas != null) {
                            Bitmap bitmap = getScaledReplacementBitmap(config.getActiveMediaPath(), canvas.getWidth(), canvas.getHeight(), config);
                            if (bitmap != null) {
                                canvas.drawBitmap(bitmap, 0, 0, null);
                            } else {
                                canvas.drawColor(Color.BLACK);
                            }
                            surface.unlockCanvasAndPost(canvas);
                        }
                    }
                    Thread.sleep(100); // Maintain 10 FPS
                } catch (Exception ignored) {}
            }
        });
        imageRenderThread.start();
    }

    private static void stopImageRenderLoop() {
        renderImage = false;
        if (imageRenderThread != null) {
            try { imageRenderThread.join(200); } catch (Exception ignored) {}
            imageRenderThread = null;
        }
    }

    private static Bitmap getScaledReplacementBitmap(String imagePath, int targetWidth, int targetHeight, AppConfig config) {
        Bitmap original = BitmapFactory.decodeFile(imagePath);
        if (original == null) return null;

        int srcW = original.getWidth();
        int srcH = original.getHeight();
        if (srcW == targetWidth && srcH == targetHeight && config.rotation == 0) return original;

        Matrix matrix = new Matrix();
        
        if (config.rotation != 0) {
            matrix.postRotate(config.rotation);
            original = Bitmap.createBitmap(original, 0, 0, srcW, srcH, matrix, true);
            srcW = original.getWidth();
            srcH = original.getHeight();
            matrix.reset();
        }

        float scale;
        if ("STRETCH".equals(config.scaleMode)) {
            matrix.postScale((float) targetWidth / srcW, (float) targetHeight / srcH);
            return Bitmap.createBitmap(original, 0, 0, srcW, srcH, matrix, true);
        } else if ("FIT".equals(config.scaleMode)) {
            scale = Math.min((float) targetWidth / srcW, (float) targetHeight / srcH);
        } else { 
            // FILL (Default)
            scale = Math.max((float) targetWidth / srcW, (float) targetHeight / srcH);
        }

        matrix.postScale(scale, scale);
        Bitmap scaled = Bitmap.createBitmap(original, 0, 0, srcW, srcH, matrix, true);

        if ("FILL".equals(config.scaleMode)) {
            int x = Math.max(0, (scaled.getWidth() - targetWidth) / 2);
            int y = Math.max(0, (scaled.getHeight() - targetHeight) / 2);
            return Bitmap.createBitmap(scaled, x, y, targetWidth, targetHeight);
        }

        return scaled;
    }
}
