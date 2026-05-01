# Operations and Troubleshooting

## Configure API key

Use either:

1. `AIBRIDGE_API_KEY` environment variable, or
2. AiBridge Settings API key field (stored in Password Safe)

Priority: environment variable overrides Password Safe.

## Start/stop server

- Tool window buttons: `Start`, `Stop`, `Restart`
- Status bar icon click toggles state
- Auto-start on project open if enabled

## Verify service

```bash
curl http://127.0.0.1:3040/health
```

Then test auth:

```bash
curl http://127.0.0.1:3040/v1/models \
  -H "Authorization: Bearer <aibridge-api-key>"
```

## Common issues

### `API key missing`

Log message:

- `AiBridge Server not started: API key missing...`

Fix:
- Set `AIBRIDGE_API_KEY`, or
- Save key in AiBridge settings (Password Safe)

### `Invalid API Key`

Cause:
- Bearer token does not match effective key.

Fix:
- Confirm token in client request.
- If env var is set, it overrides Password Safe key.

### `Empty request body` / `Invalid JSON request body`

Cause:
- Client sends empty payload or unsupported JSON shape.

Fix:
- Ensure valid JSON body with `messages` array for chat endpoint.
- Inspect request logs with request ID + body preview.

### `No open project found`

Cause:
- No open IDE project available for Copilot session context.

Fix:
- Open at least one project in IDE and retry.

### Stream ends with empty `delta`

Cause:
- Expected OpenAI SSE terminal chunk behavior.

Fix:
- Treat chunk with `finish_reason` as completion marker.

### Copilot unavailable / session errors

Cause:
- Copilot plugin/session initialization issues.

Fix:
- Verify Copilot plugin enabled and authenticated.
- Retry after IDE restart.
- Check IDE logs for Copilot exceptions.

## Hardening recommendations

- Keep host bound to `127.0.0.1` unless needed.
- Restrict CORS to explicit trusted origins.
- Set conservative concurrency + rate limits.
- Enable logs in staging; reduce verbosity in stable environments.

## Developer commands

```bash
./gradlew test --no-daemon
./gradlew runIde
./gradlew buildPlugin --no-daemon
```
