package sim.quran.ui;

import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import sim.quran.model.MushafDivisions;
import sim.quran.model.Surah;

import java.util.List;
import java.util.function.BiConsumer;

/** نافذة تصفّح القرآن حسب الأجزاء (30) أو الأحزاب (60) أو أرباع الأحزاب (240)، بدل السور فقط. */
public class NavigationDialog {

    private enum Mode { JUZ, HIZB, QUARTER }

    /** @param onGoTo يُستدعى بالسورة ورقم الآية عند اختيار وحدة (جزء/حزب/ربع) للانتقال إليها. */
    public static void show(List<Surah> allSurahs, BiConsumer<Surah, Integer> onGoTo) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("تصفّح حسب الأجزاء والأحزاب والأرباع");

        ComboBox<Mode> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll(Mode.JUZ, Mode.HIZB, Mode.QUARTER);
        modeCombo.setCellFactory(lv -> modeCell());
        modeCombo.setButtonCell(modeCell());
        modeCombo.setValue(Mode.JUZ);
        modeCombo.setPrefWidth(220);

        ListView<MushafDivisions.Division> list = new ListView<>();
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(MushafDivisions.Division item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String surahName = allSurahs.stream()
                        .filter(s -> s.getNumber() == item.surahNumber())
                        .findFirst()
                        .map(Surah::getName)
                        .orElse("سورة " + item.surahNumber());
                setText(item.label() + "  —  يبدأ من سورة " + surahName + " آية " + item.ayahNumber());
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2) return;
            MushafDivisions.Division selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            allSurahs.stream()
                    .filter(s -> s.getNumber() == selected.surahNumber())
                    .findFirst()
                    .ifPresent(surah -> {
                        onGoTo.accept(surah, selected.ayahNumber());
                        stage.close();
                    });
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Runnable refresh = () -> {
            List<MushafDivisions.Division> items = switch (modeCombo.getValue()) {
                case JUZ -> MushafDivisions.listJuz();
                case HIZB -> MushafDivisions.listHizb();
                case QUARTER -> MushafDivisions.listQuarters();
            };
            list.getItems().setAll(items);
        };
        modeCombo.valueProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();

        Label hint = new Label("انقر نقرًا مزدوجًا على أي وحدة للانتقال إليها مباشرة.");
        hint.setStyle("-fx-font-size:12px; -fx-text-fill:#666;");

        VBox root = new VBox(12, new Label("تصفّح حسب:"), modeCombo, list, hint);
        root.setPadding(new Insets(16));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        Scene scene = new Scene(root, 460, 520);
        DesignStyles.applyTo(scene);
        stage.setScene(scene);
        stage.show();
    }

    private static ListCell<Mode> modeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Mode item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(switch (item) {
                    case JUZ -> "الأجزاء (30)";
                    case HIZB -> "الأحزاب (60)";
                    case QUARTER -> "أرباع الأحزاب (240)";
                });
            }
        };
    }
}
