package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;

import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.instructions.args.SSAVar;
import jadx.api.metadata.annotations.VarNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;

public class RefactoringRoutes {
    private static final Logger logger = LoggerFactory.getLogger(RefactoringRoutes.class);
    private final MainWindow mainWindow;

    public RefactoringRoutes(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
    }

    /**
     * @return void
     * @param Context
     * 
     *                This routing method handle the /rename-class mcp tool call's
     *                http request, After validating the
     *                required http params, it tries to find the class which has to
     *                be renamed. If it is found
     *                then it renames it using NodeRenamedByUser class' events
     *                methods 'setRenameNode' and 'setResetName'.
     *                Then it sends these events using MainWindows's send() method.
     */
    public void handleRenameClass(Context ctx) {
        String className = ctx.queryParam("class_name");
        String newName = ctx.queryParam("new_name");

        if (validateParams(ctx, className, newName))
            return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    ICodeNodeRef nodeRef = cls.getCodeNodeRef();
                    NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, cls.getName(), newName);
                    event.setRenameNode(cls.getClassNode());
                    event.setResetName(newName.isEmpty());
                    mainWindow.events().send(event);

                    logger.info("Renaming Class {} to {}", cls.getName(), newName);
                    ctx.json(Map.of("result", "Renamed Class " + cls.getName() + " to " + newName));
                    return;
                }
            }
            JadxAIMCPPluginError.handleError(ctx, 404, "Class " + className + " not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to rename the class: " + e.getMessage(),
                    e, logger);
        }
    }

    /**
     * @param
     * @return
     * 
     *         This routing method handle the /rename-method mcp tool call's http
     *         request, After validating the
     *         required http params, it tries to find the class whose method has to
     *         be renamed. If it is found
     *         then it renames it using NodeRenamedByUser class' events methods
     *         'setRenameNode' and 'setResetName'.
     *         Then it sends these events using MainWindows's send() method.
     * 
     */
    public void handleRenameMethod(Context ctx) {
        String methodName = ctx.queryParam("method_name");
        String newName = ctx.queryParam("new_name");
        String methodSignature = ctx.queryParam("method_signature");

        if (validateParams(ctx, methodName, newName))
            return;

        // Strip method signature if present
        if (methodName.contains("(")) {
            if (methodSignature == null || methodSignature.isEmpty()) {
                methodSignature = methodName.substring(methodName.indexOf('('));
            }
            methodName = methodName.substring(0, methodName.indexOf('('));
        }

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                // Fix: removed the .replace('$', '.'); from below line to
                // prevent bug where innerclasses are discoverable
                String clsName = cls.getFullName();
                for (JavaMethod method : cls.getMethods()) {
                    String fullMethodName = clsName + "." + method.getName();
                    if (fullMethodName.equalsIgnoreCase(methodName) || method.getName().equalsIgnoreCase(methodName)) {
                        if (methodSignature != null && !methodSignature.isEmpty()) {
                            String shortId = method.getMethodNode().getMethodInfo().getShortId();
                            if (!shortId.contains(methodSignature)) {
                                continue;
                            }
                        }
                        
                        ICodeNodeRef nodeRef = method.getCodeNodeRef();
                        NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, method.getName(), newName);
                        event.setRenameNode(method.getMethodNode());
                        event.setResetName(newName.isEmpty());
                        mainWindow.events().send(event);

                        logger.info("Renaming method {} to {}", method.getName(), newName);
                        ctx.json(Map.of("result", "Rename method " + method.getName() + " to " + newName));
                        return;
                    }
                }
            }
            JadxAIMCPPluginError.handleError(ctx, 404,
                    "Either Class not found or the Method " + methodName + " not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to rename the method: " + e.getMessage(),
                    e, logger);
        }
    }

    /**
     * 
     * @param ctx
     * @return
     * 
     *         This routing method handle the /rename-field mcp tool call's http
     *         request, After validating the
     *         required http params, it tries to find the class whose method has to
     *         be renamed. If it is found
     *         then it renames it using NodeRenamedByUser class' events methods
     *         'setRenameNode' and 'setResetName'.
     *         Then it sends these events using MainWindows's send() method.
     */
    public void handleRenameField(Context ctx) {
        String className = ctx.queryParam("class_name");
        String oldFieldName = ctx.queryParam("field_name");
        String newFieldName = ctx.queryParam("new_field_name");

        if (validateParams(ctx, className, oldFieldName, newFieldName))
            return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    for (JavaField field : cls.getFields()) {
                        if (field.getName().equals(oldFieldName)) {
                            ICodeNodeRef nodeRef = field.getCodeNodeRef();
                            NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, field.getName(), newFieldName);
                            event.setRenameNode(field.getFieldNode());
                            event.setResetName(newFieldName.isEmpty());
                            mainWindow.events().send(event);

                            logger.info("Renaming field {} to {}", field.getName(), newFieldName);
                            ctx.json(Map.of("result", "Renamed field " + field.getName() + " to " + newFieldName));
                            return;
                        }
                    }
                }
            }
            JadxAIMCPPluginError.handleError(ctx, 404,
                    "Either Class " + className + " not found or the Field not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to rename the field: " + e.getMessage(),
                    e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     *                This routing method handle the /rename-variable mcp tool
     *                call's http request.
     *                It validates required params: class_name, method_name,
     *                variable_name, new_name.
     *                It tries to find the method and then iterates over its SSA
     *                variables to find the matching variable.
     *                If found, it renames it using NodeRenamedByUser event.
     */
    public void handleRenameVariable(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodName = ctx.queryParam("method_name");
        String variableName = ctx.queryParam("variable_name");
        String newName = ctx.queryParam("new_name");

        // Optional params for more specific targeting
        String regStr = ctx.queryParam("reg");
        String ssaStr = ctx.queryParam("ssa");

        if (validateParams(ctx, className, methodName, variableName, newName))
            return;

        // Strip method signature if present
        if (methodName.contains("(")) {
            methodName = methodName.substring(0, methodName.indexOf('('));
        }

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();

            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    for (JavaMethod method : cls.getMethods()) {
                        String fullMethodName = cls.getFullName() + "." + method.getName();
                        if (method.getName().equals(methodName) || fullMethodName.equalsIgnoreCase(methodName)) {
                            MethodNode methodNode = method.getMethodNode();
                            if (methodNode == null)
                                continue;

                            List<SSAVar> sVars = methodNode.getSVars();

                            // Ensure class is processed to populate SSA variables
                            if (sVars.isEmpty()) {
                                logger.info("SSA variables empty for method {}, forcing class reload and processing...",
                                        method.getName());
                                try {
                                    // defined class need to be unloaded to reset state and allow full processing
                                    cls.getClassNode().unload();
                                    cls.getClassNode().root().getProcessClasses().forceProcess(cls.getClassNode());

                                    // Re-fetch method node and sVars after processing because unload/load recreates
                                    // MethodNode objects
                                    MethodNode newMethodNode = cls.getClassNode()
                                            .searchMethodByShortName(method.getName());
                                    if (newMethodNode != null) {
                                        methodNode = newMethodNode;
                                        sVars = methodNode.getSVars();
                                        logger.info("Class reloaded. New SSA variables count: {}",
                                                sVars != null ? sVars.size() : "null");
                                    } else {
                                        logger.error("Failed to find method {} after reload", method.getName());
                                    }

                                } catch (Exception e) {
                                    logger.error("Failed to force process class {}", cls.getName(), e);
                                }
                            }

                            if (sVars == null || sVars.isEmpty())
                                continue;

                            for (SSAVar sVar : sVars) {
                                boolean nameMatch = variableName.equals(sVar.getName());
                                boolean regMatch = regStr == null || regStr.isEmpty()
                                        || String.valueOf(sVar.getRegNum()).equals(regStr);
                                boolean ssaMatch = ssaStr == null || ssaStr.isEmpty()
                                        || String.valueOf(sVar.getVersion()).equals(ssaStr);
                                if (nameMatch && regMatch && ssaMatch) {
                                    VarNode varNode = VarNode.get(methodNode, sVar);
                                    if (varNode != null) {
                                        NodeRenamedByUser event = new NodeRenamedByUser(varNode, variableName, newName);
                                        event.setRenameNode(varNode);
                                        event.setResetName(newName.isEmpty());
                                        mainWindow.events().send(event);

                                        logger.info("Renamed variable {} to {} in method {}", variableName, newName,
                                                method.getName());
                                        ctx.json(
                                                Map.of("result",
                                                        "Renamed variable " + variableName + " to " + newName));
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            JadxAIMCPPluginError.handleError(ctx, 404,
                    "Variable " + variableName + " not found in method " + methodName, logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error while trying to rename the variable: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     *                This routing method handle the /rename-package mcp tool call's
     *                http request, After validating the
     *                required http params, It iterates over list of class one by
     *                one under the oldpackage and
     *                then it renames it using NodeRenamedByUser class' events
     *                methods 'setRenameNode' and 'setResetName'.
     *                Then it sends these events using MainWindows's send() method.
     */
    public void handleRenamePackage(Context ctx) {
        String oldPackage = ctx.queryParam("old_package_name");
        String newPackage = ctx.queryParam("new_package_name");

        if (validateParams(ctx, oldPackage, newPackage))
            return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<String> errors = new ArrayList<>();
            int count = 0;
            int total = 0;

            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                String fullName = cls.getFullName();
                if (fullName.startsWith(oldPackage + ".") || fullName.equals(oldPackage)) {
                    total++;
                    try {
                        String relativePath = fullName.substring(oldPackage.length());
                        String newFullName = newPackage + relativePath;

                        NodeRenamedByUser event = new NodeRenamedByUser(cls.getCodeNodeRef(), cls.getName(),
                                newFullName);
                        event.setRenameNode(cls.getClassNode());
                        event.setResetName(false);
                        mainWindow.events().send(event);
                        count++;
                    } catch (Exception e) {
                        errors.add("Failed to rename " + fullName + ": " + e.getMessage());
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("renamed", count);
            result.put("total", total);
            result.put("errors", errors);
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error occurred while trying to rename the package: " + e.getMessage(), e, logger);
        }
    }

    // Helper methods

    /**
     * @param Context, String, String
     * @return boolean
     * 
     *         This method is used to validate the availability of required http
     *         params in RefactoringRoutes
     *         MCP tool's HTTP requests. If params are ok return true else return
     *         false.
     */
    private boolean validateParams(Context ctx, String p1, String p2) {
        if (p1 == null || p1.isEmpty() || p2 == null || p2.isEmpty()) {
            // ctx.status(400).json(Map.of("error", "Missing required parameters."));
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameters", logger);
            return true;
        }
        return false;
    }

    /**
     * @param Context, String, String, String
     * @return boolean
     * 
     *         This method is used to validate the availability of required http
     *         params in RefactoringRoutes
     *         MCP tool's HTTP requests. If params are ok return true else return
     *         false.
     */
    private boolean validateParams(Context ctx, String p1, String p2, String p3) {
        if (p1 == null || p1.isEmpty() || p2 == null || p2.isEmpty()) {
            // ctx.status(400).json(Map.of("error", "Missing required parameters."));
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameters", logger);
            return true;
        }
        return false;
    }

    /**
     * @param Context, String, String, String, String
     * @return boolean
     * 
     *         This method is used to validate the availability of required http
     *         params in RefactoringRoutes
     *         MCP tool's HTTP requests. If params are ok return true else return
     *         false.
     */
    private boolean validateParams(Context ctx, String p1, String p2, String p3, String p4) {
        if (p1 == null || p1.isEmpty() || p2 == null || p2.isEmpty() || p3 == null || p3.isEmpty() || p4 == null
                || p4.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameters", logger);
            return true;
        }
        return false;
    }

}
