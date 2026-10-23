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
package io.github.agentic.ai.graph.skills.registry.classpath;

import io.github.agentic.ai.graph.skills.SkillMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClasspathSkillRegistryTest {

	@Test
	void registryLoadsListsReadsAndCopiesClasspathSkills(@TempDir Path tempDir) throws Exception {
		ClasspathSkillRegistry registry = ClasspathSkillRegistry.builder()
			.classpathPath("skills")
			.basePath(tempDir.toString())
			.build();

		assertNotNull(registry);
		assertEquals("Classpath", registry.getRegistryType());
		assertEquals(1, registry.size());
		assertTrue(registry.contains("sample-skill"));

		List<SkillMetadata> skills = registry.listAll();
		assertEquals(1, skills.size());
		SkillMetadata skill = skills.get(0);
		assertEquals("sample-skill", skill.getName());
		assertEquals("Sample skill fixture for classpath registry enhancement tests.", skill.getDescription());
		assertTrue(Path.of(skill.getSkillPath()).startsWith(tempDir));
		assertTrue(Files.isRegularFile(Path.of(skill.getSkillPath()).resolve("SKILL.md")));
		assertTrue(registry.readSkillContent("sample-skill").contains("# Sample Skill"));
	}

	@Test
	void registryReloadKeepsClasspathSkillsAvailable(@TempDir Path tempDir) throws Exception {
		ClasspathSkillRegistry registry = ClasspathSkillRegistry.builder()
			.classpathPath("skills")
			.basePath(tempDir.toString())
			.build();

		registry.reload();

		assertEquals(1, registry.size());
		assertFalse(registry.listAll().isEmpty());
		assertTrue(registry.get("sample-skill").isPresent());
	}

}
