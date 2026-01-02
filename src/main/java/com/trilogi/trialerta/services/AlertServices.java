package com.trilogi.trialerta.services;

public class AlertServices {

    private final MailService mailService;
    private final ConfigurationService configurationService;

    private AlertServices() {
        this.mailService = new MailService();
        this.configurationService = new ConfigurationService();
    }
    private static AlertServices alertServices;
    public static synchronized  AlertServices getInstance() {
        if (alertServices == null) {
            alertServices = new AlertServices();
        }
        return alertServices;
    }

    public MailService getMailService() {
        return mailService;
    }
    public ConfigurationService getConfigurationService() {
        return configurationService;
    }
}
