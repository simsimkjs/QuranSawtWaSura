package sim.quran.ui;

import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import sim.quran.model.AppSettings;

import java.util.function.Consumer;

/**
 * نافذة إعدادات (خط / حجم خط / ثيم) مع معاينة حية فورية.
 * الحفظ فقط عند الضغط على "حفظ" - الإلغاء ما يغيّر شيء بالإعدادات الفعلية المطبَّقة.
 */
public class SettingsDialog {

    private static final String SAMPLE_TEXT =
            "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ ﴿١﴾ ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ ﴿٢﴾";

    /** الخطوط المتاحة للاختيار - محصورة عمدًا بخطوط عربية مناسبة للنص القرآني فقط (وليس كل خطوط النظام).
     *  "KFGQPC Uthmanic Script HAFS" خط عثماني أصيل مضمّن داخل التطبيق نفسه (EmbeddedFonts)،
     *  فيظهر دائمًا بغض النظر عن الخطوط المثبَّتة فعليًا على جهاز المستخدم. */
    private static final String[] CURATED_FONTS =
            {"KFGQPC Uthmanic Script HAFS", "Amiri", "Traditional Arabic", "Sakkal Majalla"};

    public static void show(AppSettings currentSettings, Consumer<AppSettings> onSave) {
        AppSettings draft = currentSettings.copy();

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("الإعدادات");

        // ---- عناصر التحكم ----
        java.util.List<String> installedFamilies = Font.getFamilies();
        ComboBox<String> fontCombo = new ComboBox<>();
        for (String candidate : CURATED_FONTS) {
            if (installedFamilies.contains(candidate)) {
                fontCombo.getItems().add(candidate);
            }
        }
        if (fontCombo.getItems().isEmpty()) {
            // احتياط: لو ولا خط من القائمة المفضّلة مثبّت على الجهاز، لا نترك القائمة فارغة
            fontCombo.getItems().add(Font.getDefault().getFamily());
        }
        fontCombo.setValue(fontCombo.getItems().contains(draft.getFontFamily())
                ? draft.getFontFamily() : fontCombo.getItems().get(0));
        fontCombo.setEditable(false);
        fontCombo.setPrefWidth(260);

        Slider sizeSlider = new Slider(16, 50, draft.getFontSize());
        sizeSlider.setShowTickLabels(true);
        sizeSlider.setShowTickMarks(true);
        sizeSlider.setMajorTickUnit(6);
        sizeSlider.setBlockIncrement(1);
        Label sizeValueLabel = new Label(String.valueOf((int) draft.getFontSize()));

        ToggleGroup themeGroup = new ToggleGroup();
        HBox themeBox = new HBox(14);
        for (AppSettings.Theme t : AppSettings.Theme.values()) {
            RadioButton rb = new RadioButton(t.displayName);
            rb.setUserData(t);
            rb.setToggleGroup(themeGroup);
            if (t == draft.getTheme()) rb.setSelected(true);
            themeBox.getChildren().add(rb);
        }

        // ---- المعاينة الحية ----
        Text previewText = new Text(SAMPLE_TEXT);
        previewText.setTextAlignment(TextAlignment.RIGHT);
        previewText.getStyleClass().add("ayah-text");
        StackPane previewFrame = new StackPane(previewText);
        previewFrame.getStyleClass().add("mushaf-frame");
        previewFrame.setPadding(new Insets(24));
        previewFrame.setPrefHeight(140);
        StackPane previewWrapper = new StackPane(previewFrame);
        previewWrapper.getStyleClass().addAll(draft.getTheme().styleClass);
        previewWrapper.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        Runnable applyPreview = () -> {
            previewText.setStyle("-fx-font-family:'" + fontCombo.getValue() + "'; -fx-font-size:"
                    + (int) sizeSlider.getValue() + "px;");
            AppSettings.Theme selected = (AppSettings.Theme) themeGroup.getSelectedToggle().getUserData();
            previewWrapper.getStyleClass().removeIf(c -> c.startsWith("theme-"));
            previewWrapper.getStyleClass().add(selected.styleClass);
        };

        fontCombo.valueProperty().addListener((o, a, b) -> applyPreview.run());
        sizeSlider.valueProperty().addListener((o, a, b) -> {
            sizeValueLabel.setText(String.valueOf((int) sizeSlider.getValue()));
            applyPreview.run();
        });
        themeGroup.selectedToggleProperty().addListener((o, a, b) -> applyPreview.run());
        applyPreview.run();

        // ---- الأزرار ----
        Button saveBtn = new Button("💾 حفظ");
        saveBtn.getStyleClass().add("gold-button");
        saveBtn.setOnAction(e -> {
            draft.setFontFamily(fontCombo.getValue());
            draft.setFontSize(sizeSlider.getValue());
            draft.setTheme((AppSettings.Theme) themeGroup.getSelectedToggle().getUserData());
            onSave.accept(draft);
            stage.close();
        });

        Button cancelBtn = new Button("إلغاء");
        cancelBtn.setOnAction(e -> stage.close());

        HBox buttonsBar = new HBox(10, saveBtn, cancelBtn);
        buttonsBar.setAlignment(Pos.CENTER_LEFT);

        // ---- التخطيط ----
        VBox root = new VBox(16,
                new Label("الخط:"), fontCombo,
                new Label("حجم الخط:"), new HBox(10, sizeSlider, sizeValueLabel),
                new Label("الثيم:"), themeBox,
                new Separator(),
                new Label("معاينة:"), previewWrapper,
                buttonsBar
        );
        root.setPadding(new Insets(16));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        HBox.setHgrow(sizeSlider, javafx.scene.layout.Priority.ALWAYS);

        Scene scene = new Scene(root, 480, 560);
        scene.getStylesheets().add(SettingsDialog.class.getResource("/sim/quran/css/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }
}
