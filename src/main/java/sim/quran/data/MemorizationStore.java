package sim.quran.data;

import org.json.JSONObject;
import sim.quran.model.JuzBoundaries;
import sim.quran.model.ProgressStats;
import sim.quran.model.Surah;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * "مشوار حفظ القرآن": يتتبّع أبعد نقطة وصلها المستخدم فعليًا أثناء الاستماع/القراءة
 * (منفصل تمامًا عن BookmarkStore الذي يحفظ "آخر موضع" للاستئناف، لأن المستخدم قد يرجع
 * لمراجعة آيات سابقة دون أن يعني هذا تراجعًا في تقدّمه الفعلي بالمشوار).
 *
 * يُخزَّن في: <المجلد الشخصي>/.quran_sawt_sura/progress.json
 */
public class MemorizationStore {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DAILY_AVERAGE_WINDOW_DAYS = 14; // نحسب المعدل من آخر أسبوعين، مو من أول يوم

    private final File filePath;

    private int furthestSurah = 0;
    private int furthestAyah = 0;
    /** تاريخ -> أبعد موضع تراكمي وصله المستخدم بنهاية ذلك اليوم. */
    private final TreeMap<LocalDate, Integer> dailyLog = new TreeMap<>();
    private final TreeSet<Integer> congratulatedJuz = new TreeSet<>();

    private MemorizationStore(File filePath) {
        this.filePath = filePath;
    }

    public static MemorizationStore load(File profileDataDir) {
        profileDataDir.mkdirs();
        MemorizationStore store = new MemorizationStore(new File(profileDataDir, "progress.json"));
        store.readFromDisk();
        return store;
    }

    /**
     * يُستدعى في كل مرة يتقدّم فيها المستخدم لآية جديدة أثناء القراءة/الاستماع.
     * يحدّث أبعد نقطة فقط لو كانت هذه الآية أبعد من أي نقطة سابقة (تجاهل الرجوع للمراجعة).
     *
     * @return رقم الجزء المكتمل حديثًا (للتهنئة)، أو null إن لم يكتمل جزء جديد الآن.
     */
    public synchronized Integer recordProgress(int surahNumber, int ayahNumber, List<Surah> allSurahs) {
        int newCumulative = JuzBoundaries.cumulativeAyah(surahNumber, ayahNumber, allSurahs);
        int previousCumulative = JuzBoundaries.cumulativeAyah(
                furthestSurah == 0 ? 1 : furthestSurah, furthestAyah, allSurahs);

        if (furthestSurah != 0 && newCumulative <= previousCumulative) {
            return null; // مراجعة لموضع سابق أو نفسه - ما يعتبر تقدّم جديد بالمشوار
        }

        furthestSurah = surahNumber;
        furthestAyah = ayahNumber;
        dailyLog.put(LocalDate.now(), newCumulative);
        save();

        int newJuz = JuzBoundaries.juzOf(surahNumber, ayahNumber);
        // هل اكتمل جزء جديد بالكامل؟ يعني وصلنا لبداية الجزء التالي (أو تجاوزناه)
        int completedJuz = newJuz - 1;
        if (completedJuz >= 1 && !congratulatedJuz.contains(completedJuz)) {
            congratulatedJuz.add(completedJuz);
            save();
            return completedJuz;
        }
        // حالة خاصة: اكتمال آخر جزء (30) يعني الانتهاء من كامل المصحف
        if (newCumulative >= JuzBoundaries.totalAyahCount(allSurahs) && !congratulatedJuz.contains(30)) {
            congratulatedJuz.add(30);
            save();
            return 30;
        }
        return null;
    }

    public ProgressStats computeStats(List<Surah> allSurahs) {
        int total = JuzBoundaries.totalAyahCount(allSurahs);
        int furthest = furthestSurah == 0 ? 0 : JuzBoundaries.cumulativeAyah(furthestSurah, furthestAyah, allSurahs);
        int currentJuz = furthestSurah == 0 ? 0 : JuzBoundaries.juzOf(furthestSurah, furthestAyah);

        double dailyAverage = computeDailyAverage(furthest);
        Integer daysRemaining = null;
        if (dailyAverage > 0.01) {
            int remaining = Math.max(0, total - furthest);
            daysRemaining = (int) Math.ceil(remaining / dailyAverage);
        }

        ProgressStats.Level level;
        if (dailyAverage <= 0.01) level = ProgressStats.Level.BEGINNER;
        else if (dailyAverage < 5) level = ProgressStats.Level.BEGINNER;
        else if (dailyAverage < 15) level = ProgressStats.Level.INTERMEDIATE;
        else if (dailyAverage < 40) level = ProgressStats.Level.ADVANCED;
        else level = ProgressStats.Level.EXPERT;

        return new ProgressStats(furthest, total, currentJuz, dailyAverage, daysRemaining, level);
    }

    private double computeDailyAverage(int currentFurthest) {
        if (dailyLog.isEmpty()) return 0;
        LocalDate cutoff = LocalDate.now().minusDays(DAILY_AVERAGE_WINDOW_DAYS);
        // أقدم قيمة داخل نافذة الحساب (أو أول قيمة مسجَّلة أصلاً لو أحدث من النافذة)
        var windowEntries = dailyLog.tailMap(cutoff, true);
        if (windowEntries.isEmpty()) return 0;

        LocalDate firstDay = windowEntries.firstKey();
        int firstValue = windowEntries.firstEntry().getValue();
        long daysElapsed = java.time.temporal.ChronoUnit.DAYS.between(firstDay, LocalDate.now()) + 1;
        int gained = currentFurthest - firstValue;
        if (gained <= 0 || daysElapsed <= 0) return 0;
        return gained / (double) daysElapsed;
    }

    public int getFurthestSurah() { return furthestSurah; }
    public int getFurthestAyah() { return furthestAyah; }

    private void readFromDisk() {
        if (!filePath.exists()) return;
        try {
            String content = Files.readString(filePath.toPath(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);
            furthestSurah = root.optInt("furthest_surah", 0);
            furthestAyah = root.optInt("furthest_ayah", 0);

            JSONObject log = root.optJSONObject("daily_log");
            if (log != null) {
                for (String key : log.keySet()) {
                    try {
                        dailyLog.put(LocalDate.parse(key, DATE_FMT), log.getInt(key));
                    } catch (Exception ignored) {
                    }
                }
            }
            for (Object j : root.optJSONArray("congratulated_juz") != null
                    ? root.getJSONArray("congratulated_juz").toList() : List.of()) {
                if (j instanceof Number n) congratulatedJuz.add(n.intValue());
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        JSONObject root = new JSONObject();
        root.put("furthest_surah", furthestSurah);
        root.put("furthest_ayah", furthestAyah);

        JSONObject log = new JSONObject();
        for (var entry : dailyLog.entrySet()) {
            log.put(entry.getKey().format(DATE_FMT), entry.getValue());
        }
        root.put("daily_log", log);
        root.put("congratulated_juz", congratulatedJuz);

        try {
            Files.writeString(filePath.toPath(), root.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("تعذر حفظ تقدّم المشوار: " + e.getMessage());
        }
    }
}
