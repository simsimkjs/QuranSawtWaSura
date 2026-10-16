package sim.quran.ui;

import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import sim.quran.model.ProgressStats;

import java.util.Locale;

/** نافذة "مشواري" - إحصائيات تقدّم حفظ/قراءة المصحف كاملاً. */
public class ProgressDialog {

    public static void show(ProgressStats stats) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("مشواري مع القرآن الكريم");

        Label bigPercent = new Label(String.format(Locale.ROOT, "%.1f%%", stats.percentComplete()));
        bigPercent.setStyle("-fx-font-size:36px; -fx-font-weight:bold; -fx-text-fill:#9a6f00;");

        ProgressBar bar = new ProgressBar(stats.percentComplete() / 100.0);
        bar.setPrefWidth(360);
        bar.setStyle("-fx-accent:#9a6f00;");

        Label ayahCountLabel = new Label(
                stats.furthestCumulativeAyah + " من " + stats.totalAyahCount + " آية");

        Label juzLabel = new Label("الجزء الحالي: " + (stats.currentJuz == 0 ? "لم تبدأ بعد" : stats.currentJuz + " من 30"));
        juzLabel.getStyleClass().add("progress-stat-label");

        Label levelLabel = new Label("مستواك: " + stats.level.displayName);
        levelLabel.getStyleClass().add("progress-stat-label");

        String paceText = stats.dailyAveragePace <= 0.01
                ? "لا توجد بيانات كافية بعد - استمر بالقراءة ليُحسب معدّلك"
                : String.format(Locale.ROOT, "%.1f آية/يوم (متوسط آخر فترة نشطة)", stats.dailyAveragePace);
        Label paceLabel = new Label("معدّلك اليومي: " + paceText);
        paceLabel.getStyleClass().add("progress-stat-label");
        paceLabel.setWrapText(true);

        String remainingText = stats.estimatedDaysRemaining == null
                ? "غير محدد بعد"
                : stats.estimatedDaysRemaining + " يوم تقريبًا (بنفس معدّلك الحالي)";
        Label remainingLabel = new Label("الزمن المتوقع لإكمال المصحف: " + remainingText);
        remainingLabel.getStyleClass().add("progress-stat-label");
        remainingLabel.setWrapText(true);

        Label goalLabel = new Label("🎯 الهدف: إكمال حفظ/قراءة المصحف الشريف كاملاً");
        goalLabel.setStyle("-fx-font-weight:bold; -fx-text-fill:#1a3d5c;");

        Button closeBtn = new Button("إغلاق");
        closeBtn.setOnAction(e -> stage.close());

        VBox root = new VBox(14,
                bigPercent, bar, ayahCountLabel,
                new Separator(),
                juzLabel, levelLabel, paceLabel, remainingLabel,
                new Separator(),
                goalLabel,
                closeBtn
        );
        root.setPadding(new Insets(24));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        root.setStyle("-fx-alignment:center; -fx-background-color:#fdf8ec;");

        Scene scene = new Scene(root, 420, 460);
        DesignStyles.applyTo(scene);
        stage.setScene(scene);
        stage.show();
    }
}
