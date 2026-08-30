package sim.quran.data;

import org.json.JSONArray;
import org.json.JSONObject;
import sim.quran.model.UserProfile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * يدير قائمة القرّاء (المستخدمين). كل قارئ له مجلد بيانات منفصل تمامًا تحت:
 *   <المجلد الشخصي>/.quran_sawt_sura/profiles/<id>/
 * فيه bookmarks.json و progress.json و settings.json خاصة به فقط.
 *
 * الفهرس نفسه (قائمة القرّاء + آخر قارئ نشط) يُخزَّن في:
 *   <المجلد الشخصي>/.quran_sawt_sura/profiles.json
 */
public class ProfileStore {

    private static File rootDir() {
        return new File(System.getProperty("user.home"), ".quran_sawt_sura");
    }

    private static File indexFile() {
        return new File(rootDir(), "profiles.json");
    }

    public static File profileDataDir(String profileId) {
        File dir = new File(new File(rootDir(), "profiles"), sanitize(profileId));
        dir.mkdirs();
        return dir;
    }

    public static List<UserProfile> loadProfiles() {
        List<UserProfile> result = new ArrayList<>();
        File file = indexFile();
        if (!file.exists()) return result;
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(content);
            JSONArray arr = root.optJSONArray("profiles");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.getJSONObject(i);
                    result.add(new UserProfile(
                            p.getString("id"),
                            p.getString("name"),
                            p.optString("password_hash", null),
                            p.optString("password_salt", null)));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static String getLastActiveProfileId() {
        File file = indexFile();
        if (!file.exists()) return null;
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return new JSONObject(content).optString("last_active", null);
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveProfiles(List<UserProfile> profiles, String lastActiveId) {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        for (UserProfile p : profiles) {
            JSONObject o = new JSONObject();
            o.put("id", p.getId());
            o.put("name", p.getDisplayName());
            if (p.hasPassword()) {
                o.put("password_hash", p.getPasswordHash());
                o.put("password_salt", p.getPasswordSalt());
            }
            arr.put(o);
        }
        root.put("profiles", arr);
        root.put("last_active", lastActiveId);
        try {
            rootDir().mkdirs();
            Files.writeString(indexFile().toPath(), root.toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("تعذر حفظ فهرس القرّاء: " + e.getMessage());
        }
    }

    /** يعيّن كلمة مرور جديدة لقارئ (يستبدل أي كلمة سابقة). */
    public static void setPassword(UserProfile profile, String plainPassword) {
        String salt = PasswordUtil.newSalt();
        profile.setPassword(PasswordUtil.hash(plainPassword, salt), salt);
    }

    /** يتحقق من كلمة المرور. يرجع true تلقائيًا لو القارئ ما عنده كلمة مرور أصلاً. */
    public static boolean verifyPassword(UserProfile profile, String plainPassword) {
        return PasswordUtil.verify(plainPassword, profile.getPasswordSalt(), profile.getPasswordHash());
    }

    /** يولّد معرّفًا فريدًا من الاسم (بدون تعارض مع قرّاء موجودين). */
    public static String newProfileId(String displayName, List<UserProfile> existing) {
        String base = sanitize(displayName);
        if (base.isBlank()) base = "reader";
        String candidate = base;
        int suffix = 2;
        while (idExists(candidate, existing)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private static boolean idExists(String candidate, List<UserProfile> existing) {
        for (UserProfile p : existing) {
            if (p.getId().equals(candidate)) return true;
        }
        return false;
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.trim().replaceAll("[^\\p{L}\\p{N}_\\-]", "_");
    }
}
