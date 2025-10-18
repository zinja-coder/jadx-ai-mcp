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

//debug
import jadx.gui.ui.panel.JDebuggerPanel;  
import jadx.gui.ui.panel.IDebugController; 
import jadx.gui.device.debugger.DebugController;
import jadx.gui.ui.codearea.SmaliArea;
import jadx.gui.utils.JumpPosition;
import jadx.gui.treemodel.JClass;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Document;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
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
            app.get("/health", this::handleHealth);


            // In start() method, ensure these routes are registered:
app.get("/debug/status", this::handleDebugStatus);
app.post("/debug/initialize", this::handleDebugInitialize);
app.post("/debug/attach", this::handleDebugAttach);
app.post("/debug/detach", this::handleDebugDetach);
app.post("/debug/set-breakpoint", this::handleSetBreakpoint);
app.post("/debug/remove-breakpoint", this::handleRemoveBreakpoint);
app.get("/debug/list-breakpoints", this::handleListBreakpoints);
app.post("/debug/step-over", this::handleStepOver);
app.post("/debug/step-into", this::handleStepInto);
app.post("/debug/step-out", this::handleStepOut);
app.post("/debug/resume", this::handleResume);
app.post("/debug/suspend", this::handleSuspend);
app.get("/debug/stack-frames", this::handleGetStackFrames);
app.get("/debug/variables", this::handleGetVariables);
app.get("/debug/threads", this::handleGetThreads);
// Add to start() method with other routes
app.get("/smali-debug", this::handleGetSmaliForDebug);
app.get("/method-smali-debug", this::handleGetMethodSmaliForDebug);



            logger.info(
                    "// -------------------- JADX AI MCP PLUGIN -------------------- //\n - By Jafar Pathan (https://github.com/zinja-coder)\n - To Report Issues : https://github.com/zinja-coder/jadx-ai-mcp\n\n");
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

    // Utility class for handling pagination across different MCP tools
    public static class PaginationUtils {

        // Configuration constants
        public static final int DEFAULT_PAGE_SIZE = 100;
        public static final int MAX_PAGE_SIZE = 10000;
        public static final int MAX_OFFSET = 1000000;

        // Generic pagination handler that can be used by any endpoint
        public static <T> Map<String, Object> handlePagination(
                Context ctx,
                List<T> allItems,
                String dataType,
                String itemsKey) throws PaginationException {

            return handlePagination(ctx, allItems, dataType, itemsKey, item -> item.toString());
        }

        // Generic pagination handler with custom item transformer
        public static <T> Map<String, Object> handlePagination(
                Context ctx,
                List<T> allItems,
                String dataType,
                String itemsKey,
                Function<T, Object> itemTransformer) throws PaginationException {

            if (allItems == null) {
                allItems = new ArrayList<>();
            }

            int totalItems = allItems.size();

            // Parse pagination parameters
            PaginationParams params = parsePaginationParams(ctx, totalItems);

            // Calculate bounds
            PaginationBounds bounds = calculatePaginationBounds(params, totalItems);

            // Transform and extract paginated subset
            List<Object> transformedItems = allItems.subList(bounds.startIndex, bounds.endIndex)
                    .stream()
                    .map(itemTransformer)
                    .collect(Collectors.toList());

            // Build response
            return buildPaginationResponse(transformedItems, params, bounds, totalItems, dataType, itemsKey);
        }

        // Parse and validate pagination parameters
        private static PaginationParams parsePaginationParams(Context ctx, int totalItems) throws PaginationException {
            String offsetParam = ctx.queryParam("offset");
            String limitParam = ctx.queryParam("limit");
            String countParam = ctx.queryParam("count"); // Legacy support

            // Use 'limit' if provided, otherwise fall back to 'count'
            String pageSizeParam = limitParam != null ? limitParam : countParam;

            int offset = 0;
            int requestedLimit = 0;
            boolean hasCustomLimit = pageSizeParam != null && !pageSizeParam.isEmpty();

            // Parse offset
            if (offsetParam != null && !offsetParam.isEmpty()) {
                try {
                    offset = Integer.parseInt(offsetParam.trim());
                    if (offset < 0) {
                        throw new PaginationException("Offset must be non-negative, got: " + offset);
                    }
                    if (offset > MAX_OFFSET) {
                        throw new PaginationException("Offset too large, maximum: " + MAX_OFFSET);
                    }
                } catch (NumberFormatException e) {
                    throw new PaginationException("Invalid offset format: '" + offsetParam + "'");
                }
            }

            // Parse limit/count
            if (hasCustomLimit) {
                try {
                    requestedLimit = Integer.parseInt(pageSizeParam.trim());
                    if (requestedLimit < 0) {
                        throw new PaginationException("Limit must be non-negative, got: " + requestedLimit);
                    }
                    if (requestedLimit > MAX_PAGE_SIZE) {
                        throw new PaginationException("Limit too large, maximum: " + MAX_PAGE_SIZE);
                    }
                } catch (NumberFormatException e) {
                    throw new PaginationException("Invalid limit format: '" + pageSizeParam + "'");
                }
            }

            // Determine effective limit
            int effectiveLimit;
            if (hasCustomLimit) {
                effectiveLimit = requestedLimit == 0 ? Math.max(0, totalItems - offset) : requestedLimit;
            } else {
                effectiveLimit = Math.min(DEFAULT_PAGE_SIZE, Math.max(0, totalItems - offset));
            }

            effectiveLimit = Math.max(0, Math.min(effectiveLimit, totalItems - offset));

            return new PaginationParams(offset, effectiveLimit, requestedLimit, hasCustomLimit);
        }

        // Calculate pagination boundaries
        private static PaginationBounds calculatePaginationBounds(PaginationParams params, int totalItems) {
            if (params.offset >= totalItems) {
                return new PaginationBounds(0, 0, false, totalItems);
            }

            int startIndex = params.offset;
            int endIndex = Math.min(startIndex + params.limit, totalItems);
            boolean hasMore = endIndex < totalItems;
            int nextOffset = hasMore ? endIndex : -1;

            return new PaginationBounds(startIndex, endIndex, hasMore, nextOffset);
        }

        // Build comprehensive pagination response
        private static Map<String, Object> buildPaginationResponse(
                List<Object> data,
                PaginationParams params,
                PaginationBounds bounds,
                int totalItems,
                String dataType,
                String itemsKey) {

            Map<String, Object> result = new HashMap<>();

            // Core data
            result.put("type", dataType);
            result.put(itemsKey, data);

            // Pagination metadata
            Map<String, Object> pagination = new HashMap<>();
            pagination.put("total", totalItems);
            pagination.put("offset", params.offset);
            pagination.put("limit", params.limit);
            pagination.put("count", data.size());
            pagination.put("has_more", bounds.hasMore);

            // Navigation helpers
            if (bounds.hasMore) {
                pagination.put("next_offset", bounds.nextOffset);
            }

            if (params.offset > 0) {
                int prevOffset = Math.max(0, params.offset - params.limit);
                pagination.put("prev_offset", prevOffset);
            }

            // Page calculations
            if (params.limit > 0) {
                int currentPage = (params.offset / params.limit) + 1;
                int totalPages = (int) Math.ceil((double) totalItems / params.limit);
                pagination.put("current_page", currentPage);
                pagination.put("total_pages", totalPages);
                pagination.put("page_size", params.limit);
            }

            // Legacy compatibility
            result.put("requested_count", params.requestedLimit);
            result.put("pagination", pagination);

            return result;
        }

        // Helper classes remain the same as before
        private static class PaginationParams {
            final int offset;
            final int limit;
            final int requestedLimit;
            final boolean hasCustomLimit;

            PaginationParams(int offset, int limit, int requestedLimit, boolean hasCustomLimit) {
                this.offset = offset;
                this.limit = limit;
                this.requestedLimit = requestedLimit;
                this.hasCustomLimit = hasCustomLimit;
            }
        }

        private static class PaginationBounds {
            final int startIndex;
            final int endIndex;
            final boolean hasMore;
            final int nextOffset;

            PaginationBounds(int startIndex, int endIndex, boolean hasMore, int nextOffset) {
                this.startIndex = startIndex;
                this.endIndex = endIndex;
                this.hasMore = hasMore;
                this.nextOffset = nextOffset;
            }
        }

        public static class PaginationException extends Exception {
            public PaginationException(String message) {
                super(message);
            }
        }
    }

    // -------------------------- various request handlers -------------------------- //

    // method to handle /health request which is used to ensure plugin and mcp
    // server are properly started //
    public void handleHealth(Context ctx) {
        try {
            String status = serverStarted && app != null ? "Running" : "Stopped";
            String url = serverStarted ? "http://127.0.0.1:" + currentPort + "/" : "N/A";

            Map<String, Object> result = new HashMap<>();
            result.put("status", status);
            result.put("url", url);
            logger.info("JADX AI MCP Plugin: GOT HEALTH PING");
            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500)
                    .json(Map.of("error",
                            "Internal Error while trying to handle health ping request: " + e.getMessage()));
        }
    }

    // method to handle /current-class request //
    public void handleCurrentClass(Context ctx) {
        try {
            String className = getSelectedTabTitle();
            String code = extractTextFromCurrentTab();

            Map<String, Object> result = new HashMap<>();
            result.put("name", className != null ? className.replace(".java", "") : "unknown");
            result.put("type", "code/java");
            result.put("content", code != null ? code : "");

            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500)
                    .json(Map.of("error", "Internal Error while trying to fetch current class: " + e.getMessage()));
        }
    }

    // method to handle /all-classes call
    private void handleAllClasses(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<JavaClass> classes = wrapper.getIncludedClassesWithInners();

            Map<String, Object> result = PaginationUtils.handlePagination(
                    ctx,
                    classes,
                    "class-list",
                    "classes",
                    cls -> cls.getFullName());

            ctx.json(result);

        } catch (PaginationUtils.PaginationException e) {
            logger.error("JADX AI MCP Pagination Error: " + e.getMessage());
            ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Failed to load class list: " + e.getMessage()));
        }
    }

    // method to handle /selected-text call
    private void handleSelectedText(Context ctx) {
        try {
            JTextArea textArea = findTextArea(mainWindow.getTabbedPane().getSelectedComponent());
            String selectedText = textArea != null ? textArea.getSelectedText() : null;

            Map<String, String> result = new HashMap<>();
            result.put("selectedText", selectedText != null ? selectedText : "");
            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500)
                    .json(Map.of("error", "Internal error while trying to fetch selected text: " + e.getMessage()));
        }
    }

    // method to handle /method-by-name call
    private void handleMethodByName(Context ctx) {
        String methodName = ctx.queryParam("method");
        String className = ctx.queryParam("class");

        if (methodName == null || methodName.isEmpty()) {
            logger.error("JADX AI MCP Error: Missing 'method' parameter.");
            ctx.status(400).json(Map.of("error", "Missing 'method' parameter"));
            return;
        }
        className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            if (wrapper == null) {
                logger.error("JADX AI MCP Error: JadxWrapper not initialized");
                ctx.status(500).json(Map.of("error", "JadxWrapper no initialized"));
                return;
            }

            // if className parameter is not given, return all matching method code
            if (className == null || className.isEmpty()) {

                for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                    for (jadx.api.JavaMethod method : cls.getMethods()) {
                        if (method.getName().equalsIgnoreCase(methodName)) {
                            String codeStr;
                            try {
                                codeStr = method.getCodeStr();
                            } catch (Exception e) {
                                logger.error("JADX AI MCP Error: " + e.getMessage(), e);
                                codeStr = "Error retrieving code from method: " + e.getMessage();
                            }

                            Map<String, Object> result = new HashMap<>();
                            result.put("class", cls.getFullName());
                            result.put("method", method.getName());
                            result.put("decl", String.valueOf(method.getCodeNodeRef()));
                            result.put("code", codeStr);
                            ctx.json(result);
                            return;
                        }
                    }
                }
            } else { // if className parameter is given then return only that class' method
                for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                    if (cls.getFullName().equals(className)) {
                        for (jadx.api.JavaMethod method : cls.getMethods()) {
                            if (method.getName().equalsIgnoreCase(methodName)) {
                                String codeStr;
                                try {
                                    codeStr = method.getCodeStr();
                                } catch (Exception e) {
                                    logger.error("JADX AI MCP Error: " + e.getMessage(), e);
                                    codeStr = "Error retrieving code from method: " + e.getMessage();
                                }

                                Map<String, Object> result = new HashMap<>();
                                result.put("class", cls.getFullName());
                                result.put("method", method.getName());
                                result.put("decl", String.valueOf(method.getCodeNodeRef()));
                                result.put("code", codeStr);
                                ctx.json(result);
                                return;
                            }
                        }
                    }
                }
            }

            ctx.status(404).json(Map.of("error", "Method not found in any class."));
            logger.error("JADX AI MCP Error: Method not found in any class");
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500)
                    .json(Map.of("error", "Internal error while trying to retrieve method code: " + e.getMessage()));
        }
    }

    // method to handle /class-source
    private void handleClassSource(Context ctx) {
        String className = ctx.queryParam("class");

        if (className == null || className.isEmpty()) {
            logger.error("JADX AI MCP Error: Missing 'class' parameter.");
            ctx.status(400).json(Map.of("error", "Missing 'class' parameter."));
            return;
        }
        className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    ctx.result(cls.getCode());
                    return;
                }
            }
            ctx.status(404).json(Map.of("error", "Class not found."));
            logger.error("JADX AI MCP Error: Class not found.");
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving class source: " + e.getMessage()));
        }
    }

    // method to handle /search-method
    private void handleSearchMethod(Context ctx) {
        String methodName = ctx.queryParam("method");
        List<String> results = new ArrayList<>();

        if (methodName == null) {
            logger.error("JADX AI MCP Error: Missing 'method' parameter.");
            ctx.status(400).json(Map.of("error", "Missing method parameter"));
            return;
        }

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getCode().toLowerCase().contains(methodName.toLowerCase())) {
                    results.add(cls.getFullName());
                }
            }
            ctx.result(String.join("\n", results));
            logger.debug(ctx.body());
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error during method search: " + e.getMessage()));
        }
    }

    // method to handle /methods-of-class call
    private void handleMethodsOfClass(Context ctx) {
        String className = ctx.queryParam("class_name");

        if (className == null || className.isEmpty()) {
            logger.error("JADX AI MCP Error: Missing 'class' parameter.");
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class'"));
            return;
        }
        className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    List<String> methods = new ArrayList<>();
                    for (JavaMethod method : cls.getMethods()) {
                        String fullMethodName = cls.getFullName()+"."+method.getName();
                        String methodData = method.getAccessFlags() + " " + method.getReturnType() + " " +
                                method.getName() +" "+ method.getMethodNode()+" " + fullMethodName;
                        methods.add(methodData);
                    }
                    ctx.result(String.join("\n", methods));
                    return;
                }
            }
            ctx.status(404).json(Map.of("error", "Class not found."));
            logger.error("JADX AI MCP Error: Class not found.");
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving methods: " + e.getMessage()));
        }
    }

    // method to handle /fields-of-class call
    private void handleFieldsOfClass(Context ctx) {
        String className = ctx.queryParam("class_name");

        if (className == null || className.isEmpty()) {
            logger.error("JADX AI MCP Error: Missing 'class_name' parameter.");
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class'"));
            return;
        }
        className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    List<String> fields = new ArrayList<>();
                    for (JavaField field : cls.getFields()) {
                        String fieldData = field.getAccessFlags() + " "
                                + field.getType() + " " + field.getName();
                        fields.add(fieldData);
                    }
                    ctx.result(String.join("\n", fields));
                    return;
                }
            }
            ctx.status(404).json(Map.of("error", "Class not found"));
            logger.error("JADX AI MCP Error: Class not found.");
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving fields: " + e.getMessage()));
        }
    }

    // method to handle /rename-class
    private void handleRenameClass(Context ctx) {
        String className = ctx.queryParam("class");
        String newName = ctx.queryParam("newName");

        if (className == null || className.isEmpty() || newName == null || newName.isEmpty()) {
            logger.error("JADX AI MCP Error: Missing 'class' or 'newName' parameter.");
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class' or 'newName'"));
            return;
        }
        className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    ICodeNodeRef nodeRef = cls.getCodeNodeRef();
                    NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, cls.getName(), newName);
                    event.setRenameNode(cls.getClassNode());
                    event.setResetName(newName.isEmpty());
                    mainWindow.events().send(event);
                    logger.info("rename Class " + cls.getName() + " to " + newName);
                    Map<String, Object> result = new HashMap<>();
                    result.put("result", "rename Class " + cls.getName() + " to " + newName);
                    ctx.status(200);
                    return;
                }
            }
            ctx.status(404).json(Map.of("error", "Class not found"));
            logger.error("JADX AI MCP Error: Class not found.");
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error rename Class: " + e.getMessage()));
        }
    }

    // method to handle /rename-method
    private void handleRenameMethod(Context ctx) {
        String methodName = ctx.queryParam("method");
        String newName = ctx.queryParam("newName");

        if (methodName == null || methodName.isEmpty() || newName == null || newName.isEmpty()) {
            logger.error("JADX AI MCP Error: Missing 'method' or 'newName' parameter.");
            ctx.status(400).json(Map.of("error", "Missing 'method' or 'newName' parameter"));
            return;
        }
        // remove useless string
        int index = methodName.indexOf('(');
        if(index != -1){
            methodName = methodName.substring(0, index);
        }
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            if (wrapper == null) {
                logger.error("JADX AI MCP Error: JadxWrapper not initialized");
                ctx.status(500).json(Map.of("error", "JadxWrapper no initialized"));
                return;
            }
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                String className = cls.getFullName().replace('$', '.');
                for (JavaMethod method : cls.getMethods()) {
                    String fullMethodName = className+"."+method.getName();
                    if (fullMethodName.equalsIgnoreCase(methodName)) {
                        ICodeNodeRef nodeRef = method.getCodeNodeRef();
                        NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, method.getName(), newName);
                        event.setRenameNode(method.getMethodNode());
                        event.setResetName(newName.isEmpty());
                        mainWindow.events().send(event);
                        logger.info("rename method " + method.getName() + " to " + newName);
                        Map<String, Object> result = new HashMap<>();
                        result.put("result", "rename method " + method.getName() + " to " + newName);
                        ctx.status(200);
                        return;
                    }
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("result", "method not found");
            ctx.status(400);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500)
                    .json(Map.of("error", "Internal error while trying to retrieve method code: " + e.getMessage()));
        }
    }

    // method to handle /rename-field
    private void handleRenameField(Context ctx) {
        String className = ctx.queryParam("class");
        String oldFieldName = ctx.queryParam("field");
        String newFieldName = ctx.queryParam("newFieldName");

        if (className == null || className.isEmpty() || oldFieldName == null || oldFieldName.isEmpty()) {
            logger.error("JADX AI MCP Error: Missing 'class' or 'field' parameter.");
            ctx.status(400).json(Map.of("error", "Missing required parameter 'class' or 'field'"));
            return;
        }
        className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    for (JavaField field : cls.getFields()) {
                        if (field.getName().equals(oldFieldName)) {
                            logger.info("Renaming field: " + field.getName() + " to " + newFieldName);
                            ICodeNodeRef nodeRef = field.getCodeNodeRef();
                            NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, field.getName(), newFieldName);
                            event.setRenameNode(field.getFieldNode());
                            event.setResetName(newFieldName.isEmpty());
                            mainWindow.events().send(event);
                            Map<String, Object> result = new HashMap<>();
                            result.put("result", "rename method " + field.getName() + " to " + newFieldName);
                            ctx.status(200);
                            return;

                        }
                    }
                }
            }
            ctx.status(404).json(Map.of("error", "Class not found"));
            logger.error("JADX AI MCP Error: Class not found.");
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error rename field: " + e.getMessage()));
        }
    }

    // method to handle /smali-of-class call
    private void handleSmaliOfClass(Context ctx) {
        String className = ctx.queryParam("class");

        if (className == null || className.isEmpty()) {
            logger.error("JADX AI MCP Error: Missing 'class' parameter.");
            ctx.status(400).json(Map.of("error", "Missing 'class' parameter."));
            return;
        }
        className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    ctx.result(cls.getSmali());
                    return;
                }
            }
            ctx.status(404).json(Map.of("error", "Class not found."));
            logger.error("JADX AI MCP Error: Class not found.");
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving class source: " + e.getMessage()));
        }
    }

    // method to handle /manifest
    private void handleManifest(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<ResourceFile> resources = wrapper.getResources();
            ResourceFile manifest = AndroidManifestParser.getAndroidManifest(resources);

            if (manifest == null) {
                logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
                ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
                return;
            }

            ResContainer container = manifest.loadContent();
            String manifestContent = container.getText().getCodeStr();

            Map<String, Object> result = new HashMap<>();
            result.put("name", manifest.getOriginalName());
            result.put("type", "manifest/xml");
            result.put("content", manifestContent);

            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
        }
    }

    // method to handle /main-application-classes-name
    private void handleMainApplicationClassesNames(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<ResourceFile> resources = wrapper.getResources();

            // Get the manifest ResourceFile
            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
            if (manifestRes == null) {
                logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
                ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
                return;
            }

            // Load manifest content and parse XML
            String manifestXml = manifestRes.loadContent().getText().getCodeStr();
            Document manifestDoc = parseManifestXml(manifestXml, wrapper.getArgs().getSecurity());

            // Extract the package name from the <manifest> tag
            Element manifestElement = (Element) manifestDoc.getElementsByTagName("manifest").item(0);
            String packageName = manifestElement.getAttribute("package");

            if (packageName.isEmpty()) {
                logger.error("JADX AI MCP Error: Package name not found in manifest");
                ctx.status(404).json(Map.of("error", "Package name not found in manifest."));
                return;
            }

            // Filter classes under this package
            List<JavaClass> matchedClasses = wrapper.getDecompiler()
                    .getClasses()
                    .stream()
                    .filter(cls -> cls.getFullName().startsWith(packageName))
                    .collect(Collectors.toList());

            List<Map<String, Object>> classesInfo = new ArrayList<>();
            for (JavaClass cls : matchedClasses) {
                Map<String, Object> classInfo = new HashMap<>();
                classInfo.put("name", cls.getFullName());
                classesInfo.add(classInfo);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("classes", classesInfo);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
        }
    }

    // handle /main-application-classes-codes
    private void handleMainApplicationClassesCode(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<ResourceFile> resources = wrapper.getResources();

            // get the manifest resource file
            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
            if (manifestRes == null) {
                logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
                ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
                return;
            }

            // load manifest content and parse xml
            String manifestXml = manifestRes.loadContent()
                    .getText()
                    .getCodeStr();
            Document manifestDoc = parseManifestXml(manifestXml, wrapper.getArgs().getSecurity());

            // Extract the package name from the <manifest> tag
            Element manifestElement = (Element) manifestDoc.getElementsByTagName("manifest").item(0);
            String packageName = manifestElement.getAttribute("package");

            if (packageName.isEmpty()) {
                logger.error("JADX AI MCP Error: Package name not found manifest.");
                ctx.status(404).json(Map.of("error", "Package name not found manifest."));
                return;
            }

            // filter classes under this package
            List<JavaClass> matchedClasses = wrapper.getDecompiler()
                    .getClasses()
                    .stream()
                    .filter(cls -> cls.getFullName().startsWith(packageName))
                    .collect(Collectors.toList());

            List<Map<String, Object>> classesInfo = new ArrayList<>();
            for (JavaClass cls : matchedClasses) {
                Map<String, Object> classInfo = new HashMap<>();
                classInfo.put("name", cls.getFullName());
                classInfo.put("type", "code/java");
                classInfo.put("content", cls.getCode());
                classesInfo.add(classInfo);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("allClassesInPackage", classesInfo);
            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
        }
    }

    // method to handle /main-activity
    private void handleMainActivity(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<ResourceFile> resources = wrapper.getResources();

            AndroidManifestParser parser = new AndroidManifestParser(
                    AndroidManifestParser.getAndroidManifest(resources),
                    EnumSet.of(AppAttribute.MAIN_ACTIVITY),
                    wrapper.getArgs().getSecurity());

            if (!parser.isManifestFound()) {
                logger.error("JADX AI MCP Error: AndroidManifest.xml not found.");
                ctx.status(404).json(Map.of("error", "AndroidManifest.xml not found."));
                return;
            }

            ApplicationParams results = parser.parse();
            if (results.getMainActivity() == null) {
                logger.error("JADX AI MCP Error: Failed to get main activity from manifest.");
                ctx.status(404).json(Map.of("error", "Failed to get main activity from manifest."));
                return;
            }

            JavaClass mainActivityClass = results.getMainActivityJavaClass(wrapper.getDecompiler());

            if (mainActivityClass == null) {
                logger.error("JADX AI MCP Error: Failed to get activity class: " + results.getApplication());
                ctx.status(404).json(Map.of("error", "Failed to get activity class: " + results.getApplication()));
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("name", mainActivityClass.getFullName());
            result.put("type", "code/java");
            result.put("content", mainActivityClass.getCode());

            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error retrieving AndroidManifest.xml: " + e.getMessage()));
        }
    }

// method to handle /strings
private void handleStrings(Context ctx) {
    try {
        JadxWrapper wrapper = mainWindow.getWrapper();
        List<ResourceFile> resourceFiles = wrapper.getResources();

        // Explicit element type
        List<Map<String, Object>> allStringEntries = new ArrayList<>();

        for (ResourceFile resFile : resourceFiles) {
            try {
                if ("resources.arsc".equals(resFile.getDeobfName())) {
                    ResContainer container = resFile.loadContent();
                    List<ResContainer> subFiles = container.getSubFiles();
                    for (ResContainer file : subFiles) {
                        if ("res/values/strings.xml".equals(file.getFileName())) {
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("file", file.getFileName());
                            entry.put("content", file.getText().getCodeStr());
                            allStringEntries.add(entry);
                        }
                    }
                } else if ("res/values/strings.xml".equals(resFile.getDeobfName())) {
                    ResContainer container = resFile.loadContent();
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("file", resFile.getDeobfName());
                    entry.put("content", container.getText().getCodeStr());
                    allStringEntries.add(entry);
                }
            } catch (Exception e) {
                logger.error("JADX AI MCP Error: {}", e.getMessage(), e);
            }
        }

        if (allStringEntries.isEmpty()) {
            ctx.status(404).json(Map.of("error", "No strings.xml resource found"));
            return;
        }

        // Use the generic pagination with an explicit transformer signature
        Map<String, Object> result = PaginationUtils.handlePagination(
            ctx,
            allStringEntries,
            "resource/strings-xml",
            "strings",
            (java.util.function.Function<Map<String, Object>, Object>) item -> {
                // Return the same map as Object to satisfy Function<T, Object>
                return item;
            }
        );

        ctx.json(result);
    } catch (JadxAIMCP.PaginationUtils.PaginationException e) {
        logger.error("JADX AI MCP Pagination Error: {}", e.getMessage());
        ctx.status(400).json(Map.of("error", "Pagination error: " + e.getMessage()));
    } catch (Exception e) {
        logger.error("JADX AI MCP Error: {}", e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Internal error while retrieving strings.xml file: " + e.getMessage()));
    }
}

    // method to handle /list-resource-files-names
    private void handleListAllResourceFilesNames(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<ResourceFile> resourceFiles = wrapper.getResources();
            List<String> resourceFileNames = new ArrayList<>();

            for (ResourceFile resFile : resourceFiles) {
                try {
                    if (resFile.getDeobfName().equals("resources.arsc")) {

                        ResContainer container = resFile.loadContent();
                        List<ResContainer> subFiles = container.getSubFiles();
                        for (ResContainer file : subFiles) {
                            resourceFileNames.add(file.getFileName());
                        }
                    }
                    resourceFileNames.add(resFile.getDeobfName());
                } catch (Exception e) {
                    logger.error("JADX AI MCP Error: " + e.getMessage(), e);
                }
            }

            if (resourceFileNames.isEmpty()) {
                ctx.status(404).json(Map.of("error", "No resources found"));
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("files", resourceFileNames);

            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(
                    Map.of("error", "Internal error while retrieving list of resource files names: " + e.getMessage()));
        }
    }

    // method to handle /get-resource-file
    private void handleGetResourceFile(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<ResourceFile> resourceFiles = wrapper.getResources();
            Map<String, Object> resFileContent = new HashMap<>();
            String filename = ctx.queryParam("name");

            if (filename == null || filename.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing required 'name' parameter."));
                return;
            }

            for (ResourceFile resFile : resourceFiles) {

                if (resFile.getDeobfName().equals(filename)) {

                    ResContainer container = resFile.loadContent();
                    resFileContent.put("file", resFile.getDeobfName());
                    resFileContent.put("content", container.getText().getCodeStr());
                    break;
                } else if (resFile.getDeobfName().equals("resources.arsc")) {
                    ResContainer container = resFile.loadContent();
                    List<ResContainer> subFiles = container.getSubFiles();
                    for (ResContainer file : subFiles) {
                        if (file.getFileName().equals(filename)) {
                            resFileContent.put("file", file.getFileName());
                            resFileContent.put("content", file.getText().getCodeStr());
                            break;
                        }
                    }
                }
            }

            if (resFileContent.isEmpty()) {
                ctx.status(404).json(Map.of("error", "No resource file found"));
                return;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("type", "resource/text");
            result.put("file", resFileContent);

            ctx.json(result);
        } catch (Exception e) {
            logger.error("JADX AI MCP Error: " + e.getMessage(), e);
            ctx.status(500).json(Map.of("error", "Internal error while retrieving resource file: " + e.getMessage()));
        }
    }

       // ==================== VERIFIED DEBUG HANDLERS ====================

/**
 * Get debugger status - VERIFIED with JDebuggerPanel API
 */
private void handleDebugStatus(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        Map<String, Object> status = new HashMap<>();
        
        if (debuggerPanel == null) {
            status.put("available", false);
            status.put("attached", false);
            status.put("message", "Debugger panel not initialized. Open Tools -> Debugger first.");
            ctx.json(status);
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null) {
            status.put("available", true);
            status.put("attached", false);
            status.put("message", "Debugger controller not initialized");
        } else {
            status.put("available", true);
            status.put("attached", controller.isDebugging());
            status.put("suspended", controller.isSuspended());
            status.put("processName", controller.getProcessName());
        }
        
        ctx.json(status);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to get debug status: " + e.getMessage()));
    }
}

/**
 * Initialize debugger panel - VERIFIED
 */
/**
 * Initialize debugger panel - FIXED for private access
 */
private void handleDebugInitialize(Context ctx) {
    try {
        final Map<String, Object> result = new HashMap<>();
        
        SwingUtilities.invokeLater(() -> {
            try {
                JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
                if (debuggerPanel == null) {
                    // Try menu approach first
                    JMenuBar menuBar = mainWindow.getJMenuBar();
                    boolean initialized = false;
                    
                    for (int i = 0; i < menuBar.getMenuCount(); i++) {
                        JMenu menu = menuBar.getMenu(i);
                        if (menu != null && "Tools".equals(menu.getText())) {
                            for (int j = 0; j < menu.getItemCount(); j++) {
                                JMenuItem item = menu.getItem(j);
                                if (item != null && item.getText() != null && 
                                    item.getText().contains("Debugger")) {
                                    item.doClick();
                                    initialized = true;
                                    logger.info("JADX AI MCP: Debugger panel initialized via menu");
                                    break;
                                }
                            }
                            if (initialized) break;
                        }
                    }
                    
                    // If menu approach fails, use reflection
                    if (!initialized) {
                        try {
                            java.lang.reflect.Method method = MainWindow.class.getDeclaredMethod("initDebuggerPanel");
                            method.setAccessible(true);
                            method.invoke(mainWindow);
                            logger.info("JADX AI MCP: Debugger panel initialized via reflection");
                        } catch (Exception e) {
                            logger.error("JADX AI MCP: Failed to initialize debugger panel", e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("JADX AI MCP: Error initializing debugger panel", e);
            }
        });
        
        // Wait for UI thread
        Thread.sleep(500);
        
        result.put("success", mainWindow.getDebuggerPanel() != null);
        result.put("message", "Debugger panel initialization requested");
        ctx.json(result);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to initialize debugger: " + e.getMessage()));
    }
}


/**
 * Attach debugger - VERIFIED with showDebugger method signature
 */
private void handleDebugAttach(Context ctx) {
    try {
        String processName = ctx.queryParam("process");
        String host = ctx.queryParamAsClass("host", String.class).getOrDefault("localhost");
        String portStr = ctx.queryParamAsClass("port", String.class).getOrDefault("8700");
        String androidVerStr = ctx.queryParamAsClass("androidVer", String.class).getOrDefault("29");
        String pid = ctx.queryParam("pid");
        
        if (processName == null || processName.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Missing required parameter: process"));
            return;
        }
        
        int port = Integer.parseInt(portStr);
        int androidVer = Integer.parseInt(androidVerStr);
        
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized. Call /debug/initialize first"));
            return;
        }
        
        // showDebugger signature from JDebuggerPanel.java line 377:
        // public boolean showDebugger(String procName, String host, int port, int androidVer, ADBDevice device, String pid)
        boolean success = debuggerPanel.showDebugger(processName, host, port, androidVer, null, pid);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("attached", success);
        result.put("process", processName);
        result.put("host", host);
        result.put("port", port);
        result.put("androidVersion", androidVer);
        
        if (success) {
            result.put("message", "Successfully attached to " + processName + " at " + host + ":" + port);
            logger.info("JADX AI MCP: Debugger attached to " + processName);
        } else {
            result.put("message", "Failed to attach. Ensure ADB is connected and JDWP port is forwarded.");
        }
        
        ctx.json(result);
    } catch (NumberFormatException e) {
        ctx.status(400).json(Map.of("error", "Invalid port or android version format"));
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to attach debugger: " + e.getMessage()));
    }
}

/**
 * Detach/stop debugger - VERIFIED (uses stop() method)
 */
private void handleDebugDetach(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached to any process"));
            return;
        }
        
        // From JDebuggerPanel.java line 226: controller.stop()
        controller.stop();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Debugger detached successfully");
        ctx.json(result);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to detach debugger: " + e.getMessage()));
    }
}

/**
 * Step over - VERIFIED (line 202)
 */
private void handleStepOver(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return;
        }
        
        if (!controller.isSuspended()) {
            ctx.status(400).json(Map.of("error", "Process not suspended. Cannot step while running."));
            return;
        }
        
        // From line 202: controller.stepOver()
        controller.stepOver();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("action", "step_over");
        result.put("message", "Step over executed");
        ctx.json(result);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to step over: " + e.getMessage()));
    }
}

/**
 * Step into - VERIFIED (line 211)
 */
private void handleStepInto(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return;
        }
        
        if (!controller.isSuspended()) {
            ctx.status(400).json(Map.of("error", "Process not suspended"));
            return;
        }
        
        // From line 211: controller.stepInto()
        controller.stepInto();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("action", "step_into");
        result.put("message", "Step into executed");
        ctx.json(result);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to step into: " + e.getMessage()));
    }
}

/**
 * Step out - VERIFIED (line 220)
 */
private void handleStepOut(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return;
        }
        
        if (!controller.isSuspended()) {
            ctx.status(400).json(Map.of("error", "Process not suspended"));
            return;
        }
        
        // From line 220: controller.stepOut()
        controller.stepOut();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("action", "step_out");
        result.put("message", "Step out executed");
        ctx.json(result);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to step out: " + e.getMessage()));
    }
}

/**
 * Resume execution - VERIFIED (line 238: controller.run())
 */
private void handleResume(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return;
        }
        
        if (!controller.isSuspended()) {
            ctx.status(400).json(Map.of("error", "Process not suspended. Already running."));
            return;
        }
        
        // From line 238: if (controller.isSuspended()) { controller.run(); }
        controller.run();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("action", "resume");
        result.put("message", "Execution resumed");
        ctx.json(result);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to resume: " + e.getMessage()));
    }
}

/**
 * Pause/suspend execution - VERIFIED (line 240: controller.pause())
 */
private void handleSuspend(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return;
        }
        
        if (controller.isSuspended()) {
            ctx.status(400).json(Map.of("error", "Process already suspended"));
            return;
        }
        
        // From line 240: else { controller.pause(); }
        controller.pause();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("action", "suspend");
        result.put("message", "Execution paused");
        ctx.json(result);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to suspend: " + e.getMessage()));
    }
}

/**
 * Navigate to breakpoint location - Breakpoints are UI-only in JADX
 */
/**
 * Navigate to breakpoint location - FIXED codeJump
 */
/**
 * Navigate to breakpoint location - COMPLETE FIX
 */
private void handleSetBreakpoint(Context ctx) {
    String className = ctx.queryParam("class");
    String lineStr = ctx.queryParam("line");
    
    if (className == null || lineStr == null) {
        ctx.status(400).json(Map.of("error", "Missing class or line parameter"));
        return;
    }
    
    try {
        int line = Integer.parseInt(lineStr);
        className = className.replace("/", ".");
        
        JadxWrapper wrapper = mainWindow.getWrapper();
        JavaClass targetClass = null;
        for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
            if (cls.getFullName().equals(className)) {
                targetClass = cls;
                break;
            }
        }
        
        if (targetClass == null) {
            ctx.status(404).json(Map.of("error", "Class not found: " + className));
            return;
        }
        
        // Navigate using TabsController - need to convert JavaClass to JClass (JNode)
        final JavaClass finalTargetClass = targetClass;
        SwingUtilities.invokeLater(() -> {
            try {
                // Get JClass (GUI tree node) from JavaClass
                jadx.gui.treemodel.JClass jClass = mainWindow.getCacheObject().getNodeCache().makeFrom(finalTargetClass);
                
                // Use TabsController's codeJump with JNode
                mainWindow.getTabsController().codeJump(
                    new jadx.gui.utils.JumpPosition(jClass)
                );
                
                //logger.info("JADX AI MCP: Navigated to " + className + " for breakpoint at line " + line);
            } catch (Exception e) {
                logger.error("JADX AI MCP: Failed to navigate to class", e);
            }
        });
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("class", className);
        result.put("line", line);
        result.put("message", "Navigated to " + className + ". Set breakpoint manually in Smali view (F2) at line " + line);
        result.put("note", "Breakpoints in JADX must be toggled via UI - press F2 on desired line in Smali view");
        ctx.json(result);
    } catch (NumberFormatException e) {
        ctx.status(400).json(Map.of("error", "Invalid line number"));
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to navigate: " + e.getMessage()));
    }
}

/**
 * Get Smali bytecode with line numbers for debugging.
 * This returns the Smali code with line number annotations to help
 * identify exact positions for setting breakpoints.
 */
private void handleGetSmaliForDebug(Context ctx) {
    String className = ctx.queryParam("class");
    
    if (className == null || className.isEmpty()) {
        logger.error("JADX AI MCP Error: Missing class parameter.");
        ctx.status(400).json(Map.of("error", "Missing class parameter."));
        return;
    }
    
    className = className.replace("/", ".");
    
    try {
        JadxWrapper wrapper = mainWindow.getWrapper();
        
        for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
            if (cls.getFullName().equals(className)) {
                // Get the Smali code
                String smaliCode = cls.getSmali();
                
                if (smaliCode == null || smaliCode.isEmpty()) {
                    ctx.status(404).json(Map.of("error", "Smali code not available for this class"));
                    return;
                }
                
                // Split into lines and add line numbers
                String[] lines = smaliCode.split("\n");
                List<Map<String, Object>> numberedLines = new ArrayList<>();
                
                for (int i = 0; i < lines.length; i++) {
                    Map<String, Object> lineInfo = new HashMap<>();
                    lineInfo.put("lineNumber", i + 1);
                    lineInfo.put("content", lines[i]);
                    
                    // Identify debuggable lines (instructions, not comments or empty lines)
                    String trimmed = lines[i].trim();
                    boolean isDebuggable = !trimmed.isEmpty() 
                        && !trimmed.startsWith("#") 
                        && !trimmed.startsWith(".") 
                        && !trimmed.equals("");
                    
                    lineInfo.put("debuggable", isDebuggable);
                    
                    // Identify method boundaries
                    if (trimmed.startsWith(".method")) {
                        lineInfo.put("methodStart", true);
                    } else if (trimmed.startsWith(".end method")) {
                        lineInfo.put("methodEnd", true);
                    }
                    
                    numberedLines.add(lineInfo);
                }
                
                Map<String, Object> result = new HashMap<>();
                result.put("class", cls.getFullName());
                result.put("type", "smali-debug");
                result.put("totalLines", lines.length);
                result.put("lines", numberedLines);
                result.put("rawSmali", smaliCode);
                
                ctx.json(result);
                return;
            }
        }
        
        ctx.status(404).json(Map.of("error", "Class not found."));
        logger.error("JADX AI MCP Error: Class not found.");
        
    } catch (Exception e) {
        logger.error("JADX AI MCP Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Internal error retrieving Smali for debug: " + e.getMessage()));
    }
}

/**
 * Get method-level Smali code with line numbers for precise breakpoint placement
 */
private void handleGetMethodSmaliForDebug(Context ctx) {
    String className = ctx.queryParam("class");
    String methodName = ctx.queryParam("method");
    
    if (className == null || className.isEmpty()) {
        ctx.status(400).json(Map.of("error", "Missing class parameter"));
        return;
    }
    
    if (methodName == null || methodName.isEmpty()) {
        ctx.status(400).json(Map.of("error", "Missing method parameter"));
        return;
    }
    
    className = className.replace("/", ".");
    
    try {
        JadxWrapper wrapper = mainWindow.getWrapper();
        
        for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
            if (cls.getFullName().equals(className)) {
                // Get full Smali code
                String smaliCode = cls.getSmali();
                
                if (smaliCode == null || smaliCode.isEmpty()) {
                    ctx.status(404).json(Map.of("error", "Smali code not available"));
                    return;
                }
                
                // Parse and find the specific method
                String[] lines = smaliCode.split("\n");
                List<Map<String, Object>> methodLines = new ArrayList<>();
                boolean inMethod = false;
                int methodStartLine = -1;
                int absoluteLineNumber = 0;
                
                for (int i = 0; i < lines.length; i++) {
                    absoluteLineNumber = i + 1;
                    String line = lines[i];
                    String trimmed = line.trim();
                    
                    // Check if this is the start of our target method
                    if (trimmed.startsWith(".method") && trimmed.contains(methodName)) {
                        inMethod = true;
                        methodStartLine = absoluteLineNumber;
                    }
                    
                    if (inMethod) {
                        Map<String, Object> lineInfo = new HashMap<>();
                        lineInfo.put("absoluteLine", absoluteLineNumber);
                        lineInfo.put("relativeLine", absoluteLineNumber - methodStartLine + 1);
                        lineInfo.put("content", line);
                        
                        // Mark debuggable lines
                        boolean isDebuggable = !trimmed.isEmpty() 
                            && !trimmed.startsWith("#") 
                            && !trimmed.startsWith(".method")
                            && !trimmed.startsWith(".end method")
                            && !trimmed.startsWith(".locals")
                            && !trimmed.startsWith(".param")
                            && !trimmed.startsWith(".annotation")
                            && !trimmed.startsWith(".end annotation");
                        
                        lineInfo.put("debuggable", isDebuggable);
                        
                        methodLines.add(lineInfo);
                        
                        // Check if method ends
                        if (trimmed.startsWith(".end method")) {
                            break;
                        }
                    }
                }
                
                if (methodLines.isEmpty()) {
                    ctx.status(404).json(Map.of("error", "Method not found: " + methodName));
                    return;
                }
                
                Map<String, Object> result = new HashMap<>();
                result.put("class", cls.getFullName());
                result.put("method", methodName);
                result.put("type", "smali-method-debug");
                result.put("methodStartLine", methodStartLine);
                result.put("totalLines", methodLines.size());
                result.put("lines", methodLines);
                
                ctx.json(result);
                return;
            }
        }
        
        ctx.status(404).json(Map.of("error", "Class not found"));
        
    } catch (Exception e) {
        logger.error("JADX AI MCP Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Internal error: " + e.getMessage()));
    }
}



// ==================== EXTRACT DATA FROM UI COMPONENTS ====================

/**
 * Get all variables (registers and 'this' object fields)
 * Extracts from JTree UI components: regTreeNode and thisTreeNode
 */
private void handleGetVariables(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return;
        }
        
        if (!controller.isSuspended()) {
            ctx.status(400).json(Map.of("error", "Process not suspended. Variables only available when paused."));
            return;
        }
        
        Map<String, Object> variables = new HashMap<>();
        
        // Access the variable tree through reflection since fields are private
        try {
            // Get regTreeNode (registers/local variables)
            java.lang.reflect.Field regField = JDebuggerPanel.class.getDeclaredField("regTreeNode");
            regField.setAccessible(true);
            DefaultMutableTreeNode regTreeNode = (DefaultMutableTreeNode) regField.get(debuggerPanel);
            
            // Get thisTreeNode (object fields)
            java.lang.reflect.Field thisField = JDebuggerPanel.class.getDeclaredField("thisTreeNode");
            thisField.setAccessible(true);
            DefaultMutableTreeNode thisTreeNode = (DefaultMutableTreeNode) thisField.get(debuggerPanel);
            
            // Extract register variables
            List<Map<String, Object>> registers = extractTreeNodeData(regTreeNode);
            variables.put("registers", registers);
            
            // Extract 'this' object fields
            List<Map<String, Object>> thisFields = extractTreeNodeData(thisTreeNode);
            variables.put("thisObject", thisFields);
            
            ctx.json(variables);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            ctx.status(500).json(Map.of("error", "Failed to access tree nodes: " + e.getMessage()));
        }
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to get variables: " + e.getMessage()));
    }
}

/**
 * Helper method to extract data from JTree nodes
 * Uses standard Swing TreeNode API
 */
private List<Map<String, Object>> extractTreeNodeData(DefaultMutableTreeNode node) {
    List<Map<String, Object>> result = new ArrayList<>();
    
    // Iterate through all children of the node
    for (int i = 0; i < node.getChildCount(); i++) {
        TreeNode childNode = node.getChildAt(i);
        
        if (childNode instanceof JDebuggerPanel.ValueTreeNode) {
            JDebuggerPanel.ValueTreeNode valueNode = (JDebuggerPanel.ValueTreeNode) childNode;
            
            Map<String, Object> varInfo = new HashMap<>();
            varInfo.put("name", valueNode.getName());
            varInfo.put("value", valueNode.getValue());
            varInfo.put("type", valueNode.getType());
            varInfo.put("typeId", valueNode.getTypeID());
            varInfo.put("updated", valueNode.isUpdated());
            
            // Recursively extract children if any
            if (valueNode.getChildCount() > 0) {
                varInfo.put("children", extractTreeNodeData(valueNode));
            }
            
            result.add(varInfo);
        }
    }
    
    return result;
}

/**
 * Get stack frames from JList UI component
 * Uses DefaultListModel API
 */
private void handleGetStackFrames(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return;
        }
        
        if (!controller.isSuspended()) {
            ctx.status(400).json(Map.of("error", "Process not suspended. Stack frames only available when paused."));
            return;
        }
        
        try {
            // Access stackFrameList through reflection
            java.lang.reflect.Field stackField = JDebuggerPanel.class.getDeclaredField("stackFrameList");
            stackField.setAccessible(true);
            @SuppressWarnings("unchecked")
            JList<JDebuggerPanel.IListElement> stackFrameList = (JList<JDebuggerPanel.IListElement>) stackField.get(debuggerPanel);
            
            // Get the list model
            DefaultListModel<JDebuggerPanel.IListElement> model = 
                (DefaultListModel<JDebuggerPanel.IListElement>) stackFrameList.getModel();
            
            List<String> frames = new ArrayList<>();
            
            // Iterate through all elements in the list
            for (int i = 0; i < model.getSize(); i++) {
                JDebuggerPanel.IListElement element = model.getElementAt(i);
                frames.add(element.toString());
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("stackFrames", frames);
            result.put("count", frames.size());
            
            ctx.json(result);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            ctx.status(500).json(Map.of("error", "Failed to access stack frame list: " + e.getMessage()));
        }
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to get stack frames: " + e.getMessage()));
    }
}

/**
 * Get threads from JComboBox UI component
 * Uses DefaultComboBoxModel API
 */
private void handleGetThreads(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        IDebugController controller = debuggerPanel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return;
        }
        
        try {
            // Access threadBox through reflection
            java.lang.reflect.Field threadField = JDebuggerPanel.class.getDeclaredField("threadBox");
            threadField.setAccessible(true);
            @SuppressWarnings("unchecked")
            JComboBox<JDebuggerPanel.IListElement> threadBox = 
                (JComboBox<JDebuggerPanel.IListElement>) threadField.get(debuggerPanel);
            
            // Get the combo box model
            DefaultComboBoxModel<JDebuggerPanel.IListElement> model = 
                (DefaultComboBoxModel<JDebuggerPanel.IListElement>) threadBox.getModel();
            
            List<String> threads = new ArrayList<>();
            String selectedThread = null;
            
            // Iterate through all elements in the combo box
            for (int i = 0; i < model.getSize(); i++) {
                JDebuggerPanel.IListElement element = model.getElementAt(i);
                threads.add(element.toString());
            }
            
            // Get selected thread
            Object selected = model.getSelectedItem();
            if (selected != null) {
                selectedThread = selected.toString();
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("threads", threads);
            result.put("selectedThread", selectedThread);
            result.put("count", threads.size());
            
            ctx.json(result);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            ctx.status(500).json(Map.of("error", "Failed to access thread box: " + e.getMessage()));
        }
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to get threads: " + e.getMessage()));
    }
}

/**
 * ADVANCED: Get breakpoints by inspecting SmaliArea components
 * This requires access to all open SmaliArea tabs
 */
/**
 * ADVANCED: Get breakpoints by inspecting SmaliArea components
 */
private void handleListBreakpoints(Context ctx) {
    try {
        JDebuggerPanel debuggerPanel = mainWindow.getDebuggerPanel();
        if (debuggerPanel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return;
        }
        
        List<Map<String, Object>> breakpoints = new ArrayList<>();
        
        // Access TabbedPane and find open tabs
        try {
            // Get the actual JTabbedPane component
            java.lang.reflect.Field tabbedPaneField = mainWindow.getTabbedPane().getClass().getDeclaredField("tabbedPane");
            tabbedPaneField.setAccessible(true);
            javax.swing.JTabbedPane tabbedPane = (javax.swing.JTabbedPane) tabbedPaneField.get(mainWindow.getTabbedPane());
            
            // Iterate through all open tabs
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                Component comp = tabbedPane.getComponentAt(i);
                
                // Find SmaliArea components recursively
                SmaliArea smaliArea = findSmaliArea(comp);
                if (smaliArea != null) {
                    try {
                        // Access breakpoint information through reflection
                        java.lang.reflect.Field bpField = SmaliArea.class.getDeclaredField("breakpoints");
                        bpField.setAccessible(true);
                        
                        @SuppressWarnings("unchecked")
                        Set<Integer> bps = (Set<Integer>) bpField.get(smaliArea);
                        
                        if (bps != null && !bps.isEmpty()) {
                            for (Integer line : bps) {
                                Map<String, Object> bp = new HashMap<>();
                                bp.put("class", smaliArea.getNode().getName());
                                bp.put("line", line);
                                breakpoints.add(bp);
                            }
                        }
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        // Breakpoint field might not exist or be accessible
                        logger.debug("Could not access breakpoints for tab: " + e.getMessage());
                    }
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            logger.error("Could not access tabbedPane", e);
            ctx.status(500).json(Map.of("error", "Could not access tabs: " + e.getMessage()));
            return;
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("breakpoints", breakpoints);
        result.put("count", breakpoints.size());
        
        ctx.json(result);
    } catch (Exception e) {
        logger.error("JADX AI MCP Debug Error: " + e.getMessage(), e);
        ctx.status(500).json(Map.of("error", "Failed to list breakpoints: " + e.getMessage()));
    }
}


/**
 * Helper method to find SmaliArea component in component tree
 */
private SmaliArea findSmaliArea(Component comp) {
    if (comp instanceof SmaliArea) {
        return (SmaliArea) comp;
    }
    
    if (comp instanceof java.awt.Container) {
        java.awt.Container container = (java.awt.Container) comp;
        for (Component child : container.getComponents()) {
            SmaliArea result = findSmaliArea(child);
            if (result != null) {
                return result;
            }
        }
    }
    
    return null;
}

/**
 * NOT IMPLEMENTABLE - Breakpoints are managed by SmaliArea UI component
 */
private void handleRemoveBreakpoint(Context ctx) {
    ctx.status(501).json(Map.of(
        "error", "Not implemented",
        "reason", "JADX breakpoints are UI-managed. Remove manually in Smali view (F2)."
    ));
}


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
}
