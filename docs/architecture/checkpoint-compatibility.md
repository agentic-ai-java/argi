# Checkpoint and Serializer Compatibility Contract

Status: adopted for the current Loop migration. Reviewed 2026-10-24.

This document records the decisions required by [#182](https://github.com/agentic-ai-java/argi/issues/182).
The protocol is intentionally split into historical formats and new neutral data
boundaries. A module or package rename must not silently change these formats.

## Format inventory

| Format | Producer and markers | Compatibility rule |
| --- | --- | --- |
| Java object stream state | `Serializer.writeUTF` inside an `ObjectOutputStream`; may contain `@class`, `@type`, or `@typeHint` values from the old runtime | Read only when the old Java classes and serializer semantics are available. There is no generic safe converter for arbitrary object graphs. Unsupported binary data fails with an explicit export/migration instruction. |
| Jackson plain-text state | Untyped JSON object with stable `ARGI_*` markers for neutral records and map envelopes | Current format. Preserve field names, null behavior, arrays, map keys, metadata, and marker values. New code must not enable default typing implicitly. |
| Historical provider chat state | Spring AI class names or role markers such as `SYSTEM`, `USER`, `ASSISTANT`, `TOOL`, `DEEPSEEK_ASSISTANT`, and `ZHI_PU_AI_ASSISTANT` | Read by `LegacyChatRecordSupport`, normalize into neutral `ChatMessage`/`ToolCall` values, and write only neutral markers. The reader does not load provider SDK classes. |
| Runtime Run/Task payload | Strict JSON `RunPayload` with schema, type, version, canonical JSON, and SHA-256 input hash | Separate from graph state serialization. Envelope and application payload versions evolve independently. Do not reuse Java object serialization. |
| Checkpoint and Store JSON fixtures | `checkpoint.json` and `store-item.json` under `src/test/resources/compatibility/2.1.0` | Continue reading and writing the existing field names and numeric values. These fixtures are regression evidence, not a new interchange specification. |

## Marker contract

The following stable type IDs are data identifiers and must not be changed by a
Java package move:

| Marker | Neutral value |
| --- | --- |
| `ARGI_CHAT_MESSAGE` | `ChatMessage` |
| `ARGI_CHAT_EVENT` | `ChatEvent` |
| `ARGI_CHAT_RESPONSE` | neutral `ChatResponse` |
| `ARGI_CONTENT_PART` | `ContentPart` |
| `ARGI_TOOL_CALL` | `ToolCall` |
| `ARGI_USAGE` | `Usage` |
| `ARGI_DURATION` | `Duration` |
| `ARGI_STREAMING_OUTPUT` | runtime streaming snapshot |

Historical `@class`, `@type`, and `@typeHint` values are interpreted only when
they match a known legacy chat shape or a registered application type. Unknown
business fields named `@class` or `@type` remain data when no supported legacy
shape is recognized; they are not silently converted into a different value.

`graphId`, `graphVersion`, `DefinitionKind.GRAPH.wireName()` (`graph`), storage
namespace encoding, and `argi.graph.*` observation names remain stable. They are
wire and operational identifiers, not product display names.

## Compatibility matrix

| Input | Action | Supported version | Failure and rollback behavior |
| --- | --- | --- | --- |
| 2.1.0 checkpoint JSON | Read, validate, and write equivalent JSON semantics | 2.1.0 and later | Fail before publishing a replacement if required fields cannot be decoded. Restore the previous checkpoint on write failure. |
| 2.1.0 Store item JSON | Read and write unchanged namespace, key, value, and epoch fields | 2.1.0 and later | Reject malformed namespace/key/value shapes; do not delete the original record. |
| Neutral `ARGI_*` chat records | Read, normalize, and write neutral records | Current neutral marker version | Unknown marker fields remain data; malformed known records fail with an `IOException`. |
| Historical provider chat records | Read and normalize to neutral records | Historical formats represented by `LegacyChatRecordSupport` | Provider classes are never loaded. Unsupported media, templates, malformed tool calls, and invalid metadata fail explicitly and require export with the old runtime or an offline converter. |
| Historical `ChatResponse`, streaming snapshots, or provider binary payloads | Do not guess a conversion | Not supported by the neutral reader | Fail with a migration instruction. The original bytes remain untouched. |
| Java object-stream state with unavailable classes | Do not degrade to an empty state or map | No generic version | Fail closed with the class/migration error. Restore from a backup or run an offline conversion in the old runtime. |
| Type ID renamed in application code | Resolve through an explicit `TypeMapper.Reference` alias or migrate offline | Application-defined | If no alias exists, fail or preserve the marker as business data; never silently instantiate a different type. |
| `RunPayload` envelope | Validate exact version, schema, type, canonical JSON, and hash | Envelope v1 | Reject unknown fields, duplicate keys, trailing documents, scalar coercion, and forged hashes before admission. |

## Implementation boundaries

- `JacksonStateSerializer` owns new neutral JSON writing and recursive state
  normalization.
- `JacksonDeserializer` owns marker dispatch and safe map fallback. It does not
  enable Jackson default typing for untrusted data.
- `LegacyChatRecordSupport` owns historical provider chat conversion and has no
  provider SDK dependency.
- `TypeMapper` owns explicit stable type registrations. An application that needs
  a custom type must register a stable ID and test both the old fixture and the
  new output.
- `RunPayload` and its codecs own recoverable Run/Task input data. They are not a
  replacement for graph state serializers.
- Extensions persistence adapters must consume these contracts without changing
  graph wire literals. A backend adapter additionally proves restart persistence,
  transaction rollback, and cross-JVM behavior using its real storage service.

## Evidence and tests

The current source and fixtures provide evidence for:

- legacy checkpoint and Store JSON round trips in
  `LegacySerializationCompatibilityTest`;
- historical provider chat conversion and explicit refusal in
  `LegacyChatCheckpointMigrationTest`;
- pre-runtime-extraction trusted context data in
  `execution/trusted-context-v1-before-runtime-extraction.json`;
- strict Run/Task payload validation in `argi-runtime-core` contract tests.

The fixture `compatibility/2.1.0/legacy-chat-checkpoint.json` is intentionally
written in the historical provider format. It proves that the migration reader
consumes data produced before the neutral type IDs were introduced, rather than
only testing a new serializer against itself.

## Migration and rollback

1. Back up the original checkpoint or Store record.
2. Upgrade the artifact coordinate without changing Java package names or wire
   literals.
3. Read the old record with the compatibility reader and write the neutral form
   only after successful normalization.
4. Keep the original record until a complete recovery execution succeeds.
5. If conversion fails, restore the original bytes and run the old-runtime export
   path for unsupported binary, media, template, or provider-specific values.

No automatic in-place rewrite is performed by the Loop artifact migration. This
keeps rollback possible and avoids treating a successful JSON parse as proof that
all execution semantics were preserved.
