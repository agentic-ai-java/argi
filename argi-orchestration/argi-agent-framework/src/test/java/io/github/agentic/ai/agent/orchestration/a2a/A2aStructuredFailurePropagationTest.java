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
package io.github.agentic.ai.agent.orchestration.a2a;

import io.github.agentic.ai.workflow.graph.CompileConfig;
import io.github.agentic.ai.workflow.graph.CompiledGraph;
import io.github.agentic.ai.workflow.graph.GraphResponse;
import io.github.agentic.ai.workflow.graph.NodeOutput;
import io.github.agentic.ai.workflow.graph.OverAllState;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import io.github.agentic.ai.workflow.graph.StateGraph;
import io.github.agentic.ai.workflow.graph.action.AsyncNodeActionWithConfig;
import io.github.agentic.ai.workflow.graph.streaming.StreamingOutput;

import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #213 (OPT-22): A2A transport and parsing failures must propagate as structured
 * errors instead of an ordinary text chunk followed by a normal completion. Each failure
 * class carries its own exception type, an error path emits at most one terminal state,
 * text already delivered before the failure keeps its identity, and normal remote text
 * that merely starts with {@code Error:} is never treated as a control signal.
 */
class A2aStructuredFailurePropagationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	// ==================== Streaming: transport failures ====================

	@Test
	void httpFailureFailsTheStreamWithTransportErrorInsteadOfCompleting() throws Exception {
		HttpServer server = startServer(500, "application/json", "{}");
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(A2aTransportException.class);
					assertThat(error.getMessage()).contains("500");
					// legacy callers catching the historic exception type keep working
					assertThat(error).isInstanceOf(IllegalStateException.class);
				})
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void missingAgentUrlFailsTheStreamWithTransportError() throws Exception {
		StepVerifier.create(streamingFlux(null))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(A2aTransportException.class)
				.hasMessageContaining("AgentCard.url is empty"))
			.verify(TIMEOUT);
	}

	// ==================== Streaming: protocol failures ====================

	@Test
	void corruptSseFrameFailsTheStreamAfterTheChunksAlreadyDelivered() throws Exception {
		HttpServer server = startServer(200, "text/event-stream",
				"data: " + artifactFrame("partial") + "\n\ndata: {not valid json\n\n");
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("partial"))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(A2aProtocolException.class);
					assertThat(error.getMessage()).contains("not valid JSON");
				})
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void jsonRpcErrorFrameFailsTheStreamWithProtocolError() throws Exception {
		HttpServer server = startServer(200, "text/event-stream",
				"data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}\n\n");
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(A2aProtocolException.class);
					assertThat(error.getMessage()).contains("Method not found").contains("-32601");
				})
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void streamEndingWithoutAnyTerminalStateFailsWithProtocolError() throws Exception {
		HttpServer server = startServer(200, "text/event-stream",
				("data: " + artifactFrame("orphan") + "\n\n"));
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("orphan"))
				.expectErrorSatisfies(
						error -> assertThat(error).isInstanceOf(A2aProtocolException.class)
							.hasMessageContaining("terminal"))
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void nonSseBodyThatIsNotJsonFailsTheStreamWithProtocolError() throws Exception {
		HttpServer server = startServer(200, "application/json", "not-json-at-all");
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(
						error -> assertThat(error).isInstanceOf(A2aProtocolException.class))
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	// ==================== Streaming: remote task failures ====================

	@Test
	void remoteFailedStatusFailsTheStreamWithRemoteTaskError() throws Exception {
		HttpServer server = startServer(200, "text/event-stream",
				("data: " + statusFrame("failed", "tool crashed") + "\n\n"));
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(A2aRemoteTaskException.class);
					assertThat(((A2aRemoteTaskException) error).taskState()).isEqualTo("failed");
					assertThat(error.getMessage()).contains("tool crashed");
				})
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void remoteCanceledStatusFailsTheStreamWithRemoteTaskError() throws Exception {
		HttpServer server = startServer(200, "text/event-stream",
				("data: " + statusFrame("canceled", null) + "\n\n"));
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(
						error -> assertThat(((A2aRemoteTaskException) error).taskState()).isEqualTo("canceled"))
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void remoteFailedSingleBodyResponseFailsTheStreamWithRemoteTaskError() throws Exception {
		HttpServer server = startServer(200, "application/json",
				"{\"jsonrpc\":\"2.0\",\"id\":\"1\"," + taskResult("failed", "late rejection") + "}");
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(A2aRemoteTaskException.class);
					assertThat(error.getMessage()).contains("late rejection");
				})
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void remoteFailedTaskObjectIsNotMisclassifiedAsAProtocolError() throws Exception {
		// A single-body response returns a Task object (kind:"task"), not a status-update:
		// the failed state must still classify as a remote task failure.
		HttpServer server = startServer(200, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":\"1\","
				+ taskResult("failed", "task object exploded") + "}");
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(A2aRemoteTaskException.class);
					assertThat(((A2aRemoteTaskException) error).taskState()).isEqualTo("failed");
					assertThat(error.getMessage()).contains("task object exploded");
				})
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void completedTaskObjectWithArtifactsDeliversOrdinaryText() throws Exception {
		HttpServer server = startServer(200, "application/json",
				"{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"task\","
						+ "\"status\":{\"state\":\"completed\"},"
						+ "\"artifacts\":[{\"parts\":[{\"text\":\"via artifacts\"}]}]}}");
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("via artifacts"))
				.assertNext(GraphResponse::isDone)
				.verifyComplete();
		}
		finally {
			server.stop(0);
		}
	}

	// ==================== Streaming: what must NOT be treated as failure ====================

	@Test
	void remoteTextStartingWithErrorIsDeliveredAsOrdinaryContent() throws Exception {
		HttpServer server = startServer(200, "text/event-stream",
				("data: " + artifactFrame("Error: this is merely the remote answer") + "\n\ndata: [DONE]\n\n"));
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response))
					.isEqualTo("Error: this is merely the remote answer"))
				.assertNext(GraphResponse::isDone)
				.verifyComplete();
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void completedStatusUpdateTerminatesTheStreamWithoutDoneSentinelOrError() throws Exception {
		HttpServer server = startServer(200, "text/event-stream", ("data: %s\n\ndata: %s\n\n")
			.formatted(artifactFrame("done via status"), statusFrame("completed", null)));
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("done via status"))
				.assertNext(GraphResponse::isDone)
				.verifyComplete();
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void completedTaskFrameInSseStreamDeliversItsArtifactsBeforeTerminating() throws Exception {
		// The terminal frame may itself carry the task's final artifacts; its output must
		// be extracted before terminating, otherwise only done is emitted and the final
		// result is an empty reply.
		HttpServer server = startServer(200, "text/event-stream",
				("data: %s\n\n").formatted("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"task\","
						+ "\"status\":{\"state\":\"completed\"},"
						+ "\"artifacts\":[{\"parts\":[{\"text\":\"answer\"}]}]}}"));
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("answer"))
				.assertNext(response -> {
					assertThat(response.isDone()).isTrue();
					assertThat(((Map<?, ?>) response.resultValue().orElseThrow()).get("reply")).isEqualTo("answer");
				})
				.verifyComplete();
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void finalStatusUpdateTerminatesTheStreamWithoutProtocolError() throws Exception {
		// input-required with the final flag is a normal interaction boundary: the stream
		// must terminate successfully instead of failing with a missing-terminal-state
		// error at EOF.
		HttpServer server = startServer(200, "text/event-stream",
				("data: %s\n\n").formatted(finalStatusFrame("input-required")));
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("Agent State: input-required"))
				.assertNext(response -> assertThat(response.isDone()).isTrue())
				.verifyComplete();
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void finalFlagDoesNotMaskARemoteTaskFailure() throws Exception {
		HttpServer server = startServer(200, "text/event-stream",
				("data: %s\n\n").formatted(finalStatusFrame("failed")));
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(
						error -> assertThat(((A2aRemoteTaskException) error).taskState()).isEqualTo("failed"))
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void rejectedStatusWithFinalFlagFailsTheStreamWithRemoteTaskError() throws Exception {
		// A rejected task is a terminal failure like failed/canceled; the final flag must
		// not let it complete successfully with placeholder text.
		HttpServer server = startServer(200, "text/event-stream",
				("data: %s\n\n").formatted(finalStatusFrame("rejected")));
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(
						error -> assertThat(((A2aRemoteTaskException) error).taskState()).isEqualTo("rejected"))
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void standaloneMessageResultTerminatesTheStreamWithoutProtocolError() throws Exception {
		// An agent may answer with a bare Message instead of a tracked Task; the Message
		// by itself completes the interaction, without a final flag or the [DONE] sentinel.
		HttpServer server = startServer(200, "text/event-stream",
				("data: %s\n\n").formatted(messageResultFrame("direct answer")));
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("direct answer"))
				.assertNext(response -> {
					assertThat(response.isDone()).isTrue();
					assertThat(((Map<?, ?>) response.resultValue().orElseThrow()).get("reply"))
						.isEqualTo("direct answer");
				})
				.verifyComplete();
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void sseCommentsUnknownFieldsAndUnknownEventsAreIgnored() throws Exception {
		HttpServer server = startServer(200, "text/event-stream", ": keep-alive comment\n\n"
				+ "event: status\nid: 42\n"
				+ "data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"some-future-extension\"}}\n\n"
				+ "data: " + artifactFrame("visible") + "\n\n" + "data: [DONE]\n\n");
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("visible"))
				.assertNext(GraphResponse::isDone)
				.verifyComplete();
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void multiDataLineFrameIsJoinedBeforeParsing() throws Exception {
		// A newline inside JSON is ordinary whitespace, so an SSE frame split across two
		// data field lines must be reassembled with a newline before parsing.
		HttpServer server = startServer(200, "text/event-stream",
				"data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\n" + "data: \"result\":{\"kind\":\"artifact-update\","
						+ "\"artifact\":{\"parts\":[{\"text\":\"joined\"}]}}}\n\n" + "data: [DONE]\n\n");
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("joined"))
				.assertNext(GraphResponse::isDone)
				.verifyComplete();
		}
		finally {
			server.stop(0);
		}
	}

	// ==================== Non-streaming: structured failures ====================

	@Test
	void nonStreamingHttpFailureThrowsTransportException() throws Exception {
		HttpServer server = startServer(500, "application/json", "{}");
		try {
			A2aException error = assertThrows(A2aException.class, () -> nonStreamingCall(server));
			assertThat(error).isInstanceOf(A2aTransportException.class);
			assertThat(error).isInstanceOf(IllegalStateException.class);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void nonStreamingRemoteFailedThrowsRemoteTaskException() throws Exception {
		HttpServer server = startServer(200, "application/json",
				"{\"jsonrpc\":\"2.0\",\"id\":\"1\"," + taskResult("failed", "remote exploded") + "}");
		try {
			A2aRemoteTaskException error = assertThrows(A2aRemoteTaskException.class,
					() -> nonStreamingCall(server));
			assertThat(error.taskState()).isEqualTo("failed");
			assertThat(error.getMessage()).contains("remote exploded");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void nonStreamingRemoteCanceledThrowsRemoteTaskException() throws Exception {
		HttpServer server = startServer(200, "application/json",
				"{\"jsonrpc\":\"2.0\",\"id\":\"1\"," + taskResult("canceled", null) + "}");
		try {
			A2aRemoteTaskException error = assertThrows(A2aRemoteTaskException.class,
					() -> nonStreamingCall(server));
			assertThat(error.taskState()).isEqualTo("canceled");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void nonStreamingUnparsableBodyThrowsProtocolException() throws Exception {
		HttpServer server = startServer(200, "application/json", "garbage");
		try {
			assertThrows(A2aProtocolException.class, () -> nonStreamingCall(server));
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void nonStreamingRemoteTextStartingWithErrorIsAnOrdinaryResult() throws Exception {
		HttpServer server = startServer(200, "application/json",
				"{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"artifact-update\",\"artifact\":"
						+ "{\"parts\":[{\"text\":\"Error: just the answer\"}]}}}");
		try {
			Map<String, Object> result = nonStreamingCall(server);
			assertThat(result.get("reply")).isEqualTo("Error: just the answer");
		}
		finally {
			server.stop(0);
		}
	}

	// ==================== Streaming: transport interruption and empty responses ====================

	@Test
	void transportInterruptionAfterPartialTextFailsWithTransportError() throws Exception {
		// The server declares more bytes than it sends and closes: the body is truncated
		// mid-stream, which is a transport failure, not a clean end of the event stream.
		byte[] bytes = ("data: " + artifactFrame("interrupted") + "\n\n").getBytes(StandardCharsets.UTF_8);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, bytes.length + 100);
			exchange.getResponseBody().write(bytes);
			exchange.getResponseBody().flush();
			exchange.close();
		});
		server.start();
		try {
			StepVerifier.create(streamingFlux(server))
				.assertNext(response -> assertThat(streamingText(response)).isEqualTo("interrupted"))
				.expectErrorSatisfies(
						error -> assertThat(error).isInstanceOf(A2aTransportException.class))
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void emptyEventStreamFailsWithMissingTerminalError() throws Exception {
		HttpServer server = startServer(200, "text/event-stream", "");
		try {
			StepVerifier.create(streamingFlux(server))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(A2aProtocolException.class);
					assertThat(error.getMessage()).contains("terminal");
				})
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	// ==================== Upper layer: the graph surfaces the structured failure ====================

	@Test
	void upperLayerGraphStreamSurfacesTheStructuredFailure() throws Exception {
		HttpServer server = startServer(500, "application/json", "{}");
		try {
			A2aNodeActionWithConfig node = new A2aNodeActionWithConfig(agentCard(baseUrl(server)), "remote-agent",
					false, "reply", "instruction", true);
			StateGraph graph = new StateGraph("a2a-upper", HashMap::new);
			graph.addNode("remote", AsyncNodeActionWithConfig.node_async(node));
			graph.addEdge(StateGraph.START, "remote");
			graph.addEdge("remote", StateGraph.END);
			CompiledGraph compiled = graph.compile(CompileConfig.builder().build());

			StepVerifier
				.create(compiled.stream(Map.of(), RunnableConfig.builder().threadId("upper-layer").build()))
				// the __START__ marker output precedes the remote node's failure
				.expectNextCount(1)
				.expectErrorSatisfies(
						error -> assertThat(error).isInstanceOf(A2aTransportException.class))
				.verify(TIMEOUT);
		}
		finally {
			server.stop(0);
		}
	}

	// ==================== helpers ====================

	@SuppressWarnings("unchecked")
	private static Flux<GraphResponse<NodeOutput>> streamingFlux(HttpServer server) throws Exception {
		String url = server == null ? null : baseUrl(server);
		A2aNodeActionWithConfig node = new A2aNodeActionWithConfig(agentCard(url), "remote-agent", false, "reply",
				"instruction", true);
		Map<String, Object> result = node.apply(new OverAllState(),
				RunnableConfig.builder().threadId("structured-failure").build());
		return (Flux<GraphResponse<NodeOutput>>) result.get("reply");
	}

	private static Map<String, Object> nonStreamingCall(HttpServer server) throws Exception {
		A2aNodeActionWithConfig node = new A2aNodeActionWithConfig(agentCard(baseUrl(server)), "remote-agent", false,
				"reply", "instruction", false);
		return node.apply(new OverAllState(), RunnableConfig.builder().threadId("structured-failure").build());
	}

	private static String streamingText(GraphResponse<NodeOutput> response) {
		assertTrue(!response.isDone());
		StreamingOutput<?> output = (StreamingOutput<?>) response.getOutput().getNow(null);
		return output.text();
	}

	private static String artifactFrame(String text) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"artifact-update\",\"artifact\":"
				+ "{\"parts\":[{\"text\":\"" + text + "\"}]}}}";
	}

	private static String statusFrame(String state, String messageText) {
		String message = messageText == null ? ""
				: ",\"message\":{\"parts\":[{\"text\":\"" + messageText + "\"}]}";
		return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"status-update\",\"status\":"
				+ "{\"state\":\"" + state + "\"" + message + "}}}";
	}

	/** A status-update event carrying the {@code final} flag that ends the stream. */
	private static String finalStatusFrame(String state) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"status-update\",\"final\":true,"
				+ "\"status\":{\"state\":\"" + state + "\"}}}";
	}

	/** A JSON-RPC result that is a standalone A2A Message answering the request directly. */
	private static String messageResultFrame(String text) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"message\",\"messageId\":\"m-1\","
				+ "\"role\":\"agent\",\"parts\":[{\"kind\":\"text\",\"text\":\"" + text + "\"}]}}";
	}

	/** A JSON-RPC envelope whose result is the Task object a single-body response returns. */
	private static String taskResult(String state, String messageText) {
		String message = messageText == null ? ""
				: ",\"message\":{\"parts\":[{\"text\":\"" + messageText + "\"}]}";
		return "\"result\":{\"id\":\"task-1\",\"contextId\":\"ctx-1\",\"kind\":\"task\","
				+ "\"status\":{\"state\":\"" + state + "\"" + message + "}}";
	}

	private static HttpServer startServer(int status, String contentType, String body) throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		return server;
	}

	private static String baseUrl(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
	}

	private static AgentCardWrapper agentCard(String url) {
		AgentCard agentCard = mock(AgentCard.class);
		when(agentCard.name()).thenReturn("test-agent");
		when(agentCard.url()).thenReturn(url);
		return new AgentCardWrapper(agentCard);
	}

}
