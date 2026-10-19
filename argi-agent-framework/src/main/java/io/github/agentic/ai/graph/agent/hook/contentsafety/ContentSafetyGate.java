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
package io.github.agentic.ai.graph.agent.hook.contentsafety;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in content check. Disabled stages pass text through unchanged. Audits and logs
 * record the decision only, never the inspected payload.
 */
public final class ContentSafetyGate {

	private static final Logger log = LoggerFactory.getLogger(ContentSafetyGate.class);

	private static final String REDACTED = "[REDACTED]";

	private final Set<ContentSafetyStage> stages;

	private final ContentSafetyAction matchAction;

	private final ContentSafetyFailurePolicy failurePolicy;

	private final long timeoutMillis;

	private final List<ContentDetector> detectors;

	private final ContentSafetyAudit audit;

	private final ExecutorService executor;

	private ContentSafetyGate(Builder builder) {
		this.stages = builder.stages.isEmpty() ? EnumSet.noneOf(ContentSafetyStage.class)
				: EnumSet.copyOf(builder.stages);
		this.matchAction = builder.matchAction;
		this.failurePolicy = builder.failurePolicy;
		this.timeoutMillis = builder.timeoutMillis;
		this.detectors = List.copyOf(builder.detectors);
		this.audit = builder.audit;
		this.executor = daemonExecutor();
	}

	public static Builder builder() {
		return new Builder();
	}

	public boolean enabled(ContentSafetyStage stage) {
		return stages.contains(stage);
	}

	public ContentSafetyDecision inspect(ContentSafetyStage stage, String text) {
		String value = text == null ? "" : text;
		if (!enabled(stage)) {
			return allow(stage, value);
		}
		try {
			List<ContentSpan> spans = detect(value);
			if (spans.isEmpty()) {
				return allow(stage, value);
			}
			ContentSpan first = spans.get(0);
			if (matchAction == ContentSafetyAction.REDACT) {
				String redacted = redact(value, spans);
				ContentSafetyDecision decision = new ContentSafetyDecision(stage, ContentSafetyAction.REDACT, redacted,
						first.start(), first.end(), status(stage, ContentSafetyAction.REDACT, ContentSafetyKeys.REASON_MATCHED,
								first.detector()));
				audit(decision, ContentSafetyKeys.REASON_MATCHED, first.detector());
				return decision;
			}
			ContentSafetyAction action = matchAction == ContentSafetyAction.ALLOW ? ContentSafetyAction.ALLOW
					: matchAction;
			String output = action == ContentSafetyAction.ALLOW ? value
					: statusText(stage, action, ContentSafetyKeys.REASON_MATCHED);
			ContentSafetyDecision decision = new ContentSafetyDecision(stage, action, output, first.start(),
					first.end(), status(stage, action, ContentSafetyKeys.REASON_MATCHED, first.detector()));
			audit(decision, ContentSafetyKeys.REASON_MATCHED, first.detector());
			return decision;
		}
		catch (TimeoutException exception) {
			return failure(stage, value, ContentSafetyKeys.REASON_TIMEOUT);
		}
		catch (RuntimeException exception) {
			return failure(stage, value, ContentSafetyKeys.REASON_DETECTOR_FAILURE);
		}
	}

	public int openSuffix(String text) {
		int open = 0;
		for (ContentDetector detector : detectors) {
			open = Math.max(open, detector.openSuffix(text));
		}
		return open;
	}

	private List<ContentSpan> detect(String text) throws TimeoutException {
		CompletableFuture<List<ContentSpan>> future = CompletableFuture.supplyAsync(() -> {
			List<ContentSpan> spans = new ArrayList<>();
			for (ContentDetector detector : detectors) {
				spans.addAll(detector.detect(text));
			}
			spans.sort(Comparator.comparingInt(ContentSpan::start));
			return List.copyOf(spans);
		}, executor);
		try {
			return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException exception) {
			future.cancel(true);
			throw exception;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("content detector interrupted", exception);
		}
		catch (java.util.concurrent.ExecutionException exception) {
			Throwable cause = exception.getCause() == null ? exception : exception.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(cause);
		}
	}

	private ContentSafetyDecision failure(ContentSafetyStage stage, String text, String reason) {
		if (failurePolicy == ContentSafetyFailurePolicy.ALLOW) {
			ContentSafetyDecision decision = allow(stage, text);
			audit(decision, reason, "");
			return decision;
		}
		ContentSafetyDecision decision = new ContentSafetyDecision(stage, ContentSafetyAction.BLOCK,
				statusText(stage, ContentSafetyAction.BLOCK, reason), -1, -1,
				status(stage, ContentSafetyAction.BLOCK, reason, ""));
		audit(decision, reason, "");
		return decision;
	}

	private void audit(ContentSafetyDecision decision, String reason, String detector) {
		if (audit != null && decision.action() != ContentSafetyAction.ALLOW) {
			audit.record(decision.stage(), decision.action(), reason, detector);
		}
		if (decision.action() != ContentSafetyAction.ALLOW) {
			log.info("content-safety stage={} action={} reason={} detector={}", decision.stage(), decision.action(),
					reason, detector);
		}
	}

	private static ExecutorService daemonExecutor() {
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "content-safety");
			thread.setDaemon(true);
			return thread;
		};
		return Executors.newCachedThreadPool(factory);
	}

	private static ContentSafetyDecision allow(ContentSafetyStage stage, String text) {
		return new ContentSafetyDecision(stage, ContentSafetyAction.ALLOW, text, -1, -1, Map.of());
	}

	private static String redact(String text, List<ContentSpan> spans) {
		StringBuilder redacted = new StringBuilder();
		int cursor = 0;
		for (ContentSpan span : spans) {
			if (span.start() < cursor) {
				continue;
			}
			redacted.append(text, cursor, span.start()).append(REDACTED);
			cursor = span.end();
		}
		redacted.append(text, cursor, text.length());
		return redacted.toString();
	}

	public static String statusText(ContentSafetyStage stage, ContentSafetyAction action, String reason) {
		return "{\"contentSafety\":\"" + action.name() + "\",\"stage\":\"" + stage.name() + "\",\"reason\":\"" + reason
				+ "\"}";
	}

	private static Map<String, Object> status(ContentSafetyStage stage, ContentSafetyAction action, String reason,
			String detector) {
		Map<String, Object> status = new LinkedHashMap<>();
		status.put(ContentSafetyKeys.STAGE, stage.name());
		status.put(ContentSafetyKeys.ACTION, action.name());
		status.put(ContentSafetyKeys.REASON, reason);
		status.put(ContentSafetyKeys.DETECTOR, detector);
		if (action == ContentSafetyAction.REQUIRE_REVIEW) {
			status.put(ContentSafetyKeys.REVIEW, Boolean.TRUE);
		}
		return status;
	}

	public static final class Builder {

		private final Set<ContentSafetyStage> stages = EnumSet.noneOf(ContentSafetyStage.class);

		private ContentSafetyAction matchAction = ContentSafetyAction.BLOCK;

		private ContentSafetyFailurePolicy failurePolicy = ContentSafetyFailurePolicy.BLOCK;

		private long timeoutMillis = 1_000L;

		private final List<ContentDetector> detectors = new ArrayList<>();

		private ContentSafetyAudit audit;

		public Builder stage(ContentSafetyStage stage) {
			this.stages.add(stage);
			return this;
		}

		public Builder stages(ContentSafetyStage... stages) {
			for (ContentSafetyStage stage : stages) {
				this.stages.add(stage);
			}
			return this;
		}

		public Builder onMatch(ContentSafetyAction action) {
			this.matchAction = action;
			return this;
		}

		public Builder onFailure(ContentSafetyFailurePolicy policy) {
			this.failurePolicy = policy;
			return this;
		}

		public Builder timeoutMillis(long timeoutMillis) {
			this.timeoutMillis = timeoutMillis;
			return this;
		}

		public Builder detector(ContentDetector detector) {
			this.detectors.add(detector);
			return this;
		}

		public Builder audit(ContentSafetyAudit audit) {
			this.audit = audit;
			return this;
		}

		public ContentSafetyGate build() {
			return new ContentSafetyGate(this);
		}

	}

}
