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
package io.github.agentic.ai.agent.orchestration.tools.task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import io.github.agentic.ai.workflow.graph.observation.trace.AgentTraceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 复现 #228：关联记录必须有保留期兜底，过期条目永不服务并被惰性清扫。 */
class TaskTraceCorrelationLifecycleTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T12:00:00Z"));

	private final TaskTraceCorrelation correlation = new TaskTraceCorrelation(clock, Duration.ofMinutes(30));

	@Test
	void anExpiredEntryIsNeverServedAndIsSweptByTheNextRecord() {
		correlation.record("task-1", context("task-1"));

		clock.advance(Duration.ofMinutes(31));

		assertThat(correlation.correlation("task-1")).as("expired entries must never be served").isEmpty();
		assertThat(correlation.retainedCount()).as("the expired entry must be gone").isZero();

		correlation.record("task-2", context("task-2"));

		assertThat(correlation.retainedCount()).as("the sweep must have removed the stale entry only").isEqualTo(1);
	}

	@Test
	void aLiveEntryIsServedUntilItsRetentionElapses() {
		correlation.record("task-1", context("task-1"));
		assertThat(correlation.correlation("task-1")).isPresent();

		// Sliding retention (#370 review): reading a live entry re-anchors its window.
		clock.advance(Duration.ofMinutes(29));
		assertThat(correlation.correlation("task-1")).as("inside the sliding window").isPresent();

		clock.advance(Duration.ofMinutes(29));
		assertThat(correlation.correlation("task-1")).as("still inside the re-anchored window").isPresent();

		// With no reads for a full retention window the entry finally lapses.
		clock.advance(Duration.ofMinutes(31));
		assertThat(correlation.correlation("task-1")).as("idle past the sliding window").isEmpty();
	}

	@Test
	void forgetIsIdempotentAndSafeForUnknownTaskIds() {
		correlation.record("task-1", context("task-1"));

		correlation.forget("task-1");
		correlation.forget("task-1");
		correlation.forget("unknown-task");

		assertThat(correlation.retainedCount()).isZero();
		assertThat(correlation.correlation("task-1")).isEmpty();
	}

	private AgentTraceContext context(String taskId) {
		return AgentTraceContext.capture(null, null).withTaskId(taskId);
	}

	/** Mutable clock for deterministic retention tests (the #331 metrics pattern). */
	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return this.now;
		}

	}

}
