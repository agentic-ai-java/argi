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

package io.github.agentic.ai.graph.agent.hook.taskcompletion;

import io.github.agentic.ai.graph.OverAllState;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.Todo;
import io.github.agentic.ai.graph.agent.interceptor.todolist.TodoListInterceptor.TodoStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Built-in completion predicates for {@link TaskCompletionHook}.
 *
 * <p>The default predicate treats the run's required work as unfinished while any todo
 * written by {@link TodoListInterceptor}'s {@code write_todos} tool is still
 * {@code pending} or {@code in_progress}. Missing, empty or unrecognized todo shapes are
 * treated as complete so the guard never blocks runs that do not use todos.</p>
 */
public final class TaskCompletionPredicates {

	private static final String TODOS_STATE_KEY = "todos";

	private static final String STATUS_PENDING = "pending";

	private static final String STATUS_IN_PROGRESS = "in_progress";

	private TaskCompletionPredicates() {
	}

	/**
	 * Returns the default completion predicate: work is complete when the {@code todos}
	 * state key is absent, empty, or holds only completed todos.
	 * @return predicate returning {@code true} when no unfinished todo remains
	 */
	public static Predicate<OverAllState> todosComplete() {
		return state -> unfinishedTodoLines(state).isEmpty();
	}

	/**
	 * Renders one operational line per unfinished todo, e.g.
	 * {@code pending: run the tests}. Elements the reader does not recognize are
	 * skipped.
	 * @param state the current state holding the {@code todos} key
	 * @return lines describing unfinished todos, empty when there are none
	 */
	static List<String> unfinishedTodoLines(OverAllState state) {
		List<String> lines = new ArrayList<>();
		Object todos = state.value(TODOS_STATE_KEY).orElse(null);
		if (!(todos instanceof List<?> entries) || entries.isEmpty()) {
			return lines;
		}
		for (Object entry : entries) {
			String status = statusOf(entry);
			String content = contentOf(entry);
			if (isUnfinishedStatus(status) && content != null) {
				lines.add(status + ": " + content);
			}
		}
		return lines;
	}

	private static boolean isUnfinishedStatus(String status) {
		return STATUS_PENDING.equals(status) || STATUS_IN_PROGRESS.equals(status);
	}

	private static String statusOf(Object entry) {
		if (entry instanceof Todo todo && todo.getStatus() != null) {
			return todo.getStatus().getValue();
		}
		if (entry instanceof Map<?, ?> map && map.get("status") instanceof String status) {
			return status;
		}
		if (entry instanceof TodoStatus todoStatus) {
			return todoStatus.getValue();
		}
		return null;
	}

	private static String contentOf(Object entry) {
		if (entry instanceof Todo todo && todo.getContent() != null && !todo.getContent().isBlank()) {
			return todo.getContent();
		}
		if (entry instanceof Map<?, ?> map && map.get("content") instanceof String content && !content.isBlank()) {
			return content;
		}
		return null;
	}

}
