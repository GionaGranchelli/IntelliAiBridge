# AiBridge

AiBridge is a plugin that exposes IDE-hosted language models (like those from GitHub Copilot) as a local, OpenAI-compatible REST API. This allows you to use your IDE's AI capabilities from external tools, CLI agents, and scripts.

## Key Features

- **OpenAI Compatibility:** Drop-in replacement for any tool expecting the OpenAI API (Chat Completions, Models list).
- **IDE Integration:** Leverages the models already authenticated and available in your IDE.
- **Security:** Requires a local API key for all requests; supports storage in the IDE Password Safe.
- **Monitoring:** Built-in Tool Window showing real-time stats and request logs.
- **Flexibility:** Supports both streaming (SSE) and non-streaming responses.

## Getting Started

1. **Install:** Install the "AiBridge" plugin from the JetBrains Marketplace.
2. **Configure:** 
   - Go to `Settings -> Tools -> AiBridge`.
   - Set an **AiBridge API Key** (this is for authenticating your local clients).
   - Enable "Automatically start server" if desired.
3. **Run:** The server defaults to `http://127.0.0.1:3040`.
4. **Verify:** Check the "AiBridge" tool window on the right side of your IDE.

## Usage Example

```bash
curl http://127.0.0.1:3040/v1/chat/completions \
  -H "Authorization: Bearer <your-aibridge-key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) - Internal design and request flow.
- [API Reference](docs/API.md) - Detailed endpoint definitions.
- [Operations](docs/OPERATIONS.md) - Troubleshooting and configuration.

## Development

Requires IntelliJ IDEA with the [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html).

```bash
./gradlew runIde      # Run a development instance of the IDE
./gradlew test        # Run the test suite
./gradlew buildPlugin # Build the distribution ZIP
```

## License

This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details.
