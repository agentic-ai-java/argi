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
package io.github.agentic.ai.api.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Per-field usage aggregation contract used to fold delivery segments (issue #207). */
@DisplayName("Usage.plus per-field aggregation")
class UsagePlusTest {

	@Test
	@DisplayName("known fields on both sides sum per field")
	void knownFieldsSumPerField() {
		Usage result = new Usage(1L, 2L, 3L).plus(new Usage(10L, 20L, 30L));

		assertEquals(new Usage(11L, 22L, 33L), result);
	}

	@Test
	@DisplayName("a field unknown on either side stays unknown instead of becoming zero")
	void unknownFieldsStayUnknown() {
		Usage result = new Usage(1L, 2L, 3L).plus(new Usage(null, 20L, null));

		assertEquals(new Usage(null, 22L, null), result);
	}

	@Test
	@DisplayName("a fully unknown segment makes every total field unknown")
	void unknownSegmentMakesTotalUnknown() {
		Usage result = new Usage(1L, 2L, 3L).plus(Usage.unknown());

		assertEquals(Usage.unknown(), result);
	}

	@Test
	@DisplayName("a null segment adds nothing known")
	void nullSegmentReturnsThisUsage() {
		Usage usage = new Usage(1L, 2L, 3L);

		assertSame(usage, usage.plus(null));
	}

}
