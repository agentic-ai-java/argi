/*
 * Copyright 2025-2026 the original author or authors.
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
package io.github.agentic.ai.graph.serializer.std;

import io.github.agentic.ai.graph.serializer.Serializer;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerializerMapperTest {

	@Test
	void exactRegistrationWinsOverAssignableFallbackAndSupportsClassNames() {
		SerializerMapper mapper = new SerializerMapper();
		Serializer<Object> fallback = serializer();
		Serializer<Object> exact = serializer();
		mapper.register(Collection.class, fallback).register(ArrayList.class, exact);
		assertSame(exact, mapper.getSerializer(ArrayList.class).orElseThrow());
		assertSame(exact, mapper.getSerializer(ArrayList.class.getName()).orElseThrow());
		assertSame(fallback, mapper.getSerializer(Collection.class.getName()).orElseThrow());
		assertTrue(mapper.getSerializer(String.class).isEmpty());
		assertTrue(mapper.getSerializer(String.class.getName()).isEmpty());
	}

	@Test
	void registrationReplacesExistingEntryAndUnregisterUsesTargetClass() {
		SerializerMapper mapper = new SerializerMapper();
		Serializer<Object> replacement = serializer();
		mapper.register(String.class, serializer()).register(String.class, replacement);
		assertSame(replacement, mapper.getSerializer(String.class).orElseThrow());
		assertSame(replacement, mapper.getSerializer(String.class.getName()).orElseThrow());
		assertTrue(mapper.unregister(String.class));
		assertFalse(mapper.unregister(String.class));
		assertTrue(mapper.getSerializer(String.class).isEmpty());
		assertTrue(mapper.getSerializer(String.class.getName()).isEmpty());
	}

	@Test
	void rejectsNullRegistrationLookupAndRemoval() {
		SerializerMapper mapper = new SerializerMapper();
		assertThrows(NullPointerException.class, () -> mapper.register(null, serializer()));
		assertThrows(NullPointerException.class, () -> mapper.register(String.class, null));
		assertThrows(NullPointerException.class, () -> mapper.getSerializer((Class<?>) null));
		assertThrows(NullPointerException.class, () -> mapper.getSerializer((String) null));
		assertThrows(NullPointerException.class, () -> mapper.unregister(null));
	}

	private static Serializer<Object> serializer() {
		return new Serializer<>() {
			@Override
			public void write(Object value, ObjectOutput out) throws IOException {
				out.writeObject(value);
			}

			@Override
			public Object read(ObjectInput in) throws IOException, ClassNotFoundException {
				return in.readObject();
			}
		};
	}

}
