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
package io.github.agentic.ai.graph;

import io.github.agentic.ai.graph.state.strategy.AppendStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static io.github.agentic.ai.graph.action.AsyncNodeAction.node_async;

class AppendStrategyMutationRegressionTest {

    @ParameterizedTest
    @ValueSource(strings = { "second", "third" })
    void scalarAppendHonorsDuplicatePolicy(String newValue) {
        var strategy = new AppendStrategy(false);
        var oldValues = List.of("first", "second", "first");

        Object result = strategy.apply(oldValues, newValue);

        assertEquals(strategy.apply(oldValues, List.of(newValue)), result);
        assertEquals(List.of("first", "second", "first"), oldValues);
        assertNotSame(oldValues, result);
        var withDuplicates = new ArrayList<>(oldValues);
        withDuplicates.add(newValue);
        assertEquals(withDuplicates, new AppendStrategy().apply(oldValues, newValue));
    }

    @Test
    void graphDeduplicatesScalarNodeUpdates() throws Exception {
        var graph = new StateGraph(() -> Map.of("results", new AppendStrategy(false)))
                .addNode("first", node_async(state -> Map.of("results", "answer")))
                .addNode("second", node_async(state -> Map.of("results", "answer")))
                .addEdge(StateGraph.START, "first")
                .addEdge("first", "second")
                .addEdge("second", StateGraph.END)
                .compile();

        assertEquals(List.of("answer"), graph.invoke(Map.of()).orElseThrow().value("results").orElseThrow());
    }

    // https://github.com/agentic-ai-java/argi/issues/4757
    @Test
    void issue4757_singleValueAppend_doesNotMutateExistingList() {
        AppendStrategy strategy = new AppendStrategy();
        List<Object> oldValues = new ArrayList<>(List.of("question"));

        Object result = strategy.apply(oldValues, "user-answer");

        assertEquals(List.of("question"), oldValues);
        assertEquals(List.of("question", "user-answer"), result);
        assertNotSame(oldValues, result);
    }
}
