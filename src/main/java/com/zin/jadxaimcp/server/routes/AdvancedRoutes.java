package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import com.zin.jadxaimcp.utils.DecompilationCache;
import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;
import com.zin.jadxaimcp.utils.PaginationUtils;
import com.zin.jadxaimcp.utils.PaginationUtils.PaginationException;
import com.zin.jadxaimcp.utils.SearchProgressTracker;

public class AdvancedRoutes {
    private static final Logger logger = LoggerFactory.getLogger(AdvancedRoutes.class);

    private final MainWindow mainWindow;
    private final PaginationUtils paginationUtils;
    private final DecompilationCache decompilationCache = DecompilationCache.getInstance();
    private final SearchProgressTracker progressTracker = SearchProgressTracker.getInstance();

    // lazy inheritance index. childFqn -> {superFqn, [interfaceFqns...]}
    // built once per sesion by parsing only the header of each class's smali.
    // accessed via getInheritanceIndex() which double-checks under a flag.
    private static final class InheritanceIndex {
        // child => direct superclass FQN (java.lang.Object if absent)
        final Map<String, String> superOf = new ConcurrentHashMap<>();
        // child => direct interfaces FQNs
        final Map<String, List<String>> interfacesOf = new ConcurrentHashMap<>();
        // reverse maps for O(1) subclass / implementer lookups
        final Map<String, List<String>> directSubclassesOf = new ConcurrentHashMap<>();
        final Map<String, List<String>> directImplementersOf = new ConcurrentHashMap<>();
    }

    private static volatile InheritanceIndex inheritanceIndex = null;
    private static final AtomicBoolean inheritanceIndexBuilding = new AtomicBoolean(false);

    private static volatile DecompilerFingerprint currentFingerprint = null;

    private static final class DecompilerFingerprint {

        static final int SAMPLE_SIZE = 32;

        final int classCount;

        final String inputFilesSignature;
        final long sampleHash;

        DecompilerFingerprint(int classCount, String inputFilesSignature, long sampleHash) {
            this.classCount = classCount;
            this.inputFilesSignature = inputFilesSignature;
            this.sampleHash = sampleHash;
        }

        boolean matches(DecompilerFingerprint o) {
            if (o == null) return false;
            return classCount == o.classCount
                    && sampleHash == o.sampleHash
                    && Objects.equals(inputFilesSignature, o.inputFilesSignature);
        }

        @Override public String toString() {
            return "FP{n=" + classCount + ", h=" + Long.toHexString(sampleHash)
                    + ", files=" + (inputFilesSignature == null ? 0 : inputFilesSignature.length()) + "}";
        }
    }


    private static final Pattern SMALI_SUPER_LINE = Pattern.compile(
            "^\\.super\\s+L([^;]+);", Pattern.MULTILINE);

    private static final Pattern SMALI_IMPL_LINE = Pattern.compile(
            "^\\.implements\\s+L([^;]+);", Pattern.MULTILINE);
 
    private static final Pattern SMALI_INVOKE_LINE = Pattern.compile(
            "^\\s*invoke-([a-z/-]+)\\s*\\{[^}]*\\}\\s*,\\s*L([^;]+);->([^(]+)\\(([^)]*)\\)([^\\s]+)",
            Pattern.MULTILINE);
  
    private static final Pattern SMALI_METHOD_DECL = Pattern.compile(
            "^\\.method\\s+([^\\n]*?)([^\\s(]+)\\(([^)]*)\\)([^\\s]+)\\s*$",
            Pattern.MULTILINE);

    private static final Pattern JAVA_STRING_LITERAL = Pattern.compile(
            "\"((?:\\\\.|[^\"\\\\])*)\"");

    // hard caps to prevent OOM on broad searches over large APKs.
    // A common-substring grep over 170k classes can otherwise return millions
    // of hits; we early-stop once this many are accumulated and signal
    // truncation in the response.
    private static final int DEFAULT_MAX_HITS = 5000;
    private static final int ABSOLUTE_MAX_HITS = 50000;

    public AdvancedRoutes(MainWindow mainWindow, PaginationUtils paginationUtils) {
        this.mainWindow = mainWindow;
        this.paginationUtils = paginationUtils;
    }

    //  /find-string-literals
    /*
     scans every class's decompiled source and extracts string literals matching the given pattern. Returns one entry per match (deduplicated per (class, line, literal) tuple).
     * Query params:
        pattern     (required) -- substring or regex to match against literal value
        regex       (optional, default false)
        case_sens   (optional, default false)
        max_literal_len (optional, default 256) -- skip enormous concatenated strings
        package     (optional) -- limit to a package prefix
        offset, limit -- pagination
        
     * Why this matters for RE:
         Most behavioral fingerprints worth anchoring on (URLs, intent extras,
         tracking strings, error messages, MobileConfig keys) live in string
         literals. Literal-only search is dramatically faster than full code
         search AND eliminates false hits inside variable names / method bodies.
     */
    public void handleFindStringLiterals(Context ctx) {
        String patternStr = ctx.queryParam("pattern");
        if (patternStr == null || patternStr.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameter 'pattern'", logger);
            return;
        }
        boolean useRegex = "true".equalsIgnoreCase(ctx.queryParam("regex"));
        boolean caseSens = "true".equalsIgnoreCase(ctx.queryParam("case_sens"));
        int maxLitLen = parseIntParam(ctx.queryParam("max_literal_len"), 256);
        int maxHits = clampMaxHits(parseIntParam(ctx.queryParam("max_hits"), DEFAULT_MAX_HITS));
        String packageFilter = ctx.queryParam("package");

        Pattern matcher;
        try {
            matcher = compilePattern(patternStr, useRegex, caseSens);
        } catch (PatternSyntaxException e) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Invalid regex: " + e.getMessage(), logger);
            return;
        }

        String searchId = null;
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<JavaClass> allClasses = filterByPackage(wrapper.getIncludedClassesWithInners(), packageFilter);
            searchId = progressTracker.startSearch("string-literals:" + patternStr, allClasses.size());

            List<Map<String, Object>> hits = new ArrayList<>();
            // flag to stop early once we hit the limit
            // the count isnt perfectly exact since workers in flight might add a few more hits,
            // but it prevents going OOM when a common pattern matches loads of classes
            final java.util.concurrent.atomic.AtomicBoolean truncated =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final int finalMaxHits = maxHits;
            allClasses.parallelStream().forEach(cls -> {
                if (truncated.get()) return;
                try {
                    progressTracker.incrementScanned();
                    String code = decompilationCache.get(cls.getFullName());
                    if (code == null) {
                        code = cls.getCode();
                        if (code != null) decompilationCache.put(cls.getFullName(), code);
                    }
                    if (code == null) return;
                    List<Map<String, Object>> classHits = scanLiterals(code, cls.getFullName(), matcher, maxLitLen);
                    if (classHits.isEmpty()) return;
                    synchronized (hits) {
                        for (Map<String, Object> h : classHits) {
                            if (hits.size() >= finalMaxHits) {
                                truncated.set(true);
                                break;
                            }
                            hits.add(h);
                            progressTracker.incrementMatches();
                        }
                    }
                } catch (Exception ex) {
                    logger.debug("scan failed for {}: {}", cls.getFullName(), ex.getMessage());
                }
            });

            progressTracker.completeSearch(searchId, hits.size());

            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx, hits, "string-literal-hits", "items", item -> item);
            if (truncated.get()) {
                result.put("truncated", true);
                result.put("truncated_reason",
                        "Hit cap of " + finalMaxHits + " reached. Narrow your pattern or pass max_hits=…");
            }
            ctx.json(result);
        } catch (PaginationException e) {
            if (searchId != null) progressTracker.failSearch(searchId, e.getMessage());
            JadxAIMCPPluginError.handleError(ctx, 400, "Pagination error: " + e.getMessage(), logger);
        } catch (Exception e) {
            if (searchId != null) progressTracker.failSearch(searchId, e.getMessage());
            JadxAIMCPPluginError.handleError(ctx, "Internal error in find-string-literals: " + e.getMessage(), e, logger);
        }
    }

    private List<Map<String, Object>> scanLiterals(String code, String fqn, Pattern userMatcher, int maxLen) {
        List<Map<String, Object>> out = new ArrayList<>();
        Matcher litM = JAVA_STRING_LITERAL.matcher(code);
        // Pre-compute line offsets lazily on first match
        int[] lineOffsets = null;
        while (litM.find()) {
            String literal = litM.group(1);
            if (literal.length() > maxLen) continue;
            if (!userMatcher.matcher(literal).find()) continue;
            if (lineOffsets == null) lineOffsets = computeLineOffsets(code);
            int line = lineOf(lineOffsets, litM.start());
            Map<String, Object> hit = new HashMap<>();
            hit.put("class", fqn);
            hit.put("line", line);
            hit.put("literal", literal);
            hit.put("snippet", extractLineSnippet(code, lineOffsets, line, 1));
            out.add(hit);
        }
        return out;
    }

    //  /grep-code
    /**
     * Full decompiled-source grep with regex support and line-snippet context.
     * Unlike /search-classes-by-keyword which returns ONLY matching class names,
     * this endpoint returns each individual line hit with surrounding context —
     * dramatically reducing the round-trips an AI needs to drill in.
     *
     * Query params:
     *   pattern     (required)
     *   regex       (default false)
     *   case_sens   (default false)
     *   context     (default 1)        — lines of context around each hit
     *   package     (optional)
     *   offset, limit
     */
    public void handleGrepCode(Context ctx) {
        String patternStr = ctx.queryParam("pattern");
        if (patternStr == null || patternStr.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameter 'pattern'", logger);
            return;
        }
        boolean useRegex = "true".equalsIgnoreCase(ctx.queryParam("regex"));
        boolean caseSens = "true".equalsIgnoreCase(ctx.queryParam("case_sens"));
        int context = Math.max(0, Math.min(parseIntParam(ctx.queryParam("context"), 1), 10));
        int maxHits = clampMaxHits(parseIntParam(ctx.queryParam("max_hits"), DEFAULT_MAX_HITS));
        String packageFilter = ctx.queryParam("package");

        Pattern matcher;
        try {
            matcher = compilePattern(patternStr, useRegex, caseSens);
        } catch (PatternSyntaxException e) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Invalid regex: " + e.getMessage(), logger);
            return;
        }

        String searchId = null;
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<JavaClass> allClasses = filterByPackage(wrapper.getIncludedClassesWithInners(), packageFilter);
            searchId = progressTracker.startSearch("grep-code:" + patternStr, allClasses.size());

            List<Map<String, Object>> hits = new ArrayList<>();
            final java.util.concurrent.atomic.AtomicBoolean truncated =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final int finalMaxHits = maxHits;
            allClasses.parallelStream().forEach(cls -> {
                if (truncated.get()) return;
                try {
                    progressTracker.incrementScanned();
                    String code = decompilationCache.get(cls.getFullName());
                    if (code == null) {
                        code = cls.getCode();
                        if (code != null) decompilationCache.put(cls.getFullName(), code);
                    }
                    if (code == null) return;
                    Matcher m = matcher.matcher(code);
                    if (!m.find()) return;
                    int[] lineOffsets = computeLineOffsets(code);
                    m.reset();
                    List<Map<String, Object>> classHits = new ArrayList<>();
                    while (m.find()) {
                        int line = lineOf(lineOffsets, m.start());
                        Map<String, Object> hit = new HashMap<>();
                        hit.put("class", cls.getFullName());
                        hit.put("line", line);
                        hit.put("match", m.group());
                        hit.put("snippet", extractLineSnippet(code, lineOffsets, line, context));
                        classHits.add(hit);
                    }
                    if (classHits.isEmpty()) return;
                    synchronized (hits) {
                        for (Map<String, Object> h : classHits) {
                            if (hits.size() >= finalMaxHits) {
                                truncated.set(true);
                                break;
                            }
                            hits.add(h);
                            progressTracker.incrementMatches();
                        }
                    }
                } catch (Exception ex) {
                    logger.debug("grep failed for {}: {}", cls.getFullName(), ex.getMessage());
                }
            });

            progressTracker.completeSearch(searchId, hits.size());

            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx, hits, "grep-hits", "items", item -> item);
            if (truncated.get()) {
                result.put("truncated", true);
                result.put("truncated_reason",
                        "Hit cap of " + finalMaxHits + " reached. Narrow your pattern or pass max_hits=…");
            }
            ctx.json(result);
        } catch (PaginationException e) {
            if (searchId != null) progressTracker.failSearch(searchId, e.getMessage());
            JadxAIMCPPluginError.handleError(ctx, 400, "Pagination error: " + e.getMessage(), logger);
        } catch (Exception e) {
            if (searchId != null) progressTracker.failSearch(searchId, e.getMessage());
            JadxAIMCPPluginError.handleError(ctx, "Internal error in grep-code: " + e.getMessage(), e, logger);
        }
    }

    // ============================================================
    //  /find-methods-by-signature
    // ============================================================
    /**
     * Search for methods by their signature without decompiling anything.
     * Reads from smali which is fast since JADX already has it.
     *
     * Query params (all optional, all filters are AND-ed together):
     *   name_pattern   - regex on method name
     *   return_type    - substring match on smali return type
     *                    (e.g. "Z" = boolean, "Ljava/lang/String;")
     *   param_types    - comma-separated smali type substrings, all must be present
     *                    can be partial like "Intent" or "String" for loose matching
     *   param_count    - exact number of paramters
     *   class_pattern  - regex on the class name
     *   package        - limit to a package prefix (faster than class_pattern)
     *   offset, limit
     *
     * Returns: [{class, method, params, return_type, access}]
     */
    public void handleFindMethodsBySignature(Context ctx) {
        String namePat = ctx.queryParam("name_pattern");
        String returnType = ctx.queryParam("return_type");
        String paramTypesRaw = ctx.queryParam("param_types");
        String paramCountStr = ctx.queryParam("param_count");
        String classPat = ctx.queryParam("class_pattern");
        String packageFilter = ctx.queryParam("package");

        if ((namePat == null || namePat.isEmpty())
                && (returnType == null || returnType.isEmpty())
                && (paramTypesRaw == null || paramTypesRaw.isEmpty())
                && (paramCountStr == null || paramCountStr.isEmpty())
                && (classPat == null || classPat.isEmpty())) {
            JadxAIMCPPluginError.handleError(ctx, 400,
                    "At least one filter is required (name_pattern, return_type, param_types, param_count, class_pattern)",
                    logger);
            return;
        }

        Pattern nameRegex = null, classRegex = null;
        try {
            if (namePat != null && !namePat.isEmpty()) nameRegex = Pattern.compile(namePat, Pattern.CASE_INSENSITIVE);
            if (classPat != null && !classPat.isEmpty()) classRegex = Pattern.compile(classPat, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Invalid regex: " + e.getMessage(), logger);
            return;
        }
        Integer paramCount = null;
        if (paramCountStr != null && !paramCountStr.isEmpty()) {
            try { paramCount = Integer.parseInt(paramCountStr.trim()); }
            catch (NumberFormatException e) {
                JadxAIMCPPluginError.handleError(ctx, 400, "Invalid param_count: " + paramCountStr, logger);
                return;
            }
        }
        List<String> paramTokens = Collections.emptyList();
        if (paramTypesRaw != null && !paramTypesRaw.isEmpty()) {
            paramTokens = Arrays.stream(paramTypesRaw.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        final Pattern fNameRegex = nameRegex;
        final Pattern fClassRegex = classRegex;
        final Integer fParamCount = paramCount;
        final List<String> fParamTokens = paramTokens;
        final String fReturnType = returnType;

        String searchId = null;
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<JavaClass> allClasses = filterByPackage(wrapper.getIncludedClassesWithInners(), packageFilter);
            searchId = progressTracker.startSearch("methods-by-sig", allClasses.size());

            List<Map<String, Object>> hits = new ArrayList<>();
            allClasses.parallelStream().forEach(cls -> {
                try {
                    progressTracker.incrementScanned();
                    if (fClassRegex != null && !fClassRegex.matcher(cls.getFullName()).find()) return;
                    String smali = cls.getSmali();
                    if (smali == null) return;
                    Matcher m = SMALI_METHOD_DECL.matcher(smali);
                    while (m.find()) {
                        String access = m.group(1).trim();
                        String mname = m.group(2);
                        String paramDesc = m.group(3);
                        String retDesc = m.group(4);

                        if (fNameRegex != null && !fNameRegex.matcher(mname).find()) continue;
                        if (fReturnType != null && !fReturnType.isEmpty() && !retDesc.contains(fReturnType)) continue;

                        List<String> params = splitSmaliParams(paramDesc);
                        if (fParamCount != null && params.size() != fParamCount) continue;
                        if (!fParamTokens.isEmpty()) {
                            boolean allFound = true;
                            for (String tok : fParamTokens) {
                                boolean found = false;
                                for (String p : params) {
                                    if (p.contains(tok)) { found = true; break; }
                                }
                                if (!found) { allFound = false; break; }
                            }
                            if (!allFound) continue;
                        }

                        progressTracker.incrementMatches();
                        Map<String, Object> hit = new HashMap<>();
                        hit.put("class", cls.getFullName());
                        hit.put("method", mname);
                        hit.put("params", params);
                        hit.put("return_type", retDesc);
                        hit.put("access", access);
                        synchronized (hits) { hits.add(hit); }
                    }
                } catch (Exception ex) {
                    logger.debug("sig scan failed for {}: {}", cls.getFullName(), ex.getMessage());
                }
            });

            progressTracker.completeSearch(searchId, hits.size());
            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx, hits, "method-sig-hits", "items", item -> item);
            ctx.json(result);
        } catch (PaginationException e) {
            if (searchId != null) progressTracker.failSearch(searchId, e.getMessage());
            JadxAIMCPPluginError.handleError(ctx, 400, "Pagination error: " + e.getMessage(), logger);
        } catch (Exception e) {
            if (searchId != null) progressTracker.failSearch(searchId, e.getMessage());
            JadxAIMCPPluginError.handleError(ctx, "Internal error in find-methods-by-signature: " + e.getMessage(), e, logger);
        }
    }

    // ============================================================
    //  /get-callees
    // ============================================================
    /**
     * Returns all the methods that a given method calls (outbound calls).
     * Basically the opposite of xrefs-to-method.
     *
     * Query params:
     *   class_name  (required)
     *   method_name (required) - uses the first overload found,
     *                            pass param_signature to pick a specific one
     *
     * Returns:
     *   {target_class, target_method, callees: [{class, method, params, return_type, opcode}]}
     */
    public void handleGetCallees(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodName = ctx.queryParam("method_name");
        String paramSignature = ctx.queryParam("param_signature"); // optional, e.g. "Ljava/lang/String;I"
        if (className == null || className.isEmpty() || methodName == null || methodName.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Required params: class_name, method_name", logger);
            return;
        }
        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            JavaClass target = null;
            for (JavaClass c : wrapper.getIncludedClassesWithInners()) {
                if (c.getFullName().equals(className)) { target = c; break; }
            }
            if (target == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            String smali = target.getSmali();
            if (smali == null) {
                JadxAIMCPPluginError.handleError(ctx, 500, "No smali available for " + className, logger);
                return;
            }
            String methodBody = extractSmaliMethodBody(smali, methodName, paramSignature);
            if (methodBody == null) {
                String detail = paramSignature != null && !paramSignature.isEmpty()
                        ? " with param signature (" + paramSignature + ")"
                        : "";
                JadxAIMCPPluginError.handleError(ctx, 404,
                        "Method " + methodName + detail + " not found in " + className, logger);
                return;
            }
            List<Map<String, Object>> callees = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Matcher m = SMALI_INVOKE_LINE.matcher(methodBody);
            while (m.find()) {
                String opcode = "invoke-" + m.group(1);
                String calleeClass = "L" + m.group(2) + ";";
                String calleeName = m.group(3);
                String calleeParams = m.group(4);
                String calleeRet = m.group(5);
                String key = calleeClass + "->" + calleeName + "(" + calleeParams + ")" + calleeRet;
                if (!seen.add(key)) continue;
                Map<String, Object> hit = new HashMap<>();
                hit.put("class", smaliTypeToFqn(calleeClass));
                hit.put("method", calleeName);
                hit.put("params", splitSmaliParams(calleeParams));
                hit.put("return_type", calleeRet);
                hit.put("opcode", opcode);
                callees.add(hit);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("target_class", className);
            result.put("target_method", methodName);
            result.put("callee_count", callees.size());
            result.put("callees", callees);
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error in get-callees: " + e.getMessage(), e, logger);
        }
    }

    // get-subclasses
    public void handleGetSubclasses(Context ctx) {
        String className = ctx.queryParam("class_name");
        if (className == null || className.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameter 'class_name'", logger);
            return;
        }
        boolean transitive = "true".equalsIgnoreCase(ctx.queryParam("transitive"));
        try {
            InheritanceIndex idx = getInheritanceIndex();
            Set<String> subs;
            if (transitive) {
                subs = collectTransitive(idx.directSubclassesOf, className);
            } else {
                subs = new LinkedHashSet<>(idx.directSubclassesOf.getOrDefault(className, Collections.emptyList()));
            }
            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx, new ArrayList<>(subs), "subclasses", "items", s -> s);
            ctx.json(result);
        } catch (PaginationException e) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Pagination error: " + e.getMessage(), logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error in get-subclasses: " + e.getMessage(), e, logger);
        }
    }

    // get-superclasses
    public void handleGetSuperclasses(Context ctx) {
        String className = ctx.queryParam("class_name");
        if (className == null || className.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameter 'class_name'", logger);
            return;
        }
        try {
            InheritanceIndex idx = getInheritanceIndex();
            List<String> chain = new ArrayList<>();
            String cur = idx.superOf.get(className);
            int safety = 0;
            while (cur != null && !"java.lang.Object".equals(cur) && safety++ < 64) {
                chain.add(cur);
                cur = idx.superOf.get(cur);
            }
            if (cur != null) chain.add(cur); // include java.lang.Object terminator
            List<String> interfaces = idx.interfacesOf.getOrDefault(className, Collections.emptyList());
            Map<String, Object> result = new HashMap<>();
            result.put("class", className);
            result.put("super_chain", chain);
            result.put("direct_interfaces", interfaces);
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error in get-superclasses: " + e.getMessage(), e, logger);
        }
    }

    // get-implementations
    public void handleGetImplementations(Context ctx) {
        String ifaceName = ctx.queryParam("interface_name");
        if (ifaceName == null || ifaceName.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameter 'interface_name'", logger);
            return;
        }
        try {
            InheritanceIndex idx = getInheritanceIndex();
            List<String> direct = idx.directImplementersOf.getOrDefault(ifaceName, Collections.emptyList());
            // Also include subclasses of every direct implementer (they inherit the interface)
            Set<String> all = new LinkedHashSet<>(direct);
            for (String d : direct) {
                all.addAll(collectTransitive(idx.directSubclassesOf, d));
            }
            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx, new ArrayList<>(all), "implementations", "items", s -> s);
            ctx.json(result);
        } catch (PaginationException e) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Pagination error: " + e.getMessage(), logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error in get-implementations: " + e.getMessage(), e, logger);
        }
    }

    // find-android-components-deep
    /**
     * Finds Android components (Activities, Fragments, Services etc) by
     * walking down the inheritance tree from the framework base classes.
     *
     * Also catches things the manifest wont tell you, like dynamic receivers or
     * obfuscated classes whose smali .super still points to the real framework class.
     *
     * Query params:
     *   type  (required) - activity | fragment | service | receiver |
     *                      provider | application | webview-client | webview
     *   offset, limit
     */
    public void handleFindAndroidComponentsDeep(Context ctx) {
        String type = ctx.queryParam("type");
        if (type == null || type.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400,
                    "Missing required parameter 'type' (activity|fragment|service|receiver|provider|application|webview-client|webview)",
                    logger);
            return;
        }
        List<String> bases = androidBaseClassesFor(type.toLowerCase());
        if (bases.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Unknown component type: " + type, logger);
            return;
        }
        try {
            InheritanceIndex idx = getInheritanceIndex();
            Set<String> all = new LinkedHashSet<>();
            for (String base : bases) {
                all.addAll(collectTransitive(idx.directSubclassesOf, base));
            }
            // Sort: app-package classes first (no androidx./com.google./etc. prefixes) then libraries
            List<String> sorted = all.stream()
                    .sorted((a, b) -> {
                        boolean al = isLikelyLibrary(a), bl = isLikelyLibrary(b);
                        if (al == bl) return a.compareTo(b);
                        return al ? 1 : -1;
                    })
                    .collect(Collectors.toList());
            Map<String, Object> result = paginationUtils.handlePagination(
                    ctx, sorted, "android-components", "items", s -> s);
            ctx.json(result);
        } catch (PaginationException e) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Pagination error: " + e.getMessage(), logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error in find-android-components-deep: " + e.getMessage(), e, logger);
        }
    }

    private List<String> androidBaseClassesFor(String type) {
        switch (type) {
            case "activity":
                return Arrays.asList(
                        "android.app.Activity",
                        "androidx.appcompat.app.AppCompatActivity",
                        "androidx.fragment.app.FragmentActivity",
                        "androidx.activity.ComponentActivity");
            case "fragment":
                return Arrays.asList(
                        "android.app.Fragment",
                        "androidx.fragment.app.Fragment",
                        "android.support.v4.app.Fragment");
            case "service":
                return Arrays.asList(
                        "android.app.Service",
                        "android.app.IntentService",
                        "android.app.job.JobService",
                        "androidx.core.app.JobIntentService");
            case "receiver":
                return Arrays.asList("android.content.BroadcastReceiver");
            case "provider":
                return Arrays.asList("android.content.ContentProvider");
            case "application":
                return Arrays.asList("android.app.Application");
            case "webview-client":
                return Arrays.asList(
                        "android.webkit.WebViewClient",
                        "android.webkit.WebChromeClient");
            case "webview":
                return Arrays.asList("android.webkit.WebView");
            default:
                return Collections.emptyList();
        }
    }

    // inheritance index

    /**
     * Gets the inheritance index, builds it if we dont have one yet,
     * or rebuilds it if the project was reloaded.
     *
     * Uses a fingerprint to check if anything changed since last time.
     * If the wrapper isnt ready we just return whatever we have (or an empty index).
     * If the index and fingerprint somehow get out of sync we reset and rebuild.
     */
    private InheritanceIndex getInheritanceIndex() throws InterruptedException {
        JadxWrapper wrapper = mainWindow == null ? null : mainWindow.getWrapper();

        // sanity check - index and fingerprint should both be set or both be null
        // if thats not the case something went wrong, so reset and start fresh
        {
            InheritanceIndex idxQ = inheritanceIndex;
            DecompilerFingerprint fpQ = currentFingerprint;
            if ((idxQ == null) != (fpQ == null)) {
                synchronized (AdvancedRoutes.class) {
                    if ((inheritanceIndex == null) != (currentFingerprint == null)) {
                        logger.warn("AdvancedRoutes: detected inconsistent MCP state (index={}, fingerprint={}); forcing reset",
                                inheritanceIndex == null ? "null" : "present",
                                currentFingerprint == null ? "null" : currentFingerprint);
                        inheritanceIndex = null;
                        currentFingerprint = null;
                    }
                }
            }
        }

        // fast path - if we already have an index and nothing changed, just return it
        InheritanceIndex idx = inheritanceIndex;
        DecompilerFingerprint stamped = currentFingerprint;
        if (idx != null && stamped != null) {
            DecompilerFingerprint live = computeFingerprint(wrapper);
            // if we cant compute a fingerprint right now dont throw away the existing index
            // only treat it as a reload if we actualy got back a diferent fingerprint
            if (live != null && live.matches(stamped)) {
                return idx;
            }
            // fall through to the slow path; it will re-check under lock and
            // either rebuild (real reload) or return the existing index (transient).
        }

        // slow path - need to build or rebuild, grab the lock first
        synchronized (AdvancedRoutes.class) {
            idx = inheritanceIndex;
            stamped = currentFingerprint;
            DecompilerFingerprint live = computeFingerprint(wrapper);

            if (idx != null && stamped != null && live != null && live.matches(stamped)) {
                return idx; // another thread rebuilt while we were waiting on the lock
            }

            // cant get a fingerprint so dont save anything
            // return what we have, or an empty index if theres nothing
            // the static fields stay untouched so the next call will try again
            if (live == null) {
                if (idx != null) return idx;
                logger.debug("AdvancedRoutes: wrapper not ready; returning empty inheritance index (uncommitted)");
                return new InheritanceIndex();
            }

            // rebuilding - if there was an old index then the project probably changed
            // also clear the decompilation cache since class names might be diferent now
            if (idx != null) {
                logger.info("AdvancedRoutes: fingerprint changed (was {} now {}); rebuilding index and clearing decompilation cache",
                        stamped, live);
                try {
                    DecompilationCache.getInstance().clear();
                } catch (Exception e) {
                    logger.warn("AdvancedRoutes: DecompilationCache.clear() failed: {}", e.getMessage());
                }
            } else {
                logger.info("AdvancedRoutes: building inheritance index (first build, {} classes)...", live.classCount);
            }

            // build the index and fingerprint it afterward
            // only save if both steps succeed - on any failure we leave the old state alone
            inheritanceIndexBuilding.set(true);
            try {
                long t0 = System.currentTimeMillis();
                InheritanceIndex built = buildInheritanceIndex();
                DecompilerFingerprint postFp = computeFingerprint(wrapper);
                if (postFp == null) {
                    // Wrapper disappeared mid-build. Refuse to commit; return what
                    // we built but leave the cached state untouched. Next call will
                    // either find the wrapper ready and rebuild, or return empty.
                    logger.warn("AdvancedRoutes: wrapper unavailable after build; not committing index");
                    return built;
                }
                // save the index first, then the fingerprint - order matters here
                // both happen under the lock so readers see a consistent state
                inheritanceIndex = built;
                currentFingerprint = postFp;
                long elapsed = System.currentTimeMillis() - t0;
                logger.info("AdvancedRoutes: index built in {} ms ({} classes, {} edges); stamped {}",
                        elapsed, built.superOf.size(),
                        built.directSubclassesOf.values().stream().mapToInt(List::size).sum(),
                        postFp);
                return built;
            } finally {
                inheritanceIndexBuilding.set(false);
            }
        }
    }

    /**
     * Makes a fingerprint for the currently loaded project.
     * Returns null if theres no project loaded or the wrapper isnt avaliable.
     *
     * Three things go into the fingerprint:
     *   - total class count
     *   - a hash of a sample of class names (FNV-1a)
     *   - input file paths, sizes and timestamps (via reflection, may be empty if it fails)
     *
     * Using reflection for getInputFiles() means if the API changes
     * we just get an empty file signature instead of crashing.
     */
    private static DecompilerFingerprint computeFingerprint(JadxWrapper wrapper) {
        if (wrapper == null) return null;
        List<JavaClass> classes;
        try {
            classes = wrapper.getIncludedClassesWithInners();
        } catch (Exception e) {
            return null;
        }
        if (classes == null || classes.isEmpty()) return null;

        final int n = classes.size();
        final int sampleSize = Math.min(DecompilerFingerprint.SAMPLE_SIZE, n);
        String[] sample = new String[sampleSize];
        for (int i = 0; i < sampleSize; i++) {
            // Spread sample points across the list deterministically.
            int idx = (int) ((long) i * n / sampleSize);
            try {
                String fqn = classes.get(idx).getFullName();
                sample[i] = fqn == null ? "" : fqn;
            } catch (Exception e) {
                sample[i] = "";
            }
        }
        Arrays.sort(sample); // sort so we get the same hash no matter what order classes come back in

        // FNV-1a 64-bit hash - simple, no deps, works well enough for class names
        long h = 0xcbf29ce484222325L;
        for (String s : sample) {
            for (int i = 0, len = s.length(); i < len; i++) {
                h ^= s.charAt(i);
                h *= 0x100000001b3L;
            }
            h ^= '|';
            h *= 0x100000001b3L;
        }

        // try to get input file info via reflection - if it fails thats ok,
        // the class count and hash are usualy enough to tell if the project changed
        StringBuilder sb = new StringBuilder(128);
        try {
            Object args = wrapper.getArgs();
            if (args != null) {
                Object input = args.getClass().getMethod("getInputFiles").invoke(args);
                if (input instanceof List<?>) {
                    for (Object o : (List<?>) input) {
                        if (o instanceof java.io.File) {
                            java.io.File f = (java.io.File) o;
                            sb.append(f.getAbsolutePath()).append('|')
                              .append(f.length()).append('|')
                              .append(f.lastModified()).append('\n');
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // best effort; degrade gracefully
        }

        return new DecompilerFingerprint(n, sb.toString(), h);
    }

    /**
     * Kicks off the inheritance index build in a background thread after the server starts.
     * Runs as a daemon so it doesnt block the UI.
     * If it fails thats fine - the next request will just build it on demand.
     */
    public static void warmInheritanceIndexAsync(MainWindow mainWindow) {
        if (mainWindow == null) return;
        Thread t = new Thread(() -> {
            try {
                // need an instance to call getInheritanceIndex()
                // PaginationUtils isnt actually used here so just pass a new empty one
                AdvancedRoutes warmer = new AdvancedRoutes(mainWindow, new PaginationUtils());
                warmer.getInheritanceIndex();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t2) {
                // catch Throwable here so even OOM or missing class errors
                // dont bring down the whole plugin at startup
                LoggerFactory.getLogger(AdvancedRoutes.class)
                        .warn("AdvancedRoutes: inheritance-index warm-up failed: {}", t2.getMessage());
            }
        }, "jadx-ai-mcp-index-warmer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private InheritanceIndex buildInheritanceIndex() {
        InheritanceIndex idx = new InheritanceIndex();
        JadxWrapper wrapper = mainWindow.getWrapper();
        List<JavaClass> all = wrapper.getIncludedClassesWithInners();
        all.parallelStream().forEach(cls -> {
            try {
                String smali = cls.getSmali();
                if (smali == null) return;
                String selfFqn = cls.getFullName();

                // .super line — there is exactly one
                Matcher sm = SMALI_SUPER_LINE.matcher(smali);
                if (sm.find()) {
                    String superFqn = smaliInternalToFqn(sm.group(1));
                    idx.superOf.put(selfFqn, superFqn);
                    idx.directSubclassesOf.computeIfAbsent(superFqn, k ->
                            Collections.synchronizedList(new ArrayList<>())).add(selfFqn);
                }
                // .implements lines — zero or more
                Matcher im = SMALI_IMPL_LINE.matcher(smali);
                List<String> ifaces = new ArrayList<>();
                while (im.find()) {
                    String ifaceFqn = smaliInternalToFqn(im.group(1));
                    ifaces.add(ifaceFqn);
                    idx.directImplementersOf.computeIfAbsent(ifaceFqn, k ->
                            Collections.synchronizedList(new ArrayList<>())).add(selfFqn);
                }
                if (!ifaces.isEmpty()) idx.interfacesOf.put(selfFqn, ifaces);
            } catch (Exception e) {
                logger.debug("inheritance build failed for {}: {}", cls.getFullName(), e.getMessage());
            }
        });
        return idx;
    }

    private static Set<String> collectTransitive(Map<String, List<String>> graph, String start) {
        Set<String> out = new LinkedHashSet<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            for (String child : graph.getOrDefault(cur, Collections.emptyList())) {
                if (out.add(child)) stack.push(child);
            }
        }
        return out;
    }

    // ==================== shared helpers ====================

    private Pattern compilePattern(String userPattern, boolean asRegex, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        if (asRegex) return Pattern.compile(userPattern, flags);
        return Pattern.compile(Pattern.quote(userPattern), flags);
    }

    private List<JavaClass> filterByPackage(List<JavaClass> all, String pkg) {
        if (pkg == null || pkg.isEmpty()) return all;
        return all.parallelStream()
                .filter(c -> c.getFullName().startsWith(pkg + ".") || c.getFullName().equals(pkg))
                .collect(Collectors.toList());
    }

    private static int parseIntParam(String raw, int dflt) {
        if (raw == null || raw.isEmpty()) return dflt;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return dflt; }
    }

    private static int clampMaxHits(int requested) {
        if (requested <= 0) return DEFAULT_MAX_HITS;
        return Math.min(requested, ABSOLUTE_MAX_HITS);
    }

    private static int[] computeLineOffsets(String code) {
        // Each entry is the index of the *start* of a line. lineOffsets[0] = 0.
        List<Integer> off = new ArrayList<>();
        off.add(0);
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') off.add(i + 1);
        }
        int[] out = new int[off.size()];
        for (int i = 0; i < off.size(); i++) out[i] = off.get(i);
        return out;
    }

    private static int lineOf(int[] offsets, int charIdx) {
        // Binary search for the largest offset <= charIdx
        int lo = 0, hi = offsets.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offsets[mid] <= charIdx) lo = mid; else hi = mid - 1;
        }
        return lo + 1; // 1-based line number
    }

    private static String extractLineSnippet(String code, int[] offsets, int line, int context) {
        int idx0 = Math.max(0, line - 1 - context);
        int idx1 = Math.min(offsets.length - 1, line - 1 + context);
        int start = offsets[idx0];
        int end = (idx1 + 1 < offsets.length) ? offsets[idx1 + 1] - 1 : code.length();
        if (end < start) end = start;
        StringBuilder sb = new StringBuilder();
        // Render with line numbers prefix
        for (int li = idx0; li <= idx1; li++) {
            int ls = offsets[li];
            int le = (li + 1 < offsets.length) ? offsets[li + 1] - 1 : code.length();
            String text = code.substring(ls, le);
            // strip trailing \r if windows line endings
            if (!text.isEmpty() && text.charAt(text.length() - 1) == '\r') text = text.substring(0, text.length() - 1);
            sb.append(li + 1).append(": ").append(text).append('\n');
        }
        return sb.toString();
    }

    private static List<String> splitSmaliParams(String paramDesc) {
        // Splits "Lcom/foo/Bar;Ljava/lang/String;I[I" into its descriptors
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < paramDesc.length()) {
            char c = paramDesc.charAt(i);
            int start = i;
            while (c == '[') { i++; if (i >= paramDesc.length()) break; c = paramDesc.charAt(i); }
            if (i >= paramDesc.length()) break;
            if (c == 'L') {
                int end = paramDesc.indexOf(';', i);
                if (end < 0) { out.add(paramDesc.substring(start)); break; }
                out.add(paramDesc.substring(start, end + 1));
                i = end + 1;
            } else {
                // primitive: one char
                out.add(paramDesc.substring(start, i + 1));
                i++;
            }
        }
        return out;
    }

    private static String smaliInternalToFqn(String internal) {
        // convert "com/foo/Outer$Inner" to "com.foo.Outer.Inner"
        // also replace $ with . for inner classes, otherwise the lookups wont match JADX names
        return internal.replace('/', '.').replace('$', '.');
    }

    private static String smaliTypeToFqn(String desc) {
        // strips the L prefix and ; suffix from class descriptors
        // "Lcom/foo/Bar;" becomes "com.foo.Bar", same $ to . fix as above
        if (desc == null || desc.isEmpty()) return desc;
        if (desc.charAt(0) == 'L' && desc.charAt(desc.length() - 1) == ';') {
            return desc.substring(1, desc.length() - 1).replace('/', '.').replace('$', '.');
        }
        return desc;
    }

    private static String extractSmaliMethodBody(String classSmali, String methodName, String paramSignature) {
        // find the method declaration in smali and grab the body until .end method
        // using lookbehind instead of \b because <init> and <clinit> have the
        // < character which doesnt play nice with word boundaries
        //
        // if paramSignature is given only return that specific overload,
        // otherwise just take the first match we find
        Pattern start = Pattern.compile(
                "^\\.method\\s[^\\n]*?(?<![^\\s])" + Pattern.quote(methodName) + "\\(([^)]*)\\)[^\\s]+\\s*$",
                Pattern.MULTILINE);
        Matcher m = start.matcher(classSmali);
        boolean filterByParams = paramSignature != null && !paramSignature.isEmpty();
        while (m.find()) {
            if (filterByParams && !paramSignature.equals(m.group(1))) continue;
            int s = m.end();
            int e = classSmali.indexOf("\n.end method", s);
            if (e < 0) e = classSmali.length();
            return classSmali.substring(s, e);
        }
        return null;
    }

    private static final String[] LIBRARY_PREFIXES = {
            "androidx.", "android.support.", "com.google.", "com.android.",
            "kotlin.", "kotlinx.", "okhttp3.", "okio.", "retrofit2.",
            "com.squareup.", "io.reactivex.", "rx.", "dagger.",
            "com.facebook.react.", "com.amazonaws.", "org.apache.", "org.json.",
            "com.fasterxml.", "org.slf4j.", "javax.", "junit.",
            "io.netty.", "com.bumptech.glide.", "org.greenrobot.",
            "com.airbnb.lottie.", "io.realm.", "bolts.", "butterknife."
    };

    private static boolean isLikelyLibrary(String fqn) {
        if (fqn == null) return false;
        for (String p : LIBRARY_PREFIXES) if (fqn.startsWith(p)) return true;
        return false;
    }

    /**
     * Clears the cached inheritance index so it gets rebuilt next time.
     * Mostly used by tests or when you know the project changed and dont
     * want to wait for the auto-detection to kick in.
     *
     * Nulls both the index and fingerprint under the lock so they stay in sync.
     */
    public static void invalidateCaches() {
        synchronized (AdvancedRoutes.class) {
            inheritanceIndex = null;
            currentFingerprint = null;
        }
    }
}
