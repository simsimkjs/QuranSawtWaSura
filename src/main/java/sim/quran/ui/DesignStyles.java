package sim.quran.ui;

import javafx.scene.Scene;
import sim.quran.model.AppSettings;

/**
 * مصدر وحيد لـ"نمط التصميم" الحالي في التطبيق.
 *
 * بدون هذا الصنف كانت كل نافذة منبثقة تحطّ شريحة CSS مثبّتة يدويًا (app2.css)، فيصير
 * شكل النافذة الجانبية مختلفًا عن النافذة الرئيسية لو اختار المستخدم النمط "معاصر".
 * الآن النافذة الرئيسية تحدّث النمط الحالي هنا، وكل نافذة جديدة تستدعي applyTo(scene).
 */
public final class DesignStyles {

    private static volatile AppSettings.DesignStyle current = AppSettings.DesignStyle.CLASSIC_MUSHAF;

    private DesignStyles() {}

    public static void setCurrent(AppSettings.DesignStyle style) {
        if (style != null) current = style;
    }

    public static AppSettings.DesignStyle getCurrent() {
        return current;
    }

    /** يستبدل شريحة CSS الخاصة بالمشهد بالشريحة المطابقة للنمط الحالي (يتجاهل لو الملف مفقود). */
    public static void applyTo(Scene scene) {
        if (scene == null) return;
        scene.getStylesheets().clear();
        var url = DesignStyles.class.getResource(current.cssResourcePath);
        if (url != null) scene.getStylesheets().add(url.toExternalForm());
    }
}
