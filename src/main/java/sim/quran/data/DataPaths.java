package sim.quran.data;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * كل ملفات البيانات (فهرس السور، بيانات كل سورة، الصور، الصوت، التفسير)
 * تُقرأ من مجلد خارجي اسمه "quran-data" بجانب مجلد المشروع (وليس من داخل الـ jar)،
 * لتسهيل إضافة/تحديث السور دون إعادة بناء البرنامج.
 *
 * الهيكلية المتوقعة:
 *   quran-data/surahs.json
 *   quran-data/quran/001.json ... 114.json
 *   quran-data/audio/001.mp3  ... 114.mp3
 *   quran-data/tafsir/001.json ... (اختياري - عند توفره)
 */
public final class DataPaths {

    private DataPaths() {}

    private static final String ROOT_DIR_NAME = "quran-data";
    private static File cachedRoot; // نحسبها مرة واحدة فقط ونطبع النتيجة للتشخيص

    /**
     * يحدد مجلد quran-data بتجربة عدة مواقع محتملة بالترتيب،
     * لأن مجلد العمل الفعلي وقت التشغيل يختلف حسب طريقة التشغيل
     * (NetBeans "Run File" يختلف أحياناً عن "mvn javafx:run").
     */
    private static File rootDir() {
        if (cachedRoot != null) return cachedRoot;

        String override = System.getProperty("quran.data.dir");
        List<File> candidates = new ArrayList<>();
        if (override != null && !override.isBlank()) {
            candidates.add(new File(override));
        }
        candidates.add(new File(ROOT_DIR_NAME));                          // ./quran-data
        candidates.add(new File("..", ROOT_DIR_NAME));                    // ../quran-data
        candidates.add(new File("../..", ROOT_DIR_NAME));                 // ../../quran-data
        candidates.add(new File(System.getProperty("user.dir"), ROOT_DIR_NAME));

        for (File candidate : candidates) {
            File indexFile = new File(candidate, "surahs.json");
            if (indexFile.exists()) {
                cachedRoot = candidate;
                System.out.println("[QuranApp] تم العثور على quran-data في: " + candidate.getAbsolutePath());
                return cachedRoot;
            }
        }

        // لم يُعثر عليه في أي مكان - نستخدم الافتراضي ونطبع تحذيراً واضحاً بكل المسارات التي جُرِّبت
        cachedRoot = new File(ROOT_DIR_NAME);
        System.err.println("[QuranApp] تحذير: لم يُعثر على مجلد quran-data (يحتوي surahs.json) في أي من هذه المسارات:");
        for (File candidate : candidates) {
            System.err.println("   - " + candidate.getAbsolutePath());
        }
        System.err.println("[QuranApp] مجلد العمل الحالي (user.dir): " + System.getProperty("user.dir"));
        return cachedRoot;
    }

    public static File root() {
        return rootDir();
    }

    public static File surahIndexFile() {
        return new File(rootDir(), "surahs.json");
    }

    public static File surahDataFile(int surahNumber) {
        return new File(new File(rootDir(), "quran"), String.format(Locale.ROOT, "%03d.json", surahNumber));
    }

    public static File audioFile(String fileName) {
        return new File(new File(rootDir(), "audio"), fileName);
    }

    public static File tafsirFile(int surahNumber) {
        return new File(new File(rootDir(), "tafsir"), String.format(Locale.ROOT, "%03d.json", surahNumber));
    }
}
