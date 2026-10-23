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
package io.github.agentic.ai.graph.observation.graph;

import io.github.agentic.ai.graph.observation.ObservationContentSanitizer;
import io.github.agentic.ai.graph.observation.ArgiKind;
import io.github.agentic.ai.graph.observation.graph.GraphObservationDocumentation.HighCardinalityKeyNames;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Default implementation of GraphObservationConvention. Provides standard observation
 * conventions for graph operations with configurable naming.
 *
 * @author XiaoYunTao
 * @since 2025/6/29
 */
public class DefaultGraphObservationConvention implements GraphObservationConvention {

	/** Default operation name for graph observations */
	public static final String DEFAULT_OPERATION_NAME = "argi.graph";

	private String name;

	private final boolean captureContent;

	private final int maxContentLength;

	/**
	 * Constructs a default convention with the default operation name.
	 */
	public DefaultGraphObservationConvention() {
		this(DEFAULT_OPERATION_NAME);
	}

	/**
	 * Constructs a convention with a custom operation name.
	 * @param name the custom operation name
	 */
	public DefaultGraphObservationConvention(String name) {
		this(name, false, ObservationContentSanitizer.DEFAULT_MAX_CONTENT_LENGTH);
	}

	public DefaultGraphObservationConvention(String name, boolean captureContent, int maxContentLength) {
		this.name = name;
		this.captureContent = captureContent;
		this.maxContentLength = maxContentLength;
	}

	@Override
	public String getName() {
		return this.name;
	}

	/**
	 * Generates a contextual name for the observation. Combines the operation name with
	 * the graph name if available.
	 */
	@Override
	@Nullable
	public String getContextualName(GraphObservationContext context) {
		if (StringUtils.hasText(context.getName())) {
			return "%s.%s".formatted(DEFAULT_OPERATION_NAME, context.getName());
		}
		return DEFAULT_OPERATION_NAME;
	}

	/**
	 * Provides low cardinality key values for metrics. Includes graph kind and name for
	 * grouping and filtering.
	 */
	@Override
	public KeyValues getLowCardinalityKeyValues(GraphObservationContext context) {
		return KeyValues.of(
				KeyValue.of(GraphObservationDocumentation.LowCardinalityKeyNames.ARGI_KIND,
						ArgiKind.GRAPH.getValue()),
				KeyValue.of(GraphObservationDocumentation.LowCardinalityKeyNames.GRAPH_NAME, context.getGraphName()));
	}

	/**
	 * Provides high cardinality key values for detailed analysis. Includes graph state
	 * and output information.
	 */
	@Override
	public KeyValues getHighCardinalityKeyValues(GraphObservationContext context) {
		if (!captureContent) {
			return KeyValues.empty();
		}
		KeyValues keyValues = KeyValues.of(KeyValue.of(HighCardinalityKeyNames.GRAPH_NODE_STATE,
				ObservationContentSanitizer.sanitizeState(context.getState(), maxContentLength)));
		if (context.getOutput() != null) {
			keyValues = keyValues.and(KeyValue.of(HighCardinalityKeyNames.GRAPH_NODE_OUTPUT,
					ObservationContentSanitizer.sanitizeText(context.getOutput(), maxContentLength)));
		}
		return keyValues;
	}

}
