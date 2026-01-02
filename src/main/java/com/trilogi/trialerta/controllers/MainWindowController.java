package com.trilogi.trialerta.controllers;

import com.dustinredmond.fxtrayicon.FXTrayIcon;
import com.trilogi.trialerta.models.Model;
import com.trilogi.trialerta.services.AlertServices;
import com.trilogi.trialerta.services.ConfigurationService;
import com.trilogi.trialerta.services.MailService;
import de.jensd.fx.glyphs.fontawesome.FontAwesomeIconView;
import javafx.application.Platform;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.controlsfx.control.Notifications;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.awt.Desktop;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Image;
import java.awt.PopupMenu;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.ResourceBundle;

public class MainWindowController implements Initializable {
    public Button start_restart_btn;
    public Button configurations_btn;
    public Button options_btn;
    public FontAwesomeIconView playBtn_Icon;
    public FontAwesomeIconView optionsBtn_Icon;

    private ConfigurationService configurationService;
    private AlertServices alertServices;
    private MailService mailService;
    private TrayIcon trayIcon;
    private FXTrayIcon fxTrayIcon;
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        configurationService = getConfigurationService();
        mailService = getMailService();

        configurationService.loadConfig();
        addListeners();

    }

    private void addListeners() {
        configurations_btn.setOnMouseClicked(mouseEvent -> onConfigurationClicked());
        start_restart_btn.setOnMouseClicked(mouseEvent -> onStartRestartClicked());
    }

    private void onStartRestartClicked() {
        if (mailService.isRunning()) {
            mailService.stopMonitoring();



            if (fxTrayIcon != null) {
                fxTrayIcon.showInfoMessage("Service Stopped", "App Monitoring");
            }

            playBtn_Icon.setGlyphName("PLAY");
            start_restart_btn.setTooltip(new Tooltip("Start Monitoring"));

            showAlert(Alert.AlertType.INFORMATION, "Monitoring Paused", "Monitoring has been paused.");
            return;
        }

        // Start monitoring
        playBtn_Icon.setGlyphName("PAUSE");
        start_restart_btn.setTooltip(new Tooltip("Pause Monitoring"));



        if (fxTrayIcon != null) {
            fxTrayIcon.showInfoMessage("Service Started", "App Monitoring");
        }

        mailService.startMonitoring(
                // 1. On Log Message
                (msg) -> Platform.runLater(() -> {
                    System.out.println("LOG: " + msg);
                }),

                // 2. On Auth Required
                (url, code) -> Platform.runLater(() -> showAuthDialog(url, code)),

                // 3. On New Mail
                (info) -> Platform.runLater(() -> {
                    // playAlertSound();
                    showNotification("New Email Received", info);
                }),

                // 4. On Error
                (ex) -> Platform.runLater(() -> {
                    // Reset button to Play on error
                    playBtn_Icon.setGlyphName("PLAY");
                    start_restart_btn.setDisable(false);

                    showAlert(Alert.AlertType.ERROR, "Error", "Error: " + ex.getMessage());
                    ex.printStackTrace();
                })
        );
    }

    private void onConfigurationClicked() {
        Model.getInstance().getViewFactory().showConfigWindow();
    }

    private void showAuthDialog(String url, String code) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Authentication Required");
        alert.setHeaderText("Please sign in to Microsoft");

        // Create clickable content
        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER_LEFT);

        Label instruction1 = new Label("1. Click the link below:");
        Hyperlink urlLink = new Hyperlink(url);
        urlLink.setOnAction(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(new URI(url));
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        Label instruction2 = new Label("2. Enter Code:");
        TextField codeField = new TextField(code);
        codeField.setEditable(false);
        codeField.setMaxWidth(200);

        content.getChildren().addAll(instruction1, urlLink, instruction2, codeField);

        alert.getDialogPane().setContent(content);
        alert.showAndWait();
    }

/*
    private void initSystemTray() {
        if (!SystemTray.isSupported()) {
            System.out.println("System tray not supported");
            return;
        }

        try {
            SystemTray tray = SystemTray.getSystemTray();

            // Load tray icon image
            Image image = Toolkit.getDefaultToolkit().getImage(
                    getClass().getResource("/mail.png")
            );

            PopupMenu popup = new PopupMenu();

            // Add "Open" option - explicitly use AWT MenuItem
            java.awt.MenuItem openItem = new java.awt.MenuItem("Open");
            openItem.addActionListener(e -> Platform.runLater(() -> {
                Stage stage = Model.getInstance().getViewFactory().getMainStage();
                if (stage != null) {
                    stage.show();
                    stage.toFront();
                    stage.requestFocus();
                }
            }));

            // Add "Exit" option - explicitly use AWT MenuItem
            java.awt.MenuItem exitItem = new java.awt.MenuItem("Exit");
            exitItem.addActionListener(e -> {
                shutdown();
                Platform.exit();
                System.exit(0);
            });

            popup.add(openItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(image, "TriAlert", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.setToolTip("TriAlert Running");

            // Double click to open
            trayIcon.addActionListener(e -> Platform.runLater(() -> {
                Stage stage = Model.getInstance().getViewFactory().getMainStage();
                if (stage != null) {
                    stage.show();
                    stage.toFront();
                    stage.requestFocus();
                }
            }));

            tray.add(trayIcon);
        } catch (Exception e) {
            System.err.println("Tray initialization failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private void showNotification(String title, String message) {
        // Try system tray first
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        } else {
            System.out.println("NOTIFICATION: " + title + " - " + message);
        }

        // Also show JavaFX notification (requires ControlsFX library)
        // If you don't have ControlsFX, comment out this block
        try {
            Notifications.create()
                    .title(title)
                    .darkStyle()
                    .text(message)
                    .hideAfter(Duration.minutes (10))
                    .position(Pos.BOTTOM_RIGHT)
                    .showInformation();
        } catch (Exception e) {

            System.out.println("NOTIFICATION: " + title + " - " + message);
        }
    }
*/

    public void initAfterStageSet() {
        initSystemTray();
    }
private void initSystemTray() {
    try {
        Stage stage = Model.getInstance().getViewFactory().getMainStage();

        // Create FXTrayIcon
        fxTrayIcon = new FXTrayIcon(stage, getClass().getResource("/mail.png"));
        fxTrayIcon.setApplicationTitle("TriAlert");
        fxTrayIcon.setTrayIconTooltip("TriAlert Running");

        // Add menu items
        javafx.scene.control.MenuItem openItem = new javafx.scene.control.MenuItem("Open");
        openItem.setOnAction(e -> {
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });

        javafx.scene.control.MenuItem exitItem = new javafx.scene.control.MenuItem("Exit");
        exitItem.setOnAction(e -> {
            shutdown();
            Platform.exit();
        });

        fxTrayIcon.addMenuItem(openItem);
        fxTrayIcon.addSeparator();
        fxTrayIcon.addMenuItem(exitItem);

        // Show tray icon
        fxTrayIcon.show();

    } catch (Exception e) {
        System.err.println("Tray initialization failed: " + e.getMessage());
        e.printStackTrace();
    }
}
    private void showNotification(String title, String message) {
        // Use FXTrayIcon for notifications
        if (fxTrayIcon != null) {
            fxTrayIcon.showInfoMessage(title, message);
        }

        // Also show JavaFX notification (ControlsFX)
        try {
            Notifications.create()
                    .title(title)
                    .darkStyle()
                    .text(message)
                    .hideAfter(Duration.minutes(10))
                    .position(Pos.BOTTOM_RIGHT)
                    .showInformation();
        } catch (Exception e) {
            System.out.println("NOTIFICATION: " + title + " - " + message);
        }
    }
    private void playAlertSound() {
        new Thread(() -> {
            try {
                InputStream audioSrc = getClass().getResourceAsStream("/alert.wav");
                if (audioSrc == null) {
                    // Fallback beep
                    Toolkit.getDefaultToolkit().beep();
                    return;
                }

                InputStream bufferedIn = new BufferedInputStream(audioSrc);
                AudioInputStream audioStream = AudioSystem.getAudioInputStream(bufferedIn);

                Clip clip = AudioSystem.getClip();
                clip.open(audioStream);
                clip.start();

                // Wait for clip to finish
                Thread.sleep(clip.getMicrosecondLength() / 1000);
                clip.close();
                audioStream.close();

            } catch (Exception e) {
                System.err.println("Audio playback failed: " + e.getMessage());
            }
        }).start();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // Lazy initialization methods
    private ConfigurationService getConfigurationService() {
        if (configurationService == null) {
            configurationService = getAlertServices().getConfigurationService();
        }
        return configurationService;
    }

    private AlertServices getAlertServices() {
        if (alertServices == null) {
            alertServices = AlertServices.getInstance();
        }
        return alertServices;
    }

    private MailService getMailService() {
        if (mailService == null) {
            mailService = getAlertServices().getMailService();
        }
        return mailService;
    }

    /**
     * Call this when the window is closing to clean up resources
     */
/*    public void shutdown() {
        if (mailService != null && mailService.isRunning()) {
            mailService.stopMonitoring();
        }
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }*/
    public void shutdown() {
        if (mailService != null && mailService.isRunning()) {
            mailService.stopMonitoring();
        }
        if (fxTrayIcon != null) {
            fxTrayIcon.hide();
        }
    }
}