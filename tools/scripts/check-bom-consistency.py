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
"""Checks that the security patch families of the root build and the published
ARGI BOM stay aligned (issue #295).

The root pom.xml and argi-bom/pom.xml both declare the versions of the shared
security families (BouncyCastle, Tomcat embed, Jackson, jsoup) as properties,
and the two files are maintained by hand. A root upgrade that forgets the BOM
ships consumers a different version than the main build resolves. This check
compares the two source poms directly, so it runs on every CI build without
requiring a release package or a flattened POM.

With --verify-consumer, the script additionally builds a minimal out-of-repo
consumer that imports only the installed argi-bom and resolves the managed
BouncyCastle and Tomcat artifacts without versions, asserting that the
effective versions are exactly what the BOM declares. This is the validation
the issue asks for: effective versions as a consumer sees them, not reactor
property strings. It requires argi-bom to be installed into the local
repository first (in CI the build job installs it before this step).

Exit code 0 means aligned, 1 means drift or verification failure.
"""

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

ROOT_POM = REPO_ROOT / "pom.xml"
BOM_POM = REPO_ROOT / "argi-bom" / "pom.xml"

# Properties the root build and the published BOM must keep aligned. Both sides
# are hand-maintained; any drift here is what issue #295 reported.
SHARED_PROPERTIES = (
    "revision",
    "jackson-2-bom.version",
    "jackson-bom.version",
    "bouncycastle.version",
    "jsoup.version",
    "tomcat.version",
)

# The artifacts the consumer verification resolves without versions. The
# BouncyCastle family is managed but unused inside the main build (published for
# consumers only); the Tomcat embed family is the real transitive pin.
CONSUMER_ARTIFACTS = (
    ("org.bouncycastle", "bcprov-jdk18on"),
    ("org.bouncycastle", "bcpkix-jdk18on"),
    ("org.bouncycastle", "bcutil-jdk18on"),
    ("org.apache.tomcat.embed", "tomcat-embed-core"),
    ("org.apache.tomcat.embed", "tomcat-embed-el"),
    ("org.apache.tomcat.embed", "tomcat-embed-jasper"),
    ("org.apache.tomcat.embed", "tomcat-embed-websocket"),
)


def properties(pom_path: Path) -> dict[str, str]:
    root = ET.parse(pom_path).getroot()
    node = root.find("m:properties", NS)
    if node is None:
        raise ValueError(f"{pom_path} has no <properties> section")
    return {child.tag.replace(f"{{{NS['m']}}}", ""): (child.text or "").strip() for child in node}


def check_source_consistency() -> bool:
    root_props = properties(ROOT_POM)
    bom_props = properties(BOM_POM)
    aligned = True
    for name in SHARED_PROPERTIES:
        root_value = root_props.get(name)
        bom_value = bom_props.get(name)
        if root_value is None or bom_value is None:
            print(f"MISSING {name}: root={root_value!r} bom={bom_value!r}")
            aligned = False
        elif root_value != bom_value:
            print(f"DRIFT {name}: root pom has {root_value}, argi-bom has {bom_value}")
            aligned = False
    if aligned:
        print(f"Aligned: {len(SHARED_PROPERTIES)} shared properties of root pom and argi-bom agree.")
    return aligned


def managed_versions(bom_props: dict[str, str]) -> dict[tuple[str, str], str]:
    """Reads the managed group:artifact -> version map from the BOM dependencyManagement,
    resolving property references through the BOM properties."""
    root = ET.parse(BOM_POM).getroot()
    management = root.find("m:dependencyManagement/m:dependencies", NS)
    if management is None:
        return {}
    managed: dict[tuple[str, str], str] = {}
    for dependency in management.findall("m:dependency", NS):
        group_id = dependency.findtext("m:groupId", default="", namespaces=NS).strip()
        artifact_id = dependency.findtext("m:artifactId", default="", namespaces=NS).strip()
        version = dependency.findtext("m:version", default="", namespaces=NS).strip()
        match = re.fullmatch(r"\$\{([^}]+)\}", version)
        if match:
            version = bom_props.get(match.group(1), "")
        if group_id and artifact_id and version:
            managed[(group_id, artifact_id)] = version
    return managed


CONSUMER_POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.github.agentic-ai-java.check</groupId>
    <artifactId>bom-consumer-verify</artifactId>
    <version>0.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>io.github.agentic-ai-java</groupId>
                <artifactId>argi-bom</artifactId>
                <version>{bom_version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <dependencies>
{dependencies}
    </dependencies>
</project>
"""

DEPENDENCY_TEMPLATE = """        <dependency>
            <groupId>{group_id}</groupId>
            <artifactId>{artifact_id}</artifactId>
        </dependency>"""


def verify_consumer(mvn: str) -> bool:
    bom_props = properties(BOM_POM)
    bom_version_value = bom_props.get("revision")
    if not bom_version_value:
        print("argi-bom has no <revision> property to import")
        return False

    with tempfile.TemporaryDirectory(prefix="argi-bom-consumer-") as tmp:
        consumer_dir = Path(tmp)
        dependencies = "\n".join(
            DEPENDENCY_TEMPLATE.format(group_id=group_id, artifact_id=artifact_id)
            for group_id, artifact_id in CONSUMER_ARTIFACTS
        )
        pom_path = consumer_dir / "pom.xml"
        pom_path.write_text(
            CONSUMER_POM_TEMPLATE.format(bom_version=bom_version_value, dependencies=dependencies),
            encoding="utf-8",
        )
        output_file = consumer_dir / "dependency-list.txt"
        # Maven is a batch script on Windows; shutil.which resolves mvn.cmd there.
        mvn_executable = shutil.which(mvn) or shutil.which(mvn + ".cmd") or shutil.which(mvn + ".bat")
        if mvn_executable is None:
            print(f"Maven executable {mvn!r} not found on PATH")
            return False
        result = subprocess.run(
            [mvn_executable, "-B", "-q", "-f", str(pom_path),
             "org.apache.maven.plugins:maven-dependency-plugin:3.7.1:list",
             f"-DoutputFile={output_file}"],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            print(result.stdout)
            print(result.stderr)
            print("Consumer verification could not resolve the BOM; is argi-bom installed "
                  f"at version {bom_version_value}? Run: mvn -pl argi-bom -am install -DskipTests")
            return False
        if not output_file.exists():
            print(f"dependency:list did not write {output_file}")
            return False

        resolved: dict[tuple[str, str], str] = {}
        for line in output_file.read_text(encoding="utf-8").splitlines():
            # The output file lists "group:artifact:type:version[:classifier]:scope"
            # without the console [INFO] prefix.
            match = re.match(r"\s*([^:\s]+):([^:\s]+):([^:\s]+):([^:\s]+)", line)
            if match:
                resolved[(match.group(1), match.group(2))] = match.group(4)

        managed = managed_versions(bom_props)
        aligned = True
        for group_id, artifact_id in CONSUMER_ARTIFACTS:
            expected = managed.get((group_id, artifact_id))
            effective = resolved.get((group_id, artifact_id))
            if expected is None:
                print(f"UNMANAGED {group_id}:{artifact_id} is not managed by argi-bom")
                aligned = False
            elif effective is None:
                print(f"MISSING {group_id}:{artifact_id} was not resolved by the consumer")
                aligned = False
            elif effective != expected:
                print(f"DRIFT {group_id}:{artifact_id}: consumer resolves {effective}, BOM manages {expected}")
                aligned = False
        if aligned:
            print(f"Aligned: all {len(CONSUMER_ARTIFACTS)} managed artifacts resolve to the BOM versions "
                  f"when imported by an out-of-repo consumer.")
        return aligned


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify-consumer", action="store_true",
                        help="additionally resolve the managed artifacts through an out-of-repo consumer "
                             "that imports only the installed argi-bom")
    parser.add_argument("--mvn", default="mvn", help="Maven executable used for the consumer verification")
    args = parser.parse_args()

    if not check_source_consistency():
        return 1
    if args.verify_consumer and not verify_consumer(args.mvn):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
