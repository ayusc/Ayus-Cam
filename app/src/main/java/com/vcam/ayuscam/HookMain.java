package com.vcam.ayuscam;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
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
import android.media.ImageReader;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
    public static Context toast_content;
    public static String video_path = Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/";

    private static Thread renderThread;
    private static volatile boolean renderActive = false;

    private static GLVideoRenderer glVideoRenderer;
    private static GLVideoRenderer glVideoRenderer_1 = null;
    private static GLVideoRenderer dataRenderer = null;
    private static GLVideoRenderer dataRenderer_1 = null;
    
    public static volatile byte[] data_buffer = null;
    private static VideoToFrames dataDecoder = null;

    private static Surface c2_reader_Surface = null;
    private static Surface c2_reader_Surface_1 = null;
    private static final Set<Class<?>> hooked_classes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Surface> imageReaderSurfaces = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static Surface activePreviewSurface = null;
    private static Surface activePreviewSurface_1 = null;
    private static Surface currentPlayingSurface = null;

    private static AppConfig cachedConfig = null;
    private static long lastCheckTime = 0;

    public static AppConfig getLiveConfig() {
        if (cachedConfig == null || (System.currentTimeMillis() - lastCheckTime > 50)) {
            cachedConfig = AppConfig.loadForHook(video_path);
            lastCheckTime = System.currentTimeMillis();
        }
        return cachedConfig;
    }

    private static boolean isSubstitutionActive() {
        AppConfig config = getLiveConfig();
        if (!config.enabled) return false;
        String path = config.getActiveMediaPath();
        if (path == null || path.trim().isEmpty()) return false;
        File f = new File(path);
        return f.exists() && f.canRead();
    }

    private static synchronized SurfaceTexture getFakeSurfaceTexture() {
        if (fake_SurfaceTexture == null) {
            fake_SurfaceTexture = new SurfaceTexture(15);
            fake_Surface = new Surface(fake_SurfaceTexture);
        }
        return fake_SurfaceTexture;
    }

    private static synchronized Surface getFakeSurface() {
        if (fake_Surface == null || !fake_Surface.isValid()) {
            if (fake_SurfaceTexture != null) {
                try { fake_SurfaceTexture.release(); } catch (Exception ignored) {}
            }
            fake_SurfaceTexture = new SurfaceTexture(15);
            fake_Surface = new Surface(fake_SurfaceTexture);
        }
        return fake_Surface;
    }

    private static boolean isReaderSurface(Surface surface) {
        if (surface == null) return false;
        if (imageReaderSurfaces.contains(surface)) return true;
        String desc = surface.toString().toLowerCase();
        return desc.contains("imagereader") || desc.contains("imageconsumer") || desc.contains("imageanalysis");
    }

    private static void classifySurface(Surface s) {
        if (s == null || s == getFakeSurface()) return;
        if (isReaderSurface(s)) {
            if (c2_reader_Surface == null) {
                c2_reader_Surface = s;
            } else if (!s.equals(c2_reader_Surface) && c2_reader_Surface_1 == null) {
                c2_reader_Surface_1 = s;
            }
        } else {
            if (activePreviewSurface == null) {
                activePreviewSurface = s;
            } else if (!s.equals(activePreviewSurface) && activePreviewSurface_1 == null) {
                activePreviewSurface_1 = s;
            }
        }
    }

    private static void resolveVideoPath(Context context) {
        String publicDir = Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/";
        String privateDir = null;
        try {
            File extFiles = context.getExternalFilesDir(null);
            if (extFiles != null) {
                File c1 = new File(extFiles, "Camera1");
                if (!c1.exists()) c1.mkdirs();
                privateDir = c1.getAbsolutePath() + "/";
            }
        } catch (Exception ignored) {}

        File pubMedia = new File(publicDir, "virtual.mp4");
        File pubMediaJpg = new File(publicDir, "virtual.jpg");
        File pubConfig = new File(publicDir, "config.json");

        if (pubMedia.canRead() || pubMediaJpg.canRead() || pubConfig.canRead()) {
            video_path = publicDir;
            if (privateDir != null) {
                final String fPrivateDir = privateDir;
                new Thread(() -> {
                    try {
                        copyFileIfReadable(pubConfig, new File(fPrivateDir, "config.json"));
                        copyFileIfReadable(pubMedia, new File(fPrivateDir, "virtual.mp4"));
                        copyFileIfReadable(pubMediaJpg, new File(fPrivateDir, "virtual.jpg"));
                    } catch (Exception ignored) {}
                }).start();
            }
            return;
        }
        if (privateDir != null) {
            video_path = privateDir;
        } else {
            video_path = publicDir;
        }
    }

    private static void copyFileIfReadable(File src, File dst) {
        if (src.exists() && src.canRead() && !dst.exists()) {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.vcam.ayuscam".equals(lpparam.packageName)) return;

        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args[0] instanceof Application) {
                        try {
                            toast_content = ((Application) param.args[0]).getApplicationContext();
                        } catch (Exception e) {
                            XposedBridge.log("AyusCam: " + e.toString());
                        }
                        if (toast_content != null) {
                            try {
                                Intent intent = new Intent("com.vcam.ayuscam.REGISTER_PACKAGE");
                                intent.putExtra("package_name", lpparam.packageName);
                                intent.setPackage("com.vcam.ayuscam");
                                toast_content.sendBroadcast(intent);
                            } catch (Exception ignored) {}
                            resolveVideoPath(toast_content);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

        // --- Prevent App Crashes from ImageReader NPE ---
        try {
            XposedHelpers.findAndHookMethod(ImageReader.class, "setOnImageAvailableListener", 
                ImageReader.OnImageAvailableListener.class, Handler.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] != null) {
                        final ImageReader.OnImageAvailableListener original = (ImageReader.OnImageAvailableListener) param.args[0];
                        ImageReader.OnImageAvailableListener proxy = new ImageReader.OnImageAvailableListener() {
                            @Override
                            public void onImageAvailable(ImageReader reader) {
                                try {
                                    original.onImageAvailable(reader);
                                } catch (NullPointerException e) {
                                    XposedBridge.log("AyusCam: Prevented target app NPE in onImageAvailable");
                                } catch (Exception e) {
                                    XposedBridge.log("AyusCam: Prevented target app Exception in onImageAvailable");
                                }
                            }
                        };
                        param.args[0] = proxy;
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            XC_MethodHook readerHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ImageReader reader = (ImageReader) param.getResult();
                    if (reader != null) {
                        try {
                            Surface s = reader.getSurface();
                            if (s != null) imageReaderSurfaces.add(s);
                        } catch (Exception ignored) {}
                    }
                }
            };
            XposedHelpers.findAndHookMethod(ImageReader.class, "newInstance", int.class, int.class, int.class, int.class, readerHook);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                XposedHelpers.findAndHookMethod(ImageReader.class, "newInstance", int.class, int.class, int.class, int.class, long.class, readerHook);
            }
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(ImageReader.class, "getSurface", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Surface surface = (Surface) param.getResult();
                    if (surface != null) imageReaderSurfaces.add(surface);
                }
            });
        } catch (Throwable ignored) {}

        try {
            XC_MethodHook guardHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        Throwable t = param.getThrowable();
                        if (t instanceof UnsupportedOperationException || t instanceof IllegalStateException) {
                            param.setThrowable(null);
                            param.setResult(null);
                        }
                    }
                }
            };
            XposedHelpers.findAndHookMethod(ImageReader.class, "acquireLatestImage", guardHook);
            XposedHelpers.findAndHookMethod(ImageReader.class, "acquireNextImage", guardHook);
        } catch (Throwable ignored) {}

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

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer", byte[].class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] != null && isSubstitutionActive()) {
                        param.args[0] = new byte[((byte[]) param.args[0]).length];
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay", android.view.SurfaceHolder.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    android.view.SurfaceHolder holder = (android.view.SurfaceHolder) param.args[0];
                    if (holder != null && holder.getSurface() != null) {
                        activePreviewSurface = holder.getSurface();
                    }
                    param.args[0] = null;
                    try { ((Camera) param.thisObject).setPreviewTexture(getFakeSurfaceTexture()); } catch (Exception ignored) {}
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
                    if (realST != null && realST != getFakeSurfaceTexture()) {
                        activePreviewSurface = new Surface(realST);
                    }
                    param.args[0] = getFakeSurfaceTexture();
                }
            });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isSubstitutionActive()) return;
                    if (activePreviewSurface != null) {
                        startMediaPlayback(activePreviewSurface);
                    }
                }
            });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "stopPreview", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    stopMediaPlayback();
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
                            Camera camera = (Camera) param.thisObject;
                            Camera.Size picSize = null;
                            try { picSize = camera.getParameters().getPictureSize(); } catch (Exception ignored) {}
                            int targetW = picSize != null ? picSize.width : 1920;
                            int targetH = picSize != null ? picSize.height : 1080;

                            Bitmap finalBitmap = null;
                            if ("IMAGE".equals(config.getActiveMediaType())) {
                                finalBitmap = generateStaticImage(config.getActiveMediaPath(), targetW, targetH, config);
                            } else {
                                try {
                                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                                    retriever.setDataSource(config.getActiveMediaPath());
                                    finalBitmap = retriever.getFrameAtTime(0);
                                    retriever.release();
                                } catch (Exception ignored) {}
                            }

                            if (finalBitmap != null) {
                                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                                byte[] jpegData = stream.toByteArray();
                                byte[] yuvData = rgb2YCbCr420(finalBitmap, targetW, targetH);
                                finalBitmap.recycle();

                                if (param.args[1] != null && yuvData != null) {
                                    XposedHelpers.callMethod(param.args[1], "onPictureTaken", yuvData, camera);
                                }
                                if (param.args[3] != null) {
                                    XposedHelpers.callMethod(param.args[3], "onPictureTaken", jpegData, camera);
                                }
                                param.setResult(null);
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
                    classifySurface(originalSurface);
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
                        } else if (originalSurface.equals(activePreviewSurface_1)) {
                            activePreviewSurface_1 = null;
                            if (glVideoRenderer_1 != null) { glVideoRenderer_1.release(); glVideoRenderer_1 = null; }
                        }

                        if (originalSurface.equals(c2_reader_Surface)) {
                            c2_reader_Surface = null;
                            if (dataRenderer != null) { dataRenderer.release(); dataRenderer = null; }
                        } else if (originalSurface.equals(c2_reader_Surface_1)) {
                            c2_reader_Surface_1 = null;
                            if (dataRenderer_1 != null) { dataRenderer_1.release(); dataRenderer_1 = null; }
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
                    
                    if (activePreviewSurface_1 != null && glVideoRenderer_1 == null) {
                        glVideoRenderer_1 = new GLVideoRenderer(activePreviewSurface_1, getLiveConfig().getActiveMediaPath());
                        glVideoRenderer_1.start();
                    }
                    
                    if (c2_reader_Surface != null && dataRenderer == null) {
                        if ("VIDEO".equals(getLiveConfig().getActiveMediaType())) {
                            dataRenderer = new GLVideoRenderer(c2_reader_Surface, getLiveConfig().getActiveMediaPath());
                            dataRenderer.start();
                        }
                    }
                    if (c2_reader_Surface_1 != null && dataRenderer_1 == null) {
                        if ("VIDEO".equals(getLiveConfig().getActiveMediaType())) {
                            dataRenderer_1 = new GLVideoRenderer(c2_reader_Surface_1, getLiveConfig().getActiveMediaPath());
                            dataRenderer_1.start();
                        }
                    }
                }
            });
        } catch (Throwable ignored) {}

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
                AppConfig config = getLiveConfig();
                if ("VIDEO".equals(config.getActiveMediaType())) {
                    if (dataDecoder == null) {
                        dataDecoder = new VideoToFrames();
                        dataDecoder.setSaveFrames(VideoToFrames.OutputImageFormat.NV21);
                        dataDecoder.decode(config.getActiveMediaPath());
                    }
                } else if ("IMAGE".equals(config.getActiveMediaType())) {
                    if (data_buffer == null) {
                        try {
                            Camera cam = (Camera) paramd.args[1];
                            int w = cam.getParameters().getPreviewSize().width;
                            int h = cam.getParameters().getPreviewSize().height;
                            Bitmap bmp = generateStaticImage(config.getActiveMediaPath(), w, h, config);
                            if (bmp != null) {
                                data_buffer = rgb2YCbCr420(bmp, w, h);
                                bmp.recycle();
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (data_buffer != null && paramd.args[0] != null) {
                    byte[] target = (byte[]) paramd.args[0];
                    System.arraycopy(data_buffer, 0, target, 0, Math.min(data_buffer.length, target.length));
                }
            }
        });
    }

    private void hookCamera2DeviceCallbacks(Class<?> stateCallbackClass) {
        if (!hooked_classes.add(stateCallbackClass)) return;
        try {
            XposedHelpers.findAndHookMethod(stateCallbackClass, "onOpened", CameraDevice.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    hookCamera2Sessions(((CameraDevice) param.args[0]).getClass());
                }
            });

            XC_MethodHook cleanupHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    activePreviewSurface = null;
                    activePreviewSurface_1 = null;
                    currentPlayingSurface = null;
                    c2_reader_Surface = null;
                    c2_reader_Surface_1 = null;

                    stopMediaPlayback();
                    if (glVideoRenderer_1 != null) { glVideoRenderer_1.release(); glVideoRenderer_1 = null; }
                    
                    if (dataRenderer != null) { dataRenderer.release(); dataRenderer = null; }
                    if (dataRenderer_1 != null) { dataRenderer_1.release(); dataRenderer_1 = null; }
                    if (dataDecoder != null) { dataDecoder.stopDecode(); dataDecoder = null; }
                }
            };
            XposedHelpers.findAndHookMethod(stateCallbackClass, "onClosed", CameraDevice.class, cleanupHook);
            XposedHelpers.findAndHookMethod(stateCallbackClass, "onDisconnected", CameraDevice.class, cleanupHook);
        } catch (Throwable ignored) {}
    }

    private void hookCamera2Sessions(Class<?> deviceClass) {
        if (!hooked_classes.add(deviceClass)) return;

        try {
            XposedHelpers.findAndHookMethod(deviceClass, "close", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    activePreviewSurface = null;
                    activePreviewSurface_1 = null;
                    currentPlayingSurface = null;
                    c2_reader_Surface = null;
                    c2_reader_Surface_1 = null;

                    stopMediaPlayback();
                    if (glVideoRenderer_1 != null) { glVideoRenderer_1.release(); glVideoRenderer_1 = null; }
                    
                    if (dataRenderer != null) { dataRenderer.release(); dataRenderer = null; }
                    if (dataRenderer_1 != null) { dataRenderer_1.release(); dataRenderer_1 = null; }
                    if (dataDecoder != null) { dataDecoder.stopDecode(); dataDecoder = null; }
                }
            });
        } catch (Throwable ignored) {}

        XC_MethodHook listSessionHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isSubstitutionActive()) return;
                if (param.args[0] instanceof List) {
                    List<?> list = (List<?>) param.args[0];
                    for (Object item : list) {
                        if (item instanceof Surface) classifySurface((Surface) item);
                        else if (item instanceof OutputConfiguration) classifySurface(((OutputConfiguration) item).getSurface());
                    }
                }
                param.args[0] = Arrays.asList(getFakeSurface());
            }
        };

        try { XposedHelpers.findAndHookMethod(deviceClass, "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, listSessionHook); } catch (Throwable ignored) {}
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { XposedHelpers.findAndHookMethod(deviceClass, "createConstrainedHighSpeedCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, listSessionHook); } catch (Throwable ignored) {}
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSession", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isSubstitutionActive()) return;
                        if (param.args[1] instanceof List) {
                            for (Object s : (List<?>) param.args[1]) {
                                if (s instanceof Surface) classifySurface((Surface) s);
                            }
                        }
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
                        if (param.args[0] instanceof List) {
                            for (Object oc : (List<?>) param.args[0]) {
                                if (oc instanceof OutputConfiguration) classifySurface(((OutputConfiguration) oc).getSurface());
                            }
                        }
                        param.args[0] = Arrays.asList(new OutputConfiguration(getFakeSurface()));
                    }
                });
            } catch (Throwable ignored) {}
            try {
                XposedHelpers.findAndHookMethod(deviceClass, "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isSubstitutionActive()) return;
                        if (param.args[1] instanceof List) {
                            for (Object oc : (List<?>) param.args[1]) {
                                if (oc instanceof OutputConfiguration) classifySurface(((OutputConfiguration) oc).getSurface());
                            }
                        }
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
                            List<OutputConfiguration> configs = originalConfig.getOutputConfigurations();
                            for (OutputConfiguration oc : configs) {
                                classifySurface(oc.getSurface());
                            }
                            SessionConfiguration safeFakeConfig = new SessionConfiguration(
                                    originalConfig.getSessionType(),
                                    Arrays.asList(new OutputConfiguration(getFakeSurface())),
                                    originalConfig.getExecutor(),
                                    originalConfig.getStateCallback()
                            );
                            param.args[0] = safeFakeConfig;
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
            glVideoRenderer = new GLVideoRenderer(targetSurface, config.getActiveMediaPath());
            glVideoRenderer.start();
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
        if (glVideoRenderer != null) {
            glVideoRenderer.release();
            glVideoRenderer = null;
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
                        try { canvas = surface.lockCanvas(null); } catch (Exception ignored) {}

                        if (canvas != null) {
                            try {
                                String targetPath = localConfig.getActiveMediaPath();
                                if (activeBitmap == null || !loadedPath.equals(targetPath)) {
                                    if (activeBitmap != null) activeBitmap.recycle();
                                    try (FileInputStream fis = new FileInputStream(targetPath)) {
                                        activeBitmap = BitmapFactory.decodeStream(fis);
                                    }
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
        try (FileInputStream fis = new FileInputStream(imagePath)) {
            Bitmap original = BitmapFactory.decodeStream(fis);
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
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] rgb2YCbCr420(Bitmap bitmap, int width, int height) {
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, Math.min(width, bitmap.getWidth()), Math.min(height, bitmap.getHeight()));
        int len = width * height;
        byte[] yuv = new byte[len * 3 / 2];

        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int rgb = pixels[i * width + j] & 0x00FFFFFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                yuv[i * width + j] = (byte) Math.max(16, Math.min(y, 255));
                if ((i & 1) == 0 && (j & 1) == 0) {
                    yuv[len + (i >> 1) * width + j] = (byte) Math.max(0, Math.min(v, 255));
                    yuv[len + (i >> 1) * width + j + 1] = (byte) Math.max(0, Math.min(u, 255));
                }
            }
        }
        return yuv;
    }

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

        private volatile boolean mRunning = false;
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
            android.opengl.Matrix.setIdentityM(mSTMatrix, 0);
            mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);
        }

        @Override
        public void run() {
            mRunning = true;
            initEGL();
            if (!mRunning) {
                startDirectFallbackPlayer();
                return;
            }
            initGL();
            mSurfaceTexture = new SurfaceTexture(mTextureID);
            mSurfaceTexture.setOnFrameAvailableListener(this);
            mInputSurface = new Surface(mSurfaceTexture);
            mMediaPlayer = new MediaPlayer();

            FileInputStream fis = null;
            try {
                mMediaPlayer.setSurface(mInputSurface);
                fis = new FileInputStream(mVideoPath);
                mMediaPlayer.setDataSource(fis.getFD());
                mMediaPlayer.setLooping(true);
                AppConfig startCfg = getLiveConfig();
                mMediaPlayer.setVolume(startCfg.volume / 100f, startCfg.volume / 100f);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        android.media.PlaybackParams pp = mMediaPlayer.getPlaybackParams();
                        pp.setSpeed(startCfg.speed);
                        mMediaPlayer.setPlaybackParams(pp);
                    } catch (Exception ignored) {}
                }
                mMediaPlayer.prepare();
                if (!startCfg.isPaused) mMediaPlayer.start();
            } catch (Exception e) {
                try {
                    mMediaPlayer.reset();
                    mMediaPlayer.setDataSource(mVideoPath);
                    mMediaPlayer.setSurface(mInputSurface);
                    mMediaPlayer.setLooping(true);
                    mMediaPlayer.prepare();
                    mMediaPlayer.start();
                } catch (Exception ex) {
                    mRunning = false;
                }
            } finally {
                if (fis != null) {
                    try { fis.close(); } catch (Exception ignored) {}
                }
            }

            long lastCheckMod = 0;
            AppConfig localConfig = getLiveConfig();
            boolean wasPaused = localConfig.isPaused;
            float wasSpeed = localConfig.speed;

            while (mRunning) {
                synchronized (mFrameSyncObject) {
                    try { mFrameSyncObject.wait(33); } catch (InterruptedException ignored) {}
                    if (mFrameAvailable) {
                        mFrameAvailable = false;
                        try {
                            mSurfaceTexture.updateTexImage();
                            mSurfaceTexture.getTransformMatrix(mSTMatrix);
                        } catch (Exception ignored) {}
                    }
                }

                if (System.currentTimeMillis() - lastCheckMod > 50) {
                    localConfig = getLiveConfig();
                    lastCheckMod = System.currentTimeMillis();
                    if (mMediaPlayer != null) {
                        if (localConfig.isPaused && !wasPaused) {
                            mMediaPlayer.pause();
                            wasPaused = true;
                        } else if (!localConfig.isPaused && wasPaused) {
                            mMediaPlayer.start();
                            wasPaused = false;
                        }
                        mMediaPlayer.setVolume(localConfig.volume / 100f, localConfig.volume / 100f);

                        if (localConfig.speed != wasSpeed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                android.media.PlaybackParams pp = mMediaPlayer.getPlaybackParams();
                                pp.setSpeed(localConfig.speed);
                                mMediaPlayer.setPlaybackParams(pp);
                            } catch (Exception ignored) {}
                            wasSpeed = localConfig.speed;
                        }
                    }
                }

                if (mRunning) {
                    drawFrame(localConfig);
                    EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
                }
            }

            if (mMediaPlayer != null) {
                try {
                    mMediaPlayer.stop();
                    mMediaPlayer.release();
                } catch (Exception ignored) {}
                mMediaPlayer = null;
            }
            releaseEGL();
        }

        private void startDirectFallbackPlayer() {
            FileInputStream fis = null;
            try {
                mMediaPlayer = new MediaPlayer();
                mMediaPlayer.setSurface(mOutputSurface);
                fis = new FileInputStream(mVideoPath);
                mMediaPlayer.setDataSource(fis.getFD());
                mMediaPlayer.setLooping(true);
                AppConfig cfg = getLiveConfig();
                mMediaPlayer.setVolume(cfg.volume / 100f, cfg.volume / 100f);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        android.media.PlaybackParams pp = mMediaPlayer.getPlaybackParams();
                        pp.setSpeed(cfg.speed);
                        mMediaPlayer.setPlaybackParams(pp);
                    } catch (Exception ignored) {}
                }
                mMediaPlayer.prepare();
                if (!cfg.isPaused) mMediaPlayer.start();
            } catch (Exception e) {
                try {
                    if (mMediaPlayer != null) {
                        mMediaPlayer.reset();
                        mMediaPlayer.setDataSource(mVideoPath);
                        mMediaPlayer.setSurface(mOutputSurface);
                        mMediaPlayer.setLooping(true);
                        mMediaPlayer.prepare();
                        mMediaPlayer.start();
                    }
                } catch (Exception ignored) {}
            } finally {
                if (fis != null) {
                    try { fis.close(); } catch (Exception ignored) {}
                }
            }
        }

        private void drawFrame(AppConfig config) {
            int[] width = new int[1];
            int[] height = new int[1];
            EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_WIDTH, width, 0);
            EGL14.eglQuerySurface(mEGLDisplay, mEGLSurface, EGL14.EGL_HEIGHT, height, 0);

            if (width[0] == 0 || height[0] == 0) return;

            GLES20.glViewport(0, 0, width[0], height[0]);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

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

            float[] mvpMatrix = new float[16];
            android.opengl.Matrix.setIdentityM(mvpMatrix, 0);

            float glPanX = config.panX / 500f;
            float glPanY = -config.panY / 500f;
            android.opengl.Matrix.translateM(mvpMatrix, 0, glPanX, glPanY, 0f);

            float zoom = config.zoom / 100f;
            android.opengl.Matrix.scaleM(mvpMatrix, 0, zoom, zoom, 1f);
            android.opengl.Matrix.rotateM(mvpMatrix, 0, -config.rotation, 0f, 0f, 1f);

            float scaleX = 1f, scaleY = 1f;
            int videoW = mMediaPlayer != null ? mMediaPlayer.getVideoWidth() : 0;
            int videoH = mMediaPlayer != null ? mMediaPlayer.getVideoHeight() : 0;

            if (videoW > 0 && videoH > 0) {
                float videoAspect = (float) videoW / videoH;
                float viewAspect = (float) width[0] / height[0];
                if ("STRETCH".equals(config.scaleMode)) {
                    scaleX = 1f; scaleY = 1f;
                } else if ("FIT".equals(config.scaleMode)) {
                    if (videoAspect > viewAspect) { scaleY = viewAspect / videoAspect; }
                    else { scaleX = videoAspect / viewAspect; }
                } else if ("FILL".equals(config.scaleMode)) {
                    if (videoAspect > viewAspect) { scaleX = videoAspect / viewAspect; }
                    else { scaleY = viewAspect / viewAspect; }
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
                mRunning = false; return;
            }

            int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            mEGLContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

            int[] surfaceAttribs = { EGL14.EGL_NONE };
            try {
                if (mOutputSurface == null || !mOutputSurface.isValid()) {
                    mRunning = false; return;
                }
                mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mOutputSurface, surfaceAttribs, 0);
            } catch (Exception e) {
                mRunning = false; return;
            }

            if (mEGLSurface == null || mEGLSurface == EGL14.EGL_NO_SURFACE) {
                mRunning = false; return;
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
            GLES20.glDisable(GLES20.GL_CULL_FACE);

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
                if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                }
                if (mEGLContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
                }
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(mEGLDisplay);
            }
            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLContext = EGL14.EGL_NO_CONTEXT;
            mEGLSurface = EGL14.EGL_NO_SURFACE;
        }
    }
}
