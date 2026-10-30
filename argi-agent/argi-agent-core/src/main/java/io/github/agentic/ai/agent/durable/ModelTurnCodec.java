/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.agent.durable;

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.runtime.execution.run.RunEntry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Version-tagged JSON payloads for model-turn journal entries.
 *
 * <p>
 * The neutral chat contracts are bound by their declared record components, not by a
 * hand-written field-by-field codec: the payload and the type stay described in one place,
 * and a codec bug cannot desynchronise the write and read paths. What is written by hand
 * is only where the recorded shape must deliberately differ from the type:
 * <ul>
 * <li>{@link RequestFingerprint} - a request is reduced to its fingerprint, so the body is
 * never journaled and adapter-level {@code attributes} are excluded from the hash;</li>
 * <li>{@link ToolPlan} - the provider's tool-call id is excluded, because it correlates
 * protocol messages and is not a task identity;</li>
 * <li>{@link AttemptRecord} - a model-call reservation pins its ordinal and request hash;</li>
 * <li>{@link Delta} - streamed text is a single scalar.</li>
 * </ul>
 *
 * <p>
 * No Java type information is written: this mapper never enables default typing, so a
 * payload stays readable by any process and after a class moves. Unknown fields are
 * ignored on read so a newer writer can add fields without invalidating old records, and
 * property and map-key ordering is pinned so equal data always produces equal JSON - which
 * is what makes {@link #requestHash(ChatRequest)} stable.
 *
 * <p>
 * Request bodies are never journaled. Responses and tool arguments are, because replaying
 * them is the purpose of the journal; applications apply their data-protection policy to
 * the journal backend.
 */
public final class ModelTurnCodec {

	/** Version of every payload schema defined here, carried by {@code RunPayload}. */
	public static final int SCHEMA_VERSION = 1;

	/** {@code MODEL_CALL}: the request fingerprint, written before the provider call. */
	public static final String REQUEST_TYPE = "argi.model-request";

	/** {@code MODEL_CALL}: a model-call reservation, committed before invoking the provider. */
	public static final String ATTEMPT_TYPE = "argi.model-attempt";

	/** {@code MODEL_CALL}: the complete terminal response. */
	public static final String RESPONSE_TYPE = "argi.model-response";

	/** {@code TASK_CALL}: one planned tool call, committed with its response. */
	public static final String PLAN_TYPE = "argi.tool-plan";

	/** {@code EVENT}: streamed text received before the terminal response. */
	public static final String DELTA_TYPE = "argi.model-delta";

	/**
	 * Metadata that belongs to one process run: protocol markers and handles into
	 * in-memory tool-binding snapshots. A recovering invocation re-derives these, so
	 * recording them would only replay stale state - and a snapshot id that changes per
	 * process would change the request fingerprint across a restart.
	 */
	private static final Set<String> PROCESS_LOCAL_METADATA = Set.of("argiFinalResponse", "argiTextDelta",
			"argi.toolBindingSnapshot", "argi.toolBindingSelection", "argi.dynamicToolBindings", "argiModelFailure");

	private static final JsonMapper MAPPER = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	private ModelTurnCodec() {
	}

	/**
	 * Canonical fingerprint of the request a provider actually receives: ordered messages,
	 * disclosed tool definitions and effective generation options.
	 *
	 * <p>
	 * {@link ChatRequest#attributes()} is deliberately excluded. It is a transport escape
	 * hatch whose meaning differs per adapter (Spring AI passes it to advisor parameters,
	 * LangChain4j to listener attributes, Google ADK only checks emptiness), and in the
	 * graph-independent engine it carries the whole mutable agent state. Hashing it would
	 * bind request identity to adapter internals and to state that changes for reasons
	 * unrelated to the request. An application whose adapters derive generation semantics
	 * from attributes must not rely on replay across differing attribute values.
	 */
	public static String requestHash(ChatRequest request) {
		Objects.requireNonNull(request, "request cannot be null");
		RequestFingerprint fingerprint = new RequestFingerprint(sanitize(request.messages()), request.tools(),
				request.options());
		return sha256(write(fingerprint));
	}

	/** The {@link #REQUEST_TYPE} payload: the fingerprint only, never the request body. */
	public static String encodeRequest(ChatRequest request) {
		return write(new RequestRecord(requestHash(request)));
	}

	/** Reads the fingerprint recorded in a {@link #REQUEST_TYPE} payload. */
	public static String decodeRequestHash(String json) {
		return read(json, RequestRecord.class).requestHash();
	}

	static String encodeAttempt(int ordinal, String requestHash) {
		return write(new AttemptRecord(ordinal, requestHash));
	}

	static int decodeAttemptOrdinal(String json, String requestHash) {
		JsonNode record = read(json, JsonNode.class);
		JsonNode ordinal = record.get("ordinal");
		JsonNode hash = record.get("requestHash");
		if (ordinal == null || !ordinal.isInt() || ordinal.intValue() < 1 || hash == null || !hash.isTextual()
				|| !requestHash.equals(hash.textValue())) {
			throw new ModelTurnJournalException("Invalid model attempt ordinal or request fingerprint");
		}
		return ordinal.intValue();
	}

	/** The {@link #RESPONSE_TYPE} payload. */
	public static String encodeResponse(ChatResponse response) {
		return write(sanitize(Objects.requireNonNull(response, "response cannot be null")));
	}

	public static ChatResponse decodeResponse(String json) {
		return read(json, ChatResponse.class);
	}

	/** The {@link #DELTA_TYPE} payload. */
	public static String encodeDelta(String text) {
		return write(new Delta(text));
	}

	/**
	 * The {@link #PLAN_TYPE} payload. The tool-call id assigned by the provider is
	 * deliberately absent: it correlates protocol messages, it is not a task identity.
	 *
	 * <p>
	 * Arguments are stored and hashed in canonical form, so two semantically equal
	 * invocations that differ only in whitespace or object-key order produce the same
	 * record and the same hash. The hash checks input consistency; the logical task is
	 * identified separately by {@link ToolTaskIdentity}.
	 */
	public static String encodeToolPlan(ToolCall call, ToolDefinition definition, int ordinal) {
		String arguments = canonicalArguments(call.arguments());
		return write(new ToolPlan(ordinal, call.name(), definition == null ? null : definitionVersion(definition),
				sha256(arguments), arguments));
	}

	/**
	 * Reads an existing plan without resolving tools or validating argument schemas.
	 * Checks the envelope, stored field types, task identity and argument hash only.
	 * The caller supplies the owning turn because entries are scoped to a run by storage,
	 * not by their payload. This method neither verifies a complete model turn nor grants
	 * permission to dispatch; use the recorder to validate the full committed snapshot.
	 * @param turn the owning logical model call
	 * @param entry the stored TASK_CALL entry
	 * @return the typed view of the stored plan, including a possibly absent definition
	 * @throws ModelTurnJournalException if the journal entry is incompatible or inconsistent
	 */
	public static PlannedToolCall decodeToolPlan(ModelTurnRecorder.Turn turn, RunEntry entry) {
		Objects.requireNonNull(turn, "turn cannot be null");
		Objects.requireNonNull(entry, "entry cannot be null");
		if (entry.schemaVersion() != 1 || entry.kind() != RunEntry.Kind.TASK_CALL
				|| entry.payload().schemaVersion() != SCHEMA_VERSION || !PLAN_TYPE.equals(entry.payload().type())) {
			throw new ModelTurnJournalException("Incompatible tool plan entry " + entry.id());
		}
		JsonNode plan = read(entry.payload().json(), JsonNode.class);
		JsonNode ordinal = plan.get("ordinal");
		JsonNode name = plan.get("toolName");
		JsonNode definition = plan.get("definitionVersion");
		JsonNode hash = plan.get("argumentsHash");
		JsonNode arguments = plan.get("arguments");
		if (ordinal == null || !ordinal.isInt() || ordinal.intValue() < 0
				|| name == null || !name.isTextual() || name.textValue().isBlank()
				|| (definition != null && !definition.isNull() && !definition.isTextual())
				|| hash == null || !hash.isTextual() || arguments == null || !arguments.isTextual()) {
			throw new ModelTurnJournalException("Invalid tool plan fields in entry " + entry.id());
		}
		ToolTaskIdentity identity = new ToolTaskIdentity(turn.runId(), turn.turnId(), ordinal.intValue());
		if (!identity.entryId().equals(entry.id()) || !sha256(arguments.textValue()).equals(hash.textValue())) {
			throw new ModelTurnJournalException("Inconsistent tool plan identity or argument hash in entry " + entry.id());
		}
		return new PlannedToolCall(identity, name.textValue(),
				definition == null || definition.isNull() ? null : definition.textValue(),
				hash.textValue(), arguments.textValue());
	}

	/**
	 * Canonical form of a tool-argument JSON string: object keys sorted recursively,
	 * compact output.
	 *
	 * <p>
	 * Keys are reordered explicitly rather than through a mapper feature: the mapper's
	 * ordering settings apply to POJO properties and {@code Map} entries, not to an
	 * {@code ObjectNode}, which preserves insertion order. Only objects are reordered.
	 * Array order carries meaning (argument lists are positional) and scalars have no order.
	 * Malformed JSON is returned unchanged rather than rejected: argument validation is
	 * opt-in, so a provider may legitimately produce arguments this codec cannot parse, and
	 * that must not fail a turn that would otherwise dispatch.
	 */
	public static String canonicalArguments(String arguments) {
		if (arguments == null || arguments.isBlank()) {
			return arguments;
		}
		try {
			JsonNode tree = MAPPER.readTree(arguments);
			return tree == null || !tree.isContainerNode() ? arguments : write(sortKeys(tree));
		}
		catch (JsonProcessingException malformed) {
			return arguments;
		}
	}

	private static JsonNode sortKeys(JsonNode node) {
		if (node.isObject()) {
			ObjectNode sorted = MAPPER.createObjectNode();
			TreeSet<String> keys = new TreeSet<>();
			node.fieldNames().forEachRemaining(keys::add);
			keys.forEach(key -> sorted.set(key, sortKeys(node.get(key))));
			return sorted;
		}
		if (node.isArray()) {
			ArrayNode values = MAPPER.createArrayNode();
			node.forEach(child -> values.add(sortKeys(child)));
			return values;
		}
		return node;
	}

	/** Stable identity of one tool definition; a changed schema must not reuse a task id. */
	public static String definitionVersion(ToolDefinition definition) {
		return sha256(write(new DefinitionRecord(definition.name(), definition.description(),
				definition.inputSchema())));
	}

	private static List<ChatMessage> sanitize(List<ChatMessage> messages) {
		return messages == null ? List.of() : messages.stream().map(ModelTurnCodec::sanitize).toList();
	}

	private static ChatResponse sanitize(ChatResponse response) {
		return new ChatResponse(response.id(), response.model(), sanitize(response.messages()), response.toolCalls(),
				response.finishReason(), response.usage(), sanitize(response.metadata()));
	}

	private static ChatMessage sanitize(ChatMessage message) {
		List<ContentPart> content = message.content()
			.stream()
			.map(part -> new ContentPart(part.type(), part.text(), part.mimeType(), part.data(),
					sanitize(part.metadata())))
			.toList();
		// Tool arguments are canonicalised here too: an assistant message replaying earlier
		// calls must fingerprint identically no matter how the provider formatted them.
		List<ToolCall> toolCalls = message.toolCalls()
			.stream()
			.map(call -> new ToolCall(call.id(), call.name(), canonicalArguments(call.arguments())))
			.toList();
		return new ChatMessage(message.role(), content, toolCalls, message.toolCallId(), message.toolName(),
				sanitize(message.metadata()));
	}

	private static Map<String, Object> sanitize(Map<String, Object> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> retained = new LinkedHashMap<>();
		metadata.forEach((key, value) -> {
			if (key != null && value != null && !PROCESS_LOCAL_METADATA.contains(key)) {
				retained.put(key, value);
			}
		});
		return retained;
	}

	private static <T> T read(String json, Class<T> type) {
		try {
			return MAPPER.readValue(Objects.requireNonNull(json, "journal payload cannot be null"), type);
		}
		catch (JsonProcessingException ex) {
			throw new ModelTurnJournalException("Cannot decode journal payload as " + type.getSimpleName(), ex);
		}
	}

	private static String write(Object payload) {
		try {
			return MAPPER.writeValueAsString(payload);
		}
		catch (JsonProcessingException ex) {
			throw new ModelTurnJournalException("Cannot encode journal payload " + payload.getClass().getSimpleName(),
					ex);
		}
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
		}
	}

	/** Stored shape of {@link #REQUEST_TYPE}: the hash only, never the request body. */
	private record RequestRecord(String requestHash) {
	}

	/** Stored shape of {@link #ATTEMPT_TYPE}; a reservation is consumed even if its result is lost. */
	private record AttemptRecord(int ordinal, String requestHash) {
	}

	/**
	 * Hashed shape of a request. Excludes {@link ChatRequest#attributes()} on purpose; see
	 * {@link #requestHash(ChatRequest)}.
	 */
	private record RequestFingerprint(List<ChatMessage> messages, List<ToolDefinition> tools,
			GenerationOptions options) {
	}

	/** Stored shape of {@link #PLAN_TYPE}; excludes the provider's tool-call id. */
	private record ToolPlan(int ordinal, String toolName, String definitionVersion, String argumentsHash,
			String arguments) {
	}

	/** Hashed shape of a tool definition; a changed schema must not reuse a task id. */
	private record DefinitionRecord(String name, String description, String inputSchema) {
	}

	/** Stored shape of {@link #DELTA_TYPE}. */
	private record Delta(String text) {
	}

}
