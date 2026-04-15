# Architecture

## Overview

IntelliAiBridge is an IntelliJ application-level plugin that hosts a local Ktor server and proxies requests into GitHub Copilot agent sessions.

Request flow:

1. Client sends OpenAI-compatible request to local HTTP endpoint.
2. `IntelliAiBridgeGateway` authenticates, validates, rate-limits, and routes the request.
3. Gateway resolves IntelliJ project + model settings.
4. `CopilotBridge` opens Copilot session and sends prompt.
5. Copilot events are converted into OpenAI-style response payloads.
6. Session is cleaned up.

## Main components

### `IntelliAiBridgeGateway`

Application service responsible for:
- Ktor server lifecycle (`start`, `stop`, `close`)
- Endpoint definitions
- Auth, rate limiting, request concurrency limits
- Request logging and JSON parsing diagnostics
- Prompt building and tool-call extraction
- OpenAI-compatible response formatting

### `CopilotBridge`

Adapter around Copilot plugin APIs:
- Model discovery
- Session creation/activation
- Agent-first chat mode selection (fallback Ask)
- Sending prompts and mapping progress/completion/error events
- Extracting visible assistant text from rich message content

### Settings

- `IntelliAiBridgeSettings`: persisted server/runtime config
- `IntelliAiBridgeSecretStore`: API key storage via IntelliJ Password Safe
- `IntelliAiBridgeSettingsConfigurable`: IntelliJ Settings UI

### UI integration

- Tool window: stats + logs + server controls
- Status bar widget: current status + start/stop toggle
- Startup activity: auto-start behavior on project open

## Data model

`Models.kt` defines request/response DTOs matching OpenAI-compatible schema, including:

- Chat and completion requests
- SSE chunk responses
- Tool/function call payloads
- Model listing payloads

## Compatibility strategy

The gateway intentionally accepts superset-style input for better client interoperability:

- Unknown fields are ignored
- Tool schemas tolerate optional compatibility fields (`strict`, etc.)
- Message content supports text or block arrays

## Operational constraints

- Requires active Copilot plugin/session inside IDE.
- API key is mandatory for `/v1/*` endpoints.
- Local service is intended for trusted local automation clients.
