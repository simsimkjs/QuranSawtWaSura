package sim.quran.data;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * تجزئة كلمات مرور القرّاء. لا تُخزَّن كلمة المرور نفسها أبدًا - فقط ناتج التجزئة (hash)
 * مع "ملح" (salt) عشوائي مختلف لكل قارئ، حتى لو تطابقت كلمتا مرور قارئين لا يتطابق ناتجهما.
 *
 * التجزئة الحالية: PBKDF2WithHmacSHA256 بعدد تكرارات عالٍ (أبطأ و أكثر مقاومة لخوارزميات
 * كسر كلمات المرور من SHA-256 المباشر). الصيغة المخزَّنة: "pbkdf2$&lt;iterations&gt;$&lt;base64&gt;".
 *
 * توافق رجعي: الحسابات القديمة المخزَّنة كـ Base64 لـ SHA-256 (بدون بادئة) ما زالت تُتحقَّق
 * بنفس الطريقة القديمة، وتُرقّى تلقائيًا لأول مرة يعيد فيها المستخدم تعيين كلمة مروره.
 */
public final class PasswordUtil {

    private static final String PBKDF2_PREFIX = "pbkdf2$";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_LENGTH_BITS = 256;

    private PasswordUtil() {}

    public static String newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** يحسب تجزئة PBKDF2 (بالصيغة الحديثة مع البادئة). */
    public static String hash(String password, String salt) {
        try {
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(), saltBytes, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] derived = factory.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return PBKDF2_PREFIX + PBKDF2_ITERATIONS + "$" + Base64.getEncoder().encodeToString(derived);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** الصيغة القديمة (SHA-256 مباشر) - تُستخدم فقط لتوافق كلمات المرور المحفوظة سابقًا. */
    private static String legacySha256(String password, String salt) {
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

        if (expectedHash.startsWith(PBKDF2_PREFIX)) {
            // مقارنة بزمن ثابت لتقليل هجمات التوقيت
            return MessageDigest.isEqual(
                    hash(password, salt).getBytes(StandardCharsets.UTF_8),
                    expectedHash.getBytes(StandardCharsets.UTF_8));
        }
        // صيغة قديمة (SHA-256) - توافق رجعي
        return MessageDigest.isEqual(
                legacySha256(password, salt).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
