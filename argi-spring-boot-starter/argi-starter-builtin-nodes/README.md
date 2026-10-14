# Built-in Nodes

## Migrated Graph Nodes

The Core `KnowledgeRetrievalNode`, `HttpNode`, and `DocumentExtractorNode`
classes have been removed. Applications must use the Extensions-owned graph
node artifacts and packages.

For RAG retrieval nodes, declare:

```xml
<dependency>
  <groupId>io.github.agentic-ai</groupId>
  <artifactId>argi-graph-node-rag</artifactId>
</dependency>
```

Then import the Extensions package:

```java
import io.github.agentic.ai.graph.node.rag.KnowledgeRetrievalNode;
```

For HTTP and document extraction nodes, declare:

```xml
<dependency>
  <groupId>io.github.agentic-ai</groupId>
  <artifactId>argi-graph-node-network</artifactId>
</dependency>
```

Then import the Extensions packages:

```java
import io.github.agentic.ai.graph.node.network.DocumentExtractorNode;
import io.github.agentic.ai.graph.node.network.HttpNode;
```

The Extensions artifacts are plain libraries. They do not add Spring Boot
auto-configuration, component scanning, or automatic graph node registration.

## Secure Defaults

The code and document nodes use restrictive defaults. Applications upgrading from earlier releases
must opt in to broader access explicitly.

### Code Execution

`DockerCodeExecutor` is provided by the Extensions-owned
`argi-code-executor-docker` artifact in package
`io.github.agentic.ai.graph.node.code.docker`. It disables networking,
uses a read-only root filesystem, drops capabilities, and applies CPU, memory,
swap, PID, and output limits.

```java
CodeExecutionConfig config = new CodeExecutionConfig()
    .setDisableNetwork(false) // Only when executed code requires outbound access.
    .setMemoryLimitBytes(512L * 1024L * 1024L)
    .setMemorySwapBytes(512L * 1024L * 1024L)
    .setPidsLimit(256)
    .setMaxOutputBytes(2L * 1024L * 1024L)
    .setTimeout(120);
```

Both Docker and local executors create an isolated child directory under `workDir`. Multiple code
blocks in one `executeCodeBlocks` invocation share that directory, and the directory is deleted when
the invocation finishes. Do not use `workDir` as cross-invocation storage. The local executor runs code
on the host and is only suitable for trusted input.

### Document Extraction

Local documents are restricted to the configured root. Remote documents allow only HTTP and HTTPS,
block private, loopback, link-local, and metadata addresses by default, cap response bytes, and enforce
connect, read, and total elapsed timeouts.

```java
DocumentExtractorNode node = DocumentExtractorNode.builder()
    .localRoot(Path.of("/srv/application-documents"))
    .maxBytes(10L * 1024L * 1024L)
    .totalTimeout(Duration.ofSeconds(60))
    .build();
```

For a trusted internal document service, private-network access can be enabled explicitly:

```java
DocumentExtractorNode node = DocumentExtractorNode.builder()
    .allowPrivateNetworkAccess(true)
    .build();
```

`HttpNode` applies URI and resolved-address preflight checks even with a custom `WebClient`. A custom
client connector must enforce the same policy during connect-time DNS resolution to prevent rebinding.

## Framework-Neutral Chat Nodes

`LlmNode`, `ToolNode`, `QuestionClassifierNode`, `ParameterParsingNode`, and `AgentNode`
use `io.github.agentic.ai.api.chat.ChatClient` and the tool contracts in `argi-api`.
Construct an adapter from a native client/model, or inject the neutral bean supplied by
one of the four `argi-starter-adapter-*` starters. Adapter selection uses
`argi.chat.adapter` when several adapter starters are present. These built-in nodes are
independent of the separate `ReactAgent` implementation in `argi-agent-framework`.

This is a direct API replacement. Native `ChatModel`/`ChatClient`, `ToolCallback`, and
Spring AI options are not accepted by the migrated node builders. There are no
deprecated overloads. For example, a Spring AI model can be adapted explicitly:

```java
var chatClient = new SpringAiChatClientAdapter(springChatModel);
var registry = new ToolRegistry(tools); // Collection of ARGI Tool bindings.

AgentNode node = AgentNode.builder()
    .chatClient(chatClient)
    .toolRegistry(registry)
    .userPrompt("Summarize {document}")
    .maxModelTurns(10)
    .maxRetries(2)
    .outputKey("summary")
    .build();
```

The adapter performs one model turn and returns tool calls without executing them.
`ToolRegistry` binds tool declarations to asynchronous ARGI `Tool` executors.

## AgentNode

`AgentNode` owns a bounded asynchronous model/tool loop inside one graph node. It does
not invoke a native framework agent or delegate its loop to a provider's tool advisor.
Each invocation has its own history, usage counters, and active model/tool stages.

Input history comes from `messages` and must contain ARGI `ChatMessage` values. Optional
`systemPrompt` and `userPrompt` accept `{variable}` placeholders resolved from state.
Request order is the configured system prompt, history, then configured user prompt.
History-only input is supported. The generated user prompt, assistant turns, and tool
results are appended to the output `messages` delta; the configured system prompt is not.

On success the node returns `outputKey` (default `agent_output`), `messages`, and the last
`llm_response`. Token usage is aggregated across logical model turns and exposed on the
`NodeOutput`, not persisted as graph state. Unknown counts remain unknown, and counts
are nullable 64-bit values. A model response must contain exactly one assistant message;
duplicate call IDs, inconsistent calls, and unknown tool names fail before tool execution.

### Retries And Cancellation

`maxModelTurns` defaults to `10` and bounds logical model turns. `maxRetries` defaults to
`0`; `n` allows at most `n + 1` attempts per turn using the same immutable request. Only
the model request is retried. Already executed tools are not rerun by model retries.
Cancellation, invalid arguments, and unsupported-operation errors are not retried.
Cancelling the returned future cancels the active model/tool stage and stops further work.

The old `maxIterations` and `throwOnModelError` methods are removed. Model/tool errors
propagate to the graph instead of producing a successful empty answer. No state delta is
submitted until the entire loop succeeds.

### Strategy

`Strategy.REACT` is the default. It executes calls sequentially, returns tool results to
the model, and continues until an answer or until every requested tool has
`returnDirect() == true`. Mixed direct/non-direct calls continue to a follow-up model turn.

`Strategy.TOOL_CALLING` executes the first turn's tools and returns their concatenated
text without another model turn. If the first turn has no calls, its answer is returned.
An explicit `ToolResult.error(...)` is model-visible feedback; an exceptional tool stage
fails the node and is not retried.

### Checkpoint Boundary

Graph checkpoints occur between nodes, not between turns inside `AgentNode`. Tool side
effects cannot be rolled back. Replaying an interrupted or failed node from its preceding
checkpoint can repeat those effects; this is not exactly-once execution. Use idempotent
tools, or build the loop with separate `LlmNode` and `ToolNode` nodes when per-turn
checkpoint/resume or human intervention is required.

Existing Spring AI message checkpoints are not automatically converted into neutral
`ChatMessage` values. Start a new graph thread or explicitly transform historical messages
before using a migrated node. The file checkpoint container is unchanged, but neutral
records have their own serialization tags and are not readable by older ARGI versions.

## Adapter Verification

`AdapterGraphIntegrationTest` exercises all four native SDK boundaries with deterministic
in-process models. It checks call/stream tool loops, checkpoint round trips, usage,
failures, and cancellation without making network requests.

`RealModelAdapterGraphIntegrationTest` uses `@EnabledForRealModel` and the `real-model`
JUnit tag. It runs only when **both** `ARGI_MODEL` and `ARGI_API_KEY` are nonblank.
Without either variable it is skipped; the deterministic tests still run. Credentials
are read from the test process environment, never from source files or JVM properties.

Set `ARGI_BASE_URL` for an OpenAI-compatible endpoint; it defaults to
`https://api.openai.com/v1`. The model name is forwarded unchanged. Keep SDK HTTP
request/response and header logging disabled. Do not pass credentials using `-D` flags.
With credentials already supplied by the shell or CI secret store, run:

```shell
./mvnw -B -pl :argi-starter-builtin-nodes -am \
  -Dtest=AdapterGraphIntegrationTest,RealModelAdapterGraphIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The real-model matrix covers call and streaming for every adapter with a bounded
model/tool/model graph and a fresh file checkpoint reader. Failed real-model checks
fail the build rather than being converted into skipped tests. For an explicitly
offline run, exclude the tag with `-DexcludedGroups=real-model`, even if credentials
are present in the environment.
