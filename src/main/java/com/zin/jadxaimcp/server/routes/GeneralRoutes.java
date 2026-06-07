package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.gui.ui.MainWindow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import com.zin.jadxaimcp.server.PluginServer;
import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;

public class GeneralRoutes {
    private static final Logger logger = LoggerFactory.getLogger(GeneralRoutes.class);
    private final MainWindow mainWindow;
    private final PluginServer server;

    public GeneralRoutes(MainWindow mainWindow, int port, PluginServer server) {
        this.mainWindow = mainWindow;
        this.server = server;
    }

    /**
     * @name handleHealth
     * @param ctx - The jadx plugin server context
     * @return void
     * 
     * This method is handles health-check request which is kind of ping 
     * from jadx_mcp_server.py from mcp server to check if this 
     * plugin server is running or not.
     * 
     * It first checks the status of server using isRunning variable,
     * if it is running returns "Running" and "url" in json response
     * else return Stopped and N/A
     */
    public void handleHealth(Context ctx) {
        try {
            boolean isRunning = server.isRunning();
            String status = isRunning ? "Running" : "Stopped";
            String url = isRunning ? "http://127.0.0.1:" + server.getPort() + "/" : "N/A";

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("url", url);
            result.put("security", server.getSecurityConfig().statusForHealth());

            logger.debug("JADX AI MCP Plugin: GOT HEALTH PING");
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal Error while trying to handle health ping request: " + e.getMessage(), e, logger);
        }
    }
    
}
