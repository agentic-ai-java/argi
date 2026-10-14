/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.agent.planner;

import java.util.Objects;

import io.github.agentic.ai.graph.agent.Agent;
import io.github.agentic.ai.api.tool.Tool;

/**
 * Application-owned capability descriptor. The target is an opaque, exact registry key
 * (for example {@code tool:search} or {@code agent:writer}). Register only capabilities
 * already resolved and authorized by the application. Descriptors deliberately carry no
 * callback: planning cannot invoke a registered capability or expand its contracts.
 */
public record PlanCapability(String executionTarget, String description, String inputSchema, String outputSchema) {

	/**
	 * Snapshot an already authorized tool's actual definition without retaining its
	 * callback.
	 */
	public static PlanCapability tool(Tool tool, String outputSchema) {
		var definition = Objects.requireNonNull(tool, "tool").definition();
		return new PlanCapability("tool:" + definition.name(), definition.description(), definition.inputSchema(),
				outputSchema);
	}

	/** Snapshot an already authorized agent without calling it or compiling its graph. */
	public static PlanCapability agent(Agent agent, String inputSchema, String outputSchema) {
		Objects.requireNonNull(agent, "agent");
		return new PlanCapability("agent:" + agent.name(), agent.description(), inputSchema, outputSchema);
	}

}
