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
package io.github.agentic.ai.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.agentic.ai.agent.AgentExecutionContext;
import io.github.agentic.ai.agent.AgentOutcome;
import io.github.agentic.ai.agent.AgentRequest;
import io.github.agentic.ai.api.chat.ChatMessage;
import io.github.agentic.ai.api.chat.ToolCall;
import io.github.agentic.ai.api.chat.ToolDefinition;
import io.github.agentic.ai.api.tool.Tool;
import io.github.agentic.ai.api.tool.ToolContext;
import io.github.agentic.ai.api.tool.ToolRegistry;
import io.github.agentic.ai.api.tool.ToolResult;
import io.github.agentic.ai.runtime.execution.DelegationScope;
import io.github.agentic.ai.runtime.execution.ExecutionBudget;
import io.github.agentic.ai.runtime.execution.PrincipalReference;
import io.github.agentic.ai.runtime.execution.TenantReference;
import io.github.agentic.ai.runtime.execution.TrustedExecutionContext;

import java.time.Clock;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 复现 #272：外部可变引用和读取接口不能改变已构造、已发布或正在运行的定义。 */
class WorkflowDefinitionImmutabilityTest {

	/** 验证受支持的不可变标量及嵌套 null 不会因生成快照改变值或数字类型。 */
	@Test
	void supportedScalarsAndNestedNullKeepTheirValues() {
		final List<Object> values = new ArrayList<>(List.of("text", true, 'x', (byte) 1, (short) 2, 3, 4L,
				5.5F, 6.5D, new BigInteger("12345678901234567890"), new BigDecimal("12.50")));
		values.add(null);
		final Map<String, Object> source = new LinkedHashMap<>();
		source.put("values", values);
		source.put("optional", null);
		final WorkflowValue.Literal literal = WorkflowValue.literal(source);

		assertEquals(source, literal.value());
		assertEquals(new BigDecimal("12.50"), list(map(literal.value()).get("values")).get(10));
		assertEquals(null, WorkflowValue.literal(null).value());
	}

	/** 验证自定义可变数字不能作为不可变标量透传到 Literal 或元数据。 */
	@ParameterizedTest(name = "metadata={0}")
	@ValueSource(booleans = { false, true })
	void mutableObjectsAreRejected(boolean metadata) {
		final AtomicInteger mutable = new AtomicInteger(1);
		if (metadata) {
			assertThrows(IllegalArgumentException.class,
					() -> definition(expectedPayload()).withMetadata("counter", mutable));
		}
		else {
			assertThrows(IllegalArgumentException.class,
					() -> WorkflowValue.literal(Map.of("counter", mutable)));
		}
	}

	/** 验证循环 Map/List 被明确拒绝，而不是在快照时耗尽 JVM 调用栈。 */
	@ParameterizedTest(name = "cyclicList={0}")
	@ValueSource(booleans = { false, true })
	void cyclicCollectionsAreRejected(boolean cyclicList) {
		final Object cycle;
		if (cyclicList) {
			final List<Object> list = new ArrayList<>();
			list.add(list);
			cycle = list;
		}
		else {
			final Map<String, Object> map = new LinkedHashMap<>();
			map.put("self", map);
			cycle = map;
		}

		assertThrows(IllegalArgumentException.class, () -> WorkflowValue.literal(cycle));
		assertThrows(IllegalArgumentException.class, () -> definition(expectedPayload()).withMetadata("cycle", cycle));
	}

	/** 验证 JSON 对象的非字符串键被拒绝，避免快照或序列化时悄悄改写键。 */
	@Test
	void nonStringMapKeysAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> WorkflowValue.literal(Map.of(1, "value")));
	}

	/** 验证超深的无环结构被明确拒绝，避免递归快照导致栈溢出。 */
	@Test
	void excessivelyNestedCollectionsAreRejected() {
		Object nested = "leaf";
		for (int depth = 0; depth < 256; depth++) {
			nested = List.of(nested);
		}
		final Object value = nested;

		assertThrows(IllegalArgumentException.class, () -> WorkflowValue.literal(value));
	}

	/** 验证复用浅层已复制的共享引用时，不能绕过另一条更深路径的嵌套上限。 */
	@Test
	void cachedSharedReferenceCannotBypassDepthLimit() {
		Object shared = "leaf";
		for (int depth = 0; depth < 100; depth++) {
			shared = List.of(shared);
		}
		Object deeper = shared;
		for (int depth = 0; depth < 90; depth++) {
			deeper = List.of(deeper);
		}
		final Map<String, Object> source = new LinkedHashMap<>();
		source.put("shallow", shared);
		source.put("deep", deeper);

		assertThrows(IllegalArgumentException.class, () -> WorkflowValue.literal(source));
	}

	/** 验证文档允许的 128 层嵌套仍然可用，不因边界判断提前拒绝。 */
	@Test
	void supportedNestingBoundaryIsAccepted() {
		Object nested = "leaf";
		for (int depth = 0; depth < 128; depth++) {
			nested = List.of(nested);
		}
		final Object value = nested;

		assertDoesNotThrow(() -> WorkflowValue.literal(value));
	}

	/** 验证元数据顶层继续拒绝 null，同时嵌套 null 仍可保留。 */
	@Test
	void metadataKeepsItsExistingNullBoundary() {
		final Map<String, Object> invalid = new LinkedHashMap<>();
		invalid.put("audit", null);
		assertThrows(NullPointerException.class, () -> new WorkflowDefinition("null-metadata", "1",
				definition(expectedPayload()).steps(), null, null, invalid));
		final Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("optional", null);
		final WorkflowDefinition definition = definition(expectedPayload()).withMetadata("audit", nested);

		assertEquals(nested, definition.metadata().get("audit"));
	}

	/** 验证未经泛型检查的元数据键也得到明确校验错误，而不是类型转换异常。 */
	@Test
	@SuppressWarnings("unchecked")
	void nonStringTopLevelMetadataKeysAreRejectedExplicitly() {
		final Map<String, Object> invalid = (Map<String, Object>) (Map<?, ?>) Map.of(1, "value");

		assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinition("invalid-key", "1",
				definition(expectedPayload()).steps(), null, null, invalid));
	}

	/** 验证 Literal 构造后，修改调用方原始 Map 不会改变 Literal 的值。 */
	@Test
	void literalDetachesCallerMapAtConstruction() {
		final Map<String, Object> source = new LinkedHashMap<>(Map.of("target", "reviewed"));
		final WorkflowValue.Literal literal = WorkflowValue.literal(source);

		source.put("target", "unreviewed");

		assertEquals(Map.of("target", "reviewed"), literal.value());
	}

	/** 验证发布后修改原始多层 Map/List，已发布版本仍保留审核时的数据。 */
	@Test
	void publishedLiteralDetachesNestedCallerCollections() {
		final MutablePayload source = mutablePayload();
		final WorkflowRegistry registry = publish(source.payload());

		source.row().put("target", "unreviewed");
		source.rows().add(Map.of("target", "extra"));

		assertEquals(expectedPayload(), literalValue(registry.get("published", "1")));
	}

	/** 验证通过读取接口修改嵌套 Map 或 List，不会反向修改已发布版本。 */
	@ParameterizedTest(name = "mutateList={0}")
	@ValueSource(booleans = { false, true })
	void publishedLiteralResistsMutationsThroughAccessors(boolean mutateList) {
		final WorkflowRegistry registry = publish(mutablePayload().payload());
		final Map<?, ?> payload = map(literalValue(registry.get("published", "1")));
		final List<?> rows = list(payload.get("requests"));

		if (mutateList) {
			mutateIfAllowed(rows::clear);
		}
		else {
			mutateIfAllowed(() -> map(rows.get(0)).clear());
		}

		assertEquals(expectedPayload(), literalValue(registry.get("published", "1")));
	}

	/** 验证多个字段共享同一原始 List 时，修改共享引用不会改变任何已发布字段。 */
	@Test
	void sharedCallerReferencesDoNotChangePublishedValues() {
		final List<String> shared = new ArrayList<>(List.of("reviewed"));
		final WorkflowRegistry registry = publish(Map.of("left", shared, "right", shared));

		shared.add("unreviewed");

		assertEquals(Map.of("left", List.of("reviewed"), "right", List.of("reviewed")),
				literalValue(registry.get("published", "1")));
	}

	/** 验证元数据发布后，调用方继续修改原始嵌套集合不会改变元数据。 */
	@Test
	void publishedMetadataDetachesNestedCallerCollections() {
		final List<String> tags = new ArrayList<>(List.of("reviewed"));
		final Map<String, Object> audit = new LinkedHashMap<>(Map.of("tags", tags));
		final WorkflowDefinition definition = definition(expectedPayload()).withMetadata("audit", audit);
		final WorkflowRegistry registry = new WorkflowRegistry();
		registry.publish(definition);

		tags.add("unreviewed");
		audit.put("approved", false);

		assertEquals(Map.of("audit", Map.of("tags", List.of("reviewed"))),
				registry.get("published", "1").metadata());
	}

	/** 验证从元数据读取接口取得嵌套集合后，不能借它修改已发布版本。 */
	@Test
	void publishedMetadataResistsMutationsThroughAccessors() {
		final WorkflowRegistry registry = new WorkflowRegistry();
		registry.publish(definition(expectedPayload()).withMetadata("audit",
				Map.of("tags", new ArrayList<>(List.of("reviewed")))));
		final List<?> tags = list(map(registry.get("published", "1").metadata().get("audit")).get("tags"));

		mutateIfAllowed(tags::clear);

		assertEquals(Map.of("audit", Map.of("tags", List.of("reviewed"))),
				registry.get("published", "1").metadata());
	}

	/** 验证 JSON 解析得到的 Literal 和元数据，同样不能通过嵌套读取引用被修改。 */
	@Test
	void parsedJsonDefinitionsKeepNestedLiteralAndMetadataStable() {
		final String json = """
				{"id":"published","version":"1",
				 "metadata":{"audit":{"tags":["reviewed"]}},
				 "steps":[{"type":"tool","id":"capture","toolName":"capture",
				 "arguments":{"payload":{"type":"literal",
				 "value":{"requests":[{"target":"reviewed"}]}}}}]}
				""";
		final WorkflowRegistry registry = new WorkflowRegistry();
		registry.publish(new WorkflowDefinitionParser().parse(json));
		final WorkflowDefinition published = registry.get("published", "1");
		final List<?> rows = list(map(literalValue(published)).get("requests"));
		final List<?> tags = list(map(published.metadata().get("audit")).get("tags"));

		mutateIfAllowed(() -> map(rows.get(0)).clear());
		mutateIfAllowed(tags::clear);

		assertAll(() -> assertEquals(expectedPayload(), literalValue(registry.get("published", "1"))),
				() -> assertEquals(Map.of("audit", Map.of("tags", List.of("reviewed"))),
						registry.get("published", "1").metadata()));
	}

	/** 验证原始数据被外部修改后，重新发布审核时的同一内容仍被视为同一版本。 */
	@Test
	void callerMutationDoesNotCreateAFakeVersionConflict() {
		final MutablePayload source = mutablePayload();
		final WorkflowRegistry registry = publish(source.payload());
		final WorkflowDefinition original = registry.get("published", "1");

		source.row().put("target", "unreviewed");

		final WorkflowDefinition republished = assertDoesNotThrow(() -> registry.publish(definition(expectedPayload())));
		assertSame(original, republished);
		assertEquals(expectedPayload(), literalValue(republished));
	}

	/** 验证修改外部引用不能绕过同版本冲突检测，将新内容伪装成已发布内容。 */
	@Test
	void callerMutationDoesNotBypassVersionConflictDetection() {
		final MutablePayload source = mutablePayload();
		final WorkflowRegistry registry = publish(source.payload());
		source.row().put("target", "unreviewed");
		final Object changed = Map.of("requests", List.of(Map.of("target", "unreviewed")));

		assertThrows(IllegalStateException.class, () -> registry.publish(definition(changed)));
	}

	/** 验证没有外部修改时，等价定义可重复发布，不同内容不能覆盖同一版本。 */
	@Test
	void equalRepublishAndRealConflictKeepExistingBehavior() {
		final WorkflowRegistry registry = publish(expectedPayload());
		final WorkflowDefinition original = registry.get("published", "1");

		assertSame(original, registry.publish(definition(expectedPayload())));
		assertThrows(IllegalStateException.class,
				() -> registry.publish(definition(Map.of("requests", List.of(Map.of("target", "different"))))));
		assertEquals(expectedPayload(), literalValue(registry.get("published", "1")));
	}

	/** 用待完成的本地工具暂停运行，验证运行中修改原始引用不会改变后续步骤参数。 */
	@Test
	void runningWorkflowKeepsPublishedLiteralSnapshot() throws Exception {
		final Map<String, Object> payload = new LinkedHashMap<>(Map.of("target", "reviewed"));
		final CompletableFuture<Void> gateEntered = new CompletableFuture<>();
		final CompletableFuture<ToolResult> release = new CompletableFuture<>();
		final Tool gate = tool("gate", (call, context) -> {
			gateEntered.complete(null);
			return release;
		});
		final Tool capture = tool("capture", (call, context) ->
				CompletableFuture.completedFuture(ToolResult.text(call.arguments())));
		final WorkflowDefinition definition = new WorkflowDefinition("running", "1", List.of(
				new WorkflowStep.Tool("gate", "gate", Map.of()),
				new WorkflowStep.Tool("capture", "capture", Map.of("payload", WorkflowValue.literal(payload)))));
		final WorkflowRegistry registry = new WorkflowRegistry();
		registry.publish(definition);
		final WorkflowAgent workflow = WorkflowAgent.builder(registry.get("running", "1")).registry(registry)
			.tools(new ToolRegistry(List.of(gate, capture))).build();

		try (AgentExecutionContext context = context()) {
			final CompletableFuture<AgentOutcome> result = workflow.invoke(
					AgentRequest.messages(List.of(ChatMessage.user("Run published workflow"))), context);
			try {
				gateEntered.get(5, TimeUnit.SECONDS);
				payload.put("target", "unreviewed");
				release.complete(ToolResult.text("continue"));
				final AgentOutcome outcome = result.get(5, TimeUnit.SECONDS);
				assertEquals(AgentOutcome.Status.COMPLETED, outcome.status());
				final String capturedArguments = (String) outcome.outputs().get("capture");
				assertEquals("reviewed", new ObjectMapper().readTree(capturedArguments)
					.path("payload").path("target").asText());
			}
			finally {
				release.complete(ToolResult.text("continue"));
				result.cancel(true);
			}
		}
	}

	/** 创建可分别修改叶子 Map、嵌套 List 和外层 Map 的输入数据。 */
	private static MutablePayload mutablePayload() {
		final Map<String, Object> row = new LinkedHashMap<>(Map.of("target", "reviewed"));
		final List<Object> rows = new ArrayList<>(List.of(row));
		return new MutablePayload(row, rows, new LinkedHashMap<>(Map.of("requests", rows)));
	}

	/** 提供手工确定的审核快照，不从被测定义反向计算预期值。 */
	private static Object expectedPayload() {
		return Map.of("requests", List.of(Map.of("target", "reviewed")));
	}

	/** 构造固定 ID 和版本的最小工具工作流。 */
	private static WorkflowDefinition definition(Object payload) {
		return new WorkflowDefinition("published", "1", List.of(
				new WorkflowStep.Tool("capture", "capture", Map.of("payload", WorkflowValue.literal(payload)))));
	}

	/** 发布定义并返回注册表，后续检查均从注册表重新读取。 */
	private static WorkflowRegistry publish(Object payload) {
		final WorkflowRegistry registry = new WorkflowRegistry();
		registry.publish(definition(payload));
		return registry;
	}

	/** 通过公开读取接口取得工具参数中的 Literal 值。 */
	private static Object literalValue(WorkflowDefinition definition) {
		final WorkflowStep.Tool step = assertInstanceOf(WorkflowStep.Tool.class, definition.steps().get(0));
		return assertInstanceOf(WorkflowValue.Literal.class, step.arguments().get("payload")).value();
	}

	/** 检查读取值为 Map，避免用未检查的泛型强制转换掩盖结构问题。 */
	private static Map<?, ?> map(Object value) {
		return assertInstanceOf(Map.class, value);
	}

	/** 检查读取值为 List，并保留只读访问所需的类型信息。 */
	private static List<?> list(Object value) {
		return assertInstanceOf(List.class, value);
	}

	/** 尝试修改读取值，兼容不可变返回值或可修改的独立副本两种隔离实现。 */
	private static void mutateIfAllowed(Runnable mutation) {
		try {
			mutation.run();
		}
		catch (UnsupportedOperationException ignored) {
		}
	}

	/** 创建本地工具，不调用模型或外部服务。 */
	private static Tool tool(String name, BiFunction<ToolCall, ToolContext, CompletionStage<ToolResult>> executor) {
		return new Tool() {
			@Override
			public ToolDefinition definition() {
				return new ToolDefinition(name, name, "{\"type\":\"object\"}");
			}

			@Override
			public CompletionStage<ToolResult> execute(ToolCall call, ToolContext context) {
				return executor.apply(call, context);
			}
		};
	}

	/** 创建独立执行上下文，用于真实 Workflow 运行。 */
	private static AgentExecutionContext context() {
		final TrustedExecutionContext trusted = TrustedExecutionContext.builder()
			.tenant(new TenantReference("test", "tenant"))
			.principal(new PrincipalReference("test", "principal"))
			.runId("immutability-test").correlationId("immutability-correlation")
			.budget(ExecutionBudget.unlimited()).delegationScope(DelegationScope.none()).build();
		return new AgentExecutionContext(trusted, Clock.systemUTC());
	}

	private record MutablePayload(Map<String, Object> row, List<Object> rows, Map<String, Object> payload) {
	}

}
