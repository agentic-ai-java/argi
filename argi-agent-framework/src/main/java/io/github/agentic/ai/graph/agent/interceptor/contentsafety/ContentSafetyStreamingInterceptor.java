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
package io.github.agentic.ai.graph.agent.interceptor.contentsafety;

import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyAction;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyDecision;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyGate;
import io.github.agentic.ai.graph.agent.hook.contentsafety.ContentSafetyStage;
import io.github.agentic.ai.graph.agent.interceptor.ModelRequest;
import io.github.agentic.ai.graph.agent.interceptor.StreamingModelInterceptor;

/**
 * Strict streaming check for model egress. Incomplete secret prefixes are withheld, and
 * a completed secret is replaced before it is released. The final response text follows
 * the released deltas.
 */
public class ContentSafetyStreamingInterceptor implements StreamingModelInterceptor {

	static final String PENDING_KEY = "argi.contentSafety.pending";

	private final ContentSafetyGate gate;

	public ContentSafetyStreamingInterceptor(ContentSafetyGate gate) {
		this.gate = gate;
	}

	@Override
	public ModelRequest beforeStreamCall(ModelRequest request) {
		request.getContext().put(PENDING_KEY, new StringBuilder());
		return request;
	}

	@Override
	public ChatEvent onStreamChunk(ChatEvent chunk, ModelRequest request) {
		if (chunk.type() != ChatEvent.Type.TEXT_DELTA) {
			return chunk;
		}
		StringBuilder pending = (StringBuilder) request.getContext().computeIfAbsent(PENDING_KEY,
				key -> new StringBuilder());
		pending.append(chunk.text() == null ? "" : chunk.text());
		StringBuilder released = new StringBuilder();
		while (pending.length() > 0) {
			String buffered = pending.toString();
			ContentSafetyDecision decision = gate.inspect(ContentSafetyStage.MODEL_EGRESS, buffered);
			if (decision.action() == ContentSafetyAction.ALLOW) {
				int open = Math.min(Math.max(gate.openSuffix(buffered), 0), buffered.length());
				String release = buffered.substring(0, buffered.length() - open);
				pending.delete(0, release.length());
				released.append(release);
				break;
			}
			if (decision.action() == ContentSafetyAction.REDACT) {
				pending.setLength(0);
				released.append(decision.output());
				break;
			}
			int matchStart = decision.matchStart();
			int matchEnd = decision.matchEnd();
			if (matchStart < 0 || matchEnd <= matchStart || matchStart >= buffered.length()) {
				pending.setLength(0);
				released.append(decision.output());
				break;
			}
			matchEnd = Math.min(matchEnd, buffered.length());
			released.append(buffered, 0, matchStart).append(decision.output());
			String remainder = buffered.substring(matchEnd);
			pending.setLength(0);
			pending.append(remainder);
		}
		return released.isEmpty() ? null : ChatEvent.textDelta(released.toString());
	}

}
