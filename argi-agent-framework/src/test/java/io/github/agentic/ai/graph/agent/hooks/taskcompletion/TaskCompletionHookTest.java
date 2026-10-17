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

package io.github.agentic.ai.graph.agent.hooks.taskcompletion;

import io.github.agentic.ai.graph.KeyStrategy;
import io.github.agentic.ai.graph.agent.hook.HookPosition;
import io.github.agentic.ai.graph.agent.hook.JumpTo;
import io.github.agentic.ai.graph.agent.hook.taskcompletion.TaskCompletionHook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-independent tests pinning the metadata surface of {@link TaskCompletionHook}:
 * name, positions, jump destinations, the single declared state key and builder
 * validation.
 */
class TaskCompletionHookTest {

	@Test
	void declaresOnlyTheTerminalDecisionKeyStrategy() {
		var strategies = TaskCompletionHook.builder().build().getKeyStrategys();
		assertEquals(1, strategies.size());
		assertEquals(KeyStrategy.REPLACE, strategies.get(TaskCompletionHook.TERMINAL_DECISION_STATE_KEY));
	}

	@Test
	void exposesNamePositionsAndJumpDestinations() {
		TaskCompletionHook hook = TaskCompletionHook.builder().build();
		assertEquals("TaskCompletion", hook.getName());
		assertEquals(HookPosition.BEFORE_MODEL, hook.getHookPositions()[0]);
		assertEquals(HookPosition.AFTER_MODEL, hook.getHookPositions()[1]);
		assertTrue(hook.canJumpTo().containsAll(java.util.List.of(JumpTo.model, JumpTo.end)));
	}

	@Test
	void terminalDecisionStateKeyIsStable() {
		assertEquals("argi.actionDecision.final", TaskCompletionHook.TERMINAL_DECISION_STATE_KEY);
	}

	@Test
	void builderRejectsInvalidBounds() {
		assertThrows(IllegalArgumentException.class, () -> TaskCompletionHook.builder().maxAttempts(0));
		assertThrows(IllegalArgumentException.class, () -> TaskCompletionHook.builder().maxConsecutiveRepeats(1));
		assertThrows(IllegalArgumentException.class,
				() -> TaskCompletionHook.builder().maxUnknownToolCalls(0));
		assertThrows(IllegalArgumentException.class,
				() -> TaskCompletionHook.builder().deadline(java.time.Duration.ZERO));
	}

}
