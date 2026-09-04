package com.vcam.ayuscam;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    public static final String BASE_DIR = "/data/data/com.vcam.ayuscam/files/AyusCam/";
    public static final String CONFIG_FILE = BASE_DIR + "config.json";
    public static final String LOG_FILE = BASE_DIR + "daemon.log";

    public static final String TMP_DIR = "/data/local/tmp/AyusCam/";

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

    public static AppConfig load(Context context) {
        AppConfig config = new AppConfig();
        
        // 1. IF RUNNING INSIDE THE HOOK (e.g. inside WhatsApp)
        if (context != null && !context.getPackageName().equals("com.vcam.ayuscam")) {
            File privConfig = new File(TMP_DIR, "config.json");
            
            if (privConfig.exists() && privConfig.canRead()) {
                try (BufferedReader br = new BufferedReader(new FileReader(privConfig))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    
                    config.enabled = json.optBoolean("enabled", true);
                    config.rotation = json.optInt("rotation", 0);
                    config.zoom = json.optInt("zoom", 100);
                    config.volume = json.optInt("volume", 0);
                    config.panX = json.optInt("panX", 0);
                    config.panY = json.optInt("panY", 0);
                    config.scaleMode = json.optString("scaleMode", "FILL");
                    config.isPaused = json.optBoolean("isPaused", false);
                    
                    String mediaType = json.optString("activeMediaType", "VIDEO");
                    String ext = "IMAGE".equals(mediaType) ? ".jpg" : ".mp4";
                    File targetMedia = new File(TMP_DIR, "virtual" + ext);
                    
                    if (targetMedia.exists()) {
                        config.mediaPaths.add(targetMedia.getAbsolutePath());
                        config.mediaTypes.add(mediaType);
                        config.selectedIndex = 0;
                    } else {
                        config.enabled = false;
                    }
                } catch (Exception e) { e.printStackTrace(); }
            } else {
                config.enabled = false;
            }
            return config;
        }

        // 2. IF RUNNING INSIDE THE UI APP
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
            } catch (Exception e) { e.printStackTrace(); }
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

            writer.write(json.toString(4));
        } catch (Exception e) { e.printStackTrace(); }
        
        pushMediaToTmp();
    }

    public void pushMediaToTmp() {
        new Thread(() -> {
            try {
                String ext = "IMAGE".equals(getActiveMediaType()) ? ".jpg" : ".mp4";
                String mediaPath = getActiveMediaPath();
                
                Process p = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(p.getOutputStream());
                
                os.writeBytes("mkdir -p " + TMP_DIR + "\n");
                os.writeBytes("rm -f " + TMP_DIR + "virtual.*\n");
                
                if (!mediaPath.isEmpty()) {
                    os.writeBytes("cp -f \"" + mediaPath + "\" \"" + TMP_DIR + "virtual" + ext + "\"\n");
                }
                os.writeBytes("cp -f \"" + CONFIG_FILE + "\" \"" + TMP_DIR + "config.json\"\n");
                
                os.writeBytes("chmod -R 777 " + TMP_DIR + "\n");
                // Attempt to neutralize SELinux blocks
                os.writeBytes("chcon -R u:object_r:app_data_file:s0 " + TMP_DIR + "\n");
                
                os.writeBytes("exit\n");
                os.flush();
                p.waitFor();
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }
}
