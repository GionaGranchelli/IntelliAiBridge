# AiBridge Configuration Guide

Welcome to AiBridge! This guide helps you configure the gateway for your local workflow.

## Authentication

AiBridge requires a **Bearer Token** for all API requests to ensure only your authorized local tools can access the gateway.

1. **Environment Variable:** Set `AIBRIDGE_API_KEY` in your system environment. This is the recommended method for automation.
2. **Password Safe:** Enter a key in the "AiBridge API Key" field below. It will be securely stored in the IDE's internal Password Safe.

*Note: The environment variable always overrides the stored key.*

## Server Settings

- **Host/Port:** Defaults to `127.0.0.1:3040`. It is recommended to keep the host as `127.0.0.1` for security.
- **Auto-start:** If enabled, the server will start as soon as you open a project in the IDE.

## Connecting Clients

Point your OpenAI-compatible clients to the base URL:
`http://127.0.0.1:3040/v1`

### Example (Python OpenAI Client):

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:3040/v1",
    api_key="your-aibridge-key"
)

response = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "Hello!"}]
)
```

## Troubleshooting

- **Status "Stopped":** Ensure you have an API key set and that the port is not in use by another application.
- **No Models Found:** Ensure you have at least one project open and the GitHub Copilot plugin is signed in.
- **Unauthorized:** Check that the `Authorization: Bearer <key>` header in your client matches the key set here.
