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

import io.github.agentic.ai.api.chat.ChatResponse;

import java.util.Comparator;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * A recorded response and an immutable view of its plans in ordinal order. The recorder
 * validates their journal consistency on replay; constructing this data view does not
 * establish storage commitment, current ownership or permission to execute a tool.
 */
public record RecordedModelTurn(ChatResponse response, List<PlannedToolCall> plans) {

	public RecordedModelTurn {
		requireNonNull(response, "response cannot be null");
		plans = List.copyOf(plans).stream()
			.sorted(Comparator.comparingInt(plan -> plan.identity().ordinal()))
			.toList();
	}

}
