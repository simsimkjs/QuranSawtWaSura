package sim.quran.data;

import sim.quran.model.AyahTiming;
import sim.quran.model.Surah;
import sim.quran.model.SurahData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * بحث نصي عبر كل القرآن، يُبنى في خيط منفصل (buildAsync) حتى لا يجمّد الواجهة
 * عند أول بحث، مع تقرير نسبة تقدّم تقريبية عبر onProgress.
 */
public class SearchIndex {

    public static class Hit {
        public final Surah surah;
        public final AyahTiming ayah;
        public Hit(Surah surah, AyahTiming ayah) { this.surah = surah; this.ayah = ayah; }
    }

    private final List<Surah> surahs;
    private final List<Hit> allAyahs = new ArrayList<>();
    /** نص كل آية مطبَّع (بلا تشكيل) مسبقًا عند البناء - حتى لا نعيد regex على 6236 آية مع كل بحث. */
    private final List<String> allNormalizedTexts = new ArrayList<>();
    private volatile boolean built = false;
    private volatile boolean building = false;

    public SearchIndex(List<Surah> surahs) {
        this.surahs = surahs;
    }

    public boolean isBuilt() {
        return built;
    }

    /**
     * يبني الفهرس في الخلفية بقراءة كل ملفات السور المتوفرة محلياً حالياً
     * (السور غير المرفوعة بعد تُتجاهل بصمت ولا توقف البناء).
     */
    public void buildAsync(IntConsumer onProgress, Runnable onDone) {
        if (built) {
            if (onDone != null) onDone.run();
            return;
        }
        if (building) return;
        building = true;

        Thread worker = new Thread(() -> {
            int total = Math.max(1, surahs.size());
            for (int i = 0; i < surahs.size(); i++) {
                Surah s = surahs.get(i);
                try {
                    SurahData data = SurahDataLoader.load(s);
                    for (AyahTiming a : data.getAyahs()) {
                        if (a.hasText()) {
                            allAyahs.add(new Hit(s, a));
                            allNormalizedTexts.add(normalize(a.getText()));
                        }
                    }
                } catch (Exception ignored) {
                    // السورة غير مرفوعة بعد - تجاهل ومتابعة
                }
                if (onProgress != null) {
                    onProgress.accept((int) (((i + 1) / (double) total) * 100));
                }
            }
            built = true;
            building = false;
            if (onDone != null) onDone.run();
        }, "search-index-builder");
        worker.setDaemon(true);
        worker.start();
    }

    /** بحث نصي متسامح مع التشكيل. استدعِه بعد اكتمال buildAsync (تحقق عبر isBuilt). */
    public List<Hit> search(String query) {
        String q = normalize(query);
        List<Hit> results = new ArrayList<>();
        if (q.isBlank()) return results;
        // نقارن بالنص المطبَّع المحفوظ مسبقًا بدل إعادة تطبيع كل آية عند كل استعلام
        for (int i = 0; i < allAyahs.size(); i++) {
            if (allNormalizedTexts.get(i).contains(q)) {
                results.add(allAyahs.get(i));
            }
        }
        return results;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]", "")
                .replace("ٱ", "ا")
                .trim();
    }
}
