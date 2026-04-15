# API Reference

Base URL: `http://<host>:<port>` (default `http://127.0.0.1:3040`)

Authentication header required on all `/v1/*` endpoints:

```http
Authorization: Bearer <intelliaibridge-api-key>
```

## `GET /health`

Health probe endpoint (no auth required).

### Response

```json
{
  "status": "ok",
  "platform": "intellij",
  "copilot": "enabled"
}
```

## `GET /v1/models`

Returns model list in OpenAI-compatible format.

### Response

```json
{
  "object": "list",
  "data": [
    {
      "id": "gpt-4o",
      "object": "model",
      "created": 1710000000,
      "owned_by": "github-copilot"
    }
  ]
}
```

Notes:
- Uses discovered Copilot models when available.
- Returns fallback list when discovery fails or no project is open.

## `GET /v1/models/{id}`

Returns a single model entry or `404`.

## `POST /v1/chat/completions`

OpenAI-compatible chat completions endpoint.

### Request (typical)

```json
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "You are concise."},
    {"role": "user", "content": "Tell me a short joke about Java."}
  ],
  "stream": true,
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "read_file",
        "description": "Read a local file",
        "parameters": {"type": "object"},
        "strict": true
      }
    }
  ]
}
```

### Supported request fields

- `model: string?`
- `messages: ChatMessage[]` (required)
- `stream: boolean` (default `false`)
- `tools: ChatTool[]?`
- `tool_choice: any?`
- `max_tokens: int?`
- `temperature: double?`

Compatibility notes:
- Unknown request fields are ignored.
- `tools[].function.strict` is accepted.
- `messages[].content` can be string or array-like OpenAI content blocks; text is flattened.

### Streaming response

SSE chunks via `text/event-stream`:

```text
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[...]}

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

A terminal chunk with empty `delta` and non-null `finish_reason` is expected OpenAI-style behavior.

### Non-streaming response

OpenAI-style `chat.completion` JSON with one `choice`.

## `POST /v1/completions`

Legacy endpoint.

Request:

```json
{
  "model": "gpt-4o",
  "prompt": "Write one sentence.",
  "stream": false
}
```

Behavior:
- Converts prompt payload into chat format and routes to the same pipeline as `/v1/chat/completions`.

## Error responses

Typical error JSON:

```json
{"error": "Invalid API Key"}
```

Common status codes:
- `400`: invalid/empty request body
- `401`: invalid bearer key
- `404`: model not found
- `429`: rate limit exceeded
- `503`: no project / server capacity / Copilot unavailable
- `504`: request timeout
- `500`: internal error
