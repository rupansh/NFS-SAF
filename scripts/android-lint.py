#!/usr/bin/env python3
"""Describe Mill's real inputs to Android Lint's standalone project interface."""

import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

model = json.loads(Path(sys.argv[1]).read_text())
output = Path(sys.argv[2])
root = Path(model["root"])
sdk = Path(model["sdk"])
(output / "cache").mkdir(exist_ok=True)
project = ET.Element("project", {"android": "true"})
ET.SubElement(project, "root", {"dir": str(root)})
ET.SubElement(project, "sdk", {"dir": str(sdk)})
ET.SubElement(project, "cache", {"dir": str(output / "cache")})
module = ET.SubElement(
    project,
    "module",
    {
        "name": "nfs-saf",
        "android": "true",
        "library": "false",
        "compile-sdk-version": str(model["compileSdk"]),
    },
)
ET.SubElement(module, "manifest", {"file": model["mergedManifest"]})
ET.SubElement(module, "merged-manifest", {"file": model["mergedManifest"]})
for source in ("app/src/main/java", "core/src"):
    for file in sorted((root / source).rglob("*.kt")):
        ET.SubElement(module, "src", {"file": str(file)})
ET.SubElement(module, "resource", {"dir": str(root / "app/src/main/res")})
for entry in model["classpath"]:
    if Path(entry).exists():
        ET.SubElement(module, "classpath", {"file": entry})
for entry in model["classes"]:
    ET.SubElement(module, "classes", {"dir": entry})
# The standalone AAR model loads resources/classes but needs explicit lint jars.
checks = output / "checks"
checks.mkdir(exist_ok=True)
for entry in model["aars"]:
    ET.SubElement(module, "aar", {"file": entry})
    with zipfile.ZipFile(entry) as archive:
        if "lint.jar" in archive.namelist():
            jar = checks / (Path(entry).stem + "-lint.jar")
            jar.write_bytes(archive.read("lint.jar"))
            ET.SubElement(project, "lint-checks", {"file": str(jar)})
descriptor = output / "project.xml"
ET.indent(project)
ET.ElementTree(project).write(descriptor, encoding="utf-8", xml_declaration=True)
