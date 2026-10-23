/*
 * Copyright 2024-2026 the original author or authors.
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
package io.github.agentic.ai.graph.observation.metric;

/**
 * Enumeration defining metric attributes for ARGI graph
 * observations.
 * Provides standardized attribute names for graph, node, and edge metrics. Used
 * for
 * tagging and categorizing observation metrics in monitoring systems.
 *
 * @author XiaoYunTao
 * @since 2025/6/28
 */
public enum ArgiObservationMetricAttributes {

	/**
	 * Attribute for the name of a graph operation.
	 */
	GRAPH_NAME("argi.graph.name"),

	/**
	 * Attribute indicating the success status of a graph operation.
	 */
	GRAPH_SUCCESS("argi.graph.success"),

	/**
	 * Attribute for the name of a graph node operation.
	 */
	GRAPH_NODE_NAME("argi.graph.node.name"),

	/**
	 * Attribute indicating the success status of a graph node operation.
	 */
	GRAPH_NODE_SUCCESS("argi.graph.node.success"),

	/**
	 * Attribute for the name of a graph edge operation.
	 */
	GRAPH_EDGE_NAME("argi.graph.edge.name"),

	/**
	 * Attribute indicating the success status of a graph edge operation.
	 */
	GRAPH_EDGE_SUCCESS("argi.graph.edge.success"),

	/**
	 * Attribute for Generative AI Prompt.
	 */
	GEN_AI_PROMPT("gen_ai.prompt"),

	/**
	 * Attribute for Generative AI Completion.
	 */
	GEN_AI_COMPLETION("gen_ai.completion"),

	/**
	 * Attribute for Langfuse Input.
	 */
	LANGFUSE_INPUT("langfuse.observation.input"),

	/**
	 * Attribute for Langfuse Output.
	 */
	LANGFUSE_OUTPUT("langfuse.observation.output");

	private final String value;

	/**
	 * Constructs a new ArgiObservationMetricAttributes with the
	 * specified
	 * value.
	 * 
	 * @param value the string representation of this attribute
	 */
	ArgiObservationMetricAttributes(String value) {
		this.value = value;
	}

	/**
	 * Gets the string value of this attribute.
	 * 
	 * @return the string representation of this attribute
	 */
	public String value() {
		return value;
	}

}
