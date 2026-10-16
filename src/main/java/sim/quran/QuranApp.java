package sim.quran;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.NodeOrientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import sim.quran.data.EmbeddedFonts;

import java.io.IOException;
import java.util.Objects;

public class QuranApp extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        EmbeddedFonts.loadAll(); // تحميل الخطوط المرفقة قبل بناء أي واجهة تستخدمها

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/sim/quran/fxml/main.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/sim/quran/css/style.css")).toExternalForm());
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        // حفظ "آخر موضع" عند إغلاق النافذة بأي طريقة (زر ✕ في الأعلى، وليس فقط قائمة ملف→خروج)،
        // وإلا خسر المستخدم آخر تحديثات bookmarks.json منذ آخر حفظ صريح
        sim.quran.ui.MainController controller = loader.getController();
        if (controller != null) {
            stage.setOnCloseRequest(e -> controller.persistOnClose());
        }

        stage.setTitle("القرآن الكريم صوت وصورة");
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
