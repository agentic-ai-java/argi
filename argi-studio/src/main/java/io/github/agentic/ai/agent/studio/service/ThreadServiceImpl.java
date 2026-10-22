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

package io.github.agentic.ai.agent.studio.service;

import io.github.agentic.ai.agent.studio.dto.ListThreadsResponse;
import io.github.agentic.ai.agent.studio.dto.Thread;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * In-memory implementation of ThreadService.
 * For production use, this should be backed by a database or distributed cache.
 */
@Service
public class ThreadServiceImpl implements ThreadService {

	private static final Logger log = LoggerFactory.getLogger(ThreadServiceImpl.class);

	// In-memory storage uses encoded segments so user-controlled identifiers cannot collide.
	// Metadata and state live in one record per key so every create, update and delete
	// is a single atomic map operation.
	private final Map<String, ThreadRecord> threads = new ConcurrentHashMap<>();

	@Override
	public Mono<Optional<Thread>> getThread(
			String appName, String userId, String threadId, Optional<Map<String, Object>> state) {
		return Mono.fromCallable(() -> {
			String key = buildKey(appName, userId, threadId);
			ThreadRecord record = threads.get(key);

			return Optional.ofNullable(record)
					.map(existing -> withValues(existing.thread(), state.orElseGet(existing::state)));
		});
	}

	@Override
	public Mono<ListThreadsResponse> listThreads(String appName, String userId) {
		return Mono.fromCallable(() -> {
			String prefix = buildKeyPrefix(appName, userId);

			List<Thread> userThreads = threads.entrySet().stream()
					.filter(entry -> entry.getKey().startsWith(prefix))
					.map(entry -> withValues(entry.getValue().thread(), entry.getValue().state()))
					.collect(Collectors.toList());

			log.debug("Found {} threads for app={}, user={}", userThreads.size(), appName, userId);
			return ListThreadsResponse.of(userThreads);
		});
	}

	@Override
	public Mono<Thread> createThread(
			String appName, String userId, Map<String, Object> initialState, String threadId) {
		return Mono.fromCallable(() -> {
			// Generate thread ID if not provided
			String finalThreadId = (threadId == null || threadId.trim().isEmpty())
					? generateThreadId()
					: threadId;

			String key = buildKey(appName, userId, finalThreadId);
			Map<String, Object> state = stateCopy(initialState);

			// Create new thread
			Thread newThread = Thread.builder(finalThreadId)
					.appName(appName)
					.userId(userId)
					.values(state)
					.build();

			ThreadRecord existing = threads.putIfAbsent(key, new ThreadRecord(newThread, state, new Object()));
			if (existing != null) {
				log.warn("Attempted to create duplicate thread: {}", finalThreadId);
				throw new IllegalStateException("Thread already exists: " + finalThreadId);
			}

			log.info("Created thread: {} for app={}, user={}", finalThreadId, appName, userId);
			return newThread;
		});
	}

	@Override
	public Mono<Void> deleteThread(String appName, String userId, String threadId) {
		return Mono.fromRunnable(() -> {
			String key = buildKey(appName, userId, threadId);
			ThreadRecord removed = threads.remove(key);

			if (removed != null) {
				log.info("Deleted thread: {} for app={}, user={}", threadId, appName, userId);
			}
			else {
				log.warn("Attempted to delete non-existent thread: {}", threadId);
			}
		});
	}

	/**
	 * Gets the state for a thread.
	 *
	 * @param appName The application name.
	 * @param userId The user ID.
	 * @param threadId The thread ID.
	 * @return A copy of the thread state, or empty map if not found.
	 */
	public Map<String, Object> getThreadState(String appName, String userId, String threadId) {
		ThreadRecord record = threads.get(buildKey(appName, userId, threadId));
		return record == null ? new ConcurrentHashMap<>() : stateCopy(record.state());
	}

	/**
	 * Updates the state for a thread. The update applies to the thread instance that
	 * exists when the call starts: it is dropped if that thread is deleted, including
	 * when a thread with the same ID is created again before the update is applied.
	 *
	 * @param appName The application name.
	 * @param userId The user ID.
	 * @param threadId The thread ID.
	 * @param state The new state.
	 */
	public void updateThreadState(
			String appName, String userId, String threadId, Map<String, Object> state) {
		String key = buildKey(appName, userId, threadId);
		ThreadRecord observed = threads.get(key);
		if (observed == null) {
			return;
		}
		Map<String, Object> stateCopy = stateCopy(state);
		ThreadRecord updated = threads.computeIfPresent(key,
				(existingKey, current) -> current.generation() == observed.generation()
						? new ThreadRecord(withValues(current.thread(), stateCopy), stateCopy, current.generation())
						: current);
		if (updated != null && updated.generation() == observed.generation()) {
			log.debug("Updated state for thread: {}", threadId);
		}
		else {
			log.debug("Dropped state update for deleted thread: {}", threadId);
		}
	}

	/**
	 * Thread metadata and state stored under one key. The generation identifies one
	 * created thread instance and is kept across state updates.
	 */
	private record ThreadRecord(Thread thread, Map<String, Object> state, Object generation) {
	}

	private Thread withValues(Thread thread, Map<String, Object> state) {
		return Thread.builder(thread.threadId())
				.appName(thread.appName())
				.userId(thread.userId())
				.values(stateCopy(state))
				.build();
	}

	private Map<String, Object> stateCopy(Map<String, Object> state) {
		if (state == null || state.isEmpty()) {
			return new ConcurrentHashMap<>();
		}
		return new ConcurrentHashMap<>(state);
	}

	/**
	 * Builds a storage key for a thread.
	 */
	private String buildKey(String appName, String userId, String threadId) {
		return "%s:%s:%s".formatted(keySegment(appName), keySegment(userId), keySegment(threadId));
	}

	/**
	 * Builds a key prefix for filtering threads by app and user.
	 */
	private String buildKeyPrefix(String appName, String userId) {
		return "%s:%s:".formatted(keySegment(appName), keySegment(userId));
	}

	private String keySegment(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Generates a unique thread ID.
	 */
	private String generateThreadId() {
		return UUID.randomUUID().toString();
	}
}
