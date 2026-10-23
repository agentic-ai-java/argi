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
package io.github.agentic.ai.graph.observation.edge;

import io.github.agentic.ai.graph.observation.ArgiKind;
import io.github.agentic.ai.graph.observation.ObservationContentSanitizer;
import io.github.agentic.ai.graph.observation.edge.GraphEdgeObservationDocumentation.HighCardinalityKeyNames;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Default implementation of GraphEdgeObservationConvention. Provides standard observation
 * conventions for graph edge operations with configurable naming.
 *
 * @author XiaoYunTao
 * @since 2025/6/29
 */
public class DefaultGraphEdgeObservationConvention implements GraphEdgeObservationConvention {

	/** Default operation name for graph edge observations */
	public static final String DEFAULT_OPERATION_NAME = "argi.graph.edge";

	private String name;

	private final boolean captureContent;

	private final int maxContentLength;

	/**
	 * Constructs a default convention with the default operation name.
	 */
	public DefaultGraphEdgeObservationConvention() {
		this(DEFAULT_OPERATION_NAME);
	}

	/**
	 * Constructs a convention with a custom operation name.
	 * @param name the custom operation name
	 */
	public DefaultGraphEdgeObservationConvention(String name) {
		this(name, false, ObservationContentSanitizer.DEFAULT_MAX_CONTENT_LENGTH);
	}

	public DefaultGraphEdgeObservationConvention(String name, boolean captureContent, int maxContentLength) {
		this.name = name;
		this.captureContent = captureContent;
		this.maxContentLength = maxContentLength;
	}

	@Override
	public String getName() {
		return this.name;
	}

	/**
	 * Generates a contextual name for the edge observation. Combines the operation name
	 * with the edge name if available.
	 */
	@Override
	@Nullable
	public String getContextualName(GraphEdgeObservationContext context) {
		if (StringUtils.hasText(context.getName())) {
			return "%s.%s".formatted(DEFAULT_OPERATION_NAME, context.getName());
		}
		return DEFAULT_OPERATION_NAME;
	}

	/**
	 * Provides low cardinality key values for edge metrics. Includes graph kind and edge
	 * name for grouping and filtering.
	 */
	@Override
	public KeyValues getLowCardinalityKeyValues(GraphEdgeObservationContext context) {
		return KeyValues.of(
				KeyValue.of(GraphEdgeObservationDocumentation.LowCardinalityKeyNames.ARGI_KIND,
						ArgiKind.GRAPH.getValue()),
				KeyValue.of(GraphEdgeObservationDocumentation.LowCardinalityKeyNames.GRAPH_NAME,
						context.getGraphEdgeName()));
	}

	/**
	 * Provides high cardinality key values for detailed edge analysis. Includes edge
	 * state and next node information.
	 */
	@Override
	public KeyValues getHighCardinalityKeyValues(GraphEdgeObservationContext context) {
		if (!captureContent) {
			return KeyValues.empty();
		}
		KeyValues keyValues = KeyValues.of(KeyValue.of(HighCardinalityKeyNames.GRAPH_NODE_STATE,
				ObservationContentSanitizer.sanitizeState(context.getState(), maxContentLength)));

		if (null != context.getNextNode()) {
			keyValues = keyValues.and(KeyValue.of(HighCardinalityKeyNames.GRAPH_NODE_OUTPUT,
					ObservationContentSanitizer.sanitizeText(context.getNextNode(), maxContentLength)));
		}
		return keyValues;
	}

}
