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
package io.github.agentic.ai.agent.lifecycle;

import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.api.chat.ChatMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A model call in the neutral lifecycle contract.
 *
 * <p>
 * The invocation is immutable: a restricted transformer may replace the request, but
 * neither the identity nor the model turn identity may change. The request keeps the
 * trusted execution context of the run; the neutral contract never exposes a way to
 * rewrite that context.
 *
 * @param identity the identity of the work this call belongs to, never null
 * @param modelTurn the model turn identity, never null
 * @param request the model request, never null
 */
public record ModelCallInvocation(LifecycleIdentity identity, ModelTurnId modelTurn, ModelRequest request) {

	/**
	 * Creates a model call invocation.
	 * @param identity the identity of the work this call belongs to, must not be null
	 * @param modelTurn the model turn identity, must not be null
	 * @param request the model request, must not be null
	 */
	public ModelCallInvocation {
		Objects.requireNonNull(identity, "identity must not be null");
		Objects.requireNonNull(modelTurn, "modelTurn must not be null");
		Objects.requireNonNull(request, "request must not be null");
		request = readOnlySnapshot(request);
	}

	/**
	 * Returns a genuinely read-only snapshot of the request. Two layers protect the
	 * event: the snapshot is taken from the caller's request, so later changes to the
	 * caller's collections are invisible; and the snapshot's collection accessors are
	 * immutable views, so an extension cannot rewrite what subsequent extensions observe
	 * (clearing the message list or mutating the context map fails). Transforming
	 * extensions rebuild a replacement through {@link ModelRequest#builder(ModelRequest)};
	 * the executor keeps running on the request it holds, so the event never becomes a
	 * mutation channel into dispatch.
	 */
	private static ModelRequest readOnlySnapshot(ModelRequest request) {
		ModelRequest snapshot = ModelRequest.builder(request)
				.messages(List.copyOf(request.getMessages()))
				.context(Map.copyOf(request.getContext()))
				.attributes(Map.copyOf(request.getAttributes()))
				.build();
		return new ReadOnlyModelRequest(snapshot);
	}

	/**
	 * A request view whose editable collections are exposed as immutable. The framework
	 * keeps per-subscription {@link ModelRequest} copies mutable by design (subscription
	 * isolation); the lifecycle event is the read-only surface on top of a snapshot.
	 */
	private static final class ReadOnlyModelRequest extends ModelRequest {

		private ReadOnlyModelRequest(ModelRequest source) {
			super(source.getSystemMessage(), source.getMessages(), source.getOptions(), source.getChatClient(),
					source.getToolRegistry(), source.getTools(), source.hasToolSelection(),
					source.getDynamicToolRegistry(), source.getToolDescriptions(), source.getContext(),
					source.getAttributes(), source.getTrustedExecutionContext().orElse(null));
		}

		@Override
		public List<ChatMessage> getMessages() {
			return Collections.unmodifiableList(super.getMessages());
		}

		@Override
		public Map<String, Object> getContext() {
			return Collections.unmodifiableMap(super.getContext());
		}

	}

}
