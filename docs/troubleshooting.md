# Troubleshooting Guide

Solutions for common issues encountered when using JADX-AI-MCP.

## 🔍 Diagnostic Checklist

Before diving deep, check these basics:

1.  **JADX Version**: Is it 1.5.1+? (`jadx --version`)
2.  **Plugin Installed**: Is `jadx-ai-mcp.jar` in `~/.jadx/plugins/`?
3.  **Server Running**: Is the Python server process active?
4.  **Port Match**: Does server `--jadx-port` match plugin config?
5.  **APK Loaded**: Is a file actually open in JADX?

---

## Connection Issues

### "Connection Refused" / Server Won't Start
**Error**: `httpx.ConnectError: [Errno 111] Connection refused`

**Causes:**
1.  Plugin server is not running
2.  Port mismatch

**Solutions:**
1.  **Check Plugin Status**: Open JADX → Plugins → JADX-AI-MCP → Status. It should say "Running on port 8650".
2.  **Restart Plugin**: JADX → Plugins → JADX-AI-MCP → Restart Server.
3.  **Check Port & Host**: Ensure Python script targets correct JADX instance:
    ```bash
    # Local JADX connection
    uv run jadx_mcp_server.py --jadx-port 8650
    # Remote JADX connection
    uv run jadx_mcp_server.py --jadx-host IP_ADDRESS --jadx-port 8650
    ```

### "Port Already in Use"
**Error**: `BindException: Address already in use`

**Solution:**
1.  **Find Process**:
    ```bash
    lsof -i :8650
    ```
2.  **Kill Process**:
    ```bash
    kill -9 <PID>
    ```
3.  **Change Port**: Configure a different port in Plugin menu (e.g., 8655) and restart.

---

## Python Server Issues

### "Module Not Found"
**Error**: `ModuleNotFoundError: No module named 'fastmcp'`

**Solution**:
Use `uv` (recommended) or install dependencies manually:
```bash
uv pip install -r requirements.txt
```

### "Python Version Mismatch"
**Error**: install/runtime failure related to Python requirements

**Solution**:
Use Python 3.10+ for both script run and package install modes.

```bash
python3 --version
```

---

## Plugin Issues

### Plugin Not Appearing in Menu
**Cause**: Incompatible JADX version or corrupt JAR.

**Solution**:
1.  Update JADX to latest release.
2.  Delete `~/.jadx/plugins/jadx-ai-mcp*`.
3.  Reinstall via `jadx plugins --install ...`.

### "No APK Loaded" Error
**Error**: Tools return `NO_APK_LOADED`.

**Solution**:
The plugin requires an active project context. Open an APK file in JADX-GUI before using AI tools.

---

## LLM Client Issues

### Tools Not Showing in Claude
**Cause**: Config file syntax error or path issue.

**Solution**:
1.  Validate JSON: `cat config.json | jq .`
2.  Use **absolute paths** in config.
3.  Check Claude logs:
    - Mac: `~/Library/Logs/Claude/`
    - Linux: `~/.config/Claude/logs/`

### "Tool Execution Failed"
**Cause**: Timeout or internal error.

**Solution**:
1.  Check Python server console for traceback.
2.  If timeout, reduce `count` parameter (pagination).
3.  Restart Python server.

---

## Getting Help

If you're still stuck:

1.  Gather logs (JADX + Python console).
2.  Open an issue on [GitHub](https://github.com/zinja-coder/jadx-ai-mcp/issues).
