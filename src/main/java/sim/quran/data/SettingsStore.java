package sim.quran.data;

import org.json.JSONObject;
import sim.quran.model.AppSettings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * يحفظ/يحمّل إعدادات القارئ (الخط، حجمه، الثيم، تفعيل مشوار الحفظ) من:
 *   <مجلد بيانات القارئ>/settings.json
 */
public class SettingsStore {

    public static AppSettings load(File profileDataDir) {
        File file = filePath(profileDataDir);
        if (!file.exists()) return AppSettings.defaults();
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);
            AppSettings settings = new AppSettings(
                    root.optString("font_family", "KFGQPC Uthmanic Script HAFS"),
                    root.optDouble("font_size", 27),
                    AppSettings.Theme.fromName(root.optString("theme", "CLASSIC")));
            settings.setTrackProgress(root.optBoolean("track_progress", true));
            return settings;
        } catch (Exception e) {
            return AppSettings.defaults();
        }
    }

    public static void save(File profileDataDir, AppSettings settings) {
        File file = filePath(profileDataDir);
        file.getParentFile().mkdirs();
        JSONObject root = new JSONObject();
        root.put("font_family", settings.getFontFamily());
        root.put("font_size", settings.getFontSize());
        root.put("theme", settings.getTheme().name());
        root.put("track_progress", settings.isTrackProgress());
        try {
            Files.writeString(file.toPath(), root.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("تعذر حفظ الإعدادات: " + e.getMessage());
        }
    }

    private static File filePath(File profileDataDir) {
        return new File(profileDataDir, "settings.json");
    }
}
