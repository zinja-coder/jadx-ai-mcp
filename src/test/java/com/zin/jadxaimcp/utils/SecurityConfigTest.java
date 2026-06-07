package com.zin.jadxaimcp.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigTest {
    @AfterEach
    void clearProperties() {
        System.clearProperty(SecurityConfig.TOKEN_PROPERTY);
        System.clearProperty(SecurityConfig.AUTH_DISABLED_PROPERTY);
        System.clearProperty(SecurityConfig.REFACTOR_DISABLED_PROPERTY);
        System.clearProperty(SecurityConfig.DEBUG_DISABLED_PROPERTY);
    }

    @Test
    void usesSystemPropertyTokenBeforePreferences() throws Exception {
        Preferences prefs = Preferences.userRoot().node("/com/zin/jadxaimcp/test/property-token");
        prefs.clear();
        prefs.put("jadx_ai_mcp_bearer_token", "stored-token");
        System.setProperty(SecurityConfig.TOKEN_PROPERTY, "property-token");

        SecurityConfig config = SecurityConfig.load(prefs);

        assertEquals("property-token", config.getBearerToken());
        assertEquals("system-property", config.getTokenSource());
        prefs.removeNode();
    }

    @Test
    void generatesAndPersistsTokenWhenUnset() throws Exception {
        Preferences prefs = Preferences.userRoot().node("/com/zin/jadxaimcp/test/generated-token");
        prefs.clear();

        SecurityConfig first = SecurityConfig.load(prefs);
        SecurityConfig second = SecurityConfig.load(prefs);

        assertNotNull(first.getBearerToken());
        assertNotEquals("", first.getBearerToken());
        assertEquals(first.getBearerToken(), second.getBearerToken());
        assertEquals("java-preferences-generated", first.getTokenSource());
        assertEquals("java-preferences", second.getTokenSource());
        prefs.removeNode();
    }

    @Test
    void readsDisableSwitchesFromSystemProperties() throws Exception {
        Preferences prefs = Preferences.userRoot().node("/com/zin/jadxaimcp/test/disable-switches");
        prefs.clear();
        System.setProperty(SecurityConfig.AUTH_DISABLED_PROPERTY, "true");
        System.setProperty(SecurityConfig.REFACTOR_DISABLED_PROPERTY, "1");
        System.setProperty(SecurityConfig.DEBUG_DISABLED_PROPERTY, "yes");

        SecurityConfig config = SecurityConfig.load(prefs);

        assertTrue(config.isAuthDisabled());
        assertTrue(config.isRefactorDisabled());
        assertTrue(config.isDebugDisabled());
        assertFalse((Boolean) config.statusForHealth().get("auth_required"));
        prefs.removeNode();
    }
}
