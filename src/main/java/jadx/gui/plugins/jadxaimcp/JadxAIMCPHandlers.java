package jadx.gui.plugins;

import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

//import io.javalin.Javalin;
import io.javalin.http.Context;


public class JadxAIMCPHandlers {

   //private Context ctx;
   //private int currentPort;
   //private boolean serverStarted;
   private Logger logger;


   public void JadxAIMCPHanlders(Logger logger) {
        this.logger = logger;
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
    
                Map<String, Object> result = paginationUtils.handlePagination(
                        ctx,
                        classes,
                        "class-list",
                        "classes",
                        cls -> cls.getFullName());
    
                ctx.json(result);
    
            } catch (JadxAIMCPPaginationUtils.PaginationException e) {
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
    
            // Removing this line to solve issue #37 as raised and contributed by github@ljt270864457
            // This solves following bug -> Bug: Inner classes with $ symbol cannot be retrieved via /class-source endpoint
            // className = className.replace('$', '.');
    
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
    
            // Removing this line to solve issue #37 as raised and contributed by github@ljt270864457
            // This solves following bug -> Bug: Inner classes with $ symbol cannot be retrieved via /class-source endpoint
            // className = className.replace('$', '.');
    
            try {
                JadxWrapper wrapper = mainWindow.getWrapper();
                for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                    // DEBUG
                    logger.info(cls.getFullName());
                    if (cls.getFullName().equals(className)) {
                        ctx.result(cls.getCode());
                        return;
                    }
                }
                ctx.status(404).json(Map.of("error", "Class " + className + " not  found."));
                logger.error("JADX AI MCP Error: Class " + className + " not  found.");
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
            
            // Removing this line to solve issue #37 as raised and contributed by github@ljt270864457
            // This solves following bug -> Bug: Inner classes with $ symbol cannot be retrieved via /class-source endpoint
            // className = className.replace('$', '.');
    
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
                ctx.status(404).json(Map.of("error", "Class " + className + " not  found."));
                logger.error("JADX AI MCP Error: Class " + className + " not  found.");
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
            
            // Removing this line to solve issue #37 as raised and contributed by github@ljt270864457
            // This solves following bug -> Bug: Inner classes with $ symbol cannot be retrieved via /class-source endpoint
            // className = className.replace('$', '.');
    
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
                ctx.status(404).json(Map.of("error", "Class " + className + " not  found."));
                logger.error("JADX AI MCP Error: Class " + className + " not  found.");
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
            
            // Removing this line to solve issue #37 as raised and contributed by github@ljt270864457
            // This solves following bug -> Bug: Inner classes with $ symbol cannot be retrieved via /class-source endpoint
            // className = className.replace('$', '.');
    
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
                ctx.status(404).json(Map.of("error", "Class " + className + " not  found."));
                logger.error("JADX AI MCP Error: Class " + className + " not  found.");
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
            
            // Removing this line to solve issue #37 as raised and contributed by github@ljt270864457
            // This solves following bug -> Bug: Inner classes with $ symbol cannot be retrieved via /class-source endpoint
            // className = className.replace('$', '.');
    
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
                ctx.status(404).json(Map.of("error", "Class " + className + " not  found."));
                logger.error("JADX AI MCP Error: Class " + className + " not  found.");
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
            
            // Removing this line to solve issue #37 as raised and contributed by github@ljt270864457
            // This solves following bug -> Bug: Inner classes with $ symbol cannot be retrieved via /class-source endpoint
            // className = className.replace('$', '.');
    
            try {
                JadxWrapper wrapper = mainWindow.getWrapper();
                for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                    if (cls.getFullName().equals(className)) {
                        ctx.result(cls.getSmali());
                        return;
                    }
                }
                ctx.status(404).json(Map.of("error", "Class " + className + " not  found."));
                logger.error("JADX AI MCP Error: Class " + className + " not  found.");
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
                    // DEBUG
                    logger.info("main application class code");
                    logger.info(cls.getFullName());
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
            Map<String, Object> result = paginationUtils.handlePagination(
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
        } catch (JadxAIMCPPaginationUtils.PaginationException e) {
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
