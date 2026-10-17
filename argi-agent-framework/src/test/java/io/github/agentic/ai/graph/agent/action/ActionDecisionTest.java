/*
 * Copyright 2025-2026 the original author or authors.
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

package io.github.agentic.ai.graph.agent.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionDecisionTest {

	@Test
	void rejectsNullTypeAndReasonCode() {
		assertThrows(NullPointerException.class,
				() -> new ActionDecision(null, ActionReasonCodes.TOOL_SELECTION_EMPTY, null));
		assertThrows(NullPointerException.class,
				() -> new ActionDecision(ActionType.FINAL_RESPONSE, null, null));
	}

	@Test
	void allowsNullDetailAndFactoriesPreserveValues() {
		final ActionDecision withoutDetail = ActionDecision.of(ActionType.STOP,
				ActionReasonCodes.TOOL_SELECTION_MALFORMED);
		assertNull(withoutDetail.detail());
		final ActionDecision withDetail = ActionDecision.of(ActionType.TOOL_CALL,
				ActionReasonCodes.TOOL_SELECTION_MODEL_FAILED, "selection client unavailable");
		assertEquals(ActionType.TOOL_CALL, withDetail.type());
		assertEquals(ActionReasonCodes.TOOL_SELECTION_MODEL_FAILED, withDetail.reasonCode());
		assertEquals("selection client unavailable", withDetail.detail());
	}

	@Test
	void contextKeyIsStable() {
		assertEquals("argi.actionDecision", ActionDecision.CONTEXT_KEY);
	}

}
