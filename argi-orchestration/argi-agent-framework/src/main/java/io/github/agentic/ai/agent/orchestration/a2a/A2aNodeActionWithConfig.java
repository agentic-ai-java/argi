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

import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.MessageProvenance;

import io.github.agentic.ai.workflow.graph.CompileConfig;
import io.github.agentic.ai.workflow.graph.GraphResponse;
import io.github.agentic.ai.workflow.graph.NodeOutput;
import io.github.agentic.ai.workflow.graph.OverAllState;
import io.github.agentic.ai.workflow.graph.RunnableConfig;
import io.github.agentic.ai.workflow.graph.action.NodeActionWithConfig;
import io.github.agentic.ai.workflow.graph.streaming.OutputType;
import io.github.agentic.ai.workflow.graph.streaming.StreamingOutput;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import io.github.agentic.ai.workflow.graph.utils.PromptTemplate;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceContext;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceOperation;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceSpan;
import io.github.agentic.ai.workflow.graph.observation.trace.AgentTracer;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import static java.lang.String.format;

public class A2aNodeActionWithConfig implements NodeActionWithConfig {

	/** Default {@link RunnableConfig} metadata key holding the caller's user id. */
	public static final String DEFAULT_USER_ID_METADATA_KEY = "userId";

	/**
	 * Default time a streaming call waits for the subscriber to request the next event.
	 * The producer has no internal queue: it reads the remote stream only as fast as the
	 * subscriber consumes, so this is the bound on how long a stalled consumer may park a
	 * worker before the stream fails with {@link A2aBackpressureException}. Sixty seconds
	 * absorbs slow checkpoint writes and paused clients while still turning a dead
	 * consumer into an explicit failure instead of an unbounded buffer.
	 */
	public static final Duration DEFAULT_STREAM_DEMAND_TIMEOUT = Duration.ofSeconds(60);

	private final String agentName;

	private final AgentCardWrapper agentCard;

	private final boolean includeContents;

	private final String outputKeyToParent;

	private final boolean streaming;

	private final String instruction;

	private boolean shareState;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private CompileConfig parentCompileConfig;

	private AgentTracer agentTracer;

	private String userIdMetadataKey = DEFAULT_USER_ID_METADATA_KEY;

	private Duration streamDemandTimeout = DEFAULT_STREAM_DEMAND_TIMEOUT;

	public A2aNodeActionWithConfig(AgentCardWrapper agentCard, String agentName, boolean includeContents,
			String outputKeyToParent, String instruction, boolean streaming) {
		this.agentName = agentName;
		this.agentCard = agentCard;
		this.includeContents = includeContents;
		this.outputKeyToParent = outputKeyToParent;
		this.streaming = streaming;
		this.instruction = instruction;
		this.shareState = false;
	}

	/**
	 * Create the node action with optional cross-agent trace correlation. The tracer is an
	 * explicit opt-in; with a null tracer the historical behavior is preserved exactly and
	 * no observation is emitted and no trace metadata is added to A2A requests.
	 * @param agentCard the remote agent card
	 * @param agentName the local agent name
	 * @param includeContents whether to include contents
	 * @param outputKeyToParent the output key written back to the parent state
	 * @param instruction the instruction template
	 * @param streaming whether to use the streaming endpoint
	 * @param agentTracer the cross-agent tracer, may be null
	 */
	public A2aNodeActionWithConfig(AgentCardWrapper agentCard, String agentName, boolean includeContents,
			String outputKeyToParent, String instruction, boolean streaming, AgentTracer agentTracer) {
		this(agentCard, agentName, includeContents, outputKeyToParent, instruction, streaming);
		this.agentTracer = agentTracer;
	}

	public A2aNodeActionWithConfig(AgentCardWrapper agentCard, String agentName, boolean includeContents,
			String outputKeyToParent, String instruction, boolean streaming, boolean shareState,
			CompileConfig compileConfig) {
		this(agentCard, agentName, includeContents, outputKeyToParent, instruction, streaming);
		this.parentCompileConfig = compileConfig;
		this.shareState = shareState;
	}

	/**
	 * Create the node action with shared state and optional cross-agent trace correlation.
	 * @param agentCard the remote agent card
	 * @param agentName the local agent name
	 * @param includeContents whether to include contents
	 * @param outputKeyToParent the output key written back to the parent state
	 * @param instruction the instruction template
	 * @param streaming whether to use the streaming endpoint
	 * @param shareState whether to share the parent state
	 * @param compileConfig the parent compile config
	 * @param agentTracer the cross-agent tracer, may be null
	 */
	public A2aNodeActionWithConfig(AgentCardWrapper agentCard, String agentName, boolean includeContents,
			String outputKeyToParent, String instruction, boolean streaming, boolean shareState,
			CompileConfig compileConfig, AgentTracer agentTracer) {
		this(agentCard, agentName, includeContents, outputKeyToParent, instruction, streaming, shareState,
				compileConfig);
		this.agentTracer = agentTracer;
	}

	/**
	 * Select the {@link RunnableConfig} metadata key whose value is forwarded to the remote
	 * agent as the A2A {@code userId} metadata field.
	 * @param userIdMetadataKey the metadata key, defaults to
	 * {@link #DEFAULT_USER_ID_METADATA_KEY}
	 * @return this node action
	 */
	public A2aNodeActionWithConfig userIdMetadataKey(String userIdMetadataKey) {
		if (!StringUtils.hasText(userIdMetadataKey)) {
			throw new IllegalArgumentException("userIdMetadataKey must not be blank");
		}
		this.userIdMetadataKey = userIdMetadataKey;
		return this;
	}

	/**
	 * Bound how long a streaming call waits for the subscriber to request the next event
	 * before failing with {@link A2aBackpressureException}. The producer buffers nothing
	 * internally, so a stalled consumer blocks the remote read instead of growing memory;
	 * this timeout turns an indefinitely stalled consumer into an explicit terminal error.
	 * The done response is an ordinary event and needs one permit, so a consumer that stops
	 * requesting before it fails explicitly instead of hanging. Set {@link Duration#ZERO}
	 * to disable the timeout entirely (wait forever; zero does not mean "fail
	 * immediately").
	 * @param streamDemandTimeout the maximum wait for downstream demand, defaults to
	 * {@link #DEFAULT_STREAM_DEMAND_TIMEOUT}
	 * @return this node action
	 */
	public A2aNodeActionWithConfig streamDemandTimeout(Duration streamDemandTimeout) {
		Objects.requireNonNull(streamDemandTimeout, "streamDemandTimeout must not be null");
		if (streamDemandTimeout.isNegative()) {
			throw new IllegalArgumentException("streamDemandTimeout must not be negative");
		}
		this.streamDemandTimeout = streamDemandTimeout;
		return this;
	}

	@Override
	public Map<String, Object> apply(OverAllState state, RunnableConfig config) throws Exception {
		RunnableConfig subGraphRunnableConfig = getSubGraphRunnableConfig(config);
		AgentTraceContext context = traceContext(subGraphRunnableConfig, state);
		if (context != null) {
			context = context.withDelegationId(UUID.randomUUID().toString());
		}
		if (streaming) {
			Flux<GraphResponse<NodeOutput>> flux = createStreamingFlux(state, subGraphRunnableConfig, context);
			return Map.of(StringUtils.hasLength(this.outputKeyToParent) ? this.outputKeyToParent : "messages", flux);
		}
		else {
			String requestPayload = buildSendMessageRequest(state, subGraphRunnableConfig, context);
			String resultText = sendRequest(requestPayload, context);
			try (AgentTraceSpan receiveSpan = startSpan(AgentTraceOperation.A2A_RECEIVE, context).role("consumer")) {
				final String responseText;
				try {
					Map<String, Object> resultMap = autoDetectAndParseResponse(resultText);
					final Map<String, Object> result = resultPayload(resultMap);
					final A2aRemoteTaskException remoteFailure = result != null ? remoteTaskFailure(result) : null;
					if (remoteFailure != null) {
						throw remoteFailure;
					}
					responseText = extractResponseText(result);
				}
				catch (A2aException ex) {
					receiveSpan.error(ex);
					throw ex;
				}
				catch (IllegalArgumentException | IllegalStateException ex) {
					A2aProtocolException protocol = new A2aProtocolException("A2A response cannot be parsed", ex);
					receiveSpan.error(protocol);
					throw protocol;
				}
				receiveSpan.success();
				return buildFinalResult(this.outputKeyToParent, responseText);
			}
		}
	}

	private boolean tracing() {
		return this.agentTracer != null && this.agentTracer.isEnabled();
	}

	private AgentTraceContext traceContext(RunnableConfig config, OverAllState state) {
		return tracing() ? AgentTraceContext.capture(config, state != null ? state.data() : null) : null;
	}

	private AgentTraceSpan startSpan(AgentTraceOperation operation, AgentTraceContext context) {
		return tracing() ? this.agentTracer.start(operation, context) : AgentTraceSpan.disabled();
	}

	private String sendRequest(String requestPayload, AgentTraceContext context) throws Exception {
		if (!tracing()) {
			return sendMessageToServer(this.agentCard, requestPayload);
		}
		AgentTraceSpan sendSpan = startSpan(AgentTraceOperation.A2A_SEND, context).role("producer");
		try {
			String resultText = sendMessageToServer(this.agentCard, requestPayload);
			sendSpan.success();
			return resultText;
		}
		catch (Exception e) {
			sendSpan.error(e);
			throw e;
		}
		finally {
			sendSpan.close();
		}
	}

	private RunnableConfig getSubGraphRunnableConfig(RunnableConfig config) {
		if (shareState) {
			return config;
		}
		return RunnableConfig.builder(config)
			.threadId(config.threadId()
				.map(threadId -> format("%s_%s", threadId, subGraphId()))
				.orElseGet(this::subGraphId))
			.nextNode(null)
			.checkPointId(null)
			.build();
	}

	public String subGraphId() {
		return format("subgraph_%s", agentCard.name());
	}

	/**
	 * Stream the remote agent response. The HTTP exchange starts on subscription and runs
	 * on a bounded elastic worker. Each non-empty text chunk is emitted as a
	 * {@link StreamingOutput}; transport, protocol and remote task failures propagate as
	 * {@link A2aException} terminal errors (issue #213), so a failed remote call is never
	 * mistaken for a normal completion. On success the stream ends with a done response
	 * carrying the accumulated text for the parent state, unless the subscriber cancels
	 * first.
	 *
	 * <p>Events are demand driven (issue #212): the producer holds no internal queue and
	 * emits only while the subscriber has outstanding requests, so a stalled consumer
	 * blocks the socket read instead of buffering without bound. The wait is woken by
	 * demand, cancellation or disposal; an error terminal is delivered even at zero demand,
	 * while the done response is an ordinary event that requires one permit and is lost to
	 * an explicit {@link A2aBackpressureException} (rather than an unbounded buffer) if the
	 * consumer stops requesting before it.</p>
	 */
	private Flux<GraphResponse<NodeOutput>> createStreamingFlux(OverAllState state, RunnableConfig config,
			AgentTraceContext context) throws Exception {
		final String requestPayload = buildSendStreamingMessageRequest(state, config, context);
		final String outputKey = StringUtils.hasLength(this.outputKeyToParent) ? this.outputKeyToParent : "messages";

		return Flux.<GraphResponse<NodeOutput>>create(sink -> {
			final StreamEmission emission = new StreamEmission(sink, this.streamDemandTimeout);
			sink.onRequest(emission::onRequest);
			sink.onDispose(emission::onCancel);
			// The exchange blocks on socket reads and on downstream demand. Running it as a
			// separate task keeps the subscription worker free to forward the subscriber's
			// requests to the sink; blocking on that worker would starve its own demand.
			Schedulers.boundedElastic()
				.schedule(() -> runStreamingExchange(state, outputKey, context, requestPayload, emission));
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private void runStreamingExchange(OverAllState state, String outputKey, AgentTraceContext context,
			String requestPayload, StreamEmission emission) {
		final FluxSink<GraphResponse<NodeOutput>> sink = emission.sink();
		final StringBuilder accumulated = new StringBuilder();
		String baseUrl = resolveAgentBaseUrl(this.agentCard);
		if (baseUrl == null || baseUrl.isBlank()) {
			emission.fail(new A2aTransportException("AgentCard.url is empty"));
			return;
		}
		if (emission.isCancelled()) {
			return;
		}

		A2aException failure = null;
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpPost post = new HttpPost(baseUrl);
			post.setHeader("Content-Type", "application/json");
			post.setHeader("Accept", "text/event-stream");
			post.setEntity(new StringEntity(requestPayload, ContentType.APPLICATION_JSON));
			// A stalled consumer aborts the exchange before failing the stream, so the
			// terminal signal never waits for the peer to finish sending.
			emission.exchangeAbort(post::cancel);

			// Cancelling the subscription must reach the transport: otherwise a silent
			// remote peer keeps the worker blocked in the synchronous read below and the
			// connection open until it sends another byte or closes on its own.
			sink.onCancel(() -> {
				emission.onCancel();
				abort(httpClient, post);
			});
			if (emission.isCancelled()) {
				return;
			}

			// The worker thread has no ambient observation, so the producer context is
			// carried explicitly through the captured trace context and linked through
			// the shared delegation id instead of a parent/child relationship.
			AgentTraceSpan sendSpan = startSpan(AgentTraceOperation.A2A_SEND, context).role("producer");
			try {
				httpClient.execute(post, response -> {
					int statusCode = response.getCode();
					if (statusCode != 200) {
						throw new A2aTransportException("HTTP request failed, status: " + statusCode);
					}

					HttpEntity entity = response.getEntity();
					if (entity == null) {
						throw new A2aTransportException("Empty HTTP entity");
					}

					String contentType = entity.getContentType() != null ? entity.getContentType() : "";
					boolean isEventStream = contentType.contains("text/event-stream");

					AgentTraceSpan receiveSpan = startSpan(AgentTraceOperation.A2A_RECEIVE, context).role("consumer");
					try {
						if (isEventStream) {
							consumeEventStream(entity.getContent(), state, emission, accumulated);
						}
						else {
							consumeSingleBodyResponse(entity, state, emission, accumulated);
						}
						if (!emission.isCancelled()) {
							receiveSpan.success();
						}
					}
					catch (Exception ex) {
						if (!emission.isCancelled()) {
							receiveSpan.error(ex);
						}
						throw ex;
					}
					finally {
						receiveSpan.close();
					}
					return null;
				});
				if (!emission.isCancelled()) {
					sendSpan.success();
				}
			}
			catch (Exception e) {
				if (!emission.isCancelled()) {
					sendSpan.error(e);
				}
				throw e;
			}
			finally {
				sendSpan.close();
			}
		}
		catch (A2aException e) {
			failure = e;
		}
		catch (Exception e) {
			failure = asA2aFailure(e);
		}

		if (failure != null) {
			// The failure is the single terminal state: text chunks already delivered
			// keep their identity and no done response follows an error.
			emission.fail(failure);
			return;
		}
		try {
			complete(emission, outputKey, accumulated);
		}
		catch (A2aException e) {
			// only reachable when waiting for the final permit was interrupted; the
			// subscriber must not stay pending without a terminal state
			emission.fail(e);
		}
	}

	/**
	 * Consume an A2A {@code text/event-stream} response. Events follow the SSE wire format:
	 * comment lines and fields other than {@code data} are ignorable extensions, the
	 * {@code data} lines of one event are joined with newlines, and a blank line dispatches
	 * the accumulated event. Reading stops at the {@code [DONE]} sentinel, a completed
	 * status-update, an event marked {@code final} or a standalone Message result; an
	 * event stream that ends without any terminal state is a protocol failure
	 * (issue #213).
	 */
	private void consumeEventStream(InputStream content, OverAllState state,
			StreamEmission emission, StringBuilder accumulated) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8))) {
			final List<String> dataLines = new ArrayList<>(2);
			String line;
			while (!emission.isCancelled() && (line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					// a blank line dispatches the accumulated event per the SSE wire format
					if (!dataLines.isEmpty() && dispatchSseEvent(String.join("\n", dataLines), state, emission,
							accumulated)) {
						return;
					}
					dataLines.clear();
					continue;
				}
				if (line.startsWith(":")) {
					// comments and keep-alive lines carry no data
					continue;
				}
				if (line.startsWith("data:")) {
					dataLines.add(line.substring(5).trim());
				}
				// other field names (event:, id:, retry:) are ignorable extensions
			}
			if (!emission.isCancelled() && !dataLines.isEmpty()) {
				// the peer may close without a trailing blank line; the pending event
				// still counts
				if (dispatchSseEvent(String.join("\n", dataLines), state, emission, accumulated)) {
					return;
				}
			}
			if (!emission.isCancelled()) {
				throw new A2aProtocolException("A2A event stream ended without a terminal state");
			}
		}
	}

	/**
	 * Handle one assembled SSE data event. A terminal frame may itself carry the task's
	 * final output — for example a completed Task whose artifacts hold the whole answer —
	 * so that output is extracted and delivered before the event terminates the stream.
	 * Remote task failures are classified first: a failed, canceled or rejected
	 * status-update terminates the stream with a structured error even when it also
	 * carries the {@code final} flag.
	 * @return whether the event terminates the remote task: the {@code [DONE]} sentinel,
	 * a completed status-update, an event marked {@code final}, or a standalone Message
	 * result
	 */
	private boolean dispatchSseEvent(String data, OverAllState state, StreamEmission emission,
			StringBuilder accumulated) {
		if ("[DONE]".equals(data)) {
			return true;
		}
		final Map<String, Object> parsed;
		try {
			parsed = parseJsonMap(data);
		}
		catch (IllegalArgumentException ex) {
			throw new A2aProtocolException("Corrupt A2A SSE frame, not valid JSON: " + abbreviate(data), ex);
		}
		final Map<String, Object> result = resultPayload(parsed);
		if (result == null) {
			// valid JSON carrying no result object: an ignorable extension event
			return false;
		}
		final A2aRemoteTaskException remoteFailure = remoteTaskFailure(result);
		if (remoteFailure != null) {
			throw remoteFailure;
		}
		final boolean terminal = isTerminalEvent(result);
		final String text;
		try {
			text = extractResponseText(result);
		}
		catch (Exception ex) {
			// a recognizable frame without extractable text is skipped, mirroring the
			// historical behavior for events this client does not consume; a terminal
			// frame carrying no output still terminates the stream
			return terminal;
		}
		if (text != null && !text.isEmpty()) {
			accumulated.append(text);
			emission.next(buildStreamingOutput(text, state));
		}
		return terminal;
	}

	/**
	 * The result object carried by one parsed JSON-RPC payload, or null when the payload has
	 * none. A JSON-RPC error object translates to a protocol failure here, so both payload
	 * shapes (SSE frames and single bodies) classify errors identically.
	 */
	private Map<String, Object> resultPayload(Map<String, Object> parsed) {
		if (parsed.containsKey("error")) {
			throw jsonRpcFailure(parsed.get("error"));
		}
		return parsed.get("result") instanceof Map<?, ?> rawResult ? (Map<String, Object>) rawResult : null;
	}

	/**
	 * Consume a non-SSE response body: one JSON-RPC payload carrying the whole remote
	 * result. Parse failures, JSON-RPC errors and remote task failures propagate as
	 * structured errors; a single body is by itself the terminal state (issue #213).
	 */
	private void consumeSingleBodyResponse(HttpEntity entity, OverAllState state,
			StreamEmission emission, StringBuilder accumulated) throws IOException, ParseException {
		final String body = EntityUtils.toString(entity, "UTF-8");
		final Map<String, Object> parsed;
		try {
			parsed = parseJsonMap(body);
		}
		catch (IllegalArgumentException ex) {
			throw new A2aProtocolException("A2A response body is not valid JSON", ex);
		}
		final Map<String, Object> result = resultPayload(parsed);
		if (result == null) {
			throw new A2aProtocolException("A2A response without a result object");
		}
		final A2aRemoteTaskException remoteFailure = remoteTaskFailure(result);
		if (remoteFailure != null) {
			throw remoteFailure;
		}
		final String text;
		try {
			text = extractResponseText(result);
		}
		catch (Exception ex) {
			throw new A2aProtocolException("A2A response without usable content", ex);
		}
		if (text != null && !text.isEmpty()) {
			accumulated.append(text);
			emission.next(buildStreamingOutput(text, state));
		}
	}

	/**
	 * Map an exception thrown by the streaming exchange to the structured A2A failure. The
	 * Apache client may wrap handler exceptions, so an A2A failure is recovered from the
	 * cause chain; anything else is a transport failure.
	 */
	private static A2aException asA2aFailure(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof A2aException a2a) {
				return a2a;
			}
			current = current.getCause();
		}
		return new A2aTransportException("A2A streaming call failed: " + error.getMessage(), error);
	}

	/**
	 * Translate a JSON-RPC error object into a protocol failure carrying the remote code
	 * and message.
	 */
	private static A2aProtocolException jsonRpcFailure(Object errorValue) {
		final String detail;
		if (errorValue instanceof Map<?, ?> error && error.get("message") != null) {
			detail = "code=" + error.get("code") + ", message=" + error.get("message");
		}
		else {
			detail = String.valueOf(errorValue);
		}
		return new A2aProtocolException("A2A remote protocol error: " + detail);
	}

	/**
	 * Detect a remote task termination that must surface as a structured failure instead of
	 * a normal completion (issue #213). Both shapes carry the outcome in a
	 * {@code status.state} field: status-update events on a stream and the Task object a
	 * single-body response returns. Failed, canceled and rejected tasks are failures in
	 * either shape. The optional status message carries the remote reason.
	 * @return the failure to propagate, or null when the payload is not a task failure
	 */
	private static A2aRemoteTaskException remoteTaskFailure(Map<String, Object> result) {
		final String state = remoteTaskState(result);
		if (!"failed".equals(state) && !"canceled".equals(state) && !"rejected".equals(state)) {
			return null;
		}
		final String reason = result.get("status") instanceof Map<?, ?> status ? statusMessageText(status) : "";
		return new A2aRemoteTaskException(state,
				"Remote A2A task " + state + (reason.isBlank() ? "" : ": " + reason));
	}

	/**
	 * Best-effort text of an A2A status message: the concatenated text parts, if any.
	 */
	private static String statusMessageText(Map<?, ?> status) {
		if (!(status.get("message") instanceof Map<?, ?> message)
				|| !(message.get("parts") instanceof List<?> parts)) {
			return "";
		}
		final StringBuilder text = new StringBuilder();
		for (Object part : parts) {
			if (part instanceof Map<?, ?> partMap && partMap.get("text") instanceof String partText) {
				text.append(partText);
			}
		}
		return text.toString();
	}

	/**
	 * The remote task state reported by a status-update event or a Task object, or null for
	 * payloads carrying no task status.
	 */
	private static String remoteTaskState(Map<String, Object> result) {
		return result.get("status") instanceof Map<?, ?> status && status.get("state") instanceof String state
				? state : null;
	}

	/**
	 * Whether the payload reports the final completed state that terminates a successful
	 * remote task.
	 */
	private static boolean isCompletedStatus(Map<String, Object> result) {
		return "completed".equals(remoteTaskState(result));
	}

	/**
	 * Whether the event is explicitly marked as the stream's last one. A successful A2A
	 * event stream may end on a final status-update whose state is not completed — for
	 * example {@code input-required}, a normal interaction boundary — so the flag, not
	 * the state alone, terminates the stream.
	 */
	private static boolean isFinalEvent(Map<String, Object> result) {
		return result.get("final") instanceof Boolean flag && flag;
	}

	/**
	 * Whether the payload is a standalone A2A Message. An agent may answer a request with
	 * a bare Message instead of a tracked Task; the Message by itself completes the
	 * interaction, without a final flag or the {@code [DONE]} sentinel.
	 */
	private static boolean isStandaloneMessage(Map<String, Object> result) {
		return "message".equals(result.get("kind"));
	}

	/**
	 * Whether the event ends the remote interaction successfully: a completed task
	 * status, an event explicitly marked {@code final}, or a standalone Message that
	 * answers the request directly. Remote failures are classified before this runs, so
	 * a terminal event carrying a failed, canceled or rejected state never reaches here.
	 */
	private static boolean isTerminalEvent(Map<String, Object> result) {
		return isCompletedStatus(result) || isFinalEvent(result) || isStandaloneMessage(result);
	}

	/**
	 * Shorten an offending SSE frame for an error message.
	 */
	private static String abbreviate(String data) {
		return data.length() <= 120 ? data : data.substring(0, 120) + "...";
	}

	private void complete(StreamEmission emission, String outputKey, StringBuilder accumulated) {
		emission.complete(buildFinalResult(outputKey, accumulated.toString()));
	}

	/**
	 * Release the exchange owned by a cancelled subscription. The request is cancelled
	 * first so the client aborts the in-flight execution; closing the per-invocation
	 * client then shuts down its connection, which unblocks the worker still reading a
	 * response that the peer has not finished sending. Both steps are idempotent and the
	 * client is never shared with other runs.
	 * @param httpClient the client created for this invocation
	 * @param post the request whose exchange must be released
	 */
	private static void abort(CloseableHttpClient httpClient, HttpPost post) {
		post.cancel();
		try {
			httpClient.close();
		}
		catch (IOException ignored) {
			// best-effort teardown of an exchange that is already being abandoned
		}
	}

	/**
	 * Build a {@link StreamingOutput} for an A2A remote response chunk.
	 *
	 * The neutral assistant message supplies visible text to Studio's SSE projection; the
	 * output type identifies the event as a model delta rather than a final snapshot.
	 */
	private StreamingOutput<?> buildStreamingOutput(String text, OverAllState state) {
		ChatMessage assistant = MessageProvenance
			.forChildHandoff(io.github.agentic.ai.api.chat.ChatMessage.assistant(text));
		return new StreamingOutput<>(assistant, text, "a2aNode", agentName, state, OutputType.AGENT_MODEL_STREAMING);
	}

	/**
	 * Build the value written back to the parent graph while preserving the state key's
	 * type contract. The {@code messages} key is managed by an append strategy and
	 * consumed as a {@code List<ChatMessage>}; appending a raw string corrupts that list
	 * and causes routing agents to fail on the next model call. Custom output keys retain
	 * their historical string value.
	 */
	private Map<String, Object> buildFinalResult(String outputKey, String text) {
		Object value = "messages".equals(outputKey)
				? MessageProvenance.forChildHandoff(ChatMessage.assistant(text)) : text;
		return Map.of(outputKey, value);
	}

	/**
	 * Auto-detect response format and parse accordingly.
	 * @param responseText The raw response text
	 * @return Parsed result map
	 */
	private Map<String, Object> autoDetectAndParseResponse(String responseText) {
		if (responseText.contains("data: ")) {
			return parseStreamingResponse(responseText);
		}
		else {
			// Standard JSON response
			return parseJsonMap(responseText);
		}
	}

	/**
	 * Parse streaming response in Server-Sent Events (SSE) format.
	 * @param responseText The raw SSE response text
	 * @return Parsed result map
	 */
	private Map<String, Object> parseStreamingResponse(String responseText) {
		String[] lines = responseText.split("\n");
		Map<String, Object> lastResult = null;
		for (String line : lines) {
			line = line.trim();
			if (line.startsWith("data: ")) {
				String jsonContent = line.substring(6); // remove "data: " prefix
				try {
					Map<String, Object> parsed = parseJsonMap(jsonContent);
					Map<String, Object> result = (Map<String, Object>) parsed.get("result");
					if (result != null) {
						if (result.containsKey("artifact") || lastResult == null) {
							lastResult = result;
						}
					}
				}
				catch (Exception e) {
					continue;
				}
			}
		}

		if (lastResult == null) {
			throw new IllegalStateException("Failed to parse any valid result from streaming response");
		}
		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("result", lastResult);
		return resultMap;
	}

	private Map<String, Object> parseJsonMap(String json) {
		try {
			return this.objectMapper.readValue(json, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Failed to parse A2A JSON response", ex);
		}
	}

	private String extractResponseText(Map<String, Object> result) {
		if (result == null) {
			throw new IllegalStateException("Result is null, cannot extract response text");
		}

		if ("status-update".equals(result.get("kind"))) {
			Map<String, Object> status = (Map<String, Object>) result.get("status");
			if (status != null) {
				String state = (String) status.get("state");
				if ("completed".equals(state)) {
					return "";
				}
				else if ("processing".equals(state)) {
					return "";
				}
				else if ("failed".equals(state)) {
					return "";
				}
				else if ("working".equals(state)) {
					Map<String, Object> message = (Map<String, Object>) status.get("message");
					if (message != null && message.containsKey("parts")) {
						List<Object> parts = (List<Object>) message.get("parts");
						if (parts != null && !parts.isEmpty()) {
							Map<String, Object> lastPart = (Map<String, Object>) parts.get(parts.size() - 1);
							if (lastPart != null) {
								String text = (String) lastPart.get("text");
								if (text != null) {
									return text;
								}
							}
						}
					}
					return "";
				}
				else if ("submitted".equals(state)) {
					// submitted is the initial state when a task is created, should be
					// ignored
					return "";
				}
				else if ("canceled".equals(state)) {
					// canceled state should be ignored
					return "";
				}
				else {
					return "Agent State: " + state;
				}
			}
			return "";
		}

		if ("artifact-update".equals(result.get("kind"))) {
			Map<String, Object> artifact = (Map<String, Object>) result.get("artifact");
			if (artifact != null && artifact.containsKey("parts")) {
				List<Object> parts = (List<Object>) artifact.get("parts");
				if (parts != null && !parts.isEmpty()) {
					StringBuilder responseBuilder = new StringBuilder();
					for (Object part : parts) {
						if (part instanceof Map) {
							String text = (String) ((Map<String, Object>) part).get("text");
							if (text != null) {
								responseBuilder.append(text);
							}
						}
					}
					String response = responseBuilder.toString();
					if (!response.isEmpty()) {
						return response;
					}
				}
			}
			return "";
		}
		if (result.containsKey("artifacts")) {
			List<Object> artifacts = (List<Object>) result.get("artifacts");
			if (artifacts != null && !artifacts.isEmpty()) {
				StringBuilder responseBuilder = new StringBuilder();
				for (Object artifact : artifacts) {
					if (artifact instanceof Map) {
						List<Object> parts = (List<Object>) ((Map<String, Object>) artifact).get("parts");
						if (parts != null) {
							for (Object part : parts) {
								if (part instanceof Map) {
									String text = (String) ((Map<String, Object>) part).get("text");
									if (text != null) {
										responseBuilder.append(text);
									}
								}
							}
						}
					}
				}
				String response = responseBuilder.toString();
				if (!response.isEmpty()) {
					return response;
				}
			}
		}
		if (result.containsKey("parts")) {
			List<Object> parts = (List<Object>) result.get("parts");
			if (parts != null && !parts.isEmpty()) {
				Map<String, Object> lastPart = (Map<String, Object>) parts.get(parts.size() - 1);
				if (lastPart != null) {
					String text = (String) lastPart.get("text");
					if (text != null) {
						return text;
					}
				}
			}
		}
		if (result.containsKey("message")) {
			Map<String, Object> message = (Map<String, Object>) result.get("message");
			if (message != null && message.containsKey("parts")) {
				List<Object> parts = (List<Object>) message.get("parts");
				if (parts != null && !parts.isEmpty()) {
					Map<String, Object> lastPart = (Map<String, Object>) parts.get(parts.size() - 1);
					if (lastPart != null) {
						String text = (String) lastPart.get("text");
						if (text != null) {
							return text;
						}
					}
				}
			}
		}
		throw new IllegalStateException("No valid text content found in result: " + result);
	}

	/**
	 * Build the JSON-RPC request payload to send to the A2A server.
	 * @param state Parent state
	 * @param config the invocation-local config
	 * @param context the cross-agent trace context, or null when tracing is disabled
	 * @return JSON string payload (e.g., JSON-RPC params)
	 */
	private String buildSendMessageRequest(OverAllState state, RunnableConfig config, AgentTraceContext context) {
		Object textValue = getEffectiveInstruction(state);
		String text = String.valueOf(textValue);

		String id = UUID.randomUUID().toString();
		String messageId = UUID.randomUUID().toString().replace("-", "");

		Map<String, Object> part = Map.of("kind", "text", "text", text);

		Map<String, Object> message = new HashMap<>();
		message.put("kind", "message");
		message.put("messageId", messageId);
		message.put("parts", List.of(part));
		message.put("role", "user");

		Map<String, Object> params = new HashMap<>();
		params.put("message", message);

		Map<String, Object> metadata = new HashMap<>();
		config.threadId().ifPresent(threadId -> metadata.put("threadId", threadId));
		config.metadata(this.userIdMetadataKey).ifPresent(userId -> metadata.put("userId", userId));
		putTraceMetadata(metadata, context);
		params.put("metadata", metadata);

		Map<String, Object> root = new HashMap<>();
		root.put("id", id);
		root.put("jsonrpc", "2.0");
		root.put("method", "message/send");
		root.put("params", params);

		try {
			return objectMapper.writeValueAsString(root);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to build JSON-RPC payload", e);
		}
	}

	/**
	 * Build the JSON-RPC streaming request payload (method: message/stream).
	 * @param state Parent state
	 * @param config the invocation-local config
	 * @param context the cross-agent trace context, or null when tracing is disabled
	 * @return JSON string payload for streaming
	 */
	private String buildSendStreamingMessageRequest(OverAllState state, RunnableConfig config,
			AgentTraceContext context) {
		Object textValue = getEffectiveInstruction(state);
		String text = String.valueOf(textValue);

		String id = UUID.randomUUID().toString();
		String messageId = UUID.randomUUID().toString().replace("-", "");

		Map<String, Object> part = Map.of("kind", "text", "text", text);

		Map<String, Object> message = new HashMap<>();
		message.put("kind", "message");
		message.put("messageId", messageId);
		message.put("parts", List.of(part));
		message.put("role", "user");

		Map<String, Object> params = new HashMap<>();
		params.put("message", message);

		Map<String, Object> metadata = new HashMap<>();
		config.threadId().ifPresent(threadId -> metadata.put("threadId", threadId));
		config.metadata(this.userIdMetadataKey).ifPresent(userId -> metadata.put("userId", userId));
		putTraceMetadata(metadata, context);
		params.put("metadata", metadata);

		Map<String, Object> root = new HashMap<>();
		root.put("id", id);
		root.put("jsonrpc", "2.0");
		root.put("method", "message/stream");
		root.put("params", params);

		try {
			return objectMapper.writeValueAsString(root);
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to build JSON-RPC streaming payload", e);
		}
	}

	/**
	 * Propagate the producer trace context to the remote side through the A2A request
	 * metadata, so a remote ARGI runtime can correlate its consumer spans with this
	 * delegation. Only non-null facts are written; nothing is added when tracing is
	 * disabled.
	 * @param metadata the A2A request metadata map
	 * @param context the cross-agent trace context, or null when tracing is disabled
	 */
	private void putTraceMetadata(Map<String, Object> metadata, AgentTraceContext context) {
		if (context == null) {
			return;
		}
		if (context.runId() != null) {
			metadata.put("argiRunId", context.runId());
		}
		if (context.tenant() != null) {
			metadata.put("argiTenant", context.tenant());
		}
		if (context.attemptId() != null) {
			metadata.put("argiAttemptId", context.attemptId());
		}
		if (context.threadId() != null) {
			metadata.put("argiThreadId", context.threadId());
		}
		if (context.delegationId() != null) {
			metadata.put("argiDelegationId", context.delegationId());
		}
	}

	private String getEffectiveInstruction(OverAllState state) {
		if (StringUtils.hasLength(this.instruction)) {
			PromptTemplate template = PromptTemplate.of(this.instruction);
			return template.render(state.data());
		}
		else if (!shareState || (shareState && state.value("messages").isEmpty())) {
			throw new IllegalStateException("Instruction is empty and shareState is false");
		}
		return "";
	}

	/**
	 * Send the request to the remote A2A server and return the non-streaming response.
	 * @param agentCard Agent card (source for server URL/metadata)
	 * @param requestPayload JSON string payload built by buildSendMessageRequest
	 * @return Response body as string
	 */
	private String sendMessageToServer(AgentCardWrapper agentCard, String requestPayload) throws Exception {
		String baseUrl = resolveAgentBaseUrl(agentCard);
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new A2aTransportException("AgentCard.url is empty");
		}

		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpPost post = new HttpPost(baseUrl);
			post.setHeader("Content-Type", "application/json");
			post.setEntity(new StringEntity(requestPayload, ContentType.APPLICATION_JSON));

			return httpClient.execute(post, response -> {
				int statusCode = response.getCode();
				if (statusCode != 200) {
					throw new A2aTransportException("HTTP request failed, status: " + statusCode);
				}
				HttpEntity entity = response.getEntity();
				if (entity == null) {
					throw new A2aTransportException("Empty HTTP entity");
				}
				return EntityUtils.toString(entity, "UTF-8");
			});
		}
	}

	/**
	 * Resolve base URL from the AgentCard.
	 */
	private String resolveAgentBaseUrl(AgentCardWrapper agentCard) {
		return agentCard.url();
	}

	/**
	 * Serializes the streaming producer against downstream demand. No queue sits between
	 * the producer and the subscriber: {@link #next} emits only after it has consumed one
	 * permit supplied by a downstream request, so a stalled consumer blocks the socket
	 * read and applies backpressure to the remote peer instead of growing memory. Because
	 * the producer never emits without a permit, the sink's internal queue is empty
	 * whenever a terminal signal is sent, and {@link FluxSink#error}/{@link FluxSink#complete}
	 * are delivered even at zero demand, so they are never lost behind buffered events
	 * (issue #212).
	 */
	private static final class StreamEmission {

		/** Upper bound of one wait slice; the loop re-checks permits and cancellation. */
		private static final long MAX_WAIT_SLICE_MILLIS = 50;

		private final FluxSink<GraphResponse<NodeOutput>> sink;

		private final Duration demandTimeout;

		private final Object demandMonitor = new Object();

		private long permits;

		private volatile boolean cancelled;

		private volatile boolean timedOut;

		private Runnable exchangeAbort;

		StreamEmission(FluxSink<GraphResponse<NodeOutput>> sink, Duration demandTimeout) {
			this.sink = sink;
			this.demandTimeout = demandTimeout;
		}

		FluxSink<GraphResponse<NodeOutput>> sink() {
			return this.sink;
		}

		/** Register how to abort the in-flight exchange once the consumer stalls. */
		void exchangeAbort(Runnable abort) {
			synchronized (this.demandMonitor) {
				this.exchangeAbort = abort;
			}
		}

		/**
		 * Record a downstream request and wake the producer. Demand is tracked from these
		 * notifications instead of {@link FluxSink#requestedFromDownstream()}: the sink
		 * decrements its own counter while delivering, so reading it from the producer can
		 * briefly report one request too many, which would let one event slip into the
		 * sink's internal queue and delay the terminal signal behind it.
		 */
		void onRequest(long requested) {
			synchronized (this.demandMonitor) {
				if (requested == Long.MAX_VALUE || this.permits == Long.MAX_VALUE) {
					this.permits = Long.MAX_VALUE;
				}
				else {
					final long total = this.permits + requested;
					this.permits = total < 0 ? Long.MAX_VALUE : total;
				}
				this.demandMonitor.notifyAll();
			}
		}

		void onCancel() {
			synchronized (this.demandMonitor) {
				this.cancelled = true;
				this.demandMonitor.notifyAll();
			}
		}

		boolean isCancelled() {
			return this.cancelled || this.timedOut || this.sink.isCancelled();
		}

		void next(NodeOutput output) {
			if (isCancelled() || !awaitDemand()) {
				return;
			}
			this.sink.next(GraphResponse.of(output));
		}

		/**
		 * Deliver the structured failure as the stream's single terminal state. Text
		 * chunks already emitted keep their identity; a cancelled subscription gets
		 * neither the error nor a done response.
		 */
		void fail(A2aException failure) {
			if (!isCancelled()) {
				this.sink.error(failure);
			}
		}

		void complete(Map<String, Object> finalResult) {
			if (isCancelled() || !awaitDemand()) {
				return;
			}
			this.sink.next(GraphResponse.done(finalResult));
			this.sink.complete();
		}

		/**
		 * Wait for and consume one downstream permit. The loop re-checks every short slice
		 * so a missed wake-up only delays the producer briefly. When the consumer stalls
		 * for longer than the configured timeout the exchange is aborted first and the
		 * stream fails immediately; the producer then unwinds normally, so a slow transport
		 * teardown can never delay the terminal signal.
		 * @return {@code true} when the event may be emitted, {@code false} when the
		 * subscription was cancelled or the stream failed
		 */
		private boolean awaitDemand() {
			final boolean waitForever = this.demandTimeout.isZero();
			final long startNanos = System.nanoTime();
			final long timeoutNanos = waitForever ? 0L : this.demandTimeout.toNanos();
			synchronized (this.demandMonitor) {
				while (this.permits <= 0) {
					if (isCancelled()) {
						return false;
					}
					final long remainingNanos = waitForever ? Long.MAX_VALUE
							: timeoutNanos - (System.nanoTime() - startNanos);
					if (remainingNanos <= 0) {
						this.timedOut = true;
						break;
					}
					final long waitMillis = Math.max(1,
							Math.min(MAX_WAIT_SLICE_MILLIS, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
					try {
						this.demandMonitor.wait(waitMillis);
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new A2aTransportException(
								"A2A streaming worker was interrupted while waiting for demand", ex);
					}
				}
				if (!this.timedOut) {
					this.permits--;
					return true;
				}
			}
			reportStalledConsumer();
			return false;
		}

		/**
		 * Abort the exchange and deliver the explicit backpressure failure. Aborting first
		 * releases the connection without waiting for the peer, and delivering the error
		 * from here (rather than letting it unwind through the HTTP client) keeps the
		 * terminal signal independent of the transport teardown.
		 */
		private void reportStalledConsumer() {
			final A2aBackpressureException failure = new A2aBackpressureException(format(
					"A2A stream consumer requested no event for %s; the stream cannot advance without downstream "
							+ "demand (configure streamDemandTimeout)",
					this.demandTimeout));
			final Runnable abort;
			synchronized (this.demandMonitor) {
				abort = this.exchangeAbort;
			}
			if (abort != null) {
				abort.run();
			}
			if (!this.sink.isCancelled()) {
				this.sink.error(failure);
			}
		}

	}

}
