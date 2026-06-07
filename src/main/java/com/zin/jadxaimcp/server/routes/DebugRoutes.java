package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.gui.ui.MainWindow;
import jadx.gui.ui.panel.IDebugController;
import jadx.gui.ui.panel.JDebuggerPanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;
import com.zin.jadxaimcp.utils.UntrustedArtifactUtils;

public class DebugRoutes {
    private static final Logger logger = LoggerFactory.getLogger(DebugRoutes.class);
    private final MainWindow mainWindow;

    public DebugRoutes(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
    }

    /**
     * @return void
     * @param Context
     * 
     * This method is used to get the StackFrames. 
     * It first get's the debuggerPanel (UI Component) using getDebuggerPanel() method.
     * Then it fetches the Field UI component holding stack frames' list.
     * It then sets the stackField as accessible to access the UI components
     * Then it gets the all stack frames in list 
     * from this list, it makes them in model to get their elements later
     * Using for loop it iterates over model size and get's all lists' elements as well.
     */
    public void handleGetStackFrames(Context ctx) {
        try {
            JDebuggerPanel debuggerPanel = getDebuggerPanel(ctx);
            if (debuggerPanel == null) return;

            Field stackField = JDebuggerPanel.class.getDeclaredField("stackFrameList");
            stackField.setAccessible(true);
            JList<?> stackFrameList = (JList<?>) stackField.get(debuggerPanel);
            DefaultListModel<?> model = (DefaultListModel<?>) stackFrameList.getModel();

            List<String> frames = new ArrayList<>();
            for (int i = 0; i < model.getSize(); i++) {
                frames.add(model.getElementAt(i).toString());
            }

            ctx.json(UntrustedArtifactUtils.withMetadata(Map.of("stackFrames", frames, "count", frames.size())));
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Failed to get stack frames: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @param Context
     * @return void
     * 
     * This method fetches the Threads from debugger panel
     * After getting the debuggerPanel, From debuggerPanel UI component,
     * It gets the Thread Field, makes this field accessible for accessing other UI components.
     * It then fetches the JComboBox from this threadField and stores them threadBox.
     * From threadBox it fetches the their model (UI) and from this it get's the elements.
     * It separately gets the selected thread details as well.
     */
    public void handleGetThreads(Context ctx) {
        try {
            JDebuggerPanel debuggerPanel = getDebuggerPanel(ctx);
            if (debuggerPanel == null) return;

            Field threadField = JDebuggerPanel.class.getDeclaredField("threadBox");
            threadField.setAccessible(true);
            JComboBox<?> threadBox = (JComboBox<?>) threadField.get(debuggerPanel);
            DefaultComboBoxModel<?> model = (DefaultComboBoxModel<?>) threadBox.getModel();

            List<String> threads = new ArrayList<>();
            for (int i = 0; i < model.getSize(); i++) {
                threads.add(model.getElementAt(i).toString());
            }
            String selected = model.getSelectedItem() != null ? model.getSelectedItem().toString() : null;

            Map<String, Object> result = new HashMap<>();
            result.put("threads", threads);
            result.put("selectedThread", selected);
            result.put("count", threads.size());
            ctx.json(UntrustedArtifactUtils.withMetadata(result));
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Error while trying to get the threads: " + e.getMessage(), e, logger);
        }
    }

    /**
     * 
     * @param Context
     * @return void
     * 
     * This method is used to fetch and return debug variables from debugger panel.
     * After getting the debuggerPanel, it fetches the register field.
     * Then it gets the 'this' field object 
     * Then it returns these both fields info.
     */
    public void handleGetVariables(Context ctx) {
        try {
            JDebuggerPanel debuggerPanel = getDebuggerPanel(ctx);
            if (debuggerPanel == null) return;

            // Get registers
            Field regField = JDebuggerPanel.class.getDeclaredField("regTreeNode");
            regField.setAccessible(true);
            DefaultMutableTreeNode regTreeNode = (DefaultMutableTreeNode) regField.get(debuggerPanel);

            // Get 'this' object
            Field thisField = JDebuggerPanel.class.getDeclaredField("thisTreeNode");
            thisField.setAccessible(true);
            DefaultMutableTreeNode thisTreeNode = (DefaultMutableTreeNode) thisField.get(debuggerPanel);

            Map<String, Object> variables = new HashMap<>();
            variables.put("registers", extractTreeNodeData(regTreeNode));
            variables.put("thisObject", extractTreeNodeData(thisTreeNode));
            ctx.json(UntrustedArtifactUtils.withMetadata(variables));
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Error while trying to get the debug variables: " + e.getMessage(), e, logger);
        }
    }

    // Helper methods

    /**
     * 
     * @param Context
     * @return JDebuggerPanel
     * 
     * This helper method is used to get the JDebuggerPanel
     * It get's this JDebuggerPanel using getDebuggerPanel() method of MainWindow and handles the error
     * 
     * It then get's IdebugController UI component and handles any error.
     * 
     * If no error then return the JDebuggerPanel
     */
    private JDebuggerPanel getDebuggerPanel(Context ctx) {
        JDebuggerPanel panel = mainWindow.getDebuggerPanel();
        if (panel == null) {
            ctx.status(400).json(Map.of("error", "Debugger panel not initialized"));
            return null;
        }

        IDebugController controller = panel.getDbgController();
        if (controller == null || !controller.isDebugging()) {
            ctx.status(400).json(Map.of("error", "Debugger not attached"));
            return null;
        }

        if (!controller.isSuspended()) {
            ctx.status(400).json(Map.of("error", "Process not suspended. Data only available when paused."));
            return null;
        }
        return panel;
    }

    /**
     * @param DefaultMutableTreeNode
     * @return List<Map<String, Object>>
     * 
     * This helper method is used to extract the TreeNodeData.
     * It uses for loop to iterate over child component of node.
     * 
     * One by one it get's the childNode, If the childNode is instanceof 
     * JDebuggerPanel.ValueTreeNode it fetches it's details and adds it to record
     * 
     * After all child nodes have been processed it returns the details.
     */
    private List<Map<String, Object>> extractTreeNodeData(DefaultMutableTreeNode node) {
        List<Map<String, Object>> result = new ArrayList<>();
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
                if (valueNode.getChildCount() > 0) {
                    varInfo.put("children", extractTreeNodeData(valueNode));
                }
                result.add(varInfo);
            }
        }
        return result;
    }
}
