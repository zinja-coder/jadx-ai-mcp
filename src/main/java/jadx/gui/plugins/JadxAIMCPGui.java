import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;

public class JadxAIMCPGui {

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
