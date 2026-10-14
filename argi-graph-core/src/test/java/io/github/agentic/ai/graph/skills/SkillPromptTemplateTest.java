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
package io.github.agentic.ai.graph.skills;

import java.util.List;

import io.github.agentic.ai.graph.skills.registry.SkillRegistry;
import io.github.agentic.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import io.github.agentic.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import io.github.agentic.ai.graph.utils.PromptTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPromptTemplateTest {

	private static final String CONTEXT_TEMPLATE = "{skills_registry}\n{skills_list}\n{skills_load_instructions}";

	@Test
	void filesystemStringTemplateReceivesAllDocumentedVariables() {
		FileSystemSkillRegistry registry = FileSystemSkillRegistry.builder()
			.autoLoad(false)
			.systemPromptTemplate(CONTEXT_TEMPLATE)
			.build();
		assertPrompt(registry, "FileSystem");
	}

	@Test
	void classpathStringTemplateReceivesAllDocumentedVariables() {
		ClasspathSkillRegistry registry = ClasspathSkillRegistry.builder()
			.autoLoad(false)
			.systemPromptTemplate(CONTEXT_TEMPLATE)
			.build();
		assertPrompt(registry, "Classpath");
	}

	@Test
	void registriesAcceptCustomFrameworkNeutralRenderers() {
		PromptTemplate renderer = variables -> variables.get("skills_registry") + " custom renderer";
		FileSystemSkillRegistry filesystem = FileSystemSkillRegistry.builder()
			.autoLoad(false)
			.systemPromptTemplate(renderer)
			.build();
		ClasspathSkillRegistry classpath = ClasspathSkillRegistry.builder()
			.autoLoad(false)
			.systemPromptTemplate(renderer)
			.build();
		assertSame(renderer, filesystem.getSystemPromptTemplate());
		assertSame(renderer, classpath.getSystemPromptTemplate());
		assertEquals("FileSystem custom renderer",
				SkillPromptConstants.buildSkillsPrompt(List.of(), filesystem, renderer));
		assertEquals("Classpath custom renderer",
				SkillPromptConstants.buildSkillsPrompt(List.of(), classpath, renderer));
	}

	@Test
	void skillsPromptUsesTheNeutralTemplateContract() {
		FileSystemSkillRegistry registry = FileSystemSkillRegistry.builder().autoLoad(false).build();
		String prompt = SkillPromptConstants.buildSkillsPrompt(skills(), registry, PromptTemplate.of(CONTEXT_TEMPLATE));
		assertTrue(prompt.startsWith("FileSystem\n"));
		assertTrue(prompt.contains("**User Skills:**"));
		assertTrue(prompt.contains("**Project Skills:**"));
		assertTrue(prompt.contains("Supporting files"));
		assertTrue(prompt.endsWith(registry.getSkillLoadInstructions()));
	}

	@Test
	void emptyRegistryAndDefaultTemplatesRenderWithoutMissingVariables() {
		for (SkillRegistry registry : List.of(FileSystemSkillRegistry.builder().autoLoad(false).build(),
				ClasspathSkillRegistry.builder().autoLoad(false).build())) {
			String prompt = SkillPromptConstants.buildSkillsPrompt(List.of(), registry,
					registry.getSystemPromptTemplate());
			assertTrue(prompt.contains("Skills System"));
		}
	}

	private void assertPrompt(SkillRegistry registry, String registryType) {
		String prompt = SkillPromptConstants.buildSkillsPrompt(skills(), registry, registry.getSystemPromptTemplate());
		assertTrue(prompt.startsWith(registryType + "\n"));
		assertTrue(prompt.contains("**User Skills:**"));
		assertTrue(prompt.contains("**Project Skills:**"));
		assertTrue(prompt.contains("`/user/docs`"));
		assertTrue(prompt.contains("`/project/code`"));
		assertTrue(prompt.endsWith(registry.getSkillLoadInstructions()));
	}

	private List<SkillMetadata> skills() {
		return List.of(
				SkillMetadata.builder()
					.name("docs")
					.description("Documentation")
					.skillPath("/user/docs")
					.source("user")
					.build(),
				SkillMetadata.builder()
					.name("code")
					.description("Code review")
					.skillPath("/project/code")
					.source("project")
					.build());
	}

}
