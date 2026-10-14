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
package io.github.agentic.ai.graph.agent;

import io.github.agentic.ai.graph.agent.hook.skills.DisableSkillTool;
import io.github.agentic.ai.graph.agent.hook.skills.ReadSkillTool;
import io.github.agentic.ai.graph.agent.hook.skills.SearchSkillsTool;
import io.github.agentic.ai.graph.agent.hook.skills.SkillsAgentHook;
import io.github.agentic.ai.graph.skills.registry.SkillRegistry;
import io.github.agentic.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.agentic.ai.api.tool.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillsToolingTest {

	@TempDir
	Path tempDir;

	private SkillRegistry registry;

	private Path skillDir;

	@BeforeEach
	void setUp() throws Exception {
		Path skillsDir = tempDir.resolve("skills");
		skillDir = skillsDir.resolve("allowed-tools-test");
		Files.createDirectories(skillDir);
		Files.writeString(skillDir.resolve("SKILL.md"), """
				---
				name: allowed-tools-test
				description: Skill fixture for tool-level unit tests.
				allowed_tools:
				  - record_result
				---

				# Allowed Tools Test

				Test fixture content.
				""");
		registry = FileSystemSkillRegistry.builder().projectSkillsDirectory(skillsDir.toString()).build();
	}

	@Test
	void readSkillToolSupportsNameAndPathResolution() {
		ReadSkillTool tool = new ReadSkillTool(registry);

		String byName = tool.apply(new ReadSkillTool.ReadSkillRequest("allowed-tools-test", null),
				new ToolContext(Map.of()));
		String byPath = tool.apply(new ReadSkillTool.ReadSkillRequest(null, skillDir.toString()),
				new ToolContext(Map.of()));

		assertTrue(byName.contains("# Allowed Tools Test"));
		assertEquals(byName, byPath);
	}

	@Test
	void searchAndDisableToolsReflectRegistryState() {
		SearchSkillsTool searchTool = new SearchSkillsTool(registry);
		DisableSkillTool disableTool = new DisableSkillTool(registry);
		ToolContext context = new ToolContext(Map.of());

		String searchResult = searchTool.apply(new SearchSkillsTool.SearchSkillsRequest("allowed-tools"), context);
		assertTrue(searchResult.contains("allowed-tools-test"));
		assertTrue(searchResult.contains("source=project"));
		assertTrue(searchResult.contains("allowed_tools=[record_result]"));

		String disableResult = disableTool.apply(new DisableSkillTool.DisableSkillRequest(null, skillDir.toString()),
				context);
		assertTrue(disableResult.contains("disabled"));

		assertFalse(searchTool.apply(new SearchSkillsTool.SearchSkillsRequest("allowed-tools"), context)
			.contains("allowed-tools-test"));
	}

	@Test
	void skillsAgentHookExposesRegistryTools() {
		SkillsAgentHook hook = SkillsAgentHook.builder().skillRegistry(registry).build();
		List<String> toolNames = hook.getTools().stream().map(tool -> tool.definition().name()).toList();

		assertEquals(List.of("read_skill", "search_skills", "disable_skill"), toolNames);
		assertFalse(toolNames.contains("allowed-tools-test"));
	}

}
