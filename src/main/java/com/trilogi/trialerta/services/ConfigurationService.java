package com.trilogi.trialerta.services;

import com.trilogi.trialerta.models.ConfigurationProps;

import java.io.*;
import java.util.Properties;

public class ConfigurationService {
    private static final String CONFIG_FILE = "Config.properties";

    private Properties config = new Properties();

    protected ConfigurationService(){

    }
    public void loadConfig() {
        File file = new File(CONFIG_FILE);

        if (!file.exists()) {
            createDefaultConfigFile(file);
        }

        try (FileInputStream in = new FileInputStream(file)) {
            config.load(in);
        } catch (IOException e) {

            System.err.println("Could not load config file: " + e.getMessage());
        }
    }
    private void createDefaultConfigFile(File targetFile) {
        try {

            try (InputStream internalStream = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (internalStream != null) {

                    java.nio.file.Files.copy(internalStream, targetFile.toPath());
                } else {

                    targetFile.createNewFile();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not create configuration file", e);
        }
    }
    public ConfigurationProps getConfiguration() {
        ConfigurationProps props = new ConfigurationProps();

        props.ClientId = config.getProperty("client.id", "");
        props.TenantId = config.getProperty("tenant.id", "");
        props.Email    = config.getProperty("app.email", "");
        props.Val1     = config.getProperty("value.1", "");
        props.Val2     = config.getProperty("value.2", "");
        props.Val3     = config.getProperty("value.3", "");
        return props;
    }

    public void saveConfig(ConfigurationProps properties) {
        config.setProperty("client.id", properties.ClientId);
        config.setProperty("tenant.id", properties.TenantId);
        config.setProperty("app.email", properties.Email);


        if (properties.Val1 != null && !properties.Val1.isBlank()) config.setProperty("value.1", properties.Val1);
        if (properties.Val2 != null && !properties.Val2.isBlank()) config.setProperty("value.2", properties.Val2);
        if (properties.Val3 != null && !properties.Val3.isBlank()) config.setProperty("value.3", properties.Val3);

        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            config.store(out, "TriAlert Config");
        } catch (IOException e) {
            throw new RuntimeException("Unable To Save Configuration File", e);
        }
    }
}
