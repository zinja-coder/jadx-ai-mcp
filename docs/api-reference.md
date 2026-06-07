# Enhanced API Reference

Comprehensive guide to all JADX-AI-MCP tools with detailed usage examples.

## Table of Contents

- [Class Analysis](#class-analysis)
- [Search Capabilities](#search-capabilities)
- [Resource Analysis](#resource-analysis)
- [Cross-Reference Analysis](#cross-reference-analysis)
- [Refactoring](#refactoring)
- [Debugging](#debugging)

---

## Class Analysis

Tools for inspecting decompiled Java code.

### `fetch_current_class()`

Fetches the currently selected class in JADX-GUI.

**Parameters:** None

**Returns:**
```json
{
  "className": "com.example.MainActivity",
  "package": "com.example",
  "source": "public class MainActivity...",
  "type": "class"
}
```

**Use Case:** Quick context for "Explain this class" prompts.

---

### `get_all_classes(offset: int = 0, count: int = 0)`

Lists all classes in the APK.

**Parameters:**
- `offset` (int): Starting index
- `count` (int): Number to return (0 = all)

**Example:**
```python
# Get first 100 classes
classes = await get_all_classes(offset=0, count=100)
print(f"Total classes: {classes['pagination']['total']}")
```

**Best Practice:** Always use pagination for production APKs to avoid timeouts.

---

### `get_class_source(class_name: str)`

Gets full source code for a specific class.

**Parameters:**
- `class_name` (str): Fully qualified name

**Example:**
```python
source = await get_class_source("com.example.crypto.AES")
```

**Note:** Returns cached source if already decompiled.

---

## Search Capabilities

### `search_classes_by_keyword(search_term: str, search_in: str = "all", package: str = "", offset: int = 0, count: int = 20)`

Full-text search across code with advanced scoping.

**Parameters:**
- `search_term` (str): Text to find
- `search_in` (str): Scope ("all", "code", "comments", "strings")
- `package` (str): Restrict to specific package (e.g., "com.example")
- `offset` (int): Start index
- `count` (int): Max results

**Example:**
```python
# Find hardcoded passwords in comments only
results = await search_classes_by_keyword("password", search_in="comments", count=50)

for res in results['items']:
    print(f"Found in {res['className']}: {res['preview']}")
```

---

### `search_method_by_name(method_name: str)`

Finds methods matching a name pattern.

**Parameters:**
- `method_name` (str): Name or partial signature

**Example:**
```python
# Find encryption methods
methods = await search_method_by_name("encrypt")
```

---

## Resource Analysis

### `get_android_manifest()`

Parses AndroidManifest.xml.

**Returns:**
- Package name
- Version info
- Permissions
- Activities/Services/Receivers/Providers
- Raw XML

**Use Case:** Security auditing permissions and exported components.

---

### `get_manifest_component(type: str)`

Get specific components from AndroidManifest.xml.

**Parameters:**
- `type` (str): "Activity", "Service", "Receiver", or "Provider"

---

### `get_strings(offset: int = 0, count: int = 0)`

Extracts strings from `res/values/strings.xml`.

**Parameters:**
- `offset` (int): Start index
- `count` (int): Max strings

**Use Case:** Finding API keys, URLs, or hidden messages.

---

## Cross-Reference Analysis

### `get_xrefs_to_method(class_name: str, method_name: str, ...)`

Finds all callers of a method.

**Example:**
```python
# Who calls login()?
callers = await get_xrefs_to_method(
    "com.example.Auth", 
    "login",
    count=100
)
```

**Features:**
- Includes direct calls
- Includes interface implementations
- Includes super calls

---

## Refactoring

Refactoring tools mutate the open JADX project. The Java plugin accepts these HTTP calls with POST only, requires the plugin bearer token by default, and validates Java identifiers/package names before applying changes.

### `rename_class(class_name: str, new_name: str)`

Renames class and updates references.

**Example:**
```python
# Deobfuscate
await rename_class("a.b.c", "CryptoHelper")
```

**Warning:** Affects multiple files. Use carefully.

---

### `rename_method(class_name: str, method_name: str, new_name: str)`

Renames method and updates references.

---

### `rename_package(old_pkg: str, new_pkg: str)`

Renames an entire package and updates declarations/imports.

---

### `rename_variable(class_name: str, method_name: str, old_var: str, new_var: str)`

Renames a local variable inside a specific method.

---

## Debugging

Debug tools expose runtime state from the debugged process. They require the plugin bearer token by default and can be disabled with `JADX_AI_MCP_DISABLE_DEBUG=true`.

### `debug_get_stack_frames()`

Gets current call stack.

**Requirements:**
- Debugger active
- Process suspended

**Returns:**
- List of stack frames (class, method, line)

---

### `debug_get_variables()`

Gets local variables and fields.

**Returns:**
- Locals (name, type, value)
- Fields (name, type, value)

**Security Note:** Values may contain sensitive data (passwords, keys).
