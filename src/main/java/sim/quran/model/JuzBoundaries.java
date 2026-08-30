package sim.quran.model;

import java.util.List;

/**
 * حدود الأجزاء الثلاثين (بداية كل جزء: رقم السورة ورقم الآية)، حسب التقسيم المعتمد
 * في أغلب المصاحف (رواية حفص). تُستخدم لحساب أي جزء تقع فيه آية معيّنة، ولحساب
 * الموضع التراكمي (رقم الآية الإجمالي من أصل 6236) لمتابعة تقدّم القراءة/الحفظ.
 */
public final class JuzBoundaries {

    private JuzBoundaries() {}

    /** {رقم السورة, رقم الآية} لبداية كل جزء، بالترتيب من الجزء 1 إلى 30. */
    private static final int[][] STARTS = {
            {1, 1},     // جزء 1: الفاتحة 1 - البقرة 141
            {2, 142},   // جزء 2
            {2, 253},   // جزء 3
            {3, 93},    // جزء 4
            {4, 24},    // جزء 5
            {4, 148},   // جزء 6
            {5, 82},    // جزء 7
            {6, 111},   // جزء 8
            {7, 88},    // جزء 9
            {8, 41},    // جزء 10
            {9, 93},    // جزء 11
            {11, 1},    // جزء 12
            {12, 53},   // جزء 13
            {15, 1},    // جزء 14
            {17, 1},    // جزء 15
            {18, 75},   // جزء 16
            {21, 1},    // جزء 17
            {23, 1},    // جزء 18
            {25, 1},    // جزء 19
            {27, 56},   // جزء 20
            {30, 1},    // جزء 21
            {33, 31},   // جزء 22
            {36, 28},   // جزء 23
            {39, 32},   // جزء 24
            {41, 47},   // جزء 25
            {46, 1},    // جزء 26
            {51, 31},   // جزء 27
            {58, 1},    // جزء 28
            {67, 1},    // جزء 29
            {78, 1},    // جزء 30
    };

    public static final int TOTAL_JUZ = STARTS.length;

    /** رقم الجزء (1..30) الذي تقع فيه آية معيّنة. */
    public static int juzOf(int surahNumber, int ayahNumber) {
        int result = 1;
        for (int i = 0; i < STARTS.length; i++) {
            int[] start = STARTS[i];
            if (start[0] < surahNumber || (start[0] == surahNumber && start[1] <= ayahNumber)) {
                result = i + 1;
            } else {
                break;
            }
        }
        return result;
    }

    /** الموضع التراكمي لآية (رقمها من أصل مجموع آيات القرآن كله، 1..6236). */
    public static int cumulativeAyah(int surahNumber, int ayahNumber, List<Surah> allSurahs) {
        int total = 0;
        for (Surah s : allSurahs) {
            if (s.getNumber() < surahNumber) {
                total += s.getAyahCount();
            } else {
                break;
            }
        }
        return total + ayahNumber;
    }

    /** إجمالي عدد آيات القرآن كله (يُحسب من فهرس السور المحمَّل، وليس رقمًا ثابتًا بالكود). */
    public static int totalAyahCount(List<Surah> allSurahs) {
        int total = 0;
        for (Surah s : allSurahs) total += s.getAyahCount();
        return total;
    }
}
