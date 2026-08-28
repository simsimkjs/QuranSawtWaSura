package sim.quran.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * TODO: صيغة ملفات التفسير الفعلية لم تُرفع بعد.
 * الصيغة المفترضة حالياً لكل سورة (quran-data/tafsir/NNN.json):
 * {
 *   "source": "ابن كثير",
 *   "ayahs": [ { "ayah_number": 1, "text": "..." }, ... ]
 * }
 * عند رفع الملفات الفعلية سنُعدّل التحليل هنا فقط دون أي تعديل على ReaderPane،
 * لأنها تستخدم فقط: loadFor(int) / getSource() / isAvailable() / getTafsirFor(int).
 */
public class TafsirLoader {

    private String source;
    private final Map<Integer, String> textByAyah = new HashMap<>();
    private boolean loaded = false;

    /** يحاول تحميل تفسير سورة معينة. يعيد true عند النجاح، أو false إن لم يتوفر الملف بعد. */
    public boolean loadFor(int surahNumber) {
        loaded = false;
        textByAyah.clear();
        source = null;

        File file = DataPaths.tafsirFile(surahNumber);
        if (!file.exists()) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);
            source = root.optString("source", "غير معروف");
            JSONArray arr = root.getJSONArray("ayahs");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                textByAyah.put(a.getInt("ayah_number"), a.optString("text", ""));
            }
            loaded = true;
        } catch (IOException | RuntimeException e) {
            loaded = false;
        }
        return loaded;
    }

    public boolean isAvailable() {
        return loaded && !textByAyah.isEmpty();
    }

    public String getSource() {
        return source != null ? source : "غير متاح";
    }

    public String getTafsirFor(int ayahNumber) {
        return textByAyah.getOrDefault(ayahNumber, "لا يوجد تفسير محمّل لهذه الآية.");
    }
}
