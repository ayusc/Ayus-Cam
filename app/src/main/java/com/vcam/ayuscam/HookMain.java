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
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
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
    
    private static Context appContext;
    
    // Media Playback control
    private static Thread renderThread;
    private static volatile boolean renderActive = false;
    
    // EGL Video renderer control
    private static GLVideoRenderer glVideoRenderer;

    private static final Set<Class<?>> hooked_classes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Surface> imageReaderSurfaces = Collections.newSetFromMap(new WeakHashMap<>());
    private static Surface activePreviewSurface = null;
    private static Surface currentPlayingSurface = null;

    private static AppConfig getLiveConfig() {
        return AppConfig.load(appContext);
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

    private static void writeLog(String text) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String logEntry = timestamp + "  " + text;
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
                                writeLog("Module initialized for: " + lpparam.packageName);
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

                            Bitmap finalBitmap = generateStaticImage(config.getActiveMediaPath(), targetW, targetH, config);
                            if (finalBitmap != null) {
                                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                                byte[] jpegData = stream.toByteArray();
                                finalBitmap.recycle();

                                Object jpegCallback = param.args[3];
                                if (jpegCallback != null) {
                                    writeLog("Virtual photo captured successfully");
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
                    writeLog("Camera interface opened: " + device.getId());
                    hookCamera2Sessions(device.getClass());
                }
            });

            XC_MethodHook cleanupHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    writeLog("Camera interface closed");
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
            writeLog("Starting video rendering stream");
            glVideoRenderer = new GLVideoRenderer(targetSurface, config.getActiveMediaPath());
            glVideoRenderer.start();
        } else if ("IMAGE".equals(config.getActiveMediaType())) {
            writeLog("Starting image rendering stream");
            startImageRenderLoop(targetSurface, config);
        }
    }

    private static void stopMediaPlayback() {
        if (renderThread != null || glVideoRenderer != null) {
            writeLog("Stopping active media streams");
        }
        
        renderActive = false;
        if (renderThread != null) {
            try { renderThread.join(200); } catch (Exception ignored) {}
            renderThread = null;
        }
        
        if (glVideoRenderer != null) {
            glVideoRenderer.release();
            glVideoRenderer = null;
        }
    }

    // High performance memory safe Canvas matrix drawing
    private static void startImageRenderLoop(Surface surface, AppConfig initialConfig) {
        renderActive = true;

        renderThread = new Thread(() -> {
            long lastConfigMod = 0;
            AppConfig localConfig = initialConfig;
            Bitmap activeBitmap = null;
            String loadedPath = "";

            while (renderActive) {
                try {
                    long currentMod = 0;
                    File globalConfig = new File(AppConfig.CONFIG_FILE);
                    if (globalConfig.exists() && globalConfig.canRead()) {
                        currentMod = globalConfig.lastModified();
                    } else if (appContext != null) {
                        File extFilesDir = appContext.getExternalFilesDir(null);
                        if (extFilesDir != null) {
                            File privConfig = new File(extFilesDir, "Camera1/config.json");
                            if (privConfig.exists() && privConfig.canRead()) {
                                currentMod = privConfig.lastModified();
                            }
                        }
                    }
                    if (currentMod == 0) currentMod = 1;

                    if (currentMod > lastConfigMod) {
                        localConfig = getLiveConfig();
                        lastConfigMod = currentMod;
                    }

                    if (surface != null && surface.isValid()) {
                        Canvas canvas = null;
                        try {
                            // Using standard Canvas lock for maximum compatibility across older apps
                            canvas = surface.lockCanvas(null);
                        } catch (Exception e) {
                            writeLog("Failed to lock surface canvas: " + e.getMessage());
                        }
                        
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
            if (activeBitmap != null && !activeBitmap.isRecycled()) {
                activeBitmap.recycle();
            }
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

    // --- EGL VIDEO RENDERER FOR HARDWARE MANIPULATION --- //
    private static class GLVideoRenderer extends Thread implements SurfaceTexture.OnFrameAvailableListener {
        private final Surface mOutputSurface;
        private final String mVideoPath;
        
        private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
        
        private int mProgram;
        private int mTextureID;
        private SurfaceTexture mSurfaceTexture;
        private Surface mInputSurface;
        private MediaPlayer mMediaPlayer;
        
        private boolean mRunning = false;
        private boolean mFrameAvailable = false;
        private final Object mFrameSyncObject = new Object();
        
        private float[] mSTMatrix = new float[16];
        
        private static final String VERTEX_SHADER =
                "uniform mat4 uSTMatrix;\n" +
                "uniform mat4 uMVPMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uMVPMatrix * aPosition;\n" +
                "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n";

        private final float[] mTriangleVerticesData = {
                -1.0f, -1.0f, 0, 0.f, 0.f,
                 1.0f, -1.0f, 0, 1.f, 0.f,
                -1.0f,  1.0f, 0, 0.f, 1.f,
                 1.0f,  1.0f, 0, 1.f, 1.f,
        };
        private FloatBuffer mTriangleVertices;

        public GLVideoRenderer(Surface targetSurface, String videoPath) {
            this.mOutputSurface = targetSurface;
            this.mVideoPath = videoPath;
            mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);
        }

        @Override
        public void run() {
            mRunning = true;
            initEGL();
            if (!mRunning) return;

            initGL();
            
            mSurfaceTexture = new SurfaceTexture(mTextureID);
            mSurfaceTexture.setOnFrameAvailableListener(this);
            mInputSurface = new Surface(mSurfaceTexture);
            
            mMediaPlayer = new MediaPlayer();
            try {
                mMediaPlayer.setSurface(mInputSurface);
                mMediaPlayer.setDataSource(mVideoPath);
                mMediaPlayer.setLooping(true);

                AppConfig startCfg = getLiveConfig();
                mMediaPlayer.setVolume(startCfg.volume / 100f, startCfg.volume / 100f);
                mMediaPlayer.prepare(); // Synchronous prepare since it's a local file
                
                if (!startCfg.isPaused) {
                    mMediaPlayer.start();
                }
            } catch (Exception e) {
                writeLog("Video decoder failure: " + e.getMessage());
                mRunning = false;
            }

            long lastConfigMod = 0;
            AppConfig localConfig = getLiveConfig();
            boolean wasPaused = localConfig.isPaused;

            while (mRunning) {
                boolean frameUpdated = false;
                synchronized (mFrameSyncObject) {
                    try { mFrameSyncObject.wait(33); } catch (InterruptedException e) {}
                    if (mFrameAvailable) {
                        mFrameAvailable = false;
                        try {
                            mSurfaceTexture.updateTexImage();
                            mSurfaceTexture.getTransformMatrix(mSTMatrix);
                            frameUpdated = true;
                        } catch (Exception e) {
                            writeLog("EGL Surface Texture update failed");
                        }
                    }
                }

                // Poll Config dynamically
                long currentMod = 0;
                File globalConfig = new File(AppConfig.CONFIG_FILE);
                if (globalConfig.exists() && globalConfig.canRead()) {
                    currentMod = globalConfig.lastModified();
                } else if (appContext != null) {
                    File extFilesDir = appContext.getExternalFilesDir(null);
                    if (extFilesDir != null) {
                        File privConfig = new File(extFilesDir, "Camera1/config.json");
                        if (privConfig.exists() && privConfig.canRead()) {
                            currentMod = privConfig.lastModified();
                        }
                    }
                }
                if (currentMod == 0) currentMod = 1;

                if (currentMod > lastConfigMod) {
                    localConfig = getLiveConfig();
                    lastConfigMod = currentMod;
                    
                    if (localConfig.isPaused && !wasPaused) {
                        mMediaPlayer.pause();
                        wasPaused = true;
                    } else if (!localConfig.isPaused && wasPaused) {
                        mMediaPlayer.start();
                        wasPaused = false;
                    }
                    mMediaPlayer.setVolume(localConfig.volume / 100f, localConfig.volume / 100f);
                }

                if (mRunning) {
                    drawFrame(localConfig);
                    EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
                }
            }
            
            if (mMediaPlayer != null) {
                mMediaPlayer.stop();
                mMediaPlayer.release();
            }
            releaseEGL();
        }

        private void drawFrame(AppConfig config) {
            int[] width = new int[1];
            int[] height = new int[1];
            EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_WIDTH, width, 0);
            EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_HEIGHT, height, 0);

            // Halt drawing if the surface hasn't received dimensions yet
            if (width[0] == 0 || height[0] == 0) return;

            GLES20.glViewport(0, 0, width[0], height[0]);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Avoid drawing if zoom is explicitly set to 0 by the user
            if (config.zoom == 0) return;

            GLES20.glUseProgram(mProgram);

            int muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
            int muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            int maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            int maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

            mTriangleVertices.position(0);
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 20, mTriangleVertices);
            GLES20.glEnableVertexAttribArray(maPositionHandle);

            mTriangleVertices.position(3);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 20, mTriangleVertices);
            GLES20.glEnableVertexAttribArray(maTextureHandle);

            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

            // Establish the complex Hardware Matrices
            float[] mvpMatrix = new float[16];
            android.opengl.Matrix.setIdentityM(mvpMatrix, 0);
            
            float glPanX = config.panX / 500f;
            float glPanY = -config.panY / 500f; // EGL Y axis is inverted naturally
            android.opengl.Matrix.translateM(mvpMatrix, 0, glPanX, glPanY, 0f);
            
            float zoom = config.zoom / 100f;
            android.opengl.Matrix.scaleM(mvpMatrix, 0, zoom, zoom, 1f);
            
            android.opengl.Matrix.rotateM(mvpMatrix, 0, -config.rotation, 0f, 0f, 1f);
            
            float scaleX = 1f, scaleY = 1f;
            int videoW = mMediaPlayer.getVideoWidth();
            int videoH = mMediaPlayer.getVideoHeight();
            
            if (videoW > 0 && videoH > 0) {
                float videoAspect = (float) videoW / videoH;
                float viewAspect = (float) width[0] / height[0];
                
                if ("STRETCH".equals(config.scaleMode)) {
                    scaleX = 1f; scaleY = 1f;
                } else if ("FIT".equals(config.scaleMode)) {
                    if (videoAspect > viewAspect) {
                        scaleY = viewAspect / videoAspect;
                    } else {
                        scaleX = videoAspect / viewAspect;
                    }
                } else if ("FILL".equals(config.scaleMode)) {
                    if (videoAspect > viewAspect) {
                        scaleX = videoAspect / viewAspect;
                    } else {
                        scaleY = viewAspect / videoAspect;
                    }
                }
            }
            android.opengl.Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f);
            
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            synchronized (mFrameSyncObject) {
                mFrameAvailable = true;
                mFrameSyncObject.notifyAll();
            }
        }

        public void release() {
            mRunning = false;
            interrupt();
        }

        private void initEGL() {
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1);

            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            
            if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                writeLog("EGL Config Initialization Failed");
                mRunning = false;
                return;
            }
            
            int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

            int[] surfaceAttribs = { EGL14.EGL_NONE };
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mOutputSurface, surfaceAttribs, 0);
            
            if (mEGLSurface == null || mEGLSurface == EGL14.EGL_NO_SURFACE) {
                writeLog("EGL Surface Creation Failed: " + EGL14.eglGetError());
                mRunning = false;
                return;
            }
            
            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext);
        }

        private void initGL() {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

            mProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(mProgram, vertexShader);
            GLES20.glAttachShader(mProgram, fragmentShader);
            GLES20.glLinkProgram(mProgram);
            
            GLES20.glDisable(GLES20.GL_CULL_FACE); // Ensure matrices aren't clipped inside out

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            mTextureID = textures[0];

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private int loadShader(int type, String shaderCode) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, shaderCode);
            GLES20.glCompileShader(shader);
            return shader;
        }

        private void releaseEGL() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mEGLDisplay);
            }
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLContext = EGL14.EGL_NO_CONTEXT;
            mEGLSurface = EGL14.EGL_NO_SURFACE;
        }
    }
}
