# AiBridge: Use your IDE AI everywhere

**AiBridge** turns your JetBrains IDE into a local AI gateway. It exposes the language models available via GitHub Copilot (and other providers) as a standard **OpenAI-compatible REST API**.

### Why AiBridge?

Many powerful CLI tools, agents (like Aider or OpenDevin), and scripts are designed to work with the OpenAI API. If you already have access to high-quality models through your IDE, AiBridge lets you use them as the backend for these external tools without paying for separate API credits.

### Features

- 🚀 **Standard API:** Implements `/v1/chat/completions` and `/v1/models`.
- 🔄 **Streaming Support:** Full support for Server-Sent Events (SSE).
- 🛠️ **Tool Window:** Monitor traffic, see active models, and control the server.
- 🔒 **Local Security:** Authenticate local requests with a custom bearer token.
- ⚙️ **Automatic Discovery:** Automatically detects models available through your active IDE projects.

### Quick Start

1. Install the plugin.
2. Open `Settings | Tools | AiBridge` and set your local API key.
3. Start the server via the AiBridge tool window or the status bar icon.
4. Point your local tools to `http://127.0.0.1:3040/v1`.

### Compatibility

Works with all JetBrains IDEs version 2024.3 and later. Requires the GitHub Copilot plugin to be installed and authenticated for Copilot-based models.
