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
package io.github.agentic.ai.graph.checkpoint.savers;

import io.github.agentic.ai.graph.CompileConfig;
import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.config.SaverConfig;
import io.github.agentic.ai.graph.checkpoint.savers.redis.RedisSaver;
import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import io.github.agentic.ai.graph.state.strategy.ReplaceStrategy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import static io.github.agentic.ai.graph.StateGraph.END;
import static io.github.agentic.ai.graph.StateGraph.START;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisSaverReadFailureTest {

	private static final StateSerializer SERIALIZER = new ArgiJacksonStateSerializer(OverAllState::new);

	@AfterEach
	void clearInterruptFlag() {
		Thread.interrupted();
	}

	@Test
	void getThrowsWhenReadLockTimesOut() throws Exception {
		RedisSaver saver = saverWithLock(lock(false));
		RunnableConfig config = RunnableConfig.builder().threadId("contention-get").build();

		assertThrows(IllegalStateException.class, () -> saver.get(config));
	}

	@Test
	void listThrowsWhenReadLockTimesOut() throws Exception {
		RedisSaver saver = saverWithLock(lock(false));
		RunnableConfig config = RunnableConfig.builder().threadId("contention-list").build();

		assertThrows(IllegalStateException.class, () -> saver.list(config));
	}

	@Test
	void getTimeoutCleanupDoesNotMaskReadFailure() throws Exception {
		RedisSaver saver = saverWithLock(lockThrowingOnOwnershipAfterTimeout());
		RunnableConfig config = RunnableConfig.builder().threadId("contention-get-cleanup").build();

		IllegalStateException exception = assertThrows(IllegalStateException.class, () -> saver.get(config));

		assertTrue(exception.getMessage().contains("Timed out acquiring Redis checkpoint read lock"));
	}

	@Test
	void listTimeoutCleanupDoesNotMaskReadFailure() throws Exception {
		RedisSaver saver = saverWithLock(lockThrowingOnOwnershipAfterTimeout());
		RunnableConfig config = RunnableConfig.builder().threadId("contention-list-cleanup").build();

		IllegalStateException exception = assertThrows(IllegalStateException.class, () -> saver.list(config));

		assertTrue(exception.getMessage().contains("Timed out acquiring Redis checkpoint read lock"));
	}

	@Test
	void interruptedGetRestoresInterruptFlagAndThrows() throws Exception {
		RedisSaver saver = saverWithLock(interruptedLock());
		RunnableConfig config = RunnableConfig.builder().threadId("interrupted-get").build();

		assertThrows(IllegalStateException.class, () -> saver.get(config));

		assertTrue(Thread.currentThread().isInterrupted());
	}

	@Test
	void interruptedListRestoresInterruptFlagAndThrows() throws Exception {
		RedisSaver saver = saverWithLock(interruptedLock());
		RunnableConfig config = RunnableConfig.builder().threadId("interrupted-list").build();

		assertThrows(IllegalStateException.class, () -> saver.list(config));

		assertTrue(Thread.currentThread().isInterrupted());
	}

	@Test
	void interruptedGetCleanupDoesNotMaskReadFailureOrInterruptFlag() throws Exception {
		RedisSaver saver = saverWithLock(lockThrowingOnOwnershipAfterInterrupt());
		RunnableConfig config = RunnableConfig.builder().threadId("interrupted-get-cleanup").build();

		IllegalStateException exception = assertThrows(IllegalStateException.class, () -> saver.get(config));

		assertTrue(exception.getCause() instanceof InterruptedException);
		assertTrue(Thread.currentThread().isInterrupted());
	}

	@Test
	void interruptedListCleanupDoesNotMaskReadFailureOrInterruptFlag() throws Exception {
		RedisSaver saver = saverWithLock(lockThrowingOnOwnershipAfterInterrupt());
		RunnableConfig config = RunnableConfig.builder().threadId("interrupted-list-cleanup").build();

		IllegalStateException exception = assertThrows(IllegalStateException.class, () -> saver.list(config));

		assertTrue(exception.getCause() instanceof InterruptedException);
		assertTrue(Thread.currentThread().isInterrupted());
	}

	@Test
	void resumeDoesNotExecuteNodeWhenCheckpointReadFails() throws Exception {
		RedisSaver saver = saverWithLock(lock(false));
		AtomicInteger executedNodes = new AtomicInteger();
		StateGraph graph = new StateGraph(() -> Map.<String, KeyStrategy>of("value", new ReplaceStrategy()))
			.addNode("node", node_async(state -> {
				executedNodes.incrementAndGet();
				return Map.of("value", "executed");
			}))
			.addEdge(START, "node")
			.addEdge("node", END);
		CompileConfig compileConfig = CompileConfig.builder()
			.saverConfig(SaverConfig.builder().register(saver).build())
			.build();

		assertThrows(IllegalStateException.class, () -> graph.compile(compileConfig)
			.invoke(Map.of(), RunnableConfig.builder().threadId("resume-failed-read").build().withResume()));
		assertEquals(0, executedNodes.get());
	}

	@Test
	void missingThreadMetadataStillReturnsEmpty() throws Exception {
		RedissonClient redisson = mock(RedissonClient.class);
		RLock lock = lock(true);
		RMap<String, String> meta = mock(RMap.class);
		when(redisson.getLock("graph:checkpoint:lock:missing-meta")).thenReturn(lock);
		when(redisson.<String, String>getMap("graph:thread:meta:missing-meta")).thenReturn(meta);
		RedisSaver saver = RedisSaver.builder().redisson(redisson).stateSerializer(SERIALIZER).build();
		RunnableConfig config = RunnableConfig.builder().threadId("missing-meta").build();

		assertEquals(Optional.empty(), saver.get(config));
		assertTrue(saver.list(config).isEmpty());
		assertFalse(Thread.currentThread().isInterrupted());
	}

	private static RedisSaver saverWithLock(RLock lock) {
		RedissonClient redisson = mock(RedissonClient.class);
		when(redisson.getLock(org.mockito.ArgumentMatchers.anyString())).thenReturn(lock);
		return RedisSaver.builder().redisson(redisson).stateSerializer(SERIALIZER).build();
	}

	private static RLock lock(boolean acquired) throws InterruptedException {
		RLock lock = mock(RLock.class);
		when(lock.tryLock(500, TimeUnit.MILLISECONDS)).thenReturn(acquired);
		when(lock.isHeldByCurrentThread()).thenReturn(acquired);
		return lock;
	}

	private static RLock interruptedLock() throws InterruptedException {
		RLock lock = mock(RLock.class);
		when(lock.tryLock(500, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("interrupted"));
		when(lock.isHeldByCurrentThread()).thenReturn(false);
		return lock;
	}

	private static RLock lockThrowingOnOwnershipAfterTimeout() throws InterruptedException {
		RLock lock = mock(RLock.class);
		when(lock.tryLock(500, TimeUnit.MILLISECONDS)).thenReturn(false);
		when(lock.isHeldByCurrentThread()).thenThrow(new RedisException("ownership query failed"));
		return lock;
	}

	private static RLock lockThrowingOnOwnershipAfterInterrupt() throws InterruptedException {
		RLock lock = mock(RLock.class);
		when(lock.tryLock(500, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("interrupted"));
		when(lock.isHeldByCurrentThread()).thenThrow(new RedisException("ownership query failed"));
		return lock;
	}

}
