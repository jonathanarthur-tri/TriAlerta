package com.trilogi.trialerta.views;

import com.trilogi.trialerta.controllers.MainWindowController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class ViewFactory {

public ViewFactory() {}
    private Stage mainStage;

    public void showMainWindow(){

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/MainWindow.fxml"));
       mainStage = createStage(loader);

        MainWindowController controller = loader.getController();
        if (controller != null) {
            controller.initAfterStageSet();
        }
    }

    public void showConfigWindow(){

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ConfigurationWindow.fxml"));
        createStage(loader);
    }

    private Stage createStage(FXMLLoader loader) {
        Scene scene = null;
        try {
            scene = new Scene(loader.load());
        } catch (Exception e) {
            e.printStackTrace();
        }

        Stage stage = new Stage();
        stage.setScene(scene);
        stage.setTitle("TriAlerta");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/mail.png")));


        stage.setOnCloseRequest(event -> {
            event.consume();
            stage.hide();
        });

        stage.show();
        return stage;
    }
    public Stage getMainStage() {
        return mainStage;
    }




}
