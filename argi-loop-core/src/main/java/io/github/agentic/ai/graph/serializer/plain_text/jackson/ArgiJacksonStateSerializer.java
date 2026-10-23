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
package io.github.agentic.ai.graph.serializer.plain_text.jackson;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.state.AgentStateFactory;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import java.io.Serial;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;

/** JSON checkpoint serializer for neutral chat records and application state. */
public final class ArgiJacksonStateSerializer extends JacksonStateSerializer {

	public ArgiJacksonStateSerializer(AgentStateFactory<OverAllState> factory) {
		this(factory, new ObjectMapper());
	}

	public ArgiJacksonStateSerializer(AgentStateFactory<OverAllState> factory, ObjectMapper mapper) {
		super(factory, mapper);
		ObjectMapper.DefaultTypeResolverBuilder resolver = new ObjectMapper.DefaultTypeResolverBuilder(
				ObjectMapper.DefaultTyping.NON_FINAL, LaissezFaireSubTypeValidator.instance) {
			@Serial
			private static final long serialVersionUID = 1L;

			@Override
			public boolean useForType(JavaType type) {
				if (type.isTypeOrSubTypeOf(Map.class) || type.isMapLikeType() || type.isCollectionLikeType()
						|| type.isTypeOrSubTypeOf(Collection.class) || type.isArrayType()) {
					return false;
				}
				Class<?> raw = type.getRawClass();
				if (raw.isMemberClass() && !Modifier.isStatic(raw.getModifiers()) || raw.isLocalClass()
						|| raw.isAnonymousClass()) {
					return false;
				}
				return super.useForType(type);
			}
		};
		resolver.init(JsonTypeInfo.Id.CLASS, null);
		resolver.inclusion(JsonTypeInfo.As.PROPERTY);
		resolver.typeProperty("@class");
		mapper.setDefaultTyping(resolver);
	}

}
