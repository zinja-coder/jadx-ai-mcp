package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HashMap;

import com.zin.jadxaimcp.utils.PaginationUtils;
import com.zin.jadxaimcp.utils.PaginationUtils.PaginationException;
import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;
import com.zin.jadxaimcp.utils.SearchProgressTracker;

public class MethodRoutes {
    private static final Logger logger = LoggerFactory.getLogger(MethodRoutes.class);
    private final MainWindow mainWindow;
    private final PaginationUtils paginationUtils;
    private final SearchProgressTracker progressTracker = SearchProgressTracker.getInstance();

    public MethodRoutes(MainWindow mainWindow, PaginationUtils paginationUtils) {
        this.mainWindow = mainWindow;
        this.paginationUtils = paginationUtils;
    }

    /**
     * @return void
     * @param Context
     * 
     * This method handle the /method-by-name mcp tool call.
     * 
     * First validate the essential 'method_name' param, Then using jadxwrapper,
     * 1. if no classname is provided in http request, then search for the requested method
     * in all classes.
     *  - get list of classes
     *  - get all the methods of all classes one by one
     *  - check the if any match found and return it 
     * 2. If 'class_name' is present in http request, then 
     *  - get all the methods of that class
     *  - check if any match is found and return it
     */
    public void handleMethodByName(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodSignature = ctx.queryParam("method_signature");

        String methodName = validateMethodParam(ctx);
        if (methodName == null) return;
        
        // Support signature embedded in method_name
        if (methodName.contains("(")) {
            if (methodSignature == null || methodSignature.isEmpty()) {
                methodSignature = methodName.substring(methodName.indexOf('('));
            }
            methodName = methodName.substring(0, methodName.indexOf('('));
        }

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            if (wrapper == null) {
                JadxAIMCPPluginError.handleError(ctx, 500, "JadxWrapper not initialized", logger);
                return;
            }

            // Case 1: Search in all classes if no class name provided
            if (className == null || className.isEmpty()) {
                for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                    for (JavaMethod method : cls.getMethods()) {
                        if (method.getName().equalsIgnoreCase(methodName)) {
                            if (methodSignature != null && !methodSignature.isEmpty()) {
                                String shortId = method.getMethodNode().getMethodInfo().getShortId();
                                if (!shortId.contains(methodSignature)) {
                                    continue;
                                }
                            }
                            returnMethodResult(ctx, cls, method);
                            return;
                        }
                    }
                }
            } 
            // Case 2: Search in specific class
            else {
                for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                    if (cls.getFullName().equals(className)) {
                        for (JavaMethod method : cls.getMethods()) {
                            if (method.getName().equalsIgnoreCase(methodName)) {
                                if (methodSignature != null && !methodSignature.isEmpty()) {
                                    String shortId = method.getMethodNode().getMethodInfo().getShortId();
                                    if (!shortId.contains(methodSignature)) {
                                        continue;
                                    }
                                }
                                returnMethodResult(ctx, cls, method);
                                return;
                            }
                        }
                    }
                }
            }

            // if execution reaches here, it means that method has not been found
            JadxAIMCPPluginError.handleError(ctx, 404, "Requested method " + methodName + " not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error occurred while retrieving method: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     * This method handles the /seach-method mcp tool call.
     * 
     * After validating the 'method_name' parameter.
     * 
     * 1. for each java class
     *  - for each method in that class
     *      - if it matches the search term then add it to results
     * 2. return the results.
     */
    public void handleSearchMethod(Context ctx) {
        String methodName = validateMethodParam(ctx);
        if (methodName == null) return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            if (wrapper == null) {
                JadxAIMCPPluginError.handleError(ctx, 500, "JadxWrapper not initialized", logger);
                return;
            }
            List<JavaClass> allClasses = wrapper.getIncludedClassesWithInners();
            String searchId = progressTracker.startSearch("method:" + methodName, allClasses.size());

            try {
                String lowerMethodName = methodName.toLowerCase();
                List<String> results = allClasses.parallelStream()
                        .filter(cls -> {
                            progressTracker.incrementScanned();
                            boolean matched = false;
                            for (JavaMethod method : cls.getMethods()) {
                                if (method.getName().toLowerCase().contains(lowerMethodName)) {
                                    matched = true;
                                    break;
                                }
                                // Also match constructors against class simple name
                                if (method.isConstructor()) {
                                    String classSimpleName = cls.getName().toLowerCase();
                                    if (classSimpleName.contains(lowerMethodName)) {
                                        matched = true;
                                        break;
                                    }
                                }
                            }
                            if (matched) {
                                progressTracker.incrementMatches();
                            }
                            return matched;
                        })
                        .map(JavaClass::getFullName)
                        .collect(Collectors.toList());

                progressTracker.completeSearch(searchId, results.size());
                ctx.result(String.join("\n", results));
            } catch (Exception e) {
                progressTracker.failSearch(searchId, e.getMessage());
                throw e;
            }
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error during method search: " + e.getMessage(), e, logger);
        }    
    }
    // Helper methods

    /**
     * @param Context
     * @return String
     * 
     * Checks if the HTTP request contains the 'method_name' param or not, if yes then returns it
     * else returns null and handles the error.
     */
    private String validateMethodParam(Context ctx) {
        String methodName = ctx.queryParam("method_name");
        if (methodName == null || methodName.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameter 'method_name'", logger);
            return null;
        }
        return methodName;
    }

    /**
     * @return void
     * @param Context, JavaClass, JavaMethod
     * 
     * This helper method is used to build and return the found method code
     * 1. Get the method code
     * 2. Build the Map of the result.
     * 3. return json result.
     */
    private void returnMethodResult(Context ctx, JavaClass cls, JavaMethod method) {
        String codeStr;
        try {
            codeStr = method.getCodeStr();
        } catch (Exception e) {
            logger.error("JADX AI MCP ERROR: Error retrieving code: " + e.getMessage());
            codeStr = "Error retrieving code: " + e.getMessage();
        }

        Map<String, String> result = new HashMap<>();
        result.put("class_name", cls.getFullName());
        result.put("method_name", method.getName());
        result.put("decl", String.valueOf(method.getCodeNodeRef()));
        result.put("code", codeStr);
        ctx.json(result);
    }
}
