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
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
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
    
    private static Thread videoMonitorThread;
    private static volatile boolean monitorVideo = false;

    private static Context appContext;
    private static Thread imageRenderThread;
    private static volatile boolean renderImage = false;
    
    private static final Set<Class<?>> hooked_classes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final Set<Surface> imageReaderSurfaces = Collections.newSetFromMap(new WeakHashMap<>());

    private static Surface activePreviewSurface = null;
    private static Surface currentPlayingSurface = null;

    // Cache metrics
    private static Bitmap cachedBitmap = null;
    private static String cachedImagePath = "";
    private static int cachedWidth = 0;
    private static int cachedHeight = 0;
    private static int cachedRotation = 0;
    private static String cachedScaleMode = "";
    private static float cachedZoom = -1;
    private static int cachedPanX = -9999;
    private static int cachedPanY = -9999;

    private static AppConfig getLiveConfig() {
        return AppConfig.load();
    }

    private static boolean isSubstitutionActive() {
        AppConfig config = getLiveConfig();
        if (!config.enabled) return false;
        String path = config.getActiveMediaPath();
        if (path == null || path.trim().isEmpty()) return false;
        File file = new File(path);
        return file.exists() && file.canRead();
    }

    private static Surface getFakeSurface() {
        if (fake_SurfaceTexture == null) {
            fake_SurfaceTexture = new SurfaceTexture(15);
            fake_Surface = new Surface(fake_SurfaceTexture);
        }
        return fake_Surface;
    }

    // Fixed to be clean without brackets
    private static void writeLog(String text) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String logEntry = timestamp + " | " + text;
        XposedBridge.log("AyusCam: " + logEntry);
        try {
            File logFile = new File(AppConfig.LOG_FILE);
            if (!logFile.exists()) {
                if (logFile.getParentFile() != null) {
                    logFile.getParentFile().mkdirs();
                    logFile.getParentFile().setReadable(true, false);
                    logFile.getParentFile().setWritable(true, false);
                    logFile.getParentFile().setExecutable(true, false);
                }
                logFile.createNewFile();
            }
            logFile.setReadable(true, false);
            logFile.setWritable(true, false);
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(logEntry + "\n");
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.vcam.ayuscam".equals(lpparam.packageName)) return;

        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader,
                    "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Application) {
                                appContext = ((Application) param.args[0]).getApplicationContext();
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(android.media.ImageReader.class, "getSurface", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Surface surface = (Surface) param.getResult();
                    if (surface != null) {
                        imageReaderSurfaces.add(surface);
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(CaptureRequest.Builder.class, "addTarget", Surface.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    Surface originalSurface = (Surface) param.args[0];
                    if (originalSurface == null) return;

                    if (!imageReaderSurfaces.contains(originalSurface)) {
                        activePreviewSurface = originalSurface;
                    }
                    param.args[0] = getFakeSurface();
                }
            });

            XposedHelpers.findAndHookMethod(CaptureRequest.Builder.class, "removeTarget", Surface.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    Surface originalSurface = (Surface) param.args[0];
                    if (originalSurface != null && originalSurface.equals(activePreviewSurface)) {
                        activePreviewSurface = null;
                        currentPlayingSurface = null;
                        stopMediaPlayback();
                    }
                    param.args[0] = getFakeSurface();
                }
            });

            XposedHelpers.findAndHookMethod(CaptureRequest.Builder.class, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    if (activePreviewSurface != null) {
                        startMediaPlayback(activePreviewSurface);
                    }
                }
            });
        } catch (Throwable ignored) {}

        // Camera 1 Hooks
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader,
                    "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isSubstitutionActive()) return;
                            SurfaceTexture realST = (SurfaceTexture) param.args[0];
                            if (realST != null) {
                                startMediaPlayback(new Surface(realST));
                            }
                            param.args[0] = fake_SurfaceTexture != null ? fake_SurfaceTexture : (fake_SurfaceTexture = new SurfaceTexture(10));
                        }
                    });
        } catch (Throwable ignored) {}

        try {
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

                            Bitmap finalBitmap = getCachedScaledBitmap(config.getActiveMediaPath(), targetW, targetH, config);
                            if (finalBitmap != null) {
                                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                                byte[] jpegData = stream.toByteArray();

                                Object jpegCallback = param.args[3];
                                if (jpegCallback != null) {
                                    XposedHelpers.callMethod(jpegCallback, "onPictureTaken", jpegData, camera);
                                }
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        // Camera 2 Hooks
        try {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                    "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[1] != null) hookCamera2DeviceCallbacks(param.args[1].getClass());
                        }
                    });
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader,
                        "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args[2] != null) hookCamera2DeviceCallbacks(param.args[2].getClass());
                            }
                        });
            } catch (Throwable ignored) {}
        }
    }

    private void hookCamera2DeviceCallbacks(Class<?> stateCallbackClass) {
        if (!hooked_classes.add(stateCallbackClass)) return;

        try {
            XposedHelpers.findAndHookMethod(stateCallbackClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    CameraDevice device = (CameraDevice) param.args[0];
                    hookCamera2Sessions(device.getClass());
                }
            });

            XC_MethodHook cleanupHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    activePreviewSurface = null;
                    currentPlayingSurface = null;
                    stopMediaPlayback();
                }
            };
            XposedHelpers.findAndHookMethod(stateCallbackClass, "onClosed", CameraDevice.class, cleanupHook);
            XposedHelpers.findAndHookMethod(stateCallbackClass, "onDisconnected", CameraDevice.class, cleanupHook);

        } catch (Throwable ignored) {}
    }

    private void hookCamera2Sessions(Class<?> deviceClass) {
        if (!hooked_classes.add(deviceClass)) return;

        XC_MethodHook listSessionHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isSubstitutionActive()) return;
                param.args[0] = Arrays.asList(getFakeSurface());
            }
        };

        try { XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, listSessionHook); } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { XposedHelpers.findAndHookMethod(deviceClass, "createConstrainedHighSpeedCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, listSessionHook); } catch (Throwable ignored) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSession", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isSubstitutionActive()) return;
                        param.args[1] = Arrays.asList(getFakeSurface());
                    }
                });
            } catch (Throwable ignored) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSessionByOutputConfigurations", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isSubstitutionActive()) return;
                        param.args[0] = Arrays.asList(new OutputConfiguration(getFakeSurface()));
                    }
                });
            } catch (Throwable ignored) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isSubstitutionActive()) return;
                        param.args[1] = Arrays.asList(new OutputConfiguration(getFakeSurface()));
                    }
                });
            } catch (Throwable ignored) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", SessionConfiguration.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isSubstitutionActive()) return;
                        SessionConfiguration originalConfig = (SessionConfiguration) param.args[0];
                        if (originalConfig != null) {
                            SessionConfiguration fakeConfig = new SessionConfiguration(
                                    originalConfig.getSessionType(),
                                    Arrays.asList(new OutputConfiguration(getFakeSurface())),
                                    originalConfig.getExecutor(),
                                    originalConfig.getStateCallback()
                            );
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && originalConfig.getInputConfiguration() != null) {
                                    fakeConfig.setInputConfiguration(originalConfig.getInputConfiguration());
                                }
                            } catch (Exception ignored) {}

                            fakeConfig.setSessionParameters(originalConfig.getSessionParameters());
                            param.args[0] = fakeConfig;
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void startMediaPlayback(Surface targetSurface) {
        if (targetSurface == null || !targetSurface.isValid() || targetSurface == currentPlayingSurface) {
            return;
        }

        currentPlayingSurface = targetSurface;
        AppConfig config = getLiveConfig();
        stopMediaPlayback();

        if ("VIDEO".equals(config.getActiveMediaType())) {
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

                mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                mediaPlayer.prepareAsync();
                
                // Active configuration monitor daemon for dynamically handling Play/Pause requests
                monitorVideo = true;
                videoMonitorThread = new Thread(() -> {
                    boolean wasPaused = false;
                    while (monitorVideo && mediaPlayer != null) {
                        try {
                            AppConfig cfg = getLiveConfig();
                            if (cfg.isPaused && !wasPaused) {
                                mediaPlayer.pause();
                                wasPaused = true;
                            } else if (!cfg.isPaused && wasPaused) {
                                mediaPlayer.start();
                                wasPaused = false;
                            }
                            Thread.sleep(300);
                        } catch (Exception ignored) {}
                    }
                });
                videoMonitorThread.start();
                
            } catch (Exception ignored) {}
        } else if ("IMAGE".equals(config.getActiveMediaType())) {
            startImageRenderLoop(targetSurface, config);
        }
    }

    private static void stopMediaPlayback() {
        stopImageRenderLoop();
        
        monitorVideo = false;
        if (videoMonitorThread != null) {
            try { videoMonitorThread.join(200); } catch (Exception ignored) {}
            videoMonitorThread = null;
        }
        
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private static void startImageRenderLoop(Surface surface, AppConfig initialConfig) {
        renderImage = true;
        imageRenderThread = new Thread(() -> {
            long lastConfigMod = 0;
            AppConfig localConfig = initialConfig;

            while (renderImage) {
                try {
                    long currentMod = new File(AppConfig.CONFIG_FILE).lastModified();
                    if (currentMod > lastConfigMod) {
                        localConfig = AppConfig.load();
                        lastConfigMod = currentMod;
                    }

                    if (surface != null && surface.isValid()) {
                        Canvas canvas = surface.lockCanvas(null);
                        if (canvas != null) {
                            Bitmap bitmap = getCachedScaledBitmap(localConfig.getActiveMediaPath(), canvas.getWidth(), canvas.getHeight(), localConfig);
                            canvas.drawColor(Color.BLACK);
                            
                            // Prevent render when Zoom is exactly 0 or paused manually
                            if (bitmap != null && localConfig.zoom > 0 && !localConfig.isPaused) {
                                canvas.drawBitmap(bitmap, 0, 0, null);
                            }
                            surface.unlockCanvasAndPost(canvas);
                        }
                    }
                    Thread.sleep(33); // 30fps Loop Cycle
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

    // Now correctly utilizing Matrix scaling/translation on canvas to natively support Zoom, Pan, and Rotate properly
    private static Bitmap getCachedScaledBitmap(String imagePath, int targetWidth, int targetHeight, AppConfig config) {
        if (cachedBitmap != null && imagePath.equals(cachedImagePath) && targetWidth == cachedWidth && 
            targetHeight == cachedHeight && config.rotation == cachedRotation && 
            config.scaleMode.equals(cachedScaleMode) && config.zoom == cachedZoom && 
            config.panX == cachedPanX && config.panY == cachedPanY) {
            return cachedBitmap;
        }

        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            cachedBitmap.recycle();
            cachedBitmap = null;
        }

        Bitmap original = BitmapFactory.decodeFile(imagePath);
        if (original == null) return null;

        cachedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cachedBitmap);
        Matrix matrix = new Matrix();

        float srcW = original.getWidth();
        float srcH = original.getHeight();

        float scaleX = 1f, scaleY = 1f;

        if ("STRETCH".equals(config.scaleMode)) {
            scaleX = targetWidth / srcW;
            scaleY = targetHeight / srcH;
        } else {
            float fitScale = Math.min(targetWidth / srcW, targetHeight / srcH);
            float fillScale = Math.max(targetWidth / srcW, targetHeight / srcH);
            scaleX = scaleY = "FIT".equals(config.scaleMode) ? fitScale : fillScale;
        }

        float zoomFactor = config.zoom / 100f;
        scaleX *= zoomFactor;
        scaleY *= zoomFactor;

        float currentW = srcW * scaleX;
        float currentH = srcH * scaleY;

        float dx = (targetWidth - currentW) / 2f + config.panX;
        float dy = (targetHeight - currentH) / 2f + config.panY;

        matrix.postScale(scaleX, scaleY);
        matrix.postTranslate(dx, dy);

        if (config.rotation != 0) {
            matrix.postRotate(config.rotation, targetWidth / 2f, targetHeight / 2f);
        }

        canvas.drawBitmap(original, matrix, null);
        original.recycle();

        cachedImagePath = imagePath;
        cachedWidth = targetWidth;
        cachedHeight = targetHeight;
        cachedRotation = config.rotation;
        cachedScaleMode = config.scaleMode;
        cachedZoom = config.zoom;
        cachedPanX = config.panX;
        cachedPanY = config.panY;

        return cachedBitmap;
    }
}
