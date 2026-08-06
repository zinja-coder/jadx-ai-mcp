# Enhanced API Reference

This page documents the MCP tools currently exposed by `jadx_mcp_server.py`.

## Notes About Responses

- Some underlying plugin endpoints return raw text (`ctx.result(...)` in Java routes).
- In those cases, the Python MCP server returns:
  - `{"response": "<raw text>"}` (from `get_from_jadx(...)` fallback)
- For paginated endpoints, responses generally follow:
  - `{"type": "...", "items": [...], "pagination": {...}}`

---

## Class Analysis

### `fetch_current_class()`
Fetches the currently selected class from JADX-GUI.

### `get_selected_text()`
Returns selected text from the current editor pane.

### `get_all_classes(offset: int = 0, count: int = 0)`
Returns paginated list of all classes. `count=0` means no explicit limit.

### `get_class_source(class_name: str)`
Gets full Java source for a class.

### `get_methods_of_class(class_name: str)`
Lists methods for a class.

### `get_fields_of_class(class_name: str)`
Lists fields for a class.

### `get_smali_of_class(class_name: str)`
Gets smali for a class.

### `get_main_application_classes_names()`
Returns class names under the app package from `AndroidManifest.xml`.

### `get_main_application_classes_code(offset: int = 0, count: int = 0)`
Returns paginated decompiled code for classes under the app package.

### `get_main_activity_class()`
Returns main launcher activity class and source.

### `get_package_tree()`
Returns package summary with class counts and `is_likely_library` heuristic.

### `get_cache_stats()`
Returns decompilation cache statistics.

### `clear_cache()`
Clears decompilation cache and resets counters.

---

## Search

### `get_method_by_name(class_name: str, method_name: str, method_signature: str = None)`
Fetches one method body from a specific class.

### `search_method_by_name(method_name: str)`
Searches method names globally.

Notes:
- Matching is substring-based.
- Current Java route returns a newline-delimited class list as text, so output can be:
  - `{"response": "com.example.A\ncom.example.B\n..."}`

### `search_classes_by_keyword(search_term: str, package: str = "", search_in: str = "code", offset: int = 0, count: int = 20)`
Searches classes by keyword with package/scope filters.

Valid `search_in` values:
- `class`
- `method`
- `field`
- `code`
- `comment`

You can combine scopes with commas, for example:
- `class,method`
- `class,method,code`

---

## Resources

### `get_android_manifest()`
Returns parsed/raw manifest payload from plugin endpoint.

### `get_manifest_component(component_type: str, only_exported: bool = False)`
Extracts manifest components by type.

Valid `component_type`:
- `activity`
- `service`
- `receiver`
- `provider`

### `get_strings(offset: int = 0, count: int = 0)`
Returns paginated strings extracted from resources.

### `get_all_resource_file_names(offset: int = 0, count: int = 0)`
Returns paginated resource file paths.

### `get_resource_file(resource_name: str)`
Returns resource file content by path, e.g. `res/layout/activity_main.xml`.

---

## Cross References

### `get_xrefs_to_class(class_name: str, offset: int = 0, count: int = 20)`
Finds references to a class.

### `get_xrefs_to_method(class_name: str, method_name: str, offset: int = 0, count: int = 20)`
Finds references to a method (includes override-related methods in route logic).

### `get_xrefs_to_field(class_name: str, field_name: str, offset: int = 0, count: int = 20)`
Finds references to a field.

---

## Refactoring

### `rename_class(class_name: str, new_name: str)`
Renames class.

### `rename_method(method_name: str, new_name: str, class_name: str = None, method_signature: str = None)`
Renames method by name/signature.

Notes:
- `class_name` is optional but strongly recommended for obfuscated apps.
- If method overloads exist, provide `method_signature` as well.

### `rename_field(class_name: str, field_name: str, new_name: str)`
Renames field in a specific class.

### `rename_package(old_package_name: str, new_package_name: str)`
Renames package.

### `rename_variable(class_name: str, method_name: str, variable_name: str, new_name: str, reg: str = None, ssa: str = None)`
Renames local variable with optional `reg` and `ssa` disambiguation.

---

## Debugging

### `debug_get_stack_frames()`
Returns stack frames when debugger is active and suspended.

### `debug_get_threads()`
Returns debugged-process threads.

### `debug_get_variables()`
Returns locals/fields from current debug context.

---

## Tool Count

Current MCP tool count exposed by `jadx_mcp_server.py`: **32**.
