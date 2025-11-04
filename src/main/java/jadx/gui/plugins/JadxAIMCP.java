/* 
 *Copyright (c) 2025 Jadx AI MCP developer(s) (https://github.com/zinja-coder/jadx-ai-mcp)
 *See the file 'LICENSE' for copying permission
*/

// TO DO break down code into smaller files

package jadx.gui.plugins;

import io.javalin.Javalin;
import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.ResourceFile;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.JadxPluginInfoBuilder;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.api.security.IJadxSecurity;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.utils.android.AppAttribute;
import jadx.core.utils.android.ApplicationParams;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.core.xmlgen.ResContainer;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;
import jadx.gui.settings.JadxSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Document;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.prefs.Preferences;
import java.util.function.Function;

// Importing custom banner string
import jadx.gui.plugins.JadxAIMCPBanner;
// Importing pagination utils
import jadx.gui.plugins.JadxAIMCPPaginationUtils;
// http request handlers
import jadx.gui.plugins.JadxAIMCPHandlers;

public class JadxAIMCP implements JadxPlugin {
    private MainWindow mainWindow;
    private Javalin app;
    private static final Logger logger = LoggerFactory.getLogger(JadxAIMCP.class);
    public static final String PLUGIN_ID = "jadx-ai-mcp";
    private ScheduledExecutorService scheduler;
    private volatile boolean serverStarted = false;
    private static final int MAX_STARTUP_ATTEMPTS = 30; // 30 seconds max wait
    private static final int CHECK_INTERVAL_SECONDS = 1;
    private static final String PREF_KEY_PORT = "jadx_ai_mcp_port";
    private static final int DEFAULT_PORT = 8650;
    private int currentPort = DEFAULT_PORT;
    private Preferences prefs;
    private JadxAIMCPPaginationUtils paginationUtils;
    private JadxAIMCPHandlers jadxAIMCPHandlers;

    @Override
    public void init(JadxPluginContext context) {
        // first check for GUI context, if not then exit gracefully
        if (context.getGuiContext() == null) {
            logger.info("JADX-AI-MCP Plugin: Running in non-GUI mode, plugin features disabled.");
            return;
        }

        try {
            // now safe to use GUI context
            this.mainWindow = (MainWindow) context.getGuiContext().getMainFrame();
            if (this.mainWindow == null) {
                logger.error("JADX-AI-MCP Plugin: Main window is null. JADX AI MCP will not start.");
                return;
            }

            // Initializing Preferences
            prefs = Preferences.userNodeForPackage(JadxAIMCP.class);
            currentPort = prefs.getInt(PREF_KEY_PORT, DEFAULT_PORT);

            // Initializing pagination utils
            paginationUtils = new JadxAIMCPPaginationUtils();

            // Initializing http request handlers
            jadxAIMCPHandlers = new JadxAIMCPHandlers();

            // Add menu items for port options
            addMenuItems();

            logger.info("JADX-AI-MCP Plugin: Initializing and waiting for JADX to fully load...");

            // Initialize scheduler for delayed startup
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "JADX-AI-MCP-Startup");
                t.setDaemon(true);
                return t;
            });

            // Start the delayed initialization process
            startDelayedInitialization();

        } catch (Exception e) {
            logger.error("JADX-AI-MCP Plugin: Initialization error: " + e.getMessage(), e);
        }
    }

    @Override
    public JadxPluginInfo getPluginInfo() {
        return JadxPluginInfoBuilder.pluginId(PLUGIN_ID)
                .name("JADX-AI-MCP Plugin")
                .description("Integrates MCP Server support for JADX")
                .homepage("https://github.com/zinja-coder/jadx-ai-mcp")
                .requiredJadxVersion("1.5.1, r2333")
                .build();
    }

    // public no-argument constructor
    public JadxAIMCP() {
        // empty constructor
    }

    // Starts delayed initialization process that waits for JADX to fully load
    private void startDelayedInitialization() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (serverStarted) {
                    scheduler.shutdown();
                    return;
                }

                if (isJadxFullyLoaded()) {
                    logger.info("JADX-AI-MCP Plugin: JADX fully loaded, starting HTTP server...");
                    start();
                    serverStarted = true;
                    scheduler.shutdown();
                } else {
                    logger.debug("JADX-AI-MCP Plugin: Waiting for JADX to fully load...");
                }
            } catch (Exception e) {
                logger.error("JADX-AI-MCP Plugin: Error during delayed initialization: " + e.getMessage(), e);
            }
        }, 2, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS); // Start after 2 seconds, then check every 1 second

        // Schedule timeout to prevent indefinite waiting
        scheduler.schedule(() -> {
            if (!serverStarted) {
                logger.warn("JADX-AI-MCP Plugin: Timeout waiting for JADX to load. Starting server anyway...");
                try {
                    start();
                    serverStarted = true;
                } catch (Exception e) {
                    logger.error("JADX-AI-MCP Plugin: Failed to start server after timeout: " + e.getMessage(), e);
                }
            }
        }, MAX_STARTUP_ATTEMPTS, TimeUnit.SECONDS);
    }

    // Checks if JADX has fully loaded and has valid data to work with
    private boolean isJadxFullyLoaded() {
        try {
            if (mainWindow == null) {
                return false;
            }

            JadxWrapper wrapper = mainWindow.getWrapper();
            if (wrapper == null) {
                logger.debug("JADX-AI-MCP Plugin: JadxWrapper is null, not ready yet");
                return false;
            }

            // Check if wrapper is properly initialized and has classes
            List<JavaClass> classes = wrapper.getIncludedClassesWithInners();
            if (classes == null) {
                logger.debug("JADX-AI-MCP Plugin: Classes list is null, not ready yet");
                return false;
            }

            // Check if we have at least some content (even if it's just an empty APK)
            // This ensures the decompiler has finished its initial processing
            boolean hasDecompilerData = wrapper.getDecompiler() != null;

            if (!hasDecompilerData) {
                logger.debug("JADX-AI-MCP Plugin: Decompiler not ready yet");
                return false;
            }

            logger.debug("JADX-AI-MCP Plugin: Found {} classes, JADX appears to be loaded", classes.size());
            return true;

        } catch (Exception e) {
            logger.debug("JADX-AI-MCP Plugin: Exception during readiness check: " + e.getMessage());
            return false;
        }
    }

    // Cleanup method to properly shutdown the server and scheduler
    public void shutdown() {
        try {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown();
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            }

            if (app != null) {
                app.stop();
                logger.info("JADX-AI-MCP Plugin: HTTP Server stopped");
            }
        } catch (Exception e) {
            logger.error("JADX-AI-MCP Plugin: Error during shutdown: " + e.getMessage(), e);
        }
    }

    public void start() {
        try {
            app = Javalin.create().start(currentPort);

            // Setup routes
            app.get("/current-class", this::handleCurrentClass);
            app.get("/all-classes", this::handleAllClasses);
            app.get("/selected-text", this::handleSelectedText);
            app.get("/method-by-name", this::handleMethodByName);
            app.get("/class-source", this::handleClassSource);
            app.get("/search-method", this::handleSearchMethod);
            app.get("/methods-of-class", this::handleMethodsOfClass);
            app.get("/fields-of-class", this::handleFieldsOfClass);
            app.get("/smali-of-class", this::handleSmaliOfClass);
            app.get("/manifest", this::handleManifest);
            app.get("/main-application-classes-code", this::handleMainApplicationClassesCode);
            app.get("/main-application-classes-names", this::handleMainApplicationClassesNames);
            app.get("/main-activity", this::handleMainActivity);
            app.get("/strings", this::handleStrings);
            app.get("/list-all-resource-files-names", this::handleListAllResourceFilesNames);
            app.get("/get-resource-file", this::handleGetResourceFile);
            app.get("/rename-class", this::handleRenameClass);
            app.get("/rename-method", this::handleRenameMethod);
            app.get("/rename-field", this::handleRenameField);
            app.get("/health", ctx -> jadxAIMCPHandlers.handleHealth(serverStarted, app, currentPort));
        
            logger.info(JadxAIMCPBanner.banner);
            logger.info(
                    "// -------------------- JADX AI MCP PLUGIN -------------------- //\n\n");
            logger.info("JADX AI MCP Plugin HTTP Server Started at http://127.0.0.1:" + currentPort + "/");
        } catch (Exception e) {
            logger.error("JADX-AI-MCP Plugin Error: Could not start HTTP Server on. Exception: "
                    + e.getMessage().toString());
        }
    }

    // Add menu items to JADX's menu bar
    private void addMenuItems() {
        SwingUtilities.invokeLater(() -> {
            try {
                JMenuBar menuBar = mainWindow.getJMenuBar();
                if (menuBar == null) {
                    logger.warn("JADX-AI-MCP Plugin: Menu bar not found, cannot add menu items");
                    return;
                }

                // Look for existing Plugins menu or create one
                JMenu pluginsMenu = findOrCreatePluginsMenu(menuBar);

                // Add Jadx AI MCP submenu
                JMenu jadxAIMcpMenu = new JMenu("JADX AI MCP Server");

                // Configure Port menu item
                JMenuItem configurePortItem = new JMenuItem("Configure Port...");
                configurePortItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        showPortConfigDialog();
                    }
                });

                // Restart Server menu item
                JMenuItem restartServerItem = new JMenuItem("Restart Server");
                restartServerItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        restartServer();
                    }
                });

                // set back to default port
                JMenuItem setDefaultPortItem = new JMenuItem("Default Port");
                setDefaultPortItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        setToDefautlPort();
                    }
                });

                // Server Status menu item
                JMenuItem serverStatusItem = new JMenuItem("Server Status");
                serverStatusItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        showServerStatus();
                    }
                });

                jadxAIMcpMenu.add(configurePortItem);
                jadxAIMcpMenu.addSeparator();
                jadxAIMcpMenu.add(setDefaultPortItem);
                jadxAIMcpMenu.add(restartServerItem);
                jadxAIMcpMenu.add(serverStatusItem);

                pluginsMenu.add(jadxAIMcpMenu);

                logger.info("JADX-AI-MCP Plugin: Menu items added successfully");

            } catch (Exception e) {
                logger.error("JADX-AI-MCP Plugin: Error adding menu items: " + e.getMessage(), e);
            }
        });
    }

    // Find existing Plugins menu or create a new one
    private JMenu findOrCreatePluginsMenu(JMenuBar menuBar) {
        // Look for existing "Plugins" menu
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            JMenu menu = menuBar.getMenu(i);
            if (menu != null && ("Plugins".equals(menu.getText()) || "Plugin".equals(menu.getText()))) {
                return menu;
            }
        }

        // If no Plugins menu found, create one and add it before Help menu
        JMenu pluginsMenu = new JMenu("Plugins");

        // Try to insert before Help menu, otherwise add at the end
        boolean inserted = false;
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            JMenu menu = menuBar.getMenu(i);
            if (menu != null && "Help".equals(menu.getText())) {
                menuBar.add(pluginsMenu, i);
                inserted = true;
                break;
            }
        }

        if (!inserted) {
            menuBar.add(pluginsMenu);
        }

        return pluginsMenu;
    }

    // set back to default port
    private void setToDefautlPort() {
        currentPort = 8650;
        prefs.putInt(PREF_KEY_PORT, currentPort);

        JOptionPane.showMessageDialog(
                mainWindow,
                "Port updated to " + currentPort + ". Server will restart automatically.",
                "Port Updated",
                JOptionPane.INFORMATION_MESSAGE);

        restartServer();
    }

    // Show port configuration dialog
    private void showPortConfigDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(new JLabel("Server Port:"), gbc);

        JTextField portField = new JTextField(String.valueOf(currentPort), 10);
        gbc.gridx = 1;
        panel.add(portField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("<html><i>Valid range: 1024-65535</i></html>"), gbc);

        gbc.gridy = 2;
        panel.add(new JLabel("<html><i>Current port: " + currentPort + "</i></html>"), gbc);

        int result = JOptionPane.showConfirmDialog(
                mainWindow,
                panel,
                "Configure AI MCP Server Port",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            try {
                int newPort = Integer.parseInt(portField.getText().trim());

                if (newPort < 1024 || newPort > 65535) {
                    JOptionPane.showMessageDialog(
                            mainWindow,
                            "Port must be between 1024 and 65535",
                            "Invalid Port",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }

                if (newPort != currentPort) {
                    currentPort = newPort;
                    prefs.putInt(PREF_KEY_PORT, currentPort);

                    JOptionPane.showMessageDialog(
                            mainWindow,
                            "Port updated to " + currentPort + ". Server will restart automatically.",
                            "Port Updated",
                            JOptionPane.INFORMATION_MESSAGE);

                    restartServer();
                }

            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(
                        mainWindow,
                        "Please enter a valid port number",
                        "Invalid Port",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // Restart the server with the current port
    private void restartServer() {
        new Thread(() -> {
            try {
                logger.info("JADX-AI-MCP Plugin: Restarting server on port " + currentPort);

                // Stop existing server
                if (app != null) {
                    app.stop();
                    app = null;
                    serverStarted = false;
                }

                // Small delay to ensure port is released
                Thread.sleep(1000);

                // Start new server
                start();

                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(
                            mainWindow,
                            "AI MCP Server restarted successfully on port " + currentPort,
                            "Server Restarted",
                            JOptionPane.INFORMATION_MESSAGE);
                });

            } catch (Exception e) {
                logger.error("JADX-AI-MCP Plugin: Error restarting server: " + e.getMessage(), e);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(
                            mainWindow,
                            "Failed to restart server: " + e.getMessage(),
                            "Server Restart Error",
                            JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "JADX-AI-MCP-Restart").start();
    }

    // Show server status dialog
    private void showServerStatus() {
        String status = serverStarted && app != null ? "Running" : "Stopped";
        String url = serverStarted ? "http://127.0.0.1:" + currentPort + "/" : "N/A";

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Status:"), gbc);
        gbc.gridx = 1;
        panel.add(new JLabel(status), gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(new JLabel("Port:"), gbc);
        gbc.gridx = 1;
        panel.add(new JLabel(String.valueOf(currentPort)), gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        panel.add(new JLabel("URL:"), gbc);
        gbc.gridx = 1;
        panel.add(new JLabel(url), gbc);

        JOptionPane.showMessageDialog(
                mainWindow,
                panel,
                "AI MCP Server Status",
                JOptionPane.INFORMATION_MESSAGE);
    }

    
}
