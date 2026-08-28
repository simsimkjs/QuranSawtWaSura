package sim.quran.data;

import org.json.JSONArray;
import org.json.JSONObject;
import sim.quran.model.Surah;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SurahIndexLoader {

    /** يحمّل فهرس السور الكامل (114 سورة) من quran-data/surahs.json. */
    public static List<Surah> load() throws IOException {
        File file = DataPaths.surahIndexFile();
        if (!file.exists()) {
            throw new IOException("لم يتم العثور على فهرس السور في: " + file.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(file)) {
            return loadFromStream(in);
        }
    }

    public static List<Surah> loadFromStream(InputStream inputStream) throws IOException {
        String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        JSONArray arr = new JSONArray(content);
        List<Surah> result = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            result.add(new Surah(
                    o.getInt("number"),
                    o.getString("name"),
                    o.optString("file", null),
                    o.optString("audio", null),
                    o.optInt("ayahs", 0)
            ));
        }
        return result;
    }
}
