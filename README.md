# IntelliAiBridge: The IDE-to-Agent Copilot Proxy

IntelliAiBridge exposes your existing IntelliJ GitHub Copilot entitlement as a local, **OpenAI-compatible HTTP API**.

## 💡 Why does this exist?

Many modern AI coding tools (like OpenDevin, Aider, Cline, or custom autonomous agents) rely on direct API access or CLI tools. However, in many enterprise environments:
1. **GitHub Copilot CLI or direct API access is restricted** by corporate policy.
2. **IDE-based Copilot access is approved**, licensed, and authenticated.

**IntelliAiBridge bridges this gap.** If you want to code in a more *agentic* way using external tools, but are constrained to your company's approved IntelliJ Copilot extension, this plugin acts as a secure, local proxy. Your external agents talk to this plugin via the standard OpenAI protocol, and this plugin transparently routes those requests through the authenticated Copilot session already running inside your IDE.

## 🚀 What you get

- **Local API server** (`http://127.0.0.1:3040` by default).
- **Agent-Ready Compatibility:** Supports the OpenAI schema that most agents expect:
  - `POST /v1/chat/completions` (with streaming support).
  - Tool call compatibility (`tools` + XML tool-call extraction).
  - `GET /v1/models` (Model discovery from Copilot).
- **Secure Authentication:** API key auth (`Authorization: Bearer <key>`) stored safely in IntelliJ Password Safe.
- **Enterprise Friendly:** No external credential leakage. Traffic flows through your already-approved IDE network path.

## 📦 Requirements

- IntelliJ Platform IDE compatible with this plugin build.
- GitHub Copilot plugin installed and authenticated.
- Active Copilot entitlement for the user.
- Java/Gradle environment for local development.

## ⚡ Quick start

1. **Build/run the plugin sandbox:**

```bash
./gradlew runIde
```

2. **Configure the Bridge (Mandatory):**
- In IntelliJ, open `Settings > Tools > IntelliAiBridge`.
- Set your `Host` and `Port`.
- **Set an API Key:** You **must** create a custom API key here. This key acts as your local password; your CLI agents will use this key in their `Authorization` headers to talk to the bridge.

3. **Start the server:**
- Click `Start` from the IntelliAiBridge tool window, or toggle it from the status bar.

4. **Connect your Agent:**
Once the server is running, the OpenAI-compatible API is exposed locally. You can now connect **any CLI agent or tool** that supports the OpenAI API (such as `aider`, `cline`, or `interpreter`). Point them to your local address (e.g., `http://127.0.0.1:3040/v1`) and use the API key you just configured.

```bash
curl http://127.0.0.1:3040/v1/chat/completions \
  -H "Authorization: Bearer <your-custom-local-key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "default",
    "messages": [{"role": "user", "content": "Tell me a short joke about Java."}],
    "stream": true
  }'
```

## ⚙️ Configuration & Architecture

- **Auth:** The server requires a local API key. It checks the `INTELLIAIBRIDGE_API_KEY` environment variable first, then the IntelliJ Password Safe.
- **Models:** You can request specific Copilot models. If left blank, it defaults to your Settings preference or the Copilot default.
- **Chat Mode:** Defaults to **Agent** mode when available, falling back to **Ask** mode.
- **Tool Calling:** The bridge translates standard OpenAI `tools` arrays into Copilot prompts and extracts Copilot's `<invoke>` XML responses back into OpenAI `tool_calls`.

For deeper technical details, see the [API reference](docs/API.md), [Architecture](docs/ARCHITECTURE.md), and [Operations](docs/OPERATIONS.md).

## 🔒 Security Guidance

- **Bind to `127.0.0.1` (localhost)** unless you explicitly need LAN access. Do not expose this to the public internet.
- **Use a strong local API key.** Even on localhost, this prevents unauthorized scripts on your machine from using your Copilot quota.
- **Corporate Compliance:** Treat this service as a privileged bridge to your Copilot identity. Ensure your use of agentic workflows complies with your organization's AI and data-handling policies.

## 🛠️ Development

Run tests:
```bash
./gradlew test --no-daemon
```

Build the plugin:
```bash
./gradlew buildPlugin --no-daemon
```
