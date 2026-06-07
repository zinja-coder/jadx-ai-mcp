package com.zin.jadxaimcp.utils;

import java.util.Set;

public class JavaNameValidator {
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_QUALIFIED_NAME_LENGTH = 512;
    private static final Set<String> RESERVED_WORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false",
            "null", "_");

    private JavaNameValidator() {
    }

    public static String validateIdentifier(String value, String fieldName) {
        String commonError = validateCommon(value, fieldName, MAX_IDENTIFIER_LENGTH);
        if (commonError != null) {
            return commonError;
        }
        if (RESERVED_WORDS.contains(value)) {
            return fieldName + " must not be a Java reserved word";
        }
        if (!Character.isJavaIdentifierStart(value.charAt(0))) {
            return fieldName + " must start with a valid Java identifier character";
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return fieldName + " contains an invalid Java identifier character";
            }
        }
        return null;
    }

    public static String validateQualifiedName(String value, String fieldName) {
        String commonError = validateCommon(value, fieldName, MAX_QUALIFIED_NAME_LENGTH);
        if (commonError != null) {
            return commonError;
        }
        if (value.startsWith(".") || value.endsWith(".") || value.contains("..")) {
            return fieldName + " must be a dot-separated Java package or class name";
        }
        for (String part : value.split("\\.")) {
            String partError = validateIdentifier(part, fieldName);
            if (partError != null) {
                return partError;
            }
        }
        return null;
    }

    public static String validateOpaqueParameter(String value, String fieldName) {
        return validateCommon(value, fieldName, MAX_QUALIFIED_NAME_LENGTH);
    }

    private static String validateCommon(String value, String fieldName, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return fieldName + " is required";
        }
        if (!value.equals(value.trim())) {
            return fieldName + " must not include leading or trailing whitespace";
        }
        if (value.length() > maxLength) {
            return fieldName + " is too long";
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) {
                return fieldName + " must not include control characters";
            }
        }
        return null;
    }
}
