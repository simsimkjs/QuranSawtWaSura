package sim.quran.model;

/**
 * آية واحدة داخل ملف بيانات السورة (مثل 001.json).
 * ayahNumber == 0 يعني مقطعاً افتتاحياً (استعاذة) بلا نص يُعرض أو يُظلَّل.
 */
public class AyahTiming {

    private final int index;
    private final int ayahNumber;
    private final String text;
    private final long startMs;
    private final boolean export;

    public AyahTiming(int index, int ayahNumber, String text, long startMs, boolean export) {
        this.index = index;
        this.ayahNumber = ayahNumber;
        this.text = text == null ? "" : text;
        this.startMs = startMs;
        this.export = export;
    }

    public int getIndex() { return index; }
    public int getAyahNumber() { return ayahNumber; }
    public String getText() { return text; }
    public long getStartMs() { return startMs; }
    public boolean isExport() { return export; }

    /** هل هذا العنصر آية حقيقية لها نص (وليس مقطعاً افتتاحياً فارغاً)؟ */
    public boolean hasText() {
        return ayahNumber > 0 && !text.isBlank();
    }
}
