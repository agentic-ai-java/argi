/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent.node;

import io.github.agentic.ai.api.chat.ChatMessage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.agentic.ai.graph.RunnableConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import io.github.agentic.ai.api.chat.ToolDefinition;

/**
 * Shares immutable model-turn bindings between one agent's model and tool nodes.
 * Executable bindings remain process-local; checkpoints retain an identity and
 * declarations. A missing snapshot fails closed instead of executing a different turn's
 * tools.
 */
public final class AgentToolBindingStore {

	static final String SNAPSHOT_KEY = "argi.toolBindingSnapshot";

	private static final String SELECTION_KEY = "argi.toolBindingSelection";

	private static final String DYNAMIC_KEY = "argi.dynamicToolBindings";

	private final String contextKey = AgentToolBindingStore.class.getName() + ":" + UUID.randomUUID();

	private final Cache<String, AgentToolBindings> snapshots;

	/** Retains up to 4096 pending or rewindable model turns per compiled agent. */
	public AgentToolBindingStore() {
		this(4096);
	}

	/** Select a retention bound when assembling model and tool nodes directly. */
	public AgentToolBindingStore(long maximumSnapshots) {
		if (maximumSnapshots < 1) {
			throw new IllegalArgumentException("maximumSnapshots must be positive");
		}
		snapshots = Caffeine.newBuilder().maximumSize(maximumSnapshots).build();
	}

	void publish(RunnableConfig config, AgentToolBindings bindings) {
		config.context().put(contextKey, Objects.requireNonNull(bindings, "bindings must not be null"));
	}

	AgentToolBindings current(RunnableConfig config) {
		return (AgentToolBindings) config.context().get(contextKey);
	}

	void clear(RunnableConfig config) {
		config.context().remove(contextKey);
	}

	ChatMessage attach(ChatMessage message, AgentToolBindings bindings, AgentToolBindings configured) {
		if (bindings == null || message.toolCalls().isEmpty()) {
			return message;
		}
		String id = UUID.randomUUID().toString();
		snapshots.put(id, bindings);
		var metadata = new HashMap<>(message.metadata());
		metadata.put(SNAPSHOT_KEY, id);
		metadata.put(DYNAMIC_KEY, !bindings.usesOnly(configured));
		metadata.put(SELECTION_KEY, bindings.definitions().stream().map(definition -> {
			Map<String, Object> details = new HashMap<>();
			details.put("name", definition.name());
			details.put("description", definition.description());
			details.put("schema", definition.inputSchema());
			return details;
		}).toList());
		return message.mutate().metadata(metadata).build();
	}

	void restore(ChatMessage message, RunnableConfig config, AgentToolBindings configured) {
		// A turn without a snapshot must not inherit bindings from a prior tool
		// execution.
		clear(config);
		Object id = message.metadata().get(SNAPSHOT_KEY);
		if (id == null) {
			return;
		}
		AgentToolBindings bindings = id instanceof String key ? snapshots.getIfPresent(key) : null;
		if (bindings == null && Boolean.FALSE.equals(message.metadata().get(DYNAMIC_KEY))) {
			// Static bindings are reconstructed with the persisted selection, never
			// widened.
			bindings = configured.select(selection(message));
		}
		if (bindings == null) {
			throw new IllegalStateException("Tool binding snapshot is unavailable; regenerate the model turn "
					+ "before resuming tools after an agent rebuild or snapshot eviction");
		}
		publish(config, bindings);
	}

	private List<ToolDefinition> selection(ChatMessage message) {
		Object selection = message.metadata().get(SELECTION_KEY);
		if (!(selection instanceof List<?> definitions)) {
			throw new IllegalStateException("Checkpoint is missing its tool binding selection");
		}
		return definitions.stream().map(value -> {
			if (!(value instanceof Map<?, ?> definition) || !(definition.get("name") instanceof String name)
					|| (definition.get("description") != null && !(definition.get("description") instanceof String))
					|| !(definition.get("schema") instanceof String schema)) {
				throw new IllegalStateException("Checkpoint contains an invalid tool binding selection");
			}
			return new ToolDefinition(name, (String) definition.get("description"), schema);
		}).toList();
	}

}
