/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.agentic.ai.graph.agent.compatibility;

import io.github.agentic.ai.graph.serializer.std.ObjectInputWithMapper;
import io.github.agentic.ai.graph.serializer.std.SerializerMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyBinaryCheckpointFailureTest {

	@Test
	void rejectsHistoricalClassPlusPayloadLayoutEvenWithSdkAvailable() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(UserMessage.class);
			output.writeUTF("old payload");
		}
		try (var input = new ObjectInputWithMapper(new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())),
				new SerializerMapper())) {
			IOException error = assertThrows(IOException.class, input::readObject);
			assertTrue(error.getMessage().contains("Legacy binary chat checkpoints"));
		}
	}

	@Test
	void explainsExportWhenOldClassIsUnavailable() throws Exception {
		ObjectInput source = mock(ObjectInput.class);
		when(source.readObject()).thenThrow(new ClassNotFoundException("old SDK type"));
		var input = new ObjectInputWithMapper(source, new SerializerMapper());
		IOException error = assertThrows(IOException.class, input::readObject);
		assertInstanceOf(ClassNotFoundException.class, error.getCause());
		assertTrue(error.getMessage().contains("previous runtime"));
	}

}
