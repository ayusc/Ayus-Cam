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
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
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
        if (!config.enabled) {
            writeLog("[HOOK-STATUS] Disabled in configuration.");
            return false;
        }
        String path = config.getActiveMediaPath();
        if (path == null || path.isEmpty()) {
            writeLog("[HOOK-STATUS] No active media path configured.");
            return false;
        }
        File file = new File(path);
        boolean exists = file.exists() && file.canRead();
        writeLog("[HOOK-STATUS] Media path: " + path + " | Exists & Readable: " + exists);
        return exists;
    }

    private static void writeLog(String text) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String logEntry = "[" + timestamp + "] " + text;
        XposedBridge.log("AyusCam: " + logEntry);
        try (FileWriter fw = new FileWriter(AppConfig.LOG_FILE, true)) {
            fw.write(logEntry + "\n");
        } catch (Exception ignored) {}
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.vcam.ayuscam".equals(lpparam.packageName)) return;

        writeLog("[INIT] Loaded in package: " + lpparam.packageName + " | Process: " + lpparam.processName);

        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                    "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Application) {
                                appContext = ((Application) param.args[0]).getApplicationContext();
                                writeLog("[APP-ATTACH] Captured Application Context: " + appContext.getPackageName());
                            }
                        }
                    });
        } catch (Throwable t) {
            writeLog("[ERROR] Failed to hook callApplicationOnCreate: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            writeLog("[CAMERA1] setPreviewTexture called");
                            if (!isSubstitutionActive()) return;
                            SurfaceTexture realST = (SurfaceTexture) param.args[0];
                            if (realST != null) {
                                startMediaPlayback(new Surface(realST));
                            }
                            if (fake_SurfaceTexture == null) fake_SurfaceTexture = new SurfaceTexture(10);
                            param.args[0] = fake_SurfaceTexture;
                        }
                    });
        } catch (Throwable t) {
            writeLog("[ERROR] Failed Camera1 setPreviewTexture hook: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "takePicture", Camera.ShutterCallback.class, Camera.PictureCallback.class,
                    Camera.PictureCallback.class, Camera.PictureCallback.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            writeLog("[CAMERA1] takePicture called");
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
                                    writeLog("[CAMERA1] Injected custom JPEG bitmap into onPictureTaken");
                                }
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            writeLog("[ERROR] Failed Camera1 takePicture hook: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                    "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            writeLog("[CAMERA2] openCamera (Handler) called for CameraId: " + param.args[0]);
                            if (param.args[1] != null) hookCamera2DeviceCallbacks(param.args[1].getClass());
                        }
                    });
        } catch (Throwable t) {
            writeLog("[ERROR] Failed Camera2 openCamera (Handler) hook: " + t);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                        "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                writeLog("[CAMERA2] openCamera (Executor) called for CameraId: " + param.args[0]);
                                if (param.args[2] != null) hookCamera2DeviceCallbacks(param.args[2].getClass());
                            }
                        });
            } catch (Throwable t) {
                writeLog("[ERROR] Failed Camera2 openCamera (Executor) hook: " + t);
            }
        }
    }

    private void hookCamera2DeviceCallbacks(Class<?> stateCallbackClass) {
        if (!hooked_classes.add(stateCallbackClass)) return;
        writeLog("[HOOK] Hooking Device StateCallback: " + stateCallbackClass.getName());

        try {
            XposedHelpers.findAndHookMethod(stateCallbackClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    CameraDevice device = (CameraDevice) param.args[0];
                    writeLog("[CAMERA2] CameraDevice onOpened: " + device.getId() + " (" + device.getClass().getName() + ")");
                    hookCamera2Sessions(device.getClass());
                }
            });
        } catch (Throwable t) {
            writeLog("[ERROR] Failed hooking onOpened: " + t);
        }
    }

    private void hookCamera2Sessions(Class<?> deviceClass) {
        if (!hooked_classes.add(deviceClass)) return;
        writeLog("[HOOK] Hooking CameraDevice class: " + deviceClass.getName());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", SessionConfiguration.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        writeLog("[CAMERA2-A15] createCaptureSession(SessionConfiguration) triggered");
                        if (!isSubstitutionActive()) return;
                        SessionConfiguration sessionConfig = (SessionConfiguration) param.args[0];
                        if (sessionConfig != null) {
                            List<OutputConfiguration> configs = sessionConfig.getOutputConfigurations();
                            writeLog("[CAMERA2-A15] SessionConfiguration outputs count: " + (configs != null ? configs.size() : 0));
                            if (configs != null && !configs.isEmpty()) {
                                Surface targetSurface = configs.get(0).getSurface();
                                if (targetSurface != null && targetSurface.isValid()) {
                                    writeLog("[CAMERA2-A15] Hijacking SessionConfiguration output surface");
                                    startMediaPlayback(targetSurface);
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                writeLog("[ERROR] Failed hooking createCaptureSession(SessionConfiguration): " + t);
            }
        }

        try {
            XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    writeLog("[CAMERA2] createCaptureSession(List) triggered");
                    if (!isSubstitutionActive()) return;
                    List<Surface> originalSurfaces = (List<Surface>) param.args[0];
                    if (originalSurfaces != null && !originalSurfaces.isEmpty()) {
                        writeLog("[CAMERA2] Hijacking direct Surface list (count: " + originalSurfaces.size() + ")");
                        startMediaPlayback(originalSurfaces.get(0));
                    }
                }
            });
        } catch (Throwable t) {
            writeLog("[ERROR] Failed hooking createCaptureSession(List): " + t);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSessionByOutputConfigurations", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        writeLog("[CAMERA2] createCaptureSessionByOutputConfigurations triggered");
                        if (!isSubstitutionActive()) return;
                        List<OutputConfiguration> configs = (List<OutputConfiguration>) param.args[0];
                        if (configs != null && !configs.isEmpty()) {
                            Surface targetSurface = configs.get(0).getSurface();
                            if (targetSurface != null && targetSurface.isValid()) {
                                writeLog("[CAMERA2] Hijacking OutputConfiguration surface");
                                startMediaPlayback(targetSurface);
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                writeLog("[ERROR] Failed hooking createCaptureSessionByOutputConfigurations: " + t);
            }
        }
    }

    private static void startMediaPlayback(Surface targetSurface) {
        AppConfig config = getLiveConfig();
        stopImageRenderLoop();

        writeLog("[PLAYBACK] Initializing media injection. Mode: " + config.getActiveMediaType() + " | Path: " + config.getActiveMediaPath());

        if ("VIDEO".equals(config.getActiveMediaType())) {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.stop();
                    mediaPlayer.reset();
                    mediaPlayer.release();
                } catch (Exception ignored) {}
            }
            mediaPlayer = new MediaPlayer();
            try {
                mediaPlayer.setSurface(targetSurface);
                mediaPlayer.setDataSource(config.getActiveMediaPath());
                mediaPlayer.setLooping(true);
                mediaPlayer.setVolume(config.volume / 100f, config.volume / 100f);

                if ("FILL".equals(config.scaleMode)) {
                    mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                } else {
                    mediaPlayer.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                }

                mediaPlayer.setOnPreparedListener(mp -> {
                    mp.start();
                    writeLog("[PLAYBACK-SUCCESS] Video playback started dynamically into camera stream");
                });
                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    writeLog("[PLAYBACK-ERROR] MediaPlayer Error: what=" + what + " extra=" + extra);
                    return false;
                });
                mediaPlayer.prepareAsync();
            } catch (Exception e) {
                writeLog("[PLAYBACK-EXCEPTION] Failed starting video playback: " + e);
            }
        } else if ("IMAGE".equals(config.getActiveMediaType())) {
            startImageRenderLoop(targetSurface, config);
        }
    }

    private static void startImageRenderLoop(Surface surface, AppConfig config) {
        renderImage = true;
        imageRenderThread = new Thread(() -> {
            writeLog("[IMAGE-LOOP] Rendering loop started");
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
                    Thread.sleep(66);
                } catch (Exception e) {
                    writeLog("[IMAGE-LOOP-EXCEPTION] " + e);
                }
            }
            writeLog("[IMAGE-LOOP] Rendering loop terminated");
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
        if (original == null) {
            writeLog("[SCALE-ERROR] Failed to decode bitmap: " + imagePath);
            return null;
        }

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
