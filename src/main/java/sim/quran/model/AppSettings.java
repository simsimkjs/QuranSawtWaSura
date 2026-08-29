package sim.quran.model;

/** إعدادات المستخدم القابلة للتخصيص: الخط، حجمه، والثيم. */
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

    private String fontFamily;
    private double fontSize;
    private Theme theme;
    private boolean trackProgress = true; // تفعيل "مشوار حفظ القرآن" (تتبّع التقدّم + التهنئة)

    public AppSettings(String fontFamily, double fontSize, Theme theme) {
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.theme = theme;
    }

    public static AppSettings defaults() {
        return new AppSettings("KFGQPC Uthmanic Script HAFS", 27, Theme.CLASSIC);
    }

    public String getFontFamily() { return fontFamily; }
    public double getFontSize() { return fontSize; }
    public Theme getTheme() { return theme; }
    public boolean isTrackProgress() { return trackProgress; }

    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public void setFontSize(double fontSize) { this.fontSize = fontSize; }
    public void setTheme(Theme theme) { this.theme = theme; }
    public void setTrackProgress(boolean trackProgress) { this.trackProgress = trackProgress; }

    /** نسخة جديدة مطابقة (حتى نقدر نلغي التعديلات في نافذة الإعدادات بدون التأثير على الإعدادات الحالية الفعلية). */
    public AppSettings copy() {
        AppSettings c = new AppSettings(fontFamily, fontSize, theme);
        c.trackProgress = this.trackProgress;
        return c;
    }
}
