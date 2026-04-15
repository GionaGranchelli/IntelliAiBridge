# IntelliAiBridge IntelliJ Plugin

IntelliAiBridge exposes GitHub Copilot inside IntelliJ as a local OpenAI-compatible HTTP API.

It is designed for environments where direct CLI usage is restricted, but IDE-based Copilot access is allowed.

## What you get

- Local API server (`http://127.0.0.1:3040` by default)
- OpenAI-compatible endpoints:
  - `GET /health`
  - `GET /v1/models`
  - `GET /v1/models/{id}`
  - `POST /v1/chat/completions`
  - `POST /v1/completions` (legacy compatibility)
- API key auth (`Authorization: Bearer <key>`)
- Streaming and non-streaming chat responses
- Tool call compatibility (`tools` + XML tool-call extraction)
- Model discovery from Copilot + fallback model list
- IntelliJ settings UI + Password Safe secret storage

## Requirements

- IntelliJ Platform IDE compatible with this plugin build
- GitHub Copilot plugin installed and authenticated
- Active Copilot entitlement for the user
- Java/Gradle environment for local development

## Quick start

1. Build/run plugin sandbox:

```bash
./gradlew runIde
```

2. In IntelliJ, open `Settings > Tools > IntelliAiBridge`.

3. Configure:
- `Host` and `Port`
- `API Key` (stored in IntelliJ Password Safe)
- Optional defaults (model, system prompt, limits)

4. Start the server:
- From IntelliAiBridge tool window (`Start`), or
- Status bar widget toggle, or
- Auto-start on project open

5. Call the API:

```bash
curl http://127.0.0.1:3040/v1/chat/completions \
  -H "Authorization: Bearer <your-intelliaibridge-api-key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "default",
    "messages": [{"role": "user", "content": "Tell me a short joke about Java."}],
    "stream": true
  }'
```

## Authentication and API keys

IntelliAiBridge accepts one effective API key, resolved in this order:

1. `INTELLIAIBRIDGE_API_KEY` environment variable
2. IntelliAiBridge API key stored in IntelliJ Password Safe

If both exist, environment variable wins.

## Model behavior

- If request `model` is provided, IntelliAiBridge attempts to use it.
- If request `model` is absent/blank, IntelliAiBridge uses `Default Model` from settings.
- If both are absent, it uses Copilot default behavior.
- `/v1/models` returns discovered Copilot models, with fallback entries when discovery is unavailable.

## Chat/Agent behavior

Session mode defaults to **Agent** when available, and falls back to **Ask** mode when Agent mode is unavailable.

## Endpoint notes

### `POST /v1/chat/completions`

Supports:
- `messages`
- `model`
- `stream`
- `tools`
- `tool_choice`
- `max_tokens`
- `temperature`

Unknown JSON fields are ignored to improve client compatibility.

Streaming follows SSE format (`data: ...`) and ends with `data: [DONE]`.
A final chunk with empty `delta` and `finish_reason` is expected behavior.

### `POST /v1/completions`

Legacy shim that converts prompt-style payloads into chat-completions flow.

### Tool call compatibility

IntelliAiBridge accepts OpenAI-style `tools` payloads and also parses Copilot responses containing:

- `<function_calls>`
- `<invoke name="...">`
- `<parameter name="...">...</parameter>`

Parsed calls are returned as OpenAI-style `tool_calls`.

## Configuration reference

See [API reference](docs/API.md), [architecture](docs/ARCHITECTURE.md), and [operations/troubleshooting](docs/OPERATIONS.md).

## Development

Run tests:

```bash
./gradlew test --no-daemon
```

Build plugin:

```bash
./gradlew buildPlugin --no-daemon
```

## Security guidance

- Bind host to loopback unless you explicitly need LAN access.
- Use a strong API key.
- Restrict CORS origins to trusted local clients only.
- Treat this service as a privileged bridge to your Copilot identity.

## Known limits

- Behavior depends on Copilot plugin availability and session health.
- Model discovery may vary between IDE/plugin versions.
- Not all OpenAI features are implemented (focus is local automation compatibility).

## License

No project license is currently declared in this repository.
