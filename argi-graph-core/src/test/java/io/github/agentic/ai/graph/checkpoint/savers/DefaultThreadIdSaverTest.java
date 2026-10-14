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

import com.mongodb.BasicDBObject;
import com.mongodb.ClientSessionOptions;
import com.mongodb.client.ClientSession;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.RunnableConfig;
import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.savers.mongo.MongoSaver;
import io.github.agentic.ai.graph.checkpoint.savers.redis.RedisSaver;
import io.github.agentic.ai.graph.serializer.StateSerializer;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.ArgiJacksonStateSerializer;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultThreadIdSaverTest {

	private static final StateSerializer SERIALIZER = new ArgiJacksonStateSerializer(OverAllState::new);

	@Test
	void redisListWithoutThreadIdUsesDefaultThreadName() throws Exception {
		RedissonClient redisson = mock(RedissonClient.class);
		RLock lock = mock(RLock.class);
		RMap<String, String> meta = mock(RMap.class);
		when(redisson.getLock("graph:checkpoint:lock:" + BaseCheckpointSaver.THREAD_ID_DEFAULT)).thenReturn(lock);
		when(lock.tryLock(500, TimeUnit.MILLISECONDS)).thenReturn(true);
		when(lock.isHeldByCurrentThread()).thenReturn(true);
		when(redisson.<String, String>getMap("graph:thread:meta:" + BaseCheckpointSaver.THREAD_ID_DEFAULT))
			.thenReturn(meta);

		RedisSaver saver = RedisSaver.builder().redisson(redisson).stateSerializer(SERIALIZER).build();

		Collection<?> checkpoints = saver.list(RunnableConfig.builder().build());

		assertTrue(checkpoints.isEmpty());
		verify(redisson).getLock("graph:checkpoint:lock:" + BaseCheckpointSaver.THREAD_ID_DEFAULT);
		verify(redisson).getMap("graph:thread:meta:" + BaseCheckpointSaver.THREAD_ID_DEFAULT);
	}

	@Test
	void mongoGetWithoutThreadIdUsesDefaultThreadName() {
		MongoClient client = mock(MongoClient.class);
		MongoDatabase database = mock(MongoDatabase.class);
		ClientSession session = mock(ClientSession.class);
		MongoCollection<Document> threadMeta = mock(MongoCollection.class);
		FindIterable<Document> emptyFind = mock(FindIterable.class);

		when(client.getDatabase(any())).thenReturn(database);
		when(client.startSession(any(ClientSessionOptions.class))).thenReturn(session);
		when(database.getCollection("thread_meta")).thenReturn(threadMeta);
		when(threadMeta.find(eq(session), any(Bson.class))).thenReturn(emptyFind);
		when(emptyFind.first()).thenReturn(null);

		MongoSaver saver = MongoSaver.builder().client(client).stateSerializer(SERIALIZER).build();

		Optional<?> checkpoint = saver.get(RunnableConfig.builder().build());

		assertTrue(checkpoint.isEmpty());
		org.mockito.ArgumentCaptor<Bson> filterCaptor = org.mockito.ArgumentCaptor.forClass(Bson.class);
		verify(threadMeta).find(eq(session), filterCaptor.capture());
		BasicDBObject filter = assertInstanceOf(BasicDBObject.class, filterCaptor.getValue());
		assertEquals("mongo:thread:meta:" + BaseCheckpointSaver.THREAD_ID_DEFAULT, filter.getString("_id"));
	}

}
