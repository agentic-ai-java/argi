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
package io.github.agentic.ai.graph.compatibility;

import io.github.agentic.ai.graph.checkpoint.BaseCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.savers.h2.H2Saver;
import io.github.agentic.ai.graph.checkpoint.savers.jdbc.AbstractJdbcCheckpointSaver;
import io.github.agentic.ai.graph.checkpoint.savers.mongo.MongoSaver;
import io.github.agentic.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import io.github.agentic.ai.graph.checkpoint.savers.oracle.OracleSaver;
import io.github.agentic.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import io.github.agentic.ai.graph.checkpoint.savers.redis.RedisSaver;
import io.github.agentic.ai.graph.store.Store;
import io.github.agentic.ai.graph.store.stores.DatabaseStore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigratedPersistenceDeprecationTests {

	@Test
	void jdbcCheckpointSaverCompatibilityTypesAreDeprecatedForRemoval() {
		assertDeprecatedForRemoval(AbstractJdbcCheckpointSaver.class);
		assertDeprecatedForRemoval(H2Saver.class);
		assertDeprecatedForRemoval(MysqlSaver.class);
		assertDeprecatedForRemoval(OracleSaver.class);
		assertDeprecatedForRemoval(PostgresSaver.class);
		assertDeprecatedForRemoval(io.github.agentic.ai.graph.checkpoint.savers.h2.CreateOption.class);
		assertDeprecatedForRemoval(io.github.agentic.ai.graph.checkpoint.savers.mysql.CreateOption.class);
		assertDeprecatedForRemoval(io.github.agentic.ai.graph.checkpoint.savers.oracle.CreateOption.class);
		assertDeprecatedForRemoval(io.github.agentic.ai.graph.checkpoint.savers.postgresql.CreateOption.class);

		Class<? extends BaseCheckpointSaver> h2Type = H2Saver.class;
		Class<? extends BaseCheckpointSaver> mysqlType = MysqlSaver.class;
		Class<? extends BaseCheckpointSaver> oracleType = OracleSaver.class;
		Class<? extends BaseCheckpointSaver> postgresType = PostgresSaver.class;
		assertSame(H2Saver.class, h2Type);
		assertSame(MysqlSaver.class, mysqlType);
		assertSame(OracleSaver.class, oracleType);
		assertSame(PostgresSaver.class, postgresType);
		assertEquals("CREATE_IF_NOT_EXISTS",
				io.github.agentic.ai.graph.checkpoint.savers.h2.CreateOption.CREATE_IF_NOT_EXISTS.name());
	}

	@Test
	void databaseStoreCompatibilityTypeIsDeprecatedForRemoval() {
		assertDeprecatedForRemoval(DatabaseStore.class);

		Class<? extends Store> storeType = DatabaseStore.class;
		assertSame(DatabaseStore.class, storeType);
	}

	@Test
	void redisAndMongoSaverCompatibilityTypesAreDeprecatedForRemoval() {
		assertDeprecatedForRemoval(RedisSaver.class);
		assertDeprecatedForRemoval(MongoSaver.class);

		Class<? extends BaseCheckpointSaver> redisType = RedisSaver.class;
		Class<? extends BaseCheckpointSaver> mongoType = MongoSaver.class;
		assertSame(RedisSaver.class, redisType);
		assertSame(MongoSaver.class, mongoType);
	}

	private static void assertDeprecatedForRemoval(Class<?> type) {
		Deprecated deprecated = type.getAnnotation(Deprecated.class);
		assertNotNull(deprecated, () -> type.getName() + " must be deprecated");
		assertEquals("2.1.0", deprecated.since(), () -> type.getName() + " must declare since=2.1.0");
		assertTrue(deprecated.forRemoval(), () -> type.getName() + " must be marked for removal");
	}

}
