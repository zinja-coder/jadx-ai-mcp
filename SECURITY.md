# Security Policy
---
### Runtime Hardening

The Java-side server binds to `127.0.0.1` and requires `Authorization: Bearer <token>` by default in both GUI and headless modes. Use `JADX_AI_MCP_TOKEN` or the `jadx.ai.mcp.token` system property to set a stable token for the Python MCP bridge.

Headless mode runs without `jadx-gui` by loading inputs through `jadx-core`. It exposes read/search/resource/xref routes, but GUI-only selection, refactor, and debugger routes are unavailable.

Tool output derived from APK code, resources, or debugger state is untrusted artifact data and must not be treated as user, developer, or system instructions.

### Reporting a Vulnerability

To report a security issue, please open a new [security advisory](https://github.com/zinja-coder/jadx-ai/security/advisories). Please fill the steps you took to create the issue, affected versions, and, if known, mitigations for the issue. We will check and respond within 3 working days. If the issue is confirmed as a vulnerability, we will apply required mitigations at the next release.
