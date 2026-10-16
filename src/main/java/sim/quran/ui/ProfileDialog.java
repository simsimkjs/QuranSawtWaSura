package sim.quran.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import sim.quran.data.ProfileStore;
import sim.quran.model.Surah;
import sim.quran.model.UserProfile;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static javafx.scene.control.TableView.*;

/**
 * نافذة إدارة القراء: جدول يعرض لكل قارئ اسمه ومكان توقفه بالضبط (جزء/سورة/آية)،
 * مع زر "متابعة" بكل صف يبدّل له القارئ وينقل مباشرة لموضعه (بكلمة مرور لو محدَّدة).
 */
public class ProfileDialog {

    /**
     * @param realProfiles قائمة القراء الحقيقيين (بدون الضيف - يُضاف تلقائيًا بالأعلى)
     * @param allSurahs    فهرس السور الكامل (غير مستخدم مباشرة هنا، محتفظ فيه للتوافق)
     * @param onSwitch     يُستدعى بعد نجاح التحقق (أو مباشرة للضيف/بلا كلمة مرور) بالقارئ الجديد
     * @param onAdd        يُستدعى عند إضافة قارئ جديد: (الاسم، كلمة المرور أو null)
     * @param onDelete     يُستدعى عند حذف قارئ (غير الضيف)
     * @param onSetPassword يُستدعى عند تعيين/تغيير/حذف كلمة مرور قارئ: (القارئ، كلمة المرور الجديدة أو null للحذف)
     * @param describePosition يرجع نص "مكان التوقف" لقارئ معيّن (محسوب بمعرفة المتصل، يستخدم البيانات الحية للقارئ النشط)
     */
    public static void show(List<UserProfile> realProfiles, String activeProfileId, List<Surah> allSurahs,
                             Consumer<UserProfile> onSwitch,
                             BiConsumer<String, String> onAdd,
                             Consumer<UserProfile> onDelete,
                             BiConsumer<UserProfile, String> onSetPassword,
                             Function<UserProfile, String> describePosition) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("القرّاء");

        TableView<UserProfile> table = new TableView<>();
        table.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.getItems().add(UserProfile.GUEST);
        table.getItems().addAll(realProfiles);

        TableColumn<UserProfile, String> nameCol = new TableColumn<>("الاسم");
        nameCol.setCellValueFactory(data -> {
            UserProfile p = data.getValue();
            String suffix = p.getId().equals(activeProfileId) ? "  ✓ نشط"
                    : p.hasPassword() ? "  🔒" : "";
            return new SimpleStringProperty(p.getDisplayName() + suffix);
        });
        nameCol.setPrefWidth(150);

        TableColumn<UserProfile, String> positionCol = new TableColumn<>("مكان التوقف");
        positionCol.setCellValueFactory(data ->
                new SimpleStringProperty(describePosition.apply(data.getValue())));
        positionCol.setPrefWidth(300);

        TableColumn<UserProfile, Void> actionCol = new TableColumn<>("");
        actionCol.setSortable(false);
        actionCol.setPrefWidth(110);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button btn = new Button("▶ متابعة");
            {
                btn.getStyleClass().add("gold-button");
                btn.setMaxWidth(Double.MAX_VALUE);
                btn.setOnAction(e -> {
                    UserProfile target = getTableView().getItems().get(getIndex());
                    attemptSwitch(target, activeProfileId, onSwitch, stage);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });

        table.getColumns().add(nameCol);
        table.getColumns().add(positionCol);
        table.getColumns().add(actionCol);
        table.getItems().stream().filter(p -> p.getId().equals(activeProfileId)).findFirst()
                .ifPresent(p -> table.getSelectionModel().select(p));
        VBox.setVgrow(table, Priority.ALWAYS);

        Label hint = new Label("كل قارئ له مشواره وعلاماته وإعداداته الخاصة تمامًا، منفصلة عن بقية القراء ومحمية "
                + "بكلمة مرور لو حدّدها. \"ضيف\" لا يحتفظ بأي بيانات شخصية دائمة ومتاح للجميع بدون كلمة مرور.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-size:12px; -fx-text-fill:#666;");

        Button addBtn = new Button("➕ إضافة قارئ جديد");
        addBtn.setOnAction(e -> showAddProfileDialog((name, password) -> {
            onAdd.accept(name, password);
            stage.close();
        }));

        Button passwordBtn = new Button("🔒 كلمة المرور");
        passwordBtn.setOnAction(e -> {
            UserProfile selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || selected.isGuest()) return;

            Runnable proceedToSetNew = () -> promptNewPassword(selected.hasPassword(), newPass -> {
                onSetPassword.accept(selected, newPass); // null = حذف كلمة المرور
                stage.close();
            });

            if (selected.hasPassword()) {
                promptPassword("تأكيد كلمة المرور الحالية لـ" + selected.getDisplayName(), current -> {
                    if (current != null && ProfileStore.verifyPassword(selected, current)) {
                        proceedToSetNew.run();
                    } else if (current != null) {
                        showError("كلمة المرور الحالية غير صحيحة.");
                    }
                });
            } else {
                proceedToSetNew.run();
            }
        });

        Button deleteBtn = new Button("🗑 حذف القارئ المحدَّد");
        deleteBtn.setOnAction(e -> {
            UserProfile selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || selected.isGuest()) return;

            Runnable proceedToDelete = () -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
                confirm.setHeaderText(null);
                confirm.setContentText("حذف \"" + selected.getDisplayName() + "\" وكل بياناته (مشواره وعلاماته) نهائيًا؟");
                confirm.showAndWait().ifPresent(r -> {
                    if (r == ButtonType.OK) {
                        onDelete.accept(selected);
                        stage.close();
                    }
                });
            };

            if (selected.hasPassword()) {
                promptPassword("تأكيد كلمة المرور لحذف " + selected.getDisplayName(), pass -> {
                    if (pass != null && ProfileStore.verifyPassword(selected, pass)) {
                        proceedToDelete.run();
                    } else if (pass != null) {
                        showError("كلمة المرور غير صحيحة.");
                    }
                });
            } else {
                proceedToDelete.run();
            }
        });

        HBox buttonsBar = new HBox(8, addBtn, passwordBtn, deleteBtn);
        buttonsBar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, new Label("القرّاء:"), table, hint, buttonsBar);
        root.setPadding(new Insets(16));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        Scene scene = new Scene(root, 640, 440);
        DesignStyles.applyTo(scene);
        stage.setScene(scene);
        stage.show();
    }

    private static void attemptSwitch(UserProfile target, String activeProfileId,
                                       Consumer<UserProfile> onSwitch, Stage stage) {
        if (target.getId().equals(activeProfileId)) {
            stage.close();
            return;
        }
        if (!target.hasPassword()) {
            onSwitch.accept(target);
            stage.close();
            return;
        }
        promptPassword(target.getDisplayName(), password -> {
            if (password != null && ProfileStore.verifyPassword(target, password)) {
                onSwitch.accept(target);
                stage.close();
            } else if (password != null) {
                showError("كلمة المرور غير صحيحة.");
            }
        });
    }

    /** نافذة إضافة قارئ: اسم + كلمة مرور اختيارية. */
    private static void showAddProfileDialog(BiConsumer<String, String> onConfirm) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("قارئ جديد");

        TextField nameField = new TextField();
        PasswordField passField = new PasswordField();
        PasswordField confirmField = new PasswordField();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("اسم القارئ:"), nameField);
        grid.addRow(1, new Label("كلمة المرور (اختياري):"), passField);
        grid.addRow(2, new Label("تأكيد كلمة المرور:"), confirmField);

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill:#a3271f;");
        errorLabel.setWrapText(true);

        Button confirmBtn = new Button("إضافة");
        confirmBtn.getStyleClass().add("gold-button");
        confirmBtn.setOnAction(e -> {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String pass = passField.getText();
            String confirm = confirmField.getText();
            if (name.isBlank()) {
                errorLabel.setText("لازم تكتب اسم القارئ.");
                return;
            }
            if (!pass.isEmpty() && !pass.equals(confirm)) {
                errorLabel.setText("كلمتا المرور غير متطابقتين.");
                return;
            }
            onConfirm.accept(name, pass.isEmpty() ? null : pass);
            stage.close();
        });

        Button cancelBtn = new Button("إلغاء");
        cancelBtn.setOnAction(e -> stage.close());

        HBox buttons = new HBox(10, confirmBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(12, grid, errorLabel, buttons);
        root.setPadding(new Insets(16));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        Scene scene = new Scene(root, 380, 220);
        DesignStyles.applyTo(scene);
        stage.setScene(scene);
        stage.showAndWait();
    }

    /** يطلب كلمة مرور واحدة (للتحقق من هوية). onResult يستقبل null لو المستخدم ألغى. */
    private static void promptPassword(String title, Consumer<String> onResult) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        PasswordField field = new PasswordField();
        field.setPromptText("كلمة المرور");
        VBox box = new VBox(10, new Label(title + ":"), field);
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? field.getText() : null);
        Optional<String> result = dialog.showAndWait();
        onResult.accept(result.orElse(null));
    }

    /** يطلب كلمة مرور جديدة (أو فارغة لإزالة الحماية) مرتين للتأكيد. */
    private static void promptNewPassword(boolean hadPasswordBefore, Consumer<String> onConfirm) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(hadPasswordBefore ? "تغيير كلمة المرور" : "تعيين كلمة مرور");

        PasswordField newField = new PasswordField();
        PasswordField confirmField = new PasswordField();
        Label note = new Label("اتركها فارغة لإزالة كلمة المرور (دخول حرّ بدون تحقق).");
        note.setWrapText(true);
        note.setStyle("-fx-font-size:12px; -fx-text-fill:#666;");
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill:#a3271f;");

        Button okBtn = new Button("حفظ");
        okBtn.getStyleClass().add("gold-button");
        okBtn.setOnAction(e -> {
            String p1 = newField.getText();
            String p2 = confirmField.getText();
            if (!p1.equals(p2)) {
                errorLabel.setText("كلمتا المرور غير متطابقتين.");
                return;
            }
            onConfirm.accept(p1.isEmpty() ? null : p1);
            stage.close();
        });
        Button cancelBtn = new Button("إلغاء");
        cancelBtn.setOnAction(e -> stage.close());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("كلمة المرور الجديدة:"), newField);
        grid.addRow(1, new Label("تأكيد:"), confirmField);

        VBox root = new VBox(12, grid, note, errorLabel, new HBox(10, okBtn, cancelBtn));
        root.setPadding(new Insets(16));
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        Scene scene = new Scene(root, 360, 220);
        DesignStyles.applyTo(scene);
        stage.setScene(scene);
        stage.show();
    }

    private static void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.getDialogPane().setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
