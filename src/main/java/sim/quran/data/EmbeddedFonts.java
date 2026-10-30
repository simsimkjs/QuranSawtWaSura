package sim.quran.data;

import javafx.scene.text.Font;

import java.io.InputStream;

/**
 * يحمّل الخطوط المرفقة داخل موارد التطبيق (src/main/resources/sim/quran/fonts/)
 * برمجيًا عبر Font.loadFont، فتصير متاحة للاستخدام بالاسم مباشرة دون أي تثبيت
 * على نظام التشغيل - يعمل حتى لو المستخدم ما عنده الخط أصلاً على جهازه.
 *
 * يُستدعى مرة واحدة فقط عند إقلاع التطبيق (من QuranApp.start قبل تحميل الواجهة).
 */
public final class EmbeddedFonts {

    private EmbeddedFonts() {}

    /** الاسم الفعلي المسجَّل للخط بعد تحميله بنجاح، أو null لو فشل التحميل (الملف غير موجود مثلاً). */
    public static String uthmanicHafsFamily;

    public static void loadAll() {
        uthmanicHafsFamily = loadFontFamily("/sim/quran/fonts/UthmanicHafs1Ver18.ttf", 27);
    }

    private static String loadFontFamily(String resourcePath, double size) {
        try (InputStream in = EmbeddedFonts.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("[EmbeddedFonts] الملف غير موجود: " + resourcePath);
                return null;
            }
            Font font = Font.loadFont(in, size);
            if (font == null) {
                System.err.println("[EmbeddedFonts] تعذر تحميل الخط: " + resourcePath);
                return null;
            }
            System.out.println("[EmbeddedFonts] تم تحميل الخط بنجاح: " + font.getFamily());
            return font.getFamily();
        } catch (Exception e) {
            System.err.println("[EmbeddedFonts] خطأ أثناء تحميل " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }
}
