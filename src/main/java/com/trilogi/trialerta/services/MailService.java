package com.trilogi.trialerta.services;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DeviceCodeCredential;
import com.azure.identity.DeviceCodeCredentialBuilder;
import com.trilogi.trialerta.models.ConfigurationProps;
import jakarta.mail.*;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MailService {

    ConfigurationService configurationService = null;
    private static final String SCOPE = "https://outlook.office.com/IMAP.AccessAsUser.All offline_access";
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
     * Token storage class with refresh token support
     */
    private static class StoredToken implements Serializable {
        private static final long serialVersionUID = 2L;
        String accessToken;
        String refreshToken;
        String expiresAt; // ISO-8601 format
        String configHash;
        String clientId;
        String tenantId;
    }

    protected MailService() {
    }

    private ConfigurationService getConfigurationService() {
        if (configurationService == null) {
            configurationService = AlertServices.getInstance().getConfigurationService();
        }
        return configurationService;
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
                // Ensure cache directory exists
                Files.createDirectories(CACHE_DIR);

                // Create config hash
                String currentConfigHash = props.ClientId + ":" + props.TenantId + ":" + props.Email;
                boolean configChanged = !currentConfigHash.equals(lastConfigHash);

                AccessToken token = null;
                StoredToken storedToken = null;

                // Try to load cached token from disk if config hasn't changed

                    storedToken = loadTokenFromDisk(currentConfigHash, onLog);

                    if (storedToken != null) {
                        // Check if access token is still valid
                        OffsetDateTime expiresAt = OffsetDateTime.parse(storedToken.expiresAt);

                        if (expiresAt.isAfter(OffsetDateTime.now().plusMinutes(5))) {
                            // Access token is still valid
                            token = new AccessToken(storedToken.accessToken, expiresAt);
                            onLog.accept("✓ Using cached access token (expires: " + token.getExpiresAt() + ")");
                        } else if (storedToken.refreshToken != null && !storedToken.refreshToken.isEmpty()) {
                            // Access token expired, try to refresh
                            onLog.accept("Access token expired, attempting refresh...");
                            token = refreshAccessToken(storedToken, onLog);

                            if (token != null) {
                                // Save the new token
                                storedToken.accessToken = token.getToken();
                                storedToken.expiresAt = token.getExpiresAt().toString();
                                saveStoredToken(storedToken, onLog);
                                onLog.accept("✓ Token refreshed successfully (expires: " + token.getExpiresAt() + ")");
                            } else {
                                onLog.accept("Refresh failed, need to re-authenticate");
                                storedToken = null;
                            }
                        }
                    }


                // If no valid token, we need to authenticate
                if (token == null) {
                    onLog.accept("Need fresh authentication...");

                    AtomicReference<Boolean> authDialogShown = new AtomicReference<>(false);

                    // Create credential with auth dialog callback
                    DeviceCodeCredential credential = new DeviceCodeCredentialBuilder()
                            .clientId(props.ClientId)
                            .tenantId(props.TenantId)
                            .challengeConsumer(challenge -> {
                                // Only show dialog once
                                if (!authDialogShown.getAndSet(true)) {
                                    onAuthRequired.accept(challenge.getVerificationUrl(), challenge.getUserCode());
                                }
                            })
                            .build();

                    // Get new token (this includes refresh token internally, but Azure doesn't expose it)
                    onLog.accept("Requesting access token...");
                    TokenRequestContext request = new TokenRequestContext().addScopes(SCOPE);
                    token = credential.getToken(request).block();

                    // Cache the credential for future use
                    cachedCredential = credential;
                    lastConfigHash = currentConfigHash;

                    if (token == null) {
                        throw new RuntimeException("Failed to retrieve Access Token.");
                    }

                    // Note: We can't extract the refresh token from DeviceCodeCredential directly
                    // But we can get it using the credential object later
                    storedToken = new StoredToken();
                    storedToken.accessToken = token.getToken();
                    storedToken.refreshToken = ""; // Will be populated if we can extract it
                    storedToken.expiresAt = token.getExpiresAt().toString();
                    storedToken.configHash = currentConfigHash;
                    storedToken.clientId = props.ClientId;
                    storedToken.tenantId = props.TenantId;

                    // Try to extract refresh token from the Azure cache
                    String refreshToken = extractRefreshToken(props.TenantId, props.ClientId, onLog);
                    if (refreshToken != null) {
                        storedToken.refreshToken = refreshToken;
                        onLog.accept("✓ Refresh token extracted successfully");
                    }

                    saveStoredToken(storedToken, onLog);
                    onLog.accept("✓ Token obtained and cached (expires: " + token.getExpiresAt() + ")");
                }

                // Connect to IMAP
                connectAndMonitor(token, props, storedToken, onLog, onNewMail, onError);

            } catch (Exception e) {
                isRunning = false;
                onError.accept(e);
            }
        });
    }

    /**
     * Connect to IMAP and start monitoring
     */
    private void connectAndMonitor(AccessToken token, ConfigurationProps props,
                                   StoredToken storedToken, Consumer<String> onLog,
                                   Consumer<String> onNewMail, Consumer<Exception> onError) throws Exception {

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

            AtomicReference<AccessToken> currentToken = new AtomicReference<>(token);

            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    imapInbox.idle();
                } catch (MessagingException e) {
                    if (e.getMessage() != null && e.getMessage().contains("authentication")) {
                        onLog.accept("Authentication error - attempting token refresh...");

                        // Try to refresh the token
                        AccessToken newToken = refreshAccessToken(storedToken, onLog);

                        if (newToken != null) {
                            // Update stored token
                            storedToken.accessToken = newToken.getToken();
                            storedToken.expiresAt = newToken.getExpiresAt().toString();
                            saveStoredToken(storedToken, onLog);

                            // Reconnect
                            try {
                                store.close();
                                store = session.getStore("imaps");
                                store.connect("outlook.office365.com", props.Email, newToken.getToken());
                                imapInbox = (IMAPFolder) store.getFolder("Inbox");
                                imapInbox.open(Folder.READ_ONLY);
                                currentToken.set(newToken);
                                onLog.accept("✓ Reconnected with refreshed token");
                                continue;
                            } catch (Exception reconnectError) {
                                onLog.accept("Reconnection failed: " + reconnectError.getMessage());
                                throw e;
                            }
                        } else {
                            onLog.accept("Token refresh failed - deleting cache");
                            TOKEN_FILE.delete();
                            cachedCredential = null;
                            throw e;
                        }
                    }
                    onLog.accept("IDLE refreshed/interrupted.");
                }
            }
        }
    }

    /**
     * Refresh access token using refresh token
     */
    private AccessToken refreshAccessToken(StoredToken stored, Consumer<String> onLog) {
        if (stored == null || stored.refreshToken == null || stored.refreshToken.isEmpty()) {
            onLog.accept("No refresh token available");
            return null;
        }

        try {
            String tokenEndpoint = "https://login.microsoftonline.com/" + stored.tenantId + "/oauth2/v2.0/token";
            URL url = new URL(tokenEndpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            // Build request body
            String requestBody = "client_id=" + URLEncoder.encode(stored.clientId, StandardCharsets.UTF_8) +
                    "&grant_type=refresh_token" +
                    "&refresh_token=" + URLEncoder.encode(stored.refreshToken, StandardCharsets.UTF_8) +
                    "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }

                    // Parse JSON response
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(response.toString());

                    String accessToken = root.get("access_token").asText();
                    int expiresIn = root.get("expires_in").asInt();

                    // Update refresh token if a new one was provided
                    if (root.has("refresh_token")) {
                        stored.refreshToken = root.get("refresh_token").asText();
                    }

                    OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(expiresIn);
                    return new AccessToken(accessToken, expiresAt);
                }
            } else {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    onLog.accept("Token refresh failed: " + responseCode + " - " + errorResponse);
                }
            }
        } catch (Exception e) {
            onLog.accept("Token refresh error: " + e.getMessage());
        }

        return null;
    }

    /**
     * Try to extract refresh token from Azure Identity cache
     * This is a best-effort approach - may not always work
     */
    private String extractRefreshToken(String tenantId, String clientId, Consumer<String> onLog) {
        try {
            // Azure Identity stores tokens in platform-specific locations
            // This is a simplified approach - you might need to adjust based on OS
            Path azureCachePath = Paths.get(System.getProperty("user.home"),
                    ".azure", "msal_token_cache.json");

            if (!Files.exists(azureCachePath)) {
                onLog.accept("Azure cache file not found");
                return null;
            }

            String content = Files.readString(azureCachePath);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(content);

            // Try to find refresh token for our client
            JsonNode refreshTokens = root.get("RefreshToken");
            if (refreshTokens != null && refreshTokens.isObject()) {
                for (JsonNode tokenNode : refreshTokens) {
                    if (tokenNode.has("client_id") &&
                            tokenNode.get("client_id").asText().equals(clientId)) {
                        return tokenNode.get("secret").asText();
                    }
                }
            }
        } catch (Exception e) {
            onLog.accept("Could not extract refresh token from cache: " + e.getMessage());
        }

        return null;
    }

    /**
     * Load token from disk if it exists
     */
    private StoredToken loadTokenFromDisk(String configHash, Consumer<String> onLog) {
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

            onLog.accept("Found cached token on disk");
            return stored;

        } catch (Exception e) {
            onLog.accept("Failed to load cached token: " + e.getMessage());
            TOKEN_FILE.delete();
            return null;
        }
    }

    /**
     * Save token to disk
     */
    private void saveStoredToken(StoredToken stored, Consumer<String> onLog) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(TOKEN_FILE))) {
            oos.writeObject(stored);
            onLog.accept("Token saved to disk: " + TOKEN_FILE.getAbsolutePath());
        } catch (Exception e) {
            onLog.accept("Warning: Failed to cache token: " + e.getMessage());
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