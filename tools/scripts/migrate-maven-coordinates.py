#!/usr/bin/env python3
# Copyright 2024-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Migrate Core Maven coordinates, preserving POM formatting")
    parser.add_argument("poms", nargs="+", type=Path)
    parser.add_argument("--write", action="store_true", help="Apply changes with exclusive .before-argi-rc1 backups")
    args = parser.parse_args()
    core_artifacts = {
        "argi", "argi-bom", "argi-loop-core", "argi-agent-framework", "argi-studio",
        "argi-starter-builtin-nodes", "argi-starter-graph-observation",
    }
    # Restrict migration to Core; Extensions artifacts have a separate release lifecycle.
    block = re.compile(r"<(dependency|parent)>.*?</\1>", re.DOTALL)
    coordinate = re.compile(r"(<groupId>\s*)io\.github\.agentic-ai(\s*</groupId>)")
    artifact = re.compile(r"<artifactId>\s*([^<]+?)\s*</artifactId>")
    plans = []
    for path in dict.fromkeys(args.poms):
        ET.parse(path)
        text = path.read_text(encoding="utf-8")
        def migrate(match):
            value = match.group(0)
            name = artifact.search(value)
            return coordinate.sub(r"\g<1>io.github.agentic-ai-java\2", value) if name and name[1] in core_artifacts else value
        updated = block.sub(migrate, text)
        if updated != text:
            plans.append((path, text, updated))
    if args.write:
        for path, text, updated in plans:
            backup = path.with_name(path.name + ".before-argi-rc1")
            with backup.open("x", encoding="utf-8", newline="") as stream:
                stream.write(text)
            path.write_text(updated, encoding="utf-8", newline="")
            print(f"Migrated {path}; backup: {backup}")
    else:
        for path, _, _ in plans:
            print(f"Would migrate {path}")


if __name__ == "__main__":
    main()
