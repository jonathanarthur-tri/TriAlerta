module TriAlerta {
    requires atlantafx.base;
    requires com.azure.core;
    requires com.azure.identity;
    requires jakarta.mail;
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires org.eclipse.angus.mail;
    requires de.jensd.fx.glyphs.fontawesome;
    requires java.desktop;
    requires org.controlsfx.controls;
    requires com.dustinredmond.fxtrayicon;
    requires com.fasterxml.jackson.databind;


    opens com.trilogi.trialerta to javafx.fxml;
    opens com.trilogi.trialerta.controllers to javafx.fxml;
    opens com.trilogi.trialerta.services to com.fasterxml.jackson.databind;

    exports com.trilogi.trialerta;
}