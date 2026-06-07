# Java Plugin API Reference

Complete reference for the JADX-AI-MCP Java plugin codebase.

## Core Plugin Architecture

The plugin is built on top of JADX's plugin system and exposes functionality via an embedded Javalin HTTP server.

### Class: `JadxAIMCP`

**Package**: `com.zin.jadxaimcp`

The main entry point implementing `JadxPlugin`.

#### Lifecycle Methods

| Method | Description |
|--------|-------------|
| `init(JadxPluginContext context)` | Initializes plugin, UI components, and delayed server startup |
| `getPluginInfo()` | Returns metadata (ID, name, description, homepage) |
| `startDelayedInitialization()` | Waits for JADX to load APK content before starting server |
| `startServer()` | Starts the embedded HTTP server on configured port |

#### State Management

- **Persistence**: Uses `Java Preferences API` to store port configuration
- **Threading**: Uses `ScheduledExecutorService` for background tasks
- **UI Integration**: Injects menu items into JADX-GUI via `PluginMenu`

**Example:**
```java
// Delayed initialization pattern
private void startDelayedInitialization() {
    scheduler.scheduleAtFixedRate(() -> {
        if (isJadxFullyLoaded()) {
            startServer();
            scheduler.shutdown();
        }
    }, 2, 1, TimeUnit.SECONDS);
}
```

---

## HTTP Server & Routing

### Class: `PluginServer`

**Package**: `com.zin.jadxaimcp`

Manages the embedded Jetty server via Javalin framework.

#### Configuration

- **Host**: `127.0.0.1` (Localhost only for security)
- **Port**: Configurable (default: 8650)
- **JSON Mapper**: Uses Jackson for serialization
- **Authentication**: Bearer token required by default. Configure `JADX_AI_MCP_TOKEN` or read the generated token from the plugin status dialog.

#### Route Registration

```java
app.get("/class-source", ClassRoutes::getClassSource);
app.get("/all-classes", ClassRoutes::getAllClasses);
app.get("/search-method", SearchRoutes::searchMethod);
// ... more routes
```

---

## Route Handlers

### Class: `ClassRoutes`

**Package**: `com.zin.jadxaimcp.routes`

Handles all class-related operations.

#### Methods

| Endpoint | Method Handler | Description |
|----------|----------------|-------------|
| `/class-source` | `getClassSource` | Fetches decompiled source code |
| `/all-classes` | `getAllClasses` | Returns paginated class list |
| `/smali-of-class` | `getSmali` | Returns Smali bytecode |
| `/fields-of-class` | `getFields` | Lists class fields |

**Implementation Example:**
```java
public static void getClassSource(Context ctx) {
    String className = ctx.queryParam("class_name");

    // Access JADX API on EDT
    SwingUtilities.invokeLater(() -> {
        JavaClass cls = wrapper.searchJavaClassByFullName(className);
        String source = cls.getCode();

        Platform.runLater(() -> {
            ctx.json(Map.of("source", source));
        });
    });
}
```

---

### Class: `SearchRoutes`

**Package**: `com.zin.jadxaimcp.routes`

Handles search operations across the decompiled codebase.

#### Methods

| Endpoint | Method Handler | Description |
|----------|----------------|-------------|
| `/search-method` | `searchMethod` | Finds methods by name |
| `/search-classes-by-keyword` | `searchClasses` | Full-text code search (supports scopes & packages) |

**Implementation Note:**
Full-text search uses JADX's incremental search index for performance.

---

### Class: `XrefsRoutes`

**Package**: `com.zin.jadxaimcp.routes`

Handles cross-reference analysis.

#### Methods

| Endpoint | Method Handler | Description |
|----------|----------------|-------------|
| `/xrefs-to-class` | `getXrefsToClass` | Finds class usages |
| `/xrefs-to-method` | `getXrefsToMethod` | Finds method calls |
| `/xrefs-to-field` | `getXrefsToField` | Finds field accesses |

**Key Feature:**
Resolves method overrides and super calls automatically.

---

### Class: `RefactoringRoutes`

**Package**: `com.zin.jadxaimcp.routes`

Handles code renaming operations.

#### Methods

| Endpoint | Method Handler | Description |
|----------|----------------|-------------|
| `POST /rename-class` | `renameClass` | Renames class & updates refs |
| `POST /rename-method` | `renameMethod` | Renames method & updates calls |
| `POST /rename-field` | `renameField` | Renames field & updates accesses |
| `POST /rename-variable` | `renameVariable` | Renames local variables within a method |
| `POST /rename-package` | `renamePackage` | Renames package & updates all classes |

**Safety Check:**
Validates new names against Java naming conventions before applying.

---

## Utilities

### Class: `PaginationUtils`

**Package**: `com.zin.jadxaimcp.utils`

Provides consistent pagination logic for large datasets.

#### Methods

| Method | Description |
|--------|-------------|
| `paginate(List<T> list, int offset, int limit)` | Returns sublist with metadata |
| `validateParams(int offset, int limit)` | Ensures safe bounds |

**Pagination Response Format:**
```json
{
  "items": [...],
  "pagination": {
    "total": 1500,
    "offset": 0,
    "limit": 100,
    "count": 100,
    "has_more": true
  }
}
```

---

## Extension Guide

### How to Add a New Route

1. **Create Handler Method** in appropriate Route class:
```java
// routes/MyRoutes.java
public static void myHandler(Context ctx) {
    String param = ctx.queryParam("param");
    // logic...
    ctx.json(result);
}
```

2. **Register Route** in `PluginServer.java`:
```java
app.get("/my-endpoint", MyRoutes::myHandler);
```

3. **Add Python Tool** in `src/server/tools/`:
```python
async def my_tool(param: str):
    return await get_from_jadx("my-endpoint", {"param": param})
```

4. **Register MCP Tool** in `jadx_mcp_server.py`:
```python
@mcp.tool()
async def my_tool(param: str):
    return await tools.my_tool(param)
```

---

## JADX API Integration

### Accessing Decompiled Code
```java
JadxWrapper wrapper = mainWindow.getWrapper();
JavaClass cls = wrapper.searchJavaClassByFullName("com.example.MainActivity");
String source = cls.getCode();
```

### Accessing Resources
```java
ResourceFile manifest = wrapper.getResources().get(0); // AndroidManifest.xml
String content = manifest.loadContent().getText().getCodeStr();
```

### Accessing Debugger
```java
DebuggerController debugger = mainWindow.getDebuggerPanel().getController();
List<StackFrame> stack = debugger.getStack();
```
