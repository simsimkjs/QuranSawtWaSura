package sim.quran.data;

import org.json.JSONArray;
import org.json.JSONObject;
import sim.quran.model.AyahRegion;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * يحفظ/يحمّل إحداثيات مستطيلات الآيات فوق صورة كل سورة:
 *   quran-data/regions/{رقم السورة بصيغة 001}.json
 *
 * ملف غير موجود لسورة معيّنة = ببساطة لا يوجد تظليل فوق الصورة لها بعد،
 * فيبقى التطبيق يعرض التظليل على النص فقط (لا يوجد أي كسر أو خطأ).
 */
public class AyahRegionStore {

    public static File regionsFile(int surahNumber) {
        File dir = new File(DataPaths.root(), "regions");
        return new File(dir, String.format(Locale.ROOT, "%03d.json", surahNumber));
    }

    public static List<AyahRegion> load(int surahNumber) {
        List<AyahRegion> result = new ArrayList<>();
        File file = regionsFile(surahNumber);
        if (!file.exists()) return result;
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);
            JSONArray arr = root.optJSONArray("regions");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    result.add(new AyahRegion(
                            r.getInt("ayah_number"),
                            r.getDouble("x"),
                            r.getDouble("y"),
                            r.getDouble("width"),
                            r.getDouble("height")));
                }
            }
        } catch (Exception ignored) {
            // ملف تالف أو غير مكتمل - نتجاهله بدل ما نكسر التطبيق، ونرجع قائمة فارغة
        }
        return result;
    }

    public static void save(int surahNumber, String imageFileName, List<AyahRegion> regions) {
        File file = regionsFile(surahNumber);
        file.getParentFile().mkdirs();

        JSONObject root = new JSONObject();
        root.put("surah_number", surahNumber);
        root.put("image_file", imageFileName);
        JSONArray arr = new JSONArray();
        for (AyahRegion r : regions) {
            JSONObject o = new JSONObject();
            o.put("ayah_number", r.ayahNumber);
            o.put("x", r.x);
            o.put("y", r.y);
            o.put("width", r.width);
            o.put("height", r.height);
            arr.put(o);
        }
        root.put("regions", arr);

        try {
            Files.writeString(file.toPath(), root.toString(2), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("تعذر حفظ إحداثيات الآيات: " + e.getMessage());
        }
    }
}
