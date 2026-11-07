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
package io.github.agentic.ai.agent.orchestration.hook.messages;

import io.github.agentic.ai.agent.budget.RequestCompactor;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.orchestration.hook.summarization.SummarizationHook;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.workflow.graph.RunnableConfig;

import java.util.List;
import java.util.Objects;

/**
 * Compresses a model request with the existing {@link SummarizationHook}.
 *
 * <p>
 * The adapter is the bridge between the neutral budget contract and the existing
 * compression capability. It does not summarize anything itself: it hands the
 * conversation to the hook and rebuilds the request with the messages the hook
 * returned.
 *
 * <p>
 * The behaviour of the hook is not changed. When the hook decides that no summary
 * is needed, or when the summary fails and the hook keeps the original history, the
 * original request is returned unchanged and the budget gate decides what to do with
 * it.
 */
public final class SummarizationRequestCompactor implements RequestCompactor {

	private final SummarizationHook hook;

	private final RunnableConfig config;

	/**
	 * Creates a compactor which summarizes without a run configuration.
	 * @param hook the summarization hook, must not be null
	 */
	public SummarizationRequestCompactor(SummarizationHook hook) {
		this(hook, null);
	}

	/**
	 * Creates a compactor which summarizes within a run.
	 * @param hook the summarization hook, must not be null
	 * @param config the run configuration, nullable
	 */
	public SummarizationRequestCompactor(SummarizationHook hook, RunnableConfig config) {
		this.hook = Objects.requireNonNull(hook, "hook must not be null");
		this.config = config;
	}

	@Override
	public ModelRequest compact(ModelRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		AgentCommand command = this.hook.beforeModel(request.getMessages(), this.config);
		List<ChatMessage> messages = command.getMessages();
		if (messages == null || messages.equals(request.getMessages())) {
			return request;
		}
		return ModelRequest.builder(request).messages(messages).build();
	}

}
