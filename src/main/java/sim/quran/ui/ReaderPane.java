package sim.quran.ui;

import javafx.animation.AnimationTimer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
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
 *  - عنوان السورة، ثم نص الآيات داخل إطار مزخرف يحاكي شكل صفحة المصحف
 *    (بدون أي صورة فعلية - النص فقط، مع تظليل الآية الحالية أثناء التلاوة وفق start_ms).
 *  - أسفل الكل: شريط تحكم بالصوت (تشغيل/إيقاف/تكرار الآية الحالية/السرعة).
 *  - جانبي (اختياري): لوحة التفسير تظهر عند النقر على أي آية.
 *
 * الأداء: نص السورة يُعرض عبر ListView افتراضية (virtualized)، فلا تُرسم إلا الآيات
 * الظاهرة على الشاشة فعليًا. سابقًا كان النص كله يُبنى كـ TextFlow واحد (مئات عناصر Text)،
 * وتشكيل الخط العربي لكل الآيات دفعة واحدة كان يسبب تأخيرًا ملحوظًا عند تبديل السور
 * أو الانتقال لآية معينة في سورة طويلة.
 */
public class ReaderPane extends BorderPane {

    /** الآيات المعروضة فعليًا (التي لها نص) - هي عناصر القائمة الافتراضية. */
    private final ObservableList<AyahTiming> visibleAyahs = FXCollections.observableArrayList();
    private final ListView<AyahTiming> ayahList = new ListView<>(visibleAyahs);

    private final StackPane pageFrame = new StackPane();
    private final StackPane pageFrameWrapper = new StackPane(pageFrame);
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

    /** فهرس داخل currentData.getAyahs() (يشمل العناصر بلا نص) -> فهرس داخل visibleAyahs. */
    private final Map<Integer, Integer> dataIndexToVisibleIndex = new HashMap<>();
    /** تخزين مؤقت لبيانات السور المحمّلة سابقًا، لتفادي إعادة قراءة/تحليل JSON عند الرجوع لنفس السورة. */
    private final Map<Integer, SurahData> dataCache = new HashMap<>();
    private int currentAyahIndex = -1;      // فهرس بيانات الآية الحالية (currentData.getAyahs())
    private int currentVisibleIndex = -1;   // فهرس الآية الحالية داخل visibleAyahs

    private boolean userIsDraggingSlider = false;
    private Integer repeatRangeStart = null; // لتكرار آية واحدة: نبدأ من start_ms، ننتهي عند نهايتها
    private boolean trackingPaused = true; // البرنامج يبدأ دائمًا بوضع "إيقاف المتابعة" حتى يضغط المستخدم "استئناف" صراحةً
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

        // ---- قائمة الآيات الافتراضية ----
        ayahList.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        ayahList.getStyleClass().add("mushaf-list");
        ayahList.setCellFactory(this::buildAyahCell);
        ayahList.setOnMouseClicked(e -> {
            AyahTiming selected = ayahList.getSelectionModel().getSelectedItem();
            if (selected != null && selected.hasText()) {
                ayahList.getSelectionModel().clearSelection();
                onAyahClicked(selected.getAyahNumber());
            }
        });
        pageFrame.getChildren().setAll(ayahList);

        // ---- لوحة التفسير ----
        tafsirContent.setWrapText(true);
        tafsirContent.setPadding(new Insets(10));
        tafsirContent.setTextAlignment(TextAlignment.RIGHT);
        tafsirContent.setAlignment(Pos.CENTER_RIGHT);
        tafsirContent.setMaxWidth(Double.MAX_VALUE);
        tafsirPane = new TitledPane("التفسير", tafsirContent);
        tafsirPane.setExpanded(false);

        VBox centerBox = new VBox(10, surahTitleLabel, pageFrameWrapper, tafsirPane);
        centerBox.setPadding(new Insets(10));
        VBox.setVgrow(pageFrameWrapper, Priority.ALWAYS);

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

    // ==================== خلية الآية (عنصر القائمة الافتراضية) ====================

    /**
     * خلية واحدة = سطر آية واحد (النص + رقم الآية). لأن القائمة افتراضية، تُبنى هذه
     * الخلايا للآيات الظاهرة فقط ويُعاد استخدامها عند التمرير، فتبقى التكلفة ثابتة
     * مهما طال السورة.
     */
    private ListCell<AyahTiming> buildAyahCell(ListView<AyahTiming> lv) {
        return new ListCell<>() {
            private final TextFlow flow = new TextFlow();
            private final Text ayahText = new Text();
            private final Text numberText = new Text();

            {
                flow.setTextAlignment(TextAlignment.RIGHT);
                flow.setLineSpacing(6);
                flow.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
                ayahText.getStyleClass().add("ayah-text");
                numberText.getStyleClass().add("ayah-badge-number");
                flow.getChildren().addAll(ayahText, numberText);
                // نقيّد عرض النص بعرض القائمة حتى يتلفّط السطر الطويل داخل الخلية،
                // ونتيح للخلية حساب ارتفاعها (متغيّر) حسب طول الآية تلقائيًا.
                flow.prefWidthProperty().bind(lv.widthProperty().subtract(52));
                getStyleClass().add("ayah-cell");
                setGraphic(flow);
            }

            @Override
            protected void updateItem(AyahTiming item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || !item.hasText()) {
                    setGraphic(null);
                    return;
                }
                setGraphic(flow);
                ayahText.setText(item.getText() + " ");
                numberText.setText("(" + toArabicNumber(item.getAyahNumber()) + ") ");
                applyAyahTextStyle(ayahText, getIndex() == currentVisibleIndex);
                applyNumberStyle(numberText);
            }
        };
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
        prevBtn.setOnAction(e -> jumpToAyah(currentAyahIndex - 1, true));

        Button nextBtn = new Button("الآية التالية ▶");
        nextBtn.setOnAction(e -> jumpToAyah(currentAyahIndex + 1, true));

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
        loadSurah(surah, jumpToAyahNumber, true);
    }

    /** @param autoPlay مرّر false للاستعادة الصامتة عند إقلاع البرنامج (ينقل ويظلل بدون تشغيل الصوت تلقائيًا). */
    public void loadSurah(Surah surah, Integer jumpToAyahNumber, boolean autoPlay) {
        stopPlayback();
        this.currentSurah = surah;
        currentAyahIndex = -1;
        currentVisibleIndex = -1;
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
            currentData = loadDataCached(surah);
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

        // النص (داخل الإطار المزخرف) - ضمن قائمة افتراضية
        buildAyahList();

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
                    // نعيد القفز هنا كمان لضبط موضع الصوت نفسه بمجرد جاهزيته فعليًا
                    // (القفزة الأولى تحت ضمنت التظليل والتمرير فورًا بغض النظر عن الصوت)
                    jumpToAyahNumber(jumpToAyahNumber, autoPlay);
                }
            });
            player.setOnEndOfMedia(this::stopPlayback);
        } else {
            player = null;
            report("تنبيه: ملف الصوت غير موجود لهذه السورة (" + currentData.getAudioFileName() + ")");
        }

        // القفز للآية المطلوبة فورًا - يشتغل بغض النظر عن جاهزية الصوت أو توفره أصلاً
        if (jumpToAyahNumber != null) {
            jumpToAyahNumber(jumpToAyahNumber, autoPlay);
        }

        // لو التتبّع مجمَّد، ما نغيّر "آخر موضع" لمجرد فتح سورة للتصفح (بحث/تجول حرّ)
        if (!trackingPaused) {
            bookmarkStore.updateLastPosition(surah.getNumber(), jumpToAyahNumber != null ? jumpToAyahNumber : 1);
        }
        report("تم تحميل سورة " + currentData.getSurahName());
    }

    private SurahData loadDataCached(Surah surah) throws Exception {
        SurahData cached = dataCache.get(surah.getNumber());
        if (cached != null) return cached;
        SurahData data = SurahDataLoader.load(surah);
        dataCache.put(surah.getNumber(), data);
        return data;
    }

    /** يبني قائمة الآيات المعروضة (التي لها نص) وخريطة الربط بفهارس البيانات الأصلية. */
    private void buildAyahList() {
        dataIndexToVisibleIndex.clear();
        List<AyahTiming> visible = new ArrayList<>();
        List<AyahTiming> ayahs = currentData.getAyahs();

        for (int i = 0; i < ayahs.size(); i++) {
            AyahTiming a = ayahs.get(i);
            if (!a.hasText()) continue;
            dataIndexToVisibleIndex.put(i, visible.size());
            visible.add(a);
        }

        // setAll يطلق تحديثًا واحدًا للقائمة بدل حدث لكل آية (أسرع عند تبديل السور الطويلة)
        visibleAyahs.setAll(visible);
    }

    /** يطبّق تنسيق رقم الآية: في النمط الحديث يُترك لـ CSS (ayah-badge-number)، وفي الكلاسيكي inline. */
    private void applyNumberStyle(Text numberText) {
        boolean modern = settings.getDesignStyle() == AppSettings.DesignStyle.MODERN;
        if (!modern) {
            numberText.setStyle("-fx-font-family:'" + settings.getFontFamily() + "'; -fx-font-size:"
                    + (int) settings.getFontSize() + "px; -fx-fill:#1a1a1a; -fx-font-weight:bold;");
        } else {
            numberText.setStyle("");
        }
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
        if (dataIndex < 0) return;

        currentAyahIndex = dataIndex;
        Integer visible = dataIndexToVisibleIndex.get(dataIndex);
        currentVisibleIndex = visible != null ? visible : -1;

        // نحدّث الآيات الظاهرة فقط (بدل إعادة بناء كامل النص كما كان سابقًا)
        ayahList.refresh();

        if (currentVisibleIndex >= 0) {
            // نُمرّر فقط عندما تخرج الآية الحالية من مجال الرؤية، وبمقدار سطر واحد من السياق
            // فوقها - حتى لا تقفز الصفحة مع كل آية أثناء التلاوة المستمرة.
            if (!isVisible(currentVisibleIndex)) {
                ayahList.scrollTo(Math.max(0, currentVisibleIndex - 1));
            }

            // تسجيل مكان التوقف الحالي (للاستئناف) + تحديث مشوار الحفظ - فقط لو التتبّع
            // غير مجمَّد (لو المستخدم ضغط "إيقاف المتابعة" نتجاهل هذا التحديث بالكامل، حتى
            // يقدر يتجول بحرية بالبرنامج بدون ما يفقد مكان توقفه الحقيقي)
            if (!trackingPaused) {
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
    }

    /** هل خلية الفهرس المعطى ظاهرة حاليًا داخل مجال رؤية القائمة؟ */
    private boolean isVisible(int index) {
        for (Node node : ayahList.lookupAll(".list-cell")) {
            if (!(node instanceof ListCell<?> cell) || cell.getIndex() != index || cell.isEmpty()) continue;
            Bounds cellBounds = cell.localToScene(cell.getBoundsInLocal());
            Bounds listBounds = ayahList.localToScene(ayahList.getBoundsInLocal());
            return cellBounds.getMaxY() > listBounds.getMinY() && cellBounds.getMinY() < listBounds.getMaxY();
        }
        return false;
    }

    /** يعيد بناء الـ inline style الكامل للآية (خط + حجم + لون)، مضمون الظهور بغض النظر عن CSS
     *  في النمط الكلاسيكي - بالنمط "معاصر" نترك اللون لملف style.css نفسه (صنف ayah-text). */
    private void applyAyahTextStyle(Text node, boolean current) {
        boolean modern = settings.getDesignStyle() == AppSettings.DesignStyle.MODERN;
        String fontPart = "-fx-font-family:'" + settings.getFontFamily() + "'; -fx-font-size:"
                + (int) settings.getFontSize() + "px;";
        if (modern) {
            node.setStyle(fontPart + (current ? "-fx-font-weight:bold;-fx-underline:true;" : ""));
        } else {
            String color = current ? currentAyahColor() : ayahTextColor();
            String extra = current ? "-fx-font-weight:bold;-fx-underline:true;" : "";
            node.setStyle(fontPart + " -fx-fill:" + color + ";" + extra);
        }
        // صنف التظليل (يفيد النمط الحديث المعتمد على CSS) - نضيفه/نزيله حسب الحالة
        node.getStyleClass().remove("ayah-text-current");
        if (current) node.getStyleClass().add("ayah-text-current");
    }

    private String currentAyahColor() {
        switch (settings.getTheme()) {
            case LIGHT: return "#a3271f";
            case DARK: return "#ffcf6b";
            default: return "#8b1a1a";
        }
    }

    private void jumpToAyah(int dataIndex, boolean autoPlay) {
        if (currentData == null) return;
        List<AyahTiming> ayahs = currentData.getAyahs();
        if (dataIndex < 0 || dataIndex >= ayahs.size()) return;

        // ننقل العرض ونظلّل الآية فورًا، بغض النظر عن حالة مشغّل الصوت (جاهز/غير موجود أصلاً) -
        // هذا يضمن إن القفزة تشتغل حتى لو الصوت ما توفر بعد لهذه السورة
        highlightAyah(dataIndex);

        if (player != null) {
            player.seek(Duration.millis(ayahs.get(dataIndex).getStartMs()));
            if (autoPlay && player.getStatus() != MediaPlayer.Status.PLAYING) {
                player.play();
                playPauseBtn.setText("⏸ إيقاف مؤقت");
            }
        }
    }

    public void jumpToAyahNumber(int ayahNumber) {
        jumpToAyahNumber(ayahNumber, true);
    }

    /** @param autoPlay مرّر false للانتقال الصامت (تظليل + مزامنة الصوت بدون بدء التشغيل تلقائيًا). */
    public void jumpToAyahNumber(int ayahNumber, boolean autoPlay) {
        if (currentData == null) return;
        List<AyahTiming> ayahs = currentData.getAyahs();
        for (int i = 0; i < ayahs.size(); i++) {
            if (ayahs.get(i).getAyahNumber() == ayahNumber) {
                jumpToAyah(i, autoPlay);
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

    /**
     * يجمّد "آخر موضع" عند نقطة التوقف الحالية بالضبط، ويوقف أي تحديث تلقائي له لحد الاستئناف.
     * بعدها يقدر المستخدم يتجول بحرية بالبرنامج (يفتح أي سورة، يبحث...) بدون ما يفقد مكانه الحقيقي.
     */
    public void pauseTracking() {
        if (currentSurah != null && currentAyahIndex >= 0 && currentAyahIndex < currentData.getAyahs().size()) {
            AyahTiming ayah = currentData.getAyahs().get(currentAyahIndex);
            if (ayah.hasText()) {
                bookmarkStore.updateLastPosition(currentSurah.getNumber(), ayah.getAyahNumber());
                bookmarkStore.save(); // نحفظه فورًا على القرص، مو بس بالذاكرة، احتياطًا
            }
        }
        trackingPaused = true;
    }

    public void resumeTracking() {
        trackingPaused = false;
    }

    /** يضبط حالة التجميد مباشرة بدون أي أثر جانبي (بلا حفظ موضع) - يُستخدم عند بدء جلسة قارئ جديد. */
    public void resetTrackingPaused(boolean paused) {
        this.trackingPaused = paused;
    }

    public boolean isTrackingPaused() {
        return trackingPaused;
    }

    public Surah getCurrentSurah() {
        return currentSurah;
    }

    /** يطبّق الخط/الحجم/الثيم فورًا. يعيد رسم الآيات الظاهرة بتنسيق الخط الجديد. */
    public void applySettings(AppSettings newSettings) {
        this.settings = newSettings.copy();

        getStyleClass().removeIf(c -> c.startsWith("theme-"));
        getStyleClass().add(settings.getTheme().styleClass);
        applyThemeInlineStyles();

        if (currentData != null) {
            int previousAyahIndex = currentAyahIndex;
            currentVisibleIndex = -1;
            // إعادة رسم الخلايا الظاهرة فقط (بدل إعادة بناء النص كاملًا كما كان سابقًا)
            ayahList.refresh();
            if (previousAyahIndex >= 0) {
                highlightAyah(previousAyahIndex);
            }
        }
    }

    /**
     * تنسيق الثيم مباشرة كـ inline style على العناصر الأساسية (خلفية الجذر + إطار الصفحة المزخرف) -
     * فقط بالنمط الكلاسيكي. بالنمط "معاصر" نمسح أي inline سابق ونترك التلوين بالكامل لملف style.css.
     */
    private void applyThemeInlineStyles() {
        if (settings.getDesignStyle() == AppSettings.DesignStyle.MODERN) {
            setStyle("");
            pageFrame.setStyle("");
            return;
        }

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
            case LIGHT: return "#0F6A5E";
            case DARK: return "#5EEAD4";
            default: return "#0F6A5E";
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
