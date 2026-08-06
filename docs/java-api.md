# Java Plugin Reference

This page reflects the current Java plugin structure and route handlers.

## Core Plugin Class

### `com.zin.jadxaimcp.JadxAIMCP`

Key responsibilities:

- Implements `JadxPlugin`
- Registers plugin metadata (`PLUGIN_ID = "jadx-ai-mcp"`)
- Initializes menu integration
- Starts plugin HTTP server with delayed startup logic
- Persists plugin port with Java Preferences

Notable defaults:

- Default plugin port: `8650`

## HTTP Server

### `com.zin.jadxaimcp.server.PluginServer`

Creates embedded Javalin server and registers routes.

Current route groups:

- Health/general
- Class/source/navigation
- Search
- Resource
- Refactor
- Xrefs
- Debug
- Cache/package helpers

## Route Controllers

Located in:

- `com.zin.jadxaimcp.server.routes.GeneralRoutes`
- `ClassRoutes`
- `MethodRoutes`
- `ResourceRoutes`
- `RefactoringRoutes`
- `XrefsRoutes`
- `DebugRoutes`

### Important route behavior details

- Several handlers return plain text via `ctx.result(...)`.
- Others return JSON via `ctx.json(...)`.
- Pagination is handled with `com.zin.jadxaimcp.utils.PaginationUtils`.
- Search progress state comes from `SearchProgressTracker`.

## Refactor Route Notes

### `RefactoringRoutes.handleRenameMethod(...)`

Current HTTP params:

- optional `class_name`
- `method_name`
- `new_name`
- optional `method_signature`

## Search Route Notes

### `ClassRoutes.handleSearchClassesByKeyword(...)`

Current `search_in` values:

- `class`
- `method`
- `field`
- `code`
- `comment`

Default search location: `code`.

## Utilities

### `com.zin.jadxaimcp.utils.DecompilationCache`

Used by class/search paths and exposed through:

- `/cache-stats`
- `/cache-clear`

### `com.zin.jadxaimcp.utils.PaginationUtils`

Shared pagination helper for class/xref/search-like endpoints.

### `com.zin.jadxaimcp.utils.JadxAIMCPPluginError`

Centralized HTTP error response helper used by route handlers.

## Practical Extension Path

To add a new capability:

1. Add route handler in `server/routes/*`
2. Register route in `PluginServer.registerRoutes()`
3. Add Python wrapper in `jadx-mcp-server/src/server/tools/*`
4. Register MCP tool in `jadx_mcp_server.py`

This keeps Java route surface and MCP tool surface aligned.
