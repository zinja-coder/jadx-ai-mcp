// -------------------------- helper methods to assist the request handler methods -------------------------- //
    private String getSelectedTabTitle() {
        JTabbedPane tabs = mainWindow.getTabbedPane();
        int index = tabs.getSelectedIndex();
        return (index != -1) ? tabs.getTitleAt(index) : null;
    }

    private String extractTextFromCurrentTab() {
        Component selectedComponent = mainWindow.getTabbedPane().getSelectedComponent();
        JTextArea textArea = findTextArea(selectedComponent);
        return textArea != null ? textArea.getText() : null;
    }

    private JTextArea findTextArea(Component component) {
        if (component instanceof JTextArea)
            return (JTextArea) component;
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                JTextArea result = findTextArea(child);
                if (result != null)
                    return result;
            }
        }
        return null;
    }

    // reusing jadx's secure xml parsing logic for parsing manifest xml file
    // this code is taken from jadx -
    // https://github.com/skylot/jadx/blob/47647bbb9a9a3cd3150705e09cc1f84a5e9f0be6/jadx-core/src/main/java/jadx/core/utils/android/AndroidManifestParser.java#L214
    private Document parseManifestXml(String xmlContent, IJadxSecurity security) {
        try (InputStream xmlStream = new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8))) {
            Document doc = security.parseXml(xmlStream);
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            throw new JadxRuntimeException("Failed to parse AndroidManifest.xml", e);
        }
    }