package com.zin.jadxaimcp.server;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxaimcp.utils.JadxAIMCPBanner;
import com.zin.jadxaimcp.utils.PaginationUtils;
import com.zin.jadxaimcp.utils.SecurityConfig;
import com.zin.jadxaimcp.utils.UntrustedArtifactUtils;
import com.zin.jadxaimcp.server.routes.*; // MCP tool call's request handlers

import java.util.Map;
import java.util.prefs.Preferences;

public class PluginServer {
    private static final Logger logger = LoggerFactory.getLogger(PluginServer.class);
    // JVM-wide key to store the ServerSocketChannel for cross-classloader shutdown
    private static final String JVM_SERVER_KEY = "jadx-ai-mcp-server-channel";
    private final MainWindow mainWindow;
    private final int port;
    private Javalin app;
    private final PaginationUtils paginationUtils;
    private SecurityConfig securityConfig;
    private volatile boolean isRunning = false;

    /**
     * @param mainWindows - The main Jadx window context
     * @param port        - The port to listen on
     */
    public PluginServer(MainWindow mainWindow, int port) {
        this.mainWindow = mainWindow;
        this.port = port;
        this.paginationUtils = new PaginationUtils();
    }

    /**
     * Starts the Javalin HTTP server for the MCP plugin.
     * Before starting, it closes any existing server socket from a previous
     * classloader to prevent port conflicts.
     */
    public void start() {
        try {
            // This solves github issue -> #81
            // Close any existing server socket from a previous classloader
            // (e.g. after Reset Code Cache)
            closeExistingServerSocket();

            // Configure and start Javalin
            securityConfig = SecurityConfig.load(Preferences.userNodeForPackage(SecurityConfig.class));
            app = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.jetty.defaultHost = SecurityConfig.LOOPBACK_HOST;
            });
            app.before(this::enforceRequestSecurity);
            app.start(SecurityConfig.LOOPBACK_HOST, port);

            // Extract and store the underlying ServerSocketChannel (JDK class) JVM-wide
            // so future classloaders can close it even if the old classloader is broken
            storeServerSocketChannel();

            // Register all route handlers
            registerRoutes();

            isRunning = true;

            // Log startup success and banner
            logger.info(JadxAIMCPBanner.banner);
            logger.info("// -------------------- JADX AI MCP PLUGIN -------------------- //");
            logger.info("JADX AI MCP Plugin HTTP Server Started at http://127.0.0.1:" + port + "/");
            logger.info("JADX AI MCP Plugin auth required: {}, token source: {}",
                    !securityConfig.isAuthDisabled(), securityConfig.getTokenSource());

        } catch (Exception e) {
            logger.error("JADX-AI-MCP Plugin Error: Could not start HTTP Server. Exception: " + e.getMessage(), e);
            isRunning = false;
            // Re-throw to let the main plugin know startup failed
            throw new RuntimeException("Failed to start Javalin Server", e);
        }
    }

    /**
     * Performs graceful shutdown of the Javalin server.
     */
    public void stop() {
        if (app != null) {
            try {
                app.stop();
                System.getProperties().remove(JVM_SERVER_KEY);
                logger.info("JADX-AI-MCP Plugin: HTTP Server Stopped");
            } catch (Exception e) {
                logger.error("JADX-AI-MCP Plugin Error: Error during shutdown: " + e.getMessage(), e);
            } finally {
                app = null;
                isRunning = false;
            }
        }
    }

    /**
     * Extracts the underlying ServerSocketChannel from Javalin/Jetty via reflection
     * and stores it in JVM-wide System properties. This is done at start time when
     * the classloader is valid. The ServerSocketChannel is a JDK class and can be
     * closed later without any dependency on the plugin's classloader.
     *
     * Javalin 6 / Jetty 11 reflection chain:
     * Javalin -> jettyServer() -> server() -> getConnectors()[0] -> getTransport()
     */
    private void storeServerSocketChannel() {
        try {
            Object jettyServer = app.getClass().getMethod("jettyServer").invoke(app);
            Object server = jettyServer.getClass().getMethod("server").invoke(jettyServer);
            Object[] connectors = (Object[]) server.getClass().getMethod("getConnectors").invoke(server);
            if (connectors != null && connectors.length > 0) {
                Object transport = connectors[0].getClass().getMethod("getTransport").invoke(connectors[0]);
                if (transport instanceof java.nio.channels.ServerSocketChannel) {
                    System.getProperties().put(JVM_SERVER_KEY, transport);
                    logger.debug("JADX-AI-MCP Plugin: Stored ServerSocketChannel for cross-classloader cleanup");
                }
            }
        } catch (Exception e) {
            logger.warn("JADX-AI-MCP Plugin: Could not store server socket channel: " + e.getMessage());
        }
    }

    /**
     * Closes any existing ServerSocketChannel stored by a previous plugin instance.
     * This directly releases the port at the OS level without needing to call any
     * methods on the broken old classloader's Javalin/Jetty objects.
     */
    private void closeExistingServerSocket() {
        Object stored = System.getProperties().get(JVM_SERVER_KEY);
        if (stored instanceof java.nio.channels.ServerSocketChannel) {
            try {
                java.nio.channels.ServerSocketChannel channel = (java.nio.channels.ServerSocketChannel) stored;
                if (channel.isOpen()) {
                    logger.info("JADX-AI-MCP Plugin: Closing existing server socket from previous classloader...");
                    channel.close();
                    // Wait for the OS to fully release the port
                    Thread.sleep(1000);
                    logger.info("JADX-AI-MCP Plugin: Previous server socket closed, port released.");
                }
            } catch (Exception e) {
                logger.warn("JADX-AI-MCP Plugin: Error closing old server socket: " + e.getMessage());
            } finally {
                System.getProperties().remove(JVM_SERVER_KEY);
            }
        }
    }

    /**
     * @return boolean True if server is running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * @return int The port number the server is configured to listen on
     */
    public int getPort() {
        return port;
    }

    public SecurityConfig getSecurityConfig() {
        return securityConfig;
    }

    /**
     * Registers all HTTP API endpoints with their route handlers.
     */
    private void registerRoutes() {
        // Instantiate Route Controllers
        // Passing 'mainWindow' and 'paginationUtils' to them so they can do their work
        GeneralRoutes generalRoutes = new GeneralRoutes(mainWindow, port, this);
        ClassRoutes classRoutes = new ClassRoutes(mainWindow, paginationUtils);
        MethodRoutes methodRoutes = new MethodRoutes(mainWindow, paginationUtils);
        ResourceRoutes resourceRoutes = new ResourceRoutes(mainWindow);
        RefactoringRoutes refactoringRoutes = new RefactoringRoutes(mainWindow);
        DebugRoutes debugRoutes = new DebugRoutes(mainWindow);
        XrefsRoutes xrefsRoutes = new XrefsRoutes(mainWindow);

        // --- General & Health ---
        app.get("/health", generalRoutes::handleHealth);

        // --- Class & Code Navigation ---
        app.get("/current-class", classRoutes::handleCurrentClass);
        app.get("/all-classes", classRoutes::handleAllClasses);
        app.get("/selected-text", classRoutes::handleSelectedText);
        app.get("/class-source", classRoutes::handleClassSource);
        app.get("/smali-of-class", classRoutes::handleSmaliOfClass);
        app.get("/methods-of-class", classRoutes::handleMethodsOfClass);
        app.get("/fields-of-class", classRoutes::handleFieldsOfClass);
        app.get("/main-application-classes-code", classRoutes::handleMainApplicationClassesCode);
        app.get("/main-application-classes-names", classRoutes::handleMainApplicationClassesNames);
        app.get("/main-activity", classRoutes::handleMainActivity);
        app.get("/search-classes-by-keyword", classRoutes::handleSearchClassesByKeyword);
        app.get("/search-progress", classRoutes::handleSearchProgress);
        app.get("/package-tree", classRoutes::handleGetPackageTree);
        app.get("/cache-stats", classRoutes::handleCacheStats);
        app.post("/cache-clear", classRoutes::handleCacheClear);

        // --- Methods ---
        app.get("/method-by-name", methodRoutes::handleMethodByName);
        app.get("/search-method", methodRoutes::handleSearchMethod);

        // --- Xrefs ---
        app.get("/xrefs-to-class", xrefsRoutes::handleXrefsToClass);
        app.get("/xrefs-to-method", xrefsRoutes::handleXrefsToMethod);
        app.get("/xrefs-to-field", xrefsRoutes::handleXrefsToField);

        // --- Resources & Manifest ---
        app.get("/manifest", resourceRoutes::handleManifest);
        app.get("/strings", resourceRoutes::handleStrings);
        app.get("/list-all-resource-files-names", resourceRoutes::handleListAllResourceFilesNames);
        app.get("/get-resource-file", resourceRoutes::handleGetResourceFile);

        // --- Renaming ---
        if (securityConfig.isRefactorDisabled()) {
            app.get("/rename-class", ctx -> rejectDisabled(ctx, "refactoring"));
            app.get("/rename-method", ctx -> rejectDisabled(ctx, "refactoring"));
            app.get("/rename-field", ctx -> rejectDisabled(ctx, "refactoring"));
            app.get("/rename-package", ctx -> rejectDisabled(ctx, "refactoring"));
            app.get("/rename-variable", ctx -> rejectDisabled(ctx, "refactoring"));
            app.post("/rename-class", ctx -> rejectDisabled(ctx, "refactoring"));
            app.post("/rename-method", ctx -> rejectDisabled(ctx, "refactoring"));
            app.post("/rename-field", ctx -> rejectDisabled(ctx, "refactoring"));
            app.post("/rename-package", ctx -> rejectDisabled(ctx, "refactoring"));
            app.post("/rename-variable", ctx -> rejectDisabled(ctx, "refactoring"));
        } else {
            app.get("/rename-class", this::rejectGetMutation);
            app.get("/rename-method", this::rejectGetMutation);
            app.get("/rename-field", this::rejectGetMutation);
            app.get("/rename-package", this::rejectGetMutation);
            app.get("/rename-variable", this::rejectGetMutation);
            app.post("/rename-class", refactoringRoutes::handleRenameClass);
            app.post("/rename-method", refactoringRoutes::handleRenameMethod);
            app.post("/rename-field", refactoringRoutes::handleRenameField);
            app.post("/rename-package", refactoringRoutes::handleRenamePackage);
            app.post("/rename-variable", refactoringRoutes::handleRenameVariable);
        }

        // --- Debugging ---
        if (securityConfig.isDebugDisabled()) {
            app.get("/debug/stack-frames", ctx -> rejectDisabled(ctx, "debug"));
            app.get("/debug/variables", ctx -> rejectDisabled(ctx, "debug"));
            app.get("/debug/threads", ctx -> rejectDisabled(ctx, "debug"));
        } else {
            app.get("/debug/stack-frames", debugRoutes::handleGetStackFrames);
            app.get("/debug/variables", debugRoutes::handleGetVariables);
            app.get("/debug/threads", debugRoutes::handleGetThreads);
        }
    }

    private void enforceRequestSecurity(Context ctx) {
        if (!"/health".equals(ctx.path())) {
            UntrustedArtifactUtils.mark(ctx);
        }
        if (!securityConfig.isAuthorized(ctx)) {
            throw new UnauthorizedResponse("Missing or invalid Authorization bearer token");
        }
    }

    private void rejectGetMutation(Context ctx) {
        ctx.status(405).json(Map.of("error", "Refactoring endpoints require POST"));
    }

    private void rejectDisabled(Context ctx, String featureName) {
        ctx.status(403).json(Map.of("error", featureName + " tools are disabled by server configuration"));
    }

}
