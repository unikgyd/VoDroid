"""Package the built APK, matching Magisk module and corresponding source."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path
from project import ROOT, NATIVE, REPOSITORY, git, sdk_tool, version


def source_files():
    for record in git("ls-files", "--stage", "-z").split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, commit, stage = metadata.decode().split()
        name = raw_path.decode("utf-8")
        if stage != "0":
            raise RuntimeError("Resolve Git conflicts before packaging")
        if name.split("/", 1)[0] in {"artifacts", "references", "signing", ".git", ".codex", ".agents"}:
            raise RuntimeError("Local/private directory is still in the index: " + name)
        if mode == "160000":
            if NATIVE.get(name) != commit or git("rev-parse", "HEAD", cwd=ROOT / name).decode().strip() != commit:
                raise RuntimeError("Native dependency revision differs: " + name)
            if git("status", "--porcelain", cwd=ROOT / name).strip():
                raise RuntimeError("Native dependency has uncommitted changes: " + name)
            for child in git("ls-files", "-z", cwd=ROOT / name).split(b"\0"):
                if child:
                    path = name + "/" + child.decode("utf-8")
                    yield path, ROOT / path
        else:
            yield name, ROOT / name


def package(variant="debug", name=None, output=None, include_source=False):
    if include_source:
        from check_repo import main as check_publication
        check_publication()
    configured_name, code = version()
    name = name or configured_name
    if not re.fullmatch(r"[0-9A-Za-z.-]+", name):
        raise ValueError("Invalid package version")
    output = Path(output or ROOT / "artifacts").resolve()
    output.mkdir(parents=True, exist_ok=True)
    apk = ROOT / "app/build/outputs/apk" / variant / f"app-{variant}.apk"
    if not apk.is_file():
        raise RuntimeError("Build the APK before packaging")
    badging = subprocess.check_output([sdk_tool("aapt"), "dump", "badging", str(apk)], text=True, encoding="utf-8")
    if f"versionCode='{code}'" not in badging or f"versionName='{name}'" not in badging or "sdkVersion:'33'" not in badging:
        raise RuntimeError("APK version/minSdk does not match package metadata")
    if variant == "release" and "application-debuggable" in badging:
        raise RuntimeError("Refusing to publish a debuggable APK as a release")
    if "targetSdkVersion:'35'" not in badging:
        raise RuntimeError("APK targetSdk does not match build metadata")
    with zipfile.ZipFile(apk) as archive:
        abis = {entry.split("/")[1] for entry in archive.namelist() if entry.startswith("lib/") and entry.endswith(".so")}
        if abis != {"arm64-v8a", "x86_64"}:
            raise RuntimeError("APK native ABIs do not match build metadata")
    signature = subprocess.check_output([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    certificate = re.search(r"Signer #1 certificate SHA-256 digest: (\w+)", signature)
    if certificate is None or "Verified using v2 scheme (APK Signature Scheme v2): true" not in signature:
        raise RuntimeError("APK signature verification failed")
    target_apk = output / ("vodroid-debug.apk" if variant == "debug" else f"vodroid-{name}.apk")
    shutil.copyfile(apk, target_apk)
    module = output / ("vodroid-magisk.zip" if variant == "debug" else f"vodroid-magisk-{name}.zip")
    with zipfile.ZipFile(module, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((ROOT / "magisk").rglob("*")):
            if path.is_file():
                relative = path.relative_to(ROOT / "magisk").as_posix()
                if relative == "module.prop":
                    data = re.sub(r"(?m)^version=.*$", "version=" + name, path.read_text())
                    data = re.sub(r"(?m)^versionCode=.*$", "versionCode=" + str(code), data)
                    archive.writestr(relative, data)
                else:
                    archive.write(path, relative)
        archive.write(target_apk, "system/priv-app/VoDroid/VoDroid.apk")
        archive.write(ROOT / "LICENSE", "LICENSE")
        archive.write(ROOT / "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")
    products = [target_apk, module]
    if include_source:
        source = output / f"vodroid-{name}-source.zip"
        with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
            for relative, path in sorted(source_files()):
                entry = zipfile.ZipInfo.from_file(path, "VoDroid/" + relative)
                entry.external_attr = (0o100755 if relative == "gradlew" or relative.endswith(".sh") else 0o100644) << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, path.read_bytes())
        products.append(source)
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    info = output / "BUILD_INFO.json"
    info.write_text(json.dumps({"repository": REPOSITORY, "versionName": name, "versionCode": code, "variant": variant,
        "commit": commit.stdout.strip() if commit.returncode == 0 else "uncommitted-initial-tree",
        "certificateSha256": certificate.group(1), "minSdk": 33, "targetSdk": 35,
        "abis": ["arm64-v8a", "x86_64"], "nativeDependencies": NATIVE}, indent=2) + "\n", encoding="utf-8")
    products.append(info)
    sums = []
    for path in products:
        sums.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name)
    (output / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="ascii")
    with zipfile.ZipFile(module) as archive:
        if archive.read("system/priv-app/VoDroid/VoDroid.apk") != target_apk.read_bytes():
            raise RuntimeError("Module APK does not match standalone APK")
    print("Packaged and verified " + ", ".join(path.name for path in products))
    return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    parser.add_argument("--version")
    parser.add_argument("--out")
    parser.add_argument("--source", action="store_true")
    args = parser.parse_args()
    package(args.variant, args.version, args.out, args.source)
