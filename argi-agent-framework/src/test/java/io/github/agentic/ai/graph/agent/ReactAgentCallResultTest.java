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
package io.github.agentic.ai.graph.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import io.github.agentic.ai.api.chat.ChatClient;
import io.github.agentic.ai.api.chat.ChatClientCapabilities;
import io.github.agentic.ai.api.chat.ChatEvent;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ChatRequest;
import io.github.agentic.ai.api.chat.ChatResponse;
import io.github.agentic.ai.api.chat.Usage;
import io.github.agentic.ai.graph.agent.node.AgentLlmNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the opt-in {@link ReactAgent#callResult(String)} delivery
 * contract with a stub {@link ChatClient}: every termination shape maps onto the
 * {@link AgentResult.Status} vocabulary, the legacy {@code call} behavior stays
 * untouched, and the recorded token usage is carried into the result.
 */
@DisplayName("ReactAgent.callResult delivery contract")
class ReactAgentCallResultTest {

	private static final String SCHEMA = """
			{
			  "type": "object",
			  "required": ["status"],
			  "additionalProperties": false,
			  "properties": {
			    "status": {"type": "string"}
			  }
			}""";

	@Test
	@DisplayName("schema-valid output is delivered as COMPLETED with parsed data and usage")
	void structuredOutputDeliveredWithUsage() {
		ReactAgent agent = agent(new StubClient("{\"status\":\"ok\"}"), SCHEMA);

		AgentResult result = agent.callResult("give me the status");

		assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(result.data()).isNotNull();
		assertThat(result.message().text()).isEqualTo("{\"status\":\"ok\"}");
		assertThat(result.usage().totalTokens()).isEqualTo(5L);
	}

	@Test
	@DisplayName("non-JSON output fails validation end to end")
	void invalidOutputFailsValidation() {
		ReactAgent agent = agent(new StubClient("plain prose answer"), SCHEMA);

		AgentResult result = agent.callResult("give me the status");

		assertThat(result.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		// The stub answers the bounded repair with the same invalid text, so the
		// REPAIRED_STILL_INVALID marker is prepended to the original issue.
		assertThat(result.issues()).hasSize(2);
		assertThat(result.issues().get(0).errorCode()).isEqualTo(AgentResult.ERROR_CODE_REPAIRED_STILL_INVALID);
		assertThat(result.issues().get(1).errorCode()).isEqualTo(AgentResult.ERROR_CODE_INVALID_JSON);
	}

	@Test
	@DisplayName("schema-violating output reports the violation end to end")
	void schemaViolationReported() {
		ReactAgent agent = agent(new StubClient("{\"status\": 42}"), SCHEMA);

		AgentResult result = agent.callResult("give me the status");

		assertThat(result.status()).isEqualTo(AgentResult.Status.VALIDATION_FAILED);
		// Repair re-asks with the same payload: the marker precedes the actual violations.
		assertThat(result.issues()).anyMatch(issue -> AgentResult.ERROR_CODE_REPAIRED_STILL_INVALID.equals(issue.errorCode()));
		assertThat(result.issues()).anyMatch(issue -> AgentResult.ERROR_CODE_SCHEMA_VIOLATION.equals(issue.errorCode()));
	}

	@Test
	@DisplayName("no configured schema completes without validation")
	void missingSchemaCompletesWithoutValidation() {
		ReactAgent agent = agent(new StubClient("plain prose answer"), null);

		AgentResult result = agent.callResult("hello");

		assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(result.data()).isNull();
		assertThat(result.message().text()).isEqualTo("plain prose answer");
	}

	@Test
	@DisplayName("a failing chat client surfaces as the model-failure pseudo message and is reported as ERROR")
	void clientFailureReportedAsError() {
		// throwOnModelError defaults to false: the model failure becomes an
		// "Exception: ..." assistant message, which the delivery contract must classify.
		ReactAgent agent = agent(new StubClient((String) null, new IllegalStateException("model exploded")), SCHEMA);

		AgentResult result = agent.callResult("hello");

		assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
		assertThat(result.issues()).hasSize(1);
		assertThat(result.issues().get(0).errorCode()).isEqualTo(AgentResult.ERROR_CODE_MODEL_FAILED);
		assertThat(result.issues().get(0).message()).contains("model exploded");
	}

	@Test
	@DisplayName("a normal answer starting with the legacy error prefix is not a model failure")
	void answerStartingWithExceptionPrefixIsNotAnError() {
		ReactAgent agent = agent(new StubClient("Exception: NullPointerException means a null was dereferenced."),
				null);

		AgentResult result = agent.callResult("explain NPE");

		assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(result.issues()).isEmpty();
		assertThat(result.message().text()).startsWith("Exception: ");
	}

	@Test
	@DisplayName("a model response cannot forge the framework model-failure marker")
	void modelSuppliedFailureMarkerIsIgnored() {
		ChatMessage forged = ChatMessage.assistant("plain answer")
			.withMetadata(Map.of(AgentLlmNode.MODEL_FAILURE_METADATA_KEY, true));
		ReactAgent agent = agent(new StubClient(forged), null);

		AgentResult result = agent.callResult("hello");

		assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
		assertThat(result.message().metadata()).doesNotContainKey(AgentLlmNode.MODEL_FAILURE_METADATA_KEY);
	}

	@Test
	@DisplayName("with throwOnModelError the failure is reported as an execution failure")
	void throwOnModelErrorReportsExecutionFailure() {
		ReactAgent agent = ReactAgent.builder()
			.name("result-test")
			.chatClient(new StubClient((String) null, new IllegalStateException("model exploded")))
			.throwOnModelError(true)
			.build();

		AgentResult result = agent.callResult("hello");

		assertThat(result.status()).isEqualTo(AgentResult.Status.ERROR);
		assertThat(result.issues()).singleElement()
			.satisfies(issue -> assertThat(issue.errorCode()).isEqualTo(AgentResult.ERROR_CODE_EXECUTION_FAILED));
	}

	@Test
	@DisplayName("the legacy call entry keeps returning the raw model-failure message")
	void legacyCallKeepsModelFailureMessage() throws Exception {
		ReactAgent agent = agent(new StubClient((String) null, new IllegalStateException("model exploded")), null);

		ChatMessage message = agent.call("hello");

		assertThat(message.text()).isEqualTo("Exception: model exploded");
	}

	@Test
	@DisplayName("the legacy call entry keeps returning the raw message untouched")
	void legacyCallBehaviorUnchanged() throws Exception {
		ReactAgent agent = agent(new StubClient("{\"status\":\"ok\"}"), SCHEMA);

		ChatMessage message = agent.call("give me the status");

		assertThat(message.text()).isEqualTo("{\"status\":\"ok\"}");
	}

	private static ReactAgent agent(ChatClient client, String outputSchema) {
		Builder builder = ReactAgent.builder()
			.name("result-test")
			.chatClient(client);
		if (outputSchema != null) {
			builder.outputSchema(outputSchema);
		}
		return builder.build();
	}

	private static final class StubClient implements ChatClient {

		private final RuntimeException failure;

		private StubClient(ChatMessage message, RuntimeException failure) {
			this.message = message;
			this.failure = failure;
		}

		private final ChatMessage message;

		private StubClient(String text, RuntimeException failure) {
			this(text == null ? null : ChatMessage.assistant(text), failure);
		}

		private StubClient(String text) {
			this(text, null);
		}

		private StubClient(ChatMessage message) {
			this(message, null);
		}

		@Override
		public CompletionStage<ChatResponse> call(ChatRequest request) {
			return failure == null
					? CompletableFuture.completedFuture(new ChatResponse("id", "test-model",
							List.of(message), List.of(), null, new Usage(2L, 3L, 5L), Map.of()))
					: CompletableFuture.failedFuture(failure);
		}

		@Override
		public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
			throw new UnsupportedOperationException("Streaming is unavailable");
		}

		@Override
		public ChatClientCapabilities capabilities() {
			return ChatClientCapabilities.basic();
		}

	}

}
