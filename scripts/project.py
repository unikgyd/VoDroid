"""Shared, dependency-free build metadata for local builds and GitHub Actions."""
from pathlib import Path
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NATIVE = {
    "lpac/src/main/jni/lpac": "50cdf15ab40942b00f8819a1c2ca19997f1a6d34",
    "lpac/src/main/jni/cjson/cjson": "6d9f2443ab071f86e5d9b43025a40929ec41c46c",
}
REPOSITORY = "unikgyd/VoDroid"


def version():
    values = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
    name, code = values["versionName"], int(values["versionCode"])
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", name) or not 0 < code <= 2100000000:
        raise ValueError("Invalid version.properties")
    return name, code


def release_version(tag):
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", tag):
        raise ValueError("Use a version tag such as v0.4.6 or v0.4.6-rc.1")
    name, _ = version()
    if tag[1:].split("-", 1)[0] != name.split("-", 1)[0]:
        raise ValueError("Tag version does not match version.properties")
    return tag[1:]


def sdk_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and (ROOT / "local.properties").exists():
        for line in (ROOT / "local.properties").read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        raise RuntimeError("Set ANDROID_HOME or local.properties sdk.dir")
    suffix = ".bat" if name == "apksigner" and os.name == "nt" else ".exe" if os.name == "nt" else ""
    tool = Path(sdk) / "build-tools" / "35.0.0" / (name + suffix)
    if not tool.is_file():
        raise RuntimeError("Install Android build-tools;35.0.0")
    return str(tool)


def git(*args, cwd=ROOT):
    return subprocess.check_output(["git", *args], cwd=cwd)
