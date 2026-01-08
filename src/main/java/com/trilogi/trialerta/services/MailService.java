package com.trilogi.trialerta.services;

import com.trilogi.trialerta.models.ConfigurationProps;
import jakarta.mail.*;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MailService {

    private ConfigurationService configurationService = null;
    private OAuth2Service oauth2Service = null;
    private volatile boolean isRunning = false;
    private Store store;
    private Folder inbox;

    protected MailService() {
    }

    private ConfigurationService getConfigurationService() {
        if (configurationService == null) {
            configurationService = AlertServices.getInstance().getConfigurationService();
        }
        return configurationService;
    }

    private OAuth2Service getOAuth2Service() {
        if (oauth2Service == null) {
            oauth2Service = AlertServices.getInstance().getOAuth2Service();
        }
        return oauth2Service;
    }

    /**
     * Starts the monitoring process in a background thread.
     */
    public void startMonitoring(Consumer<String> onLog,
                                BiConsumer<String, String> onAuthRequired,
                                Consumer<String> onNewMail,
                                Consumer<Exception> onError) {

        if (isRunning) {
            onLog.accept("Service is already running.");
            return;
        }

        ConfigurationProps props = getConfigurationService().getConfiguration();
        if (props.ClientId.isEmpty() || props.TenantId.isEmpty() || props.Email.isEmpty()) {
            onError.accept(new IllegalStateException("Configuration is missing. Please check Settings."));
            return;
        }

        isRunning = true;
        onLog.accept("Starting background service for " + props.Email + "...");

        CompletableFuture.runAsync(() -> {
            try {
                // Get access token (handles caching and refresh automatically)
                String accessToken = getOAuth2Service().getAccessToken(
                        props.ClientId,
                        props.TenantId,
                        props.Email,
                        onAuthRequired,
                        onLog
                );

                // Connect to IMAP
                connectAndMonitor(accessToken, props, onLog, onNewMail, onError);

            } catch (Exception e) {
                isRunning = false;
                onError.accept(e);
            }
        });
    }

    /**
     * Connect to IMAP and start monitoring
     */
    private void connectAndMonitor(String accessToken, ConfigurationProps props,
                                   Consumer<String> onLog,
                                   Consumer<String> onNewMail,
                                   Consumer<Exception> onError) throws Exception {

        Properties mailProps = new Properties();
        mailProps.put("mail.imaps.host", "outlook.office365.com");
        mailProps.put("mail.imaps.port", "993");
        mailProps.put("mail.imaps.ssl.enable", "true");
        mailProps.put("mail.imaps.auth.mechanisms", "XOAUTH2");
        mailProps.put("mail.imaps.auth.plain.disable", "true");
        mailProps.put("mail.imaps.auth.xoauth2.disable", "false");

        Session session = Session.getInstance(mailProps);
        store = session.getStore("imaps");

        onLog.accept("Connecting to Outlook...");
        store.connect("outlook.office365.com", props.Email, accessToken);
        onLog.accept("Connected successfully!");

        inbox = store.getFolder("Inbox");
        if (inbox instanceof IMAPFolder imapInbox) {
            imapInbox.open(Folder.READ_ONLY);

            imapInbox.addMessageCountListener(new MessageCountAdapter() {
                @Override
                public void messagesAdded(MessageCountEvent e) {
                    for (Message m : e.getMessages()) {
                        try {
                            if (m.getSubject() != null && m.getSubject().equals("Teniu un VH")) {
                                String info = "From: " + m.getFrom()[0] + "\nSub: " + m.getSubject();
                                onNewMail.accept(info);
                            }
                        } catch (MessagingException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            });

            onLog.accept("IDLE Mode Active. Waiting for emails...");

            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    imapInbox.idle();
                } catch (MessagingException e) {
                    if (e.getMessage() != null && e.getMessage().contains("authentication")) {
                        onLog.accept("Authentication error - attempting token refresh...");

                        try {
                            // Get fresh token (will use refresh token automatically)
                            String newToken = getOAuth2Service().getAccessToken(
                                    props.ClientId,
                                    props.TenantId,
                                    props.Email,
                                    (url, code) -> {}, // Don't show dialog for refresh
                                    onLog
                            );

                            // Reconnect
                            store.close();
                            store = session.getStore("imaps");
                            store.connect("outlook.office365.com", props.Email, newToken);
                            imapInbox = (IMAPFolder) store.getFolder("Inbox");
                            imapInbox.open(Folder.READ_ONLY);
                            onLog.accept("✓ Reconnected with refreshed token");
                            continue;

                        } catch (Exception reconnectError) {
                            onLog.accept("Reconnection failed: " + reconnectError.getMessage());
                            throw e;
                        }
                    }
                    onLog.accept("IDLE refreshed/interrupted.");
                }
            }
        }
    }

    public void stopMonitoring() {
        isRunning = false;
        try {
            if (inbox != null && inbox.isOpen()) inbox.close(false);
            if (store != null && store.isConnected()) store.close();
            System.out.println("Disconnected - Monitoring Paused (Token preserved)");
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }

    /**
     * Clear all cached data
     */
    public void clearCache() {
        getOAuth2Service().clearCache();
        System.out.println("Token cache cleared");
    }

    public boolean isRunning() {
        return isRunning;
    }
}