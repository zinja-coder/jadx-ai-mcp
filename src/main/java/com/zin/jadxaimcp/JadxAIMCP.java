/* 
 * Copyright (c) 2025 Jadx AI MCP developer(s) (https://github.com/zinja-coder/jadx-ai-mcp)
 * See the file 'LICENSE' for copying permission
*/

package com.zin.jadxaimcp;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.JadxPluginInfoBuilder;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

// Custom imports removed
import com.zin.jadxaimcp.ui.PluginMenu;
import com.zin.jadxaimcp.server.PluginServer;
import com.zin.jadxaimcp.utils.SecurityConfig;

public class JadxAIMCP implements JadxPlugin {
    public static final String PLUGIN_ID = "jadx-ai-mcp";
    private static final Logger logger = LoggerFactory.getLogger(JadxAIMCP.class);
    private static final String PREF_KEY_PORT = "jadx_ai_mcp_port";
    private static final int DEFAULT_PORT = 8650;

    // Keep track of the active plugin instance to handle multiple instantiations
    // correctly
    private static JadxAIMCP activeInstance = null;

    // Config & State
    private int currentPort = DEFAULT_PORT;
    private Preferences prefs;
    private ScheduledExecutorService scheduler;

    // Components
    private MainWindow mainWindow;
    private PluginServer pluginServer;
    private PluginMenu pluginMenu;

    public JadxAIMCP() {
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

    @Override
    public void init(JadxPluginContext context) {
        if (context.getGuiContext() == null) {
            logger.info("JADX-AI-MCP Plugin: Running in non-GUI mode, plugin features disabled.");
            return;
        }

        // Cleanup previous instance if JADX initializes the plugin multiple times
        if (activeInstance != null) {
            activeInstance.cleanup();
        }
        activeInstance = this;

        try {
            this.mainWindow = (MainWindow) context.getGuiContext().getMainFrame();
            if (this.mainWindow == null) {
                logger.error("JADX-AI-MCP Plugin: Main Window is null.");
                return;
            }

            // 1. Initialize Config
            prefs = Preferences.userNodeForPackage(JadxAIMCP.class);
            currentPort = prefs.getInt(PREF_KEY_PORT, DEFAULT_PORT);

            // 2. Initialize UI
            this.pluginMenu = new PluginMenu(mainWindow, this);
            this.pluginMenu.addMenuItems();

            // 3. Start Server Lifecycle
            logger.info("JADX-AI-MCP Plugin: Initializing...");
            startDelayedInitialization();
        } catch (Exception e) {
            logger.error("JADX-AI-MCP Plugin: Initialization error: " + e.getMessage(), e);
        }
    }

    // ---- Server lifecycle management ---

    /**
     * @return void
     * 
     *         This method initializes the delayed server startup mechanism using a
     *         scheduled executor.
     * 
     *         1. It creates a daemon thread executor for background initialization
     *         tasks
     *         2. It schedules a periodic check (every 1 second after 2 second
     *         initial delay) to verify:
     *         - Whether the server is already running (exits if true)
     *         - Whether JADX has fully loaded its content (starts server if true)
     *         3. It implements a fallback timeout of 30 seconds that forces server
     *         start if JADX hasn't
     *         loaded by then to prevent indefinite waiting
     *         4. Once the server starts successfully, the scheduler shuts down
     * 
     *         This delayed initialization is necessary because the plugin may load
     *         before Jadx completes
     *         loading the APK content, and the server requires access to decompled
     *         classes.
     */
    private void startDelayedInitialization() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JADX-AI-MCP-Startup");
            t.setDaemon(true);
            return t;
        });

        // Wait for JADX to load content before starting server
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (isServerRunning()) {
                    scheduler.shutdown();
                    return;
                }
                if (isJadxFullyLoaded()) {
                    logger.info("JADX-AI-MCP Plugin: JADX ready, starting server...");
                    startServer();
                    scheduler.shutdown();
                }
            } catch (Exception e) {
                logger.error("JADX-AI-MCP Plugin: Init error: " + e.getMessage());
            }
        }, 2, 1, TimeUnit.SECONDS);

        // Fallback timeout (30s)
        scheduler.schedule(() -> {
            if (!isServerRunning()) {
                logger.warn("JADX-AI-MCP Plugin: Timeout waiting for JADX. Forcing start...");
                startServer();
            }
        }, 30, TimeUnit.SECONDS);
    }

    /**
     * @return void
     * 
     *         This method starts the plugin's HTTP server on the configured port.
     * 
     *         1. It stops any existing server instance to prevent port conflicts
     *         2. It creates a new PluginServer instnace with the current MainWindow
     *         reference
     *         and port
     *         3. It starts the server to begin listening for MCP client
     *         connnections.
     *         4. If any error occurs during startup, it logs the error message.
     * 
     *         This method is called by the delayed initialization mechanism after
     *         JADX is ready.
     */
    private void startServer() {
        try {
            if (pluginServer != null)
                pluginServer.stop();
            pluginServer = new PluginServer(mainWindow, currentPort);
            pluginServer.start();
        } catch (Exception e) {
            logger.error("JADX-AI-MCP Plugin: Failed to start server: " + e.getMessage());
        }
    }

    /**
     * @return void
     * 
     *         This method performs a graceful restart of the plugin server.
     * 
     *         1. It spawns a new daemon thread to handle the restart asynchronously
     *         2. it stops the current server instance if one is running
     *         3. It waits 1 second to ensure the port is fully released by the OS
     *         4. It starts a new server instance on the configured port
     *         5. It displays a success dialog to inform the user of the restart
     *         6. If restart fails, it logs the error
     * 
     *         This methods is typically called from the plugin menu UI when users
     *         need to restart the server after configuration changes or connection
     *         issues.
     */
    public void restartServer() {
        new Thread(() -> {
            logger.info("JADX-AI-MCP Plugin: Restarting server on port " + currentPort);
            if (pluginServer != null)
                pluginServer.stop();
            try {
                Thread.sleep(1000); // Wait for port release
                startServer();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(mainWindow,
                        "Server restarted on port " + currentPort,
                        "Server restarted.", JOptionPane.INFORMATION_MESSAGE));
            } catch (Exception e) {
                logger.error("Failed to restart server", e);
            }
        }, "JADX-AI-MCP-Restart").start();
    }

    public void cleanup() {
        logger.info("JADX-AI-MCP Plugin: Cleaning up previous instance...");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (pluginServer != null) {
            pluginServer.stop();
            pluginServer = null;
        }
        if (pluginMenu != null) {
            pluginMenu.removeMenuItems();
            pluginMenu = null;
        }
    }

    // --- Configuration Methods (Used by UI) ---

    /**
     * @param newPort The new port number to configure for the server
     * @return void
     * 
     *         This method updates the server port configuration and persists it.
     *         1. It updates the currentPort instance variable with the new port
     *         value
     *         2. It saves the new port to Java Preferences API for persistence
     *         across sessions
     * 
     *         This method does not restart the server automatically. Call
     *         restartServer()
     *         after updating the port to apply the changes.
     */
    public void updatePort(int newPort) {
        this.currentPort = newPort;
        prefs.putInt(PREF_KEY_PORT, newPort);
    }

    /**
     * @return void
     * 
     *         This method resets the server port configuration to the default value
     *         (8650).
     *         It delegates to updatePort() to handle the actual update and
     *         persistence.
     * 
     *         The server must be restarted for the default port to take effect.
     */
    public void resetToDefaultPort() {
        updatePort(DEFAULT_PORT);
    }

    /**
     * @return int The currently configured port number
     * 
     *         This method returns the port number on which the server is configured
     *         to run.
     *         The value is loaded from Java Preferences on plugin initialization
     *         and can be
     *         modified via updatePort() or resetToDefaultPort().
     */
    public int getCurrentPort() {
        return currentPort;
    }

    /**
     * @return boolean True if the server is running, false otherwise
     * 
     *         This method checks the runtime status of the plugin server.
     *         It verifies that:
     *         1. The pluginServer instance is not null
     *         2. The server's internal running state is true
     * 
     *         This check is used by the delayed initialization mechanism and UI
     *         status displays.
     */
    public boolean isServerRunning() {
        return pluginServer != null && pluginServer.isRunning();
    }

    public SecurityConfig getSecurityConfig() {
        return pluginServer != null ? pluginServer.getSecurityConfig() : null;
    }

    // --- Helpers ---

    /**
     * @return boolean True if JADX has completed loading APK content, false
     *         otherwise
     * 
     *         This method determines whether JADX has finished loading and
     *         decompiling the APK.
     *         It checks for:
     *         1. MainWindow instance availability
     *         2. JadxWrapper instance availability
     *         3. Presence of decompiled classes or an initialized decompiler
     *         instance
     * 
     *         This is used by startDelayedInitialization() to determine when it's
     *         safe
     *         to start the MCP server, ensuring the server has access to decompiled
     *         content.
     */
    private boolean isJadxFullyLoaded() {
        try {
            if (mainWindow == null)
                return false;
            JadxWrapper wrapper = mainWindow.getWrapper();
            if (wrapper == null)
                return false;
            // Check if we have classes or at least a decompiler instance
            List<?> classes = wrapper.getIncludedClassesWithInners();
            return (classes != null && !classes.isEmpty()) || wrapper.getDecompiler() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
