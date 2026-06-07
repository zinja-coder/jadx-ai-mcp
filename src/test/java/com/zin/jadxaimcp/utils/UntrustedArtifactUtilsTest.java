package com.zin.jadxaimcp.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UntrustedArtifactUtilsTest {
    @Test
    void withMetadataAddsSafetyFields() {
        Map<String, Object> result = UntrustedArtifactUtils.withMetadata(Map.of("content", "apk-data"));

        assertEquals(true, result.get("untrusted_artifact"));
        assertEquals(UntrustedArtifactUtils.WARNING, result.get("llm_safety_notice"));
        assertEquals("apk-data", result.get("content"));
    }

    @Test
    void labelTextPreservesWarningInBody() {
        String labeled = UntrustedArtifactUtils.labelText("class Evil {}");

        assertTrue(labeled.startsWith("[UNTRUSTED APK ARTIFACT DATA]"));
        assertTrue(labeled.contains(UntrustedArtifactUtils.WARNING));
        assertTrue(labeled.endsWith("class Evil {}"));
    }
}
