package com.vcam.ayuscam;

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
    public static final String BASE_DIR = Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/";
    public static final String CONFIG_FILE = BASE_DIR + "config.json";
    public static final String LOG_FILE = BASE_DIR + "daemon.log";

    public boolean enabled = true;
    
    // Lists to support multiple items
    public List<String> mediaPaths = new ArrayList<>();
    public List<String> mediaTypes = new ArrayList<>(); // "VIDEO" or "IMAGE"
    public List<String> mediaNames = new ArrayList<>();
    public int selectedIndex = -1;

    public int rotation = 0;
    public int zoom = 100;
    public int volume = 0;
    public int panX = 0;
    public int panY = 0;
    public String scaleMode = "FILL";
    public boolean showHud = false;
    public boolean disableToast = false;

    // Helper to get active media type and path for backwards compatibility and hooks
    public String getActiveMediaType() {
        if (selectedIndex >= 0 && selectedIndex < mediaTypes.size()) return mediaTypes.get(selectedIndex);
        return "VIDEO";
    }

    public String getActiveMediaPath() {
        if (selectedIndex >= 0 && selectedIndex < mediaPaths.size()) return mediaPaths.get(selectedIndex);
        return "";
    }

    public static AppConfig load() {
        AppConfig config = new AppConfig();
        File file = new File(CONFIG_FILE);
        if (!file.exists()) {
            config.save();
            return config;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            JSONObject json = new JSONObject(sb.toString());
            config.enabled = json.optBoolean("enabled", true);
            config.selectedIndex = json.optInt("selectedIndex", -1);
            config.rotation = json.optInt("rotation", 0);
            config.zoom = json.optInt("zoom", 100);
            config.volume = json.optInt("volume", 0);
            config.panX = json.optInt("panX", 0);
            config.panY = json.optInt("panY", 0);
            config.scaleMode = json.optString("scaleMode", "FILL");
            config.showHud = json.optBoolean("showHud", false);
            config.disableToast = json.optBoolean("disableToast", false);

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
        return config;
    }

    public void save() {
        File dir = new File(BASE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
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

            json.put("mediaPaths", new JSONArray(mediaPaths));
            json.put("mediaTypes", new JSONArray(mediaTypes));
            json.put("mediaNames", new JSONArray(mediaNames));

            writer.write(json.toString(4));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
