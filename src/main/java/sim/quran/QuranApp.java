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
                Objects.requireNonNull(getClass().getResource("/sim/quran/css/app.css")).toExternalForm());
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

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
