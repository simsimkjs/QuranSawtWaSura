package sim.quran.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.*;
import javafx.stage.Stage;
import sim.quran.data.BookmarkStore;
import sim.quran.data.SearchIndex;
import sim.quran.data.SettingsStore;
import sim.quran.data.SurahIndexLoader;
import sim.quran.model.AppSettings;
import sim.quran.model.Surah;

import java.util.List;
import java.util.Optional;

public class MainController {

    @FXML private ListView<Surah> surahListView;
    @FXML private javafx.scene.layout.StackPane centerPane;
    @FXML private TextField searchField;
    @FXML private Label statusLabel;

    private ReaderPane readerPane;
    private BookmarkStore bookmarkStore;
    private SearchIndex searchIndex;
    private List<Surah> allSurahs;
    private Integer pendingJumpAyah;
    private AppSettings appSettings;

    @FXML
    public void initialize() {
        bookmarkStore = BookmarkStore.load();
        appSettings = SettingsStore.load();
        readerPane = new ReaderPane(bookmarkStore, this::setStatus);
        readerPane.applySettings(appSettings);
        centerPane.getChildren().add(readerPane);

        try {
            allSurahs = SurahIndexLoader.load();
        } catch (Exception e) {
            setStatus("تعذّر تحميل فهرس السور: " + e.getMessage());
            allSurahs = List.of();
        }

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
            }
        });

        searchIndex = new SearchIndex(allSurahs);

        // تحديد آخر موضع قراءة تلقائيًا عند الإقلاع - نؤجّلها لبعد ما تظهر النافذة فعليًا،
        // لأن تحميل السورة يشمل تهيئة محرّك الصوت (MediaPlayer) وأول استخدام له على بعض
        // أجهزة ويندوز يمكن ياخذ وقت طويل (فحص مكتبات صوتية أصلية) - لو سويناها هنا مباشرة
        // قبل ظهور النافذة، البرنامج كله يتجمّد بدون أي مؤشر لحد ما تخلص
        if (!allSurahs.isEmpty()) {
            Platform.runLater(() -> {
                int lastSurahNumber = bookmarkStore.getLastSurah();
                Surah target = allSurahs.stream()
                        .filter(s -> s.getNumber() == lastSurahNumber)
                        .findFirst()
                        .orElse(allSurahs.get(0));
                surahListView.getSelectionModel().select(target);
            });
        }

        setStatus("جاهز — تم تحميل " + allSurahs.size() + " سورة من الفهرس.");
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
            SettingsStore.save(appSettings);
            readerPane.applySettings(appSettings);
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
        alert.setContentText("تطبيق لعرض صفحات/رسوم السور مع تلاوة صوتية متزامنة، تكرار للحفظ، تفسير، بحث، وعلامات مرجعية.\nتطوير: sim");
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
