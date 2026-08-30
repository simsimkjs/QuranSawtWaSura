package sim.quran.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * تجزئة كلمات مرور القرّاء. لا تُخزَّن كلمة المرور نفسها أبدًا - فقط ناتج التجزئة (hash)
 * مع "ملح" (salt) عشوائي مختلف لكل قارئ، حتى لو تطابقت كلمتا مرور قارئين لا يتطابق ناتجهما.
 */
public final class PasswordUtil {

    private PasswordUtil() {}

    public static String newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] hashed = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** يرجع true لو كلمة المرور صحيحة، أو لو ما فيه كلمة مرور أصلاً (expectedHash == null). */
    public static boolean verify(String password, String salt, String expectedHash) {
        if (expectedHash == null || expectedHash.isBlank()) return true;
        if (password == null) return false;
        return hash(password, salt).equals(expectedHash);
    }
}
