package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.ResourceFile;
import jadx.api.security.IJadxSecurity;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.utils.android.AppAttribute;
import jadx.core.utils.android.ApplicationParams;
import jadx.core.utils.exceptions.JadxRuntimeException;
import jadx.core.xmlgen.ResContainer;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.io.InputStream;

import com.zin.jadxaimcp.utils.PaginationUtils;
import com.zin.jadxaimcp.utils.PaginationUtils.PaginationException;
import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;

public class ResourceRoutes {
    private static final Logger logger = LoggerFactory.getLogger(ResourceRoutes.class);
    private final MainWindow mainWindow;
    private final PaginationUtils paginationUtils;

    public ResourceRoutes(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
        this.paginationUtils = new PaginationUtils();
    }

    /**
     * @return void
     * @param Context
     * 
     * This route method handles /manifest http MCP tool call. 
     * First it tries to get the manifest file using getManifestFile() method
     * and assigns it to object named manifest. If this object is null then it returns,
     * else it loads the contents of manifest file in ResContainer object and from it fetches 
     * the content in String and returns this content.
     */
    public void handleManifest(Context ctx) {
        try {
            ResourceFile manifest = getManifestFile();
            if (manifest == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "AndroidManifest.xml not found.", logger);
                return;
            }
            ResContainer container = manifest.loadContent();
            String content = container.getText().getCodeStr();
            ctx.json(Map.of("name", manifest.getOriginalName(), "type", "manifest/xml", "content", content));
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error occurred while trying to fetch the AndroidManifest.xml file: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     * This routing method handle the /strings mcp tool call.
     * 1. For each resource file,
     *  a. Check if file compiled resources archive "resources.arsc"
     *      i. If yes then load that resource file content
     *          - for each subFile in content.subfiles
     *              * if subfile's path == "res/values/strings.xml" then add it to allStringsEntries
     *      ii. If no then check if file is standalone strings.xml 
     *          - if yes then load the content and add it to allStringsEntries
     */
    public void handleStrings(Context ctx) {
        try {
            List<Map<String, String>> allStringEntries = new ArrayList<>();
            List<ResourceFile> resourceFiles = mainWindow.getWrapper().getResources();

            for (ResourceFile resFile : resourceFiles) {
                try {
                    if ("resources.arsc".equals(resFile.getDeobfName())) {
                        for (ResContainer file : resFile.loadContent().getSubFiles()) {
                            if ("res/values/strings.xml".equals(file.getFileName())) {
                                allStringEntries.add(Map.of("file", file.getFileName(),
                                                            "content", file.getText().getCodeStr()));
                            }
                        }
                    } else if ("res/values/strings.xml".equals(resFile.getDeobfName())) {
                        allStringEntries.add(Map.of("file", resFile.getDeobfName(), "content",
                                                     resFile.loadContent().getText().getCodeStr()));
                    }
                } catch (Exception e) {
                    logger.error("JADX AI MCP Plugin Error: Error processing resource file during handleStrings(): " + e.getMessage());
                }
            }

            if (allStringEntries.isEmpty()) {
                JadxAIMCPPluginError.handleError(ctx, 404, "No strings.xml resource found.", logger);
                return;
            }

            Map<String, Object> result = paginationUtils.handlePagination(ctx, allStringEntries, "resource/strings-xml",
                                                                          "strings", item->item);
            ctx.json(result);
        } catch (PaginationException e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while generating pagination result for handleStrings(): " + e.getMessage(), e, logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error occurred while trying to handle the /strings: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     * This method handle the /get-resource-file mcp tool call.
     * 
     * First it validates the http request for 'file_name' parameter.
     * Then for each ResourceFile 
     *  1. if this ResourceFile's is equal to the requested file
     *      a. return this file
     *  2. If this ResourceFile is compiled resource archive
     *      a. Then for each subfile in this compiled resource archive
     *          - Check if the subfile is the one requested - if yes then return it
     *  3. Break once any mathcing file is found
     * If none found then handle it else return the requested file.
     */
    public void handleGetResourceFile(Context ctx) {
        String fileName = ctx.queryParam("file_name");
        if (fileName == null || fileName.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required 'file_name' parameter.", logger);
            return;
        }

        try {
            List<ResourceFile> resourceFiles = mainWindow.getWrapper().getResources();
            String matchedFileName = null;
            String matchedContent = null;
            boolean matchedNonText = false;

            for (ResourceFile resFile : resourceFiles) {
                String deobfName = resFile.getDeobfName();

                // 1) Direct top-level resource match.
                if (fileName.equals(deobfName)) {
                    matchedFileName = deobfName;
                    matchedContent = safeExtractText(safeLoadContent(resFile));
                    if (matchedContent == null) {
                        matchedNonText = true;
                    }
                    break;
                }

                // 2) Search nested files in any container resource.
                try {
                    ResContainer container = resFile.loadContent();
                    List<ResContainer> subFiles = container.getSubFiles();
                    if (subFiles == null || subFiles.isEmpty()) {
                        continue;
                    }

                    for (ResContainer file : subFiles) {
                        if (!fileName.equals(file.getFileName())) {
                            continue;
                        }

                        matchedFileName = file.getFileName();
                        matchedContent = safeExtractText(file);
                        if (matchedContent == null) {
                            matchedNonText = true;
                        }
                        break;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to inspect subfiles for {}: {}", deobfName, e.getMessage());
                }

                if (matchedFileName != null) {
                    break;
                }
            }

            if (matchedFileName == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "No resource file found", logger);
                return;
            }

            if (matchedContent == null) {
                // Avoid throwing ClassCastException on non-text resources and return
                // a stable response instead of HTTP 500 (issue #59).
                Map<String, String> file = new HashMap<>();
                file.put("file_name", matchedFileName);
                file.put("content", "");
                file.put("note", matchedNonText
                        ? "Matched resource is not text-decodable by JADX"
                        : "Unable to decode resource content");
                ctx.json(Map.of("type", "resource/binary", "file", file));
                return;
            }

            Map<String, String> file = new HashMap<>();
            file.put("file_name", matchedFileName);
            file.put("content", matchedContent);
            ctx.json(Map.of("type", "resource/text", "file", file));
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal Error occured while trying to handle the handleGetResourceFile(): " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     * This method handle the /list-all-resource-file-names mcp tool call.
     * 
     * For each resource file, 
     *  1. if the resource file if compiled resource archive "resources.arsc"
     *      a. load the content of complied resource archive.
     *      b. get all the sub files.
     *      c. get the names of or sub files' names.
     *  2. if it is standalone file, then directly get it's name.
     *  3. If none file is found, return error
     *  4. else return the list of resoure files names with pagination support.
     */
    public void handleListAllResourceFilesNames(Context ctx) {
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
                    logger.error("JADX AI MCP Error: Internal error occurred while trying to read the resourcefile in handleListAllResourceFilesNames" + e.getMessage(), e);
                }
            }

            if (resourceFileNames.isEmpty()) {
                JadxAIMCPPluginError.handleError(ctx, 404, "No resources found.", logger);
                return;
            }

            Map<String, Object> result = paginationUtils.handlePagination(
                ctx,
                resourceFileNames,
                "application-resources",
                "files",
                item -> item);

            ctx.json(result);
        } catch (PaginationException e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while generating pagination result for handleListAllResourceFilesNames(): " + e.getMessage(), e, logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while retrieving list of resource files names: " + e.getMessage(), e, logger);
        }
    }

    // Helper methods

    /**
     * Safely extract text content from a resource container.
     * Some resource kinds are binary-backed and can throw class cast errors when
     * accessed as text; this helper normalizes those failures to null.
     */
    private String safeExtractText(ResContainer container) {
        if (container == null) {
            return null;
        }
        try {
            if (container.getText() == null) {
                return null;
            }
            return container.getText().getCodeStr();
        } catch (ClassCastException e) {
            logger.debug("Resource is not text-decodable: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.debug("Failed to decode resource text: {}", e.getMessage());
            return null;
        }
    }

    private ResContainer safeLoadContent(ResourceFile file) {
        if (file == null) {
            return null;
        }
        try {
            return file.loadContent();
        } catch (ClassCastException e) {
            logger.debug("Resource content is not loadable as text: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            logger.debug("Failed to load resource content: {}", e.getMessage());
            return null;
        }
    }

    /**
     * @param
     * @return ResourceFile
     * 
     * This helper method is used to get the android manifest file using jadx's AndroidManifestParser class.
     */
    private ResourceFile getManifestFile() {
        return AndroidManifestParser.getAndroidManifest(mainWindow.getWrapper().getResources());
    }
}
