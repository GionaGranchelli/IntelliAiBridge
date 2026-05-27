# Graph Report - /home/gionag/Development/intelliaibridge  (2026-05-24)

## Corpus Check
- Corpus is ~13,343 words - fits in a single context window. You may not need a graph.

## Summary
- 213 nodes · 322 edges · 22 communities (9 shown, 13 thin omitted)
- Extraction: 82% EXTRACTED · 18% INFERRED · 0% AMBIGUOUS · INFERRED: 58 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_AiBridgeGateway  .startInternal()|AiBridgeGateway / .startInternal()]]
- [[_COMMUNITY_Models.kt  .setUp()|Models.kt / .setUp()]]
- [[_COMMUNITY_CopilotBridge  .prepareSession()|CopilotBridge / .prepareSession()]]
- [[_COMMUNITY_AiBridgeGatewayIntegrationTest  .`concurrency limit returns|AiBridgeGatewayIntegrationTest / .`concurrency limit returns]]
- [[_COMMUNITY_ChatMessage  .handle()|ChatMessage / .handle()]]
- [[_COMMUNITY_AiBridgeSettingsConfigurable  .refreshModelOptionsAsync()|AiBridgeSettingsConfigurable / .refreshModelOptionsAsync()]]
- [[_COMMUNITY_AiBridgeStatusBarWidget  AiBridgeStatusBarWidgetFactory|AiBridgeStatusBarWidget / AiBridgeStatusBarWidgetFactory]]
- [[_COMMUNITY_AiProviderBridge.kt  AiProviderBridge|AiProviderBridge.kt / AiProviderBridge]]
- [[_COMMUNITY_AiBridgeGatewayParseXmlToolCallsTest  .`parseXmlToolCalls e|AiBridgeGatewayParseXmlToolCallsTest / .`parseXmlToolCalls e]]
- [[_COMMUNITY_GatewayXmlToolCallParser  .parse()|GatewayXmlToolCallParser / .parse()]]
- [[_COMMUNITY_AiBridgeSettings  AiBridgeSettings.kt|AiBridgeSettings / AiBridgeSettings.kt]]
- [[_COMMUNITY_GeminiBridge  .prepareSession()|GeminiBridge / .prepareSession()]]
- [[_COMMUNITY_StartServerAction  StopServerAction|StartServerAction / StopServerAction]]
- [[_COMMUNITY_ContentDeserializerTest  .`deserializes array content with|ContentDeserializerTest / .`deserializes array content with ]]
- [[_COMMUNITY_GatewayProjectResolver  .resolveProject()|GatewayProjectResolver / .resolveProject()]]
- [[_COMMUNITY_AiBridgeSecretStore  .getApiKey()|AiBridgeSecretStore / .getApiKey()]]
- [[_COMMUNITY_AiBridgeProjectActivity  .execute()|AiBridgeProjectActivity / .execute()]]
- [[_COMMUNITY_GatewayPromptBuilder  .build()|GatewayPromptBuilder / .build()]]
- [[_COMMUNITY_OpenAiCompatibilityRequestDeserializationTest  .`chat compl|OpenAiCompatibilityRequestDeserializationTest / .`chat compl]]
- [[_COMMUNITY_AiBridgeToolWindowFactory  AiBridgeToolWindowFactory.kt|AiBridgeToolWindowFactory / AiBridgeToolWindowFactory.kt]]

## God Nodes (most connected - your core abstractions)
1. `AiBridgeGateway` - 32 edges
2. `AiBridgeGatewayIntegrationTest` - 15 edges
3. `CopilotBridge` - 15 edges
4. `AiBridgeSettingsConfigurable` - 12 edges
5. `AiBridgeGatewayParseXmlToolCallsTest` - 8 edges
6. `ChatMessage` - 8 edges
7. `AiBridgeStatusBarWidgetFactory` - 7 edges
8. `AiBridgeStatusBarWidget` - 7 edges
9. `GeminiBridge` - 6 edges
10. `AiProviderBridge` - 6 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Communities (22 total, 13 thin omitted)

### Community 0 - "AiBridgeGateway / .startInternal()"
Cohesion: 0.10
Nodes (5): AiBridgeGateway, LogListener, ChatCompletionRequest, OpenAiError, OpenAiErrorResponse

### Community 1 - "Models.kt / .setUp()"
Cohesion: 0.15
Nodes (12): GatewayModelCatalog, ChatChunkChoice, ChatChunkDelta, ChatChunkToolCall, ChatCompletionChunkResponse, ChatFunction, ChatTool, CompletionsRequest (+4 more)

### Community 2 - "CopilotBridge / .prepareSession()"
Cohesion: 0.18
Nodes (3): AvailableModel, CopilotBridge, SessionHandle

### Community 4 - "ChatMessage / .handle()"
Cohesion: 0.21
Nodes (4): AiBridgeGatewayBuildPromptTest, GatewayChatCompletions, ChatChoice, ChatMessage

### Community 7 - "AiProviderBridge.kt / AiProviderBridge"
Cohesion: 0.15
Nodes (7): AiEvent, AiProviderBridge, AiSessionHandle, Complete, Error, Other, Progress

### Community 9 - "GatewayXmlToolCallParser / .parse()"
Cohesion: 0.36
Nodes (4): GatewayXmlToolCallParser, ParsedXmlTools, FunctionCall, ToolCall

### Community 10 - "AiBridgeSettings / AiBridgeSettings.kt"
Cohesion: 0.25
Nodes (3): AiBridgeSettings, AiProvider, ApiKeySource

## Knowledge Gaps
- **12 isolated node(s):** `AiSessionHandle`, `AiEvent`, `Progress`, `Complete`, `Error` (+7 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **13 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `AiBridgeGateway` connect `AiBridgeGateway / .startInternal()` to `AiBridgeGatewayParseXmlToolCallsTest / .`parseXmlToolCalls e`, `Models.kt / .setUp()`, `ChatMessage / .handle()`?**
  _High betweenness centrality (0.260) - this node is a cross-community bridge._
- **Why does `CopilotBridge` connect `CopilotBridge / .prepareSession()` to `AiBridgeGateway / .startInternal()`?**
  _High betweenness centrality (0.210) - this node is a cross-community bridge._
- **Why does `AvailableModel` connect `CopilotBridge / .prepareSession()` to `AiProviderBridge.kt / AiProviderBridge`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `AiBridgeGateway` (e.g. with `.invokeParseXmlToolCalls()` and `.invokeBuildPrompt()`) actually correct?**
  _`AiBridgeGateway` has 3 INFERRED edges - model-reasoned connections that need verification._
- **What connects `AiSessionHandle`, `AiEvent`, `Progress` to the rest of the system?**
  _12 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `AiBridgeGateway / .startInternal()` be split into smaller, more focused modules?**
  _Cohesion score 0.10338680926916222 - nodes in this community are weakly interconnected._