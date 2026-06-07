package com.zin.jadxaimcp.server;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;

import java.util.List;
import java.util.Optional;

public interface JadxProjectContext extends AutoCloseable {
    List<JavaClass> getIncludedClassesWithInners();

    List<ResourceFile> getResources();

    JadxArgs getArgs();

    JadxDecompiler getDecompiler();

    String getMode();

    default boolean isHeadless() {
        return false;
    }

    default Optional<CurrentClassView> getCurrentClassView() {
        return Optional.empty();
    }

    default Optional<String> getSelectedText() {
        return Optional.empty();
    }

    @Override
    default void close() {
    }
}
