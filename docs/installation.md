# Installation Guide

## Prerequisites

- Java 11+
- JADX 1.5.1+
- UV installed
- Python 3.10+

## 1) Install the JADX plugin

Recommended:

```bash
jadx plugins --install "github:zinja-coder:jadx-ai-mcp"
```

Alternative: install the release JAR from JADX GUI plugin manager.

## 2) Prepare MCP server

```bash
git clone https://github.com/zinja-coder/jadx-ai-mcp.git
cd jadx-ai-mcp/jadx-mcp-server
uv --version
```

## 3) Validate server startup

```bash
uv run jadx_mcp_server.py --help
```

Run (stdio mode, recommended for desktop MCP clients):

```bash
uv run jadx_mcp_server.py
```

## 4) Configure MCP client

Use absolute paths.

```json
{
  "mcpServers": {
    "jadx-mcp-server": {
      "command": "/path/to/uv",
      "args": [
        "--directory",
        "/absolute/path/to/jadx-ai-mcp/jadx-mcp-server",
        "run",
        "jadx_mcp_server.py"
      ]
    }
  }
}
```

## Optional: install as a tool

```bash
uv tool install git+https://github.com/zinja-coder/jadx-mcp-server
```

Then:

```json
{
  "mcpServers": {
    "jadx-mcp-server": {
      "command": "jadx_mcp_server"
    }
  }
}
```

Packaged install also supports Python 3.10+.

## HTTP mode (optional)

```bash
uv run jadx_mcp_server.py --http
```

Useful flags:
- `--host` / `--port`: where MCP server listens
- `--jadx-host` / `--jadx-port`: where MCP server reaches JADX plugin

## Security warning

Using `--host 0.0.0.0` exposes an unauthenticated HTTP service.  
Use only on trusted networks and prefer firewall/SSH tunnel controls.

## Verification Checklist

1. Open JADX-GUI with an APK loaded.
2. Confirm plugin server status in plugin menu.
3. Start MCP client and verify `jadx-mcp-server` tools appear.
4. Test with prompt: `List available JADX MCP tools`.
