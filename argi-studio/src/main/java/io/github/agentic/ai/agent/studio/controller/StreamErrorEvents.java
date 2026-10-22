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

package io.github.agentic.ai.agent.studio.controller;

import io.github.agentic.ai.agent.studio.dto.StreamErrorResponse;

import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds the {@code error} SSE event shared by the agent and graph streams. */
final class StreamErrorEvents {

	static final String EVENT_NAME = "error";

	static final String FALLBACK_JSON = "{\"error\":true,\"errorType\":\"InternalError\",\"errorMessage\":\"Internal error occurred\"}";

	private static final Logger log = LoggerFactory.getLogger(StreamErrorEvents.class);

	private StreamErrorEvents() {
	}

	static ServerSentEvent<String> of(ObjectMapper mapper, Throwable failure) {
		String data;
		try {
			data = mapper.writeValueAsString(StreamErrorResponse.of(failure));
		}
		catch (Exception e) {
			log.error("Failed to serialize stream error event", e);
			data = FALLBACK_JSON;
		}
		return ServerSentEvent.<String>builder().event(EVENT_NAME).data(data).build();
	}

}
