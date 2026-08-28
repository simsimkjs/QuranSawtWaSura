package sim.quran.model;

/**
 * مستطيل يحدد موضع آية واحدة فوق صورة السورة.
 * الإحداثيات نسب (0..1) من أبعاد الصورة الأصلية (وليست بكسل مطلق)،
 * حتى تبقى صحيحة بغض النظر عن حجم عرض الصورة على الشاشة.
 */
public class AyahRegion {

    public final int ayahNumber;
    public final double x;
    public final double y;
    public final double width;
    public final double height;

    public AyahRegion(int ayahNumber, double x, double y, double width, double height) {
        this.ayahNumber = ayahNumber;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
}
