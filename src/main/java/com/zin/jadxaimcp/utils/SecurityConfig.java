package com.zin.jadxaimcp.utils;

import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

public class SecurityConfig {
    public static final String LOOPBACK_HOST = "127.0.0.1";
    public static final String AUTH_HEADER = "Authorization";
    public static final String TOKEN_ENV = "JADX_AI_MCP_TOKEN";
    public static final String TOKEN_PROPERTY = "jadx.ai.mcp.token";
    public static final String AUTH_DISABLED_ENV = "JADX_AI_MCP_AUTH_DISABLED";
    public static final String AUTH_DISABLED_PROPERTY = "jadx.ai.mcp.auth.disabled";
    public static final String REFACTOR_DISABLED_ENV = "JADX_AI_MCP_DISABLE_REFACTOR";
    public static final String REFACTOR_DISABLED_PROPERTY = "jadx.ai.mcp.refactor.disabled";
    public static final String DEBUG_DISABLED_ENV = "JADX_AI_MCP_DISABLE_DEBUG";
    public static final String DEBUG_DISABLED_PROPERTY = "jadx.ai.mcp.debug.disabled";

    private static final String PREF_TOKEN = "jadx_ai_mcp_bearer_token";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String bearerToken;
    private final String tokenSource;
    private final boolean authDisabled;
    private final boolean refactorDisabled;
    private final boolean debugDisabled;

    private SecurityConfig(
            String bearerToken,
            String tokenSource,
            boolean authDisabled,
            boolean refactorDisabled,
            boolean debugDisabled) {
        this.bearerToken = bearerToken;
        this.tokenSource = tokenSource;
        this.authDisabled = authDisabled;
        this.refactorDisabled = refactorDisabled;
        this.debugDisabled = debugDisabled;
    }

    public static SecurityConfig load(Preferences preferences) {
        boolean authDisabled = readBoolean(AUTH_DISABLED_PROPERTY, AUTH_DISABLED_ENV, false);
        boolean refactorDisabled = readBoolean(REFACTOR_DISABLED_PROPERTY, REFACTOR_DISABLED_ENV, false);
        boolean debugDisabled = readBoolean(DEBUG_DISABLED_PROPERTY, DEBUG_DISABLED_ENV, false);

        TokenValue tokenValue = resolveToken(preferences);
        return new SecurityConfig(
                tokenValue.value,
                tokenValue.source,
                authDisabled,
                refactorDisabled,
                debugDisabled);
    }

    public boolean isAuthorized(Context ctx) {
        if (authDisabled) {
            return true;
        }
        String authorization = ctx.header(AUTH_HEADER);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        String presentedToken = authorization.substring("Bearer ".length()).trim();
        return constantTimeEquals(bearerToken, presentedToken);
    }

    public Map<String, Object> statusForHealth() {
        return Map.of(
                "bind_host", LOOPBACK_HOST,
                "auth_required", !authDisabled,
                "token_source", tokenSource,
                "refactor_disabled", refactorDisabled,
                "debug_disabled", debugDisabled);
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public String getTokenSource() {
        return tokenSource;
    }

    public boolean isAuthDisabled() {
        return authDisabled;
    }

    public boolean isRefactorDisabled() {
        return refactorDisabled;
    }

    public boolean isDebugDisabled() {
        return debugDisabled;
    }

    private static TokenValue resolveToken(Preferences preferences) {
        String propertyToken = trimToNull(System.getProperty(TOKEN_PROPERTY));
        if (propertyToken != null) {
            return new TokenValue(propertyToken, "system-property");
        }

        String envToken = trimToNull(System.getenv(TOKEN_ENV));
        if (envToken != null) {
            return new TokenValue(envToken, "environment");
        }

        String persistedToken = trimToNull(preferences.get(PREF_TOKEN, null));
        if (persistedToken != null) {
            return new TokenValue(persistedToken, "java-preferences");
        }

        String generatedToken = generateToken();
        preferences.put(PREF_TOKEN, generatedToken);
        return new TokenValue(generatedToken, "java-preferences-generated");
    }

    private static String generateToken() {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private static boolean readBoolean(String propertyName, String envName, boolean defaultValue) {
        String propertyValue = trimToNull(System.getProperty(propertyName));
        if (propertyValue != null) {
            return parseBoolean(propertyValue, defaultValue);
        }
        String envValue = trimToNull(System.getenv(envName));
        if (envValue != null) {
            return parseBoolean(envValue, defaultValue);
        }
        return defaultValue;
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static class TokenValue {
        private final String value;
        private final String source;

        private TokenValue(String value, String source) {
            this.value = value;
            this.source = source;
        }
    }
}
