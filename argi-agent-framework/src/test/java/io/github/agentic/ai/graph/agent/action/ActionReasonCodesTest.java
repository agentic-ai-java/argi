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

package io.github.agentic.ai.graph.agent.action;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Model-independent contract test pinning the stable reason-code labels of the action
 * decision vocabulary (T36 slices 1 and 2).
 */
class ActionReasonCodesTest {

	@Test
	void sliceOneAndSliceTwoCodesAreStableLabels() {
		assertEquals("TOOL_SELECTION_EMPTY", ActionReasonCodes.TOOL_SELECTION_EMPTY);
		assertEquals("TOOL_SELECTION_MALFORMED", ActionReasonCodes.TOOL_SELECTION_MALFORMED);
		assertEquals("TOOL_SELECTION_MODEL_FAILED", ActionReasonCodes.TOOL_SELECTION_MODEL_FAILED);
		assertEquals("UNFINISHED_REQUIRED_TASKS", ActionReasonCodes.UNFINISHED_REQUIRED_TASKS);
		assertEquals("NO_PROGRESS", ActionReasonCodes.NO_PROGRESS);
		assertEquals("REPEATED_DECISION", ActionReasonCodes.REPEATED_DECISION);
		assertEquals("UNKNOWN_TOOL", ActionReasonCodes.UNKNOWN_TOOL);
	}

	@Test
	void reasonCodeValuesAreUnique() throws Exception {
		Set<String> values = new TreeSet<>();
		for (Field field : ActionReasonCodes.class.getDeclaredFields()) {
			if (field.getType() == String.class) {
				field.setAccessible(true);
				values.add((String) field.get(null));
			}
		}
		assertEquals(7, values.size());
	}

}
