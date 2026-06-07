package com.zin.jadxaimcp.server;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;

import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Optional;

public class GuiJadxProjectContext implements JadxProjectContext {
    private final MainWindow mainWindow;

    public GuiJadxProjectContext(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
    }

    @Override
    public List<JavaClass> getIncludedClassesWithInners() {
        return wrapper().getIncludedClassesWithInners();
    }

    @Override
    public List<ResourceFile> getResources() {
        return wrapper().getResources();
    }

    @Override
    public JadxArgs getArgs() {
        return wrapper().getArgs();
    }

    @Override
    public JadxDecompiler getDecompiler() {
        return wrapper().getDecompiler();
    }

    @Override
    public String getMode() {
        return "gui";
    }

    @Override
    public Optional<CurrentClassView> getCurrentClassView() {
        if (mainWindow == null || mainWindow.getTabbedPane() == null) {
            return Optional.empty();
        }
        int index = mainWindow.getTabbedPane().getSelectedIndex();
        if (index == -1) {
            return Optional.empty();
        }
        Component component = mainWindow.getTabbedPane().getSelectedComponent();
        JTextArea textArea = findTextArea(component);
        String title = mainWindow.getTabbedPane().getTitleAt(index);
        String name = title != null ? title.replace(".java", "") : "unknown";
        String content = textArea != null ? textArea.getText() : "";
        return Optional.of(new CurrentClassView(name, content));
    }

    @Override
    public Optional<String> getSelectedText() {
        if (mainWindow == null || mainWindow.getTabbedPane() == null) {
            return Optional.empty();
        }
        Component component = mainWindow.getTabbedPane().getSelectedComponent();
        JTextArea textArea = findTextArea(component);
        if (textArea == null || textArea.getSelectedText() == null) {
            return Optional.empty();
        }
        return Optional.of(textArea.getSelectedText());
    }

    public MainWindow getMainWindow() {
        return mainWindow;
    }

    private JadxWrapper wrapper() {
        if (mainWindow == null || mainWindow.getWrapper() == null) {
            throw new IllegalStateException("JadxWrapper not initialized");
        }
        return mainWindow.getWrapper();
    }

    private JTextArea findTextArea(Component component) {
        if (component instanceof JTextArea) {
            return (JTextArea) component;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                JTextArea found = findTextArea(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
