# ARGI API and Adapters

This directory groups the framework-neutral ChatClient contract and its four
framework adapters. It is a source directory group, not a Maven module or a
runtime dependency. The root `pom.xml` aggregates each child module directly.

| Directory | Maven Artifact | Responsibility |
| --- | --- | --- |
| `argi-api-base` | `argi-api` | JDK-only chat, message, tool, event, and capability contracts |
| `argi-adapter-spring-ai` | `argi-adapter-spring-ai` | Adapt Spring AI ChatClient and ChatModel |
| `argi-adapter-langchain4j` | `argi-adapter-langchain4j` | Adapt LangChain4j chat and streaming models |
| `argi-adapter-google-adk` | `argi-adapter-google-adk` | Adapt Google ADK BaseLlm |
| `argi-adapter-agentscope` | `argi-adapter-agentscope` | Adapt AgentScope Model |

All artifacts use the group ID `io.github.agentic-ai-java`. The `argi-api-base`
directory intentionally publishes as **`argi-api`**: applications should not need
a second public API dependency or a different package name after this source
layout change. Public contracts remain in `io.github.agentic.ai.api.*`.

The base contract has no external production dependencies, including dependencies
inherited from parent POMs. Its published JAR requires only `java.base`.
Each adapter depends on `argi-api` and its own framework, not on other
adapters. Spring Boot auto-configuration lives separately in
[`argi-spring-boot-starter`](../argi-spring-boot-starter).

From the repository root:

```shell
./mvnw -B -pl :argi-api -am test
./mvnw -B -pl :argi-adapter-spring-ai,:argi-adapter-langchain4j,:argi-adapter-google-adk,:argi-adapter-agentscope -am test
```

For path-based module selection, use the complete child path, for example
`-pl argi-api/argi-api-base`. The previous `-pl argi-api` path now names a directory
group and must be replaced by `-pl :argi-api` or the child path. Maven coordinates,
Java packages, and adapter behavior are unchanged by the directory move.

## Application Setup

Import `io.github.agentic-ai-java:argi-bom` at the ARGI version used by the
application. For a standalone ReAct application, add `argi-agent-framework` and
one of the four adapter artifacts. A graph-only application uses `argi-graph-core`
and, for built-in model nodes, `argi-starter-builtin-nodes` instead. The directory
name `argi-api-base` is not a dependency coordinate.

The application creates the native model or client with its provider's SDK, then
wraps it explicitly. Adapter constructors accept these native types:

| Adapter | Native Input |
| --- | --- |
| `SpringAiChatClientAdapter` | Spring AI `ChatModel` or `ChatClient` |
| `LangChain4jChatClientAdapter` | LangChain4j `ChatModel`, optionally with `StreamingChatModel` |
| `GoogleAdkChatClientAdapter` | Google ADK `BaseLlm` |
| `AgentScopeChatClientAdapter` | AgentScope `Model` |

After wrapping, the agent code is the same for all four frameworks:

```java
import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.graph.agent.ReactAgent;

// client is a framework adapter, or an application-provided neutral implementation.
ChatMessage answer(ChatClient client, ToolRegistry tools, String question) throws Exception {
    ReactAgent agent = ReactAgent.builder()
        .name("assistant")
        .chatClient(client)
        .toolRegistry(tools)
        .build();
    return agent.call(question);
}

// A single model turn does not run the agent's tool loop.
String oneTurn(ChatClient client, String question) {
    return client.callBlocking(ChatRequest.of(java.util.List.of(ChatMessage.user(question)))).text();
}
```

For Spring Boot, replace the standalone adapter dependency with its matching
`argi-starter-adapter-*` artifact and provide a native bean. Inject the resulting
ARGI `ChatClient` into the agent factory. See the starter documentation for
candidate selection and multi-adapter configuration.

## Real Model Verification

Normal offline builds do not require credentials. Real-model integration tests
use `@EnabledForRealModel` and run only when both `ARGI_MODEL` and `ARGI_API_KEY`
contain a non-whitespace value. Set `ARGI_BASE_URL` for an OpenAI-compatible
endpoint. The matrix uses a fixed 60-second model timeout and disables provider
retries where supported. Credentials are environment variables, not Java source
constants or Maven command-line secrets.

```shell
# Inject ARGI_MODEL, ARGI_API_KEY, and optionally ARGI_BASE_URL into this process first.
./mvnw -B -pl :argi-agent-framework,:argi-starter-builtin-nodes -am \
  -Dtest=RealModelAdapterAgentIntegrationTest,RealModelAdapterGraphIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The matrix covers four adapters, Graph and ReAct tool loops, and synchronous and
streaming calls. ADK uses its `ApigeeLlm` OpenAI-compatible transport in this
matrix; it does not validate native Gemini/Vertex AI, native ADK Claude, or the
upstream identity of a model served by a gateway. Studio's optional Jina MCP
example uses `ARGI_JINA_API_KEY` separately from the model credential.

## Runtime Context and Invocation Attributes

Agent state, message history, and `RunnableConfig` metadata are available through
`ModelRequest.getContext()` to interceptors. They are not forwarded to a model
framework. `ModelRequest.getAttributes()` is a separate immutable map, empty by
default. An interceptor can opt into native invocation attributes using
`ModelRequest.builder(request).attributes(attributes).build()`.

Adapters reject attributes they cannot represent. Do not copy the runtime context
into attributes: this can expose internal state and break frameworks that do not
support native request attributes. Request copies, including fallback attempts,
preserve explicitly supplied attributes.

## Runtime Tool Selection

`ModelRequest` carries static and dynamic `ToolRegistry` bindings. An interceptor
may replace either registry for a model turn; the selected definitions and their
executors stay together through the tool node, checkpoint interruption, and
resume. Copying a request preserves that selection. An explicitly empty registry
disables that source of tools rather than restoring the original bindings.

Only the ARGI runtime executes tools. Adapters return tool calls without installing
their framework's agent loop. Approval, return-direct results, invocation-local
context, timeouts, and cancellation remain runtime responsibilities.

ARGI child agents can be registered without native tool callback types:

```java
ReactAgent parent = ReactAgent.builder()
    .name("coordinator")
    .chatClient(client)
    .toolRegistry(new ToolRegistry(List.of(AgentTool.create(child))))
    .build();
```

`AgentTool.create` returns a neutral `Tool`. It unwraps the `input` JSON argument,
preserves structured child response content and metadata (including empty
content), and derives a child thread ID from the parent config. It clears parent
checkpoint cursors and runtime context without mutating the parent. Execution is
asynchronous; cancelling its future interrupts the child invocation worker.
The removed `getFunctionToolCallback` entry point has no compatibility alias.

## Framework-Neutral Skills

Graph skill summaries use `ArgiSkillChatClient`, a neutral client decorator:

```java
SkillRegistry registry = FileSystemSkillRegistry.builder()
    .projectSkillsDirectory("./skills")
    .build();
ChatClient client = new ArgiSkillChatClient(adapterClient, registry);
ReactAgent agent = ReactAgent.builder()
    .name("writer")
    .chatClient(client)
    .toolRegistry(new ToolRegistry(List.of(ReadSkillTool.createReadSkillTool(registry, null))))
    .build();
```

The decorator refreshes the registry at invocation time, augments the first
system message or prepends one when absent, and preserves other messages, content
parts, metadata, tool schemas, generation options, and invocation attributes.
An empty registry leaves the original request unchanged. Streaming remains cold:
each subscription refreshes skills and starts a separate model turn. Cancellation
propagates to the delegate. The same registry should back the read-skill tool;
ARGI, not the underlying model framework, executes the tool loop.

`SkillsInterceptor` and `SkillsAgentHook.Builder` accept grouped bindings as
`Map<String, ToolRegistry>` and resolve `allowed_tools` through `.toolRegistry(...)`.
The grouped map remains shared so changes between turns are visible; returned
views are immutable snapshots. Tools are merged in this order: existing dynamic
bindings, skill-group bindings, then allowed-name bindings. The first binding for
each name wins. Empty selections and request copies retain the same semantics as
other model interceptors.

The previous `SpringAiSkillAdvisor` and `SkillPromptAugmentAdvisor` classes have
been removed directly, without deprecated aliases. Replace native
`defaultAdvisors(...)` skill configuration with this decorator. Decorator nesting
replaces advisor ordering; native advisor scheduler options are not retained.
Filesystem builder options accept directory strings; resolve a Spring `Resource`
to its filesystem path before passing it when needed.

Agent `call`, `invoke`, and streaming conversation APIs use neutral `ChatMessage`
values; model-interceptor `ModelResponse` is also neutral. ReAct model turns and
Flow routing use the ARGI `ChatClient`, and neutral conversation records are
supported by checkpoint serializers and Studio message conversion.

ReAct builders register tools through `toolRegistry(...)` only. Hooks and model
interceptors return neutral `Tool` bindings, and built-in tool factories and
builders also return `Tool`. Bindings are merged by name in this order: hooks,
interceptors, then the application registry. The first binding wins. Duplicate
names within a single `ToolRegistry` are rejected rather than silently overwritten.
`ToolInjection` matches a neutral tool by name, then by type if supplied, or picks
the first binding when neither is specified. Injection occurs during lazy graph
construction (`getGraph()` or the first invocation), not during `build()`.

`throwOnToolError(false)` is the default: execution exceptions become tool error
messages. Set it to `true` to propagate execution failures instead. This applies
to neutral and built-in tools in sequential, parallel, and asynchronous execution.
Argument validation and unresolved tool names remain error responses; interceptors
may implement their own recovery policy.

The old `tools`, `methodTools`, `toolCallbackProviders`, `toolNames`, `resolver`,
and `toolExecutionExceptionProcessor` builder methods and `create*ToolCallback`
built-in factories are removed without deprecated aliases. Use neutral factories
and `ToolRegistry`; existing Spring AI callbacks can be explicitly wrapped with
`SpringAiToolAdapter` in the Spring AI adapter module.

The API, Graph, Agent/ReAct, Studio, and generic starters use neutral messages,
tools, serializers, and model calls. Their production sources, compiled classes,
and runtime dependency trees must not reference provider SDKs or adapters. Provider
SDKs are confined to the four adapter modules and their dedicated starters;
test-scoped adapter fixtures do not become application dependencies. Third-party
Native Agent Mode is not implemented in this phase.

This is a direct replacement, not a source or binary compatibility promise.
`make api-contract-check` compiles an independent application using the current
neutral public API. External Extensions and Examples consumers must update their
builders and tool bindings before compiling against this branch; they are not
migrated by this repository.

## Runtime Dependency Verification

The root build and published `argi-bom` align Jackson 2/3 patch families,
Bouncy Castle, jsoup, and embedded Tomcat. Import `argi-bom` before other BOMs
that manage the same dependencies, or explicitly align your application overrides.
An application's dependency management can override transitive library versions;
audit the final application dependency tree as well as the framework's tree.

## Google ADK Capability Profiles

ADK `BaseLlm` does not expose a capability-discovery contract. The adapter uses
implementation-specific defaults for the pinned ADK 1.11.0 baseline:

| Native implementation | Streaming | Tool calling | Structured output | Cancellation |
| --- | --- | --- | --- | --- |
| `Gemini`, `ApigeeLlm` | Yes | Yes | Yes | Subscription disposal |
| `Claude` | No | No | No | Not guaranteed |
| Other `BaseLlm` implementations | No by default | No by default | No by default | Not guaranteed |

These profiles describe the mappings provided by ARGI, not the capabilities of
every remote model behind that implementation. All profiles accept text/JSON
content and provide asynchronous invocation. Binary multimodal input is not
mapped by this adapter. For a custom implementation, explicitly declare only
capabilities verified against that implementation:

```java
var capabilities = new ChatClientCapabilities(true, true, true, false, true, true);
ChatClient chatClient = new GoogleAdkChatClientAdapter(customBaseLlm, capabilities);
```

The overload can also narrow the defaults for a provider. It rejects declarations
that this adapter cannot implement, including multimodal input, synchronous-only
invocation, and advanced capabilities for native ADK `Claude`.

Native ADK `Claude` receives only non-streaming text requests through this
adapter. Unsupported streaming, tool history, tool schemas, structured output,
and per-request temperature, top-p, token-limit, or stop-sequence overrides fail
explicitly. Configure its supported generation parameters on the native model
constructor instead. ARGI does not install ADK tool executors or delegate the
tool loop to ADK.

Model invocation is deferred onto Reactor's bounded-elastic scheduler because
some `BaseLlm` implementations block before returning their RxJava publisher.
Cancelling a future or stream disposes its subscription; whether this interrupts
an in-flight network request depends on the native transport. In particular,
native ADK `Claude` cannot guarantee transport cancellation.

In Spring Boot, a user-provided neutral `ChatClient` bean takes precedence over
the adapter starter. Declare the explicit-capabilities adapter as that bean when
using a custom `BaseLlm`. The starter preserves the implementation-specific
default profile when wrapping a native model automatically.

The source baseline is Google's `google/adk-java` tag `v1.11.0`, specifically
`core/src/main/java/com/google/adk/models/{BaseLlm,Gemini,ApigeeLlm,Claude}.java`.
Capability, caller-blocking, and subscription-cancellation behavior is covered by
`GoogleAdkChatClientAdapterTests` and
`GoogleAdkChatClientAutoConfigurationTests`.


## Checkpoint Upgrade and Rollback

The RC2 work keeps the development version `2.1.0-RC2-SNAPSHOT`. It is a breaking
pre-release, not a patch-compatible upgrade from RC1. Existing applications must
migrate before adopting it; the final stable release version is not decided here.

`ArgiJacksonStateSerializer` recognizes the historical Spring AI JSON `@class`
names and `@type` markers for system, user, assistant, DeepSeek/ZhiPu reasoning,
and grouped tool-response messages. The same identifiers in `@typeHint` are also
recognized. Conversion needs no provider SDK on the classpath. It preserves roles,
text, metadata, tool-call IDs, names, argument JSON and response order. A grouped
tool response becomes one neutral TOOL message per response. Subsequent writes
use `ARGI_*` records. The SDK boundary gate permits these exact historical wire
literals only in the migration reader, not SDK imports, reflection or JVM types.

Legacy binary checkpoints, templated instruction messages, nonempty legacy media,
unknown SDK types and legacy StreamingOutput records are **not automatically
compatible**. They fail explicitly; they must not become user messages or disappear.
Template content must be resolved with the previous runtime. There is no bundled
one-command binary export tool: use the old runtime and an application-specific
export that emits the supported historical JSON layout or neutral messages.
Do not pass legacy maps directly to `Messageutils`; deserialize them first.

Before deployment:

1. Back up the checkpoint store and test restoring that backup with the old runtime.
2. Drain in-flight streams and pending tool executions. Resolve/export unsupported
   records with the old runtime while it still has the required classes.
3. On a copy of production data, read JSON with the new serializer and verify
   message counts, roles, tool-call/result pairing, argument JSON and reasoning.
4. Upgrade the application, persistence integration and Studio client together.
   Do not run old and new writers against the same checkpoint store.
5. Once neutral records have been written, **rollback requires restoring the
   pre-upgrade backup** (or a separately verified reverse migration). The old
   runtime cannot read new markers. Restoring the backup loses post-upgrade work;
   coordinate application side effects separately to avoid duplicate tool execution.

### Tool Snapshots and Operational Limits

Dynamic tool bindings selected by hooks/interceptors remain in a process-local
Caffeine cache: at most 4096 turns per compiled agent by default. A checkpoint
persists identity and the complete selected definitions, not executable tools.
After restart, agent rebuild or eviction, a pending dynamic turn fails closed.
Drain pending HITL tool approvals before rolling restarts or regenerate the model
turn and obtain approval again under application control. Sticky routing alone
cannot survive process loss. Static selections can be restored only when the
configured bindings match the persisted full name, description and schema;
storing names only would weaken that check, so full definitions are retained.

Each tool node owns a bounded daemon executor; idle core threads time out after
30 seconds. Saturation rejects work immediately. Execution rejection follows the
configured tool-error policy rather than silently queuing indefinitely. This is
not a persistent job queue or a guarantee that pending work survives shutdown.

`ToolChoice.NONE` guarantees no tool invocation, not identical schema visibility:
Spring AI omits tool schemas, while other native APIs may still advertise them.
ADK output parts not mapped by the declared profile fail explicitly rather than
being skipped. Blank provider tool-call IDs still receive turn-local random IDs;
applications must not assume identical synthesized IDs across model replays.
Spring AI usage-only chunks must contain an empty generation list, not a
generation with a null output. The native advisor chain aggregates before ARGI
conversion and rejects malformed outputs. Such a stream fails with its original
cause retained and does not emit successful completion events; the adapter does
not bypass native advisors or invent assistant messages to repair invalid data.

### Reasoning and Studio Events

All adapters expose reasoning on assistant **message** metadata through
`ChatMetadataKeys.REASONING` (`"reasoning"`). Native `reasoningContent` / response
`thinking` are not portable contract keys. Reasoning delta events use the same
metadata key. The runtime and checkpoint reader consume this neutral convention.
Streaming text remains append-only: adapters and runtime use the shared
`ChatStreams.unseenSuffix` invariant, and rewritten final text fails explicitly.
An appended corrective delta cannot retract text already delivered to callers.

Studio attaches turn usage only to the final event when splitting a multi-message
output. Earlier split events carry unknown usage. Upgrade SSE consumers for
`inputTokens`, `outputTokens`, `totalTokens` rather than the previous
`promptTokens` / `completionTokens` fields; do not accumulate usage per message.
AgentScope uses the native total-token getter, without assuming a provider total
can always be reconstructed from input and output tokens.

The agent framework uses JSON Schema Validator 3.x for its current neutral tool
validation API. Studio pins 1.5.7 in test scope because its MCP fixture requires
`SpecVersion.VersionFlag`, an incompatible older API. That test-only pin is not
a runtime dependency or a recommendation for applications. Adapter SDK baselines
remain module-specific and are not managed by `argi-bom`; align provider versions
explicitly when an application adds its own SDK dependencies.
