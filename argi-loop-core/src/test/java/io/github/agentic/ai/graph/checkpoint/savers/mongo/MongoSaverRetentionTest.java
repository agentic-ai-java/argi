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
package io.github.agentic.ai.graph.checkpoint.savers.mongo;

import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.Checkpoint;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import com.mongodb.ClientSessionOptions;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoSaverRetentionTest {

	private static final String THREAD_ID = "retained-thread-id";

	private static final String CHECKPOINT_DOC_ID = "mongo:checkpoint:content:" + THREAD_ID;

	private static final String CHECKPOINT_CONTENT_KEY = "checkpoint_content";

	@Test
	void putRetainsOnlyConfiguredLatestCheckpointsInStoredContent() throws Exception {
		Map<String, Document> checkpointDocuments = new LinkedHashMap<>();
		MongoSaver saver = buildSaver(checkpointDocuments);
		RunnableConfig config = RunnableConfig.builder()
				.threadId("retained-thread")
				.checkpointsNumRetained(2)
				.build();
		Checkpoint first = checkpoint("first");
		Checkpoint second = checkpoint("second");
		Checkpoint third = checkpoint("third");

		saver.put(config, first);
		saver.put(config, second);
		saver.put(config, third);

		LinkedList<Checkpoint> storedCheckpoints = deserializeCheckpoints(saver,
				checkpointDocuments.get(CHECKPOINT_DOC_ID).getString(CHECKPOINT_CONTENT_KEY));
		assertEquals(2, storedCheckpoints.size());
		assertEquals(third.getId(), storedCheckpoints.get(0).getId());
		assertEquals(second.getId(), storedCheckpoints.get(1).getId());
		assertTrue(storedCheckpoints.stream().noneMatch(checkpoint -> checkpoint.getId().equals(first.getId())));
	}

	@Test
	void putRetainsOnlyConfiguredLatestCheckpointsWhenReplacingExistingCheckpoint() throws Exception {
		Map<String, Document> checkpointDocuments = new LinkedHashMap<>();
		MongoSaver saver = buildSaver(checkpointDocuments);
		RunnableConfig config = RunnableConfig.builder()
				.threadId("retained-thread")
				.checkpointsNumRetained(2)
				.build();
		Checkpoint first = checkpoint("first");
		Checkpoint second = checkpoint("second");
		Checkpoint third = checkpoint("third");

		saver.put(config, first);
		RunnableConfig secondConfig = saver.put(config, second);
		saver.put(config, third);
		saver.put(secondConfig, checkpoint("second-replacement"));

		LinkedList<Checkpoint> storedCheckpoints = deserializeCheckpoints(saver,
				checkpointDocuments.get(CHECKPOINT_DOC_ID).getString(CHECKPOINT_CONTENT_KEY));
		assertEquals(2, storedCheckpoints.size());
		assertEquals(third.getId(), storedCheckpoints.get(0).getId());
		assertTrue(storedCheckpoints.stream().noneMatch(checkpoint -> checkpoint.getId().equals(first.getId())));
	}

	private static MongoSaver buildSaver(Map<String, Document> checkpointDocuments) {
		MongoClient client = mock(MongoClient.class);
		MongoDatabase database = mock(MongoDatabase.class);
		ClientSession session = mock(ClientSession.class);
		MongoCollection<Document> threadMetaCollection = mock(MongoCollection.class);
		MongoCollection<Document> checkpointCollection = mock(MongoCollection.class);

		when(client.getDatabase(anyString())).thenReturn(database);
		when(client.startSession(any(ClientSessionOptions.class))).thenReturn(session);
		when(database.getCollection("thread_meta")).thenReturn(threadMetaCollection);
		when(database.getCollection("checkpoint_collection")).thenReturn(checkpointCollection);

		Document activeThreadMeta = new Document("_id", "mongo:thread:meta:retained-thread")
				.append("thread_id", THREAD_ID)
				.append("is_released", false);
		when(threadMetaCollection.findOneAndUpdate(eq(session), any(Bson.class), any(Bson.class),
				any(FindOneAndUpdateOptions.class))).thenReturn(activeThreadMeta);

		when(checkpointCollection.find(eq(session), any(Bson.class))).thenAnswer(invocation -> {
			FindIterable<Document> iterable = mock(FindIterable.class);
			Document document = checkpointDocuments.get(CHECKPOINT_DOC_ID);
			when(iterable.first()).thenReturn(document == null ? null : new Document(document));
			return iterable;
		});
		doAnswer(invocation -> {
			Document document = invocation.getArgument(1);
			checkpointDocuments.put(document.getString("_id"), new Document(document));
			return null;
		}).when(checkpointCollection).insertOne(eq(session), any(Document.class));
		when(checkpointCollection.replaceOne(eq(session), any(Bson.class), any(Document.class),
				any(ReplaceOptions.class))).thenAnswer(invocation -> {
					Document document = invocation.getArgument(2);
					checkpointDocuments.put(document.getString("_id"), new Document(document));
					return mock(UpdateResult.class);
				});
		when(checkpointCollection.replaceOne(eq(session), any(Bson.class), any(Document.class)))
				.thenAnswer(invocation -> {
					Document document = invocation.getArgument(2);
					checkpointDocuments.put(document.getString("_id"), new Document(document));
					return mock(UpdateResult.class);
				});

		return MongoSaver.builder().client(client).build();
	}

	private static Checkpoint checkpoint(String value) {
		return Checkpoint.builder()
				.state(Map.of("value", value))
				.nodeId(value)
				.nextNodeId(value + "-next")
				.build();
	}

	@SuppressWarnings("unchecked")
	private static LinkedList<Checkpoint> deserializeCheckpoints(MongoSaver saver, String content) throws Exception {
		Method method = MongoSaver.class.getDeclaredMethod("deserializeCheckpoints", String.class);
		method.setAccessible(true);
		return (LinkedList<Checkpoint>) method.invoke(saver, content);
	}

}
