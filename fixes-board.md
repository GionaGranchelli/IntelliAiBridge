# intelliaibridge Fixes Board

Round 2 Brutal 3-AI Review — consolidated findings.

| # | Status | File | Severity | Finding | 3-AI Consensus |
|---|--------|------|----------|---------|----------------|
| 1 | ⬜ | CopilotBridge.kt : 69-72 | 🔴 CRITICAL | Session leak: `prepareSession` creates session, then `getCurrentSessionController()` null → throws, session never cleaned up | 3/3 |
| 2 | ⬜ | GatewayChatCompletions.kt : 64, AiBridgeGateway.kt : 363 | 🔴 CRITICAL | `CancellationException` swallowed by `catch (e: Exception)` → `withTimeout` dead code, timeouts return 503 not 504 | 2/3 |
| 3 | ⬜ | CopilotBridge.kt : 219-223 | 🔴 HIGH | `Thread.sleep(100)` blocks Netty event-loop / pooled threads for up to 600ms during model listing | 3/3 |
| 4 | ⬜ | GatewayChatCompletions.kt : 173-177, 186 | 🔴 HIGH | Channel closed with exception in streaming path → broken SSE (no `[DONE]`, mixed error JSON with SSE) | 2/3 |
| 5 | ⬜ | AiBridgeGateway.kt : 258 | 🔴 HIGH | Body size limit bypassed via chunked encoding (no Content-Length header) | 2/3 |
| 6 | ⬜ | GatewayChatCompletions.kt : 170 | 🟡 MEDIUM | `channel.trySend()` return value ignored → silent progress token loss when channel full | 1/3 |
| 7 | ⬜ | GatewayXmlToolCallParser.kt : 28-32 | 🔴 CRITICAL | Malformed XML silently stripped from response → tool calls vanish from both text AND array | 1/3 |
| 8 | ⬜ | GatewayPromptBuilder.kt : 16-28 | 🟡 MEDIUM | `tool` role messages silently discarded → Copilot never sees tool results | 1/3 |
| 9 | ⬜ | CopilotBridge.kt : 107-121 | 🟡 MEDIUM | EDT dispatcher assumption breaks in headless/CI, `sendMessage` exception leaves channel hanging | 1/3 |
| 10 | ⬜ | AiBridgeGateway.kt : 92 | 🟡 MEDIUM | `isRunning()` missing `@Volatile` → stale reads on ARM/under JIT | 1/3 |
| 11 | ⬜ | CopilotBridge.kt : 139,161 | 🔴 HIGH | `Unchecked cast` to `Iterable<MessageContent>` → ClassCastException if Copilot changes internals | 1/3 |
| 12 | ⬜ | CopilotBridge.kt : 89-91 | 🔴 HIGH | Destroys user's Copilot model selection via `setSelectedModel` on every API request | 1/3 |
| 13 | ⬜ | AiBridgeToolWindowFactory.kt : 102-113 | 🔴 CRITICAL | EDT flood: `invokeLater` called on every log line → thousands of EDT events under streaming load | 1/3 |
| 14 | ⬜ | AiBridgeGateway.kt : 454 | 🟡 MEDIUM | Logs up to 500 chars of request body → leaks API keys, confidential code | 1/3 |
| 15 | ⬜ | CopilotBridge.kt : 215-278 | 🟡 MEDIUM | Overly broad `catch (_: Throwable)` and `catch (_: Exception)` → swallows OOM, CancellationException | 1/3 |
| 16 | ⬜ | AiBridgeGateway.kt : 192 | 🟢 LOW | API key length leaked in log: `Checking API key... (length=XXXX)` | 1/3 |
| 17 | ⬜ | AiBridgeGateway.kt : 243 | 🟢 LOW | `toByteArray()` uses platform default charset → theoretical auth break on non-UTF8 systems | 1/3 |
| 18 | ⬜ | AiBridgeToolWindowFactory.kt : 98-116 | 🟢 LOW | Log listener lifecycle gap: duplicate listeners accumulate on tool window reopen | 1/3 |
| 19 | ⬜ | AiBridgeSettingsConfigurable.kt : 34 | 🟢 LOW | Creates separate `CopilotBridge` instance instead of reusing gateway's | 1/3 |
| 20 | ⬜ | AiBridgeGateway.kt : 392 | 🟡 MEDIUM | `rateBucket.peek()!!` NPE risk if queue accessed outside lock | 1/3 |
| 21 | ⬜ | AiBridgeGateway.kt : 371-385 | 🟢 LOW | `CompletionsRequest.toChatCompletionRequest()` silently drops non-String/non-List prompts | 1/3 |
| 22 | ⬜ | AiBridgeGateway.kt : 47 | 🟢 LOW | `CoroutineScope(Dispatchers.IO)` with default `Job()` — dead after `close()`, can't restart | 1/3 |
| 23 | ⬜ | AiBridgeGateway.kt : 354 | 🟢 LOW | `requestTimeoutSeconds` unvalidated — hand-edited config could set 0 or negative | 1/3 |

## Fix order

1. Session leak (#1) — pipelines
2. CancellationException (#2) — mechanical
3. Thread.sleep (#3) — mechanical
4. SSE error (#4) — pipelines
5. Body size bypass (#5) — mechanical
6. trySend ignored (#6) — mechanical
7. XML tool call loss (#7) — mechanical
8. tool role messages (#8) — mechanical
9. EDT dispatcher assumption (#9) — mechanical
10. isRunning @Volatile (#10) — mechanical
11. Unchecked cast (#11) — mechanical
12. Model selection destruction (#12) — mechanical
13. EDT flood (#13) — pipelines
14. Body logging (#14) — mechanical
15. Broad catch (#15) — mechanical
16-23. LOW findings (#16-23) — mechanical batch
