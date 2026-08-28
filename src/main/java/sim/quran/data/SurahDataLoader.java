package sim.quran.data;

import org.json.JSONArray;
import org.json.JSONObject;
import sim.quran.model.AyahTiming;
import sim.quran.model.Surah;
import sim.quran.model.SurahData;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SurahDataLoader {

    /** يحمّل ملف سورة واحد (مثل 001.json) بصيغة quran-ayah-splitter-project. */
    public static SurahData load(Surah surah) throws IOException {
        File file = DataPaths.surahDataFile(surah.getNumber());
        if (!file.exists()) {
            throw new IOException("ملف بيانات السورة غير موجود: " + file.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(file)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);

            int surahNumber = root.optInt("surah_number", surah.getNumber());
            String surahName = root.optString("surah_name", surah.getName());
            String audioFile = root.optString("audio_file_name", surah.getAudioFile());

            JSONArray ayahsArr = root.getJSONArray("ayahs");
            List<AyahTiming> ayahs = new ArrayList<>();
            for (int i = 0; i < ayahsArr.length(); i++) {
                JSONObject a = ayahsArr.getJSONObject(i);
                ayahs.add(new AyahTiming(
                        a.optInt("index", i),
                        a.optInt("ayah_number", 0),
                        a.optString("text", ""),
                        a.optLong("start_ms", 0L),
                        a.optBoolean("export", true)
                ));
            }
            return new SurahData(surahNumber, surahName, audioFile, ayahs);
        }
    }
}
