package sim.quran.model;

/** سطر واحد من فهرس السور (surahs.json). */
public class Surah {

    private final int number;
    private final String name;
    private final String dataFile;   // مثال: 001.json
    private final String audioFile;  // مثال: 001.mp3
    private final int ayahCount;

    public Surah(int number, String name, String dataFile, String audioFile, int ayahCount) {
        this.number = number;
        this.name = name;
        this.dataFile = dataFile;
        this.audioFile = audioFile;
        this.ayahCount = ayahCount;
    }

    public int getNumber() { return number; }
    public String getName() { return name; }
    public String getDataFile() { return dataFile; }
    public String getAudioFile() { return audioFile; }
    public int getAyahCount() { return ayahCount; }

    /** النص المعروض في قوائم السور (مطلوب من MainController/ReaderPane). */
    public String displayLabel() {
        return number + ". " + name;
    }

    @Override
    public String toString() {
        return displayLabel();
    }
}
