package sim.quran.model;

import java.util.Collections;
import java.util.List;

/** محتوى ملف سورة واحد (مثل 001.json) بعد التحميل. */
public class SurahData {

    private final int surahNumber;
    private final String surahName;
    private final String audioFileName;
    private final List<AyahTiming> ayahs;

    public SurahData(int surahNumber, String surahName, String audioFileName, List<AyahTiming> ayahs) {
        this.surahNumber = surahNumber;
        this.surahName = surahName;
        this.audioFileName = audioFileName;
        this.ayahs = Collections.unmodifiableList(ayahs);
    }

    public int getSurahNumber() { return surahNumber; }
    public String getSurahName() { return surahName; }
    public String getAudioFileName() { return audioFileName; }
    public List<AyahTiming> getAyahs() { return ayahs; }

    /**
     * بحث ثنائي (binary search) عن فهرس الآية النشطة عند زمن معيّن بالميلي ثانية.
     * يفترض أن القائمة مرتّبة تصاعدياً حسب startMs (كما هي في ملفات المشروع).
     * يعيد -1 إذا لم تبدأ أي آية بعد.
     */
    public int findAyahIndexForTime(long ms) {
        int lo = 0, hi = ayahs.size() - 1, result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (ayahs.get(mid).getStartMs() <= ms) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    /** زمن نهاية الآية عند dataIndex = بداية التالية، أو Long.MAX_VALUE إن كانت الأخيرة. */
    public long endMsOf(int dataIndex) {
        if (dataIndex < 0 || dataIndex + 1 >= ayahs.size()) return Long.MAX_VALUE;
        return ayahs.get(dataIndex + 1).getStartMs();
    }
}
