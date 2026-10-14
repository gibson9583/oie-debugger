#!/usr/bin/env python3
"""Check source versions and the exact installable ZIP before publication."""

import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_versions(root=ROOT, environ=None):
    environ = os.environ if environ is None else environ
    pom = ET.parse(root / "pom.xml")
    version = pom.findtext("m:properties/m:revision", namespaces=NS)
    require(bool(re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", version or "")),
            "Maven revision must be a release version")
    catalog = json.loads((root / "oie.json").read_text())
    require(catalog["version"] == version, f"oie.json version must match Maven revision {version}")
    require(catalog["filename"].replace("{version}", version) == f"oie-debugger-{version}.zip",
            "Catalog filename must match the release package")
    require((root / catalog["storeDocs"]).is_file(), "Catalog store documentation must exist")
    for name in ("plugin.json", "package.json", "package-lock.json"):
        data = json.loads((root / "package/webadmin" / name).read_text())
        require(data["version"] == version, f"{name} version must match Maven revision {version}")
        if name == "package-lock.json":
            require(data["packages"][""]["version"] == version, "Lockfile root version mismatch")
    if environ.get("GITHUB_REF_TYPE") == "tag":
        require(environ.get("GITHUB_REF_NAME") == "v" + version,
                f"Release tag must be v{version}")
    require((root / "LICENSE").read_bytes() == (root / "package/resources/LICENSE").read_bytes(),
            "Packaged license must match repository license")
    return version


def verify_bundle(bundle, root=ROOT, environ=None):
    version = verify_versions(root, environ)
    prefix = "oie-debugger/"
    jars = {f"libs/oie-debugger-{module}-{version}.jar" for module in ("shared", "server")}
    expected = {prefix + name for name in jars | {"plugin.xml", "LICENSE", "webadmin/plugin.json", "webadmin/web/plugin.js"}}
    require(bundle.name == f"oie-debugger-{version}.zip", "Package filename version mismatch")
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entries")
        require({entry.filename for entry in archive.infolist() if not entry.is_dir()} == expected,
                "Package must contain exactly the descriptor, license, browser assets and two plugin JARs")
        require(archive.testzip() is None, "Package ZIP CRC mismatch")
        descriptor = ET.fromstring(archive.read(prefix + "plugin.xml"))
        require(descriptor.attrib.get("path") == "oie-debugger", "Wrong plugin installation path")
        require(descriptor.findtext("pluginVersion") == version, "Packaged descriptor version mismatch")
        require({node.attrib["path"] for node in descriptor.findall("library")} == jars,
                "Descriptor libraries do not match packaged JARs")
        for relative in ("LICENSE", "webadmin/plugin.json"):
            source = root / "package" / ("resources/LICENSE" if relative == "LICENSE" else relative)
            require(archive.read(prefix + relative) == source.read_bytes(), f"Packaged {relative} changed")
        require(bool(archive.read(prefix + "webadmin/web/plugin.js")), "Browser bundle is empty")
    return version


if __name__ == "__main__":
    try:
        require(len(sys.argv) <= 2, "Usage: verify-package.py [package.zip]")
        version = verify_bundle(Path(sys.argv[1])) if len(sys.argv) == 2 else verify_versions()
        print(f"Validated OIE Debugger {version}" + (" package" if len(sys.argv) == 2 else " source versions"))
    except (ValueError, KeyError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error)) from None
