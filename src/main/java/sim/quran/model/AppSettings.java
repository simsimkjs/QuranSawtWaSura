package sim.quran.model;

/** إعدادات المستخدم القابلة للتخصيص: الخط، حجمه، الثيم اللوني، ونمط التصميم. */
public class AppSettings {

    public enum Theme {
        CLASSIC("كلاسيكي (رَقّي)", "theme-classic"),
        LIGHT("فاتح", "theme-light"),
        DARK("داكن", "theme-dark");

        public final String displayName;
        public final String styleClass;

        Theme(String displayName, String styleClass) {
            this.displayName = displayName;
            this.styleClass = styleClass;
        }

        public static Theme fromName(String name) {
            for (Theme t : values()) {
                if (t.name().equalsIgnoreCase(name)) return t;
            }
            return CLASSIC;
        }
    }

    /** نمط التصميم العام للتطبيق (ملف CSS مختلف تمامًا) - منفصل عن الثيم اللوني أعلاه. */
    public enum DesignStyle {
        CLASSIC_MUSHAF("كلاسيكي (المصحف المزخرف)", "/sim/quran/css/app2.css"),
        MODERN("معاصر", "/sim/quran/css/style.css");

        public final String displayName;
        public final String cssResourcePath;

        DesignStyle(String displayName, String cssResourcePath) {
            this.displayName = displayName;
            this.cssResourcePath = cssResourcePath;
        }

        public static DesignStyle fromName(String name) {
            for (DesignStyle d : values()) {
                if (d.name().equalsIgnoreCase(name)) return d;
            }
            return CLASSIC_MUSHAF;
        }
    }

    private String fontFamily;
    private double fontSize;
    private Theme theme;
    private boolean trackProgress = true; // تفعيل "مشوار حفظ القرآن" (تتبّع التقدّم + التهنئة)
    private DesignStyle designStyle = DesignStyle.CLASSIC_MUSHAF;

    public AppSettings(String fontFamily, double fontSize, Theme theme) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.theme = theme;
    }

    /**
     * الإعدادات الافتراضية. يُفضَّل الخط العثماني المضمَّن داخل التطبيق نفسه (UthmanicHafs1Ver18.ttf)
     * فنستخدم اسمه الفعلي المسجَّل بخط تحميله، ونتراجع للاسم الثابت لو لم يُحمَّل الملف أصلاً.
     */
    public static AppSettings defaults() {
        String embedded = sim.quran.data.EmbeddedFonts.uthmanicHafsFamily;
        if (embedded == null || embedded.isBlank()) embedded = "KFGQPC HAFS Uthmanic Script";
        return new AppSettings(embedded, 27, Theme.CLASSIC);
    }

    public String getFontFamily() { return fontFamily; }
    public double getFontSize() { return fontSize; }
    public Theme getTheme() { return theme; }
    public boolean isTrackProgress() { return trackProgress; }
    public DesignStyle getDesignStyle() { return designStyle; }

    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public void setFontSize(double fontSize) { this.fontSize = fontSize; }
    public void setTheme(Theme theme) { this.theme = theme; }
    public void setTrackProgress(boolean trackProgress) { this.trackProgress = trackProgress; }
    public void setDesignStyle(DesignStyle designStyle) { this.designStyle = designStyle; }

    /** نسخة جديدة مطابقة (حتى نقدر نلغي التعديلات في نافذة الإعدادات بدون التأثير على الإعدادات الحالية الفعلية). */
    public AppSettings copy() {
        AppSettings c = new AppSettings(fontFamily, fontSize, theme);
        c.trackProgress = this.trackProgress;
        c.designStyle = this.designStyle;
        return c;
    }
}
