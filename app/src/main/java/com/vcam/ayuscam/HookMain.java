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
    private static Context appContext;
    private static Thread imageRenderThread;
    private static volatile boolean renderImage = false;
    private static final Set<Class<?>> hooked_classes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    
    // Identifies ImageReader surfaces to prevent RGBA -> YUV format crashes
    private static final Set<Surface> imageReaderSurfaces = Collections.newSetFromMap(new WeakHashMap<>());
    
    // Playback state tracking
    private static Surface activePreviewSurface = null;
    private static Surface currentPlayingSurface = null;

    // Bitmap Caching Variables
    private static Bitmap cachedBitmap = null;
    private static String cachedImagePath = "";
    private static int cachedWidth = 0;
    private static int cachedHeight = 0;
    private static int cachedRotation = 0;
    private static String cachedScaleMode = "";

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
    
    // A single unified dummy surface to trick the hardware camera HAL
    private static Surface getFakeSurface() {
        if (fake_SurfaceTexture == null) {
            fake_SurfaceTexture = new SurfaceTexture(15);
            fake_Surface = new Surface(fake_SurfaceTexture);
        }
        return fake_Surface;
    }

    private static void writeLog(String text) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String logEntry = "[" + timestamp + "] " + text;
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
        
        // Track ImageReader surfaces so we don't accidentally try to push Video/Canvas pixels into them
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

        // Intercept CaptureRequest to align its surfaces with our fake session
        try {
            XposedHelpers.findAndHookMethod(CaptureRequest.Builder.class, "addTarget", Surface.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    Surface originalSurface = (Surface) param.args[0];
                    if (originalSurface == null) return;

                    // Grab the real preview surface to draw to it later
                    if (!imageReaderSurfaces.contains(originalSurface)) {
                        activePreviewSurface = originalSurface;
                    }

                    // Force the hardware request to use our dummy surface
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

            // Start playing the video exactly when the app builds the request
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
            
            // Clean up state on disconnect
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

        // 1. Standard Capture Session
        try { XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, listSessionHook); } catch (Throwable ignored) {}

        // 2. High Speed Capture Session
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { XposedHelpers.findAndHookMethod(deviceClass, "createConstrainedHighSpeedCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, listSessionHook); } catch (Throwable ignored) {}
        }

        // 3. Reprocessable Capture Session
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

        // 4. Capture Session by Output Configurations
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

        // 5. Reprocessable Session by Configurations
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

        // 6. Modern Session Configuration (Android P+)
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
            } catch (Exception ignored) {}
        } else if ("IMAGE".equals(config.getActiveMediaType())) {
            startImageRenderLoop(targetSurface, config);
        }
    }
    
    private static void stopMediaPlayback() {
        stopImageRenderLoop();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private static void startImageRenderLoop(Surface surface, AppConfig config) {
        renderImage = true;
        imageRenderThread = new Thread(() -> {
            while (renderImage) {
                try {
                    if (surface != null && surface.isValid()) {
                        Canvas canvas = surface.lockCanvas(null);
                        if (canvas != null) {
                            Bitmap bitmap = getCachedScaledBitmap(config.getActiveMediaPath(), canvas.getWidth(), canvas.getHeight(), config);
                            if (bitmap != null) {
                                canvas.drawBitmap(bitmap, 0, 0, null);
                            } else {
                                canvas.drawColor(Color.BLACK);
                            }
                            surface.unlockCanvasAndPost(canvas);
                        }
                    }
                    Thread.sleep(66);
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

    private static Bitmap getCachedScaledBitmap(String imagePath, int targetWidth, int targetHeight, AppConfig config) {
        if (cachedBitmap != null && imagePath.equals(cachedImagePath) && targetWidth == cachedWidth && targetHeight == cachedHeight && config.rotation == cachedRotation && config.scaleMode.equals(cachedScaleMode)) {
            return cachedBitmap;
        }

        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            cachedBitmap.recycle();
            cachedBitmap = null;
        }

        Bitmap original = BitmapFactory.decodeFile(imagePath);
        if (original == null) return null;

        int srcW = original.getWidth();
        int srcH = original.getHeight();

        if (srcW == targetWidth && srcH == targetHeight && config.rotation == 0) {
            cachedBitmap = original;
        } else {
            Matrix matrix = new Matrix();
            if (config.rotation != 0) {
                matrix.postRotate(config.rotation);
                Bitmap rotated = Bitmap.createBitmap(original, 0, 0, srcW, srcH, matrix, true);
                if (rotated != original) {
                    original.recycle();
                    original = rotated;
                }
                srcW = original.getWidth();
                srcH = original.getHeight();
                matrix.reset();
            }

            float scale;
            if ("STRETCH".equals(config.scaleMode)) {
                matrix.postScale((float) targetWidth / srcW, (float) targetHeight / srcH);
                cachedBitmap = Bitmap.createBitmap(original, 0, 0, srcW, srcH, matrix, true);
                if (cachedBitmap != original) original.recycle();
            } else {
                if ("FIT".equals(config.scaleMode)) {
                    scale = Math.min((float) targetWidth / srcW, (float) targetHeight / srcH);
                } else {
                    scale = Math.max((float) targetWidth / srcW, (float) targetHeight / srcH);
                }
                matrix.postScale(scale, scale);
                Bitmap scaled = Bitmap.createBitmap(original, 0, 0, srcW, srcH, matrix, true);
                if (scaled != original) original.recycle();

                if ("FILL".equals(config.scaleMode)) {
                    int x = Math.max(0, (scaled.getWidth() - targetWidth) / 2);
                    int y = Math.max(0, (scaled.getHeight() - targetHeight) / 2);
                    cachedBitmap = Bitmap.createBitmap(scaled, x, y, Math.min(targetWidth, scaled.getWidth()), Math.min(targetHeight, scaled.getHeight()));
                    if (cachedBitmap != scaled) scaled.recycle();
                } else {
                    cachedBitmap = scaled;
                }
            }
        }

        cachedImagePath = imagePath;
        cachedWidth = targetWidth;
        cachedHeight = targetHeight;
        cachedRotation = config.rotation;
        cachedScaleMode = config.scaleMode;

        return cachedBitmap;
    }
}
