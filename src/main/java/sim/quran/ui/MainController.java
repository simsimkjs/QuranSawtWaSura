package sim.quran.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.*;
import javafx.stage.Stage;
import sim.quran.data.BookmarkStore;
import sim.quran.data.MemorizationStore;
import sim.quran.data.ProfileStore;
import sim.quran.data.SearchIndex;
import sim.quran.data.SettingsStore;
import sim.quran.data.SurahIndexLoader;
import sim.quran.model.AppSettings;
import sim.quran.model.ProgressStats;
import sim.quran.model.Surah;
import sim.quran.model.UserProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class MainController {

    @FXML private ListView<Surah> surahListView;
    @FXML private javafx.scene.layout.StackPane centerPane;
    @FXML private TextField searchField;
    @FXML private Label statusLabel;
    @FXML private Label miniProgressLabel;
    @FXML private ProgressBar miniProgressBar;
    @FXML private javafx.scene.layout.VBox miniProgressBox;
    @FXML private Label activeProfileLabel;
    @FXML private Button toggleTrackingButton;

    private ReaderPane readerPane;
    private BookmarkStore bookmarkStore;
    private MemorizationStore memorizationStore;
    private SearchIndex searchIndex;
    private List<Surah> allSurahs;
    private Integer pendingJumpAyah;
    private boolean silentRestore = false; // true أثناء الاستعادة الصامتة عند الإقلاع (بدون تشغيل صوت تلقائي)
    private AppSettings appSettings;

    private List<UserProfile> profiles;
    private UserProfile activeProfile;

    @FXML
    public void initialize() {
        loadActiveProfileData(); // يهيّئ profiles/activeProfile/bookmarkStore/memorizationStore/appSettings

        readerPane = new ReaderPane(bookmarkStore, memorizationStore, this::setStatus, this::onJuzCompleted);
        readerPane.applySettings(appSettings);
        centerPane.getChildren().add(readerPane);

        // تحميل شريحة CSS المناسبة (كلاسيكي/معاصر) بمجرد ما تصير النافذة الرئيسية مربوطة بمشهد (Scene) فعلي
        centerPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) applyDesignStylesheet();
        });

        try {
            allSurahs = SurahIndexLoader.load();
        } catch (Exception e) {
            setStatus("تعذّر تحميل فهرس السور: " + e.getMessage());
            allSurahs = List.of();
        }
        readerPane.setAllSurahs(allSurahs);

        surahListView.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        surahListView.getItems().addAll(allSurahs);
        surahListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Surah item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayLabel());
            }
        });
        surahListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                readerPane.loadSurah(selected, pendingJumpAyah, !silentRestore);
                pendingJumpAyah = null;
                silentRestore = false;
                refreshMiniProgress();
            }
        });

        searchIndex = new SearchIndex(allSurahs);

        // تحديد آخر موضع قراءة تلقائيًا عند الإقلاع - نؤجّلها لبعد ما تظهر النافذة فعليًا،
        // لأن تحميل السورة يشمل تهيئة محرّك الصوت (MediaPlayer) وأول استخدام له على بعض
        // أجهزة ويندوز يمكن ياخذ وقت طويل (فحص مكتبات صوتية أصلية)
        goToLastPositionDeferred();

        setStatus("جاهز — تم تحميل " + allSurahs.size() + " سورة من الفهرس.");
        refreshProfileLabel();
        refreshMiniProgress();
        updateTrackingButtonLabel();

        // تحديث دوري خفيف لشريط التقدّم الجانبي أثناء الاستماع المتواصل
        javafx.animation.Timeline progressTicker = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(15), e -> refreshMiniProgress()));
        progressTicker.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        progressTicker.play();
    }

    // ==================== إدارة القراء (المستخدمين) ====================

    private void loadActiveProfileData() {
        profiles = new ArrayList<>(ProfileStore.loadProfiles());

        // خصوصية: البرنامج يبدأ دائمًا بوضع "ضيف" بلا أي بيانات شخصية، بغض النظر عن آخر
        // قارئ كان نشطًا - الدخول لأي قارئ حقيقي له بيانات يتطلب اختياره صراحةً (وكلمة
        // مروره لو حدّدها) من نافذة "القرّاء"، وليس تلقائيًا عند فتح البرنامج
        activeProfile = UserProfile.GUEST;

        var dataDir = ProfileStore.profileDataDir(activeProfile.getId());
        bookmarkStore = BookmarkStore.load(dataDir);
        memorizationStore = MemorizationStore.load(dataDir);
        appSettings = SettingsStore.load(dataDir);
    }

    @FXML
    private void onManageProfiles() {
        ProfileDialog.show(profiles, activeProfile.getId(), allSurahs,
                this::switchToProfile,
                this::addNewProfile,
                this::deleteProfile,
                this::setProfilePassword,
                this::describePosition);
    }

    @FXML
    private void onShowNavigation() {
        NavigationDialog.show(allSurahs, this::goToAyah);
    }

    /** نص "مكان التوقف" لقارئ معيّن: جزء X - سورة Y - آية Z (أو "لم يبدأ بعد"). */
    private String describePosition(UserProfile profile) {
        var dataDir = ProfileStore.profileDataDir(profile.getId());
        BookmarkStore store = profile.getId().equals(activeProfile.getId())
                ? bookmarkStore : BookmarkStore.load(dataDir);

        int surahNumber = store.getLastSurah();
        int ayahNumber = store.getLastAyah();
        if (surahNumber <= 0 || ayahNumber <= 0) return "لم يبدأ بعد";

        return allSurahs.stream()
                .filter(s -> s.getNumber() == surahNumber)
                .findFirst()
                .map(surah -> "جزء " + sim.quran.model.JuzBoundaries.juzOf(surahNumber, ayahNumber)
                        + " - سورة " + surah.getName() + " - آية " + ayahNumber)
                .orElse("لم يبدأ بعد");
    }

    private void switchToProfile(UserProfile target) {
        // نحفظ بيانات القارئ الحالي قبل ما نطلع منه (المشوار يُكتب لا تزامنيًا، فنفرغه هنا)
        bookmarkStore.save();
        memorizationStore.flush();

        activeProfile = target;
        if (!activeProfile.isGuest()) {
            ProfileStore.saveProfiles(profiles, activeProfile.getId());
        }

        var dataDir = ProfileStore.profileDataDir(activeProfile.getId());
        bookmarkStore = BookmarkStore.load(dataDir);
        memorizationStore = MemorizationStore.load(dataDir);
        appSettings = SettingsStore.load(dataDir);

        readerPane.switchStores(bookmarkStore, memorizationStore);
        readerPane.applySettings(appSettings);
        applyDesignStylesheet();
        readerPane.resetTrackingPaused(true); // كل قارئ يبدأ جلسته بوضع "إيقاف المتابعة" لحد ما يضغط "استئناف" صراحةً
        updateTrackingButtonLabel();

        refreshProfileLabel();
        refreshMiniProgress();
        setStatus("تم التبديل إلى: " + activeProfile.getDisplayName());
        goToLastPositionDeferred();
    }

    private void addNewProfile(String name, String password) {
        UserProfile newProfile = new UserProfile(ProfileStore.newProfileId(name, profiles), name);
        if (password != null && !password.isBlank()) {
            ProfileStore.setPassword(newProfile, password);
        }
        profiles.add(newProfile);
        ProfileStore.saveProfiles(profiles, newProfile.getId());
        switchToProfile(newProfile);
    }

    private void setProfilePassword(UserProfile target, String newPasswordOrNull) {
        if (newPasswordOrNull == null) {
            target.clearPassword();
            setStatus("تمت إزالة كلمة مرور " + target.getDisplayName() + ".");
        } else {
            ProfileStore.setPassword(target, newPasswordOrNull);
            setStatus("تم تحديث كلمة مرور " + target.getDisplayName() + ".");
        }
        // نحافظ على آخر قارئ نشط كما هو: لو كنا بالوضع "ضيف" ما نسجّل القارئ الذي
        // عدّلنا له كلمة المرور كآخر قارئ نشط (الضيف ليس قارئًا حقيقيًا سجّلناه)
        ProfileStore.saveProfiles(profiles, activeProfile.isGuest() ? null : activeProfile.getId());
    }

    private void deleteProfile(UserProfile target) {
        boolean wasActive = target.getId().equals(activeProfile.getId());
        profiles.removeIf(p -> p.getId().equals(target.getId()));

        if (wasActive) {
            // نرجع للضيف دائمًا (لا نفتح تلقائيًا قارئًا حقيقيًا آخر قد يكون محميًا بكلمة مرور).
            // نستدعيه قبل حذف المجلد حتى لا تحاول switchToProfile حفظ مجلد منتهٍ بالفعل.
            switchToProfile(UserProfile.GUEST);
        }
        // نحفظ فهرس القرّاء دائمًا بعد الحذف - بدون هذا السطر يظل القارئ المحذوف
        // مسجّلًا في profiles.json ويعود ظاهرًا بعد إعادة تشغيل البرنامج
        ProfileStore.saveProfiles(profiles, activeProfile.isGuest() ? null : activeProfile.getId());

        deleteDirectoryRecursively(ProfileStore.profileDataDir(target.getId()));
    }

    private static void deleteDirectoryRecursively(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) deleteDirectoryRecursively(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    /** يطبّق شريحة CSS المطابقة لـ appSettings.getDesignStyle() على مشهد النافذة الرئيسية ويوثّق النمط الحالي للنوافذ المنبثقة. */
    private void applyDesignStylesheet() {
        if (appSettings == null) return;
        DesignStyles.setCurrent(appSettings.getDesignStyle());
        DesignStyles.applyTo(centerPane.getScene());
    }

    /**
     * يحفظ أي تغييرات معلّقة (آخر موضع القراءة + العلامات المرجعية) قبل إغلاق النافذة.
     * يُستدعى من QuranApp عند طلب الإغلاق (زر ✕)، لأن onExit (قائمة ملف→خروج) لا يغطي هذه الحالة.
     */
    public void persistOnClose() {
        if (bookmarkStore != null) bookmarkStore.save();
        if (memorizationStore != null) memorizationStore.flush();
    }

    private void refreshProfileLabel() {
        if (activeProfileLabel != null) {
            activeProfileLabel.setText("👤 " + activeProfile.getDisplayName());
        }
    }

    private void goToLastPositionDeferred() {
        if (allSurahs == null || allSurahs.isEmpty()) return;
        Platform.runLater(() -> {
            int lastSurahNumber = bookmarkStore.getLastSurah();
            Surah target = allSurahs.stream()
                    .filter(s -> s.getNumber() == lastSurahNumber)
                    .findFirst()
                    .orElse(allSurahs.get(0));
            if (target.equals(surahListView.getSelectionModel().getSelectedItem())) {
                readerPane.loadSurah(target, bookmarkStore.getLastAyah(), false);
            } else {
                silentRestore = true;
                pendingJumpAyah = bookmarkStore.getLastAyah() > 0 ? bookmarkStore.getLastAyah() : null;
                surahListView.getSelectionModel().select(target);
            }
        });
    }

    private void refreshMiniProgress() {
        if (miniProgressLabel == null || allSurahs == null || allSurahs.isEmpty()) return;

        boolean tracking = appSettings != null && appSettings.isTrackProgress();
        if (miniProgressBox != null) {
            miniProgressBox.setVisible(tracking);
            miniProgressBox.setManaged(tracking);
        }
        if (!tracking) return;

        ProgressStats stats = memorizationStore.computeStats(allSurahs);
        miniProgressLabel.setText(String.format(Locale.ROOT,
                "تقدّمك: %.1f%% (الجزء %s من 30)",
                stats.percentComplete(), stats.currentJuz == 0 ? "-" : String.valueOf(stats.currentJuz)));
        miniProgressBar.setProgress(stats.percentComplete() / 100.0);
    }

    /** يفتح سورة على آية محددة: قفزة مباشرة لو السورة نفسها مفتوحة أصلاً، وإلا يحمّلها أولاً ثم يقفز. */
    private void goToAyah(Surah surah, int ayahNumber) {
        Surah currentlySelected = surahListView.getSelectionModel().getSelectedItem();
        if (currentlySelected != null && currentlySelected.getNumber() == surah.getNumber()) {
            readerPane.jumpToAyahNumber(ayahNumber);
        } else {
            pendingJumpAyah = ayahNumber;
            surahListView.getSelectionModel().select(surah);
        }
    }

    @FXML
    private void onSearch() {
        String query = searchField.getText();
        if (query == null || query.isBlank()) return;

        if (!searchIndex.isBuilt()) {
            setStatus("جاري بناء فهرس البحث لأول مرة (قد يستغرق ثوانٍ حسب عدد السور المتوفرة)...");
            searchIndex.buildAsync(
                    percent -> Platform.runLater(() -> setStatus("بناء فهرس البحث... " + percent + "%")),
                    () -> Platform.runLater(this::onSearch)
            );
            return;
        }

        List<SearchIndex.Hit> hits = searchIndex.search(query);
        showSearchResults(query, hits);
    }

    private void showSearchResults(String query, List<SearchIndex.Hit> hits) {
        Stage dialog = new Stage();
        dialog.setTitle("نتائج البحث عن: " + query);

        ListView<SearchIndex.Hit> list = new ListView<>();
        list.getItems().addAll(hits);
        list.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(SearchIndex.Hit item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.surah.displayLabel() + "  —  آية " + item.ayah.getAyahNumber()
                            + "：" + item.ayah.getText());
                }
            }
        });
        list.setOnMouseClicked(e -> {
            SearchIndex.Hit selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                goToAyah(selected.surah, selected.ayah.getAyahNumber());
                dialog.close();
            }
        });

        Label countLabel = new Label(hits.isEmpty() ? "لا توجد نتائج." : (hits.size() + " نتيجة"));
        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(8, countLabel, list);
        root.setPadding(new javafx.geometry.Insets(10));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        dialog.setScene(new javafx.scene.Scene(root, 520, 480));
        dialog.show();
    }

    @FXML
    private void onShowBookmarks() {
        Stage dialog = new Stage();
        dialog.setTitle("العلامات المرجعية");

        ListView<BookmarkStore.Bookmark> list = new ListView<>();
        list.getItems().addAll(bookmarkStore.getBookmarks());
        list.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(BookmarkStore.Bookmark item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label);
            }
        });
        list.setOnMouseClicked(e -> {
            BookmarkStore.Bookmark selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                Optional<Surah> target = allSurahs.stream()
                        .filter(s -> s.getNumber() == selected.surahNumber).findFirst();
                target.ifPresent(surah -> goToAyah(surah, selected.ayahNumber));
                dialog.close();
            }
        });

        Button deleteBtn = new Button("حذف العلامة المحددة");
        deleteBtn.setOnAction(e -> {
            BookmarkStore.Bookmark selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                bookmarkStore.removeBookmark(selected);
                bookmarkStore.save();
                list.getItems().remove(selected);
            }
        });

        javafx.scene.layout.VBox root = new javafx.scene.layout.VBox(8, list, deleteBtn);
        root.setPadding(new javafx.geometry.Insets(10));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        dialog.setScene(new javafx.scene.Scene(root, 420, 480));
        dialog.show();
    }

    @FXML
    private void onSettings() {
        SettingsDialog.show(appSettings, newSettings -> {
            appSettings = newSettings;
            SettingsStore.save(ProfileStore.profileDataDir(activeProfile.getId()), appSettings);
            readerPane.applySettings(appSettings);
            applyDesignStylesheet();
            refreshMiniProgress();
        });
    }

    @FXML
    private void onShowProgress() {
        if (!appSettings.isTrackProgress()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
            alert.setHeaderText(null);
            alert.setContentText("مشوار حفظ القرآن معطَّل حاليًا لهذا القارئ. فعّله من الإعدادات لتتبّع تقدّمك.");
            alert.showAndWait();
            return;
        }
        ProgressDialog.show(memorizationStore.computeStats(allSurahs));
    }

    /** يُستدعى تلقائيًا من ReaderPane عند اكتمال جزء جديد فعليًا لأول مرة. */
    private void onJuzCompleted(int juzNumber, boolean isFullQuran) {
        Platform.runLater(() -> {
            refreshMiniProgress();
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
            alert.getDialogPane().getStyleClass().add("congrats-alert");
            if (isFullQuran) {
                alert.setTitle("ختمة مباركة!");
                alert.setHeaderText("🎉 تهانينا يا " + activeProfile.getDisplayName() + "! أكملت المصحف الشريف كاملاً 🎉");
                alert.setContentText("بارك الله فيك وتقبّل منك. اللهم اجعل القرآن ربيع قلبك ونور صدرك.\n"
                        + "يمكنك متابعة المراجعة أو البدء من جديد في أي وقت.");
            } else {
                alert.setTitle("تهانينا!");
                alert.setHeaderText("🎉 أكملت الجزء " + juzNumber + " من 30 🎉");
                alert.setContentText("بارك الله فيك، استمر على هذا المنوال. بقي "
                        + (30 - juzNumber) + " جزءًا لإكمال المصحف كاملاً بإذن الله.");
            }
            alert.showAndWait();
        });
    }

    @FXML
    private void onGoToLastPosition() {
        readerPane.resumeTracking(); // استئناف تلقائي - رجوعك لموضعك يعني إنك عدت للتتبّع الحي
        updateTrackingButtonLabel();
        int lastSurahNumber = bookmarkStore.getLastSurah();
        int lastAyah = bookmarkStore.getLastAyah();
        allSurahs.stream()
                .filter(s -> s.getNumber() == lastSurahNumber)
                .findFirst()
                .ifPresent(surah -> goToAyah(surah, lastAyah > 0 ? lastAyah : 1));
    }

    @FXML
    private void onToggleTracking() {
        if (readerPane.isTrackingPaused()) {
            readerPane.resumeTracking();
            setStatus("تم استئناف تتبّع الموضع.");
        } else {
            readerPane.pauseTracking();
            setStatus("تم تجميد موضعك الحالي - تقدر تتجول بحرية بدون ما تفقده. اضغط \"متابعة الحفظ\" للرجوع له.");
        }
        updateTrackingButtonLabel();
    }

    private void updateTrackingButtonLabel() {
        if (toggleTrackingButton == null) return;
        boolean paused = readerPane.isTrackingPaused();
        toggleTrackingButton.setText(paused ? "▶ استئناف التتبّع" : "⏸ إيقاف المتابعة (تجميد الموضع)");
        toggleTrackingButton.setStyle(paused ? "-fx-background-color:#9a6f00; -fx-text-fill:white;" : "");
    }

    @FXML
    private void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        alert.setTitle("عن البرنامج");
        alert.setHeaderText("القرآن الكريم — صوت وصورة");
        alert.setContentText("تطبيق لعرض السور مع تلاوة صوتية متزامنة، تكرار للحفظ، تفسير، بحث، علامات مرجعية، "
                + "ودعم أكثر من قارئ بمشوار حفظ خاص لكل واحد.\nتطوير: sim");
        alert.showAndWait();
    }

    @FXML
    private void onExit() {
        bookmarkStore.save();
        memorizationStore.flush();
        Platform.exit();
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            Platform.runLater(() -> statusLabel.setText(text));
        }
    }
}
