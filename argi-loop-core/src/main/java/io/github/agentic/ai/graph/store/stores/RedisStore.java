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
package io.github.agentic.ai.graph.store.stores;

import io.github.agentic.ai.graph.store.NamespaceListRequest;
import io.github.agentic.ai.graph.store.StoreItem;
import io.github.agentic.ai.graph.store.StoreSearchRequest;
import io.github.agentic.ai.graph.store.StoreSearchResult;
import io.github.agentic.ai.graph.store.constant.StoreConstant;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Redis-like implementation of the Store interface using in-memory storage.
 * <p>
 * This implementation simulates Redis behavior using a ConcurrentHashMap for environments
 * where actual Redis dependencies are not available. For production use with actual
 * Redis, replace this with a proper Redis client implementation.
 * </p>
 *
 * @since 1.0.0.3
 */
public class RedisStore extends BaseStore {

	private final Map<String, String> redisLikeStorage;

	private final ObjectMapper objectMapper;

	private final String keyPrefix;

	private final ReadWriteLock lock = new ReentrantReadWriteLock();

	/**
	 * Constructor with default key prefix.
	 */
	public RedisStore() {
		this(StoreConstant.REDIS_KEY_PREFIX);
	}

	/**
	 * Constructor with custom key prefix.
	 * @param keyPrefix Redis key prefix
	 */
	public RedisStore(String keyPrefix) {
		this.redisLikeStorage = new HashMap<>();
		this.keyPrefix = keyPrefix;
		this.objectMapper = new ObjectMapper();
		this.objectMapper.findAndRegisterModules();
	}

	@Override
	public void putItem(StoreItem item) {
		validatePutItem(item);

		lock.writeLock().lock();
		try {
			String storeKey = createStoreKey(item.getNamespace(), item.getKey());
			String redisKey = keyPrefix + storeKey;
			String itemJson = objectMapper.writeValueAsString(item);
			redisLikeStorage.put(redisKey, itemJson);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to store item in Redis-like storage", e);
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public Optional<StoreItem> getItem(List<String> namespace, String key) {
		validateGetItem(namespace, key);

		lock.readLock().lock();
		try {
			String storeKey = createStoreKey(namespace, key);
			String value = redisLikeStorage.get(keyPrefix + storeKey);

			if (value == null) {
				return Optional.empty();
			}

			StoreItem item = objectMapper.readValue(value, StoreItem.class);
			return Optional.of(item);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to retrieve item from Redis-like storage", e);
		}
		finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public boolean deleteItem(List<String> namespace, String key) {
		validateDeleteItem(namespace, key);

		lock.writeLock().lock();
		try {
			String storeKey = createStoreKey(namespace, key);
			return redisLikeStorage.remove(keyPrefix + storeKey) != null;
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public StoreSearchResult searchItems(StoreSearchRequest searchRequest) {
		validateSearchItems(searchRequest);

		lock.readLock().lock();
		try {
			List<StoreItem> allItems = getAllItems();

			// Apply filters
			List<StoreItem> filteredItems = allItems.stream()
				.filter(item -> matchesSearchCriteria(item, searchRequest))
				.collect(Collectors.toList());

			// Sort items
			if (!searchRequest.getSortFields().isEmpty()) {
				filteredItems.sort(createComparator(searchRequest));
			}

			long totalCount = filteredItems.size();

			// Apply pagination
			int offset = searchRequest.getOffset();
			int limit = searchRequest.getLimit();

			if (offset >= filteredItems.size()) {
				return StoreSearchResult.of(Collections.emptyList(), totalCount, offset, limit);
			}

			int endIndex = Math.min(offset + limit, filteredItems.size());
			List<StoreItem> resultItems = filteredItems.subList(offset, endIndex);

			return StoreSearchResult.of(resultItems, totalCount, offset, limit);
		}
		finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public List<String> listNamespaces(NamespaceListRequest namespaceRequest) {
		validateListNamespaces(namespaceRequest);

		lock.readLock().lock();
		try {
			Set<String> namespaceSet = new HashSet<>();
			List<String> prefixFilter = namespaceRequest.getNamespace();

			List<StoreItem> allItems = getAllItems();

			for (StoreItem item : allItems) {
				List<String> itemNamespace = item.getNamespace();

				// Check if namespace starts with prefix filter
				if (!prefixFilter.isEmpty() && !startsWithPrefix(itemNamespace, prefixFilter)) {
					continue;
				}

				// Generate all possible namespace paths up to maxDepth
				int maxDepth = namespaceRequest.getMaxDepth();
				int depth = (maxDepth == -1) ? itemNamespace.size() : Math.min(maxDepth, itemNamespace.size());
				int startDepth = prefixFilter.isEmpty() ? 1 : prefixFilter.size();

				for (int i = startDepth; i <= depth; i++) {
					String namespacePath = String.join("/", itemNamespace.subList(0, i));
					namespaceSet.add(namespacePath);
				}
			}

			List<String> namespaces = new ArrayList<>(namespaceSet);
			Collections.sort(namespaces);

			// Apply pagination
			int offset = namespaceRequest.getOffset();
			int limit = namespaceRequest.getLimit();

			if (offset >= namespaces.size()) {
				return Collections.emptyList();
			}

			int endIndex = Math.min(offset + limit, namespaces.size());
			return namespaces.subList(offset, endIndex);
		}
		finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public void clear() {
		lock.writeLock().lock();
		try {
			Set<String> keysToRemove = redisLikeStorage.keySet().stream().filter(key -> key.startsWith(keyPrefix))
				.collect(Collectors.toSet());
			keysToRemove.forEach(redisLikeStorage::remove);
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public long size() {
		lock.readLock().lock();
		try {
			return redisLikeStorage.keySet().stream().filter(key -> key.startsWith(keyPrefix)).count();
		}
		finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public boolean isEmpty() {
		return size() == 0;
	}

	/**
	 * Get all items using the configured key prefix.
	 * @return list of all items
	 */
	private List<StoreItem> getAllItems() {
		List<StoreItem> items = new ArrayList<>();

		for (Map.Entry<String, String> entry : redisLikeStorage.entrySet()) {
			if (entry.getKey().startsWith(keyPrefix)) {
				try {
					items.add(objectMapper.readValue(entry.getValue(), StoreItem.class));
				}
				catch (Exception e) {
					// Skip invalid items
				}
			}
		}

		return items;
	}

}
