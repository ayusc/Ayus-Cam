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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
    private static Context appContext;

    // Playback Components
    private static Thread renderThread;
    private static volatile boolean renderActive = false;
    private static MediaPlayer mMediaPlayer;

    // Data Streams
    public static volatile byte[] data_buffer = null;
    private static VideoToFrames dataDecoder;
    private static Surface c2_reader_Surface = null;

    private static final Set<Class<?>> hooked_classes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Surface> imageReaderSurfaces = Collections.newSetFromMap(new WeakHashMap<>());
    private static Surface activePreviewSurface = null;
    
    // Guards to prevent continuous looping bugs
    private static Surface currentPlayingSurface = null;
    private static Surface currentDataSurface = null;

    private static AppConfig cachedConfig = null;
    private static long lastCheckTime = 0;

    private static AppConfig getLiveConfig() {
        // Cache for 500ms to avoid brutal disk I/O loops, allowing smooth Pan/Zoom syncing
        if (cachedConfig == null || (System.currentTimeMillis() - lastCheckTime > 500)) {
            cachedConfig = AppConfig.load(appContext);
            lastCheckTime = System.currentTimeMillis();
        }
        return cachedConfig;
    }

    private static boolean isSubstitutionActive() {
        AppConfig config = getLiveConfig();
        if (!config.enabled) return false;
        String path = config.getActiveMediaPath();
        if (path == null || path.trim().isEmpty()) return false;
        File file = new File(path);
        return file.exists() && file.canRead();
    }

    private static void recreateFakeSurface() {
        if (fake_SurfaceTexture != null) {
            fake_SurfaceTexture.release();
            fake_SurfaceTexture = null;
        }
        if (fake_Surface != null) {
            fake_Surface.release();
            fake_Surface = null;
        }
        fake_SurfaceTexture = new SurfaceTexture(15);
        fake_Surface = new Surface(fake_SurfaceTexture);
    }

    private static SurfaceTexture getFakeSurfaceTexture() {
        if (fake_SurfaceTexture == null) recreateFakeSurface();
        return fake_SurfaceTexture;
    }

    private static Surface getFakeSurface() {
        if (fake_Surface == null) recreateFakeSurface();
        return fake_Surface;
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

        // --- CAMERA 1: PREVIEW CALLBACKS ---
        String[] callbackMethods = {"setPreviewCallbackWithBuffer", "setPreviewCallback", "setOneShotPreviewCallback"};
        for (String method : callbackMethods) {
            try {
                XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, method, Camera.PreviewCallback.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args[0] != null && isSubstitutionActive()) {
                            process_callback(param);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        // --- CAMERA 1: SET PREVIEW DISPLAY ---
        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay", android.view.SurfaceHolder.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    android.view.SurfaceHolder holder = (android.view.SurfaceHolder) param.args[0];
                    if (holder != null && holder.getSurface() != null) {
                        startMediaPlayback(holder.getSurface());
                    }
                    param.args[0] = null;
                    
                    recreateFakeSurface();
                    try { ((Camera) param.thisObject).setPreviewTexture(getFakeSurfaceTexture()); } catch (Exception e) {}
                    param.setResult(null);
                }
            });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    SurfaceTexture realST = (SurfaceTexture) param.args[0];
                    if (realST != null) {
                        startMediaPlayback(new Surface(realST));
                    }
                    recreateFakeSurface();
                    param.args[0] = getFakeSurfaceTexture();
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

                            Bitmap finalBitmap = generateStaticImage(config.getActiveMediaPath(), targetW, targetH, config);
                            if (finalBitmap != null) {
                                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                                byte[] jpegData = stream.toByteArray();
                                finalBitmap.recycle();

                                Object jpegCallback = param.args[3];
                                if (jpegCallback != null) XposedHelpers.callMethod(jpegCallback, "onPictureTaken", jpegData, camera);
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable ignored) {}

        // --- CAMERA 2: IMAGE READER HOOK ---
        try {
            XposedHelpers.findAndHookMethod(android.media.ImageReader.class, "getSurface", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Surface surface = (Surface) param.getResult();
                    if (surface != null) imageReaderSurfaces.add(surface);
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

                    String surfaceInfo = originalSurface.toString();
                    if (surfaceInfo.contains("Surface(name=null)") || imageReaderSurfaces.contains(originalSurface)) {
                        c2_reader_Surface = originalSurface;
                    } else {
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
                    if (originalSurface != null) {
                        if (originalSurface.equals(activePreviewSurface)) {
                            activePreviewSurface = null;
                            currentPlayingSurface = null;
                            stopMediaPlayback();
                        }
                        if (originalSurface.equals(c2_reader_Surface)) {
                            c2_reader_Surface = null;
                            currentDataSurface = null;
                            stopDataPlayback();
                        }
                    }
                    param.args[0] = getFakeSurface();
                }
            });

            XposedHelpers.findAndHookMethod(CaptureRequest.Builder.class, "build", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    
                    if (activePreviewSurface != null) startMediaPlayback(activePreviewSurface);
                    if (c2_reader_Surface != null) startDataPlayback(c2_reader_Surface);
                }
            });
        } catch (Throwable ignored) {}

        // Camera 2 Entry Hooks
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

    private void process_callback(XC_MethodHook.MethodHookParam param) {
        Class<?> cbClass = param.args[0].getClass();
        XposedHelpers.findAndHookMethod(cbClass, "onPreviewFrame", byte[].class, Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) {
                if (!isSubstitutionActive()) return;
                
                if (dataDecoder == null) {
                    AppConfig config = getLiveConfig();
                    if ("VIDEO".equals(config.getActiveMediaType())) {
                        dataDecoder = new VideoToFrames();
                        dataDecoder.setSaveFrames(VideoToFrames.OutputImageFormat.NV21);
                        dataDecoder.decode(config.getActiveMediaPath());
                    }
                }
                
                if (data_buffer != null && paramd.args[0] != null) {
                    byte[] target = (byte[]) paramd.args[0];
                    System.arraycopy(data_buffer, 0, target, 0, Math.min(data_buffer.length, target.length));
                }
            }
        });
    }

    private static void startDataPlayback(Surface target) {
        if (target == null || !target.isValid()) return;
        if (target == currentDataSurface && dataDecoder != null) return;
        
        currentDataSurface = target;
        stopDataPlayback();
        
        AppConfig config = getLiveConfig();
        if ("VIDEO".equals(config.getActiveMediaType())) {
            dataDecoder = new VideoToFrames();
            dataDecoder.setSaveFrames(VideoToFrames.OutputImageFormat.NV21);
            dataDecoder.setSurface(target);
            try {
                dataDecoder.decode(config.getActiveMediaPath());
            } catch (Throwable t) {}
        }
    }

    private static void stopDataPlayback() {
        if (dataDecoder != null) {
            dataDecoder.stopDecode();
            dataDecoder = null;
        }
    }

    private void hookCamera2DeviceCallbacks(Class<?> stateCallbackClass) {
        if (!hooked_classes.add(stateCallbackClass)) return;

        try {
            XposedHelpers.findAndHookMethod(stateCallbackClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    recreateFakeSurface();
                    hookCamera2Sessions(((CameraDevice) param.args[0]).getClass());
                }
            });

            XC_MethodHook cleanupHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    activePreviewSurface = null;
                    currentPlayingSurface = null;
                    c2_reader_Surface = null;
                    currentDataSurface = null;
                    
                    stopMediaPlayback();
                    stopDataPlayback();
                    recreateFakeSurface();
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
                if (isSubstitutionActive()) param.args[0] = Arrays.asList(getFakeSurface());
            }
        };

        try { XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, listSessionHook); } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { XposedHelpers.findAndHookMethod(deviceClass, "createConstrainedHighSpeedCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, listSessionHook); } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSession", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isSubstitutionActive()) param.args[1] = Arrays.asList(getFakeSurface());
                    }
                });
            } catch (Throwable ignored) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSessionByOutputConfigurations", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isSubstitutionActive()) param.args[0] = Arrays.asList(new OutputConfiguration(getFakeSurface()));
                    }
                });
            } catch (Throwable ignored) {}

            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isSubstitutionActive()) param.args[1] = Arrays.asList(new OutputConfiguration(getFakeSurface()));
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
                            fakeConfig.setSessionParameters(originalConfig.getSessionParameters());
                            param.args[0] = fakeConfig;
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void startMediaPlayback(Surface targetSurface) {
        if (targetSurface == null || !targetSurface.isValid()) return;
        if (targetSurface == currentPlayingSurface) return; 

        currentPlayingSurface = targetSurface;
        stopMediaPlayback();
        AppConfig config = getLiveConfig();

        if ("VIDEO".equals(config.getActiveMediaType())) {
            try {
                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setSurface(targetSurface);
                mMediaPlayer.setDataSource(config.getActiveMediaPath());
                mMediaPlayer.setLooping(true);
                mMediaPlayer.setVolume(config.volume / 100f, config.volume / 100f);
                mMediaPlayer.setOnPreparedListener(MediaPlayer::start);
                mMediaPlayer.prepareAsync();
            } catch (Exception e) {}
        } else if ("IMAGE".equals(config.getActiveMediaType())) {
            startImageRenderLoop(targetSurface, config);
        }
    }

    private static void stopMediaPlayback() {
        renderActive = false;
        if (renderThread != null) {
            try { renderThread.join(200); } catch (Exception ignored) {}
            renderThread = null;
        }
        
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.stop();
                mMediaPlayer.release();
            } catch (Exception ignored) {}
            mMediaPlayer = null;
        }
    }

    private static void startImageRenderLoop(Surface surface, AppConfig initialConfig) {
        renderActive = true;
        renderThread = new Thread(() -> {
            AppConfig localConfig = initialConfig;
            Bitmap activeBitmap = null;
            String loadedPath = "";

            while (renderActive) {
                try {
                    localConfig = getLiveConfig();
                    if (surface != null && surface.isValid()) {
                        Canvas canvas = null;
                        try { canvas = surface.lockCanvas(null); } catch (Exception e) {}
                        
                        if (canvas != null) {
                            try {
                                String targetPath = localConfig.getActiveMediaPath();
                                if (activeBitmap == null || !loadedPath.equals(targetPath)) {
                                    if (activeBitmap != null) activeBitmap.recycle();
                                    activeBitmap = BitmapFactory.decodeFile(targetPath);
                                    loadedPath = targetPath;
                                }

                                canvas.drawColor(Color.BLACK);

                                if (activeBitmap != null && localConfig.zoom > 0 && !localConfig.isPaused) {
                                    int viewW = canvas.getWidth();
                                    int viewH = canvas.getHeight();
                                    int bmpW = activeBitmap.getWidth();
                                    int bmpH = activeBitmap.getHeight();

                                    Matrix m = new Matrix();
                                    m.postTranslate(-bmpW / 2f, -bmpH / 2f);

                                    float scaleX = 1f, scaleY = 1f;
                                    if ("STRETCH".equals(localConfig.scaleMode)) {
                                        scaleX = (float) viewW / bmpW;
                                        scaleY = (float) viewH / bmpH;
                                    } else {
                                        float fitScale = Math.min((float) viewW / bmpW, (float) viewH / bmpH);
                                        float fillScale = Math.max((float) viewW / bmpW, (float) viewH / bmpH);
                                        float baseScale = "FIT".equals(localConfig.scaleMode) ? fitScale : fillScale;
                                        scaleX = baseScale;
                                        scaleY = baseScale;
                                    }
                                    m.postScale(scaleX, scaleY);

                                    float zoom = localConfig.zoom / 100f;
                                    m.postScale(zoom, zoom);
                                    m.postRotate(localConfig.rotation);
                                    m.postTranslate(viewW / 2f + localConfig.panX, viewH / 2f + localConfig.panY);

                                    canvas.drawBitmap(activeBitmap, m, null);
                                }
                            } finally {
                                surface.unlockCanvasAndPost(canvas);
                            }
                        }
                    }
                    Thread.sleep(33);
                } catch (Exception ignored) {}
            }
            if (activeBitmap != null && !activeBitmap.isRecycled()) activeBitmap.recycle();
        });
        renderThread.start();
    }

    private static Bitmap generateStaticImage(String imagePath, int targetWidth, int targetHeight, AppConfig config) {
        Bitmap original = BitmapFactory.decodeFile(imagePath);
        if (original == null) return null;

        Bitmap outBmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(outBmp);
        canvas.drawColor(Color.BLACK);

        Matrix m = new Matrix();
        m.postTranslate(-original.getWidth() / 2f, -original.getHeight() / 2f);

        float scaleX = 1f, scaleY = 1f;
        if ("STRETCH".equals(config.scaleMode)) {
            scaleX = (float) targetWidth / original.getWidth();
            scaleY = (float) targetHeight / original.getHeight();
        } else {
            float fitScale = Math.min((float) targetWidth / original.getWidth(), (float) targetHeight / original.getHeight());
            float fillScale = Math.max((float) targetWidth / original.getWidth(), (float) targetHeight / original.getHeight());
            float baseScale = "FIT".equals(config.scaleMode) ? fitScale : fillScale;
            scaleX = baseScale;
            scaleY = baseScale;
        }
        m.postScale(scaleX, scaleY);
        float zoom = config.zoom / 100f;
        m.postScale(zoom, zoom);
        m.postRotate(config.rotation);
        m.postTranslate(targetWidth / 2f + config.panX, targetHeight / 2f + config.panY);

        canvas.drawBitmap(original, m, null);
        original.recycle();

        return outBmp;
    }
}
