package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import com.zin.jadxaimcp.server.PluginServer;
import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;

public class GeneralRoutes {
    private static final Logger logger = LoggerFactory.getLogger(GeneralRoutes.class);
    private final PluginServer server;

    public GeneralRoutes(int port, PluginServer server) {
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
            result.put("mode", server.getProjectMode());
            Map<String, Object> security = new LinkedHashMap<>(server.getSecurityConfig().statusForHealth());
            if ("headless".equals(server.getProjectMode())) {
                security.put("refactor_disabled", true);
                security.put("debug_disabled", true);
                security.put("headless_gui_only_tools_disabled", true);
            }
            result.put("security", security);

            logger.debug("JADX AI MCP Plugin: GOT HEALTH PING");
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal Error while trying to handle health ping request: " + e.getMessage(), e, logger);
        }
    }
    
}
