package com.zin.jadxaimcp.server;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HeadlessJadxProjectContext implements JadxProjectContext {
    private final JadxDecompiler decompiler;

    private HeadlessJadxProjectContext(JadxDecompiler decompiler) {
        this.decompiler = decompiler;
    }

    public static HeadlessJadxProjectContext open(List<File> inputFiles, int threadsCount) {
        if (inputFiles == null || inputFiles.isEmpty()) {
            throw new IllegalArgumentException("At least one JADX input file is required");
        }
        JadxArgs args = new JadxArgs();
        args.setInputFiles(inputFiles);
        args.setSkipFilesSave(true);
        args.setRunDebugChecks(false);
        args.setDisabledPlugins(new HashSet<>(Set.of("jadx-ai-mcp")));
        if (threadsCount > 0) {
            args.setThreadsCount(threadsCount);
        }

        JadxDecompiler decompiler = new JadxDecompiler(args);
        try {
            decompiler.load();
            return new HeadlessJadxProjectContext(decompiler);
        } catch (RuntimeException e) {
            decompiler.close();
            throw e;
        }
    }

    @Override
    public List<JavaClass> getIncludedClassesWithInners() {
        return decompiler.getClassesWithInners();
    }

    @Override
    public List<ResourceFile> getResources() {
        return decompiler.getResources();
    }

    @Override
    public JadxArgs getArgs() {
        return decompiler.getArgs();
    }

    @Override
    public JadxDecompiler getDecompiler() {
        return decompiler;
    }

    @Override
    public String getMode() {
        return "headless";
    }

    @Override
    public boolean isHeadless() {
        return true;
    }

    @Override
    public void close() {
        decompiler.close();
    }
}
