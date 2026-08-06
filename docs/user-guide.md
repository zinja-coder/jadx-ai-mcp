# User Guide

## Standard Workflow

1. Open JADX-GUI and load APK
2. Start MCP server (`uv run jadx_mcp_server.py`)
3. Connect MCP client
4. Start with discovery tools:
   - `get_package_tree()`
   - `get_main_activity_class()`
   - `get_all_classes(...)`

## Useful Prompt Patterns

### Initial triage

```text
Get package tree, main activity, and main application class names.
Then summarize likely entry points and sensitive modules.
```

### Security hunt

```text
Search for WebView, crypto, auth, and manifest risks.
Return high-risk findings first with class names.
```

### Refactor/deobfuscate

```text
Suggest better names for obfuscated classes and fields,
then apply safe renames incrementally.
```

## Search Usage

Use `search_classes_by_keyword` with real supported scopes:

- `class`
- `method`
- `field`
- `code`
- `comment`

Examples:

```text
search_classes_by_keyword(search_term="password", search_in="code")
search_classes_by_keyword(search_term="retrofit", search_in="class,method")
search_classes_by_keyword(search_term="token", package="com.example.auth", search_in="field,code")
```

Default `search_in` is `code`.

## Refactor Usage Notes

- `rename_class(class_name, new_name)` is class-scoped.
- `rename_field(class_name, field_name, new_name)` is class-scoped.
- `rename_method(method_name, new_name, class_name=None, method_signature=None)` supports optional class scoping.

When renaming methods in obfuscated apps, pass both `class_name` and `method_signature` where possible.

## Debug Session Tips

For debugger tools (`debug_get_stack_frames`, `debug_get_threads`, `debug_get_variables`):

- Ensure process is paused on a breakpoint.
- Pull stack + variables together to understand execution context.

## Large APK Strategy

- Use pagination for broad scans (`offset`/`count`).
- Narrow package scope before deep source extraction.
- Use cache tools (`get_cache_stats`, `clear_cache`) when switching APKs.
