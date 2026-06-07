package com.zin.jadxaimcp.ui;

import com.zin.jadxaimcp.JadxAIMCP;
import com.zin.jadxaimcp.utils.SecurityConfig;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

public class PluginMenu {
    private static final Logger logger = LoggerFactory.getLogger(PluginMenu.class);
    private MainWindow mainWindow;
    private final JadxAIMCP plugin;
    private JMenu mcpMenu;

    public PluginMenu(MainWindow mainWindow, JadxAIMCP plugin) {
        this.mainWindow = mainWindow;
        this.plugin = plugin;
    }

    /**
     * @return void
     * 
     *         This method creates and adds plugin UI menu items to the JADX menu
     *         bar.
     *         1. It runs on the Swing EDT thread using SwingUtilities.invokeLater()
     *         2. It retrieves the main window's menu bar
     *         3. It finds or creates a "Plugins" menu in the menu bar
     *         4. It creates a "JADX AI MCP Server" submenu with the following
     *         items:
     *         - Configure Port: Opens dialog to change server port
     *         - Default Port: Resets to default port (8650) and restarts server
     *         - Restart Server: Manually restarts the MCP server
     *         - Server Status: Shows current server status and connection details
     *         5. It adds the submenu to the plugins menu
     * 
     *         All UI operations are performed on the EDT to ensure thread safety.
     */
    public void addMenuItems() {
        SwingUtilities.invokeLater(() -> {
            try {
                JMenuBar menuBar = mainWindow.getJMenuBar();
                if (menuBar == null) {
                    logger.warn("JADX-AI-MCP Plugin: Menu bar not found");
                    return;
                }

                JMenu pluginsMenu = findOrCreatePluginsMenu(menuBar);
                mcpMenu = new JMenu("JADX AI MCP Server");

                // 1. Configure Port
                JMenuItem portItem = new JMenuItem("Configure Port...");
                portItem.addActionListener(e -> showPortConfigDialog());

                // 2. Default Port
                JMenuItem defaultPortItem = new JMenuItem("Default Port");
                defaultPortItem.addActionListener(e -> {
                    plugin.resetToDefaultPort();
                    plugin.restartServer();
                });

                // 3. Restart Server
                JMenuItem restartItem = new JMenuItem("Restart Server");
                restartItem.addActionListener(e -> plugin.restartServer());

                // 4. Server Status
                JMenuItem statusItem = new JMenuItem("Server Status");
                statusItem.addActionListener(e -> showServerStatus());

                mcpMenu.add(portItem);
                mcpMenu.add(defaultPortItem);
                mcpMenu.addSeparator();
                mcpMenu.add(restartItem);
                mcpMenu.add(statusItem);
                pluginsMenu.add(mcpMenu);

                logger.debug("JADX-AI-MCP Plugin: Menu items added");
            } catch (Exception e) {

            }
        });
    }

    public void removeMenuItems() {
        if (mcpMenu != null && mainWindow != null) {
            SwingUtilities.invokeLater(() -> {
                try {
                    JMenuBar menuBar = mainWindow.getJMenuBar();
                    if (menuBar != null) {
                        JMenu pluginsMenu = findOrCreatePluginsMenu(menuBar);
                        pluginsMenu.remove(mcpMenu);
                        menuBar.repaint();
                        logger.debug("JADX-AI-MCP Plugin: Menu items removed");
                    }
                } catch (Exception e) {
                    logger.error("JADX-AI-MCP Plugin Error: Failed to remove menu items", e);
                }
            });
        }
    }

    /**
     * @param menuBar The main window's menu bar
     * @return JMenu The existing or newly created Plugins menu
     * 
     *         This method locates or creates the "Plugins" menu in the JADX menu
     *         bar.
     *         1. It searches through existing menus for one named "Plugins" or
     *         "Plugin"
     *         2. If found, it returns the existing menu
     *         3. If not found, it creates a new "Plugins" menu
     *         4. It attempts to insert the new menu before the "Help" menu if it
     *         exists
     *         5. Otherwise, it appends the menu to the end of the menu bar
     * 
     *         This ensures consistent menu organization across different JADX
     *         versions.
     */
    private JMenu findOrCreatePluginsMenu(JMenuBar menuBar) {
        // Look for existing "Plugins" menu
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            JMenu menu = menuBar.getMenu(i);
            if (menu != null && ("Plugins".equals(menu.getText()) || "Plugin".equals(menu.getText()))) {
                return menu;
            }
        }

        // Create new if not found, inserting before "Help" if possible
        JMenu pluginsMenu = new JMenu("Plugins");
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            if ("Help".equals(menuBar.getMenu(i).getText())) {
                menuBar.add(pluginsMenu, i);
                return pluginsMenu;
            }
        }
        menuBar.add(pluginsMenu);
        return pluginsMenu;
    }

    /**
     * @return void
     * 
     *         This method displays a dialog for configuring the server port.
     *         1. It shows an input dialog with the current port as default value
     *         2. It validates the input is a valid integer
     *         3. It ensures the port is in the valid range (1024-65535)
     *         4. If the port differs from current port, it:
     *         - Updates the port configuration
     *         - Restarts the server on the new port
     *         5. It displays error messages for invalid input or out-of-range
     *         values
     * 
     *         Ports below 1024 are reserved and require root privileges.
     */
    private void showPortConfigDialog() {
        String input = JOptionPane.showInputDialog(mainWindow,
                "Enter Server Port (1024-65535):", String.valueOf(plugin.getCurrentPort()));

        if (input != null) {
            try {
                int newPort = Integer.parseInt(input.trim());
                if (newPort >= 1024 && newPort <= 65535) {
                    if (newPort != plugin.getCurrentPort()) {
                        plugin.updatePort(newPort);
                        plugin.restartServer();
                    }
                } else {
                    JOptionPane.showMessageDialog(mainWindow, "Port must be between 1024 and 65535",
                            "Ivalid Port", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(mainWindow, "Invalid number format",
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * @return void
     * 
     *         This method displays the current server status in a dialog.
     *         1. It checks whether the server is currently running
     *         2. It retrieves the configured port number
     *         3. It constructs the server URL if running (http://127.0.0.1:<port>/)
     *         4. It displays a dialog showing:
     *         - Status (Running/Stopped)
     *         - Port number
     *         - Server URL (or N/A if stopped)
     * 
     *         This provides users with quick access to connection information.
     */
    private void showServerStatus() {
        boolean running = plugin.isServerRunning();
        String status = running ? "Running" : "Stopped";
        String url = running ? "http://127.0.0.1:" + plugin.getCurrentPort() + "/" : "N/A";
        SecurityConfig securityConfig = plugin.getSecurityConfig();
        String authStatus = securityConfig == null || securityConfig.isAuthDisabled() ? "Disabled" : "Required";
        String tokenSource = securityConfig != null ? securityConfig.getTokenSource() : "N/A";
        String token = securityConfig != null && !securityConfig.isAuthDisabled()
                ? securityConfig.getBearerToken()
                : "N/A";

        JOptionPane.showMessageDialog(mainWindow,
                "Status " + status + "\nPort: " + plugin.getCurrentPort() + "\nURL: " + url
                        + "\nAuth: " + authStatus
                        + "\nToken Source: " + tokenSource
                        + "\nBearer Token: " + token,
                "MCP Server Status", JOptionPane.INFORMATION_MESSAGE);
    }
}
