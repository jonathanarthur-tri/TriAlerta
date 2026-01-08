package com.trilogi.trialerta.controllers;

import com.trilogi.trialerta.models.ConfigurationProps;
import com.trilogi.trialerta.services.AlertServices;
import com.trilogi.trialerta.services.ConfigurationService;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;

import java.net.URL;
import java.util.ResourceBundle;

public class ConfigurationWindowController implements Initializable {
    public TextField clientId_TF;
    public TextField tenantId_TF;
    public TextField email_Tf;
    public TextField value1_Tf;
    public TextField value2_Tf;
    public TextField value3_Tf;
    public Button save_btn;

    ConfigurationService configurationService = null;

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        configurationService = getConfigurationService();

        try{
            configurationService.loadConfig();
            ConfigurationProps configProps = configurationService.getConfiguration();
            clientId_TF.setText(configProps.ClientId);
            tenantId_TF.setText(configProps.TenantId);
            email_Tf.setText(configProps.Email);
            value1_Tf.setText(configProps.Val1);
            value2_Tf.setText(configProps.Val2);
            value3_Tf.setText(configProps.Val3);
        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Load Error");
            alert.setHeaderText(null);
            alert.setContentText("Failed to load configuration:\n" + e.getMessage());
            alert.initOwner(clientId_TF.getScene().getWindow());
            alert.showAndWait();
        }

        loadListeners();
    }

    private ConfigurationService getConfigurationService() {
        if (configurationService == null) {
            configurationService = AlertServices.getInstance().getConfigurationService();
        }
        return configurationService;
    }

    private void loadListeners(){
        save_btn.setOnMouseClicked(mouseEvent -> onButtonSaveClicked());
    }

    private void onButtonSaveClicked() {
        ConfigurationProps configProps = new ConfigurationProps();
        configProps.ClientId = clientId_TF.getText().trim();
        configProps.TenantId = tenantId_TF.getText().trim();
        configProps.Email    = email_Tf.getText().trim();
        configProps.Val1     = value1_Tf.getText().trim();
        configProps.Val2     = value2_Tf.getText().trim();
        configProps.Val3     = value3_Tf.getText().trim();

        if(configProps.ClientId.isEmpty() || configProps.TenantId.isEmpty() || configProps.Email.isEmpty()){
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Validation Error");
            alert.setHeaderText(null);
            alert.setContentText("Client ID, Tenant ID, and Email are required fields.");
            alert.initOwner(clientId_TF.getScene().getWindow());
            alert.showAndWait();
            return;
        }

        try{

            configurationService.saveConfig(configProps);

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Success");
            alert.setHeaderText(null);
            alert.setContentText("Configuration saved successfully!");
            alert.initOwner(clientId_TF.getScene().getWindow());
            alert.showAndWait();

        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Save Error");
            alert.setHeaderText(null);
            alert.setContentText("Failed to save configuration:\n" + e.getMessage());
            alert.initOwner(clientId_TF.getScene().getWindow());
            alert.showAndWait();
        }
    }
}