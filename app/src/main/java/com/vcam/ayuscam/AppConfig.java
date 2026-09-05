package com.vcam.ayuscam;

import android.content.Context;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    public static final String BASE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/";
    public static final String CONFIG_FILE = BASE_DIR + "config.json";
    public static final String LOG_FILE = BASE_DIR + "daemon.log";

    public boolean enabled = true;
    public List<String> mediaPaths = new ArrayList<>();
    public List<String> mediaTypes = new ArrayList<>();
    public List<String> mediaNames = new ArrayList<>();
    public List<String> scopedPackages = new ArrayList<>();
    public int selectedIndex = 0;
    public int rotation = 0;
    public int zoom = 100;
    public int volume = 0;
    public float speed = 1.0f;
    public int panX = 0;
    public int panY = 0;
    public String scaleMode = "FILL";
    public boolean showHud = false;
    public boolean disableToast = false;
    public boolean isPaused = false;

    private static long lastConfigFileModTime = 0;
    private static AppConfig cachedHookConfig = null;

    public String getActiveMediaType() {
        if (selectedIndex >= 0 && selectedIndex < mediaTypes.size()) return mediaTypes.get(selectedIndex);
        if (!mediaTypes.isEmpty()) return mediaTypes.get(0);
        return "VIDEO";
    }

    public String getActiveMediaPath() {
        if (selectedIndex >= 0 && selectedIndex < mediaPaths.size()) return mediaPaths.get(selectedIndex);
        if (!mediaPaths.isEmpty()) return mediaPaths.get(0);
        return "";
    }

    public static AppConfig load() {
        return load(null);
    }

    public static AppConfig loadForHook(String activeDir) {
        File privConfig = new File(activeDir, "config.json");
        File pubConfig = new File(BASE_DIR, "config.json");
        File configFileToUse = (privConfig.exists() && privConfig.canRead()) ? privConfig : null;
        
        if (configFileToUse == null && pubConfig.exists() && pubConfig.canRead()) {
            configFileToUse = pubConfig;
        }

        if (configFileToUse != null) {
            long currentModTime = configFileToUse.lastModified();
            if (cachedHookConfig != null && currentModTime == lastConfigFileModTime) {
                return cachedHookConfig;
            }
            
            AppConfig config = new AppConfig();
            try (FileInputStream fis = new FileInputStream(configFileToUse);
                 BufferedReader br = new BufferedReader(new InputStreamReader(fis))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JSONObject json = new JSONObject(sb.toString());

                config.enabled = json.optBoolean("enabled", true);
                config.rotation = json.optInt("rotation", 0);
                config.zoom = json.optInt("zoom", 100);
                config.volume = json.optInt("volume", 0);
                config.speed = (float) json.optDouble("speed", 1.0);
                config.panX = json.optInt("panX", 0);
                config.panY = json.optInt("panY", 0);
                config.scaleMode = json.optString("scaleMode", "FILL");
                config.isPaused = json.optBoolean("isPaused", false);

                String mediaType = json.optString("activeMediaType", "VIDEO");
                String ext = "IMAGE".equals(mediaType) ? ".jpg" : ".mp4";
                File targetMedia = new File(activeDir, "virtual" + ext);

                if (!targetMedia.exists() || !targetMedia.canRead()) {
                    targetMedia = new File(BASE_DIR, "virtual" + ext);
                }

                if (!targetMedia.exists() || !targetMedia.canRead()) {
                    File altMedia = new File(activeDir, "1000.bmp");
                    if (altMedia.exists() && altMedia.canRead()) {
                        targetMedia = altMedia;
                        mediaType = "IMAGE";
                    } else {
                        altMedia = new File(BASE_DIR, "1000.bmp");
                        if (altMedia.exists() && altMedia.canRead()) {
                            targetMedia = altMedia;
                            mediaType = "IMAGE";
                        }
                    }
                }

                if (targetMedia.exists() && targetMedia.canRead()) {
                    config.mediaPaths.add(targetMedia.getAbsolutePath());
                    config.mediaTypes.add(mediaType);
                    config.selectedIndex = 0;
                    
                    lastConfigFileModTime = currentModTime;
                    cachedHookConfig = config;
                    return config;
                }
            } catch (Exception ignored) {}
        }

        AppConfig fallbackConfig = new AppConfig();
        File[] candidateDirs = new File[]{new File(activeDir), new File(BASE_DIR)};
        for (File dir : candidateDirs) {
            if (!dir.exists() || !dir.canRead()) continue;
            File vid = new File(dir, "virtual.mp4");
            if (vid.exists() && vid.canRead()) {
                fallbackConfig.enabled = true;
                fallbackConfig.mediaPaths.add(vid.getAbsolutePath());
                fallbackConfig.mediaTypes.add("VIDEO");
                fallbackConfig.selectedIndex = 0;
                return fallbackConfig;
            }
            File img = new File(dir, "virtual.jpg");
            if (img.exists() && img.canRead()) {
                fallbackConfig.enabled = true;
                fallbackConfig.mediaPaths.add(img.getAbsolutePath());
                fallbackConfig.mediaTypes.add("IMAGE");
                fallbackConfig.selectedIndex = 0;
                return fallbackConfig;
            }
        }
        fallbackConfig.enabled = false;
        return fallbackConfig;
    }

    public static AppConfig load(Context context) {
        AppConfig config = new AppConfig();
        File globalFile = new File(CONFIG_FILE);

        if (globalFile.exists() && globalFile.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(globalFile))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                JSONObject json = new JSONObject(sb.toString());

                config.enabled = json.optBoolean("enabled", true);
                config.selectedIndex = json.optInt("selectedIndex", 0);
                config.rotation = json.optInt("rotation", 0);
                config.zoom = json.optInt("zoom", 100);
                config.volume = json.optInt("volume", 0);
                config.speed = (float) json.optDouble("speed", 1.0);
                config.panX = json.optInt("panX", 0);
                config.panY = json.optInt("panY", 0);
                config.scaleMode = json.optString("scaleMode", "FILL");
                config.showHud = json.optBoolean("showHud", false);
                config.disableToast = json.optBoolean("disableToast", false);
                config.isPaused = json.optBoolean("isPaused", false);

                JSONArray pathsArr = json.optJSONArray("mediaPaths");
                JSONArray typesArr = json.optJSONArray("mediaTypes");
                JSONArray namesArr = json.optJSONArray("mediaNames");
                JSONArray scopedArr = json.optJSONArray("scopedPackages");

                if (pathsArr != null && typesArr != null && namesArr != null) {
                    for (int i = 0; i < pathsArr.length(); i++) {
                        config.mediaPaths.add(pathsArr.getString(i));
                        config.mediaTypes.add(typesArr.getString(i));
                        config.mediaNames.add(namesArr.getString(i));
                    }
                }
                
                if (scopedArr != null) {
                    for (int i = 0; i < scopedArr.length(); i++) {
                        config.scopedPackages.add(scopedArr.getString(i));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return config;
    }

    public void save() {
        File dir = new File(BASE_DIR);
        if (!dir.exists()) dir.mkdirs();

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            JSONObject json = new JSONObject();
            json.put("enabled", enabled);
            json.put("selectedIndex", selectedIndex);
            json.put("rotation", rotation);
            json.put("zoom", zoom);
            json.put("volume", volume);
            json.put("speed", speed);
            json.put("panX", panX);
            json.put("panY", panY);
            json.put("scaleMode", scaleMode);
            json.put("showHud", showHud);
            json.put("disableToast", disableToast);
            json.put("isPaused", isPaused);
            json.put("activeMediaType", getActiveMediaType());
            json.put("mediaPaths", new JSONArray(mediaPaths));
            json.put("mediaTypes", new JSONArray(mediaTypes));
            json.put("mediaNames", new JSONArray(mediaNames));
            json.put("scopedPackages", new JSONArray(scopedPackages));
            writer.write(json.toString(4));
        } catch (Exception e) {
            e.printStackTrace();
        }
        pushMediaToPublicDir();
    }

    public void pushMediaToPublicDir() {
        new Thread(() -> {
            try {
                List<String> targetPackages = new ArrayList<>(scopedPackages);

                String[] lsposedPaths = {
                    "/data/adb/lspd/config/modules_config.json",
                    "/data/adb/modules/zygisk_lsposed/config/modules_config.json"
                };

                for (String path : lsposedPaths) {
                    try {
                        Process getScope = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
                        BufferedReader reader = new BufferedReader(new InputStreamReader(getScope.getInputStream()));
                        StringBuilder scopeJson = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            scopeJson.append(line);
                        }
                        getScope.waitFor();

                        if (scopeJson.length() > 0) {
                            JSONObject configJson = new JSONObject(scopeJson.toString());
                            JSONObject modules = configJson.optJSONObject("modules");
                            if (modules != null) {
                                JSONObject ayuscam = modules.optJSONObject("com.vcam.ayuscam");
                                if (ayuscam != null) {
                                    JSONArray scope = ayuscam.optJSONArray("scope");
                                    if (scope != null) {
                                        for (int i = 0; i < scope.length(); i++) {
                                            String pkg = scope.getString(i);
                                            if (!targetPackages.contains(pkg)) {
                                                targetPackages.add(pkg);
                                            }
                                        }
                                    }
                                }
                            }
                            break; 
                        }
                    } catch (Exception ignored) {}
                }

                String ext = "IMAGE".equals(getActiveMediaType()) ? ".jpg" : ".mp4";
                String mediaPath = getActiveMediaPath();
                
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());

                os.writeBytes("mkdir -p " + BASE_DIR + "\n");
                os.writeBytes("rm -f " + BASE_DIR + "virtual.*\n");

                if (mediaPath != null && !mediaPath.isEmpty()) {
                    os.writeBytes("cp -f \"" + mediaPath + "\" \"" + BASE_DIR + "virtual" + ext + "\"\n");
                    if ("IMAGE".equals(getActiveMediaType())) {
                        os.writeBytes("cp -f \"" + mediaPath + "\" \"" + BASE_DIR + "1000.bmp\"\n");
                    }
                }

                os.writeBytes("chmod -R 777 " + BASE_DIR + "\n");

                if (!targetPackages.isEmpty()) {
                    for (String pkg : targetPackages) {
                        String path = "/storage/emulated/0/Android/data/" + pkg;
                        os.writeBytes("  if [ -d \"" + path + "\" ]; then\n");
                        os.writeBytes("    mkdir -p \"" + path + "/files/Camera1\"\n");
                        os.writeBytes("    cp -f " + BASE_DIR + "config.json \"" + path + "/files/Camera1/config.json\" 2>/dev/null\n");
                        os.writeBytes("    rm -f \"" + path + "/files/Camera1/virtual.*\" 2>/dev/null\n");
                        if (mediaPath != null && !mediaPath.isEmpty()) {
                            os.writeBytes("    cp -f \"" + mediaPath + "\" \"" + path + "/files/Camera1/virtual" + ext + "\" 2>/dev/null\n");
                            if ("IMAGE".equals(getActiveMediaType())) {
                                os.writeBytes("    cp -f \"" + mediaPath + "\" \"" + path + "/files/Camera1/1000.bmp\" 2>/dev/null\n");
                            }
                        }
                        os.writeBytes("    chmod -R 777 \"" + path + "/files/Camera1\" 2>/dev/null\n");
                        os.writeBytes("  fi\n");
                    }
                }

                os.writeBytes("exit\n");
                os.flush();
                p.waitFor();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
