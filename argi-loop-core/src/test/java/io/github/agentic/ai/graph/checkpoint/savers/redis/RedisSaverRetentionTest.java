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
package io.github.agentic.ai.graph.checkpoint.savers.redis;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.StateGraph;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisSaverRetentionTest {

	@Test
	void putRetainsOnlyConfiguredLatestCheckpoints() throws Exception {
		RedisSaver saver = saver();
		RunnableConfig config = RunnableConfig.builder()
			.threadId("redis-retention-thread")
			.checkpointsNumRetained(2)
			.build();

		saver.put(config, checkpoint("first"));
		saver.put(config, checkpoint("second"));
		saver.put(config, checkpoint("third"));

		assertCheckpointIds(saver.list(config), "third", "second");
		assertTrue(saver.get(config("redis-retention-thread", "first")).isEmpty());
	}

	@Test
	void replacingExistingCheckpointHonorsConfiguredRetention() throws Exception {
		RedisSaver saver = saver();
		RunnableConfig config = RunnableConfig.builder()
			.threadId("redis-replace-retention-thread")
			.checkpointsNumRetained(2)
			.build();

		saver.put(config, checkpoint("first"));
		RunnableConfig secondConfig = saver.put(config, checkpoint("second"));
		saver.put(config, checkpoint("third"));
		saver.put(secondConfig, checkpoint("second-replacement"));

		assertCheckpointIds(saver.list(config), "third", "second-replacement");
		assertTrue(saver.get(config("redis-replace-retention-thread", "first")).isEmpty());
	}

	@Test
	void putWithoutConfiguredRetentionKeepsAllCheckpoints() throws Exception {
		RedisSaver saver = saver();
		RunnableConfig config = RunnableConfig.builder().threadId("redis-unlimited-thread").build();

		saver.put(config, checkpoint("first"));
		saver.put(config, checkpoint("second"));
		saver.put(config, checkpoint("third"));

		assertCheckpointIds(saver.list(config), "third", "second", "first");
		assertEquals("first", saver.get(config("redis-unlimited-thread", "first")).orElseThrow().getId());
	}

	private static RedisSaver saver() {
		return RedisSaver.builder()
			.redisson(redisson())
			.stateSerializer(StateGraph.DEFAULT_JACKSON_SERIALIZER)
			.build();
	}

	private static RunnableConfig config(String threadId, String checkpointId) {
		return RunnableConfig.builder().threadId(threadId).checkPointId(checkpointId).build();
	}

	private static Checkpoint checkpoint(String id) {
		return Checkpoint.builder()
			.id(id)
			.state(Map.of("value", id))
			.nodeId(id)
			.nextNodeId(id + "-next")
			.build();
	}

	private static void assertCheckpointIds(Collection<Checkpoint> checkpoints, String... ids) {
		List<String> actualIds = checkpoints.stream().map(Checkpoint::getId).toList();
		assertEquals(List.of(ids), actualIds);
	}

	private static RedissonClient redisson() {
		RedissonClient redisson = mock(RedissonClient.class);
		Map<String, Map<String, String>> maps = new LinkedHashMap<>();
		Map<String, String> buckets = new LinkedHashMap<>();

		when(redisson.getLock(anyString())).thenAnswer(invocation -> lock());
		when(redisson.<String, String>getMap(anyString()))
			.thenAnswer(invocation -> map(maps.computeIfAbsent(invocation.getArgument(0), key -> new LinkedHashMap<>())));
		when(redisson.<String>getBucket(anyString()))
			.thenAnswer(invocation -> bucket(buckets, invocation.getArgument(0)));
		return redisson;
	}

	private static RLock lock() throws InterruptedException {
		RLock lock = mock(RLock.class);
		when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
		when(lock.isHeldByCurrentThread()).thenReturn(true);
		return lock;
	}

	@SuppressWarnings("unchecked")
	private static RMap<String, String> map(Map<String, String> backing) {
		RMap<String, String> map = mock(RMap.class);
		when(map.get(anyString())).thenAnswer(invocation -> backing.get(invocation.getArgument(0)));
		when(map.put(anyString(), anyString()))
			.thenAnswer(invocation -> backing.put(invocation.getArgument(0), invocation.getArgument(1)));
		when(map.expire(any(Duration.class))).thenReturn(true);
		return map;
	}

	@SuppressWarnings("unchecked")
	private static RBucket<String> bucket(Map<String, String> backing, String key) {
		RBucket<String> bucket = mock(RBucket.class);
		when(bucket.get()).thenAnswer(invocation -> backing.get(key));
		doAnswer(invocation -> {
			backing.put(key, invocation.getArgument(0));
			return null;
		}).when(bucket).set(anyString());
		when(bucket.expire(any(Duration.class))).thenReturn(true);
		return bucket;
	}

}
