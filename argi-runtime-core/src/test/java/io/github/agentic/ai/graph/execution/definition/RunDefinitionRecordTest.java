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

package io.github.agentic.ai.graph.execution.definition;

import io.github.agentic.ai.graph.execution.run.RunSpec;

import org.junit.jupiter.api.Test;

import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.APP_V5;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.CODEC_V2;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.GRAPH_V1;
import static io.github.agentic.ai.graph.execution.definition.DefinitionTestSupport.TOOLS_V3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunDefinitionRecordTest {

	private final RunDefinitionRecordCodec codec = new RunDefinitionRecordCodec();

	@Test
	void recordCarriesOneReferencePerKindInOrder() {
		RunDefinitionRecord record = DefinitionTestSupport.record();

		assertThat(record.schemaVersion()).isEqualTo(1);
		assertThat(record.references()).containsExactly(GRAPH_V1, TOOLS_V3, CODEC_V2, APP_V5);
		assertThat(record.reference(DefinitionKind.CODEC)).isEqualTo(CODEC_V2);
		assertThat(record.reference(DefinitionKind.APPLICATION)).isEqualTo(APP_V5);
	}

	@Test
	void rejectsAReferenceInTheWrongSlot() {
		assertThatThrownBy(() -> RunDefinitionRecord.of(TOOLS_V3, TOOLS_V3, CODEC_V2, APP_V5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Execution definition kind is not supported");
		assertThatThrownBy(() -> RunDefinitionRecord.of(GRAPH_V1, GRAPH_V1, CODEC_V2, APP_V5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("toolCatalog must be a definition reference of kind tool_catalog");
		assertThatThrownBy(() -> RunDefinitionRecord.of(GRAPH_V1, TOOLS_V3, APP_V5, APP_V5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("codec must be a definition reference of kind codec");
		assertThatThrownBy(() -> RunDefinitionRecord.of(GRAPH_V1, TOOLS_V3, CODEC_V2, CODEC_V2))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("application must be a definition reference of kind application");
		assertThatThrownBy(() -> new RunDefinitionRecord(0, GRAPH_V1, TOOLS_V3, CODEC_V2, APP_V5))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("schemaVersion must be positive");
	}

	@Test
	void graphReferenceComesFromTheAdmittedRun() {
		RunSpec run = DefinitionTestSupport.run("run-1", "checkout", "1");
		RunDefinitionRecord record = RunDefinitionRecord.forRun(run, TOOLS_V3, CODEC_V2, APP_V5);

		assertThat(record.graph()).isEqualTo(DefinitionReference.of(DefinitionKind.GRAPH, "checkout", "1"));
		assertThat(record.toolCatalog()).isEqualTo(TOOLS_V3);
	}

	@Test
	void roundTripsThroughStrictJson() {
		RunDefinitionRecord record = DefinitionTestSupport.record();
		String json = this.codec.encode(record);

		assertThat(this.codec.readSchemaVersion(json)).isEqualTo(1);
		assertThat(this.codec.decode(json)).isEqualTo(record);
	}

	@Test
	void reportsAnUnreadableEnvelopeAsAStructuredCodecFailure() {
		String json = this.codec.encode(DefinitionTestSupport.record())
			.replace("\"schemaVersion\":1", "\"schemaVersion\":3");

		assertThatThrownBy(() -> this.codec.decode(json))
			.isInstanceOfSatisfying(CodecIncompatibilityException.class, (ex) -> {
				assertThat(ex.code()).isEqualTo(DefinitionFailureCode.ENVELOPE_VERSION_UNSUPPORTED);
				assertThat(ex.subject()).isEqualTo("definition envelope");
				assertThat(ex.recorded()).isEqualTo("3");
				assertThat(ex.supported()).containsExactly("2");
			});
		assertThat(this.codec.readSchemaVersion(json)).isEqualTo(3);
	}

	@Test
	void rejectsMalformedOrAmbiguousJson() {
		String valid = this.codec.encode(DefinitionTestSupport.record());

		assertThatThrownBy(() -> this.codec.decode("[1,2,3]")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.codec.decode("{\"schemaVersion\":1}"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.codec.decode("{\"schemaVersion\":\"1\"}"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.codec.decode("{}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.codec.decode(valid + "{}")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.codec.decode(valid.replace("\"toolCatalog\"", "\"unknownField\"")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.codec.decode(valid.replaceFirst("\\{", "{\"schemaVersion\":1,")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.codec.decode(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.codec.encode(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unknownSchemaVersionIsNotSilentlyAccepted() {
		String json = this.codec.encode(DefinitionTestSupport.record())
			.replace("\"schemaVersion\":1", "\"schemaVersion\":99");

		assertThatThrownBy(() -> this.codec.decode(json))
			.isInstanceOf(CodecIncompatibilityException.class);
	}

}
