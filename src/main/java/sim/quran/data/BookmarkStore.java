package sim.quran.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * يخزن العلامات المرجعية وآخر موضع قراءة في:
 * <المجلد الشخصي للمستخدم>/.quran_sawt_sura/bookmarks.json
 */
public class BookmarkStore {

    /** علامة مرجعية واحدة. الحقول عامة عمداً لأن الواجهة (MainController) تقرأها مباشرة. */
    public static class Bookmark {
        public final String label;
        public final int surahNumber;
        public final int ayahNumber;
        public final LocalDateTime createdAt;

        public Bookmark(String label, int surahNumber, int ayahNumber, LocalDateTime createdAt) {
            this.label = label;
            this.surahNumber = surahNumber;
            this.ayahNumber = ayahNumber;
            this.createdAt = createdAt;
        }
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private final Path filePath;

    private int lastSurah = 1;
    private int lastAyah = 0;
    private final List<Bookmark> bookmarks = new ArrayList<>();

    private BookmarkStore(File dataDir) {
        Path dir = dataDir.toPath();
        this.filePath = dir.resolve("bookmarks.json");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        loadFromDisk();
    }

    /** يحمّل (أو ينشئ) مخزن العلامات المرجعية الخاص بمجلد قارئ معيّن. */
    public static BookmarkStore load(File profileDataDir) {
        return new BookmarkStore(profileDataDir);
    }

    public int getLastSurah() { return lastSurah; }
    public int getLastAyah() { return lastAyah; }
    public List<Bookmark> getBookmarks() { return bookmarks; }

    public void updateLastPosition(int surahNumber, int ayahNumber) {
        this.lastSurah = surahNumber;
        this.lastAyah = ayahNumber;
    }

    public void addBookmark(String label, int surahNumber, int ayahNumber) {
        bookmarks.add(new Bookmark(label, surahNumber, ayahNumber, LocalDateTime.now()));
    }

    public void removeBookmark(Bookmark bookmark) {
        bookmarks.remove(bookmark);
    }

    private void loadFromDisk() {
        if (!Files.exists(filePath)) return;
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);
            lastSurah = root.optInt("last_surah", 1);
            lastAyah = root.optInt("last_ayah", 0);
            JSONArray arr = root.optJSONArray("bookmarks");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.getJSONObject(i);
                    LocalDateTime created;
                    try {
                        created = LocalDateTime.parse(b.optString("created_at"), FMT);
                    } catch (Exception e) {
                        created = LocalDateTime.now();
                    }
                    bookmarks.add(new Bookmark(
                            b.optString("label", "بدون اسم"),
                            b.optInt("surah", 1),
                            b.optInt("ayah", 0),
                            created));
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** يحفظ آخر موضع وقائمة العلامات على القرص. تستدعيه الواجهة صراحةً بعد كل تعديل. */
    public void save() {
        JSONObject root = new JSONObject();
        root.put("last_surah", lastSurah);
        root.put("last_ayah", lastAyah);
        JSONArray arr = new JSONArray();
        for (Bookmark b : bookmarks) {
            JSONObject o = new JSONObject();
            o.put("label", b.label);
            o.put("surah", b.surahNumber);
            o.put("ayah", b.ayahNumber);
            o.put("created_at", b.createdAt.format(FMT));
            arr.put(o);
        }
        root.put("bookmarks", arr);
        try {
            Files.writeString(filePath, root.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("تعذر حفظ العلامات المرجعية: " + e.getMessage());
        }
    }
}
