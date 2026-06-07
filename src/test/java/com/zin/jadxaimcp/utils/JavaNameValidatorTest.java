package com.zin.jadxaimcp.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaNameValidatorTest {
    @Test
    void acceptsValidIdentifiers() {
        assertNull(JavaNameValidator.validateIdentifier("CryptoHelper", "new_name"));
        assertNull(JavaNameValidator.validateIdentifier("_local1", "new_name"));
    }

    @Test
    void rejectsInvalidIdentifiers() {
        assertNotNull(JavaNameValidator.validateIdentifier("1Crypto", "new_name"));
        assertNotNull(JavaNameValidator.validateIdentifier("class", "new_name"));
        assertNotNull(JavaNameValidator.validateIdentifier("bad-name", "new_name"));
        assertNotNull(JavaNameValidator.validateIdentifier("bad\nname", "new_name"));
    }

    @Test
    void acceptsValidQualifiedNames() {
        assertNull(JavaNameValidator.validateQualifiedName("com.example.network", "new_package_name"));
        assertNull(JavaNameValidator.validateQualifiedName("a.b.c", "old_package_name"));
    }

    @Test
    void rejectsInvalidQualifiedNames() {
        assertNotNull(JavaNameValidator.validateQualifiedName(".com.example", "new_package_name"));
        assertNotNull(JavaNameValidator.validateQualifiedName("com..example", "new_package_name"));
        assertNotNull(JavaNameValidator.validateQualifiedName("com.example.class", "new_package_name"));
        assertNotNull(JavaNameValidator.validateQualifiedName("com.example.bad-name", "new_package_name"));
    }
}
