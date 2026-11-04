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
package io.github.agentic.ai.agent.budget;

import io.github.agentic.ai.agent.interceptor.ModelInterceptor;
import io.github.agentic.ai.agent.interceptor.ModelCallHandler;
import io.github.agentic.ai.agent.interceptor.ModelRequest;
import io.github.agentic.ai.agent.interceptor.ModelResponse;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.GenerationOptions;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the context window budget gate.
 *
 * <p>
 * The tests are model independent: no provider is called, the request is measured
 * and the dispatch is counted. The invariant under test is that an oversized request
 * never reaches the provider.
 */
class ContextBudgetInterceptorTest {

	@Test
	void theEstimateCoversSystemMessagesToolSchemasAndTheOutputReserve() {
		RequestTokenEstimate estimate = new ApproximateRequestTokenEstimator().estimate(request(), budget());

		assertThat(estimate.systemTokens()).isPositive();
		assertThat(estimate.messageTokens()).isPositive();
		assertThat(estimate.toolSchemaTokens()).isPositive();
		assertThat(estimate.outputReserve()).isEqualTo(100);
		assertThat(estimate.totalTokens()).isEqualTo(estimate.inputTokens() + 100);
		assertThat(estimate.accuracy()).isEqualTo(EstimationAccuracy.APPROXIMATE);
	}

	// issue #137 review: an explicit maxTokens output limit is an output demand —
	// a request whose requested completion alone exceeds the window must be refused
	// even when its input is small.
	@Test
	void anExplicitOutputDemandBeyondTheWindowIsRefused() {
		AtomicInteger dispatches = new AtomicInteger();
		ContextBudgetInterceptor interceptor = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(1000, 100))
			.build();
		ModelRequest demanded = ModelRequest.builder()
			.systemMessage(ChatMessage.system("system instructions for the agent"))
			.messages(List.of(ChatMessage.user("hello")))
			.options(GenerationOptions.builder().maxTokens(2000).build())
			.build();

		assertThatThrownBy(() -> interceptor.interceptModel(demanded, r -> {
			dispatches.incrementAndGet();
			return response();
		})).isInstanceOf(ContextWindowBudgetExceededException.class)
			.satisfies(failure -> {
				ContextWindowBudgetExceededException exceeded = (ContextWindowBudgetExceededException) failure;
				assertThat(exceeded.dominantComponent()).isEqualTo(BudgetComponent.OUTPUT_RESERVE);
			});

		assertThat(dispatches).hasValue(0);
	}

	// The same gate must not reject a request whose explicit output limit fits.
	@Test
	void anExplicitOutputDemandWithinTheWindowStillDispatches() {
		AtomicInteger dispatches = new AtomicInteger();
		ContextBudgetInterceptor interceptor = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(1000, 100))
			.build();
		ModelRequest modest = ModelRequest.builder()
			.systemMessage(ChatMessage.system("system instructions for the agent"))
			.messages(List.of(ChatMessage.user("hello")))
			.options(GenerationOptions.builder().maxTokens(50).build())
			.build();

		interceptor.interceptModel(modest, r -> {
			dispatches.incrementAndGet();
			return response();
		});

		assertThat(dispatches).hasValue(1);
	}

	// The estimate itself accounts for the explicit output demand, floored by the
	// configured reserve.
	@Test
	void theEstimateAccountsForAnExplicitMaxTokens() {
		RequestTokenEstimate estimate = new ApproximateRequestTokenEstimator().estimate(request(), budget());

		ModelRequest demanded = ModelRequest.builder()
			.messages(List.of(ChatMessage.user("hello")))
			.options(GenerationOptions.builder().maxTokens(2000).build())
			.build();
		RequestTokenEstimate demandedEstimate = new ApproximateRequestTokenEstimator().estimate(demanded, budget());

		assertThat(estimate.outputReserve()).isEqualTo(100);
		assertThat(demandedEstimate.outputReserve()).isEqualTo(2000);
		assertThat(demandedEstimate.totalTokens()).isEqualTo(demandedEstimate.inputTokens() + 2000);
	}

	// issue #137 review: the budget must hold after interceptor rewrites, so the
	// budget interceptor has to sit **after** every request-rewriting interceptor
	// in the chain (innermost). This chain-level case registers [rewriter, budget]:
	// a small request passes the rewriter, grows beyond the window, and must be
	// refused before reaching the provider.
	@Test
	void budgetStillGuardsAfterARequestRewritingInterceptor() throws Exception {
		AtomicInteger dispatches = new AtomicInteger();
		ModelInterceptor rewriter = new ModelInterceptor() {
			public String getName() {
				return "rewriter";
			}

			public ModelResponse interceptModel(ModelRequest request, ModelCallHandler next) {
				// Rewrites the small incoming request into an oversized one.
				return next.call(longRequest());
			}
		};
		ContextBudgetInterceptor budget = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(20, 5))
			.build();
		// Registration order: the rewriter outermost, the budget innermost (the
		// terminal position, right before the dispatch).
		ModelCallHandler chain = request -> {
			dispatches.incrementAndGet();
			return response();
		};
		ModelCallHandler budgetHandler = request -> budget.interceptModel(request, chain);
		ModelCallHandler fullChain = request -> rewriter.interceptModel(request, budgetHandler);

		assertThatThrownBy(() -> fullChain.call(request()))
			.isInstanceOf(ContextWindowBudgetExceededException.class);
		assertThat(dispatches).hasValue(0);
	}
	@Test
	void anOversizedRequestIsNeverDispatched() {
		AtomicInteger dispatches = new AtomicInteger();
		ContextBudgetInterceptor interceptor = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(20, 5))
			.build();

		assertThatThrownBy(() -> interceptor.interceptModel(request(), r -> {
			dispatches.incrementAndGet();
			return response();
		})).isInstanceOf(ContextWindowBudgetExceededException.class)
			.satisfies(failure -> {
				ContextWindowBudgetExceededException exceeded = (ContextWindowBudgetExceededException) failure;
				assertThat(exceeded.violation().component()).isEqualTo(BudgetComponent.TOTAL);
				assertThat(exceeded.violation().excessTokens()).isPositive();
				assertThat(exceeded.dominantComponent()).isNotNull();
			});

		assertThat(dispatches).hasValue(0);
	}

	@Test
	void aCompactedRequestIsMeasuredAgainBeforeItIsDispatched() {
		AtomicInteger dispatches = new AtomicInteger();
		AtomicReference<ModelRequest> dispatched = new AtomicReference<>();
		ContextBudgetInterceptor interceptor = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(100, 5))
			.compactor(request -> ModelRequest.builder(request).messages(List.of(ChatMessage.user("short"))).build())
			.build();

		interceptor.interceptModel(longRequest(), r -> {
			dispatches.incrementAndGet();
			dispatched.set(r);
			return response();
		});

		assertThat(dispatches).hasValue(1);
		assertThat(dispatched.get().getMessages()).hasSize(1);
		assertThat(dispatched.get().getMessages().get(0).text()).isEqualTo("short");
	}

	@Test
	void aCompactionWhichDoesNotHelpStillFails() {
		AtomicInteger dispatches = new AtomicInteger();
		ContextBudgetInterceptor interceptor = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(20, 5))
			.compactor(request -> request)
			.build();

		assertThatThrownBy(() -> interceptor.interceptModel(request(), r -> {
			dispatches.incrementAndGet();
			return response();
		})).isInstanceOf(ContextWindowBudgetExceededException.class);

		assertThat(dispatches).hasValue(0);
	}

	@Test
	void aCompactionWhichBreaksTheToolCallPairingIsRejected() {
		ChatMessage call = ChatMessage.assistant("calling", List.of(new ToolCall("call-1", "search", "{}")));
		ChatMessage result = ChatMessage.tool("call-1", "search", "found it");
		ModelRequest request = ModelRequest.builder().messages(List.of(call, result)).build();
		AtomicInteger dispatches = new AtomicInteger();
		ContextBudgetInterceptor interceptor = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(5, 1))
			.compactor(original -> ModelRequest.builder(original).messages(List.of(call)).build())
			.build();

		assertThatThrownBy(() -> interceptor.interceptModel(request, r -> {
			dispatches.incrementAndGet();
			return response();
		})).isInstanceOf(ToolCallPairingValidator.PairingException.class)
			.satisfies(failure -> assertThat(((ToolCallPairingValidator.PairingException) failure).violation().kind())
				.isEqualTo(ToolCallPairingValidator.Kind.MISSING_TOOL_RESULT));

		assertThat(dispatches).hasValue(0);
	}

	@Test
	void theEstimatorIsReplaceable() {
		AtomicInteger dispatches = new AtomicInteger();
		RequestTokenEstimator exact = (request, budget) -> new RequestTokenEstimate(1, 1, 1, budget.outputReserve(),
				EstimationAccuracy.EXACT);
		ContextBudgetInterceptor interceptor = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(20, 5))
			.estimator(exact)
			.build();

		interceptor.interceptModel(request(), r -> {
			dispatches.incrementAndGet();
			return response();
		});

		assertThat(dispatches).hasValue(1);
	}

	@Test
	void theBudgetBoundaryIsExact() {
		RequestTokenEstimator fixed = (request, budget) -> new RequestTokenEstimate(50, 50, 0, 100,
				EstimationAccuracy.EXACT);
		AtomicInteger dispatches = new AtomicInteger();

		ContextBudgetInterceptor fitting = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(200, 100))
			.estimator(fixed)
			.build();
		fitting.interceptModel(request(), r -> {
			dispatches.incrementAndGet();
			return response();
		});
		assertThat(dispatches).hasValue(1);

		ContextBudgetInterceptor tooSmall = ContextBudgetInterceptor.builder()
			.budget(new ContextWindowBudget(199, 100))
			.estimator(fixed)
			.build();
		assertThatThrownBy(() -> tooSmall.interceptModel(request(), r -> {
			dispatches.incrementAndGet();
			return response();
		})).isInstanceOf(ContextWindowBudgetExceededException.class);
		assertThat(dispatches).hasValue(1);
	}

	private static ContextWindowBudget budget() {
		return new ContextWindowBudget(1000, 100);
	}

	private static ModelRequest request() {
		return ModelRequest.builder()
			.systemMessage(ChatMessage.system("system instructions for the agent"))
			.messages(List.of(ChatMessage.user("hello"), ChatMessage.assistant("hi")))
			.toolRegistry(new ToolRegistry(List.of(tool("search", "{\"type\":\"object\",\"properties\":{\"query\":{}}}"))))
			.build();
	}

	private static ModelResponse response() {
		return ModelResponse.of(ChatMessage.assistant("ok"));
	}

	private static ModelRequest longRequest() {
		List<ChatMessage> messages = new java.util.ArrayList<>();
		for (int index = 0; index < 20; index++) {
			messages.add(ChatMessage.user("message number " + index + " with a fair amount of content"));
		}
		return ModelRequest.builder()
			.systemMessage(ChatMessage.system("system instructions for the agent"))
			.messages(messages)
			.toolRegistry(new ToolRegistry(List.of(tool("search", "{\"type\":\"object\",\"properties\":{\"query\":{}}}"))))
			.build();
	}

	private static Tool tool(String name, String schema) {
		return new Tool() {

			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, "tool " + name, schema);
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return CompletableFuture.completedFuture(ToolResult.text("result"));
			}
		};
	}

}
