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

    private ReaderPane readerPane;
    private BookmarkStore bookmarkStore;
    private MemorizationStore memorizationStore;
    private SearchIndex searchIndex;
    private List<Surah> allSurahs;
    private Integer pendingJumpAyah;
    private AppSettings appSettings;

    private List<UserProfile> profiles;
    private UserProfile activeProfile;

    @FXML
    public void initialize() {
        loadActiveProfileData(); // يهيّئ profiles/activeProfile/bookmarkStore/memorizationStore/appSettings

        readerPane = new ReaderPane(bookmarkStore, memorizationStore, this::setStatus, this::onJuzCompleted);
        readerPane.applySettings(appSettings);
        centerPane.getChildren().add(readerPane);

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
                readerPane.loadSurah(selected, pendingJumpAyah);
                pendingJumpAyah = null;
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

        // تحديث دوري خفيف لشريط التقدّم الجانبي أثناء الاستماع المتواصل
        javafx.animation.Timeline progressTicker = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.seconds(15), e -> refreshMiniProgress()));
        progressTicker.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        progressTicker.play();
    }

    // ==================== إدارة القراء (المستخدمين) ====================

    private void loadActiveProfileData() {
        profiles = new ArrayList<>(ProfileStore.loadProfiles());
        String lastActiveId = ProfileStore.getLastActiveProfileId();

        if (profiles.isEmpty()) {
            // أول تشغيل: ننشئ قارئًا افتراضيًا تلقائيًا بدون إزعاج المستخدم بأي نافذة إجبارية
            UserProfile defaultProfile = new UserProfile(
                    ProfileStore.newProfileId("القارئ الأول", profiles), "القارئ الأول");
            profiles.add(defaultProfile);
            ProfileStore.saveProfiles(profiles, defaultProfile.getId());
            activeProfile = defaultProfile;
        } else {
            activeProfile = profiles.stream()
                    .filter(p -> p.getId().equals(lastActiveId))
                    .findFirst()
                    .orElse(profiles.get(0));
        }

        var dataDir = ProfileStore.profileDataDir(activeProfile.getId());
        bookmarkStore = BookmarkStore.load(dataDir);
        memorizationStore = MemorizationStore.load(dataDir);
        appSettings = SettingsStore.load(dataDir);
    }

    @FXML
    private void onManageProfiles() {
        ProfileDialog.show(profiles, activeProfile.getId(),
                this::switchToProfile,
                this::addNewProfile,
                this::deleteProfile);
    }

    private void switchToProfile(UserProfile target) {
        // نحفظ بيانات القارئ الحالي قبل ما نطلع منه
        bookmarkStore.save();

        activeProfile = target;
        ProfileStore.saveProfiles(profiles, activeProfile.getId());

        var dataDir = ProfileStore.profileDataDir(activeProfile.getId());
        bookmarkStore = BookmarkStore.load(dataDir);
        memorizationStore = MemorizationStore.load(dataDir);
        appSettings = SettingsStore.load(dataDir);

        readerPane.switchStores(bookmarkStore, memorizationStore);
        readerPane.applySettings(appSettings);

        refreshProfileLabel();
        refreshMiniProgress();
        setStatus("تم التبديل إلى: " + activeProfile.getDisplayName());
        goToLastPositionDeferred();
    }

    private void addNewProfile(String name) {
        UserProfile newProfile = new UserProfile(ProfileStore.newProfileId(name, profiles), name);
        profiles.add(newProfile);
        ProfileStore.saveProfiles(profiles, activeProfile.getId());
        switchToProfile(newProfile);
    }

    private void deleteProfile(UserProfile target) {
        boolean wasActive = target.getId().equals(activeProfile.getId());
        profiles.removeIf(p -> p.getId().equals(target.getId()));
        // نحذف مجلد بياناته بالكامل
        deleteDirectoryRecursively(ProfileStore.profileDataDir(target.getId()));

        if (wasActive && !profiles.isEmpty()) {
            switchToProfile(profiles.get(0));
        } else {
            ProfileStore.saveProfiles(profiles, activeProfile.getId());
        }
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
                readerPane.loadSurah(target, bookmarkStore.getLastAyah());
            } else {
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
        int lastSurahNumber = bookmarkStore.getLastSurah();
        allSurahs.stream()
                .filter(s -> s.getNumber() == lastSurahNumber)
                .findFirst()
                .ifPresent(surah -> surahListView.getSelectionModel().select(surah));
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
        Platform.exit();
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            Platform.runLater(() -> statusLabel.setText(text));
        }
    }
}
