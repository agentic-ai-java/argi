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
import io.github.agentic.ai.api.chat.ChatRole;
import io.github.agentic.ai.api.chat.ContentPart;
import io.github.agentic.ai.api.chat.FinishReason;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ResponseFormat;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolChoice;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.chat.Usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Explicit, version-tagged JSON payloads for model-turn journal entries.
 *
 * <p>
 * This is deliberately not a Java object serializer: a recorded response must stay
 * readable after a class changes, so every field is written by name and unknown fields
 * are ignored on read. The payload types below are the schema identifiers carried in
 * {@code RunPayload#type()}; they are stable data, never Java class names.
 *
 * <p>
 * Request bodies are never journaled. A request is reduced to a SHA-256 fingerprint of its
 * canonical form, which is enough to prove a recovered turn is the same request without
 * persisting prompts that may contain sensitive data. Responses and tool arguments are
 * recorded, because replaying them is the purpose of the journal; applications apply
 * their data-protection policy to the journal backend.
 */
public final class ModelTurnCodec {

    /**
     * Version of every payload schema defined here.
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * {@code MODEL_CALL}: the request fingerprint, written before the provider call.
     */
    public static final String REQUEST_TYPE = "argi.model-request";

    /**
     * {@code MODEL_CALL}: the complete terminal response.
     */
    public static final String RESPONSE_TYPE = "argi.model-response";

    /**
     * {@code TASK_CALL}: one planned tool call, committed with its response.
     */
    public static final String PLAN_TYPE = "argi.tool-plan";

    /**
     * {@code EVENT}: streamed text received before the terminal response.
     */
    public static final String DELTA_TYPE = "argi.model-delta";

    /**
     * Metadata that belongs to one process run: protocol markers and handles into
     * in-memory tool-binding snapshots. A recovering invocation re-derives these, so
     * recording them would only replay stale state.
     */
    private static final Set<String> PROCESS_LOCAL_METADATA = Set.of(
            "argiFinalResponse",
            "argiTextDelta",
            "argi.toolBindingSnapshot",
            "argi.toolBindingSelection",
            "argi.dynamicToolBindings",
            "argiModelFailure"
    );

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ModelTurnCodec() {
    }

    /**
     * Canonical fingerprint of the request a provider actually receives: ordered messages,
     * disclosed tool definitions and effective generation options. Two requests with the
     * same fingerprint are the same logical model call.
     */
    public static String requestHash(ChatRequest request) {
        return sha256(encodeRequestBody(Objects.requireNonNull(request, "request cannot be null")));
    }

    /**
     * The {@link #REQUEST_TYPE} payload: the fingerprint only, never the request body.
     */
    public static String encodeRequest(ChatRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("requestHash", requestHash(request));
        return write(root);
    }

    /**
     * Reads the fingerprint recorded in a {@link #REQUEST_TYPE} payload.
     */
    public static String decodeRequestHash(String json) {
        return text(read(json), "requestHash");
    }

    /**
     * The {@link #RESPONSE_TYPE} payload.
     */
    public static String encodeResponse(ChatResponse response) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("id", response.id());
        root.put("model", response.model());
        root.put("finishReason", response.finishReason() == null ? null : response.finishReason().name());
        root.set("usage", encodeUsage(response.usage()));
        root.set("metadata", encodeMetadata(response.metadata()));
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : response.messages()) {
            messages.add(encodeMessage(message));
        }
        ArrayNode toolCalls = root.putArray("toolCalls");
        for (ToolCall call : response.toolCalls()) {
            toolCalls.add(encodeToolCall(call));
        }
        return write(root);
    }

    public static ChatResponse decodeResponse(String json) {
        JsonNode root = read(json);
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode message : root.path("messages")) {
            messages.add(decodeMessage(message));
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : root.path("toolCalls")) {
            toolCalls.add(decodeToolCall(call));
        }
        String finishReason = text(root, "finishReason");
        return new ChatResponse(text(root, "id"), text(root, "model"), messages, toolCalls,
                finishReason == null ? null : FinishReason.valueOf(finishReason), decodeUsage(root.path("usage")),
                decodeMetadata(root.path("metadata")));
    }

    /**
     * The {@link #DELTA_TYPE} payload.
     */
    public static String encodeDelta(String text) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("text", text);
        return write(root);
    }

    /**
     * The {@link #PLAN_TYPE} payload. The tool-call id assigned by the provider is
     * deliberately absent: it correlates protocol messages, it is not a task identity.
     */
    public static String encodeToolPlan(ToolCall call, ToolDefinition definition, int ordinal) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("ordinal", ordinal);
        root.put("toolName", call.name());
        root.put("definitionVersion", definition == null ? null : definitionVersion(definition));
        root.put("argumentsHash", sha256(call.arguments()));
        root.put("arguments", call.arguments());
        return write(root);
    }

    /**
     * Stable identity of one tool definition; a changed schema must not reuse a task id.
     */
    public static String definitionVersion(ToolDefinition definition) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("name", definition.name());
        root.put("description", definition.description());
        root.put("inputSchema", definition.inputSchema());
        return sha256(write(root));
    }

    private static String encodeRequestBody(ChatRequest request) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : request.messages()) {
            messages.add(encodeMessage(message));
        }
        ArrayNode tools = root.putArray("tools");
        for (ToolDefinition definition : request.tools()) {
            ObjectNode tool = tools.addObject();
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            tool.put("inputSchema", definition.inputSchema());
        }
        root.set("options", encodeOptions(request.options()));
        return write(root);
    }

    private static ObjectNode encodeMessage(ChatMessage message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", message.role().name());
        node.put("toolCallId", message.toolCallId());
        node.put("toolName", message.toolName());
        ArrayNode parts = node.putArray("content");
        for (ContentPart part : message.content()) {
            ObjectNode encoded = parts.addObject();
            encoded.put("type", part.type().name());
            encoded.put("text", part.text());
            encoded.put("mimeType", part.mimeType());
            byte[] data = part.data();
            encoded.put("data", data == null ? null : Base64.getEncoder().encodeToString(data));
            encoded.set("metadata", encodeMetadata(part.metadata()));
        }
        ArrayNode calls = node.putArray("toolCalls");
        for (ToolCall call : message.toolCalls()) {
            calls.add(encodeToolCall(call));
        }
        node.set("metadata", encodeMetadata(message.metadata()));
        return node;
    }

    private static ChatMessage decodeMessage(JsonNode node) {
        List<ContentPart> content = new ArrayList<>();
        for (JsonNode part : node.path("content")) {
            String data = text(part, "data");
            content.add(new ContentPart(ContentPart.Type.valueOf(part.path("type").asText("TEXT")), text(part, "text"),
                    text(part, "mimeType"), data == null ? null : Base64.getDecoder().decode(data),
                    decodeMetadata(part.path("metadata"))));
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : node.path("toolCalls")) {
            calls.add(decodeToolCall(call));
        }
        return new ChatMessage(ChatRole.valueOf(node.path("role").asText()), content, calls, text(node, "toolCallId"),
                text(node, "toolName"), decodeMetadata(node.path("metadata")));
    }

    private static ObjectNode encodeToolCall(ToolCall call) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", call.id());
        node.put("name", call.name());
        node.put("arguments", call.arguments());
        return node;
    }

    private static ToolCall decodeToolCall(JsonNode node) {
        return new ToolCall(node.path("id").asText(), node.path("name").asText(), node.path("arguments").asText("{}"));
    }

    private static ObjectNode encodeUsage(Usage usage) {
        ObjectNode node = MAPPER.createObjectNode();
        Usage value = usage == null ? Usage.unknown() : usage;
        node.put("inputTokens", value.inputTokens());
        node.put("outputTokens", value.outputTokens());
        node.put("totalTokens", value.totalTokens());
        return node;
    }

    private static Usage decodeUsage(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return Usage.unknown();
        }
        return new Usage(number(node, "inputTokens"), number(node, "outputTokens"), number(node, "totalTokens"));
    }

    private static ObjectNode encodeOptions(GenerationOptions options) {
        ObjectNode node = MAPPER.createObjectNode();
        GenerationOptions value = options == null ? GenerationOptions.defaults() : options;
        node.put("temperature", value.temperature());
        node.put("topP", value.topP());
        node.put("maxTokens", value.maxTokens());
        ArrayNode stops = node.putArray("stopSequences");
        value.stopSequences().forEach(stops::add);
        ToolChoice choice = value.toolChoice();
        ObjectNode encodedChoice = node.putObject("toolChoice");
        encodedChoice.put("mode", choice.mode().name());
        encodedChoice.put("toolName", choice.toolName());
        ResponseFormat format = value.responseFormat();
        ObjectNode encodedFormat = node.putObject("responseFormat");
        encodedFormat.put("type", format.type().name());
        encodedFormat.put("name", format.name());
        encodedFormat.put("schema", format.schema());
        node.set("metadata", encodeMetadata(value.metadata()));
        return node;
    }

    private static ObjectNode encodeMetadata(Map<String, Object> metadata) {
        ObjectNode node = MAPPER.createObjectNode();
        if (metadata == null) {
            return node;
        }
        new TreeMap<>(metadata).forEach((key, value) -> {
            if (value != null && !PROCESS_LOCAL_METADATA.contains(key)) {
                node.set(key, MAPPER.valueToTree(value));
            }
        });
        return node;
    }

    private static Map<String, Object> decodeMetadata(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        node.fields()
                .forEachRemaining(entry -> metadata.put(entry.getKey(), MAPPER.convertValue(entry.getValue(), Object.class)));
        return metadata;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new ModelTurnJournalException("Cannot encode journal payload", ex);
        }
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(Objects.requireNonNull(json, "journal payload cannot be null"));
        } catch (JsonProcessingException ex) {
            throw new ModelTurnJournalException("Cannot decode journal payload", ex);
        }
    }

}
