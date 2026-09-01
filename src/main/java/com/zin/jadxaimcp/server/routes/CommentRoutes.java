package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.data.CommentStyle;
import jadx.api.data.ICodeComment;
import jadx.api.data.IJavaCodeRef;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeMetadata;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.InsnCodeOffset;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.gui.JadxWrapper;
import jadx.gui.settings.JadxProject;
import jadx.gui.treemodel.JClass;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.codearea.AbstractCodeArea;
import jadx.gui.ui.codearea.ClassCodeContentPanel;
import jadx.gui.ui.codearea.CodeArea;
import jadx.gui.ui.panel.ContentPanel;
import jadx.gui.utils.UiUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.zin.jadxaimcp.utils.DecompilationCache;
import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;
import com.zin.jadxaimcp.utils.MethodSignatures;

/**
 * Routes for reading and writing user comments on decompiled code.
 *
 * Comments live in the project code data (the same store used by the
 * "Add comment" dialog in JADX-GUI), so anything written here is rendered in
 * the code view and saved with the .jadx project file.
 */
public class CommentRoutes {
    private static final Logger logger = LoggerFactory.getLogger(CommentRoutes.class);
    /** How far below a comment line to look for the declaration it belongs to. */
    private static final int COMMENT_LINE_LOOKAHEAD = 3;
    private final MainWindow mainWindow;
    private final DecompilationCache decompilationCache = DecompilationCache.getInstance();

    public CommentRoutes(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
    }

    /**
     * @return void
     * @param Context
     *
     *                This routing method handles the /add-comment mcp tool call's
     *                http request. It resolves the target, a class, a method, a
     *                field or a single code line ('line'), and stores a
     *                JadxCodeComment for it in the project code data. An existing
     *                comment on the same target is replaced, an empty comment
     *                removes it, and a comment that does not render is rolled back.
     */
    public void handleAddComment(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodName = ctx.queryParam("method_name");
        String methodSignature = ctx.queryParam("method_signature");
        String fieldName = ctx.queryParam("field_name");
        String comment = ctx.queryParam("comment");
        String styleParam = ctx.queryParam("style");
        String lineParam = ctx.queryParam("line");

        if (className == null || className.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameter: class_name", logger);
            return;
        }
        if (comment == null) {
            JadxAIMCPPluginError.handleError(ctx, 400,
                    "Missing required parameter: comment (pass an empty value to remove a comment)", logger);
            return;
        }

        CommentStyle style;
        try {
            style = parseStyle(styleParam);
        } catch (IllegalArgumentException e) {
            JadxAIMCPPluginError.handleError(ctx, 400, e.getMessage(), logger);
            return;
        }

        Integer line;
        try {
            line = parseOptionalInt(lineParam, "line");
        } catch (IllegalArgumentException e) {
            JadxAIMCPPluginError.handleError(ctx, 400, e.getMessage(), logger);
            return;
        }
        if (line != null && line < 1) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Parameter 'line' is 1-based, got " + line, logger);
            return;
        }
        if (line != null && fieldName != null && !fieldName.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400,
                    "'field_name' cannot be combined with 'line': the line already says what to comment", logger);
            return;
        }

        try {
            JavaClass cls = findClass(className);
            if (cls == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "Class " + className + " not found.", logger);
                return;
            }

            IJavaNodeRef nodeRef;
            IJavaCodeRef codeRef = null;
            String target;
            Integer resolvedLine = null;
            String resolvedKind = null;

            if (line != null) {
                LineTarget lineTarget = resolveByLine(cls, line);
                if (lineTarget == null) {
                    JadxAIMCPPluginError.handleError(ctx, 404, lineTargetError(cls, line), logger);
                    return;
                }
                nodeRef = lineTarget.nodeRef;
                codeRef = lineTarget.codeRef;
                resolvedLine = line;
                resolvedKind = lineTarget.kind;
                target = lineTarget.describe(cls, line);
            } else if (methodName != null && !methodName.isEmpty()) {
                String shortMethodName = methodName;
                String signature = methodSignature;
                // Strip method signature if it was passed inside method_name
                if (shortMethodName.contains("(")) {
                    if (signature == null || signature.isEmpty()) {
                        signature = shortMethodName.substring(shortMethodName.indexOf('('));
                    }
                    shortMethodName = shortMethodName.substring(0, shortMethodName.indexOf('('));
                }

                JavaMethod method = findMethod(cls, shortMethodName, signature);
                if (method == null) {
                    JadxAIMCPPluginError.handleError(ctx, 404,
                            "Method " + methodName + " not found in class " + className + ".", logger);
                    return;
                }
                nodeRef = JadxNodeRef.forMth(method);
                target = cls.getFullName() + "." + method.getName();
            } else if (fieldName != null && !fieldName.isEmpty()) {
                JavaField field = findField(cls, fieldName);
                if (field == null) {
                    JadxAIMCPPluginError.handleError(ctx, 404,
                            "Field " + fieldName + " not found in class " + className + ".", logger);
                    return;
                }
                nodeRef = JadxNodeRef.forFld(field);
                target = cls.getFullName() + "." + field.getName();
            } else {
                nodeRef = JadxNodeRef.forCls(cls);
                target = cls.getFullName();
            }

            String commentText = comment.trim();
            final IJavaCodeRef finalCodeRef = codeRef;
            ICodeComment newComment = commentText.isEmpty()
                    ? null
                    : new JadxCodeComment(nodeRef, codeRef, commentText, style);

            List<ICodeComment> replacedComments = new ArrayList<>();
            int total = updateComments(list -> {
                for (Iterator<ICodeComment> it = list.iterator(); it.hasNext();) {
                    ICodeComment existing = it.next();
                    if (sameTarget(existing, nodeRef, finalCodeRef)) {
                        replacedComments.add(existing);
                        it.remove();
                    }
                }
                if (newComment != null) {
                    list.add(newComment);
                }
            });
            boolean replaced = !replacedComments.isEmpty();

            if (newComment == null && !replaced) {
                JadxAIMCPPluginError.handleError(ctx, 404, "No comment found on " + target + " to remove.", logger);
                return;
            }

            refreshUi(cls);

            // JADX drops a comment whose anchor it cannot resolve without reporting
            // anything, so the write is only believed once it shows up in the code
            Integer renderedLine = null;
            if (newComment != null) {
                renderedLine = findRenderedComment(cls, commentText);
                if (renderedLine == null) {
                    updateComments(list -> {
                        list.remove(newComment);
                        list.addAll(replacedComments);
                    });
                    refreshUi(cls);
                    JadxAIMCPPluginError.handleError(ctx, 422, notRenderedError(target, nodeRef), logger);
                    return;
                }
            }

            String action = newComment == null ? "removed" : (replaced ? "updated" : "added");
            Map<String, Object> result = new HashMap<>();
            result.put("result", "Comment " + action + " on " + target);
            result.put("action", action);
            result.put("target", target);
            result.put("style", style.name());
            result.put("total_comments", total);
            if (resolvedLine != null) {
                // say which line was used and what it turned out to be, since a
                // declaration line comments the declared node rather than the line
                result.put("line", resolvedLine);
                result.put("attached_to", resolvedKind);
            }
            if (renderedLine != null) {
                // proof the comment is really in the code now, and where it landed
                // after this write shifted the lines below it
                result.put("rendered_at_line", renderedLine);
                result.put("rendered", renderedSourceLine(cls, renderedLine));
            }
            logger.info("Comment {} on {}", action, target);
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to add the comment: " + e.getMessage(),
                    e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     *
     *                This routing method handles the /list-comments mcp tool call's
     *                http request. It returns every comment stored in the project
     *                code data, optionally filtered by the declaring class.
     */
    public void handleListComments(Context ctx) {
        String className = ctx.queryParam("class_name");

        try {
            List<Map<String, Object>> comments = new ArrayList<>();
            JadxCodeData codeData = mainWindow.getProject().getCodeData();
            if (codeData != null) {
                for (ICodeComment codeComment : codeData.getComments()) {
                    IJavaNodeRef nodeRef = codeComment.getNodeRef();
                    if (nodeRef == null) {
                        continue;
                    }
                    if (className != null && !className.isEmpty()
                            && !className.equals(nodeRef.getDeclaringClass())) {
                        continue;
                    }

                    Map<String, Object> entry = new HashMap<>();
                    entry.put("class_name", nodeRef.getDeclaringClass());
                    entry.put("node_type", nodeRef.getType() != null ? nodeRef.getType().name() : null);
                    entry.put("node_id", nodeRef.getShortId());
                    entry.put("comment", codeComment.getComment());
                    entry.put("style", codeComment.getStyle() != null ? codeComment.getStyle().name() : null);

                    IJavaCodeRef codeRef = codeComment.getCodeRef();
                    if (codeRef != null) {
                        entry.put("code_ref_type",
                                codeRef.getAttachType() != null ? codeRef.getAttachType().name() : null);
                        entry.put("offset", codeRef.getIndex());
                    }
                    comments.add(entry);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("comments", comments);
            result.put("total", comments.size());
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to list comments: " + e.getMessage(),
                    e, logger);
        }
    }

    // Helper methods

    /**
     * What a source line resolves to. A line holding an instruction becomes a
     * trailing comment on that line, a declaration line comments the class,
     * method or field declared there, exactly like pressing the comment shortcut
     * on that line in JADX-GUI.
     */
    private static final class LineTarget {
        private final IJavaNodeRef nodeRef;
        private final IJavaCodeRef codeRef;
        private final String kind;
        private final String name;

        private LineTarget(IJavaNodeRef nodeRef, IJavaCodeRef codeRef, String kind, String name) {
            this.nodeRef = nodeRef;
            this.codeRef = codeRef;
            this.kind = kind;
            this.name = name;
        }

        private String describe(JavaClass cls, int line) {
            if (codeRef != null) {
                return cls.getFullName() + "." + name + " line " + line;
            }
            return name + " (declared on line " + line + ")";
        }
    }

    /**
     * @param JavaClass, int
     * @return LineTarget, null when the line holds nothing to comment
     *
     *         Resolves a 1-based source line the same way the GUI comment action
     *         does: an instruction on the line wins, otherwise a declaration on
     *         the line is commented as a whole.
     */
    private LineTarget resolveByLine(JavaClass cls, int line) {
        ICodeInfo codeInfo = cls.getCodeInfo();
        int[] bounds = lineBounds(codeInfo.getCodeStr(), line);
        if (bounds == null) {
            return null;
        }
        ICodeMetadata metadata = codeInfo.getCodeMetadata();
        JadxWrapper wrapper = mainWindow.getWrapper();
        final int lineStart = bounds[0];
        final int lineEnd = bounds[1];

        ICodeAnnotation offsetAnn = metadata.searchUp(lineEnd, lineStart, ICodeAnnotation.AnnType.OFFSET);
        if (offsetAnn instanceof InsnCodeOffset) {
            ICodeNodeRef nodeAt = metadata.getNodeAt(lineEnd);
            JavaNode node = nodeAt == null ? null : wrapper.getJavaNodeByRef(nodeAt);
            if (node instanceof JavaMethod) {
                JavaMethod method = (JavaMethod) node;
                return new LineTarget(JadxNodeRef.forMth(method),
                        JadxCodeRef.forInsn(((InsnCodeOffset) offsetAnn).getOffset()),
                        "line", method.getName());
            }
        }

        // No instruction on the line: comment whatever is declared there, which is
        // what the GUI falls back to for class, method and field declaration lines
        ICodeNodeRef declared;
        declared = metadata.searchUp(lineEnd, (pos, ann) -> {
            if (pos >= lineStart && ann.getAnnType() == ICodeAnnotation.AnnType.DECLARATION) {
                ICodeNodeRef declaredRef = ((NodeDeclareRef) ann).getNode();
                if (declaredRef.getAnnType() != ICodeAnnotation.AnnType.VAR) {
                    return declaredRef;
                }
            }
            return null;
        });
        if (declared == null && isCommentLine(codeInfo.getCodeStr(), lineStart, lineEnd)) {
            // An existing comment line belongs to whatever is declared under it, so
            // pointing at a rendered comment edits that node's comment, as in the GUI.
            // The search is bounded to the lines right below: without a bound it walks
            // on to the next declaration anywhere in the file, which silently retargets
            // the comment at an unrelated method or at the class itself.
            final String code = codeInfo.getCodeStr();
            declared = metadata.searchDown(lineEnd, (pos, ann) -> {
                if (pos <= lineEnd || lineAt(code, pos) - line > COMMENT_LINE_LOOKAHEAD) {
                    return null;
                }
                if (ann.getAnnType() == ICodeAnnotation.AnnType.DECLARATION) {
                    ICodeNodeRef declaredRef = ((NodeDeclareRef) ann).getNode();
                    if (declaredRef.getAnnType() != ICodeAnnotation.AnnType.VAR) {
                        return declaredRef;
                    }
                }
                return null;
            });
        }
        if (declared == null) {
            return null;
        }
        JavaNode node = wrapper.getJavaNodeByRef(declared);
        if (node == null) {
            return null;
        }
        String kind = node instanceof JavaMethod ? "method" : node instanceof JavaField ? "field" : "class";
        return new LineTarget(JadxNodeRef.forJavaNode(node), null, kind, node.getFullName());
    }

    /**
     * @param JavaClass, String
     * @return Integer 1-based line the comment renders on, null when it does not
     *
     *         JADX resolves a comment anchor by indexing the raw instruction array
     *         of the referenced method and drops the comment without an error when
     *         that lookup misses, so a stored comment is no proof of a rendered
     *         one and the decompiled code is checked instead.
     */
    private Integer findRenderedComment(JavaClass cls, String commentText) {
        String firstLine = commentText.split("\\R", 2)[0].trim();
        if (firstLine.isEmpty()) {
            return null;
        }
        String code = cls.getCodeInfo().getCodeStr();
        int line = 1;
        for (String codeLine : code.split("\n", -1)) {
            if (codeLine.contains(firstLine) && isRenderedComment(codeLine, firstLine)) {
                return line;
            }
            line++;
        }
        return null;
    }

    /**
     * @param String, String
     * @return boolean True when the text sits inside a comment on that line and
     *         not in the code, so a matching string literal is not mistaken for it.
     */
    private boolean isRenderedComment(String codeLine, String commentText) {
        int textPos = codeLine.indexOf(commentText);
        String before = codeLine.substring(0, textPos);
        return before.contains("//") || before.contains("/*") || before.trim().startsWith("*");
    }

    /**
     * @param JavaClass, int
     * @return String the trimmed source line, empty when out of range
     */
    private String renderedSourceLine(JavaClass cls, int line) {
        String code = cls.getCodeInfo().getCodeStr();
        int[] bounds = lineBounds(code, line);
        if (bounds == null) {
            return "";
        }
        return code.substring(bounds[0], Math.min(bounds[1], code.length())).trim();
    }

    /**
     * @param String, IJavaNodeRef
     * @return String Message for a comment JADX accepted but never rendered. It is
     *         rolled back before this is returned, so the project is left as it was.
     */
    private String notRenderedError(String target, IJavaNodeRef nodeRef) {
        String where = nodeRef.getShortId() == null ? nodeRef.getDeclaringClass()
                : nodeRef.getDeclaringClass() + "." + nodeRef.getShortId();
        return "Comment on " + target + " did not render, so it was rolled back and nothing was stored. "
                + "JADX could not resolve the anchor in " + where + ", which happens when the line belongs to "
                + "inlined or generated code. Comment the enclosing method with 'method_name' instead, "
                + "or pick a different line.";
    }

    /**
     * @param String, int, int
     * @return boolean True when the line holds nothing but a rendered comment.
     */
    private boolean isCommentLine(String code, int lineStart, int lineEnd) {
        String text = code.substring(lineStart, Math.min(lineEnd, code.length())).trim();
        return text.startsWith("//") || text.startsWith("/*") || text.startsWith("*");
    }

    /**
     * @param JavaClass, int
     * @return String
     *
     *         Builds the 404 message for a line that cannot hold a comment.
     */
    private String lineTargetError(JavaClass cls, int line) {
        int totalLines = countLines(cls.getCodeInfo().getCodeStr());
        if (line > totalLines) {
            return "Line " + line + " is past the end of " + cls.getFullName()
                    + ", which has " + totalLines + " lines.";
        }
        return "Line " + line + " of " + cls.getFullName() + " has nothing to comment: it holds neither an "
                + "instruction nor a declaration. " + describeCommentableLines(cls, line);
    }

    /**
     * @param JavaClass, int
     * @return String Names the instruction lines closest to the rejected one, since
     *         which lines JADX maps to an instruction is not obvious from the source.
     */
    private String describeCommentableLines(JavaClass cls, int line) {
        try {
            ICodeInfo codeInfo = cls.getCodeInfo();
            String code = codeInfo.getCodeStr();
            List<Integer> lines = new ArrayList<>();
            for (Map.Entry<Integer, ICodeAnnotation> entry : codeInfo.getCodeMetadata().getAsMap().entrySet()) {
                if (entry.getValue() instanceof InsnCodeOffset) {
                    lines.add(lineAt(code, entry.getKey()));
                }
            }
            if (lines.isEmpty()) {
                return "This class has no instruction lines at all.";
            }
            Collections.sort(lines, (a, b) -> {
                int byDistance = Integer.compare(Math.abs(a - line), Math.abs(b - line));
                return byDistance != 0 ? byDistance : Integer.compare(a, b);
            });
            List<Integer> closest = new ArrayList<>(new java.util.TreeSet<>(lines.subList(0, Math.min(5, lines.size()))));
            return "Closest lines that can take one: " + closest + ".";
        } catch (Exception e) {
            logger.warn("Failed to collect commentable lines: " + e.getMessage());
            return "";
        }
    }

    /**
     * @param String, int
     * @return int[] {start, end} character positions of a 1-based line, null when
     *         out of range. End is the line break, where the metadata search starts.
     */
    private int[] lineBounds(String code, int line) {
        int start = 0;
        int current = 1;
        while (current < line) {
            int nl = code.indexOf('\n', start);
            if (nl < 0) {
                return null;
            }
            start = nl + 1;
            current++;
        }
        if (start > code.length()) {
            return null;
        }
        int nl = code.indexOf('\n', start);
        int end = nl < 0 ? code.length() : nl;
        return new int[] { start, end };
    }

    /**
     * @param String, int
     * @return int 1-based line number holding the given character position
     */
    private int lineAt(String code, int pos) {
        int line = 1;
        int limit = Math.min(pos, code.length());
        for (int i = 0; i < limit; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * @param String
     * @return int number of lines in the given code
     */
    private int countLines(String code) {
        int lines = 1;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * @param String, String
     * @return Integer, null when the param was not supplied. Throws
     *         IllegalArgumentException with a caller-facing message when not a number.
     */
    private Integer parseOptionalInt(String value, String paramName) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter '" + paramName + "' must be an integer, got '" + value + "'");
        }
    }

    /**
     * @param String
     * @return JavaClass by fully qualified name, including inner classes, null when
     *         it is not part of the loaded project.
     */
    private JavaClass findClass(String className) {
        JadxWrapper wrapper = mainWindow.getWrapper();
        for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
            if (cls.getFullName().equals(className)) {
                return cls;
            }
        }
        return null;
    }

    /**
     * @param JavaClass, String, String
     * @return JavaMethod by short name, the optional signature picking between
     *         overloads.
     */
    private JavaMethod findMethod(JavaClass cls, String methodName, String methodSignature) {
        for (JavaMethod method : cls.getMethods()) {
            if (!method.getName().equalsIgnoreCase(methodName)) {
                continue;
            }
            if (!MethodSignatures.matches(method, methodSignature)) {
                continue;
            }
            return method;
        }
        return null;
    }

    /**
     * @param JavaClass, String
     * @return JavaField of the given class by name.
     */
    private JavaField findField(JavaClass cls, String fieldName) {
        for (JavaField field : cls.getFields()) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }

    /**
     * @param String
     * @return CommentStyle
     *
     *         Parses the requested comment style, defaulting to LINE. Throws
     *         IllegalArgumentException with the accepted values when the style is
     *         unknown.
     */
    private CommentStyle parseStyle(String styleParam) {
        if (styleParam == null || styleParam.isEmpty()) {
            return CommentStyle.LINE;
        }
        try {
            return CommentStyle.valueOf(styleParam.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            List<String> valid = new ArrayList<>();
            for (CommentStyle s : CommentStyle.values()) {
                valid.add(s.name());
            }
            throw new IllegalArgumentException("Unknown comment style '" + styleParam + "'. Valid values: " + valid);
        }
    }

    /**
     * @param ICodeComment, IJavaNodeRef, IJavaCodeRef
     * @return boolean
     *
     *         True when an existing comment is attached to exactly the same node
     *         and the same code position, which makes writing a comment an update
     *         instead of a duplicate. A node-level comment (codeRef null) never
     *         collides with a line comment on the same method.
     */
    private boolean sameTarget(ICodeComment existing, IJavaNodeRef nodeRef, IJavaCodeRef codeRef) {
        return Objects.equals(existing.getNodeRef(), nodeRef) && Objects.equals(existing.getCodeRef(), codeRef);
    }

    /**
     * @param Consumer
     * @return int Total number of comments stored after the update
     *
     *         Applies an update to the project comment list the same way the
     *         JADX-GUI comment dialog does: mutate a copy, sort it, store it back
     *         into the project code data and reload the code data so the
     *         decompiler picks the comments up.
     */
    private int updateComments(Consumer<List<ICodeComment>> updater) {
        JadxProject project = mainWindow.getProject();
        JadxCodeData codeData = project.getCodeData();
        if (codeData == null) {
            codeData = new JadxCodeData();
        }
        List<ICodeComment> comments = new ArrayList<>(codeData.getComments());
        updater.accept(comments);
        Collections.sort(comments);
        codeData.setComments(comments);
        project.setCodeData(codeData);
        mainWindow.getWrapper().reloadCodeData();
        return comments.size();
    }

    /**
     * @param JavaClass
     * @return void
     *
     *         Drops the cached decompiled code of the commented class, both in the
     *         plugin source cache and in the JADX-GUI class cache, and refreshes it
     *         in any open tab, so the new comment shows up without a manual reload.
     */
    private void refreshUi(JavaClass cls) {
        JavaClass topCls = cls.getTopParentClass();
        // Drop the plugin source cache too, else the tools keep serving the
        // pre-comment source back to the MCP client
        decompilationCache.invalidate(cls.getFullName());
        decompilationCache.invalidate(topCls.getFullName());

        // Unload the decompiled code before this request answers, not on the EDT
        // later: a client that writes a comment and immediately reads the source
        // back would otherwise race the refresh and see the pre-comment code.
        try {
            JClass jCls = mainWindow.getCacheObject().getNodeCache().makeFrom(topCls);
            if (jCls != null) {
                jCls.unload(mainWindow.getCacheObject());
            } else {
                topCls.unload();
            }
        } catch (Exception e) {
            logger.warn("Failed to unload class after comment change: " + e.getMessage());
        }

        // Repainting the open tab is cosmetic, so it can happen on the EDT in its own time
        UiUtils.uiRun(() -> {
            try {
                for (ContentPanel tab : mainWindow.getTabbedPane().getTabs()) {
                    if (!(tab instanceof ClassCodeContentPanel)) {
                        continue;
                    }
                    JClass rootClass = tab.getNode().getRootClass();
                    if (rootClass == null || !topCls.getFullName().equals(rootClass.getCls().getFullName())) {
                        continue;
                    }
                    AbstractCodeArea codeArea = ((ClassCodeContentPanel) tab).getJavaCodePanel().getCodeArea();
                    if (codeArea instanceof CodeArea) {
                        ((CodeArea) codeArea).refreshClass();
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to refresh code view after comment change: " + e.getMessage());
            }
        });
    }
}
