package com.trilogi.trialerta.services;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manual OAuth2 implementation for Microsoft 365
 * This gives full control over token management including refresh tokens
 */
public class OAuth2Service {

    private static final String AUTHORITY = "https://login.microsoftonline.com";
    private static final String SCOPE = "https://outlook.office.com/IMAP.AccessAsUser.All offline_access";
    private static final Path CACHE_DIR = Paths.get(System.getProperty("user.home"), ".trialert");
    private static final Path TOKEN_FILE = CACHE_DIR.resolve("oauth_token.json");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Token storage class - using proper JavaBean pattern for Jackson
     */
    public static class TokenInfo {
        @JsonProperty("accessToken")
        private String accessToken;

        @JsonProperty("refreshToken")
        private String refreshToken;

        @JsonProperty("expiresAt")
        private long expiresAt;

        @JsonProperty("clientId")
        private String clientId;

        @JsonProperty("tenantId")
        private String tenantId;

        @JsonProperty("email")
        private String email;

        // Default constructor for Jackson
        public TokenInfo() {}

        // Getters and setters
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isExpired() {
            return Instant.now().getEpochSecond() >= (expiresAt - 300); // 5 min buffer
        }
    }

    /**
     * Get valid access token - refresh if needed
     */
    public String getAccessToken(String clientId, String tenantId, String email,
                                 BiConsumer<String, String> onAuthRequired,
                                 Consumer<String> onLog) throws Exception {

        Files.createDirectories(CACHE_DIR);

        // Try to load cached token
        TokenInfo token = loadToken();

        // Check if token is valid and matches current config
        if (token != null &&
                token.getClientId().equals(clientId) &&
                token.getTenantId().equals(tenantId) &&
                token.getEmail().equals(email)) {

            if (!token.isExpired()) {
                onLog.accept("✓ Using cached access token");
                return token.getAccessToken();
            }

            // Try to refresh
            onLog.accept("Access token expired, refreshing...");
            TokenInfo refreshed = refreshAccessToken(token, onLog);
            if (refreshed != null) {
                saveToken(refreshed);
                onLog.accept("✓ Token refreshed successfully");
                return refreshed.getAccessToken();
            }

            onLog.accept("Refresh failed, need new authentication");
        }

        // Need new authentication
        onLog.accept("Starting device code flow...");
        TokenInfo newToken = authenticateWithDeviceCode(clientId, tenantId, email, onAuthRequired, onLog);
        saveToken(newToken);
        onLog.accept("✓ Authentication successful");
        return newToken.getAccessToken();
    }

    /**
     * Authenticate using device code flow
     */
    private TokenInfo authenticateWithDeviceCode(String clientId, String tenantId, String email,
                                                 BiConsumer<String, String> onAuthRequired,
                                                 Consumer<String> onLog) throws Exception {

        // Step 1: Request device code
        String deviceCodeEndpoint = AUTHORITY + "/" + tenantId + "/oauth2/v2.0/devicecode";

        URL url = new URL(deviceCodeEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        String requestBody = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        Map<String, Object> deviceCodeResponse = readJsonResponseAsMap(conn);

        String deviceCode = (String) deviceCodeResponse.get("device_code");
        String userCode = (String) deviceCodeResponse.get("user_code");
        String verificationUrl = (String) deviceCodeResponse.get("verification_uri");
        int interval = ((Number) deviceCodeResponse.get("interval")).intValue();

        // Show auth dialog to user
        onAuthRequired.accept(verificationUrl, userCode);

        // Step 2: Poll for token
        String tokenEndpoint = AUTHORITY + "/" + tenantId + "/oauth2/v2.0/token";

        while (true) {
            Thread.sleep(interval * 1000L);

            URL tokenUrl = new URL(tokenEndpoint);
            HttpURLConnection tokenConn = (HttpURLConnection) tokenUrl.openConnection();
            tokenConn.setRequestMethod("POST");
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            tokenConn.setDoOutput(true);

            String tokenRequestBody = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                    "&grant_type=urn:ietf:params:oauth:grant-type:device_code" +
                    "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);

            try (OutputStream os = tokenConn.getOutputStream()) {
                os.write(tokenRequestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = tokenConn.getResponseCode();

            if (responseCode == 200) {
                Map<String, Object> tokenResponse = readJsonResponseAsMap(tokenConn);
                return parseTokenResponse(tokenResponse, clientId, tenantId, email);
            }

            // Check error
            Map<String, Object> errorResponse = readJsonResponseAsMap(tokenConn);
            String error = (String) errorResponse.getOrDefault("error", "");

            if (error.equals("authorization_pending")) {
                onLog.accept("Waiting for user authentication...");
                continue;
            } else if (error.equals("slow_down")) {
                interval += 5;
                continue;
            } else {
                throw new Exception("Authentication failed: " + error);
            }
        }
    }

    /**
     * Refresh access token using refresh token
     */
    private TokenInfo refreshAccessToken(TokenInfo oldToken, Consumer<String> onLog) {
        try {
            String tokenEndpoint = AUTHORITY + "/" + oldToken.getTenantId() + "/oauth2/v2.0/token";

            URL url = new URL(tokenEndpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            String requestBody = "client_id=" + URLEncoder.encode(oldToken.getClientId(), StandardCharsets.UTF_8) +
                    "&grant_type=refresh_token" +
                    "&refresh_token=" + URLEncoder.encode(oldToken.getRefreshToken(), StandardCharsets.UTF_8) +
                    "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                Map<String, Object> response = readJsonResponseAsMap(conn);
                return parseTokenResponse(response, oldToken.getClientId(), oldToken.getTenantId(), oldToken.getEmail());
            } else {
                Map<String, Object> errorResponse = readJsonResponseAsMap(conn);
                onLog.accept("Refresh failed: " + errorResponse);
                return null;
            }

        } catch (Exception e) {
            onLog.accept("Refresh error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parse token response from Microsoft
     */
    private TokenInfo parseTokenResponse(Map<String, Object> response, String clientId, String tenantId, String email) {
        TokenInfo token = new TokenInfo();
        token.setAccessToken((String) response.get("access_token"));
        token.setRefreshToken((String) response.get("refresh_token"));

        int expiresIn = ((Number) response.get("expires_in")).intValue();
        token.setExpiresAt(Instant.now().getEpochSecond() + expiresIn);

        token.setClientId(clientId);
        token.setTenantId(tenantId);
        token.setEmail(email);

        return token;
    }

    /**
     * Read JSON response from HttpURLConnection as Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonResponseAsMap(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getResponseCode() < 400 ?
                conn.getInputStream() : conn.getErrorStream();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return mapper.readValue(response.toString(), HashMap.class);
        }
    }

    /**
     * Save token to disk
     */
    private void saveToken(TokenInfo token) {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(token);
            Files.writeString(TOKEN_FILE, json);
            System.out.println("Token saved to: " + TOKEN_FILE.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to save token: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Load token from disk
     */
    private TokenInfo loadToken() {
        try {
            if (Files.exists(TOKEN_FILE)) {
                String json = Files.readString(TOKEN_FILE);
                TokenInfo token = mapper.readValue(json, TokenInfo.class);
                System.out.println("Token loaded from: " + TOKEN_FILE.toAbsolutePath());
                return token;
            } else {
                System.out.println("No token file found at: " + TOKEN_FILE.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Failed to load token: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Clear cached token
     */
    public void clearCache() {
        try {
            Files.deleteIfExists(TOKEN_FILE);
            System.out.println("Token cache cleared");
        } catch (Exception e) {
            System.err.println("Failed to clear cache: " + e.getMessage());
        }
    }
}