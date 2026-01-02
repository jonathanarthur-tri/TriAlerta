package com.trilogi.trialerta;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.trilogi.trialerta.models.Model;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class App extends Application {
    @Override
    public void start(Stage stage) throws Exception {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        Model.getInstance().getViewFactory().showMainWindow();


    }

    public static void main(String[] args) {
        launch(args);
    }
}
