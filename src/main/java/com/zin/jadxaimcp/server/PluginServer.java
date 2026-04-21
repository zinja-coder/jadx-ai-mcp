package com.zin.jadxaimcp.server;

import io.javalin.Javalin;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxaimcp.utils.JadxAIMCPBanner;
import com.zin.jadxaimcp.utils.PaginationUtils;
import com.zin.jadxaimcp.server.routes.*; // MCP tool call's request handlers

public class PluginServer {
    private static final Logger logger = LoggerFactory.getLogger(PluginServer.class);
    // JVM-wide key to store the ServerSocketChannel for cross-classloader shutdown
    private static final String JVM_SERVER_KEY = "jadx-ai-mcp-server-channel";
    private final MainWindow mainWindow;
    private final int port;
    private Javalin app;
    private final PaginationUtils paginationUtils;
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
            app = Javalin.create(config -> {
                config.showJavalinBanner = false;
            }).start(port);

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

            // warm the inheritance index in the background. Started AFTER the server
            // is up so that, 1, any client request landing during warm-up still
            // succeeds (it will block on the same lock and reuse the result) and
            // 2, a warm-up failure cannot prevent the server from starting.
            // Daemon thread, lowest priority, exception-safe.
            com.zin.jadxaimcp.server.routes.AdvancedRoutes.warmInheritanceIndexAsync(mainWindow);

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
        AdvancedRoutes advancedRoutes = new AdvancedRoutes(mainWindow, paginationUtils);

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
        app.get("/rename-class", refactoringRoutes::handleRenameClass);
        app.get("/rename-method", refactoringRoutes::handleRenameMethod);
        app.get("/rename-field", refactoringRoutes::handleRenameField);
        app.get("/rename-package", refactoringRoutes::handleRenamePackage);
        app.get("/rename-variable", refactoringRoutes::handleRenameVariable);

        // --- Debugging ---
        app.get("/debug/stack-frames", debugRoutes::handleGetStackFrames);
        app.get("/debug/variables", debugRoutes::handleGetVariables);
        app.get("/debug/threads", debugRoutes::handleGetThreads);

        // advanced RE capabilities (added v6.4.0)
        // high-leverage tools that eliminate the dead-ends hit when
        // tracing flow through obfuscated APKs without a fully-indexed code search.
        app.get("/find-string-literals", advancedRoutes::handleFindStringLiterals);
        app.get("/grep-code", advancedRoutes::handleGrepCode);
        app.get("/find-methods-by-signature", advancedRoutes::handleFindMethodsBySignature);
        app.get("/get-callees", advancedRoutes::handleGetCallees);
        app.get("/get-subclasses", advancedRoutes::handleGetSubclasses);
        app.get("/get-superclasses", advancedRoutes::handleGetSuperclasses);
        app.get("/get-implementations", advancedRoutes::handleGetImplementations);
        app.get("/find-android-components-deep", advancedRoutes::handleFindAndroidComponentsDeep);
    }

}