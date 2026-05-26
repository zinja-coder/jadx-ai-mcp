package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;
import jadx.api.ResourceFile;
import jadx.api.security.IJadxSecurity;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.utils.android.AppAttribute;
import jadx.core.utils.android.ApplicationParams;
import jadx.core.utils.exceptions.JadxRuntimeException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

import com.zin.jadxaimcp.utils.PaginationUtils;
import com.zin.jadxaimcp.utils.PaginationUtils.PaginationException;
import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;
import com.zin.jadxaimcp.utils.SearchProgressTracker;
import com.zin.jadxaimcp.utils.DecompilationCache;

public class ClassRoutes {
    private static final Logger logger = LoggerFactory.getLogger(ClassRoutes.class);
    private final MainWindow mainWindow;
    private final PaginationUtils paginationUtils;
    private final SearchProgressTracker progressTracker = SearchProgressTracker.getInstance();
    private final DecompilationCache decompilationCache = DecompilationCache.getInstance();

    /**
     * Enum for specifying search locations in handleSearchClassesByKeyword.
     * Supports searching in different parts of decompiled code.
     */
    public enum SearchLocation {
        CLASS_NAME, // Search classes by class name containing keyword
        METHOD_NAME, // Search classes by method name/constructor/parameter types containing keyword
        FIELD_NAME, // Search classes by field name containing keyword
        CODE, // Search code containing keyword (default)
        COMMENT // Search comments containing keyword (searches for // and /* */ patterns)
    }

    // Map lowercase search location names to enum values for URL-friendly parameter
    // parsing
    private static final Map<String, SearchLocation> SEARCH_LOCATION_MAP = new HashMap<>();
    static {
        SEARCH_LOCATION_MAP.put("class", SearchLocation.CLASS_NAME);
        SEARCH_LOCATION_MAP.put("class_name", SearchLocation.CLASS_NAME);
        SEARCH_LOCATION_MAP.put("method", SearchLocation.METHOD_NAME);
        SEARCH_LOCATION_MAP.put("method_name", SearchLocation.METHOD_NAME);
        SEARCH_LOCATION_MAP.put("field", SearchLocation.FIELD_NAME);
        SEARCH_LOCATION_MAP.put("field_name", SearchLocation.FIELD_NAME);
        SEARCH_LOCATION_MAP.put("code", SearchLocation.CODE);
        SEARCH_LOCATION_MAP.put("comment", SearchLocation.COMMENT);
    }

    // Pattern to detect jadx obfuscated package names (e.g., p000, p001, p123)
    private static final Pattern OBFUSCATED_PACKAGE_PATTERN = Pattern.compile("^p\\d+$");

    public ClassRoutes(MainWindow mainWindow, PaginationUtils paginationUtils) {
        this.mainWindow = mainWindow;
        this.paginationUtils = paginationUtils;
    }

    // ------------------------------- Request Handlers --------------------------

    /**
     * @param Context
     * @return void
     * 
     *         This handler method handle the /current-class api call,
     *         It return currently open/active/visible class code in UI in jadx.
     *         Using helper methods getSelectedTabTitle() and
     *         extractTextFromCurrentTab() it gets
     *         the title of UI component holding class code and then using that UI
     *         component extracts
     *         the text from that UI component.
     * 
     *         After getting the code it returns it.
     */
    public void handleCurrentClass(Context ctx) {
        try {
            String className = getSelectedTabTitle();
            String code = extractTextFromCurrentTab();

            Map<String, String> result = new HashMap<>();
            result.put("name", className != null ? className.replace(".java", "") : "unknown");
            result.put("type", "code/java");
            result.put("content", code != null ? code : "");

            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal Error while trying to fetch current class class: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return
     * @param Context
     * 
     *                This routing method returns all classes decompiled from apk by
     *                jadx
     *                It first fetches the list of JavaClass classes using
     *                JadxWrapper.
     *                Then it combines this JavaClass list into Map and uses
     *                pagination utils to return the
     *                details of all classes.
     */
    public void handleAllClasses(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<JavaClass> classes = wrapper.getIncludedClassesWithInners();

            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx,
                    classes,
                    "class-list",
                    "classes",
                    JavaClass::getFullName);
            ctx.json(result);
        } catch (PaginationException e) {
            JadxAIMCPPluginError.handleError(ctx, "Pagination Error: " + e.getMessage(), e, logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Failed to load class list: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @param Context
     * @return
     * 
     *         This routing method handles the /selected-text api call
     *         it first gets the currently selecte UI component using MainWindow's
     *         methods
     *         Then it find the text area from the currently active UI component,
     *         this text area
     *         holds the selected text.
     * 
     *         From this text area, it fetches the selected text using
     *         getSelectedText() method and
     *         returns this using Map and ctx.
     */
    public void handleSelectedText(Context ctx) {
        try {
            Component selectedComponent = mainWindow.getTabbedPane().getSelectedComponent();
            JTextArea textArea = findTextArea(selectedComponent);
            String selectedText = textArea != null ? textArea.getSelectedText() : null;

            Map<String, String> result = new HashMap<>();
            result.put("selectedText", selectedText != null ? selectedText : "");
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error while trying to fetch selected text: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @param Context
     * @return void
     * 
     *         This routing method handles the /class-source MCP tool call
     *         First it checks for request validity, the check is availability of
     *         'class' parameter in http request, then it fetches the source code of
     *         the class
     *         by fetching the classes one by one and compares it with the requested
     *         class name, if
     *         it matches returns the requested classe's code.
     */
    public void handleClassSource(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null)
            return;

        // Removing this line to solve issue #37 as raised and contributed by
        // github@ljt270864457
        // This solves following bug -> Bug: Inner classes with $ symbol cannot be
        // retrieved via /class-source endpoint
        // className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    String code = decompilationCache.get(className);
                    if (code == null) {
                        code = cls.getCode();
                        decompilationCache.put(className, code);
                    }
                    ctx.result(code);
                    return;
                }
            }
            ctx.status(404).json(Map.of("error", "Class " + className + " not found"));
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error retrieving class source: " + e.getMessage(), e,
                    logger);
        }
    }

    /**
     * @param Context
     * @return void
     * 
     *         This routing method handles the /methods-of-class endpoint.
     *         First it checks whether the 'class_name' parameter is present or not
     *         in http request
     *         then it iterates over each class present in jadx, and matches it for
     *         the `class_name`'s value
     *         Then once the requested class is found, it iterates over the methods
     *         of that class and gathers
     *         their details.
     * 
     *         After gathering the details it returns the methods details.
     */
    public void handleMethodsOfClass(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null)
            return;

        // Removing this line to solve issue #37 as raised and contributed by
        // github@ljt270864457
        // This solves following bug -> Bug: Inner classes with $ symbol cannot be
        // retrieved via /methods-of-class endpoint
        // className = className.replace('$', '.');

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    List<String> methods = new ArrayList<>();
                    for (JavaMethod method : cls.getMethods()) {
                        String fullMethodName = cls.getFullName() + "." + method.getName();
                        String methodData = method.getAccessFlags() +
                                " " + method.getReturnType() +
                                " " + method.getName() +
                                " " + method.getMethodNode() +
                                " " + fullMethodName;
                        methods.add(methodData);
                    }
                    ctx.result(String.join("\n", methods));
                    return;
                }
            }
            JadxAIMCPPluginError.handleError(ctx, 404, "Class " + className + " not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error retrieving methods: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @param Context
     * @return void
     * 
     *         This routing method handles the /fields-of-class mcp tool call
     *         After checking for presence of 'class_name' parameter, it finds the
     *         class with
     *         'class_name' name, after finding the requested class, it fetches the
     *         fields of class
     *         starts gathering their details.
     * 
     *         Then it return these details.
     */
    public void handleFieldsOfClass(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null)
            return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    List<String> fields = new ArrayList<>();
                    for (JavaField field : cls.getFields()) {
                        String fieldData = field.getAccessFlags() +
                                " " + field.getType() +
                                " " + field.getName();
                        fields.add(fieldData);
                    }
                    ctx.result(String.join("\n", fields));
                    return;
                }
            }
            JadxAIMCPPluginError.handleError(ctx, 404, "Class " + className + " not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error retrieving fields: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @param Context
     * @return void
     * 
     *         This routing method handles the /smali-of-class mcp tool call
     *         After checking for availability of 'class' parameter in request, it
     *         finds that class,
     *         After finding that class it fetch smali of that class and returns it.
     */
    public void handleSmaliOfClass(Context ctx) {
        String className = checkClassParam(ctx);
        if (className == null)
            return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    ctx.result(cls.getSmali());
                    return;
                }
            }
            JadxAIMCPPluginError.handleError(ctx, 404, "Class " + className + " not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error retrieving smali: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     *                This routing method handle the /main-activity mcp tool call.
     *                1. It gets the manifest file
     *                2. It gets the manifest file parser
     *                3. It parses the manifest file and fetches the name of the
     *                Main Activity class
     *                4. It gets the Main Activity class code and returns it.
     */
    public void handleMainActivity(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(mainWindow.getWrapper().getResources());
            if (manifestRes == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "AndroidManifest.xml not found", logger);
                return;
            }

            AndroidManifestParser parser = new AndroidManifestParser(
                    manifestRes,
                    EnumSet.of(AppAttribute.MAIN_ACTIVITY),
                    wrapper.getArgs().getSecurity());

            if (!parser.isManifestFound()) {
                JadxAIMCPPluginError.handleError(ctx, 404, "AndroidManifest.xml not found.", logger);
                return;
            }

            ApplicationParams results = parser.parse();
            if (results.getMainActivity() == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "Failed to get main activity from manifest.", logger);
                return;
            }

            JavaClass mainActivityClass = results.getMainActivityJavaClass(wrapper.getDecompiler());
            if (mainActivityClass == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "Failed to get activity class: " + results.getApplication(),
                        logger);
                return;
            }

            String cachedCode = decompilationCache.get(mainActivityClass.getFullName());
            String code = cachedCode != null ? cachedCode : cacheAndReturn(mainActivityClass);
            ctx.json(Map.of("name", mainActivityClass.getFullName(), "type", "code/java", "content", code));
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error occurred while trying to get the Main Activity class code: " + e.getMessage(), e,
                    logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     *                This method handles the /main-application-classes-names mcp
     *                tool call.
     * 
     *                First goal is to get the package name, to get this first it
     *                gets the manifest file.
     *                Then parses it and get's the package name from it. Then get
     *                all the decompiled classes and
     *                filter them under the package name of main applcaiton. After
     *                filtering classes, build a dictionary
     *                of them and return them.
     */
    public void handleMainApplicationClassesNames(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<ResourceFile> resources = wrapper.getResources();

            // get the manifest resource file
            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
            if (manifestRes == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "AndroidManifest.xml not found.", logger);
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
                JadxAIMCPPluginError.handleError(ctx, 404, "Package name not found in AndroiManifest.xml", logger);
                return;
            }

            // Changed the getClasses() to getClassesWithInners()
            List<JavaClass> matchedClasses = wrapper.getDecompiler()
                    .getClassesWithInners()
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
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error while trying to fetch all classes names: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @param Context
     * @return void
     * 
     *         This routing method handles the /main-application-classes-code MCP
     *         tool call.
     *         1. It retrieves the AndroidManifest.xml resource file
     *         2. It parses the manifest XML to extract the application's package
     *         name
     *         3. It filters all decompiled classes (including inner classes) that
     *         belong to the main package
     *         4. For each matched class, it builds a map containing:
     *         - Class full name
     *         - Content type (code/java)
     *         - Decompiled source code (or error message if decompilation fails)
     *         5. It applies pagination to the collected class information
     *         6. It returns the paginated result containing class details with
     *         their source code
     * 
     *         Note: This method handles decompilation errors gracefully by
     *         including error messages
     *         in the content field instead of failing the entire request.
     */
    public void handleMainApplicationClassesCode(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<ResourceFile> resources = wrapper.getResources();

            // get the manifest resource file
            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(resources);
            if (manifestRes == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "AndroidManifest.xml not found.", logger);
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
                JadxAIMCPPluginError.handleError(ctx, 404, "Package name not found in AndroiManifest.xml", logger);
                return;
            }

            logger.info("JADX AI MCP: Package name: " + packageName);
            // filter classes under this package
            // Changed the getClasses() to getClassesWithInners()
            List<JavaClass> matchedClasses = wrapper.getDecompiler()
                    .getClassesWithInners()
                    .stream()
                    .filter(cls -> cls.getFullName().startsWith(packageName))
                    .collect(Collectors.toList());

            logger.info("JADX AI MCP: Found " + matchedClasses.size() + " classes in package " + packageName);
            logger.info("JADX AI MCP: Request params - offset: " + ctx.queryParam("offset") +
                    ", limit: " + ctx.queryParam("limit") +
                    ", count: " + ctx.queryParam("count"));

            // Build list of class info maps Before pagination
            List<Map<String, Object>> classInfoList = new ArrayList<>();
            for (JavaClass cls : matchedClasses) {
                Map<String, Object> classInfo = new HashMap<>();
                classInfo.put("name", cls.getFullName());
                classInfo.put("type", "code/java");
                try {
                    String code = decompilationCache.get(cls.getFullName());
                    if (code == null) {
                        code = cls.getCode();
                        decompilationCache.put(cls.getFullName(), code);
                    }
                    classInfo.put("content", code);
                    logger.debug("JADX AI MCP: Successfully got code for " + cls.getFullName() +
                            " (length: " + code.length() + ")");
                } catch (Exception e) {
                    logger.warn("Failed to decompile class " + cls.getFullName() + ": " + e.getMessage());
                    classInfo.put("content", "// Error decompiling class: " + e.getMessage());
                }
                classInfoList.add(classInfo);
            }

            logger.info("JADX AI MCP: Built " + classInfoList.size() + " class info objects");

            // Apply pagination to the pre-build list
            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx,
                    classInfoList,
                    "application-classes",
                    "classes",
                    item -> item); // Identity function since items are already transformed

            ctx.json(result);
        } catch (PaginationException e) {
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error while generating pagination result for handleMainApplicationClassesCode: "
                            + e.getMessage(),
                    e, logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error occurred while retrieving main application classes' code: " + e.getMessage(), e,
                    logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     *                This method handles the call for /search-classes-by-keyword
     *                mcp tool.
     * 
     *                Request parameters:
     *                - search_term (required): The keyword to search for
     *                - package (optional): Limit search to specific package (e.g.,
     *                "com.example.app")
     *                Note: Package filtering is disabled for jadx obfuscated
     *                packages (p000, p001, etc.)
     *                - search_in (optional): Comma-separated list of search
     *                locations. Valid values:
     *                CLASS_NAME, METHOD_NAME, FIELD_NAME, CODE, RESOURCE, COMMENT
     *                Default: CODE
     * 
     *                The method searches for the keyword in specified locations and
     *                returns deduplicated class list.
     */

    /**
     * Returns the current search progress as JSON.
     * Called by the GET /search-progress endpoint.
     */
    public void handleSearchProgress(Context ctx) {
        ctx.json(progressTracker.getProgress());
    }

    /**
     * Searches for classes containing a keyword across the configured search locations.
      * Supports pagination and package filtering.
     */
    public void handleSearchClassesByKeyword(Context ctx) {
        String searchTerm = ctx.queryParam("search_term");
        if (searchTerm == null || searchTerm.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing 'search_term' parameter.", logger);
            return;
        }

        // Parse optional package filter parameter
        String packageFilter = ctx.queryParam("package");

        // Parse search locations, default to CODE if not specified
        Set<SearchLocation> searchLocations = parseSearchLocations(ctx.queryParam("search_in"));

        String searchId = null;
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            if (wrapper == null) {
                JadxAIMCPPluginError.handleError(ctx, 500, "JadxWrapper not initialized", logger);
                return;
            }
            List<JavaClass> allClasses = wrapper.getIncludedClassesWithInners();
            String term = searchTerm.toLowerCase();

            // Start progress tracking
            String locationsDesc = searchLocations.stream()
                    .map(Enum::name).collect(Collectors.joining(","));
            // Total work = classes × locations, cuz each location scans all classes
            searchId = progressTracker.startSearch(locationsDesc, allClasses.size() * searchLocations.size());

            // Check if package filter should be applied
            // Disable package filtering for jadx obfuscated packages (p000, p001, etc.)
            boolean applyPackageFilter = isValidPackageFilter(packageFilter);
            if (packageFilter != null && !applyPackageFilter) {
                logger.info(
                        "JADX AI MCP: Package filter '{}' appears to be a jadx obfuscated package name, skipping package filtering",
                        packageFilter);
            }

            // Use LinkedHashSet to maintain order and ensure deduplication
            Set<JavaClass> matchingClassesSet = new LinkedHashSet<>();

            // Search in each specified location
            for (SearchLocation location : searchLocations) {
                Set<JavaClass> locationResults = searchInLocation(allClasses, term, location, packageFilter,
                        applyPackageFilter);
                matchingClassesSet.addAll(locationResults);
            }

            // Convert to list for pagination
            List<JavaClass> matchingClasses = new ArrayList<>(matchingClassesSet);

            // Mark search as completed/ done
            progressTracker.completeSearch(searchId, matchingClasses.size());

            logger.info("JADX AI MCP: Search completed. Found {} unique classes matching '{}' in locations: {}",
                    matchingClasses.size(), searchTerm, searchLocations);

            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx,
                    matchingClasses,
                    "class-list",
                    "classes",
                    JavaClass::getFullName);
            ctx.json(result);
        } catch (PaginationException e) {
            if (searchId != null) {
                progressTracker.failSearch(searchId, e.getMessage());
            }
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error while generating pagination result for handleSearchClassesByKeyword: "
                            + e.getMessage(),
                    e, logger);
        } catch (Exception e) {
            if (searchId != null) {
                progressTracker.failSearch(searchId, e.getMessage());
            }
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error occurred while trying to handle the search classes by keyword mcp request: "
                            + e.getMessage(),
                    e, logger);
        }
    }

    /**
     * Parse the search_in parameter into a set of SearchLocation enums.
     * Accepts lowercase values like "class,method,code" for URL-friendly usage.
     * 
     * @param searchIn Comma-separated string of search locations (e.g.,
     *                 "class,method,code")
     * @return Set of SearchLocation enums, defaults to {CODE} if null or empty
     */
    private Set<SearchLocation> parseSearchLocations(String searchIn) {
        Set<SearchLocation> locations = EnumSet.noneOf(SearchLocation.class);

        if (searchIn == null || searchIn.trim().isEmpty()) {
            // Default to CODE search if not specified
            locations.add(SearchLocation.CODE);
            return locations;
        }

        // Parse comma-separated values using lowercase mapping
        String[] parts = searchIn.toLowerCase().split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            SearchLocation loc = SEARCH_LOCATION_MAP.get(trimmed);
            if (loc != null) {
                locations.add(loc);
            } else {
                logger.warn("JADX AI MCP: Invalid search location '{}', ignoring. Valid values: {}",
                        trimmed, SEARCH_LOCATION_MAP.keySet());
            }
        }

        // If no valid locations parsed, default to CODE
        if (locations.isEmpty()) {
            locations.add(SearchLocation.CODE);
        }

        return locations;
    }

    /**
     * Check if package filter is valid and should be applied.
     * Returns false for jadx obfuscated package names (p000, p001, etc.)
     * 
     * @param packageFilter The package filter string
     * @return true if package filter should be applied, false otherwise
     */
    private boolean isValidPackageFilter(String packageFilter) {
        if (packageFilter == null || packageFilter.trim().isEmpty()) {
            return false;
        }
        // defpackage is the default package name for jadx obfuscated classes
        if (packageFilter.equals("defpackage")) {
            return false;
        }

        // Check if the package filter matches jadx obfuscated pattern
        // Jadx uses patterns like "p000", "p001" for obfuscated packages
        String firstPart = packageFilter.split("\\.")[0];
        if (OBFUSCATED_PACKAGE_PATTERN.matcher(firstPart).matches()) {
            return false;
        }

        return true;
    }

    /**
     * Check if a class belongs to the specified package.
     * 
     * @param cls           The JavaClass to check
     * @param packageFilter The package prefix to match
     * @return true if class belongs to the package
     */
    private boolean matchesPackageFilter(JavaClass cls, String packageFilter) {
        if (packageFilter == null || packageFilter.trim().isEmpty()) {
            return true;
        }
        String fullName = cls.getFullName();
        // Match if the class full name starts with package filter
        return fullName.startsWith(packageFilter + ".") || fullName.equals(packageFilter);
    }

    /**
     * Search for keyword in the specified location.
     * 
     * @param allClasses         List of all classes to search
     * @param term               Search term (lowercase)
     * @param location           SearchLocation to search in
     * @param packageFilter      Package filter string
     * @param applyPackageFilter Whether to apply package filtering
     * @return Set of matching JavaClasses
     */
    private Set<JavaClass> searchInLocation(List<JavaClass> allClasses,
            String term, SearchLocation location,
            String packageFilter, boolean applyPackageFilter) {
        switch (location) {
            case CLASS_NAME:
                return searchByClassName(allClasses, term, packageFilter, applyPackageFilter);
            case METHOD_NAME:
                return searchByMethodName(allClasses, term, packageFilter, applyPackageFilter);
            case FIELD_NAME:
                return searchByFieldName(allClasses, term, packageFilter, applyPackageFilter);
            case CODE:
                return searchByCode(allClasses, term, packageFilter, applyPackageFilter);
            case COMMENT:
                return searchByComment(allClasses, term, packageFilter, applyPackageFilter);
            default:
                return new HashSet<>();
        }
    }

    /**
     * Search classes by class name containing the keyword.
     */
    private Set<JavaClass> searchByClassName(List<JavaClass> allClasses, String term,
            String packageFilter, boolean applyPackageFilter) {
        return allClasses.parallelStream()
                .filter(cls -> {
                    progressTracker.incrementScanned();
                    // Apply package filter if enabled
                    if (applyPackageFilter && !matchesPackageFilter(cls, packageFilter)) {
                        return false;
                    }
                    // Check if class name contains the term
                    String className = cls.getName().toLowerCase();
                    boolean matched = className.contains(term);
                    if (matched) progressTracker.incrementMatches();
                    return matched;
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Search classes by method name, constructor name, or parameter types
     * containing the keyword.
     * This matches jadx's search behavior which searches:
     * - Method names
     * - Constructor names (methods named <init> or <clinit>)
     * - Method parameter types
     */
    private Set<JavaClass> searchByMethodName(List<JavaClass> allClasses, String term,
            String packageFilter, boolean applyPackageFilter) {
        return allClasses.parallelStream()
                .filter(cls -> {
                    progressTracker.incrementScanned();
                    // Apply package filter if enabled
                    if (applyPackageFilter && !matchesPackageFilter(cls, packageFilter)) {
                        return false;
                    }
                    // Check each method in the class
                    for (JavaMethod method : cls.getMethods()) {
                        // Check method name (includes constructors <init> and static initializers
                        // <clinit>)
                        if (method.getName().toLowerCase().contains(term)) {
                            progressTracker.incrementMatches();
                            return true;
                        }

                        // Check if it's a constructor - also match against class simple name
                        if (method.isConstructor()) {
                            String classSimpleName = cls.getName().toLowerCase();
                            if (classSimpleName.contains(term)) {
                                progressTracker.incrementMatches();
                                return true;
                            }
                        }
                    }
                    return false;
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Search classes by field name containing the keyword.
     */
    private Set<JavaClass> searchByFieldName(List<JavaClass> allClasses, String term,
            String packageFilter, boolean applyPackageFilter) {
        return allClasses.parallelStream()
                .filter(cls -> {
                    progressTracker.incrementScanned();
                    // Apply package filter if enabled
                    if (applyPackageFilter && !matchesPackageFilter(cls, packageFilter)) {
                        return false;
                    }
                    // Check if any field name contains the term
                    for (JavaField field : cls.getFields()) {
                        if (field.getName().toLowerCase().contains(term)) {
                            progressTracker.incrementMatches();
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Search classes by code containing the keyword.
     */
    private Set<JavaClass> searchByCode(List<JavaClass> allClasses, String term,
            String packageFilter, boolean applyPackageFilter) {
        return allClasses.parallelStream()
                .filter(cls -> {
                    try {
                        progressTracker.incrementScanned();
                        // Apply package filter if enabled
                        if (applyPackageFilter && !matchesPackageFilter(cls, packageFilter)) {
                            return false;
                        }
                        String code = decompilationCache.get(cls.getFullName());
                        if (code == null) {
                            code = cls.getCode();
                            decompilationCache.put(cls.getFullName(), code);
                        }
                        boolean matched = code != null && code.toLowerCase().contains(term);
                        if (matched) progressTracker.incrementMatches();
                        return matched;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Search classes by comments containing the keyword.
     * Comments include both single-line (//) and multi-line comments.
     */
    private Set<JavaClass> searchByComment(List<JavaClass> allClasses, String term,
            String packageFilter, boolean applyPackageFilter) {
        // Pattern to match Java comments: // single line or /* multi line */
        Pattern singleLineComment = Pattern.compile("//.*?" + Pattern.quote(term) + ".*", Pattern.CASE_INSENSITIVE);
        Pattern multiLineComment = Pattern.compile("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", Pattern.DOTALL);

        return allClasses.parallelStream()
                .filter(cls -> {
                    try {
                        progressTracker.incrementScanned();
                        // Apply package filter if enabled
                        if (applyPackageFilter && !matchesPackageFilter(cls, packageFilter)) {
                            return false;
                        }
                        String code = decompilationCache.get(cls.getFullName());
                        if (code == null) {
                            code = cls.getCode();
                            if (code != null) decompilationCache.put(cls.getFullName(), code);
                        }
                        if (code == null)
                            return false;

                        // Search for keyword in single-line comments
                        if (singleLineComment.matcher(code).find()) {
                            progressTracker.incrementMatches();
                            return true;
                        }

                        // Search for keyword in multi-line comments
                        java.util.regex.Matcher matcher = multiLineComment.matcher(code);
                        while (matcher.find()) {
                            String comment = matcher.group();
                            if (comment.toLowerCase().contains(term)) {
                                progressTracker.incrementMatches();
                                return true;
                            }
                        }
                        return false;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * handles the /package-tree endpoint.
     * returns a flat list of packages sorted by class count (descending),
     * With a library-detection heuristic for each package.
     */
    public void handleGetPackageTree(Context ctx) {
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<JavaClass> allClasses = wrapper.getIncludedClassesWithInners();

            // group classes by package
            Map<String, Integer> packageCounts = new HashMap<>();
            for (JavaClass cls : allClasses) {
                String fullName = cls.getFullName();
                int lastDot = fullName.lastIndexOf('.');
                String pkg = lastDot > 0 ? fullName.substring(0, lastDot) : "(default)";
                packageCounts.merge(pkg, 1, Integer::sum);
            }

            // build sorted list (descending by class count)
            List<Map<String, Object>> packages = packageCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(entry -> {
                        Map<String, Object> pkg = new HashMap<>();
                        pkg.put("name", entry.getKey());
                        pkg.put("class_count", entry.getValue());
                        pkg.put("is_likely_library", isLikelyLibrary(entry.getKey()));
                        return pkg;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("total_classes", allClasses.size());
            result.put("total_packages", packages.size());
            result.put("packages", packages);
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx,
                    "Internal error building package tree: " + e.getMessage(), e, logger);
        }
    }

    /**
     * returns cache statistics (hits, misses, size, compression ratio).
     */
    public void handleCacheStats(Context ctx) {
        ctx.json(decompilationCache.getStats());
    }

    /**
     * clears the decompilation cache and resets all counters.
     */
    public void handleCacheClear(Context ctx) {
        decompilationCache.clear();
        ctx.json(Map.of("status", "cleared", "stats", decompilationCache.getStats()));
    }

    // known library package prefixes for the is_likely_library heuristic
    private static final String[] LIBRARY_PREFIXES = {
            "androidx.", "android.support.", "com.google.", "com.android.",
            "kotlin.", "kotlinx.", "okhttp3.", "okio.", "retrofit2.",
            "com.squareup.", "io.reactivex.", "rx.", "dagger.",
            "com.facebook.", "com.amazonaws.", "org.apache.", "org.json.",
            "com.fasterxml.", "org.slf4j.", "javax.", "junit.",
            "io.netty.", "com.bumptech.glide.", "org.greenrobot.",
            "com.airbnb.", "io.realm.", "bolts.", "butterknife."
    };

    /**
     * heuristic to detect whether a package is likely a third-party library.
     * Uses prefix matching against known library namespaces.
     */
    private boolean isLikelyLibrary(String packageName) {
        if (packageName == null) return false;
        for (String prefix : LIBRARY_PREFIXES) {
            if (packageName.startsWith(prefix)) {
                return true;
            }
        }
        // jadx obfuscated packages (p000, p001...) are app code, not libraries
        return false;
    }

    // -------------------------------- Helper methods ----------------------------

    /**
     * decompile a class, store in cache, and return the source code.
     */
    private String cacheAndReturn(JavaClass cls) {
        String code = cls.getCode();
        if (code != null) {
            decompilationCache.put(cls.getFullName(), code);
        }
        return code;
    }

    /**
     * @param Context
     * @return String
     * 
     *         Checks if the HTTP request contains the 'class_name' param or not, if
     *         yes then returns it,
     *         else returns null
     */
    private String checkClassParam(Context ctx) {
        String className = ctx.queryParam("class_name");
        if (className == null || className.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameter 'class_name'", logger);
            return null;
        }
        return className;
    }

    /**
     * @param
     * @return String
     * 
     *         This helper method extracts the selected(currently open class's UI
     *         tab)'s
     *         title. First it checks whether the mainWindow is null or not if it is
     *         null then
     *         return null.
     * 
     *         Then first it gets's the index of TabbedPane if it is -1 then it is
     *         not valid/ there
     *         is no selected class UI. Else it extracts title of tab using it's
     *         index and returns it
     *         as String.
     */
    private String getSelectedTabTitle() {
        if (mainWindow == null || mainWindow.getTabbedPane() == null)
            return null;

        int index = mainWindow.getTabbedPane().getSelectedIndex();
        if (index != -1) {
            return mainWindow.getTabbedPane().getTitleAt(index);
        }

        return null;
    }

    /**
     * @param
     * @return String
     * 
     *         This helper method extracts the text from current tab (active tab in
     *         UI) in other
     *         words, UI where we see class code.
     * 
     *         After checking for mainWindow's state for `null`, it first creates
     *         the Component object
     *         to store the current tab ( UI where we see class code ), Then using
     *         findTextArea() it
     *         extracts all text (class code) from it and return it via
     *         textArea.getText() method after
     *         checking for null.
     */
    private String extractTextFromCurrentTab() {
        if (mainWindow == null)
            return null;

        Component component = mainWindow.getTabbedPane().getSelectedComponent();
        JTextArea textArea = findTextArea(component);

        return textArea != null ? textArea.getText() : null;
    }

    /**
     * @return JTextArea
     * @param Component
     *                  Recursively searches for a JTextArea (or compatible
     *                  component) inside the given container.
     * 
     *                  This helper method is used in extractTextFromCurrentTab()
     *                  method. It takes the UI component
     *                  and recursively check if there is any JTextArea in that UI
     *                  compoenet, if yes then return it
     *                  else return null
     */
    private JTextArea findTextArea(Component component) {
        if (component instanceof JTextArea) {
            return (JTextArea) component;
        }

        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                JTextArea found = findTextArea(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * @param String, IJadxSecurity
     * @return Document
     * 
     *         reusing jadx's secure xml parsing logic for parsing manifest xml file
     *         this code is taken from jadx -
     *         https://github.com/skylot/jadx/blob/47647bbb9a9a3cd3150705e09cc1f84a5e9f0be6/jadx-core/src/main/java/jadx/core/utils/android/AndroidManifestParser.java#L214
     */
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
