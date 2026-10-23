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
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import com.mongodb.BasicDBObject;
import com.mongodb.ClientSessionOptions;
import com.mongodb.MongoDriverInformation;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.String.format;

/**
 * MongoDB checkpoint saver.
 * <p>
 * Thread identity: every operation is keyed on the user-facing thread id
 * resolved from {@code RunnableConfig}. That id is used verbatim as the
 * {@code _id} of the {@code thread_meta} document
 * ({@code mongo:thread:meta:<user-thread-id>}) and stored in its
 * {@code thread_name} field, while the {@code thread_id} field holds an
 * internally generated UUID identifying one activation of the thread between a
 * release and the next reuse of the same id. Checkpoint documents are stored
 * under that internal id so a released thread id can be reused without
 * orphaning the released checkpoint history.
 * <p>
 * Replacement: add artifact
 * {@code io.github.agentic-ai:argi-graph-persistence-mongodb}
 * and use {@code io.github.agentic.ai.graph.persistence.mongodb.MongoSaver}.
 *
 * @deprecated since 2.1.0 for removal. Use the replacement artifact and FQCN
 * listed above.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
public class MongoSaver implements BaseCheckpointSaver {

	private static final Logger logger = LoggerFactory.getLogger(MongoSaver.class);

	private static final MongoDriverInformation DRIVER_INFO = MongoDriverInformation.builder()
			.driverName("agentic-ai")
			.build();
	private static final String DB_NAME = "check_point_db";
	private static final String THREAD_META_COLLECTION = "thread_meta";
	private static final String CHECKPOINT_COLLECTION = "checkpoint_collection";
	private static final String THREAD_META_PREFIX = "mongo:thread:meta:";
	private static final String CHECKPOINT_PREFIX = "mongo:checkpoint:content:";
	private static final String DOCUMENT_CONTENT_KEY = "checkpoint_content";
	// Thread meta document field names
	private static final String FIELD_THREAD_ID = "thread_id";
	private static final String FIELD_IS_RELEASED = "is_released";
	private static final String FIELD_THREAD_NAME = "thread_name";
	private final Serializer<Checkpoint> checkpointSerializer;
	private MongoClient client;
	private MongoDatabase database;
	private TransactionOptions txnOptions;

	/**
	 * Protected constructor for MongoSaver.
	 * Use {@link #builder()} to create instances.
	 *
	 * @param client the client
	 * @param stateSerializer the state serializer
	 */
	protected MongoSaver(MongoClient client, StateSerializer stateSerializer) {
		Objects.requireNonNull(client, "client cannot be null");
		Objects.requireNonNull(stateSerializer, "stateSerializer cannot be null");
		this.client = client;
		try {
			Method appendMetadata = client.getClass().getMethod("appendMetadata", MongoDriverInformation.class);
			appendMetadata.invoke(client, DRIVER_INFO);
		}
		catch (Exception ignored) {
			// appendMetadata not available in this driver version — skip silently
		}
		this.database = client.getDatabase(DB_NAME);
		this.txnOptions = TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build();
		this.checkpointSerializer = new CheckPointSerializer(stateSerializer);
		Runtime.getRuntime().addShutdownHook(new Thread(client::close));
	}

	/**
	 * Creates a new builder for MongoSaver.
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
	 * This method uses atomic operations to prevent race conditions in concurrent scenarios.
	 * Uses findOneAndUpdate with conditional logic to ensure thread-safe creation.
	 *
	 * @param threadId the user-facing thread id
	 * @param clientSession the MongoDB client session for transaction
	 * @return the internal thread id (UUID string)
	 */
	private String getOrCreateThreadId(String threadId, ClientSession clientSession) {
		MongoCollection<Document> threadMetaCollection = database.getCollection(THREAD_META_COLLECTION);
		String metaId = THREAD_META_PREFIX + threadId;

		// Step 1: Try to atomically get an active thread
		// Filter: _id matches AND is_released != true
		Document activeThreadFilter = new Document("_id", metaId)
				.append(FIELD_IS_RELEASED, new Document("$ne", true));

		FindOneAndUpdateOptions findOptions = new FindOneAndUpdateOptions()
				.returnDocument(ReturnDocument.AFTER);

		// Atomically get an active thread (using a no-op update to ensure atomic read)
		Document existingDoc = threadMetaCollection.findOneAndUpdate(
				clientSession,
				activeThreadFilter,
				Updates.currentDate("_lastAccessed"), // No-op update for atomic read
				findOptions
		);

		if (existingDoc != null) {
			String persistedThreadId = existingDoc.getString(FIELD_THREAD_ID);
			if (persistedThreadId != null) {
				// Active thread exists, return its internal thread id
				return persistedThreadId;
			}
		}

		// Step 2: No active thread exists, create a new one atomically
		// Strategy: Use findOneAndUpdate with upsert, but handle two cases:
		// a) Document doesn't exist - upsert will create it
		// b) Document exists but is_released == true - update it conditionally

		String newThreadId = UUID.randomUUID().toString();
		FindOneAndUpdateOptions upsertOptions = new FindOneAndUpdateOptions()
				.upsert(true)
				.returnDocument(ReturnDocument.AFTER);

		// First, try to create if document doesn't exist (using upsert)
		// This will create the document if it doesn't exist
		Document createResult = threadMetaCollection.findOneAndUpdate(
				clientSession,
				Filters.eq("_id", metaId), // This matches if exists, or creates if not (with upsert)
				Updates.combine(
						// Only set these when inserting (document doesn't exist)
						Updates.setOnInsert(FIELD_THREAD_ID, newThreadId),
						Updates.setOnInsert(FIELD_IS_RELEASED, false)
				),
				upsertOptions
		);

		if (createResult != null) {
			Boolean isReleased = createResult.getBoolean(FIELD_IS_RELEASED, false);
			String existingThreadId = createResult.getString(FIELD_THREAD_ID);

			// If document was just created or was already active, return the thread_id
			if (existingThreadId != null && !Boolean.TRUE.equals(isReleased)) {
				return existingThreadId;
			}

			// If document exists but is released, update it atomically
			// Use conditional update to ensure we only update if still released
			if (Boolean.TRUE.equals(isReleased)) {
				Document updateResult = threadMetaCollection.findOneAndUpdate(
						clientSession,
						Filters.and(
								Filters.eq("_id", metaId),
								Filters.eq(FIELD_IS_RELEASED, true) // Only update if still released
						),
						Updates.combine(
								Updates.set(FIELD_THREAD_ID, newThreadId),
								Updates.set(FIELD_IS_RELEASED, false)
						),
						new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
				);

				if (updateResult != null) {
					return updateResult.getString(FIELD_THREAD_ID);
				}

				// If update failed (another thread already updated it), query again
				Document finalDoc = threadMetaCollection.find(clientSession, new BasicDBObject("_id", metaId)).first();
				if (finalDoc != null) {
					String finalThreadId = finalDoc.getString(FIELD_THREAD_ID);
					Boolean finalIsReleased = finalDoc.getBoolean(FIELD_IS_RELEASED, false);
					if (finalThreadId != null && !Boolean.TRUE.equals(finalIsReleased)) {
						return finalThreadId;
					}
				}
			}
		}

		// Final fallback: query again to get the current state
		Document finalDoc = threadMetaCollection.find(clientSession, new BasicDBObject("_id", metaId)).first();
		if (finalDoc != null) {
			String finalThreadId = finalDoc.getString(FIELD_THREAD_ID);
			if (finalThreadId != null) {
				return finalThreadId;
			}
		}

		return newThreadId;
	}

	/**
	 * Gets the internal surrogate thread id of the active entry for the given
	 * user-facing thread id.
	 * Returns null if no active thread exists.
	 *
	 * @param threadId the user-facing thread id
	 * @param clientSession the MongoDB client session for transaction
	 * @return the active internal thread id, or null if not found
	 */
	private String getActiveThreadId(String threadId, ClientSession clientSession) {
		MongoCollection<Document> threadMetaCollection = database.getCollection(THREAD_META_COLLECTION);
		String metaId = THREAD_META_PREFIX + threadId;

		Document metaDoc = threadMetaCollection.find(clientSession, new BasicDBObject("_id", metaId)).first();

		if (metaDoc != null) {
			String persistedThreadId = metaDoc.getString(FIELD_THREAD_ID);
			Boolean isReleased = metaDoc.getBoolean(FIELD_IS_RELEASED, false);

			if (persistedThreadId != null && !Boolean.TRUE.equals(isReleased)) {
				return persistedThreadId;
			}
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
		ClientSession clientSession = this.client
				.startSession(ClientSessionOptions.builder().defaultTransactionOptions(txnOptions).build());
		clientSession.startTransaction();
		List<Checkpoint> checkpoints = null;
		try {
			// Get the internal thread id of the active entry
			String persistedThreadId = getActiveThreadId(threadId, clientSession);
			if (persistedThreadId == null) {
				clientSession.commitTransaction();
				return Collections.emptyList();
			}

			// Use the internal thread id to query checkpoints
			MongoCollection<Document> collection = database.getCollection(CHECKPOINT_COLLECTION);
			String checkpointId = CHECKPOINT_PREFIX + persistedThreadId;
			Document document = collection.find(clientSession, new BasicDBObject("_id", checkpointId)).first();
			if (document == null) {
				clientSession.commitTransaction();
				return Collections.emptyList();
			}
			String checkpointsStr = document.getString(DOCUMENT_CONTENT_KEY);
			checkpoints = deserializeCheckpoints(checkpointsStr);
			clientSession.commitTransaction();
		}
		catch (Exception e) {
			clientSession.abortTransaction();
			throw new RuntimeException(e);
		}
		finally {
			clientSession.close();
		}
		return checkpoints;
	}

	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {
		String threadId = threadId(config);
		ClientSession clientSession = this.client
				.startSession(ClientSessionOptions.builder().defaultTransactionOptions(txnOptions).build());
		LinkedList<Checkpoint> checkpoints = null;
		try {
			clientSession.startTransaction();

			// Get the internal thread id of the active entry
			String persistedThreadId = getActiveThreadId(threadId, clientSession);
			if (persistedThreadId == null) {
				clientSession.commitTransaction();
				return Optional.empty();
			}

			// Use the internal thread id to query checkpoints
			MongoCollection<Document> collection = database.getCollection(CHECKPOINT_COLLECTION);
			String checkpointId = CHECKPOINT_PREFIX + persistedThreadId;
			Document document = collection.find(clientSession, new BasicDBObject("_id", checkpointId)).first();
			if (document == null) {
				clientSession.commitTransaction();
				return Optional.empty();
			}
			String checkpointsStr = document.getString(DOCUMENT_CONTENT_KEY);
			checkpoints = deserializeCheckpoints(checkpointsStr);
			clientSession.commitTransaction();

			if (config.checkPointId().isPresent()) {
				List<Checkpoint> finalCheckpoints = checkpoints;
				return config.checkPointId()
						.flatMap(id -> finalCheckpoints.stream()
								.filter(checkpoint -> checkpoint.getId().equals(id))
								.findFirst());
			}
			return getLast(checkpoints, config);
		}
		catch (Exception e) {
			clientSession.abortTransaction();
			throw new RuntimeException(e);
		}
		finally {
			clientSession.close();
		}
	}

	@Override
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
		String threadId = threadId(config);
		ClientSession clientSession = this.client
				.startSession(ClientSessionOptions.builder().defaultTransactionOptions(txnOptions).build());
		clientSession.startTransaction();
		try {
			// Get or create the internal thread id
			String persistedThreadId = getOrCreateThreadId(threadId, clientSession);

			// Use the internal thread id as key for checkpoint storage
			MongoCollection<Document> collection = database.getCollection(CHECKPOINT_COLLECTION);
			String checkpointDocId = CHECKPOINT_PREFIX + persistedThreadId;
			Document document = collection.find(clientSession, new BasicDBObject("_id", checkpointDocId)).first();
			LinkedList<Checkpoint> checkpointLinkedList = null;

			if (Objects.nonNull(document)) {
				String checkpointsStr = document.getString(DOCUMENT_CONTENT_KEY);
				checkpointLinkedList = deserializeCheckpoints(checkpointsStr);
				LinkedList<Checkpoint> finalCheckpointLinkedList = checkpointLinkedList;
				if (config.checkPointId().isPresent()) { // Replace Checkpoint
					String checkPointId = config.checkPointId().get();
					int index = IntStream.range(0, checkpointLinkedList.size())
							.filter(i -> finalCheckpointLinkedList.get(i).getId().equals(checkPointId))
							.findFirst()
							.orElseThrow(() -> (new NoSuchElementException(
									format("Checkpoint with id %s not found!", checkPointId))));
					finalCheckpointLinkedList.set(index, checkpoint);
					retainLatestCheckpoints(finalCheckpointLinkedList, config);
					Document tempDocument = new Document().append("_id", checkpointDocId)
							.append(DOCUMENT_CONTENT_KEY, serializeCheckpoints(finalCheckpointLinkedList));
					collection.replaceOne(clientSession, Filters.eq("_id", checkpointDocId), tempDocument);
					clientSession.commitTransaction();
					return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
				}
			}

			if (checkpointLinkedList == null) {
				checkpointLinkedList = new LinkedList<>();
				checkpointLinkedList.push(checkpoint); // Add Checkpoint
				Document tempDocument = new Document().append("_id", checkpointDocId)
						.append(DOCUMENT_CONTENT_KEY, serializeCheckpoints(checkpointLinkedList));
				collection.insertOne(clientSession, tempDocument);
			}
			else {
				checkpointLinkedList.push(checkpoint); // Add Checkpoint
				retainLatestCheckpoints(checkpointLinkedList, config);
				Document tempDocument = new Document().append("_id", checkpointDocId)
						.append(DOCUMENT_CONTENT_KEY, serializeCheckpoints(checkpointLinkedList));
				ReplaceOptions opts = new ReplaceOptions().upsert(true);
				collection.replaceOne(clientSession, Filters.eq("_id", checkpointDocId), tempDocument, opts);
			}
			clientSession.commitTransaction();
		}
		catch (Exception e) {
			clientSession.abortTransaction();
			throw new RuntimeException(e);
		}
		finally {
			clientSession.close();
		}
		return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();
	}

	@Override
	public Tag release(RunnableConfig config) throws Exception {
		String threadId = threadId(config);
		ClientSession clientSession = this.client
				.startSession(ClientSessionOptions.builder().defaultTransactionOptions(txnOptions).build());
		clientSession.startTransaction();
		try {
			MongoCollection<Document> threadMetaCollection = database.getCollection(THREAD_META_COLLECTION);
			String metaId = THREAD_META_PREFIX + threadId;

			Document metaDoc = threadMetaCollection.find(clientSession, new BasicDBObject("_id", metaId)).first();
			if (metaDoc == null) {
				clientSession.abortTransaction();
				throw new IllegalStateException("Thread not found: " + threadId);
			}

			String persistedThreadId = metaDoc.getString(FIELD_THREAD_ID);
			if (persistedThreadId == null) {
				clientSession.abortTransaction();
				throw new IllegalStateException("Thread not found: " + threadId);
			}

			// Mark thread as released atomically
			// Use findOneAndUpdate with condition to ensure we only release active threads
			Document releaseFilter = new Document("_id", metaId)
					.append(FIELD_IS_RELEASED, false); // Only release if not already released

			Document updatedDoc = threadMetaCollection.findOneAndUpdate(
					clientSession,
					releaseFilter,
					Updates.set(FIELD_IS_RELEASED, true),
					new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
			);

			if (updatedDoc == null) {
				// Thread was already released or doesn't exist
				clientSession.abortTransaction();
				throw new IllegalStateException("Thread is not active or already released: " + threadId);
			}

			// Get checkpoints for Tag (using the internal thread id)
			MongoCollection<Document> checkpointCollection = database.getCollection(CHECKPOINT_COLLECTION);
			String checkpointDocId = CHECKPOINT_PREFIX + persistedThreadId;
			Document checkpointDoc = checkpointCollection.find(clientSession, new BasicDBObject("_id", checkpointDocId))
					.first();

			Collection<Checkpoint> checkpoints = Collections.emptyList();
			if (checkpointDoc != null) {
				String checkpointsStr = checkpointDoc.getString(DOCUMENT_CONTENT_KEY);
				if (checkpointsStr != null) {
					checkpoints = deserializeCheckpoints(checkpointsStr);
				}
			}

			clientSession.commitTransaction();
			return new Tag(threadId, checkpoints);

		}
		catch (Exception e) {
			clientSession.abortTransaction();
			throw new RuntimeException(e);
		}
		finally {
			clientSession.close();
		}
	}

	/**
	 * Builder class for MongoSaver.
	 */
	public static class Builder {
		private MongoClient client;
		private StateSerializer stateSerializer;

		public Builder client(MongoClient client) {
			this.client = client;
			return this;
		}

		public Builder stateSerializer(StateSerializer stateSerializer) {
			this.stateSerializer = stateSerializer;
			return this;
		}

		/**
		 * Builds a new MongoSaver instance.
		 * @return a new MongoSaver instance
		 * @throws IllegalArgumentException if client or stateSerializer is null
		 */
		public MongoSaver build() {
			if (client == null) {
				throw new IllegalArgumentException("client cannot be null");
			}
			if (stateSerializer == null) {
				this.stateSerializer = StateGraph.DEFAULT_JACKSON_SERIALIZER;
			}
			return new MongoSaver(client, stateSerializer);
		}
	}

}
