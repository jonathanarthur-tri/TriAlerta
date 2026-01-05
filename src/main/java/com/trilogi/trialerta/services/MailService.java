package com.trilogi.trialerta.services;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DeviceCodeCredential;
import com.azure.identity.DeviceCodeCredentialBuilder;
import com.azure.identity.TokenCachePersistenceOptions;
import com.trilogi.trialerta.models.ConfigurationProps;
import jakarta.mail.*;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MailService {

    ConfigurationService configurationService = null;
    private static final String SCOPE = "https://outlook.office.com/IMAP.AccessAsUser.All";
    private volatile boolean isRunning = false;
    private Store store;
    private Folder inbox;

    // Cache directory
    private static final Path CACHE_DIR = Paths.get(System.getProperty("user.home"), ".trialert");
    private static final File TOKEN_FILE = CACHE_DIR.resolve("token.dat").toFile();

    // In-memory cache
    private DeviceCodeCredential cachedCredential;
    private String lastConfigHash;

    /**
     * Simple token storage class
     */
    private static class StoredToken implements Serializable {
        private static final long serialVersionUID = 1L;
        String token;
        String expiresAt; // ISO-8601 format
        String configHash;
    }
    protected MailService(){

    }

    private ConfigurationService getConfigurationService() {
        if (configurationService == null) {
            configurationService = AlertServices.getInstance().getConfigurationService();
        }
        return configurationService;
    }


    /**
     * Starts the monitoring process in a background thread.
     *
     * @param onLog         Callback for status updates (String message)
     * @param onAuthRequired Callback when Microsoft requires login (Url, UserCode)
     * @param onNewMail     Callback when a new email arrives (Subject/Sender)
     * @param onError       Callback for errors (Exception)
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
                // Ensure cache directory exists
                Files.createDirectories(CACHE_DIR);

                // Create config hash
                String currentConfigHash = props.ClientId + ":" + props.TenantId + ":" + props.Email;
                boolean configChanged = !currentConfigHash.equals(lastConfigHash);

                AccessToken token = null;

                // Try to load cached token from disk if config hasn't changed
               /* if (!configChanged) {
                    token = loadTokenFromDisk(currentConfigHash, onLog);
                }
*/
                token = loadTokenFromDisk(currentConfigHash, onLog);
                // If no valid cached token, we need to authenticate
                if (token == null) {
                    onLog.accept("Need fresh authentication...");

                    // Create or reuse credential
                    if (cachedCredential == null || configChanged) {
                        onLog.accept("Initializing authentication...");

                        AtomicReference<Boolean> deviceCodeShown = new AtomicReference<>(false);

                        cachedCredential = new DeviceCodeCredentialBuilder()
                                .clientId(props.ClientId)
                                .tenantId(props.TenantId)
                                .challengeConsumer(challenge -> {
                                    deviceCodeShown.set(true);
                                    onAuthRequired.accept(challenge.getVerificationUrl(), challenge.getUserCode());
                                })
                                .build();

                        lastConfigHash = currentConfigHash;
                    }

                    // Get new token
                    onLog.accept("Requesting access token...");
                    TokenRequestContext request = new TokenRequestContext().addScopes(SCOPE);
                    token = cachedCredential.getToken(request).block();

                    if (token == null) {
                        throw new RuntimeException("Failed to retrieve Access Token.");
                    }

                    // Save token to disk
                    saveTokenToDisk(token, currentConfigHash, onLog);
                    onLog.accept("✓ Token obtained and cached (expires: " + token.getExpiresAt() + ")");
                } else {
                    onLog.accept("✓ Using cached token (expires: " + token.getExpiresAt() + ")");
                }

                // Connect to IMAP
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
                store.connect("outlook.office365.com", props.Email, token.getToken());
                onLog.accept("Connected successfully!");

                inbox = store.getFolder("Inbox");
                if (inbox instanceof IMAPFolder) {
                    IMAPFolder imapInbox = (IMAPFolder) inbox;
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
                                onLog.accept("Authentication error - token may have expired");
                                // Delete cached token so we re-authenticate next time
                                TOKEN_FILE.delete();
                                cachedCredential = null;
                                throw e;
                            }
                            onLog.accept("IDLE refreshed/interrupted.");
                        }
                    }
                }

            } catch (Exception e) {
                isRunning = false;
                onError.accept(e);
            }
        });
    }

    /**
     * Load token from disk if it exists and is still valid
     */
    private AccessToken loadTokenFromDisk(String configHash, Consumer<String> onLog) {
        if (!TOKEN_FILE.exists()) {
            onLog.accept("No cached token found");
            return null;
        }

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(TOKEN_FILE))) {
            StoredToken stored = (StoredToken) ois.readObject();

            // Verify config hasn't changed
            if (!configHash.equals(stored.configHash)) {
                onLog.accept("Config changed - cached token invalid");
                return null;
            }

            // Parse expiry time
            OffsetDateTime expiresAt = OffsetDateTime.parse(stored.expiresAt);

            // Check if token is still valid (with 5 minute buffer)
            if (expiresAt.isBefore(OffsetDateTime.now().plusMinutes(5))) {
                onLog.accept("Cached token expired");
                TOKEN_FILE.delete();
                return null;
            }

            onLog.accept("Found valid cached token on disk");
            // Reconstruct AccessToken
            return new AccessToken(stored.token, expiresAt);

        } catch (Exception e) {
            onLog.accept("Failed to load cached token: " + e.getMessage());
            TOKEN_FILE.delete();
            return null;
        }
    }

    /**
     * Save token to disk
     */
    private void saveTokenToDisk(AccessToken token, String configHash, Consumer<String> onLog) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(TOKEN_FILE))) {
            StoredToken stored = new StoredToken();
            stored.token = token.getToken();
            stored.expiresAt = token.getExpiresAt().toString();
            stored.configHash = configHash;

            oos.writeObject(stored);
            onLog.accept("Token saved to disk: " + TOKEN_FILE.getAbsolutePath());

        } catch (Exception e) {
            onLog.accept("Warning: Failed to cache token: " + e.getMessage());
            // Not critical - we can still work without disk cache
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
        cachedCredential = null;
        lastConfigHash = null;

        if (TOKEN_FILE.exists()) {
            TOKEN_FILE.delete();
            System.out.println("Token cache deleted");
        }

        System.out.println("Credential cache cleared");
    }

    public boolean isRunning() {
        return isRunning;
    }
}
