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
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;
import io.github.agentic.ai.graph.serializer.Serializer;
import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.github.agentic.ai.graph.serializer.check_point.CheckPointSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * The type Redis saver.
 * <p>
 * Thread identity: every operation is keyed on the user-facing thread id
 * resolved from {@code RunnableConfig}. That id is used verbatim in the
 * {@code graph:thread:meta:<user-thread-id>} key and stored in the
 * {@code thread_name} field of the reverse mapping, while the {@code thread_id}
 * hash field holds an internally generated UUID identifying one activation of
 * the thread between a release and the next reuse of the same id. Checkpoint
 * buckets are stored under that internal id so a released thread id can be
 * reused without orphaning the released checkpoint history.
 * <p>
 * Replacement: add artifact
 * {@code io.github.agentic-ai:argi-graph-persistence-redis}
 * and use {@code io.github.agentic.ai.graph.persistence.redis.RedisSaver}.
 *
 * @author disaster
 * @since 1.0.0-M2
 * @deprecated since 2.1.0 for removal. Use the replacement artifact and FQCN
 * listed above.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
public class RedisSaver implements BaseCheckpointSaver {

	// Redis key prefixes
	private static final String CHECKPOINT_PREFIX = "graph:checkpoint:content:";
	private static final String THREAD_META_PREFIX = "graph:thread:meta:";
	private static final String THREAD_REVERSE_PREFIX = "graph:thread:reverse:";
	private static final String LOCK_PREFIX = "graph:checkpoint:lock:";
	// Thread meta hash field names
	private static final String FIELD_THREAD_ID = "thread_id";
	private static final String FIELD_IS_RELEASED = "is_released";
	private static final String FIELD_THREAD_NAME = "thread_name";
	private final Serializer<Checkpoint> checkpointSerializer;
	private RedissonClient redisson;
	private final long ttl;
	private final TimeUnit ttlUnit;

	/**
	 * Protected constructor for RedisSaver.
	 * Use {@link #builder()} to create instances.
	 *
	 * @param redisson the redisson
	 * @param stateSerializer the state serializer
	 * @param ttl the time-to-live value for Redis keys, -1 means no expiration
	 * @param ttlUnit the time unit for ttl
	 */
	protected RedisSaver(RedissonClient redisson, StateSerializer stateSerializer, long ttl, TimeUnit ttlUnit) {
		requireNonNull(redisson, "redisson cannot be null");
		requireNonNull(stateSerializer, "stateSerializer cannot be null");
		this.redisson = redisson;
		this.checkpointSerializer = new CheckPointSerializer(stateSerializer);
		this.ttl = ttl;
		this.ttlUnit = ttlUnit;
	}

	/**
	 * Creates a new builder for RedisSaver.
	 * @return a new Builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	private String serializeCheckpoints(List<Checkpoint> checkpoints) throws IOException {
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
			 ObjectOutputStream oos = new ObjectOutputStream(baos)) {
			oos.writeInt(checkpoints.size());
			for (Checkpoint checkpoint : checkpoints) {
				checkpointSerializer.write(checkpoint, oos);
			}
			oos.flush();
			byte[] bytes = baos.toByteArray();
			return Base64.getEncoder().encodeToString(bytes);
		}
	}

	private LinkedList<Checkpoint> deserializeCheckpoints(String content) throws IOException, ClassNotFoundException {
		if (content == null || content.isEmpty()) {
			return new LinkedList<>();
		}
		byte[] bytes = Base64.getDecoder().decode(content);
		try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
			 ObjectInputStream ois = new ObjectInputStream(bais)) {
			int size = ois.readInt();
			LinkedList<Checkpoint> checkpoints = new LinkedList<>();
			for (int i = 0; i < size; i++) {
				checkpoints.add(checkpointSerializer.read(ois));
			}
			return checkpoints;
		}
	}

	/**
	 * Returns the internal surrogate thread id of the active entry for the given
	 * user-facing thread id, creating a new one when no active entry exists or
	 * the previous one was released.
	 *
	 * @param threadId the user-facing thread id
	 * @return the internal thread id (UUID string)
	 */
	private String getOrCreateThreadId(String threadId) {
		String metaKey = THREAD_META_PREFIX + threadId;
		RMap<String, String> meta = redisson.getMap(metaKey);

		// Check if an active thread exists
		String persistedThreadId = meta.get(FIELD_THREAD_ID);
		String isReleased = meta.get(FIELD_IS_RELEASED);

		if (persistedThreadId != null && !"true".equals(isReleased)) {
			// Active thread exists, return its internal thread id
			return persistedThreadId;
		}

		// No active thread exists or thread is released, create a new internal thread id
		String newThreadId = UUID.randomUUID().toString();
		meta.put(FIELD_THREAD_ID, newThreadId);
		meta.put(FIELD_IS_RELEASED, "false");
		if (ttl > 0) {
			meta.expire(java.time.Duration.ofMillis(ttlUnit.toMillis(ttl)));
		}

		// Set reverse mapping
		String reverseKey = THREAD_REVERSE_PREFIX + newThreadId;
		RMap<String, String> reverse = redisson.getMap(reverseKey);
		reverse.put(FIELD_THREAD_NAME, threadId);
		reverse.put(FIELD_IS_RELEASED, "false");
		if (ttl > 0) {
			reverse.expire(java.time.Duration.ofMillis(ttlUnit.toMillis(ttl)));
		}

		return newThreadId;
	}

	/**
	 * Gets the internal surrogate thread id of the active entry for the given
	 * user-facing thread id.
	 * Returns null if no active thread exists.
	 *
	 * @param threadId the user-facing thread id
	 * @return the active internal thread id, or null if not found
	 */
	private String getActiveThreadId(String threadId) {
		String metaKey = THREAD_META_PREFIX + threadId;
		RMap<String, String> meta = redisson.getMap(metaKey);

		String persistedThreadId = meta.get(FIELD_THREAD_ID);
		String isReleased = meta.get(FIELD_IS_RELEASED);

		if (persistedThreadId != null && !"true".equals(isReleased)) {
			return persistedThreadId;
		}

		return null; // No active thread exists
	}

	/**
	 * Resolves the user-facing thread id every saver operation is keyed on.
	 */
	private String threadId(RunnableConfig config) {
		return checkpointThreadId(config);
	}

	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		String threadId = threadId(config);
		RLock lock = redisson.getLock(LOCK_PREFIX + threadId);
		boolean tryLock = false;
		try {
			// 500ms timeout for read operations (list)
			tryLock = lock.tryLock(500, TimeUnit.MILLISECONDS);
			if (!tryLock) {
				throw new IllegalStateException("Timed out acquiring Redis checkpoint read lock for thread: " + threadId);
			}

			// Get the internal thread id of the active entry
			String persistedThreadId = getActiveThreadId(threadId);
			if (persistedThreadId == null) {
				return List.of();
			}

			// Use the internal thread id to query checkpoints
			RBucket<String> bucket = redisson.getBucket(CHECKPOINT_PREFIX + persistedThreadId);
			String content = bucket.get();
			return deserializeCheckpoints(content);

		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted acquiring Redis checkpoint read lock for thread: " + threadId,
					e);
		}
		catch (IOException | ClassNotFoundException e) {
			throw new RuntimeException("Failed to deserialize checkpoints", e);
		}
		finally {
			if (tryLock && lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}

	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {
		String threadId = threadId(config);
		RLock lock = redisson.getLock(LOCK_PREFIX + threadId);
		boolean tryLock = false;
		try {
			// 500ms timeout for read operations (get)
			tryLock = lock.tryLock(500, TimeUnit.MILLISECONDS);
			if (!tryLock) {
				throw new IllegalStateException("Timed out acquiring Redis checkpoint read lock for thread: " + threadId);
			}

			// Get the internal thread id of the active entry
			String persistedThreadId = getActiveThreadId(threadId);
			if (persistedThreadId == null) {
				return Optional.empty();
			}

			// Use the internal thread id to query checkpoints
			RBucket<String> bucket = redisson.getBucket(CHECKPOINT_PREFIX + persistedThreadId);
			String content = bucket.get();
			LinkedList<Checkpoint> checkpoints = deserializeCheckpoints(content);

			if (config.checkPointId().isPresent()) {
				return config.checkPointId()
						.flatMap(id -> checkpoints.stream()
								.filter(checkpoint -> checkpoint.getId().equals(id))
								.findFirst());
			}
			return getLast(checkpoints, config);

		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted acquiring Redis checkpoint read lock for thread: " + threadId,
					e);
		}
		catch (IOException | ClassNotFoundException e) {
			throw new RuntimeException("Failed to deserialize checkpoints", e);
		}
		finally {
			if (tryLock && lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}

	@Override
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
		String threadId = threadId(config);
		RLock lock = redisson.getLock(LOCK_PREFIX + threadId);
		boolean tryLock = false;
		try {
			// 3 seconds timeout for write operations (put) - longer timeout for concurrent scenarios
			tryLock = lock.tryLock(3, TimeUnit.SECONDS);
			if (!tryLock) {
				throw new RuntimeException("Failed to acquire lock for thread: " + threadId);
			}

			// Get or create the internal thread id
			String persistedThreadId = getOrCreateThreadId(threadId);

			// Use the internal thread id as key for checkpoint storage
			RBucket<String> bucket = redisson.getBucket(CHECKPOINT_PREFIX + persistedThreadId);
			String content = bucket.get();
			LinkedList<Checkpoint> checkpoints = deserializeCheckpoints(content);

			if (config.checkPointId().isPresent()) {
				// Replace Checkpoint
				String checkPointId = config.checkPointId().get();
				int index = IntStream.range(0, checkpoints.size())
						.filter(i -> checkpoints.get(i).getId().equals(checkPointId))
						.findFirst()
						.orElseThrow(() -> new NoSuchElementException(
								format("Checkpoint with id %s not found!", checkPointId)));
				checkpoints.set(index, checkpoint);
			}
			else {
				// Add Checkpoint
				checkpoints.push(checkpoint);
			}

			retainLatestCheckpoints(checkpoints, config);
			bucket.set(serializeCheckpoints(checkpoints));
			if (ttl > 0) {
				bucket.expire(java.time.Duration.ofMillis(ttlUnit.toMillis(ttl)));
			}
			return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();

		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		catch (IOException | ClassNotFoundException e) {
			throw new RuntimeException("Failed to serialize/deserialize checkpoints", e);
		}
		finally {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}

	@Override
	public Tag release(RunnableConfig config) throws Exception {
		String threadId = threadId(config);
		RLock lock = redisson.getLock(LOCK_PREFIX + threadId);
		boolean tryLock = false;
		try {
			// 3 seconds timeout for write operations (release) - longer timeout for concurrent scenarios
			tryLock = lock.tryLock(3, TimeUnit.SECONDS);
			if (!tryLock) {
				throw new RuntimeException("Failed to acquire lock for thread: " + threadId);
			}

			String metaKey = THREAD_META_PREFIX + threadId;
			RMap<String, String> meta = redisson.getMap(metaKey);

			String persistedThreadId = meta.get(FIELD_THREAD_ID);
			if (persistedThreadId == null) {
				throw new IllegalStateException("Thread not found: " + threadId);
			}

			// Mark thread as released
			meta.put(FIELD_IS_RELEASED, "true");

			// Update reverse mapping
			String reverseKey = THREAD_REVERSE_PREFIX + persistedThreadId;
			RMap<String, String> reverse = redisson.getMap(reverseKey);
			if (reverse != null) {
				reverse.put(FIELD_IS_RELEASED, "true");
			}

			// Get checkpoints for Tag (using the internal thread id)
			String contentKey = CHECKPOINT_PREFIX + persistedThreadId;
			RBucket<String> bucket = redisson.getBucket(contentKey);
			String content = bucket.get();
			Collection<Checkpoint> checkpoints = deserializeCheckpoints(content);

			return new Tag(threadId, checkpoints);

		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		catch (IOException | ClassNotFoundException e) {
			throw new RuntimeException("Failed to deserialize checkpoints", e);
		}
		finally {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}

	/**
	 * Builder class for RedisSaver.
	 */
	public static class Builder {
		private RedissonClient redisson;
		private StateSerializer stateSerializer;
		private long ttl = -1;
		private TimeUnit ttlUnit = TimeUnit.SECONDS;

		/**
		 * Sets the Redisson client.
		 *
		 * @param redisson the Redisson client
		 * @return this builder
		 */
		public Builder redisson(RedissonClient redisson) {
			this.redisson = redisson;
			return this;
		}

		/**
		 * Sets the state serializer.
		 *
		 * @param stateSerializer the state serializer
		 * @return this builder
		 */
		public Builder stateSerializer(StateSerializer stateSerializer) {
			this.stateSerializer = stateSerializer;
			return this;
		}

		/**
		 * Sets the time-to-live (TTL) for all Redis keys managed by RedisSaver.
		 * When set, checkpoint data, thread metadata, and reverse mappings will
		 * automatically expire after the specified duration.
		 * <p>
		 * Default is -1 (no expiration), which preserves backward compatibility.
		 *
		 * @param ttl the time-to-live value, must be positive; -1 means no expiration
		 * @param ttlUnit the time unit for ttl, must not be null
		 * @return this builder
		 * @throws IllegalArgumentException if ttlUnit is null or ttl is 0 or less than -1
		 */
		public Builder ttl(long ttl, TimeUnit ttlUnit) {
			if (ttlUnit == null) {
				throw new IllegalArgumentException("ttlUnit cannot be null");
			}
			if (ttl == 0 || ttl < -1) {
				throw new IllegalArgumentException("ttl must be positive or -1 (no expiration), got: " + ttl);
			}
			this.ttl = ttl;
			this.ttlUnit = ttlUnit;
			return this;
		}

		/**
		 * Builds a new RedisSaver instance.
		 * @return a new RedisSaver instance
		 * @throws IllegalArgumentException if redisson or stateSerializer is null
		 */
		public RedisSaver build() {
			if (redisson == null) {
				throw new IllegalArgumentException("redisson cannot be null");
			}
			if (stateSerializer == null) {
				this.stateSerializer = StateGraph.DEFAULT_JACKSON_SERIALIZER;
			}
			return new RedisSaver(redisson, stateSerializer, ttl, ttlUnit);
		}
	}

}
