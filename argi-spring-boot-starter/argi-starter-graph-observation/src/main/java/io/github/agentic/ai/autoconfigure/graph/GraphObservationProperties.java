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
package io.github.agentic.ai.autoconfigure.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Graph observation functionality.
 *
 * @author sixiyida
 * @since 2025/7/3
 */
@ConfigurationProperties(prefix = GraphObservationProperties.CONFIG_PREFIX)
public class GraphObservationProperties {

	public static final String CONFIG_PREFIX = "argi.graph.observation";

	/**
	 * Whether to enable graph observation functionality.
	 */
	private boolean enabled = true;

	/**
	 * Whether to attach raw prompt, completion, and graph state content to
	 * high-cardinality observation attributes.
	 */
	private boolean captureContent = false;

	/**
	 * Maximum length for each captured content value before truncation.
	 */
	private int maxContentLength = 1000;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isCaptureContent() {
		return captureContent;
	}

	public void setCaptureContent(boolean captureContent) {
		this.captureContent = captureContent;
	}

	public int getMaxContentLength() {
		return maxContentLength;
	}

	public void setMaxContentLength(int maxContentLength) {
		this.maxContentLength = maxContentLength;
	}

}
