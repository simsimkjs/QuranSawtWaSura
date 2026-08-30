package sim.quran.model;

/** لقطة إحصائيات جاهزة للعرض مباشرة في الواجهة، محسوبة من MemorizationStore. */
public class ProgressStats {

    public enum Level {
        BEGINNER("مبتدئ"), INTERMEDIATE("متوسط"), ADVANCED("متقدّم"), EXPERT("حافظ متميّز");
        public final String displayName;
        Level(String displayName) { this.displayName = displayName; }
    }

    public final int furthestCumulativeAyah;
    public final int totalAyahCount;
    public final int currentJuz;
    public final double dailyAveragePace; // آية/يوم
    public final Integer estimatedDaysRemaining; // null = لا يمكن التقدير بعد (ما فيه بيانات كافية)
    public final Level level;

    public ProgressStats(int furthestCumulativeAyah, int totalAyahCount, int currentJuz,
                          double dailyAveragePace, Integer estimatedDaysRemaining, Level level) {
        this.furthestCumulativeAyah = furthestCumulativeAyah;
        this.totalAyahCount = totalAyahCount;
        this.currentJuz = currentJuz;
        this.dailyAveragePace = dailyAveragePace;
        this.estimatedDaysRemaining = estimatedDaysRemaining;
        this.level = level;
    }

    public double percentComplete() {
        return totalAyahCount == 0 ? 0 : (furthestCumulativeAyah * 100.0) / totalAyahCount;
    }
}
