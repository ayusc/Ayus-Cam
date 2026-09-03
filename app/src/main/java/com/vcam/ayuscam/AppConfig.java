package com.vcam.ayuscam;

import android.content.Context;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    public static final String BASE_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/AyusCam/";
    public static final String CONFIG_FILE = BASE_DIR + "config.json";
    public static final String LOG_FILE = BASE_DIR + "daemon.log";

    public boolean enabled = true;
    public List<String> mediaPaths = new ArrayList<>();
    public List<String> mediaTypes = new ArrayList<>();
    public List<String> mediaNames = new ArrayList<>();
    public int selectedIndex = 0;
    
    public int rotation = 0;
    public int zoom = 100;
    public int volume = 0;
    public int panX = 0;
    public int panY = 0;
    public String scaleMode = "FILL";
    public boolean showHud = false;
    public boolean disableToast = false;
    public boolean isPaused = false; 

    public String getActiveMediaType() {
        if (selectedIndex >= 0 && selectedIndex < mediaTypes.size()) {
            return mediaTypes.get(selectedIndex);
        }
        if (!mediaTypes.isEmpty()) {
            return mediaTypes.get(0);
        }
        return "VIDEO";
    }

    public String getActiveMediaPath() {
        if (selectedIndex >= 0 && selectedIndex < mediaPaths.size()) {
            return mediaPaths.get(selectedIndex);
        }
        if (!mediaPaths.isEmpty()) {
            return mediaPaths.get(0);
        }
        return "";
    }

    // Default load method for the main UI App
    public static AppConfig load() {
        return load(null);
    }

    // Overloaded load method to support fallback inside hooked apps
    public static AppConfig load(Context context) {
        AppConfig config = new AppConfig();
        File globalFile = new File(CONFIG_FILE);
        boolean canReadGlobal = globalFile.exists() && globalFile.canRead();

        // 1. Try Loading Global Config
        if (canReadGlobal) {
            try (BufferedReader br = new BufferedReader(new FileReader(globalFile))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                config.enabled = json.optBoolean("enabled", true);
                config.selectedIndex = json.optInt("selectedIndex", 0);
                config.rotation = json.optInt("rotation", 0);
                config.zoom = json.optInt("zoom", 100);
                config.volume = json.optInt("volume", 0);
                config.panX = json.optInt("panX", 0);
                config.panY = json.optInt("panY", 0);
                config.scaleMode = json.optString("scaleMode", "FILL");
                config.showHud = json.optBoolean("showHud", false);
                config.disableToast = json.optBoolean("disableToast", false);
                config.isPaused = json.optBoolean("isPaused", false);

                JSONArray pathsArr = json.optJSONArray("mediaPaths");
                JSONArray typesArr = json.optJSONArray("mediaTypes");
                JSONArray namesArr = json.optJSONArray("mediaNames");
                if (pathsArr != null && typesArr != null && namesArr != null) {
                    for (int i = 0; i < pathsArr.length(); i++) {
                        config.mediaPaths.add(pathsArr.getString(i));
                        config.mediaTypes.add(typesArr.getString(i));
                        config.mediaNames.add(namesArr.getString(i));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 2. Check if Scoped Storage blocks the configured media file
        boolean mediaReadable = false;
        if (!config.mediaPaths.isEmpty() && config.selectedIndex >= 0 && config.selectedIndex < config.mediaPaths.size()) {
            File mediaFile = new File(config.mediaPaths.get(config.selectedIndex));
            if (mediaFile.exists() && mediaFile.canRead()) {
                mediaReadable = true;
            }
        }

        // 3. Fallback to Private Directory if global is inaccessible (Scoped Storage restrictions)
        if ((!canReadGlobal || !mediaReadable) && context != null) {
            File extFilesDir = context.getExternalFilesDir(null);
            if (extFilesDir != null) {
                File privateDir = new File(extFilesDir, "Camera1");
                if (!privateDir.exists()) {
                    privateDir.mkdirs();
                }
                
                File privVid = new File(privateDir, "virtual.mp4");
                File privImg = new File(privateDir, "1000.bmp");
                File privImgAlt = new File(privateDir, "virtual.jpg");
                
                // If any media file exists in the private directory, override the config
                if (privVid.exists() || privImg.exists() || privImgAlt.exists()) {
                    config.mediaPaths.clear();
                    config.mediaTypes.clear();
                    config.mediaNames.clear();
                    
                    if (privVid.exists()) {
                        config.mediaPaths.add(privVid.getAbsolutePath());
                        config.mediaTypes.add("VIDEO");
                        config.mediaNames.add("Private Video");
                    } else if (privImg.exists()) {
                        config.mediaPaths.add(privImg.getAbsolutePath());
                        config.mediaTypes.add("IMAGE");
                        config.mediaNames.add("Private Image");
                    } else if (privImgAlt.exists()) {
                        config.mediaPaths.add(privImgAlt.getAbsolutePath());
                        config.mediaTypes.add("IMAGE");
                        config.mediaNames.add("Private Image");
                    }
                    config.selectedIndex = 0;
                    config.enabled = true; // Auto-enable if private files are found
                    
                    // Optional: Read private config.json if user placed one manually
                    File privConfig = new File(privateDir, "config.json");
                    if (privConfig.exists() && privConfig.canRead()) {
                         try (BufferedReader br = new BufferedReader(new FileReader(privConfig))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = br.readLine()) != null) {
                                sb.append(line);
                            }
                            JSONObject json = new JSONObject(sb.toString());
                            config.enabled = json.optBoolean("enabled", true);
                            config.rotation = json.optInt("rotation", 0);
                            config.zoom = json.optInt("zoom", 100);
                            config.volume = json.optInt("volume", 0);
                            config.panX = json.optInt("panX", 0);
                            config.panY = json.optInt("panY", 0);
                            config.scaleMode = json.optString("scaleMode", "FILL");
                            config.isPaused = json.optBoolean("isPaused", false);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    // If neither global nor private files are available, disable hook safely
                    config.enabled = false;
                }
            }
        }
        return config;
    }

    public void save() {
        File dir = new File(BASE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            JSONObject json = new JSONObject();
            json.put("enabled", enabled);
            json.put("selectedIndex", selectedIndex);
            json.put("rotation", rotation);
            json.put("zoom", zoom);
            json.put("volume", volume);
            json.put("panX", panX);
            json.put("panY", panY);
            json.put("scaleMode", scaleMode);
            json.put("showHud", showHud);
            json.put("disableToast", disableToast);
            json.put("isPaused", isPaused);
            
            json.put("mediaPaths", new JSONArray(mediaPaths));
            json.put("mediaTypes", new JSONArray(mediaTypes));
            json.put("mediaNames", new JSONArray(mediaNames));
            
            writer.write(json.toString(4));
        } catch (Exception e) {
            e.printStackTrace();
        }

        File configFile = new File(CONFIG_FILE);
        configFile.setReadable(true, false);
        configFile.setWritable(true, false);
    }
}
