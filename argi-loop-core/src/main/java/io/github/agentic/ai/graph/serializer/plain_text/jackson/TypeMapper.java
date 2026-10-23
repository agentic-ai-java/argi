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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.deser.DeserializerFactory;
import com.fasterxml.jackson.databind.ser.SerializerFactory;

public class TypeMapper {

	public static String TYPE_PROPERTY = "@type";

	private final Set<Reference<?>> references = new HashSet<>();

	private volatile UntypedMapper untypedMapper;

	/**
	 * Reuses the mapper and its deserializer caches after explicit type markers have
	 * been resolved. The cache belongs to this serializer, not to a global registry.
	 */
	ObjectMapper mapperWithoutDefaultTyping(ObjectMapper mapper) {
		if (mapper.getDeserializationConfig().getDefaultTyper(null) == null) {
			return mapper;
		}
		UntypedMapper cached = untypedMapper;
		if (cached != null && cached.matches(mapper)) {
			return cached.copy();
		}
		synchronized (this) {
			cached = untypedMapper;
			if (cached == null || !cached.matches(mapper)) {
				cached = new UntypedMapper(mapper, mapper.getSerializationConfig(), mapper.getDeserializationConfig(),
						mapper.getSerializerFactory(), mapper.getDeserializationContext().getFactory(),
						mapper.copy().deactivateDefaultTyping());
				untypedMapper = cached;
			}
			return cached.copy();
		}
	}

	private record UntypedMapper(ObjectMapper source, SerializationConfig serializationConfig,
			DeserializationConfig deserializationConfig, SerializerFactory serializerFactory,
			DeserializerFactory deserializerFactory, ObjectMapper copy) {

		boolean matches(ObjectMapper mapper) {
			return source == mapper && serializationConfig == mapper.getSerializationConfig()
					&& deserializationConfig == mapper.getDeserializationConfig()
					&& serializerFactory == mapper.getSerializerFactory()
					&& deserializerFactory == mapper.getDeserializationContext().getFactory();
		}

	}

	public <T> TypeMapper register(Reference<T> reference) {
		Objects.requireNonNull(reference, "reference cannot be null");
		references.add(reference);
		return this;
	}

	public <T> boolean unregister(Reference<T> reference) {
		Objects.requireNonNull(reference, "reference cannot be null");
		return references.remove(reference);
	}

	public Optional<Reference<?>> getReference(String type) {
		Objects.requireNonNull(type, "type cannot be null");
		return references.stream().filter(ref -> Objects.equals(ref.getTypeName(), type)).findFirst();
	}

	public static abstract class Reference<T> extends TypeReference<T> {

		private final String typeName;

		private final Type javaType;

		public Reference(String typeName) {
			this(typeName, null);
		}

		public Reference(String typeName, Type javaType) {
			super();
			this.typeName = Objects.requireNonNull(typeName, "typeName cannot be null");
			this.javaType = javaType;
		}

		public String getTypeName() {
			return typeName;
		}

		@Override
		public Type getType() {
			return this.javaType != null ? this.javaType : super.getType();
		}

	}

}
