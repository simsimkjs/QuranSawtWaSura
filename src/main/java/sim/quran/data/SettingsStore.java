package sim.quran.data;

import org.json.JSONObject;
import sim.quran.model.AppSettings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * يحفظ/يحمّل إعدادات المستخدم (الخط، حجمه، الثيم) من:
 *   <المجلد الشخصي>/.quran_sawt_sura/settings.json
 * (نفس مجلد bookmarks.json حتى تبقى كل بيانات المستخدم الشخصية بمكان واحد)
 */
public class SettingsStore {

    public static AppSettings load() {
        File file = filePath();
        if (!file.exists()) return AppSettings.defaults();
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);
            return new AppSettings(
                    root.optString("font_family", "Amiri"),
                    root.optDouble("font_size", 27),
                    AppSettings.Theme.fromName(root.optString("theme", "CLASSIC")));
        } catch (Exception e) {
            return AppSettings.defaults();
        }
    }

    public static void save(AppSettings settings) {
        File file = filePath();
        file.getParentFile().mkdirs();
        JSONObject root = new JSONObject();
        root.put("font_family", settings.getFontFamily());
        root.put("font_size", settings.getFontSize());
        root.put("theme", settings.getTheme().name());
        try {
            Files.writeString(file.toPath(), root.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("تعذر حفظ الإعدادات: " + e.getMessage());
        }
    }

    private static File filePath() {
        File dir = new File(System.getProperty("user.home"), ".quran_sawt_sura");
        return new File(dir, "settings.json");
    }
}
