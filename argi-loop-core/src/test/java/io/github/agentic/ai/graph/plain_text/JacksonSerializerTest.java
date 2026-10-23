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
package io.github.agentic.ai.graph.plain_text;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.serializer.plain_text.jackson.JacksonStateSerializer;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.utils.CollectionsUtils.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JacksonSerializerTest {

	@Test
	public void serializeWithTypeInferenceTest() throws IOException, ClassNotFoundException {

		OverAllState state = new OverAllState(mapOf("prop1", "value1"));

		JacksonStateSerializer serializer = new JacksonStateSerializer(OverAllState::new, new ObjectMapper()) {
		};

		Class<?> type = serializer.getStateType();

		assertEquals(OverAllState.class, type);

		byte[] bytes = serializer.objectToBytes(state);

		assertNotNull(bytes);
		assertTrue(bytes.length > 0);

		OverAllState deserializedState = serializer.bytesToObject(bytes);

		assertNotNull(deserializedState);
		assertEquals(1, deserializedState.data().size());
		assertEquals("value1", deserializedState.data().get("prop1"));
	}

}
