package sim.quran.ui;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import sim.quran.data.BookmarkStore;
import sim.quran.data.DataPaths;
import sim.quran.data.MemorizationStore;
import sim.quran.data.SurahDataLoader;
import sim.quran.data.TafsirLoader;
import sim.quran.model.AppSettings;
import sim.quran.model.AyahTiming;
import sim.quran.model.Surah;
import sim.quran.model.SurahData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * لوحة القراءة الرئيسية لسورة واحدة:
 *  - عنوان السورة، ثم نص الآيات كاملاً داخل إطار مزخرف يحاكي شكل صفحة المصحف
 *    (بدون أي صورة فعلية - النص فقط، مع تظليل الآية الحالية أثناء التلاوة وفق start_ms).
 *  - أسفل الكل: شريط تحكم بالصوت (تشغيل/إيقاف/تكرار الآية الحالية/السرعة).
 *  - جانبي (اختياري): لوحة التفسير تظهر عند النقر على أي آية.
 */
public class ReaderPane extends BorderPane {

    private final TextFlow textFlow = new TextFlow();
    private final StackPane pageFrame = new StackPane(textFlow);
    private final StackPane pageFrameWrapper = new StackPane(pageFrame);
    private final ScrollPane textScroll = new ScrollPane(pageFrameWrapper);
    private final Label surahTitleLabel = new Label();

    private final Slider progressSlider = new Slider();
    private final Label timeLabel = new Label("00:00 / 00:00");
    private final Button playPauseBtn = new Button("▶ تشغيل");
    private final ToggleButton repeatAyahBtn = new ToggleButton("تكرار الآية");
    private final ComboBox<Double> speedCombo = new ComboBox<>();

    private final TitledPane tafsirPane;
    private final Label tafsirContent = new Label();
    private final TafsirLoader tafsirLoader = new TafsirLoader();

    private MediaPlayer player;
    private SurahData currentData;
    private Surah currentSurah;
    private final List<Text> ayahTextNodes = new ArrayList<>();
    private final Map<Integer, Integer> dataIndexToVisibleIndex = new HashMap<>();
    private int currentAyahIndex = -1;

    private boolean userIsDraggingSlider = false;
    private Integer repeatRangeStart = null; // لتكرار آية واحدة: نبدأ من start_ms، ننتهي عند نهايتها
    private AppSettings settings = AppSettings.defaults();

    private BookmarkStore bookmarkStore;
    private MemorizationStore memorizationStore;
    private final Consumer<String> statusReporter;
    private final BiConsumer<Integer, Boolean> onJuzCompleted; // (رقم الجزء، هل هو ختمة كاملة = جزء 30 اكتمل ضمنه المصحف)
    private List<Surah> allSurahsForProgress = List.of();
    private final AnimationTimer syncTimer;

    public ReaderPane(BookmarkStore bookmarkStore, MemorizationStore memorizationStore,
                       Consumer<String> statusReporter, BiConsumer<Integer, Boolean> onJuzCompleted) {
        this.bookmarkStore = bookmarkStore;
        this.memorizationStore = memorizationStore;
        this.statusReporter = statusReporter;
        this.onJuzCompleted = onJuzCompleted;

        setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        getStyleClass().add(settings.getTheme().styleClass);

        // ---- عنوان السورة ----
        surahTitleLabel.getStyleClass().add("surah-title-label");

        // ---- إطار الصفحة المزخرف (يحاكي شكل صفحة المصحف بدون أي صورة فعلية) ----
        pageFrame.getStyleClass().add("mushaf-frame");
        pageFrame.setAlignment(Pos.TOP_CENTER);

        // هامش صغير فقط حتى تبقى الحدود الذهبية مرئية، والباقي كله للإطار (يملأ عرض الصفحة بالكامل)
        pageFrameWrapper.setPadding(new Insets(10));
        pageFrameWrapper.setAlignment(Pos.TOP_CENTER);

        applyThemeInlineStyles(); // تنسيق مباشر مضمون الظهور، لا يعتمد فقط على تحميل CSS

        textFlow.setLineSpacing(14);
        textFlow.setPadding(new Insets(28));
        textFlow.setTextAlignment(TextAlignment.RIGHT);
        textFlow.getStyleClass().add("mushaf-text-flow");
        textFlow.setMaxWidth(Double.MAX_VALUE);
        // نربط عرض النص بعرض إطار الصفحة صراحة، وإلا TextFlow ياخذ عرض محتواه فقط
        // فيصير الإطار المزخرف أوسع منه ويظهر النص كأنه بالنص بدل ملاصق لليمين
        textFlow.prefWidthProperty().bind(textScroll.widthProperty().subtract(80));

        textScroll.setFitToWidth(true);
        textScroll.getStyleClass().add("mushaf-scroll");

        // ---- لوحة التفسير ----
        tafsirContent.setWrapText(true);
        tafsirContent.setPadding(new Insets(10));
        tafsirContent.setTextAlignment(TextAlignment.RIGHT);
        tafsirContent.setAlignment(Pos.CENTER_RIGHT);
        tafsirContent.setMaxWidth(Double.MAX_VALUE);
        tafsirPane = new TitledPane("التفسير", tafsirContent);
        tafsirPane.setExpanded(false);

        VBox centerBox = new VBox(10, surahTitleLabel, textScroll, tafsirPane);
        centerBox.setPadding(new Insets(10));
        VBox.setVgrow(textScroll, Priority.ALWAYS);

        setCenter(centerBox);
        setBottom(buildPlayerBar());

        syncTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                syncWithPlayback();
            }
        };
        // يُشغَّل مرة واحدة فقط طوال عمر البرنامج - syncWithPlayback نفسها ترجع فورًا
        // لو ما فيه تشغيل حالي، فلا داعي لإيقافه/تشغيله مع كل تبديل سورة (كان هذا يسبب
        // تسجيل الحلقة أكثر من مرة مع كل تبديل ويصير البرنامج أبطأ تدريجيًا)
        syncTimer.start();
    }

    // ==================== شريط التشغيل ====================

    private Node buildPlayerBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("player-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));

        playPauseBtn.getStyleClass().add("gold-button");
        playPauseBtn.setOnAction(e -> togglePlayPause());

        Button stopBtn = new Button("■ إيقاف");
        stopBtn.setOnAction(e -> stopPlayback());

        Button prevBtn = new Button("◀ الآية السابقة");
        prevBtn.setOnAction(e -> jumpToAyah(currentAyahIndex - 1));

        Button nextBtn = new Button("الآية التالية ▶");
        nextBtn.setOnAction(e -> jumpToAyah(currentAyahIndex + 1));

        repeatAyahBtn.setOnAction(e -> {
            if (repeatAyahBtn.isSelected() && currentAyahIndex >= 0) {
                repeatRangeStart = currentAyahIndex;
            } else {
                repeatRangeStart = null;
            }
        });

        speedCombo.getItems().addAll(0.75, 1.0, 1.25, 1.5);
        speedCombo.setValue(1.0);
        speedCombo.setOnAction(e -> {
            if (player != null) player.setRate(speedCombo.getValue());
        });

        Button bookmarkBtn = new Button("📌 حفظ علامة");
        bookmarkBtn.setOnAction(e -> saveBookmarkAtCurrentAyah());

        progressSlider.setMin(0);
        progressSlider.setMax(100);
        progressSlider.setPrefWidth(260);
        progressSlider.setOnMousePressed(e -> userIsDraggingSlider = true);
        progressSlider.setOnMouseReleased(e -> {
            userIsDraggingSlider = false;
            if (player != null) {
                double fraction = progressSlider.getValue() / 100.0;
                player.seek(player.getTotalDuration().multiply(fraction));
            }
        });

        HBox.setHgrow(progressSlider, Priority.ALWAYS);

        bar.getChildren().addAll(
                playPauseBtn, stopBtn, prevBtn, nextBtn,
                new Separator(Orientation.VERTICAL),
                progressSlider, timeLabel,
                new Separator(Orientation.VERTICAL),
                new Label("السرعة:"), speedCombo,
                repeatAyahBtn, bookmarkBtn
        );
        return bar;
    }

    // ==================== تحميل سورة ====================

    public void loadSurah(Surah surah) {
        loadSurah(surah, null);
    }

    /** يحمّل السورة، وإن أُعطي رقم آية، يقفز إليها ويشغّل الصوت بمجرد جاهزيته. */
    public void loadSurah(Surah surah, Integer jumpToAyahNumber) {
        stopPlayback();
        this.currentSurah = surah;
        currentAyahIndex = -1;
        repeatRangeStart = null;
        repeatAyahBtn.setSelected(false);

        // تحرير مشغّل الصوت السابق قبل إنشاء واحد جديد - وإلا تتراكم موارد صوتية native
        // مع كل تبديل سورة ويصير البرنامج أبطأ تدريجيًا
        if (player != null) {
            player.stop();
            player.dispose();
            player = null;
        }

        try {
            currentData = SurahDataLoader.load(surah);
        } catch (Exception ex) {
            ex.printStackTrace();
            report("خطأ أثناء تحميل بيانات السورة: " + ex.getMessage());
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
            alert.setTitle("تعذر تحميل السورة");
            alert.setHeaderText("سورة " + surah.getName() + " (رقم " + surah.getNumber() + ")");
            alert.setContentText(ex.getMessage());
            alert.showAndWait();
            return;
        }

        surahTitleLabel.setText("سورة " + currentData.getSurahName());

        // النص (داخل الإطار المزخرف)
        buildAyahText();

        // التفسير (إن وُجد)
        boolean tafsirOk = tafsirLoader.loadFor(surah.getNumber());
        tafsirPane.setText(tafsirOk ? "التفسير (" + tafsirLoader.getSource() + ")" : "التفسير (غير متاح لهذه السورة بعد)");
        tafsirContent.setText("اختر آية لعرض تفسيرها.");

        // الصوت
        java.io.File audioFile = DataPaths.audioFile(currentData.getAudioFileName());
        if (audioFile.exists()) {
            Media media = new Media(audioFile.toURI().toString());
            player = new MediaPlayer(media);
            player.setOnReady(() -> {
                progressSlider.setMax(player.getTotalDuration().toMillis());
                updateTimeLabel(0, player.getTotalDuration().toMillis());
                if (jumpToAyahNumber != null) {
                    jumpToAyahNumber(jumpToAyahNumber);
                }
            });
            player.setOnEndOfMedia(this::stopPlayback);
        } else {
            player = null;
            report("تنبيه: ملف الصوت غير موجود لهذه السورة (" + currentData.getAudioFileName() + ")");
        }

        bookmarkStore.updateLastPosition(surah.getNumber(), jumpToAyahNumber != null ? jumpToAyahNumber : 1);
        report("تم تحميل سورة " + currentData.getSurahName());
    }

    private void buildAyahText() {
        textFlow.getChildren().clear();
        ayahTextNodes.clear();
        dataIndexToVisibleIndex.clear();

        List<AyahTiming> ayahs = currentData.getAyahs();
        for (int i = 0; i < ayahs.size(); i++) {
            AyahTiming a = ayahs.get(i);
            if (!a.hasText()) continue;

            final int ayahNumber = a.getAyahNumber();

            Text ayahText = new Text(a.getText() + " ");
            ayahText.getStyleClass().add("ayah-text");
            applyAyahTextStyle(ayahText, false);
            ayahText.setOnMouseClicked(e -> onAyahClicked(ayahNumber));

            Node badge = createAyahBadge(ayahNumber);

            textFlow.getChildren().addAll(ayahText, badge, new Text("  "));
            dataIndexToVisibleIndex.put(i, ayahTextNodes.size());
            ayahTextNodes.add(ayahText);
        }
    }

    /** دائرة زخرفية صغيرة برقم الآية (تحاكي علامة نهاية الآية في المصحف)، تُدرَج داخل سطر النص مباشرة. */
    private Node createAyahBadge(int ayahNumber) {
        Circle circle = new Circle(14);
        circle.getStyleClass().add("ayah-badge-circle");
        circle.setStyle("-fx-fill:#fdf3da; -fx-stroke:#9a6f00; -fx-stroke-width:1.5;");

        Text num = new Text(toArabicNumber(ayahNumber));
        num.getStyleClass().add("ayah-badge-number");
        num.setStyle("-fx-font-family:'Amiri',serif; -fx-font-size:13px; -fx-fill:#1a3d5c; -fx-font-weight:bold;");

        StackPane badge = new StackPane(circle, num);
        badge.setOnMouseClicked(e -> onAyahClicked(ayahNumber));
        return badge;
    }

    private void onAyahClicked(int ayahNumber) {
        tafsirPane.setExpanded(true);
        tafsirContent.setText(tafsirLoader.isAvailable()
                ? tafsirLoader.getTafsirFor(ayahNumber)
                : "لا يتوفر تفسير محمّل لهذه السورة حتى الآن.");
        jumpToAyahNumber(ayahNumber);
    }

    // ==================== التشغيل والمزامنة ====================

    private void togglePlayPause() {
        if (player == null) {
            report("لا يوجد ملف صوت محمّل لهذه السورة.");
            return;
        }
        MediaPlayer.Status status = player.getStatus();
        if (status == MediaPlayer.Status.PLAYING) {
            player.pause();
            playPauseBtn.setText("▶ تشغيل");
        } else {
            player.play();
            playPauseBtn.setText("⏸ إيقاف مؤقت");
        }
    }

    private void stopPlayback() {
        if (player != null) {
            player.stop();
        }
        playPauseBtn.setText("▶ تشغيل");
    }

    /** يُستدعى كل إطار: يحدّث الشريط الزمني، ويحدّد الآية الحالية، ويطبّق منطق تكرار الآية إن كان مفعّلاً */
    private void syncWithPlayback() {
        if (player == null || currentData == null) return;
        if (player.getStatus() != MediaPlayer.Status.PLAYING) return;

        long ms = (long) player.getCurrentTime().toMillis();

        if (!userIsDraggingSlider) {
            progressSlider.setValue(ms);
            updateTimeLabel(ms, player.getTotalDuration().toMillis());
        }

        // منطق تكرار الآية الحالية
        if (repeatRangeStart != null) {
            long endMs = currentData.endMsOf(repeatRangeStart);
            if (ms >= endMs) {
                player.seek(Duration.millis(currentData.getAyahs().get(repeatRangeStart).getStartMs()));
                return;
            }
        }

        int idx = currentData.findAyahIndexForTime(ms);
        if (idx != currentAyahIndex) {
            highlightAyah(idx);
        }
    }

    private void highlightAyah(int dataIndex) {
        // dataIndex هو فهرس داخل currentData.getAyahs() (يشمل عناصر بلا نص)
        // نحوّله لفهرس داخل ayahTextNodes (التي تستثني العناصر بلا نص) عبر الخريطة المبنية مسبقًا
        if (currentAyahIndex >= 0) {
            Integer prevVisible = dataIndexToVisibleIndex.get(currentAyahIndex);
            if (prevVisible != null) {
                Text prevNode = ayahTextNodes.get(prevVisible);
                prevNode.getStyleClass().remove("ayah-text-current");
                applyAyahTextStyle(prevNode, false);
            }
        }
        currentAyahIndex = dataIndex;
        Integer visible = dataIndexToVisibleIndex.get(dataIndex);
        if (visible != null) {
            Text node = ayahTextNodes.get(visible);
            if (!node.getStyleClass().contains("ayah-text-current")) {
                node.getStyleClass().add("ayah-text-current");
            }
            applyAyahTextStyle(node, true);
            scrollToNode(node);

            // تسجيل مكان التوقف الحالي (للاستئناف) + تحديث مشوار الحفظ (لو التتبّع مفعّل وفيه تقدّم فعلي جديد)
            AyahTiming ayah = currentData.getAyahs().get(dataIndex);
            if (ayah.hasText() && currentSurah != null) {
                bookmarkStore.updateLastPosition(currentSurah.getNumber(), ayah.getAyahNumber());
                if (settings.isTrackProgress() && !allSurahsForProgress.isEmpty()) {
                    Integer completedJuz = memorizationStore.recordProgress(
                            currentSurah.getNumber(), ayah.getAyahNumber(), allSurahsForProgress);
                    if (completedJuz != null && onJuzCompleted != null) {
                        onJuzCompleted.accept(completedJuz, completedJuz == 30);
                    }
                }
            }
        }
    }

    /** يعيد بناء الـ inline style الكامل للآية (خط + حجم + لون)، مضمون الظهور بغض النظر عن CSS. */
    private void applyAyahTextStyle(Text node, boolean current) {
        String color = current ? currentAyahColor() : ayahTextColor();
        String extra = current ? "-fx-font-weight:bold;-fx-underline:true;" : "";
        node.setStyle("-fx-font-family:'" + settings.getFontFamily() + "'; -fx-font-size:"
                + (int) settings.getFontSize() + "px; -fx-fill:" + color + ";" + extra);
    }

    private String currentAyahColor() {
        switch (settings.getTheme()) {
            case LIGHT: return "#a3271f";
            case DARK: return "#ffcf6b";
            default: return "#8b1a1a";
        }
    }

    private void scrollToNode(Text node) {
        // تمرير تقريبي يبقي الآية المُظلَّلة ضمن مجال الرؤية
        double totalHeight = textFlow.getHeight();
        if (totalHeight <= 0) return;
        double nodeY = node.getBoundsInParent().getMinY();
        double fraction = nodeY / totalHeight;
        textScroll.setVvalue(Math.max(0, Math.min(1, fraction)));
    }

    private void jumpToAyah(int dataIndex) {
        if (currentData == null || player == null) return;
        List<AyahTiming> ayahs = currentData.getAyahs();
        if (dataIndex < 0 || dataIndex >= ayahs.size()) return;
        player.seek(Duration.millis(ayahs.get(dataIndex).getStartMs()));
        if (player.getStatus() != MediaPlayer.Status.PLAYING) {
            player.play();
            playPauseBtn.setText("⏸ إيقاف مؤقت");
        }
    }

    public void jumpToAyahNumber(int ayahNumber) {
        if (currentData == null) return;
        List<AyahTiming> ayahs = currentData.getAyahs();
        for (int i = 0; i < ayahs.size(); i++) {
            if (ayahs.get(i).getAyahNumber() == ayahNumber) {
                jumpToAyah(i);
                return;
            }
        }
    }

    private void saveBookmarkAtCurrentAyah() {
        if (currentSurah == null || currentData == null) return;
        int ayahNumber = 1;
        if (currentAyahIndex >= 0 && currentAyahIndex < currentData.getAyahs().size()) {
            ayahNumber = currentData.getAyahs().get(currentAyahIndex).getAyahNumber();
        }
        bookmarkStore.addBookmark(
                "سورة " + currentData.getSurahName() + " - آية " + ayahNumber,
                currentSurah.getNumber(), ayahNumber);
        bookmarkStore.updateLastPosition(currentSurah.getNumber(), ayahNumber);
        bookmarkStore.save();
        report("تم حفظ العلامة المرجعية.");
    }

    /** يجب استدعاؤها مرة واحدة بعد تحميل فهرس السور، حتى يقدر يحسب الموضع التراكمي لتتبّع التقدّم. */
    public void setAllSurahs(List<Surah> surahs) {
        this.allSurahsForProgress = surahs;
    }

    /** يبدّل مخازن البيانات (عند تبديل القارئ النشط) بدون الحاجة لإعادة بناء لوحة القراءة كاملة. */
    public void switchStores(BookmarkStore newBookmarkStore, MemorizationStore newMemorizationStore) {
        this.bookmarkStore = newBookmarkStore;
        this.memorizationStore = newMemorizationStore;
    }

    public Surah getCurrentSurah() {
        return currentSurah;
    }

    /** يطبّق الخط/الحجم/الثيم فورًا. يعاد بناء نص السورة الحالية (إن وُجدت) لتطبيق الخط الجديد. */
    public void applySettings(AppSettings newSettings) {
        this.settings = newSettings.copy();

        getStyleClass().removeIf(c -> c.startsWith("theme-"));
        getStyleClass().add(settings.getTheme().styleClass);
        applyThemeInlineStyles();

        if (currentData != null) {
            int previousAyahIndex = currentAyahIndex;
            buildAyahText();
            if (previousAyahIndex >= 0) {
                currentAyahIndex = -1; // نجبر highlightAyah على إعادة التطبيق حتى لو نفس الفهرس
                highlightAyah(previousAyahIndex);
            }
        }
    }

    /**
     * تنسيق الثيم مباشرة كـ inline style على العناصر الأساسية (خلفية الجذر + إطار الصفحة المزخرف).
     * هذا احتياطي مضمون الظهور دائمًا، حتى لو تأخر تحميل app.css أو حصل أي تعارض بترتيب الأنماط.
     */
    private void applyThemeInlineStyles() {
        String rootBg;
        String frameStyle;
        switch (settings.getTheme()) {
            case LIGHT:
                rootBg = "#ffffff";
                frameStyle = "-fx-background-color:#ffffff;"
                        + "-fx-border-color:#c9c9c9,#e6e6e6;-fx-border-width:3,1;-fx-border-insets:0,6;"
                        + "-fx-border-radius:4,2;-fx-background-radius:4;"
                        + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.12), 8, 0.1, 0, 2);-fx-padding:10;";
                break;
            case DARK:
                rootBg = "#14202b";
                frameStyle = "-fx-background-color:linear-gradient(to bottom, #1c2b3a, #16222d);"
                        + "-fx-border-color:#d9b45c,#8a6a24;-fx-border-width:3,1;-fx-border-insets:0,6;"
                        + "-fx-border-radius:4,2;-fx-background-radius:4;"
                        + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.5), 14, 0.15, 0, 3);-fx-padding:10;";
                break;
            default: // كلاسيكي
                rootBg = "#f4f6f9";
                frameStyle = "-fx-background-color:linear-gradient(to bottom, #fdf8ec, #f7edd2);"
                        + "-fx-border-color:#9a6f00,#d9b45c;-fx-border-width:3,1;-fx-border-insets:0,6;"
                        + "-fx-border-radius:4,2;-fx-background-radius:4;"
                        + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.25), 12, 0.15, 0, 3);-fx-padding:10;";
        }
        setStyle("-fx-background-color:" + rootBg + ";");
        pageFrame.setStyle(frameStyle);
    }

    private String ayahTextColor() {
        switch (settings.getTheme()) {
            case LIGHT: return "#1a1a1a";
            case DARK: return "#f0e6c8";
            default: return "#241a08";
        }
    }

    // ==================== أدوات مساعدة ====================

    private void updateTimeLabel(double currentMs, double totalMs) {
        timeLabel.setText(formatTime(currentMs) + " / " + formatTime(totalMs));
    }

    private String formatTime(double ms) {
        long totalSeconds = (long) (ms / 1000);
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    private static final String[] ARABIC_DIGITS = {"٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩"};

    private String toArabicNumber(int n) {
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(n).toCharArray()) {
            sb.append(ARABIC_DIGITS[c - '0']);
        }
        return sb.toString();
    }

    private void report(String msg) {
        if (statusReporter != null) statusReporter.accept(msg);
    }
}
