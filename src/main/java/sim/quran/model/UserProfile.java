package sim.quran.model;

/** قارئ واحد (مستخدم) - له بياناته الخاصة تمامًا: علامات مرجعية، مشوار حفظ، إعدادات. */
public class UserProfile {

    public static final String GUEST_ID = "guest";
    /** قارئ الضيف: دائمًا موجود، بلا كلمة مرور، وهو الحالة الافتراضية عند فتح البرنامج. */
    public static final UserProfile GUEST = new UserProfile(GUEST_ID, "ضيف");

    private final String id;   // معرّف ثابت يُستخدم كاسم مجلد (بدون مسافات/رموز خاصة)
    private String displayName;
    private String passwordHash; // null = بدون كلمة مرور (دخول حرّ)
    private String passwordSalt;

    public UserProfile(String id, String displayName) {
        this(id, displayName, null, null);
    }

    public UserProfile(String id, String displayName, String passwordHash, String passwordSalt) {
        this.id = id;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPasswordHash() { return passwordHash; }
    public String getPasswordSalt() { return passwordSalt; }
    public boolean hasPassword() { return passwordHash != null && !passwordHash.isBlank(); }
    public boolean isGuest() { return GUEST_ID.equals(id); }

    public void setPassword(String hash, String salt) {
        this.passwordHash = hash;
        this.passwordSalt = salt;
    }

    public void clearPassword() {
        this.passwordHash = null;
        this.passwordSalt = null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
